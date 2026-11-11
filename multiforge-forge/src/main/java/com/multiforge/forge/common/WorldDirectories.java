package com.multiforge.forge.common;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps Minecraft levels to the folder layout understood by the MultiForge master.
 *
 * <p>MultiPaper names worlds the same way a Bukkit server does: the overworld is
 * {@code world}, the nether is {@code world_nether} and the end is
 * {@code world_the_end}, while the files live in {@code world/DIM-1} and
 * {@code world/DIM1}. Forge's storage layout is identical on disk, so the only
 * work is deriving the logical name from a {@link ServerLevel} and the matching
 * directory from the level's dimension key.</p>
 *
 * <p>All methods are pure functions of their arguments and safe to call from any
 * thread once the server has created its levels.</p>
 */
public final class WorldDirectories {

    /** Storage sub-directory names in the order MultiForge tracks them. */
    public static final List<String> STORAGE_TYPES = List.of("region", "entities", "poi");

    private WorldDirectories() {
    }

    /**
     * Base world folder name, i.e. the folder that contains {@code level.dat}.
     *
     * @param server running server
     * @return folder name such as {@code world}
     */
    public static String baseWorldName(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path name = root.getFileName();
        return name == null ? "world" : name.toString();
    }

    /**
     * Logical world name used on the wire and by the master.
     *
     * @param level level to name
     * @return {@code base}, {@code base_nether} or {@code base_the_end}; custom
     *         dimensions use {@code base_<namespace>_<path>}
     */
    public static String worldName(ServerLevel level) {
        String base = baseWorldName(level.getServer());
        ResourceKey<Level> dimension = level.dimension();
        if (dimension == Level.OVERWORLD) {
            return base;
        }
        if (dimension == Level.NETHER) {
            return base + "_nether";
        }
        if (dimension == Level.END) {
            return base + "_the_end";
        }
        return base + "_" + dimension.location().getNamespace() + "_" + dimension.location().getPath();
    }

    /**
     * Directory that contains a level's vanillla data folders.
     *
     * @param server    running server
     * @param dimension dimension to resolve
     * @return {@code world}, {@code world/DIM-1}, {@code world/DIM1} or
     *         {@code world/dimensions/<namespace>/<path>}
     */
    public static Path dimensionPath(MinecraftServer server, ResourceKey<Level> dimension) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (dimension == Level.OVERWORLD) {
            return root;
        }
        if (dimension == Level.NETHER) {
            return root.resolve("DIM-1");
        }
        if (dimension == Level.END) {
            return root.resolve("DIM1");
        }
        return root.resolve("dimensions")
                .resolve(dimension.location().getNamespace())
                .resolve(dimension.location().getPath());
    }

    /**
     * Builds the three {@link ChunkStore}s of a level.
     *
     * @param level level to describe
     * @return immutable list of region, entities and poi stores
     */
    public static List<ChunkStore> storesOf(ServerLevel level) {
        String world = worldName(level);
        Path dimensionPath = dimensionPath(level.getServer(), level.dimension());
        List<ChunkStore> stores = new ArrayList<>(STORAGE_TYPES.size());
        for (String type : STORAGE_TYPES) {
            stores.add(new ChunkStore(world, type, dimensionPath.resolve(type).toAbsolutePath().normalize()));
        }
        return List.copyOf(stores);
    }

    /**
     * Best-effort reconstruction of the logical world name from a storage directory.
     *
     * <p>Point-of-interest storages are created by {@code PoiManager} while the level
     * is still being constructed, before {@code LevelEvent.Load} registers the
     * dimension directories. Resolving by path keeps POI data attached to the right
     * world instead of a placeholder.</p>
     *
     * @param directory storage directory such as {@code world/DIM-1/region}
     * @return derived store, or {@code null} when the layout is not recognised
     */
    public static ChunkStore inferStore(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        String type = storageTypeOf(normalized);
        if (type == null) {
            return null;
        }
        Path dimensionDir = normalized.getParent();
        if (dimensionDir == null) {
            return null;
        }
        String suffix = "";
        Path worldRoot = dimensionDir;
        String dimensionName = dimensionDir.getFileName() == null ? "" : dimensionDir.getFileName().toString();
        if ("DIM-1".equals(dimensionName)) {
            suffix = "_nether";
            worldRoot = dimensionDir.getParent();
        } else if ("DIM1".equals(dimensionName)) {
            suffix = "_the_end";
            worldRoot = dimensionDir.getParent();
        } else if (dimensionDir.getParent() != null && "dimensions".equals(dimensionDir.getParent().getFileName() == null
                ? null : dimensionDir.getParent().getFileName().toString())) {
            // world/dimensions/<namespace>/<path>/<type>
            String namespace = dimensionDir.getParent().getParent() == null ? "unknown"
                    : String.valueOf(dimensionDir.getParent().getParent().getFileName());
            suffix = "_" + namespace + "_" + dimensionName;
            worldRoot = dimensionDir.getParent().getParent() == null ? null : dimensionDir.getParent().getParent().getParent();
        }
        if (worldRoot == null || worldRoot.getFileName() == null) {
            return null;
        }
        return new ChunkStore(worldRoot.getFileName() + suffix, type, normalized);
    }

    /**
     * Best-effort classification of a chunk storage directory.
     *
     * @param directory directory passed to the {@code ChunkStorage} constructor
     * @return {@code region}, {@code entities}, {@code poi} or {@code null} when
     *         the directory does not follow the vanilla layout
     */
    public static String storageTypeOf(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return null;
        }
        String value = name.toString().toLowerCase(Locale.ROOT);
        return STORAGE_TYPES.contains(value) ? value : null;
    }
}
