package com.multiforge.api;

/**
 * Value object identifying a chunk in a dimension.
 *
 * <p>MultiForge mirrors MultiPaper's naming: the world name is the level folder
 * name (for example {@code world}, {@code world_nether}, {@code world_the_end}).
 * Two servers must agree on this name because the master stores world data by
 * it, so the name is kept as a plain string instead of an enum.</p>
 *
 * @param world level folder name
 * @param x     chunk X coordinate
 * @param z     chunk Z coordinate
 */
public record ChunkLocation(String world, int x, int z) {

    /**
     * @return implementation independent key in the form {@code world,x,z}
     */
    public String asKey() {
        return world + "," + x + "," + z;
    }
}
