package com.multiforge.forge.world;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.packet.GameRuleUpdatePacket;
import com.multiforge.forge.peer.packet.LevelDataUpdatePacket;
import com.multiforge.forge.peer.packet.TimeUpdatePacket;
import com.multiforge.forge.peer.packet.WeatherUpdatePacket;
import com.multiforge.forge.peer.packet.WorldBorderUpdatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps the mutable world state of every level consistent across the cluster.
 *
 * <p>Ports MultiPaper's time, weather, gamerule, world border and level data
 * synchronizers. The control plane owns durable data; this manager only handles
 * the values vanilla keeps in memory and writes back through {@code level.dat}.
 * Values are compared once per second on the server thread and only changes are
 * broadcast as peer packets, so an idle cluster produces almost no traffic.</p>
 *
 * <table>
 *   <caption>Synced values</caption>
 *   <tr><th>Value</th><th>Packet</th><th>Policy</th></tr>
 *   <tr><td>day time</td><td>{@link TimeUpdatePacket}</td><td>broadcast every second, last writer wins</td></tr>
 *   <tr><td>rain/thunder + timers</td><td>{@link WeatherUpdatePacket}</td><td>broadcast on a state change or timer reset</td></tr>
 *   <tr><td>gamerules</td><td>{@link GameRuleUpdatePacket}</td><td>changed rules, applied with {@code /gamerule}</td></tr>
 *   <tr><td>world border</td><td>{@link WorldBorderUpdatePacket}</td><td>last writer wins</td></tr>
 *   <tr><td>difficulty, spawn</td><td>{@link LevelDataUpdatePacket}</td><td>MultiForge NBT subset, last writer wins</td></tr>
 * </table>
 *
 * <p>Time is broadcast unconditionally once per second in both directions, which
 * is what upstream MultiPaper does ({@code TimeUpdatePacket} every 20 ticks).
 * A conditional comparison would never fire because every server advances its
 * own day time; the unconditional broadcast also corrects drift after a lag
 * spike. Weather is different: vanilla decrements the timers on every server, so
 * broadcasting on every numeric difference would create a feedback loop. Instead
 * a packet is sent only when the rain/thunder state flips or a timer jumps
 * upwards (a {@code /weather} command), and the local snapshot is refreshed on
 * every poll.</p>
 *
 * <p>Threading: {@link #tick()} runs on the server thread. The {@code onRemote*}
 * methods are called by {@link PeerConnection} on a Netty thread; they hop to the
 * server thread through {@link MultiForgeServer#execute(Runnable)} before touching
 * any level state. Everything else is guarded by {@link #lock} so the periodic
 * poll and the inbound application cannot interleave.</p>
 *
 * <p>Assumptions: all servers track the same worlds under the same logical names
 * (see {@code WorldDirectories}); a packet for a world that is not loaded locally
 * is dropped. World state does not involve the master.</p>
 */
public final class WorldSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/World");

    /** Gamerule ids are restricted to this alphabet before they are used in a command. */
    private static final Pattern SAFE_RULE = Pattern.compile("[a-zA-Z0-9_]{1,64}");
    /** Values accepted by the {@code /gamerule} command. */
    private static final Pattern SAFE_VALUE = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    /** Packet NBT keys of the MultiForge level data subset. */
    private static final String TAG_DIFFICULTY = "Difficulty";
    private static final String TAG_SPAWN_X = "SpawnX";
    private static final String TAG_SPAWN_Y = "SpawnY";
    private static final String TAG_SPAWN_Z = "SpawnZ";
    private static final String TAG_SPAWN_ANGLE = "SpawnAngle";
    /** Floating point tolerance for world border comparisons. */
    private static final double EPSILON = 1.0e-6D;

    private final MultiForgeServer server;
    private final Object lock = new Object();
    private final Map<String, LevelState> states = new HashMap<>();

    private volatile int tickCounter;

    /**
     * @param server owning MultiForge server; provides the levels, the peer
     *               manager and the configuration
     */
    public WorldSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Mutable snapshot of one level's synchronized world state.
     *
     * <p>Only accessed while holding the manager lock. {@code initialized} is false
     * until the first poll recorded a baseline; baseline recording never
     * broadcasts, which prevents a startup storm when several servers join with
     * slightly different state.</p>
     */
    private static final class LevelState {
        private boolean initialized;
        private long dayTime;
        private boolean raining;
        private int rainTime;
        private boolean thundering;
        private int thunderTime;
        private final Map<String, String> gameRules = new HashMap<>();
        private double borderCenterX;
        private double borderCenterZ;
        private double borderSize;
        private double borderDamagePerBlock;
        private double borderSafeZone;
        private int borderWarningBlocks;
        private int borderWarningTime;
        private String difficulty = Difficulty.NORMAL.getSerializedName();
        private int spawnX;
        private int spawnY;
        private int spawnZ;
        private float spawnAngle;
    }

    // ------------------------------------------------------------------
    // Lifecycle and polling
    // ------------------------------------------------------------------

    /**
     * Polls the world state once per second and broadcasts the changes.
     *
     * <p>Called from {@link MultiForgeServer#tick()} on the server thread. Does
     * nothing when world synchronization is disabled or the server is stopping.</p>
     */
    public void tick() {
        if (!server.config().worldSync() || server.isStopping()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        poll();
    }

    /**
     * Forgets every snapshot so the next poll re-baselines from the local world.
     *
     * <p>Called when the master connection is re-established. World state is
     * peer-to-peer and would not reset with the master, but a reconnect often
     * follows a network partition during which values diverged; re-baselining
     * lets the next poll publish the local values instead of comparing them with
     * a stale snapshot.</p>
     */
    public void invalidate() {
        synchronized (lock) {
            states.clear();
        }
    }

    /**
     * Compares every loaded level with its snapshot and broadcasts the changes.
     */
    private void poll() {
        synchronized (lock) {
            for (ServerLevel level : server.server().getAllLevels()) {
                String world = server.chunks().worldName(level);
                LevelState state = states.computeIfAbsent(world, ignored -> new LevelState());
                try {
                    pollLevel(level, world, state);
                } catch (RuntimeException e) {
                    LOGGER.warn("Could not synchronize world state of {}: {}", world, e.toString());
                }
            }
        }
    }

    /**
     * Polls one level.
     *
     * @param level level to inspect
     * @param world logical world name
     * @param state snapshot of that level
     */
    private void pollLevel(ServerLevel level, String world, LevelState state) {
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        if (server.config().syncTime()) {
            // Upstream broadcasts time unconditionally every 20 ticks.
            server.peers().broadcast(new TimeUpdatePacket(world, level.getDayTime()));
            state.dayTime = level.getDayTime();
        }
        if (server.config().syncWeather()) {
            pollWeather(world, state, data);
        }
        if (server.config().syncGameRules()) {
            pollGameRules(level, world, state);
        }
        if (server.config().syncWorldBorder()) {
            pollWorldBorder(level, world, state);
        }
        pollLevelData(level, world, state);
        state.initialized = true;
    }


    /**
     * Detects weather transitions and {@code /weather} timer resets.
     *
     * <p>Normal ticking decrements both timers by one per tick on every server;
     * comparing them numerically would make every server rebroadcast every
     * second. A packet is therefore only sent when the rain or thunder state
     * changes, or when a timer moved upwards since the previous poll (a command
     * reset the weather). The snapshot is refreshed unconditionally.</p>
     *
     * @param world logical world name
     * @param state snapshot
     * @param data  level data
     */
    private void pollWeather(String world, LevelState state, ServerLevelData data) {
        boolean raining = data.isRaining();
        boolean thundering = data.isThundering();
        int rainTime = data.getRainTime();
        int thunderTime = data.getThunderTime();
        boolean changed = state.initialized
                && (raining != state.raining
                        || thundering != state.thundering
                        || rainTime > state.rainTime
                        || thunderTime > state.thunderTime);
        if (changed) {
            server.peers().broadcast(new WeatherUpdatePacket(world, raining, rainTime, thundering, thunderTime));
            if (server.config().debugLogging()) {
                LOGGER.info("Broadcast weather for {}: rain={}, thunder={}", world, raining, thundering);
            }
        }
        state.raining = raining;
        state.rainTime = rainTime;
        state.thundering = thundering;
        state.thunderTime = thunderTime;
    }

    /**
     * Diffs the gamerule table and broadcasts every changed rule.
     *
     * <p>{@link GameRules#createTag()} serializes all rules as strings, which is
     * the same representation the {@code /gamerule} command accepts on the
     * receiving side.</p>
     *
     * @param level level to inspect
     * @param world logical world name
     * @param state snapshot
     */
    private void pollGameRules(ServerLevel level, String world, LevelState state) {
        CompoundTag tag = level.getGameRules().createTag();
        boolean baseline = !state.initialized;
        for (String rule : tag.getAllKeys()) {
            String value = tag.getString(rule);
            String previous = state.gameRules.put(rule, value);
            if (baseline || java.util.Objects.equals(previous, value)) {
                continue;
            }
            server.peers().broadcast(new GameRuleUpdatePacket(world, rule, value));
            if (server.config().debugLogging()) {
                LOGGER.info("Broadcast gamerule {}={} for {}", rule, value, world);
            }
        }
    }

    /**
     * Diffs the world border and broadcasts a full snapshot when it changed.
     *
     * @param level level to inspect
     * @param world logical world name
     * @param state snapshot
     */
    private void pollWorldBorder(ServerLevel level, String world, LevelState state) {
        WorldBorder border = level.getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double size = border.getSize();
        double damagePerBlock = border.getDamagePerBlock();
        double safeZone = border.getDamageSafeZone();
        int warningBlocks = border.getWarningBlocks();
        int warningTime = border.getWarningTime();
        boolean changed = state.initialized && (different(centerX, state.borderCenterX)
                || different(centerZ, state.borderCenterZ)
                || different(size, state.borderSize)
                || different(damagePerBlock, state.borderDamagePerBlock)
                || different(safeZone, state.borderSafeZone)
                || warningBlocks != state.borderWarningBlocks
                || warningTime != state.borderWarningTime);
        if (changed) {
            server.peers().broadcast(new WorldBorderUpdatePacket(world, centerX, centerZ, size,
                    damagePerBlock, safeZone, warningBlocks, warningTime));
        }
        state.borderCenterX = centerX;
        state.borderCenterZ = centerZ;
        state.borderSize = size;
        state.borderDamagePerBlock = damagePerBlock;
        state.borderSafeZone = safeZone;
        state.borderWarningBlocks = warningBlocks;
        state.borderWarningTime = warningTime;
    }


    /**
     * Diffs difficulty and spawn point, the two level data values that matter
     * across servers, and broadcasts them as a compact NBT subset.
     *
     * <p>The full {@code level.dat} is owned by the master; carrying it in a peer
     * packet would duplicate that channel and race with the file upload. The
     * subset is therefore deliberately limited to values a player notices
     * immediately (difficulty and respawn point) and is documented in
     * SYNCHRONIZATION.md.</p>
     *
     * @param level level to inspect
     * @param world logical world name
     * @param state snapshot
     */
    private void pollLevelData(ServerLevel level, String world, LevelState state) {
        var data = level.getLevelData();
        String difficulty = level.getDifficulty().getSerializedName();
        int spawnX = data.getXSpawn();
        int spawnY = data.getYSpawn();
        int spawnZ = data.getZSpawn();
        float spawnAngle = data.getSpawnAngle();
        boolean changed = state.initialized && (!difficulty.equals(state.difficulty)
                || spawnX != state.spawnX
                || spawnY != state.spawnY
                || spawnZ != state.spawnZ
                || Float.compare(spawnAngle, state.spawnAngle) != 0);
        if (changed) {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIFFICULTY, difficulty);
            tag.putInt(TAG_SPAWN_X, spawnX);
            tag.putInt(TAG_SPAWN_Y, spawnY);
            tag.putInt(TAG_SPAWN_Z, spawnZ);
            tag.putFloat(TAG_SPAWN_ANGLE, spawnAngle);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(128);
                NbtIo.writeCompressed(tag, out);
                server.peers().broadcast(new LevelDataUpdatePacket(world, out.toByteArray()));
            } catch (IOException e) {
                LOGGER.warn("Could not serialize level data of {}: {}", world, e.toString());
            }
        }
        state.difficulty = difficulty;
        state.spawnX = spawnX;
        state.spawnY = spawnY;
        state.spawnZ = spawnZ;
        state.spawnAngle = spawnAngle;
    }

    /**
     * @param a first value
     * @param b second value
     * @return {@code true} when the values differ by more than {@link #EPSILON}
     */
    private static boolean different(double a, double b) {
        return Math.abs(a - b) > EPSILON;
    }

    // ------------------------------------------------------------------
    // Inbound peer updates
    // ------------------------------------------------------------------

    /**
     * Applies a day time broadcast from another server.
     *
     * @param packet time packet
     */
    public void onRemoteTime(TimeUpdatePacket packet) {
        if (!server.config().syncTime() || !server.config().worldSync()) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            synchronized (lock) {
                if (level.getDayTime() != packet.dayTime) {
                    level.setDayTime(packet.dayTime);
                }
                LevelState state = states.computeIfAbsent(packet.world, ignored -> new LevelState());
                state.dayTime = packet.dayTime;
            }
        });
    }

    /**
     * Applies a weather broadcast from another server.
     *
     * <p>The timers are written directly instead of through
     * {@code setWeatherParameters}, because that helper assigns a single timer to
     * both rain and thunder while the packet carries them separately.</p>
     *
     * @param packet weather packet
     */
    public void onRemoteWeather(WeatherUpdatePacket packet) {
        if (!server.config().syncWeather() || !server.config().worldSync()) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            synchronized (lock) {
                ServerLevelData data = (ServerLevelData) level.getLevelData();
                data.setRaining(packet.raining);
                data.setRainTime(packet.rainTime);
                data.setThundering(packet.thundering);
                data.setThunderTime(packet.thunderTime);
                LevelState state = states.computeIfAbsent(packet.world, ignored -> new LevelState());
                state.raining = packet.raining;
                state.rainTime = packet.rainTime;
                state.thundering = packet.thundering;
                state.thunderTime = packet.thunderTime;
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Applied weather for {}: rain={}, thunder={}",
                        packet.world, packet.raining, packet.thundering);
            }
        });
    }


    /**
     * Applies a gamerule change from another server.
     *
     * <p>Uses the vanilla {@code /gamerule} command so the rule type (boolean,
     * integer, enum) is parsed and validated by the same code path a player
     * command uses. The rule id and value are validated against strict patterns
     * first, so a malicious or buggy peer cannot inject command syntax. The
     * command output is suppressed.</p>
     *
     * @param packet gamerule packet
     */
    public void onRemoteGameRule(GameRuleUpdatePacket packet) {
        if (!server.config().syncGameRules() || !server.config().worldSync()) {
            return;
        }
        if (!SAFE_RULE.matcher(packet.rule).matches() || !SAFE_VALUE.matcher(packet.value).matches()) {
            LOGGER.warn("Ignoring invalid gamerule update {}={}", packet.rule, packet.value);
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            String command = "gamerule " + packet.rule + " " + packet.value;
            var source = server.server().createCommandSourceStack()
                    .withSuppressedOutput()
                    .withPermission(4)
                    .withLevel(level);
            server.server().getCommands().performPrefixedCommand(source, command);
            synchronized (lock) {
                states.computeIfAbsent(packet.world, ignored -> new LevelState())
                        .gameRules.put(packet.rule, packet.value);
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Applied gamerule {}={} for {}", packet.rule, packet.value, packet.world);
            }
        });
    }

    /**
     * Applies a world border snapshot from another server.
     *
     * @param packet border packet
     */
    public void onRemoteWorldBorder(WorldBorderUpdatePacket packet) {
        if (!server.config().syncWorldBorder() || !server.config().worldSync()) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            synchronized (lock) {
                WorldBorder border = level.getWorldBorder();
                border.setCenter(packet.centerX, packet.centerZ);
                border.setSize(packet.size);
                border.setDamagePerBlock(packet.damagePerBlock);
                border.setDamageSafeZone(packet.damageSafeZone);
                border.setWarningBlocks(packet.warningBlocks);
                border.setWarningTime(packet.warningTime);
                LevelState state = states.computeIfAbsent(packet.world, ignored -> new LevelState());
                state.borderCenterX = packet.centerX;
                state.borderCenterZ = packet.centerZ;
                state.borderSize = packet.size;
                state.borderDamagePerBlock = packet.damagePerBlock;
                state.borderSafeZone = packet.damageSafeZone;
                state.borderWarningBlocks = packet.warningBlocks;
                state.borderWarningTime = packet.warningTime;
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Applied world border for {} (size {})", packet.world, packet.size);
            }
        });
    }


    /**
     * Applies the level data subset (difficulty and spawn) from another server.
     *
     * <p>The payload is the compressed NBT written by {@link #pollLevelData}.
     * Unknown or missing tags are ignored so a newer sender cannot break an older
     * receiver. Difficulty is applied through
     * {@link net.minecraft.server.MinecraftServer} because it is a global
     * property; the spawn point is applied per level.</p>
     *
     * @param packet level data packet
     */
    public void onRemoteLevelData(LevelDataUpdatePacket packet) {
        if (!server.config().worldSync()) {
            return;
        }
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(new ByteArrayInputStream(packet.data));
        } catch (IOException e) {
            LOGGER.warn("Could not read level data for {}: {}", packet.world, e.toString());
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            synchronized (lock) {
                if (tag.contains(TAG_DIFFICULTY)) {
                    Difficulty difficulty = Difficulty.byName(tag.getString(TAG_DIFFICULTY));
                    if (difficulty != null && !difficulty.equals(level.getDifficulty())) {
                        server.server().setDifficulty(difficulty, false);
                    }
                }
                if (tag.contains(TAG_SPAWN_X) && tag.contains(TAG_SPAWN_Y) && tag.contains(TAG_SPAWN_Z)) {
                    BlockPos spawn = new BlockPos(tag.getInt(TAG_SPAWN_X), tag.getInt(TAG_SPAWN_Y),
                            tag.getInt(TAG_SPAWN_Z));
                    float angle = tag.contains(TAG_SPAWN_ANGLE) ? tag.getFloat(TAG_SPAWN_ANGLE) : 0.0F;
                    level.setDefaultSpawnPos(spawn, angle);
                }
                LevelState state = states.computeIfAbsent(packet.world, ignored -> new LevelState());
                if (tag.contains(TAG_DIFFICULTY)) {
                    state.difficulty = tag.getString(TAG_DIFFICULTY);
                }
                if (tag.contains(TAG_SPAWN_X)) {
                    state.spawnX = tag.getInt(TAG_SPAWN_X);
                    state.spawnY = tag.getInt(TAG_SPAWN_Y);
                    state.spawnZ = tag.getInt(TAG_SPAWN_Z);
                    state.spawnAngle = tag.contains(TAG_SPAWN_ANGLE) ? tag.getFloat(TAG_SPAWN_ANGLE) : 0.0F;
                }
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Applied level data for {}", packet.world);
            }
        });
    }

    /**
     * @param world logical world name
     * @return snapshot of the last synchronized gamerules, for diagnostics
     */
    public Map<String, String> gameRuleSnapshot(String world) {
        synchronized (lock) {
            LevelState state = states.get(world);
            return state == null ? Map.of() : Map.copyOf(state.gameRules);
        }
    }
}

