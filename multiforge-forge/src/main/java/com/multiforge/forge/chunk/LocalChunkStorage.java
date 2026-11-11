package com.multiforge.forge.chunk;

import com.multiforge.forge.common.ChunkStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access to the local vanilla region files while MultiForge owns the IO path.
 *
 * <p>Two situations need the original storage:</p>
 * <ul>
 *   <li><b>Fallback</b>: the master is unreachable, so a read must not silently
 *       return "chunk does not exist" (that would regenerate and overwrite
 *       terrain). The local files are the last known good copy.</li>
 *   <li><b>Backup</b>: optionally keep local copies of written chunks for
 *       disaster recovery.</li>
 * </ul>
 *
 * <p>Separate {@link ChunkStorage} instances are created per directory because the
 * vanilla instances are owned by the level and would recurse into our intercepting
 * mixin. Instances are created lazily on IO threads and cached; they are closed on
 * shutdown. All methods are blocking and must not be called from the server
 * thread except {@link #flush()} during shutdown.</p>
 */
public final class LocalChunkStorage implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/ChunkIO");

    private final MinecraftServer server;
    private final Map<Path, ChunkStorage> storages = new ConcurrentHashMap<>();

    /**
     * @param server running server, used for the data fixer
     */
    public LocalChunkStorage(MinecraftServer server) {
        this.server = server;
    }

    private ChunkStorage storage(ChunkStore store) {
        return storages.computeIfAbsent(store.directory(),
                path -> new ChunkStorage(path, server.getFixerUpper(), false));
    }

    /**
     * Reads a chunk from the local region file.
     *
     * @param store storage descriptor
     * @param pos   chunk position
     * @return future with the stored tag, empty when the chunk is absent
     */
    public CompletableFuture<Optional<CompoundTag>> read(ChunkStore store, ChunkPos pos) {
        try {
            return storage(store).read(pos);
        } catch (RuntimeException e) {
            LOGGER.warn("Local fallback read failed for {} {},{}", store.world(), pos.x, pos.z, e);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Writes a chunk to the local region file (backup).
     *
     * @param store storage descriptor
     * @param pos   chunk position
     * @param tag   chunk NBT
     */
    public void write(ChunkStore store, ChunkPos pos, CompoundTag tag) {
        try {
            storage(store).write(pos, tag);
        } catch (RuntimeException e) {
            LOGGER.warn("Local backup write failed for {} {},{}", store.world(), pos.x, pos.z, e);
        }
    }

    /** Flushes all local storage workers. Called during shutdown. */
    public void flush() {
        for (ChunkStorage storage : storages.values()) {
            try {
                storage.flushWorker();
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to flush local chunk storage", e);
            }
        }
    }

    @Override
    public void close() {
        for (ChunkStorage storage : storages.values()) {
            try {
                storage.close();
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Failed to close local chunk storage", e);
            }
        }
        storages.clear();
    }
}
