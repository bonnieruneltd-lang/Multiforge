package com.multiforge.protocol;

/**
 * Value object identifying a chunk across the cluster.
 *
 * <p>The original MultiPaper had two nearly identical classes (one in the plugin
 * and one in the master protocol); MultiForge keeps a single immutable type in the
 * protocol module so master and server agree on equality and hash codes. The world
 * name is the logical level name ({@code world}, {@code world_nether},
 * {@code world_the_end}).</p>
 *
 * <p>Instances are immutable and therefore safe to use as map keys from any
 * thread. The hash function matches MultiPaper's implementation (derived from
 * {@code ChunkPos}).</p>
 */
public class ChunkKey {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int x;
    /** Chunk Z coordinate. */
    public final int z;

    /**
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     */
    public ChunkKey(String world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    /** @return level folder name this chunk belongs to */
    public String getName() {
        return world;
    }

    /** @return chunk X coordinate */
    public int getX() {
        return x;
    }

    /** @return chunk Z coordinate */
    public int getZ() {
        return z;
    }

    @Override
    public String toString() {
        return world + "," + x + "," + z;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ChunkKey key) {
            return key.world.equals(world) && key.x == x && key.z == z;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // Same mixing as ChunkPos, with the world name folded in.
        int i = 1664525 * this.x + 1013904223;
        int j = 1664525 * (this.z ^ -559038737) + 1013904223;
        return world.hashCode() ^ i ^ j;
    }
}
