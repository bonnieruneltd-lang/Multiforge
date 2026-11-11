package com.multiforge.forge.chunk;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.ChunkStore;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.messages.masterbound.ForceReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.WillSaveChunkLaterMessage;
import com.multiforge.protocol.messages.masterbound.WriteChunkMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk data plane: reads and writes chunk NBT through the master and peers.
 *
 * <p>This replaces Paper's {@code RegionFileIOThread} hooks used by MultiPaper. In
 * vanilla 1.20.1 all chunk IO funnels through {@code ChunkStorage.read/write}, so
 * the two mixin injections in {@code ChunkStorageMixin} are enough to route the
 * region, entity and poi storages.</p>
 *
 * <p>Correctness rules implemented here:</p>
 * <ul>
 *   <li><b>No silent data loss.</b> If the cluster is unreachable the read falls
 *       back to the local region file instead of returning "empty" (which would
 *       regenerate terrain).</li>
 *   <li><b>Write coalescing.</b> The server thread saves the same chunk several
 *       times in a row; only the newest payload is uploaded while one upload is in
 *       flight.</li>
 *   <li><b>Fresh data first.</b> When the master reports that another server owns
 *       or subscribes the chunk, the data is requested from that server so the
 *       newest (in-memory) copy wins over the master's stored copy.</li>
 *   <li><b>Shutdown durability.</b> {@link #flush(long)} waits for in-flight
 *       uploads before the connection is closed.</li>
 * </ul>
 *
 * <p>Threading: must be called from IO or server threads; no method blocks the
 * caller except {@link #flush(long)} and the local fallback, which both run on IO
 * or shutdown threads.</p>
 */
public final class ChunkSyncIo {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/ChunkIO");

    private final MultiForgeServer server;
    private final LocalChunkStorage local;
    private final Map<ChunkKey, CompletableFuture<Optional<CompoundTag>>> inFlightReads = new ConcurrentHashMap<>();
    private final Map<ChunkKey, PendingWrite> pendingWrites = new ConcurrentHashMap<>();
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /**
     * @param server owning server; also used for the local fallback storage
     */
    public ChunkSyncIo(MultiForgeServer server) {
        this.server = server;
        this.local = new LocalChunkStorage(server.server());
    }

    /**
     * @param store storage being accessed
     * @return whether IO must be redirected to the cluster
     */
    public boolean shouldIntercept(ChunkStore store) {
        if (server.isIoClosed()) {
            return false;
        }
        if (!server.master().isConnected()) {
            return false;
        }
        if (store.isEntities()) {
            return server.config().entitySync();
        }
        return server.config().chunkSync();
    }

    /**
     * Reads a chunk from the cluster, falling back to the local file on failure.
     *
     * @param store storage descriptor
     * @param pos   chunk position
     * @return future with the chunk tag; empty when neither the cluster nor the
     *         local storage has the chunk (i.e. it must be generated)
     */
    public CompletableFuture<Optional<CompoundTag>> read(ChunkStore store, ChunkPos pos) {
        ChunkKey key = new ChunkKey(store.world(), pos.x, pos.z);
        CompletableFuture<Optional<CompoundTag>> existing = inFlightReads.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Optional<CompoundTag>> created = new CompletableFuture<>();
        existing = inFlightReads.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        doRead(store, pos)
                .handle((tag, error) -> {
                    if (error != null) {
                        failures.incrementAndGet();
                        LOGGER.warn("Cluster read failed for {} {},{}, using local copy: {}",
                                key, pos.x, pos.z, error.toString());
                        return local.read(store, pos);
                    }
                    return CompletableFuture.completedFuture(tag);
                })
                .thenCompose(future -> future)
                .whenComplete((tag, error) -> {
                    inFlightReads.remove(key, created);
                    if (error != null) {
                        created.completeExceptionally(error);
                    } else {
                        created.complete(tag);
                    }
                });
        reads.incrementAndGet();
        return created;
    }

    private CompletableFuture<Optional<CompoundTag>> doRead(ChunkStore store, ChunkPos pos) {
        return server.master().request(new ReadChunkMessage(store.world(), store.type(), pos.x, pos.z))
                .thenCompose(reply -> {
                    if (reply instanceof DataMessageReply data) {
                        return CompletableFuture.completedFuture(data.data);
                    }
                    if (reply instanceof ChunkLoadedOnAnotherServerMessage other) {
                        return readFromPeerOrMaster(store, pos, other.server);
                    }
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Unexpected chunk read reply " + reply.getClass().getSimpleName()));
                })
                .thenApply(this::decode);
    }

    private CompletableFuture<byte[]> readFromPeerOrMaster(ChunkStore store, ChunkPos pos, String peer) {
        return server.peers().requestChunk(peer, store.world(), pos.x, pos.z)
                .thenCompose(data -> {
                    if (data.length > 0) {
                        LOGGER.debug("Chunk {} {},{},{} received from peer {} ({} bytes)",
                                store.world(), pos.x, pos.z, peer, data.length);
                        return CompletableFuture.completedFuture(data);
                    }
                    // The peer no longer has the chunk loaded: read the master's
                    // stored copy instead of generating fresh terrain.
                    return server.master()
                            .request(new ForceReadChunkMessage(store.world(), store.type(), pos.x, pos.z), DataMessageReply.class)
                            .thenApply(reply -> reply.data);
                });
    }

    private Optional<CompoundTag> decode(byte[] data) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(NbtIo.readCompressed(new ByteArrayInputStream(data)));
        } catch (IOException e) {
            throw new IllegalStateException("Corrupt chunk data received from the cluster", e);
        }
    }

    /**
     * Uploads a chunk to the cluster. When the cluster is unreachable, or the
     * upload fails, the chunk is written to the local region file so no data is
     * lost.
     *
     * @param store storage descriptor
     * @param pos   chunk position
     * @param tag   chunk NBT to store
     */
    public void write(ChunkStore store, ChunkPos pos, CompoundTag tag) {
        if (server.config().keepLocalChunkBackup()) {
            local.write(store, pos, tag);
        }
        if (!shouldIntercept(store)) {
            local.write(store, pos, tag);
            return;
        }

        byte[] data;
        try {
            data = encode(tag);
        } catch (IOException e) {
            failures.incrementAndGet();
            LOGGER.error("Failed to serialize chunk {} {},{}", store.world(), pos.x, pos.z, e);
            local.write(store, pos, tag);
            return;
        }

        ChunkKey key = new ChunkKey(store.world(), pos.x, pos.z);
        PendingWrite pending = new PendingWrite(store, pos, tag);
        PendingWrite existing = pendingWrites.putIfAbsent(key, pending);
        if (existing != null) {
            existing.data = data;
            existing.tag = tag;
            existing.dirty = true;
            return;
        }
        pending.data = data;
        writes.incrementAndGet();
        upload(key, pending);
    }

    private void upload(ChunkKey key, PendingWrite pending) {
        pending.dirty = false;
        server.master().send(new WillSaveChunkLaterMessage(key.getName(), key.getX(), key.getZ()));
        server.master()
                .request(new WriteChunkMessage(key.getName(), pending.store.type(), key.getX(), key.getZ(), pending.data),
                        BooleanMessageReply.class)
                .whenComplete((reply, error) -> {
                    if (error != null) {
                        failures.incrementAndGet();
                        LOGGER.error("Failed to upload chunk {}, writing local backup: {}", key, error.toString());
                        local.write(pending.store, pending.pos, pending.tag);
                        pendingWrites.remove(key, pending);
                        return;
                    }
                    if (pending.dirty) {
                        // Another save happened while the upload was in flight.
                        upload(key, pending);
                    } else {
                        pendingWrites.remove(key, pending);
                    }
                    if (pending.store.isEntities()) {
                        server.onEntitiesUploaded(key);
                    }
                });
    }

    private byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    /**
     * Waits until every pending chunk upload finished.
     *
     * @param timeoutMillis maximum time to wait
     * @return {@code true} when all writes completed, {@code false} on timeout
     */
    public boolean flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!pendingWrites.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        local.flush();
        return pendingWrites.isEmpty();
    }

    /** @return number of uploads that are not acknowledged yet */
    public int pendingWriteCount() {
        return pendingWrites.size();
    }

    /** @return number of chunk reads performed */
    public long readCount() {
        return reads.get();
    }

    /** @return number of chunk writes performed */
    public long writeCount() {
        return writes.get();
    }

    /** @return number of IO operations that needed a fallback */
    public long failureCount() {
        return failures.get();
    }

    /** Releases the fallback storages. Called during shutdown. */
    public void close() {
        local.close();
    }

    /** One in-flight upload with write coalescing support. */
    private final class PendingWrite {
        private final ChunkStore store;
        private final ChunkPos pos;
        private volatile CompoundTag tag;
        private volatile byte[] data;
        private volatile boolean dirty;

        private PendingWrite(ChunkStore store, ChunkPos pos, CompoundTag tag) {
            this.store = store;
            this.pos = pos;
            this.tag = tag;
        }
    }
}
