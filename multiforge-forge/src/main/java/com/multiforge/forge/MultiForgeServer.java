package com.multiforge.forge;

import com.multiforge.api.MultiForgeApiProvider;
import com.multiforge.forge.api.MultiForgeApiImpl;
import com.multiforge.forge.chunk.ChunkManager;
import com.multiforge.forge.chunk.ChunkStorageRegistry;
import com.multiforge.forge.chunk.ChunkSyncIo;
import com.multiforge.forge.config.MultiForgeConfig;
import com.multiforge.forge.network.MasterConnection;
import com.multiforge.forge.peer.PeerManager;
import com.multiforge.forge.peer.PeerServer;
import com.multiforge.forge.peer.packet.EntityChunkChangedPacket;
import com.multiforge.forge.player.PlayerSyncManager;
import com.multiforge.forge.world.FileSyncer;
import com.multiforge.forge.world.WorldSyncManager;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.messages.masterbound.ReadLevelMessage;
import com.multiforge.protocol.messages.masterbound.ReadUidMessage;
import com.multiforge.protocol.messages.masterbound.SetPortMessage;
import com.multiforge.protocol.messages.masterbound.StartMessage;
import com.multiforge.protocol.messages.masterbound.WriteLevelMessage;
import com.multiforge.protocol.messages.masterbound.WriteUidMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime facade of MultiForge on a Forge server.
 *
 * <p>There is exactly one instance per running server. It owns the network
 * connections ({@link MasterConnection}, {@link PeerServer}, {@link PeerManager}),
 * the chunk system ({@link ChunkManager}, {@link ChunkSyncIo}) and the world/player
 * synchronization managers, and it exposes them to the mixins, commands and the
 * public API.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #bootstrap(MinecraftServer)} runs before {@code level.dat} is read
 *       (mixin on {@code MinecraftServer.loadLevel}). It connects to the master,
 *       downloads {@code level.dat}/{@code uid.dat} and starts the peer listener.</li>
 *   <li>{@link #tick()} runs at the end of every server tick.</li>
 *   <li>{@link #beginStop()} runs when {@code stopServer} starts: ownership and
 *       ticking stop, but chunk uploads continue so the final save is durable.</li>
 *   <li>{@link #shutdown(String)} runs when {@code stopServer} finishes: waits for
 *       uploads, closes every socket and clears the registries.</li>
 * </ol>
 *
 * <p>Threading: {@link #execute(Runnable)} marshals work onto the server thread.
 * All fields are final or volatile; managers are thread-safe by contract.</p>
 */
public final class MultiForgeServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge");

    private static volatile MultiForgeServer instance;

    private final MinecraftServer server;
    private final MultiForgeConfig.Snapshot config;
    private final String serverName;

    private final MasterConnection master;
    private final PeerManager peers;
    private final PeerServer peerServer;
    private final ChunkManager chunks;
    private final ChunkSyncIo chunkIo;
    private final WorldSyncManager world;
    private final PlayerSyncManager players;
    private final FileSyncer files;
    private final MultiForgeApiImpl api;

    private final Map<String, ServerLevel> levels = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean ioClosed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile int tickCounter;
    private volatile boolean announced;

    private MultiForgeServer(MinecraftServer server, MultiForgeConfig.Snapshot config, String serverName) {
        this.server = server;
        this.config = config;
        this.serverName = serverName;
        this.master = new MasterConnection(this);
        this.peers = new PeerManager(this);
        this.peerServer = new PeerServer(this, config.peerPort());
        this.chunks = new ChunkManager(this);
        this.chunkIo = new ChunkSyncIo(this);
        this.world = new WorldSyncManager(this);
        this.players = new PlayerSyncManager(this);
        this.files = new FileSyncer(this);
        this.api = new MultiForgeApiImpl(this);
    }

    /**
     * Creates and starts the runtime once. Called from the {@code loadLevel} mixin,
     * before the world data is read.
     *
     * @param server server that is starting
     */
    public static synchronized void bootstrap(MinecraftServer server) {
        if (instance != null) {
            return;
        }
        MultiForgeConfig.bake();
        MultiForgeConfig.Snapshot snapshot = MultiForgeConfig.snapshotForStartup(
                server.getServerDirectory().toPath());
        String name = snapshot.serverName();
        if (name == null || name.isBlank()) {
            name = "server" + ThreadLocalRandom.current().nextInt(10000, 100000);
        }
        MultiForgeServer runtime = new MultiForgeServer(server, snapshot, name);
        instance = runtime;
        MultiForgeApiProvider.setApi(runtime.api);
        runtime.start();
    }

    private void start() {
        LOGGER.info("Starting MultiForge as '{}' (master {}), sync: chunks={}, entities={}, players={}, world={}",
                serverName, config.masterAddress(), config.chunkSync(), config.entitySync(),
                config.playerSync(), config.worldSync());

        peers.start();
        try {
            int port = peerServer.start();
            LOGGER.info("Peer-to-peer listener bound to port {}", port);
        } catch (RuntimeException e) {
            LOGGER.error("Could not bind the peer listener; peer transfers are disabled", e);
        }

        master.start();

        // World data must be fetched before vanilla reads level.dat.
        if (config.syncLevelDat() && awaitMaster()) {
            syncWorldFilesAtStartup();
        } else if (config.syncLevelDat()) {
            LOGGER.warn("Master unreachable, starting from the local world files (standalone mode)");
        }
        // User lists are read by the dedicated server while it starts; adopting the
        // cluster copies here means the next start uses them even if this server
        // never had them. Live changes are uploaded by FileSyncer.tick().
        if (master.isConnected()) {
            files.reconcileJsonFilesAtStartup();
        }

        announce();
    }

    /**
     * Announces this server's peer port to the master. Sent again after every
     * reconnect because the master forgets the port when the connection drops.
     */
    private void announce() {
        if (!master.isConnected() || announced) {
            return;
        }
        announced = true;
        int port = peerServer.getBoundPort();
        if (port > 0) {
            master.send(new SetPortMessage(port));
        }
        String host = config.advertiseHost();
        if (host == null || host.isBlank()) {
            host = server.getLocalIp();
        }
        master.send(new StartMessage(host, port));
    }

    private boolean awaitMaster() {
        long deadline = System.currentTimeMillis() + config.ioTimeoutSeconds() * 1000L;
        while (!master.isConnected() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return master.isConnected();
    }

    /**
     * Downloads {@code level.dat} and {@code uid.dat} from the master before the
     * world is loaded. The master is authoritative: without this every server
     * would generate its own world seed and UUID.
     */
    private void syncWorldFilesAtStartup() {
        String world = com.multiforge.forge.common.WorldDirectories.baseWorldName(server);
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path uidDat = server.getWorldPath(LevelResource.ROOT).resolve("uid.dat");

        byte[] level = fetch(new ReadLevelMessage(world));
        if (level.length > 0) {
            try {
                Files.createDirectories(levelDat.getParent());
                Files.write(levelDat, level);
                LOGGER.info("Loaded level.dat ({} bytes) from the master", level.length);
            } catch (IOException e) {
                LOGGER.error("Failed to write level.dat", e);
            }
        } else if (Files.isRegularFile(levelDat)) {
            try {
                master.send(new WriteLevelMessage(world, Files.readAllBytes(levelDat)));
                LOGGER.info("Master had no level.dat, uploaded the local one");
            } catch (IOException e) {
                LOGGER.warn("Could not upload level.dat", e);
            }
        }

        byte[] uid = fetch(new ReadUidMessage(world));
        if (uid.length > 0) {
            try {
                Files.write(uidDat, uid);
                LOGGER.info("Loaded uid.dat from the master");
            } catch (IOException e) {
                LOGGER.error("Failed to write uid.dat", e);
            }
        } else if (Files.isRegularFile(uidDat)) {
            try {
                master.send(new WriteUidMessage(world, Files.readAllBytes(uidDat)));
            } catch (IOException e) {
                LOGGER.warn("Could not upload uid.dat", e);
            }
        }
    }

    private byte[] fetch(com.multiforge.protocol.messages.masterbound.MasterBoundMessage request) {
        try {
            DataMessageReply reply = master.request(request, DataMessageReply.class)
                    .get(config.ioTimeoutSeconds(), TimeUnit.SECONDS);
            return reply.data;
        } catch (Exception e) {
            LOGGER.warn("Could not fetch {} from the master: {}", request.getClass().getSimpleName(), e.toString());
            return new byte[0];
        }
    }

    /**
     * Registers a level as soon as it was constructed.
     *
     * @param level new level
     */
    public void onLevelCreated(ServerLevel level) {
        String world = chunks.worldName(level);
        levels.put(world, level);
        LOGGER.info("Tracking world {} ({})", world, level.dimension().location());
    }

    /**
     * Called once per server tick by the mod event handler.
     */
    public void tick() {
        if (!started.get()) {
            return;
        }
        tickCounter++;
        chunks.tick();
        world.tick();
        files.tick();
    }

    /**
     * Called by the mod when the server finished starting. Uploads the file set and
     * marks the server as ready.
     */
    public void onServerStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        announce();
        files.onServerStarted();
        LOGGER.info("MultiForge ready ({} worlds, port {})", levels.size(), peerServer.getBoundPort());
    }

    /** Called when the master connection is (re)established. */
    public void onMasterConnected() {
        LOGGER.info("Master connection established, re-subscribing chunks");
        announce();
        world.invalidate();
        chunks.onMasterReconnected();
    }

    /** Called when the master connection dropped. */
    public void onMasterDisconnected() {
        announced = false;
        LOGGER.warn("Master connection lost; chunk synchronization paused until reconnect");
    }

    /**
     * Called when the master reports that another server started.
     *
     * @param name peer name
     * @param host peer host
     * @param port peer port
     */
    public void onServerStarted(String name, String host, int port) {
        if (!name.equals(serverName)) {
            LOGGER.info("Peer {} is online at {}:{}", name, host, port);
        }
    }

    /**
     * Called when the master sent a TPS/player update.
     *
     * @param name server name
     */
    public void onServerInfoUpdated(String name) {
        // No cached derived state yet; the API reads the master's snapshots directly.
    }

    /**
     * Called after entity data was uploaded, so peers can invalidate cached views.
     *
     * @param key chunk whose entity data changed
     */
    public void onEntitiesUploaded(ChunkKey key) {
        peers.broadcast(new EntityChunkChangedPacket(key.getName(), key.getX(), key.getZ()));
    }

    /**
     * Schedules work on the server thread. Safe to call from any thread.
     *
     * @param task task to execute
     */
    public void execute(Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else {
            server.executeIfPossible(task);
        }
    }

    /**
     * @param world logical world name
     * @return level, or {@code null} when the world is not loaded
     */
    public ServerLevel level(String world) {
        ServerLevel level = levels.get(world);
        if (level != null) {
            return level;
        }
        for (ServerLevel candidate : server.getAllLevels()) {
            if (chunks.worldName(candidate).equals(world)) {
                levels.put(world, candidate);
                return candidate;
            }
        }
        return null;
    }

    /** Marks the server as stopping: no new ownership or ticking decisions. */
    public void beginStop() {
        if (stopping.compareAndSet(false, true)) {
            LOGGER.info("Server stopping: disabling chunk ownership and ticking");
        }
    }

    /**
     * Requests a clean server shutdown (used by the master's shutdown message).
     *
     * @param reason reason for logging
     */
    public void requestShutdown(String reason) {
        LOGGER.warn("Shutdown requested: {}", reason);
        execute(() -> server.halt(false));
    }

    /**
     * Final shutdown: flushes pending uploads and closes every connection. Called
     * after vanilla finished saving the world.
     *
     * @param reason reason for logging
     */
    public synchronized void shutdown(String reason) {
        if (ioClosed.getAndSet(true)) {
            return;
        }
        LOGGER.info("Stopping MultiForge ({})", reason);
        boolean flushed = chunkIo.flush(Math.max(5_000L, config.ioTimeoutSeconds() * 1000L));
        if (!flushed) {
            LOGGER.warn("Shutdown finished with {} pending chunk uploads", chunkIo.pendingWriteCount());
        }
        chunkIo.close();
        // The read-only world files are written by vanilla after beginStop(); upload
        // them (and any pending real-time files) while the master channel is alive.
        files.onShutdown();
        peers.shutdown();
        peerServer.stop();
        master.stop();
        players.clear();
        chunks.clear();
        levels.clear();
        ChunkStorageRegistry.clear();
        instance = null;
        MultiForgeApiProvider.reset();
    }

    /**
     * @return the running runtime, or {@code null} when MultiForge is not active
     */
    public static MultiForgeServer instanceOrNull() {
        return instance;
    }

    /**
     * @return the running runtime
     * @throws IllegalStateException when MultiForge is not active
     */
    public static MultiForgeServer instance() {
        MultiForgeServer current = instance;
        if (current == null) {
            throw new IllegalStateException("MultiForge is not running");
        }
        return current;
    }

    /** @return {@code true} while the runtime is active */
    public static boolean isActive() {
        return instance != null;
    }

    /** @return the Minecraft server */
    public MinecraftServer server() {
        return server;
    }

    /** @return the baked configuration */
    public MultiForgeConfig.Snapshot config() {
        return config;
    }

    /** @return this server's cluster name */
    public String serverName() {
        return serverName;
    }

    /** @return master control connection */
    public MasterConnection master() {
        return master;
    }

    /** @return peer connection manager */
    public PeerManager peers() {
        return peers;
    }

    /** @return chunk coordination */
    public ChunkManager chunks() {
        return chunks;
    }

    /** @return chunk data plane */
    public ChunkSyncIo chunkIo() {
        return chunkIo;
    }

    /** @return world state synchronization */
    public WorldSyncManager world() {
        return world;
    }

    /** @return player synchronization */
    public PlayerSyncManager players() {
        return players;
    }

    /** @return file synchronization */
    public FileSyncer files() {
        return files;
    }

    /** @return public API implementation */
    public MultiForgeApiImpl api() {
        return api;
    }

    /** @return peer listener */
    public PeerServer peerServer() {
        return peerServer;
    }

    /** @return whether the server began stopping */
    public boolean isStopping() {
        return stopping.get();
    }

    /** @return whether the runtime closed its IO paths */
    public boolean isIoClosed() {
        return ioClosed.get();
    }

    /** @return average tick time in milliseconds as reported by Forge */
    public float averageTickTimeMillis() {
        return server.getAverageTickTime();
    }

    /**
     * @return server TPS, capped at 20 and never negative
     */
    public double tps() {
        float average = averageTickTimeMillis();
        if (average <= 0.0F) {
            return 20.0D;
        }
        return Math.min(20.0D, Math.max(0.0D, 1000.0D / Math.max(50.0F, average)));
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MultiForgeServer[%s, ownedChunks=%d, loadedChunks=%d, peers=%d]",
                serverName, chunks.ownedChunkCount(), chunks.loadedChunkCount(), peers.connections().size());
    }
}
