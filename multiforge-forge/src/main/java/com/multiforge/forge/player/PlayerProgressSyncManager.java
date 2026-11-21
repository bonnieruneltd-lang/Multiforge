package com.multiforge.forge.player;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.AdvancementProgressPacket;
import com.multiforge.forge.peer.packet.PlayerStatsIncreasePacket;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors live statistics and advancement progress between servers.
 *
 * <p>Port of MultiPaper's {@code MultiPaperStatHandler},
 * {@code PlayerStatsIncreasePacket} and
 * {@code AdvancementGrantProgressPacket}. Statistic and advancement files are
 * authoritative on the master, but they are only written when the player data is
 * saved. Without this manager a player who breaks ten blocks on {@code survival1}
 * and moves to {@code survival2} sees the ten blocks missing from their
 * statistics until the next save, and an advancement earned in the last seconds
 * before a transfer can be lost entirely.</p>
 *
 * <p><b>Statistics.</b> {@code StatsCounter.increment} is hooked by
 * {@code StatsCounterIncrementMixin}; the delta is accumulated per player and
 * flushed every {@link #STAT_FLUSH_TICKS} ticks (ten seconds), the same batching
 * upstream uses. The receiver adds the delta to its own counter, saturating at
 * {@link Integer#MAX_VALUE}, so statistics earned locally in the meantime are not
 * overwritten. Because applying uses {@code setValue}, which does not go through
 * {@code increment}, a replayed delta cannot echo back to the cluster.</p>
 *
 * <p><b>Advancements.</b> {@code PlayerAdvancements.award} is hooked at RETURN
 * and the criterion is broadcast immediately when vanilla accepted it. The
 * receiving server calls {@code award} on its local player under an
 * {@code APPLYING} flag, so the resulting local broadcast does not produce a
 * second peer packet. A missing advancement id (a mod removed from one server)
 * is ignored.</p>
 *
 * <p>Threading: outbound hooks run on the server thread. Inbound packet handles
 * only hop to the server thread through {@link MultiForgeServer#execute}; the
 * world and player state is never touched from a Netty thread. Mirrors
 * ({@link ExternalPlayer}) are ignored on both sides: only the server hosting the
 * connection owns the player's statistics.</p>
 */
public final class PlayerProgressSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Progress");

    /** Ticks between statistic flushes; matches upstream's ten second batching. */
    public static final int STAT_FLUSH_TICKS = 200;

    /** Guards against a runaway peer incrementing the same stat forever. */
    private static final int MAX_DELTA = 1_000_000;

    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final MultiForgeServer server;

    /** Pending statistic increases per local player; server thread only. */
    private final Map<UUID, Map<Stat<?>, Integer>> pendingStats = new LinkedHashMap<>();

    private int ticksUntilFlush = STAT_FLUSH_TICKS;

    /**
     * @param server runtime that owns this manager
     */
    public PlayerProgressSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Records a statistic increase for the next flush.
     *
     * <p>Called from {@code StatsCounter.increment} before vanilla applies the
     * value, on the server thread.</p>
     *
     * @param player player whose statistic changed
     * @param stat   statistic
     * @param amount increase
     */
    public void onStatIncrease(Player player, Stat<?> stat, int amount) {
        if (!server.config().syncStatistics() || amount <= 0 || stat == null
                || !(player instanceof ServerPlayer serverPlayer)
                || serverPlayer instanceof ExternalPlayer) {
            return;
        }
        Map<Stat<?>, Integer> stats = pendingStats.computeIfAbsent(player.getUUID(),
                ignored -> new HashMap<>());
        int current = stats.getOrDefault(stat, 0);
        stats.put(stat, Math.min(MAX_DELTA, current + amount));
    }

    /**
     * Broadcasts an advancement criterion awarded by vanilla.
     *
     * <p>Called from {@code PlayerAdvancements.award} at RETURN when vanilla
     * accepted the award, on the server thread.</p>
     *
     * @param player      player that earned the advancement
     * @param advancement earned advancement
     * @param criterion   criterion that completed
     */
    public void onAdvancementAwarded(ServerPlayer player, Advancement advancement, String criterion) {
        if (!server.config().syncAdvancements() || player instanceof ExternalPlayer
                || Boolean.TRUE.equals(APPLYING.get()) || advancement == null
                || criterion == null) {
            return;
        }
        ResourceLocation id = advancement.getId();
        server.peers().broadcast(new AdvancementProgressPacket(player.getUUID(), id.toString(), criterion));
    }

    /**
     * Flushes pending statistics every ten seconds.
     *
     * <p>Called once per server tick from {@link MultiForgeServer#tick()} on the
     * server thread.</p>
     */
    public void tick() {
        if (!server.config().syncStatistics() && !server.config().syncAdvancements()) {
            pendingStats.clear();
            return;
        }
        if (--ticksUntilFlush > 0) {
            return;
        }
        ticksUntilFlush = STAT_FLUSH_TICKS;
        flushStats();
    }

    /**
     * Sends one packet per player that has pending increases. Dropped when no peer
     * connection exists; the player data file sync rebuilds the state after a
     * reconnect.
     */
    private void flushStats() {
        if (!server.config().syncStatistics() || pendingStats.isEmpty() || !server.peers().hasConnections()) {
            pendingStats.clear();
            return;
        }
        for (Map.Entry<UUID, Map<Stat<?>, Integer>> entry : pendingStats.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            server.peers().broadcast(new PlayerStatsIncreasePacket(entry.getKey(), entry.getValue()));
        }
        pendingStats.clear();
    }

    /**
     * Applies statistic deltas from a peer on the server thread.
     *
     * @param packet received packet
     */
    public void onRemoteStatIncreases(PlayerStatsIncreasePacket packet) {
        server.execute(() -> {
            if (!server.config().syncStatistics()) {
                return;
            }
            ServerPlayer player = server.server().getPlayerList().getPlayer(packet.uuid());
            if (player == null || player instanceof ExternalPlayer) {
                return;
            }
            for (PlayerStatsIncreasePacket.Entry entry : packet.entries()) {
                Stat<?> stat = resolveStat(entry.typeId(), entry.valueId());
                if (stat == null) {
                    continue;
                }
                int delta = Math.min(MAX_DELTA, Math.max(0, entry.delta()));
                int newValue = (int) Math.min((long) player.getStats().getValue(stat) + delta,
                        Integer.MAX_VALUE);
                player.getStats().setValue(player, stat, newValue);
            }
        });
    }

    /**
     * Applies an advancement award from a peer on the server thread.
     *
     * @param packet received packet
     */
    public void onRemoteAdvancement(AdvancementProgressPacket packet) {
        server.execute(() -> {
            if (!server.config().syncAdvancements()) {
                return;
            }
            ServerPlayer player = server.server().getPlayerList().getPlayer(packet.uuid());
            if (player == null || player instanceof ExternalPlayer) {
                return;
            }
            Advancement advancement = server.server().getAdvancements()
                    .getAdvancement(new ResourceLocation(packet.advancement()));
            if (advancement == null) {
                LOGGER.debug("Ignoring advancement {} that this server does not know",
                        packet.advancement());
                return;
            }
            APPLYING.set(Boolean.TRUE);
            try {
                player.getAdvancements().award(advancement, packet.criterion());
            } finally {
                APPLYING.set(Boolean.FALSE);
            }
        });
    }

    /**
     * Resolves a statistic from the registry ids on the wire.
     *
     * @param typeId  stat type registry id
     * @param valueId value registry id inside that type
     * @return statistic, or {@code null} when either id is unknown here
     */
    private static Stat<?> resolveStat(String typeId, String valueId) {
        StatType<?> type = BuiltInRegistries.STAT_TYPE.get(new ResourceLocation(typeId));
        if (type == null) {
            return null;
        }
        return resolveStat(type, valueId);
    }

    /**
     * @param type    statistic type
     * @param valueId value registry id
     * @param <T>     registry value type
     * @return statistic, or {@code null} when the value is unknown
     */
    private static <T> Stat<?> resolveStat(StatType<T> type, String valueId) {
        Registry<T> registry = type.getRegistry();
        T value = registry.get(new ResourceLocation(valueId));
        if (value == null) {
            return null;
        }
        return type.get(value);
    }

    /** Drops buffered state, called when the server stops. */
    public void clear() {
        pendingStats.clear();
        ticksUntilFlush = STAT_FLUSH_TICKS;
    }
}
