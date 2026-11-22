package com.multiforge.forge.chunk;

import com.multiforge.protocol.ChunkKey;
import net.minecraft.server.level.ServerLevel;

/**
 * Small direct-mapped cache of {@link ChunkKey} instances.
 *
 * <p>{@code ChunkManager.shouldTick(level, entity)} runs once per entity per
 * tick and used to allocate a new {@code ChunkKey} every time. Entities are
 * usually grouped by chunk, so a tiny cache removes almost all of those
 * allocations without a per-chunk map lookup.</p>
 *
 * <p>The lookup compares the level by identity and the coordinates by value; the
 * world name does not have to be compared again because one level always maps to
 * one logical world name. The cache is read and written on the server thread
 * only, matching {@link ChunkManager}'s threading contract.</p>
 */
final class ChunkKeyCache {

    private final ServerLevel[] levels;
    private final ChunkKey[] keys;
    private final int mask;

    /**
     * @param size number of slots; rounded down to a power of two
     */
    ChunkKeyCache(int size) {
        int slots = Integer.highestOneBit(Math.max(2, size));
        this.levels = new ServerLevel[slots];
        this.keys = new ChunkKey[slots];
        this.mask = slots - 1;
    }

    /**
     * Returns a key equal to {@code (worldName(level), x, z)}, reusing a cached
     * instance when possible.
     *
     * @param level level of the chunk
     * @param world logical world name of the level
     * @param x     chunk X
     * @param z     chunk Z
     * @return cached or newly created key
     */
    ChunkKey get(ServerLevel level, String world, int x, int z) {
        int index = ((x * 31) + z) & mask;
        ChunkKey cached = keys[index];
        if (cached != null && levels[index] == level && cached.x == x && cached.z == z) {
            return cached;
        }
        ChunkKey created = new ChunkKey(world, x, z);
        levels[index] = level;
        keys[index] = created;
        return created;
    }

    /** Drops every cached key. Called when the server stops. */
    void clear() {
        java.util.Arrays.fill(levels, null);
        java.util.Arrays.fill(keys, null);
    }
}
