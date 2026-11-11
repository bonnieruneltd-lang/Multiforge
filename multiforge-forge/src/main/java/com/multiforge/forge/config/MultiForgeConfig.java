package com.multiforge.forge.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MultiForge server configuration, expressed as a Forge {@code SERVER} config.
 *
 * <p>The original MultiPaper read {@code multipaper.yml} through Paper's config
 * system. Forge already provides a config system (TOML + reload support), so the
 * same knobs are declared with {@link ForgeConfigSpec} and exposed through an
 * immutable {@link Snapshot} that the runtime reads without touching the config
 * system on hot paths.</p>
 *
 * <p>Values are only valid while a server is running: {@code SERVER} configs are
 * loaded when a world is opened. {@link #snapshot()} returns the last baked
 * snapshot and is safe to call from any thread (volatile read).</p>
 */
public final class MultiForgeConfig {

    /** Master address as {@code host:port}. */
    public static final ForgeConfigSpec.ConfigValue<String> MASTER_ADDRESS;
    /** Unique name of this server; empty means auto-generate. */
    public static final ForgeConfigSpec.ConfigValue<String> SERVER_NAME;
    /** Peer-to-peer port; 0 lets the OS pick one. */
    public static final ForgeConfigSpec.IntValue PEER_PORT;
    /** Host other servers should use to reach this server; empty means auto-detect. */
    public static final ForgeConfigSpec.ConfigValue<String> ADVERTISE_HOST;

    public static final ForgeConfigSpec.IntValue COMPRESSION_THRESHOLD;
    public static final ForgeConfigSpec.IntValue PING_INTERVAL_SECONDS;
    public static final ForgeConfigSpec.IntValue RECONNECT_DELAY_SECONDS;
    public static final ForgeConfigSpec.IntValue IO_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue TPS_SYNC_INTERVAL_TICKS;

    public static final ForgeConfigSpec.BooleanValue CHUNK_SYNC;
    public static final ForgeConfigSpec.BooleanValue ENTITY_SYNC;
    public static final ForgeConfigSpec.BooleanValue PLAYER_SYNC;
    public static final ForgeConfigSpec.BooleanValue WORLD_SYNC;
    public static final ForgeConfigSpec.BooleanValue KEEP_LOCAL_CHUNK_BACKUP;
    public static final ForgeConfigSpec.IntValue CHUNK_OWNERSHIP_REQUEST_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue CHUNK_UNLOAD_GRACE_TICKS;

    public static final ForgeConfigSpec.BooleanValue SYNC_LEVEL_DAT;
    public static final ForgeConfigSpec.BooleanValue SYNC_GAME_RULES;
    public static final ForgeConfigSpec.BooleanValue SYNC_TIME;
    public static final ForgeConfigSpec.BooleanValue SYNC_WEATHER;
    public static final ForgeConfigSpec.BooleanValue SYNC_WORLD_BORDER;
    public static final ForgeConfigSpec.BooleanValue SYNC_JSON_FILES;
    public static final ForgeConfigSpec.BooleanValue SYNC_PLAYER_DATA;
    public static final ForgeConfigSpec.BooleanValue SYNC_STATISTICS;
    public static final ForgeConfigSpec.BooleanValue SYNC_ADVANCEMENTS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> FILES_TO_SYNC_ON_STARTUP;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> FILES_TO_SYNC_IN_REAL_TIME;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> FILES_TO_NOT_SYNC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue DEBUG_CHUNK_BORDERS;

    /** Immutable configuration snapshot consumed by the runtime. */
    public static final ForgeConfigSpec SPEC;

    private static volatile Snapshot snapshot = Snapshot.defaults();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Connection to the MultiForge master (the process that coordinates all servers).")
                .push("master");
        MASTER_ADDRESS = builder
                .comment("Address of the MultiForge master, in the form host:port.")
                .define("address", "localhost:35353");
        PING_INTERVAL_SECONDS = builder
                .comment("How often this server pings the master, in seconds.")
                .defineInRange("pingIntervalSeconds", 5, 1, 300);
        RECONNECT_DELAY_SECONDS = builder
                .comment("Delay between master reconnection attempts, in seconds.")
                .defineInRange("reconnectDelaySeconds", 2, 1, 300);
        IO_TIMEOUT_SECONDS = builder
                .comment("Timeout for a request/reply exchange with the master or a peer.")
                .defineInRange("ioTimeoutSeconds", 30, 5, 600);
        TPS_SYNC_INTERVAL_TICKS = builder
                .comment("How often the local TPS is reported to the master, in ticks.")
                .defineInRange("tpsSyncIntervalTicks", 20, 1, 1200);
        builder.pop();

        builder.comment("Identity and peer-to-peer networking of this server.")
                .push("server");
        SERVER_NAME = builder
                .comment("Unique name of this server in the cluster. Empty means auto-generate one.")
                .define("name", "");
        PEER_PORT = builder
                .comment("Port used for direct server-to-server connections. 0 selects a free port.")
                .defineInRange("peerPort", 0, 0, 65535);
        ADVERTISE_HOST = builder
                .comment("Host other servers should use to connect to this server. Empty means auto-detect.")
                .define("advertiseHost", "");
        COMPRESSION_THRESHOLD = builder
                .comment("Compress peer packets larger than this many bytes. 0 disables peer compression.")
                .defineInRange("compressionThreshold", 0, 0, 1 << 20);
        builder.pop();

        builder.comment("Which parts of the world are kept in sync across servers.")
                .push("synchronization");
        CHUNK_SYNC = builder.comment("Synchronize chunk data through the master and peers.").define("chunks", true);
        ENTITY_SYNC = builder.comment("Synchronize entity data and entity subscriptions.").define("entities", true);
        PLAYER_SYNC = builder.comment("Synchronize player data and the cluster-wide player list.").define("players", true);
        WORLD_SYNC = builder.comment("Synchronize level.dat, time, weather, gamerules and world border.").define("world", true);
        KEEP_LOCAL_CHUNK_BACKUP = builder
                .comment("Also write chunk data to the local region files as a backup. Costs disk I/O.")
                .define("keepLocalChunkBackup", false);
        CHUNK_OWNERSHIP_REQUEST_INTERVAL_TICKS = builder
                .comment("Minimum number of ticks between ownership requests for the same chunk.")
                .defineInRange("chunkOwnershipRequestIntervalTicks", 10, 1, 200);
        CHUNK_UNLOAD_GRACE_TICKS = builder
                .comment("Ticks to wait after an unload before unsubscribing, so short unload/reload cycles do not churn the master.")
                .defineInRange("chunkUnloadGraceTicks", 100, 0, 1200);
        SYNC_LEVEL_DAT = builder.comment("Read and write level.dat through the master.").define("levelDat", true);
        SYNC_GAME_RULES = builder.comment("Propagate gamerule changes to other servers.").define("gameRules", true);
        SYNC_TIME = builder.comment("Keep the day/night time in sync.").define("time", true);
        SYNC_WEATHER = builder.comment("Keep rain/thunder state in sync.").define("weather", true);
        SYNC_WORLD_BORDER = builder.comment("Keep the world border in sync.").define("worldBorder", true);
        SYNC_JSON_FILES = builder.comment("Synchronize whitelist/ops/banned-*.json files through the master.").define("jsonFiles", true);
        SYNC_PLAYER_DATA = builder.comment("Read and write player .dat files through the master.").define("playerData", true);
        SYNC_STATISTICS = builder.comment("Synchronize statistics files through the master.").define("statistics", true);
        SYNC_ADVANCEMENTS = builder.comment("Synchronize advancement files through the master.").define("advancements", true);
        FILES_TO_SYNC_ON_STARTUP = builder
                .comment("Extra files (relative to the server directory) uploaded to the master on startup.")
                .defineListAllowEmpty(List.of("filesToSyncOnStartup"), List::of, o -> o instanceof String);
        FILES_TO_SYNC_IN_REAL_TIME = builder
                .comment("Extra files watched and synchronized while the server runs.")
                .defineListAllowEmpty(List.of("filesToSyncInRealTime"), List::of, o -> o instanceof String);
        FILES_TO_NOT_SYNC = builder
                .comment("Files that are never uploaded, even if they match another rule.")
                .defineListAllowEmpty(List.of("filesToNotSync"), List.of("logs", "crash-reports"), o -> o instanceof String);
        builder.pop();

        builder.comment("Diagnostics.").push("debug");
        DEBUG_LOGGING = builder.comment("Log every synchronization decision (very verbose).").define("logging", false);
        DEBUG_CHUNK_BORDERS = builder.comment("Reserved: visualize chunk ownership in game.").define("chunkBorders", false);
        builder.pop();

        SPEC = builder.build();
    }

    private MultiForgeConfig() {
    }

    /**
     * Rebuilds the immutable snapshot. Called by the mod on every config load or
     * reload event, on the thread that fires the event.
     */
    public static void bake() {
        try {
            snapshot = readValues();
        } catch (IllegalStateException e) {
            // Called before Forge loaded the SERVER config (the world-data sync runs
            // earlier than the config event). Defaults keep the server bootable and
            // the config is baked again on ModConfigEvent.Loading.
            snapshot = Snapshot.defaults();
        }
    }

    private static Snapshot readValues() {
        return new Snapshot(
                MASTER_ADDRESS.get(),
                SERVER_NAME.get(),
                PEER_PORT.get(),
                ADVERTISE_HOST.get(),
                COMPRESSION_THRESHOLD.get(),
                PING_INTERVAL_SECONDS.get(),
                RECONNECT_DELAY_SECONDS.get(),
                IO_TIMEOUT_SECONDS.get(),
                TPS_SYNC_INTERVAL_TICKS.get(),
                CHUNK_SYNC.get(),
                ENTITY_SYNC.get(),
                PLAYER_SYNC.get(),
                WORLD_SYNC.get(),
                KEEP_LOCAL_CHUNK_BACKUP.get(),
                CHUNK_OWNERSHIP_REQUEST_INTERVAL_TICKS.get(),
                CHUNK_UNLOAD_GRACE_TICKS.get(),
                SYNC_LEVEL_DAT.get(),
                SYNC_GAME_RULES.get(),
                SYNC_TIME.get(),
                SYNC_WEATHER.get(),
                SYNC_WORLD_BORDER.get(),
                SYNC_JSON_FILES.get(),
                SYNC_PLAYER_DATA.get(),
                SYNC_STATISTICS.get(),
                SYNC_ADVANCEMENTS.get(),
                List.copyOf(FILES_TO_SYNC_ON_STARTUP.get()),
                List.copyOf(FILES_TO_SYNC_IN_REAL_TIME.get()),
                List.copyOf(FILES_TO_NOT_SYNC.get()),
                DEBUG_LOGGING.get(),
                DEBUG_CHUNK_BORDERS.get());
    }

    /** @return the last baked snapshot */
    public static Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Returns the configuration for start-up code that runs before Forge loads the
     * {@code SERVER} config.
     *
     * <p>{@link #bake()} can only read values after the config event fired, but
     * MultiForge connects to the master inside {@code loadLevel}, which happens
     * earlier. Rather than hard-coding defaults (which would ignore the operator's
     * server name and master address), the TOML file is parsed directly with a small
     * reader for the keys this mod defines. Forge parses the same file later and
     * {@link #bake()} replaces this snapshot with the authoritative one.</p>
     *
     * @param serverDirectory server working directory (contains {@code config})
     * @return startup snapshot, or the current snapshot when no file exists
     */
    public static Snapshot snapshotForStartup(Path serverDirectory) {
        Path file = serverDirectory.resolve("config").resolve("multiforge-server.toml");
        if (!Files.isRegularFile(file)) {
            return snapshot;
        }
        try {
            return parseToml(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return snapshot;
        }
    }

    /**
     * Parses the subset of TOML used by the MultiForge config.
     *
     * <p>Supports sections, quoted strings, integers, booleans and single line string
     * arrays. Unknown keys are ignored. This is intentionally not a general TOML
     * parser: it exists so start-up does not depend on Forge's config lifecycle.</p>
     *
     * @param toml file contents
     * @return snapshot built from the file, with defaults for missing keys
     */
    static Snapshot parseToml(String toml) {
        Snapshot defaults = Snapshot.defaults();
        Map<String, String> values = new HashMap<>();
        String section = "";
        for (String rawLine : toml.split("\n")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            values.put(section + "." + line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }

        return new Snapshot(
                string(values, "master.address", defaults.masterAddress()),
                string(values, "server.name", defaults.serverName()),
                integer(values, "server.peerPort", defaults.peerPort()),
                string(values, "server.advertiseHost", defaults.advertiseHost()),
                integer(values, "server.compressionThreshold", defaults.compressionThreshold()),
                integer(values, "master.pingIntervalSeconds", defaults.pingIntervalSeconds()),
                integer(values, "master.reconnectDelaySeconds", defaults.reconnectDelaySeconds()),
                integer(values, "master.ioTimeoutSeconds", defaults.ioTimeoutSeconds()),
                integer(values, "master.tpsSyncIntervalTicks", defaults.tpsSyncIntervalTicks()),
                bool(values, "synchronization.chunks", defaults.chunkSync()),
                bool(values, "synchronization.entities", defaults.entitySync()),
                bool(values, "synchronization.players", defaults.playerSync()),
                bool(values, "synchronization.world", defaults.worldSync()),
                bool(values, "synchronization.keepLocalChunkBackup", defaults.keepLocalChunkBackup()),
                integer(values, "synchronization.chunkOwnershipRequestIntervalTicks",
                        defaults.chunkOwnershipRequestIntervalTicks()),
                integer(values, "synchronization.chunkUnloadGraceTicks", defaults.chunkUnloadGraceTicks()),
                bool(values, "synchronization.levelDat", defaults.syncLevelDat()),
                bool(values, "synchronization.gameRules", defaults.syncGameRules()),
                bool(values, "synchronization.time", defaults.syncTime()),
                bool(values, "synchronization.weather", defaults.syncWeather()),
                bool(values, "synchronization.worldBorder", defaults.syncWorldBorder()),
                bool(values, "synchronization.jsonFiles", defaults.syncJsonFiles()),
                bool(values, "synchronization.playerData", defaults.syncPlayerData()),
                bool(values, "synchronization.statistics", defaults.syncStatistics()),
                bool(values, "synchronization.advancements", defaults.syncAdvancements()),
                list(values, "synchronization.filesToSyncOnStartup", defaults.filesToSyncOnStartup()),
                list(values, "synchronization.filesToSyncInRealTime", defaults.filesToSyncInRealTime()),
                list(values, "synchronization.filesToNotSync", defaults.filesToNotSync()),
                bool(values, "debug.logging", defaults.debugLogging()),
                bool(values, "debug.chunkBorders", defaults.debugChunkBorders()));
    }

    private static String string(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : unquote(value);
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(unquote(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(unquote(value));
    }

    private static List<String> list(Map<String, String> values, String key, List<String> fallback) {
        String value = values.get(key);
        if (value == null || !value.startsWith("[") || !value.endsWith("]")) {
            return fallback;
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String element : body.split(",")) {
            result.add(unquote(element.trim()));
        }
        return List.copyOf(result);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Immutable view of the configuration used by the runtime.
     *
     * <p>Using a record avoids repeated {@code ForgeConfigSpec} lookups in hot
     * paths (every chunk read/write) and makes tests able to construct arbitrary
     * configurations without a Forge environment.</p>
     */
    public record Snapshot(
            String masterAddress,
            String serverName,
            int peerPort,
            String advertiseHost,
            int compressionThreshold,
            int pingIntervalSeconds,
            int reconnectDelaySeconds,
            int ioTimeoutSeconds,
            int tpsSyncIntervalTicks,
            boolean chunkSync,
            boolean entitySync,
            boolean playerSync,
            boolean worldSync,
            boolean keepLocalChunkBackup,
            int chunkOwnershipRequestIntervalTicks,
            int chunkUnloadGraceTicks,
            boolean syncLevelDat,
            boolean syncGameRules,
            boolean syncTime,
            boolean syncWeather,
            boolean syncWorldBorder,
            boolean syncJsonFiles,
            boolean syncPlayerData,
            boolean syncStatistics,
            boolean syncAdvancements,
            List<String> filesToSyncOnStartup,
            List<String> filesToSyncInRealTime,
            List<String> filesToNotSync,
            boolean debugLogging,
            boolean debugChunkBorders) {

        /** @return snapshot with the documented defaults, used before the config is loaded */
        public static Snapshot defaults() {
            return new Snapshot("localhost:35353", "", 0, "", 0, 5, 2, 30, 20,
                    true, true, true, true, false, 10, 100,
                    true, true, true, true, true, true, true, true, true,
                    List.of(), List.of(), List.of("logs", "crash-reports"), false, false);
        }

        /**
         * @return {@code true} when chunk, entity or player data must be fetched
         *         from the cluster instead of local files
         */
        public boolean usesRemoteStorage() {
            return chunkSync || entitySync || syncPlayerData || syncLevelDat;
        }
    }
}
