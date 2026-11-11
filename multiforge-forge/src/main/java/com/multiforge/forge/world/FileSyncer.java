package com.multiforge.forge.world;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.protocol.messages.masterbound.DownloadFileMessage;
import com.multiforge.protocol.messages.masterbound.ReadJsonMessage;
import com.multiforge.protocol.messages.masterbound.RequestFilesToSyncMessage;
import com.multiforge.protocol.messages.masterbound.UploadFileMessage;
import com.multiforge.protocol.messages.masterbound.WriteJsonMessage;
import com.multiforge.protocol.messages.masterbound.WriteLevelMessage;
import com.multiforge.protocol.messages.masterbound.WriteUidMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.protocol.messages.serverbound.DataUpdateMessage;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.protocol.messages.serverbound.FilesToSyncMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors MultiPaper's {@code MultiPaperFileSyncer}: keeps arbitrary server files,
 * the user lists and {@code level.dat}/{@code uid.dat} in sync through the master.
 *
 * <p>The master stores shared files below its {@code synced-server-files} directory
 * ({@link UploadFileMessage}, {@link DownloadFileMessage}, {@link FileContentMessage});
 * the JSON user lists and the world files use dedicated messages and live directly
 * in the master's storage root ({@link WriteJsonMessage}, {@link WriteLevelMessage},
 * {@link WriteUidMessage}). This class owns both paths:</p>
 *
 * <ul>
 *   <li><b>startup</b> ({@link #onServerStarted()}): asks the master for the list of
 *       shared files ({@link RequestFilesToSyncMessage}), downloads files that are
 *       missing or older locally, uploads trees configured in
 *       {@code filesToSyncOnStartup}, and reconciles the JSON user lists.</li>
 *   <li><b>real time</b> ({@link #tick()}): scans {@code filesToSyncInRealTime}
 *       once per second and uploads files whose modification time changed. The
 *       master immediately forwards such uploads to every other server, which
 *       writes them in {@link #onFileContent(FileContentMessage)}.</li>
 *   <li><b>world files</b>: {@code level.dat} and {@code uid.dat} are uploaded via
 *       their dedicated messages whenever their content hash changes (vanilla
 *       rewrites {@code level.dat} on every autosave even when nothing changed, so
 *       the mtime alone is not a useful signal) and once more during shutdown.</li>
 * </ul>
 *
 * <p>Path handling: every relative path is resolved against the server directory
 * and normalised; entries that escape it are rejected. {@code filesToNotSync}
 * prefixes are honoured for both the startup upload and the periodic scan.</p>
 *
 * <p>Threading: {@link #onFileContent(FileContentMessage)} and
 * {@link #onDataUpdate(DataUpdateMessage)} run on the Netty thread and only touch
 * concurrent collections; all other methods run on the server thread. Disk and
 * network work is dispatched to a single daemon IO thread so neither the server
 * tick nor the Netty event loop blocks on file IO.</p>
 */
public final class FileSyncer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Files");

    /** User lists MultiPaper stores in the master root and exposes via JSON messages. */
    private static final List<String> JSON_FILES = List.of(
            "whitelist.json", "ops.json", "banned-players.json", "banned-ips.json");

    /** Maximum difference between two timestamps that still counts as "same file". */
    private static final long MTIME_TOLERANCE_MILLIS = 2_000L;

    /** Suffixes that are never synchronized (partially written or diagnostic files). */
    private static final List<String> IGNORED_SUFFIXES = List.of(".tmp", ".hprof");

    private final MultiForgeServer server;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MultiForge-FileSync");
        thread.setDaemon(true);
        return thread;
    });

    /** Relative path -> local modification time at the last successful upload. */
    private final Map<String, Long> uploadedModificationTimes = new ConcurrentHashMap<>();
    /** Relative paths currently being written from the network; the scanner skips them. */
    private final Set<String> pendingWrites = ConcurrentHashMap.newKeySet();
    /** JSON file name -> content hash at the last upload. */
    private final Map<String, Long> uploadedJsonHashes = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int tickCounter;
    private volatile long lastLevelDatHash = Long.MIN_VALUE;
    private volatile long lastUidDatHash = Long.MIN_VALUE;

    /**
     * @param server owning MultiForge server; provides the master connection, the
     *               configuration and the server directory
     */
    public FileSyncer(MultiForgeServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts file synchronization once the server accepted players.
     *
     * <p>Called from {@link MultiForgeServer#onServerStarted()} on the server
     * thread. The actual network/file work is queued on the IO thread.</p>
     */
    public void onServerStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        io.execute(this::reconcileWithMaster);
    }

    /**
     * Reconciles the JSON user lists with the master.
     *
     * <p>Called during bootstrap, before the master is used for chunk IO. A file
     * that exists on the master is written locally; otherwise the local file is
     * uploaded so the cluster adopts it. This method blocks the calling (server)
     * thread for at most the configured IO timeout, like the {@code level.dat}
     * download in {@link MultiForgeServer#bootstrap(MinecraftServer)}. It is a
     * no-op when JSON sync is disabled or the master is unreachable.</p>
     *
     * <p>Forge reads the user lists once while starting, after the bootstrap
     * hook, so a freshly joined server picks up the cluster lists on its next
     * start. Live list changes are uploaded to the master but are not injected
     * into a running {@code PlayerList}; see KNOWN_ISSUES.md.</p>
     */
    public void reconcileJsonFilesAtStartup() {
        if (!server.config().syncJsonFiles() || !server.master().isConnected()) {
            return;
        }
        for (String name : JSON_FILES) {
            try {
                DataMessageReply reply = server.master().request(new ReadJsonMessage(name), DataMessageReply.class)
                        .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
                Path local = resolve(name);
                if (reply.data.length > 0) {
                    writeAtomically(local, reply.data, -1L, name);
                    if (server.config().debugLogging()) {
                        LOGGER.info("Adopted {} ({} bytes) from the master", name, reply.data.length);
                    }
                } else if (Files.isRegularFile(local)) {
                    uploadJson(name, Files.readAllBytes(local));
                }
            } catch (Exception e) {
                LOGGER.debug("Could not reconcile {} at startup: {}", name, e.toString());
            }
        }
    }

    /**
     * Flushes pending file uploads and the final {@code level.dat}/{@code uid.dat}
     * before the master connection is closed.
     *
     * <p>Called from {@link MultiForgeServer#shutdown(String)} after vanilla wrote
     * its final save. Waits at most the configured IO timeout for the IO thread so
     * a hanging disk does not prevent the server from stopping.</p>
     */
    public void onShutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        io.execute(() -> {
            // Awaited: the master connection is closed right after this method, so a
            // fire-and-forget write could be dropped while still queued.
            uploadWorldFiles(true, true);
            scanRealTimeFiles(true);
        });
        io.shutdown();
        try {
            if (!io.awaitTermination(Math.max(5_000L, server.config().ioTimeoutSeconds() * 1_000L),
                    TimeUnit.MILLISECONDS)) {
                LOGGER.warn("File synchronization did not finish before shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the periodic real-time scan. Must be called on the server thread once
     * per tick; the actual scan happens on the IO thread at most once per second.
     */
    public void tick() {
        if (!started.get() || closed.get() || !server.master().isConnected()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        try {
            io.execute(this::scanRealTimeFiles);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The server is stopping and the IO executor was already shut down.
        }
    }

    // ------------------------------------------------------------------
    // Startup reconciliation
    // ------------------------------------------------------------------

    /**
     * Compares the master's shared-file index with the local files, downloads what
     * is missing or older, then uploads the configured trees.
     */
    private void reconcileWithMaster() {
        if (!server.master().isConnected()) {
            LOGGER.debug("Master unavailable, skipping the initial file reconciliation");
            return;
        }
        try {
            FilesToSyncMessage listing = server.master().request(new RequestFilesToSyncMessage(),
                    FilesToSyncMessage.class).get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            Set<String> known = new LinkedHashSet<>();
            for (FilesToSyncMessage.FileToSync remote : listing.filesToSync) {
                known.add(remote.path());
                if (shouldSkip(remote.path())) {
                    continue;
                }
                Path local = resolve(remote.path());
                long localModified = Files.isRegularFile(local) ? Files.getLastModifiedTime(local).toMillis() : -1L;
                if (localModified < 0 || Math.abs(localModified - remote.lastModified()) > MTIME_TOLERANCE_MILLIS) {
                    download(remote.path(), remote.lastModified());
                }
            }
            for (String configured : server.config().filesToSyncInRealTime()) {
                uploadTree(configured, known, true, false, false);
            }
            for (String configured : server.config().filesToSyncOnStartup()) {
                uploadTree(configured, known, false, false, false);
            }
            reconcileJsonFilesAtStartup();
            uploadWorldFiles(false, false);
            LOGGER.info("File synchronization ready ({} shared files on the master)", listing.filesToSync.length);
        } catch (Exception e) {
            LOGGER.warn("Initial file reconciliation failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Periodic scan
    // ------------------------------------------------------------------

    /**
     * Scans the real-time trees once and uploads files whose modification time
     * changed since the last upload. Also re-uploads changed JSON user lists and
     * world files.
     */
    private void scanRealTimeFiles() {
        scanRealTimeFiles(false);
    }

    /**
     * @param await {@code true} to wait for the master to acknowledge every upload
     *              (shutdown), {@code false} to fire and forget (periodic scan)
     */
    private void scanRealTimeFiles(boolean await) {
        if (!server.master().isConnected()) {
            return;
        }
        for (String configured : server.config().filesToSyncInRealTime()) {
            uploadTree(configured, Set.of(), true, true, await);
        }
        for (String name : JSON_FILES) {
            uploadJsonIfChanged(name, await);
        }
        uploadWorldFiles(false, await);
    }

    /**
     * Uploads a file or directory tree.
     *
     * @param configured      path relative to the server directory
     * @param known           paths the master already has; when {@code onlyChanged}
     *                        is false they are skipped to avoid duplicate uploads
     * @param immediatelySync whether the master should push the file to peers
     * @param onlyChanged     {@code true} to upload only files whose mtime changed
     * @param await           {@code true} to wait for the master's acknowledgement
     */
    private void uploadTree(String configured, Set<String> known, boolean immediatelySync, boolean onlyChanged,
                            boolean await) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path root;
        try {
            root = resolve(configured);
        } catch (RuntimeException e) {
            LOGGER.warn("Ignoring invalid sync path '{}': {}", configured, e.getMessage());
            return;
        }
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String relative = relativize(file);
                if (shouldSkip(relative) || pendingWrites.contains(relative)) {
                    return;
                }
                Long uploaded = uploadedModificationTimes.get(relative);
                long modified;
                try {
                    modified = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    return;
                }
                if (onlyChanged && uploaded != null && uploaded == modified) {
                    return;
                }
                if (!onlyChanged && known.contains(relative)) {
                    uploadedModificationTimes.put(relative, modified);
                    return;
                }
                if (upload(relative, file, immediatelySync, await)) {
                    uploadedModificationTimes.put(relative, modified);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Could not scan '{}': {}", configured, e.toString());
        }
    }

    /**
     * @param relative        normalized relative path
     * @param file            file to upload
     * @param immediatelySync whether peers must receive the content immediately
     * @param await           {@code true} to wait for the acknowledgement
     * @return {@code true} when the upload was handed to the master
     */
    private boolean upload(String relative, Path file, boolean immediatelySync, boolean await) {
        try {
            byte[] data = Files.readAllBytes(file);
            UploadFileMessage message = new UploadFileMessage(immediatelySync, relative,
                    Files.getLastModifiedTime(file).toMillis(), data);
            if (await) {
                server.master().request(message, BooleanMessageReply.class)
                        .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            } else {
                server.master().send(message);
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Uploaded {} ({} bytes, immediate={})", relative, data.length, immediatelySync);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not read '{}' for upload: {}", relative, e.toString());
            return false;
        } catch (Exception e) {
            LOGGER.warn("Could not upload '{}': {}", relative, e.toString());
            return false;
        }
    }

    /**
     * Uploads a JSON user list when its content changed.
     *
     * @param name  file name relative to the server directory
     * @param await {@code true} to wait for the master's acknowledgement
     */
    private void uploadJsonIfChanged(String name, boolean await) {
        Path local = resolve(name);
        if (!Files.isRegularFile(local)) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(local);
            long hash = hash(data);
            Long previous = uploadedJsonHashes.get(name);
            if (previous != null && previous == hash) {
                return;
            }
            if (uploadJson(name, data, await)) {
                uploadedJsonHashes.put(name, hash);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {}: {}", name, e.toString());
        }
    }

    /**
     * @param name file name relative to the master root
     * @param data serialized JSON
     * @return {@code true} when the message was sent
     */
    private boolean uploadJson(String name, byte[] data) {
        return uploadJson(name, data, false);
    }

    /**
     * @param name file name relative to the master root
     * @param data serialized JSON
     * @param await {@code true} to wait for the master's acknowledgement
     * @return {@code true} when the message was sent
     */
    private boolean uploadJson(String name, byte[] data, boolean await) {
        if (!server.config().syncJsonFiles()) {
            return false;
        }
        WriteJsonMessage message = new WriteJsonMessage(name, data);
        if (await) {
            try {
                server.master().request(message, BooleanMessageReply.class)
                        .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("Could not upload user list {}: {}", name, e.toString());
                return false;
            }
        } else {
            server.master().send(message);
        }
        uploadedJsonHashes.put(name, hash(data));
        if (server.config().debugLogging()) {
            LOGGER.info("Uploaded user list {} ({} bytes)", name, data.length);
        }
        return true;
    }

    /**
     * Uploads {@code level.dat} and {@code uid.dat} when their content changed.
     *
     * @param force upload even when the content hash is unchanged (shutdown)
     * @param await {@code true} to wait for the master's acknowledgement
     */
    private void uploadWorldFiles(boolean force, boolean await) {
        if (!server.config().syncLevelDat() || !server.master().isConnected()) {
            return;
        }
        String world = com.multiforge.forge.common.WorldDirectories.baseWorldName(server.server());
        Path levelDat = server.server().getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path uidDat = server.server().getWorldPath(LevelResource.ROOT).resolve("uid.dat");
        uploadWorldFile(world, levelDat, true, force, await);
        uploadWorldFile(world, uidDat, false, force, await);
    }

    /**
     * @param world    logical world name
     * @param file     file to upload
     * @param isLevel  {@code true} for {@code level.dat}, {@code false} for {@code uid.dat}
     * @param force    upload even when unchanged
     * @param await    {@code true} to wait for the master's acknowledgement
     */
    private void uploadWorldFile(String world, Path file, boolean isLevel, boolean force, boolean await) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            long hash = hash(data);
            long previous = isLevel ? lastLevelDatHash : lastUidDatHash;
            if (!force && hash == previous) {
                return;
            }
            var message = isLevel ? new WriteLevelMessage(world, data) : new WriteUidMessage(world, data);
            if (await) {
                server.master().request(message, BooleanMessageReply.class)
                        .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            } else {
                server.master().send(message);
            }
            if (isLevel) {
                lastLevelDatHash = hash;
            } else {
                lastUidDatHash = hash;
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Uploaded {} ({} bytes)", file.getFileName(), data.length);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not upload {}: {}", file.getFileName(), e.toString());
        } catch (Exception e) {
            LOGGER.warn("Could not upload {}: {}", file.getFileName(), e.toString());
        }
    }


    // ------------------------------------------------------------------
    // Network callbacks
    // ------------------------------------------------------------------

    /**
     * Handles a file the master pushed because another server uploaded it.
     *
     * <p>Runs on the Netty thread. Registers an inbound data stream and streams
     * the content into a temporary file next to the destination; the final rename
     * happens when the close marker arrives, so readers never observe a partially
     * written file. The path is relative to the server directory.</p>
     *
     * @param message announcement of the pushed file and its stream id
     */
    public void onFileContent(FileContentMessage message) {
        String path = normalizeSharedPath(message.path);
        if (path == null || shouldSkip(path)) {
            return;
        }
        downloadStream(resolve(path), path, message.lastModified, message.streamId);
    }

    /**
     * Applies a shared-data update broadcast by the master.
     *
     * <p>MultiPaper writes {@code WriteDataMessage} payloads (custom data files)
     * to the master root and forwards them to the other servers. The path is
     * resolved against the server directory and written atomically. Runs on the
     * Netty thread; the write is small enough to perform inline, matching the
     * upstream behaviour. The new modification time is recorded as already
     * uploaded so the periodic scanner does not echo it back.</p>
     *
     * @param message path and content of the updated file
     */
    public void onDataUpdate(DataUpdateMessage message) {
        String path = normalizeSharedPath(message.path);
        if (path == null || shouldSkip(path)) {
            return;
        }
        try {
            writeAtomically(resolve(path), message.data, -1L, path);
            if (server.config().debugLogging()) {
                LOGGER.info("Applied data update for {} ({} bytes)", path, message.data.length);
            }
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("Could not apply data update for {}: {}", path, e.toString());
        }
    }

    /**
     * Downloads a shared file from the master into the server directory.
     *
     * <p>Backs {@code /multiforge download <path>}. The download is asynchronous;
     * progress is only visible in the log. A path that escapes the server
     * directory is rejected.</p>
     *
     * @param path path relative to the master's shared file root
     */
    public void downloadFile(String path) {
        if (!server.master().isConnected()) {
            LOGGER.warn("Cannot download {}: master is not connected", path);
            return;
        }
        try {
            io.execute(() -> download(path, -1L));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOGGER.warn("Cannot download {}: the server is stopping", path);
        }
    }

    // ------------------------------------------------------------------
    // Download helpers
    // ------------------------------------------------------------------

    /**
     * Requests a shared file and streams it to disk.
     *
     * @param path         relative path
     * @param lastModified modification time reported by the master, or {@code -1}
     */
    private void download(String path, long lastModified) {
        if (!server.master().isConnected()) {
            return;
        }
        try {
            FileContentMessage content = server.master().request(new DownloadFileMessage(path),
                    FileContentMessage.class).get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            downloadStream(resolve(path), path, lastModified > 0 ? lastModified : content.lastModified,
                    content.streamId);
        } catch (Exception e) {
            LOGGER.warn("Could not download '{}': {}", path, e.toString());
        }
    }


    /**
     * Attaches to an inbound data stream and writes it to {@code destination}.
     *
     * <p>The stream is registered immediately: the master starts sending frames
     * right after the announcement, and {@code DataStreamManager} replays frames
     * that arrived before registration. The rename from the temporary file to the
     * destination happens on the close marker, which is the only point at which
     * the content is known to be complete.</p>
     *
     * @param destination  final file
     * @param relative     relative path, used for logging and echo suppression
     * @param lastModified modification time to apply, or {@code -1}
     * @param streamId     stream announced by the master
     */
    private void downloadStream(Path destination, String relative, long lastModified, int streamId) {
        if (!server.master().isConnected()) {
            return;
        }
        Path parent = destination.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = destination.resolveSibling(destination.getFileName() + ".mfdownload");
            pendingWrites.add(relative);
            FileOutputStream out = new FileOutputStream(temp.toFile());
            java.util.concurrent.atomic.AtomicReference<IOException> writeError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // Register the handlers through the four-argument factory: it installs
            // them before DataStreamManager replays frames that arrived while the
            // caller was still processing the announcement. Attaching copyToAsync
            // afterwards loses those frames (observed as a zero-byte download).
            server.master().dataStreams().createInboundDataStream(server.master().channel(), streamId,
                    data -> {
                        try {
                            out.write(data);
                        } catch (IOException e) {
                            writeError.compareAndSet(null, e);
                        }
                    },
                    () -> {
                        try {
                            out.close();
                            IOException error = writeError.get();
                            if (error != null) {
                                throw error;
                            }
                            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                            if (lastModified > 0) {
                                destination.toFile().setLastModified(lastModified);
                            }
                            uploadedModificationTimes.put(relative, destination.toFile().lastModified());
                            if (server.config().debugLogging()) {
                                LOGGER.info("Downloaded '{}' ({} bytes)", relative, Files.size(destination));
                            }
                        } catch (IOException e) {
                            LOGGER.warn("Could not store '{}': {}", relative, e.toString());
                            try {
                                Files.deleteIfExists(temp);
                            } catch (IOException ignored) {
                                // best effort cleanup
                            }
                        } finally {
                            pendingWrites.remove(relative);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            pendingWrites.remove(relative);
            LOGGER.warn("Could not start download of '{}': {}", relative, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Path and hash helpers
    // ------------------------------------------------------------------

    /**
     * @param relative path relative to the server directory
     * @return absolute, normalised path inside the server directory
     * @throws IllegalArgumentException when the path escapes the server directory
     */
    Path resolve(String relative) {
        Path root = server.server().getServerDirectory().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the server directory: " + relative);
        }
        return resolved;
    }

    /**
     * @param file file inside the server directory
     * @return path relative to the server directory, using {@code /} separators
     */
    private String relativize(Path file) {
        Path root = server.server().getServerDirectory().toPath().toAbsolutePath().normalize();
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * @param path path received from the master
     * @return the path without a leading shared-file prefix, or {@code null} when
     *         it escapes the server directory
     */
    private String normalizeSharedPath(String path) {
        String normalized = path.replace('\\', '/');
        String prefix = "synced-server-files/";
        if (normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        try {
            resolve(normalized);
            return normalized;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected shared file path '{}': {}", path, e.getMessage());
            return null;
        }
    }


    /**
     * @param relative relative path
     * @return {@code true} when the file must never be synchronized
     */
    private boolean shouldSkip(String relative) {
        String normalized = relative.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String suffix : IGNORED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        for (String excluded : server.config().filesToNotSync()) {
            String prefix = excluded.replace('\\', '/');
            if (prefix.isEmpty()) {
                continue;
            }
            if (normalized.equals(prefix) || normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes bytes to {@code destination} through a temporary sibling file.
     *
     * @param destination  final path
     * @param data         content
     * @param lastModified modification time to apply, or {@code -1}
     * @param relative     relative path for echo suppression
     * @throws IOException when the file cannot be written
     */
    private void writeAtomically(Path destination, byte[] data, long lastModified, String relative)
            throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        pendingWrites.add(relative);
        Path temp = destination.resolveSibling(destination.getFileName() + ".mfreceived");
        try {
            Files.write(temp, data);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            if (lastModified > 0) {
                destination.toFile().setLastModified(lastModified);
            }
            uploadedModificationTimes.put(relative, destination.toFile().lastModified());
        } finally {
            pendingWrites.remove(relative);
            Files.deleteIfExists(temp);
        }
    }

    /**
     * @param data bytes
     * @return 64-bit FNV-1a hash, used only for change detection
     */
    private static long hash(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : data) {
            hash ^= (value & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * @return JSON user list names, for diagnostics and documentation
     */
    public static List<String> jsonFileNames() {
        return JSON_FILES;
    }

    /**
     * @return a diagnostic snapshot of the relative paths uploaded so far
     */
    public Map<String, Long> uploadedFiles() {
        return Map.copyOf(uploadedModificationTimes);
    }

    /**
     * @param name JSON file name
     * @return the local file, for callers that need to inspect the user lists
     */
    public File jsonFile(String name) {
        return resolve(name).toFile();
    }

    /**
     * @param name JSON file name
     * @return UTF-8 content of the user list, or {@code null} when it is missing
     */
    public String readJson(String name) {
        Path path = resolve(name);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}: {}", name, e.toString());
            return null;
        }
    }
}

