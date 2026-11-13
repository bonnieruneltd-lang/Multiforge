package com.multiforge.forge.peer;

import com.multiforge.forge.peer.packet.BlockChangePacket;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;
import com.multiforge.forge.peer.packet.ChatMessagePacket;
import com.multiforge.forge.peer.packet.EntityChunkChangedPacket;
import com.multiforge.forge.peer.packet.EntityHurtPacket;
import com.multiforge.forge.peer.packet.EntityRemovePacket;
import com.multiforge.forge.peer.packet.EntityUpdateNBTPacket;
import com.multiforge.forge.peer.packet.EntityUpdatePacket;
import com.multiforge.forge.peer.packet.EntityUpdateWithDependenciesPacket;
import com.multiforge.forge.peer.packet.GameRuleUpdatePacket;
import com.multiforge.forge.peer.packet.HelloPacket;
import com.multiforge.forge.peer.packet.KeepAlivePacket;
import com.multiforge.forge.peer.packet.LevelDataUpdatePacket;
import com.multiforge.forge.peer.packet.RequestChunkPacket;
import com.multiforge.forge.peer.packet.SendChunkPacket;
import com.multiforge.forge.peer.packet.SetCompressionPacket;
import com.multiforge.forge.peer.packet.TimeSyncPacket;
import com.multiforge.forge.peer.packet.TimeUpdatePacket;
import com.multiforge.forge.peer.packet.WeatherUpdatePacket;
import com.multiforge.forge.peer.packet.WorldBorderUpdatePacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of peer packet types.
 *
 * <p>The wire id is the position in {@link #FACTORIES}. Ids are therefore part of
 * the protocol: only append new packet types at the end, never reorder or remove
 * entries without bumping the protocol version in {@link HelloPacket}.</p>
 *
 * <p>Registration happens in a static initializer and is immutable afterwards,
 * so lookups are lock free and safe from any thread.</p>
 */
public final class PeerProtocol {

    private static final List<Class<? extends PeerPacket>> CLASSES = new ArrayList<>();
    private static final List<Function<FriendlyByteBuf, PeerPacket>> FACTORIES = new ArrayList<>();
    /**
     * Class to wire id. {@code List.indexOf} was a linear scan on every packet
     * send; entity updates send several packets per entity per tick, so the lookup
     * is on a hot path.
     */
    private static final Map<Class<? extends PeerPacket>, Integer> IDS = new HashMap<>();

    static {
        add(HelloPacket.class, HelloPacket::new);
        add(SetCompressionPacket.class, SetCompressionPacket::new);
        add(KeepAlivePacket.class, KeepAlivePacket::new);
        add(RequestChunkPacket.class, RequestChunkPacket::new);
        add(SendChunkPacket.class, SendChunkPacket::new);
        add(BlockChangePacket.class, BlockChangePacket::new);
        add(BlockEntityUpdatePacket.class, BlockEntityUpdatePacket::new);
        add(ChatMessagePacket.class, ChatMessagePacket::new);
        add(TimeUpdatePacket.class, TimeUpdatePacket::new);
        add(WeatherUpdatePacket.class, WeatherUpdatePacket::new);
        add(GameRuleUpdatePacket.class, GameRuleUpdatePacket::new);
        add(WorldBorderUpdatePacket.class, WorldBorderUpdatePacket::new);
        add(EntityChunkChangedPacket.class, EntityChunkChangedPacket::new);
        add(LevelDataUpdatePacket.class, LevelDataUpdatePacket::new);
        add(TimeSyncPacket.class, TimeSyncPacket::new);
        // Entity mirroring (M1). Appended after every earlier id; never reorder.
        add(EntityUpdateNBTPacket.class, EntityUpdateNBTPacket::new);
        add(EntityUpdatePacket.class, EntityUpdatePacket::new);
        add(EntityRemovePacket.class, EntityRemovePacket::new);
        add(EntityUpdateWithDependenciesPacket.class, EntityUpdateWithDependenciesPacket::new);
        add(EntityHurtPacket.class, EntityHurtPacket::new);
    }

    private PeerProtocol() {
    }

    private static void add(Class<? extends PeerPacket> type, Function<FriendlyByteBuf, PeerPacket> factory) {
        IDS.put(type, CLASSES.size());
        CLASSES.add(type);
        FACTORIES.add(factory);
    }

    /**
     * @param packet packet instance
     * @return wire id of the packet type
     * @throws IllegalArgumentException when the packet was never registered
     */
    public static int idOf(PeerPacket packet) {
        Integer id = IDS.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered peer packet " + packet.getClass().getName());
        }
        return id;
    }

    /**
     * @param id  wire id
     * @param buf payload positioned after the id
     * @return decoded packet
     * @throws IllegalArgumentException when the id is unknown
     */
    public static PeerPacket create(int id, FriendlyByteBuf buf) {
        if (id < 0 || id >= FACTORIES.size()) {
            throw new IllegalArgumentException("Unknown peer packet id " + id);
        }
        return FACTORIES.get(id).apply(buf);
    }

    /** @return number of registered packet types (used by tests) */
    public static int size() {
        return CLASSES.size();
    }
}
