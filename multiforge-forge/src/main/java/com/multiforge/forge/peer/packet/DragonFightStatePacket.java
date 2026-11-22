package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Serialized state of the ender dragon fight ({@code EndDragonFight}).
 *
 * <p>The dragon fight is per-level state: the dragon UUID, whether it was killed,
 * the gateway list, the exit-portal location and the respawn phase. It is saved in
 * the level data, not in the chunk, so the chunk data plane cannot carry it when
 * the end island changes owner. The owning server serializes
 * {@code EndDragonFight.saveData(CompoundTag)} and broadcasts this packet; the
 * receiver decodes the vanilla {@code EndDragonFight.Data} codec and applies it to
 * its own fight object.</p>
 *
 * <p>The packet is sent when the serialized state changes (checked once per
 * second) and again to the new owner before a fenced handover. The dragon entity
 * itself is mirrored by the generic entity stream, and the boss bar by
 * {@link BossEventPacket}; this packet only carries the fight phase.</p>
 *
 * <p>Protocol: appended after {@link RaidStatePacket}; never reorder.</p>
 */
public final class DragonFightStatePacket extends PeerPacket {

    /** Upper bound for one fight snapshot; the real tag is a few hundred bytes. */
    public static final int MAX_DATA_LENGTH = 1 << 20;

    /** Logical world name (normally the end dimension). */
    public final String world;
    /** Ownership epoch of the end island chunk when the snapshot was taken. */
    public final long epoch;
    /** Vanilla fight NBT produced by {@code EndDragonFight.saveData()}. */
    public final CompoundTag data;

    /**
     * @param world logical world name
     * @param epoch ownership epoch of the end island chunk
     * @param data  dragon-fight snapshot
     */
    public DragonFightStatePacket(String world, long epoch, CompoundTag data) {
        this.world = world;
        this.epoch = epoch;
        this.data = data;
    }

    /**
     * @param buf payload positioned after the packet id
     */
    public DragonFightStatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf(256);
        this.epoch = buf.readLong();
        this.data = buf.readNbt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world, 256);
        buf.writeLong(epoch);
        buf.writeNbt(data);
    }

    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRemoteDragon(this);
        }
    }
}
