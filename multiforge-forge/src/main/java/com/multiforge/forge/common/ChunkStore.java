package com.multiforge.forge.common;

import java.nio.file.Path;

/**
 * Identifies one on-disk chunk storage of a level.
 *
 * <p>Vanilla 1.20.1 creates three kinds of chunk storage per dimension: the
 * region file (blocks), the entities file and the point-of-interest file. Each
 * is a {@code ChunkStorage} instance backed by a directory. MultiForge needs to
 * know which of the three a given read or write belongs to, because the master
 * stores them under the same relative paths as the vanilla server
 * ({@code region}, {@code entities}, {@code poi}).</p>
 *
 * @param world     level folder name as used by the master, e.g. {@code world},
 *                  {@code world_nether}, {@code world_the_end}
 * @param type      storage type: {@code region}, {@code entities} or {@code poi}
 * @param directory absolute directory backing the storage
 */
public record ChunkStore(String world, String type, Path directory) {

    /** @return {@code true} for the block storage ({@code region}) */
    public boolean isRegion() {
        return "region".equals(type);
    }

    /** @return {@code true} for the entity storage ({@code entities}) */
    public boolean isEntities() {
        return "entities".equals(type);
    }

    /** @return {@code true} for the point-of-interest storage ({@code poi}) */
    public boolean isPoi() {
        return "poi".equals(type);
    }
}
