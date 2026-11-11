package com.multiforge.master.util;

import java.io.File;
import java.nio.file.Path;

/**
 * Resolves every file the master stores.
 *
 * <p>MultiPaper's master used paths relative to the process working directory
 * ({@code world}, {@code world_nether}, {@code synced-server-files}, ...). That made
 * it impossible to run several masters from one directory or to point the master at
 * a mounted volume. MultiForge keeps the same layout but resolves it against a
 * configurable root ({@code -Dmultiforge.master.storage=/data}).</p>
 *
 * <p>Threading: the root is set once during start-up (before any handler runs) and
 * read from arbitrary handler threads afterwards; it is volatile.</p>
 */
public final class MasterStorage {

    private static volatile Path root = Path.of(System.getProperty("multiforge.master.storage", "."));

    private MasterStorage() {
    }

    /**
     * Changes the storage root. Called once during master start-up.
     *
     * @param newRoot directory that holds the world folders and shared files
     */
    public static void setRoot(Path newRoot) {
        root = newRoot.toAbsolutePath().normalize();
    }

    /** @return absolute storage root */
    public static Path root() {
        return root;
    }

    /**
     * @param relative path relative to the storage root
     * @return file below the storage root
     */
    public static File file(String relative) {
        return root.resolve(relative).toFile();
    }

    /**
     * Maps a logical world name to its directory, applying the same Bukkit-style
     * suffix convention MultiPaper uses.
     *
     * @param world logical world name ({@code world}, {@code world_nether}, ...)
     * @return world root, nether or end directory
     */
    public static File worldDir(String world) {
        if (world.endsWith("_nether")) {
            return dimensionDir(world, "DIM-1");
        }
        if (world.endsWith("_the_end")) {
            return dimensionDir(world, "DIM1");
        }
        return file(world);
    }

    private static File dimensionDir(String world, String dimension) {
        String base = world.substring(0, world.length() - (world.endsWith("_nether") ? "_nether".length() : "_the_end".length()));
        return new File(file(base), dimension);
    }

    /**
     * @param world    logical world name
     * @param relative path inside the world directory (for example {@code region})
     * @return directory inside the world
     */
    public static File worldDir(String world, String relative) {
        return new File(worldDir(world), relative);
    }

    /**
     * @param world logical world name
     * @param name  file name inside the world directory
     * @return file inside the world directory
     */
    public static File worldFile(String world, String name) {
        return new File(worldDir(world), name);
    }

    /**
     * @param world logical world name
     * @param sub   sub directory (for example {@code playerdata})
     * @param name  file name
     * @return file inside the sub directory of the world
     */
    public static File worldFile(String world, String sub, String name) {
        return new File(worldDir(world, sub), name);
    }

    /**
     * @param relative path inside the shared file store
     * @return file below {@code synced-server-files}
     */
    public static File sharedFile(String relative) {
        return file("synced-server-files/" + relative);
    }

    /** @return directory that holds files synchronized between servers */
    public static File sharedRoot() {
        return file("synced-server-files");
    }
}
