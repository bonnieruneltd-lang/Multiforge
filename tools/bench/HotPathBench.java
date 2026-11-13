import com.multiforge.master.util.RegionFileCache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Microbenchmark for the MultiForge hot-path optimizations.
 *
 * <p>Compares the pre-optimization implementation of two lookups that run on
 * every chunk access or peer packet with the current implementation:</p>
 * <ol>
 *   <li>region file lookup: {@code File.getCanonicalPath()} per access (old,
 *       called by {@code RegionFileCache}) versus the memoized canonical
 *       directory (new);</li>
 *   <li>peer packet id lookup: a linear scan over the registered types (old
 *       {@code PeerProtocol.idOf}) versus a hash map (new), modelled with 20
 *       type names.</li>
 * </ol>
 *
 * <p>Not part of the build. Compile and run it against the compiled master and
 * protocol classes:</p>
 * <pre>
 *   javac -cp multiforge-master/build/classes/java/main \
 *       -d tools/bench/build tools/bench/HotPathBench.java
 *   java -Xmx512m -cp multiforge-master/build/classes/java/main:tools/bench/build HotPathBench
 * </pre>
 *
 * <p>Numbers depend on the filesystem and JIT; the proportions are what matter.
 * See {@code docs/PERFORMANCE.md} for the recorded results.</p>
 */
public final class HotPathBench {

    private static final int ITERATIONS = 2_000_000;

    private HotPathBench() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("multiforge-bench");
        regionFileLookup(root.resolve("region").toFile());
        packetIdLookup();
    }

    /**
     * Compares the old per-lookup canonicalisation with the memoized version.
     *
     * @param regionDir directory for the region files
     * @throws IOException when the directory cannot be created
     */
    private static void regionFileLookup(File regionDir) throws IOException {
        Files.createDirectories(regionDir.toPath());
        File file = new File(regionDir, "r.0.0.mca");
        Map<String, Object> cache = new HashMap<>();
        cache.put(file.getCanonicalPath(), new Object());

        // Warm up both paths (JIT + page cache).
        for (int i = 0; i < 100_000; i++) {
            file.getCanonicalPath();
            RegionFileCache.getRegionFile(regionDir, 0, 0);
        }

        long start = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sink += cache.containsKey(file.getCanonicalPath()) ? 1 : 0;
        }
        long oldNanos = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            sink += RegionFileCache.getRegionFile(regionDir, 0, 0) != null ? 1 : 0;
        }
        long newNanos = System.nanoTime() - start;

        System.out.printf("region file lookup: old %.1f ns/op, new %.1f ns/op (x%.1f), sink=%d%n",
                oldNanos / (double) ITERATIONS, newNanos / (double) ITERATIONS,
                oldNanos / (double) newNanos, sink);
    }

    /** Compares a linear scan (old) with a hash map (new) for packet ids. */
    private static void packetIdLookup() {
        List<String> classes = new ArrayList<>();
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String type = "packet.Type" + i;
            ids.put(type, classes.size());
            classes.add(type);
        }
        String last = classes.get(classes.size() - 1);

        for (int i = 0; i < 100_000; i++) {
            classes.indexOf(last);
            ids.get(last);
        }

        long start = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sink += classes.indexOf(last);
        }
        long oldNanos = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            sink += ids.get(last);
        }
        long newNanos = System.nanoTime() - start;

        System.out.printf("packet id lookup: old %.1f ns/op, new %.1f ns/op (x%.1f), sink=%d%n",
                oldNanos / (double) ITERATIONS, newNanos / (double) ITERATIONS,
                oldNanos / (double) newNanos, sink);
    }
}
