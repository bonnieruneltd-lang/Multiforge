package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replicates statistics increases of a player to the cluster.
 *
 * <p>Port of MultiPaper's {@code PlayerStatsIncreasePacket}. Statistics are
 * written to the master when the player data is saved (every few minutes or on
 * logout). During a transfer there is a short window where the old server is
 * still authoritative and the new one already accepted the connection; without
 * this packet the last increases (blocks broken, deaths) would only appear after
 * the next save. The receiver applies deltas only to a locally hosted player, so
 * a mirror is never mutated. The owning server buffers increases for ten seconds
 * ({@code PlayerProgressSyncManager}) and sends only the deltas, so a busy
 * player does not produce one packet per block broken.</p>
 *
 * <p>A stat is identified by the registry id of its {@link StatType} and the
 * registry id of the value inside that type, exactly like the vanilla JSON
 * format. Both registries are static and immutable at runtime, so encoding in
 * the constructor and decoding on a Netty thread are safe. The receiver adds the
 * deltas to its own counter (saturating at {@link Integer#MAX_VALUE}) instead of
 * replacing it, which keeps statistics earned locally on the receiving server
 * that the sender has not seen yet.</p>
 *
 * <p>Wire layout: player UUID, then a length-prefixed array of entries
 * ({@code typeId}, {@code valueId}, {@code delta}). Max 1000 entries per packet,
 * bounded by the frame size.</p>
 */
public final class PlayerStatsIncreasePacket extends PeerPacket {

    /** Largest number of statistic deltas carried by one packet. */
    public static final int MAX_ENTRIES = 1024;

    private final UUID uuid;
    private final List<Entry> entries;

    /**
     * @param uuid      owner player
     * @param increases statistic deltas, at most {@link #MAX_ENTRIES}
     */
    public PlayerStatsIncreasePacket(UUID uuid, Map<Stat<?>, Integer> increases) {
        this.uuid = uuid;
        List<Entry> list = new ArrayList<>(increases.size());
        for (Map.Entry<Stat<?>, Integer> entry : increases.entrySet()) {
            ResourceLocation typeId = BuiltInRegistries.STAT_TYPE.getKey(entry.getKey().getType());
            ResourceLocation valueId = valueId(entry.getKey());
            if (typeId == null || valueId == null) {
                continue;
            }
            list.add(new Entry(typeId.toString(), valueId.toString(), entry.getValue()));
        }
        this.entries = List.copyOf(list);
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public PlayerStatsIncreasePacket(FriendlyByteBuf buf) {
        this.uuid = buf.readUUID();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid stat entry count " + count);
        }
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readVarInt()));
        }
        this.entries = List.copyOf(list);
    }

    /**
     * @param stat statistic
     * @return registry id of the statistic's value, or {@code null} when unknown
     */
    private static ResourceLocation valueId(Stat<?> stat) {
        return valueIdUnchecked(stat);
    }

    @SuppressWarnings("unchecked")
    private static <T> ResourceLocation valueIdUnchecked(Stat<T> stat) {
        Registry<T> registry = stat.getType().getRegistry();
        return registry.getKey(stat.getValue());
    }

    /**
     * @return owner player UUID
     */
    public UUID uuid() {
        return uuid;
    }

    /**
     * @return statistic deltas as registry ids
     */
    public List<Entry> entries() {
        return entries;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUtf(entry.typeId());
            buf.writeUtf(entry.valueId());
            buf.writeVarInt(entry.delta());
        }
    }

    /**
     * Applies the deltas on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.progress().onRemoteStatIncreases(this);
        }
    }

    /**
     * One statistic delta on the wire.
     *
     * @param typeId  registry id of the stat type, e.g. {@code minecraft:mined}
     * @param valueId registry id of the value, e.g. {@code minecraft:stone}
     * @param delta   increase
     */
    public record Entry(String typeId, String valueId, int delta) {
    }
}
