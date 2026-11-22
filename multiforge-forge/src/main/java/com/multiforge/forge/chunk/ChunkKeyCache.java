package com.multiforge.forge.chunk;

import com.multiforge.protocol.ChunkKey;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Small direct-mapped cache of {@link ChunkKey} instances.
 *
 * <p>{@code ChunkManager.shouldTick(level, entity)} runs once per entity per
 * tick and used to allocate a new {@code ChunkKey} every time. Entities are
 * usually grouped by chunk, so a tiny cache removes almost all of those
 * allocations without a per-chunk map lookup.</p>
 *
 * <p>Each slot holds one immutable {@link Pinned} pair (level identity and key)
 * behind an {@link AtomicReferenceArray}, so a read never observes a key from one
 * level paired with another level even if a caller ever runs off the server
 * thread. The world name does not have to be compared again because one level
 * always maps to one logical world name.</p>
 */
final class ChunkKeyCache {

    private final AtomicReferenceArray<Pinned> entries;

    /**
     * @param size number of slots; rounded down to a power of two
     */
    ChunkKeyCache(int size) {
        int slots = Integer.highestOneBit(Math.max(2, size));
        this.entries = new AtomicReferenceArray<>(slots);
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
        int index = ((x * 31) + z) & (entries.length() - 1);
        Pinned cached = entries.get(index);
        if (cached != null && cached.level == level && cached.key.x == x && cached.key.z == z) {
            return cached.key;
        }
        ChunkKey created = new ChunkKey(world, x, z);
        entries.set(index, new Pinned(level, created));
        return created;
    }

    /** Drops every cached key. Called when the server stops. */
    void clear() {
        for (int index = 0; index < entries.length(); index++) {
            entries.set(index, null);
        }
    }

    /** One level/key pair; immutable, so concurrent readers always see a match. */
    private static final class Pinned {

        private final ServerLevel level;
        private final ChunkKey key;

        private Pinned(ServerLevel level, ChunkKey key) {
            this.level = level;
            this.key = key;
        }
    }
}
