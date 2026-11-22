package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Serialized state of one vanilla raid ({@code net.minecraft.world.entity.raid.Raid}).
 *
 * <p>A raid lives in the per-level {@code Raids} saved data, not in a chunk, so the
 * chunk data plane cannot carry it across an ownership handover. The server that
 * owns the raid's centre chunk serializes the raid through its vanilla
 * {@code save(CompoundTag)} method and broadcasts this packet; the receivers keep
 * the copy up to date but never tick it (only the owner does, see
 * {@code RaidMixin}). The boss bar travels separately as a
 * {@link BossEventPacket}.</p>
 *
 * <p>The packet is sent once per changed raid per second and again to the new
 * owner before a fenced handover completes. Vanilla raid NBT is small (a few
 * hundred bytes, more with many raiders), so a full snapshot is cheaper and more
 * robust than a field-level diff.</p>
 *
 * <p>Protocol: appended after {@link BossEventPacket}; the id is part of the
 * protocol and must never be reordered.</p>
 */
public final class RaidStatePacket extends PeerPacket {

    /** Upper bound for one raid snapshot; a real raid is a few KiB at most. */
    public static final int MAX_DATA_LENGTH = 1 << 20;

    /** Logical world name. */
    public final String world;
    /** Vanilla raid id ({@code CompoundTag} "Id"). */
    public final int raidId;
    /** Ownership epoch of the raid's centre chunk when the snapshot was taken. */
    public final long epoch;
    /** Raid snapshot, or {@code null} when the raid ended and the copy must be removed. */
    public final CompoundTag data;

    /**
     * @param world  logical world name
     * @param raidId vanilla raid id
     * @param epoch  ownership epoch of the centre chunk
     * @param data   raid snapshot, {@code null} for a removal
     */
    public RaidStatePacket(String world, int raidId, long epoch, CompoundTag data) {
        this.world = world;
        this.raidId = raidId;
        this.epoch = epoch;
        this.data = data;
    }

    /**
     * @param buf payload positioned after the packet id
     */
    public RaidStatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf(256);
        this.raidId = buf.readVarInt();
        this.epoch = buf.readLong();
        this.data = buf.readBoolean() ? buf.readNbt() : null;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world, 256);
        buf.writeVarInt(raidId);
        buf.writeLong(epoch);
        buf.writeBoolean(data != null);
        if (data != null) {
            buf.writeNbt(data);
        }
    }

    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRemoteRaid(this);
        }
    }
}
