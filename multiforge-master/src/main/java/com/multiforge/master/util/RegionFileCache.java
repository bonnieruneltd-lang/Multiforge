package com.multiforge.master.util;

import com.multiforge.master.util.Log;

/*
 ** 2011 January 5
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */

/*
 * 2011 February 16
 *
 * This source code is based on the work of Scaevolus (see notice above).
 * It has been slightly modified by Mojang AB to limit the maximum cache
 * size (relevant to extremely big worlds on Linux systems with limited
 * number of file handles). The region files are postfixed with ".mcr"
 * (Minecraft region file) instead of ".data" to differentiate from the
 * original McRegion files.
 *
 */

// A simple cache and wrapper for efficiently multiple RegionFiles simultaneously.

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RegionFileCache {

    private static final int MAX_CACHE_SIZE = Integer.getInteger("max.regionfile.cache.size", 256);

    /**
     * Number of independently locked cache shards.
     *
     * <p>A single {@code synchronized} LRU serialized every chunk access in the
     * whole master. The file IO still happens under the per-region lock inside
     * {@link RegionFile}, so only the cache lookup was contendable; sharding by
     * region file path removes that contention without changing the maximum number
     * of open files (each shard evicts its own oldest entry).</p>
     */
    private static final int SEGMENTS = Math.max(1,
            Integer.getInteger("multiforge.regionfile.segments", 16));

    /** Maximum entries per shard; the total stays close to {@link #MAX_CACHE_SIZE}. */
    private static final int MAX_PER_SEGMENT = Math.max(1, MAX_CACHE_SIZE / SEGMENTS);

    private static final Segment[] segments = new Segment[SEGMENTS];

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            segments[i] = new Segment();
        }
    }

    /**
     * Canonical form of each region directory, resolved once per directory.
     *
     * <p>The original code called {@link File#getCanonicalPath()} for every chunk
     * access while holding the class lock. That is a filesystem syscall (and, on
     * some platforms, a chain of {@code stat} calls) on the hot path of every
     * chunk read and write, serialized across all server connections. Resolving
     * the directory once keeps the cache keys canonical without the syscall.</p>
     */
    private static final Map<String, File> canonicalDirectories = new ConcurrentHashMap<>();

    private RegionFileCache() {
    }

    public static boolean isRegionFileOpen(File regionDir, int chunkX, int chunkZ) {
        File file = getFileForRegionFile(regionDir, chunkX, chunkZ);
        return segment(file).isOpen(file);
    }

    /**
     * @param dir region directory
     * @return canonical form of the directory, memoized by its path
     */
    private static File canonicalDirectory(File dir) {
        return canonicalDirectories.computeIfAbsent(dir.getPath(), ignored -> {
            try {
                // Remove any .'s and ..'s and resolve symbolic links once.
                return new File(dir.getCanonicalPath());
            } catch (IOException e) {
                Log.error("Could not resolve region directory " + dir + ": " + e);
                return dir.getAbsoluteFile();
            }
        });
    }

    /**
     * @param regionDir region directory
     * @param chunkX    chunk X
     * @param chunkZ    chunk Z
     * @return region file belonging to the chunk, with a canonical parent
     */
    private static File getFileForRegionFile(File regionDir, int chunkX, int chunkZ) {
        return new File(canonicalDirectory(regionDir),
                "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    }

    public static RegionFile getRegionFileIfExists(File regionDir, int chunkX, int chunkZ) {
        File file = getFileForRegionFile(regionDir, chunkX, chunkZ);
        Segment segment = segment(file);
        synchronized (segment) {
            RegionFile open = segment.get(file);
            if (open != null) {
                return open;
            }
            if (!file.isFile()) {
                return null;
            }
            return getRegionFile(regionDir, chunkX, chunkZ);
        }
    }

    public static RegionFile getRegionFile(File regionDir, int chunkX, int chunkZ) {
        File file = getFileForRegionFile(regionDir, chunkX, chunkZ);
        Segment segment = segment(file);
        synchronized (segment) {
            RegionFile open = segment.get(file);
            if (open != null) {
                return open;
            }
            if (!regionDir.exists()) {
                regionDir.mkdirs();
            }
            segment.evictIfFull();
            RegionFile reg = new RegionFile(file);
            segment.cache.put(file, new SoftReference<>(reg));
            return reg;
        }
    }

    /**
     * @param file region file
     * @return shard that owns the file's cache entry
     */
    private static Segment segment(File file) {
        int index = (file.getPath().hashCode() & Integer.MAX_VALUE) % SEGMENTS;
        return segments[index];
    }

    /**
     * One independently locked LRU shard.
     *
     * <p>All access happens while holding the shard monitor, including construction of
     * a new {@link RegionFile}, so two threads can never open two handles for the same
     * file.</p>
     */
    private static final class Segment {
        private final LinkedHashMap<File, Reference<RegionFile>> cache =
                new LinkedHashMap<>(16, 0.75f, true);

        private RegionFile get(File file) {
            Reference<RegionFile> ref = cache.get(file);
            return ref == null ? null : ref.get();
        }

        private boolean isOpen(File file) {
            return get(file) != null;
        }

        private void evictIfFull() {
            if (cache.size() < MAX_PER_SEGMENT) {
                return;
            }
            Map.Entry<File, Reference<RegionFile>> entry = cache.entrySet().iterator().next();
            cache.remove(entry.getKey());
            try {
                RegionFile removed = entry.getValue().get();
                if (removed != null) {
                    removed.close();
                }
            } catch (IOException e) {
                Log.error("Could not close an evicted region file", e);
            }
        }
    }

    public static int getSizeDelta(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.getSizeDelta();
    }

    public static DataInputStream getChunkDataInputStream(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        if (r != null) {
            return r.getChunkDataInputStream(chunkX, chunkZ);
        } else {
            return null;
        }
    }

    public static DataOutputStream getChunkDataOutputStream(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.getChunkDataOutputStream(chunkX, chunkZ);
    }

    public static CompletableFuture<byte[]> getChunkDeflatedDataAsync(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFileIfExists(basePath, chunkX, chunkZ);
        if (r != null) {
            return r.submitTask(regionFile -> regionFile.getDeflatedBytes(chunkX, chunkZ));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static byte[] getChunkDeflatedData(File basePath, int chunkX, int chunkZ) {
        try {
            RegionFile r = getRegionFileIfExists(basePath, chunkX, chunkZ);
            if (r != null) {
                return r.getDeflatedBytes(chunkX, chunkZ);
            } else {
                return null;
            }
        } catch (Throwable throwable) {
            Log.error("Error when trying to read chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }

    public static CompletableFuture<Void> putChunkDeflatedDataAsync(File basePath, int chunkX, int chunkZ, byte[] data) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.submitTask(regionFile -> {
            regionFile.putDeflatedBytes(chunkX, chunkZ, data);
            return null;
        });
    }

    private static void putChunkDeflatedData(File basePath, int chunkX, int chunkZ, byte[] data) {
        try {
            RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
            r.putDeflatedBytes(chunkX, chunkZ, data);
        } catch (Throwable throwable) {
            Log.error("Error when trying to write chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }
}
