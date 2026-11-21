package com.multiforge.master.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the region file cache, in particular the memoized canonical directory.
 *
 * <p>The cache used to resolve {@link File#getCanonicalPath()} for every chunk
 * access while holding the global lock. The cached key must still be stable for
 * equivalent paths (absolute, relative and with {@code .} segments), otherwise
 * two {@link RegionFile} instances would open the same file.</p>
 */
class RegionFileCacheTest {

    /**
     * @param root temporary directory supplied by JUnit
     */
    @Test
    void equivalentDirectoriesShareOneRegionFile(@TempDir Path root) {
        File regionDir = root.resolve("world/region").toFile();
        RegionFile first = RegionFileCache.getRegionFile(regionDir, 0, 0);
        assertNotNull(first);

        // A "." segment must resolve to the same cache key as the plain path.
        RegionFile withDot = RegionFileCache.getRegionFile(new File(regionDir, "."), 0, 0);
        assertSame(first, withDot, "'.' segment must hit the same cache entry");

        RegionFile again = RegionFileCache.getRegionFile(regionDir, 0, 0);
        assertSame(first, again);
        assertSame(first, RegionFileCache.getRegionFile(regionDir, 31, 31));
    }

    /**
     * @param root temporary directory supplied by JUnit
     */
    @Test
    void missingRegionFileIsNotCreated(@TempDir Path root) {
        File regionDir = root.resolve("empty/region").toFile();
        assertNull(RegionFileCache.getRegionFileIfExists(regionDir, 0, 0));
        assertNull(RegionFileCache.getRegionFileIfExists(regionDir, -1, -1));
    }

    /**
     * The sharded cache must still open exactly one handle per region file when many
     * chunk reads race for it (the previous global lock made this trivial; per-shard
     * locks must not introduce duplicate handles).
     *
     * @param root temporary directory supplied by JUnit
     * @throws Exception when a worker fails
     */
    @Test
    void concurrentAccessReturnsOneHandle(@TempDir Path root) throws Exception {
        File regionDir = root.resolve("concurrent/region").toFile();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<RegionFile>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> RegionFileCache.getRegionFile(regionDir, 0, 0)));
            }
            RegionFile first = futures.get(0).get();
            for (java.util.concurrent.Future<RegionFile> future : futures) {
                assertSame(first, future.get(), "all threads must share one handle");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * @param root temporary directory supplied by JUnit
     * @throws IOException when the round trip fails
     */
    @Test
    void deflatedChunkDataRoundTrips(@TempDir Path root) throws IOException {
        File regionDir = root.resolve("roundtrip/region").toFile();
        byte[] data = new byte[8192];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }

        RegionFile regionFile = RegionFileCache.getRegionFile(regionDir, 5, -7);
        regionFile.putDeflatedBytes(5, -7, data);

        assertArrayEquals(data, RegionFileCache.getRegionFile(regionDir, 5, -7)
                .getDeflatedBytes(5, -7));
    }
}
