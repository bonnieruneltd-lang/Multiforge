package com.multiforge.forge.chunk;

import com.multiforge.forge.common.ChunkStore;
import com.multiforge.forge.common.WorldDirectories;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps vanilla {@link ChunkStorage} instances to logical MultiForge chunk stores.
 *
 * <p>In 1.20.1 {@code ChunkStorage} does not expose the directory it was created
 * with, and {@code ChunkMap} (the region storage) even extends it. The only place
 * the directory is visible is the constructor, so {@code ChunkStorageMixin}
 * captures it there. {@link ServerLevel} creation then registers the three
 * directories per dimension ({@code region}, {@code entities}, {@code poi}) with
 * their logical world name.</p>
 *
 * <p>Threading: written once per storage instance from the server thread and read
 * from IO worker threads. The path map is concurrent and the instance map is a
 * synchronized {@link WeakHashMap} so storages can be collected when a level is
 * unloaded.</p>
 */
public final class ChunkStorageRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/ChunkIO");

    private static final Map<Path, ChunkStore> BY_PATH = new ConcurrentHashMap<>();
    private static final Map<ChunkStorage, Path> BY_STORAGE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChunkStorageRegistry() {
    }

    /**
     * Records the directory of a newly constructed storage.
     *
     * <p>Called from the {@code ChunkStorage} constructor mixin.</p>
     *
     * @param storage storage instance
     * @param path    directory passed to the constructor
     */
    public static void capturePath(ChunkStorage storage, Path path) {
        if (storage == null || path == null) {
            return;
        }
        BY_STORAGE.put(storage, path.toAbsolutePath().normalize());
    }

    /**
     * Registers the three storage directories of a level.
     *
     * <p>Called when a {@link ServerLevel} finished constructing, before any
     * chunk is loaded.</p>
     *
     * @param level level that was created
     */
    public static void registerLevel(ServerLevel level) {
        for (ChunkStore store : WorldDirectories.storesOf(level)) {
            BY_PATH.put(store.directory(), store);
        }
        LOGGER.debug("Registered chunk stores for world {} at {}",
                WorldDirectories.worldName(level), WorldDirectories.dimensionPath(level.getServer(), level.dimension()));
    }

    /**
     * Resolves the logical store of a storage instance.
     *
     * @param storage storage instance
     * @return the store, or {@code null} when the directory is not a MultiForge
     *         managed storage (for example a datafixer scratch storage)
     */
    public static ChunkStore resolve(ChunkStorage storage) {
        Path path = BY_STORAGE.get(storage);
        if (path == null) {
            return null;
        }
        ChunkStore store = BY_PATH.get(path);
        if (store != null) {
            return store;
        }
        // Fall back to classifying by directory name. This covers storages that
        // are created before the level finished registering (rare) and custom
        // dimensions with a non standard path.
        String type = WorldDirectories.storageTypeOf(path);
        if (type == null) {
            return null;
        }
        ChunkStore derived = new ChunkStore("unknown", type, path);
        LOGGER.debug("Unregistered chunk storage {} classified as {}", path, type);
        return derived;
    }

    /**
     * @param storage storage instance
     * @return registered directory, or {@code null}
     */
    public static Path pathOf(ChunkStorage storage) {
        return BY_STORAGE.get(storage);
    }

    /** Removes all registrations; used when the server stops and by tests. */
    public static void clear() {
        BY_PATH.clear();
        BY_STORAGE.clear();
    }
}
