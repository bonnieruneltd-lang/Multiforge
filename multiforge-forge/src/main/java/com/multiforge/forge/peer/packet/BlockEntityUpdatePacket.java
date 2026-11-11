package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Broadcasts the NBT of a changed block entity (signs, chests, hoppers, ...).
 *
 * <p>MultiPaper calls this "block entity syncing". The sending server stores the
 * block entity's full metadata so the receiver can replace its own instance
 * without guessing which fields changed.</p>
 */
public class BlockEntityUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Block position. */
    public final int x;
    /** Block position. */
    public final int y;
    /** Block position. */
    public final int z;
    /** Full block entity NBT including id, x, y and z. */
    public final CompoundTag tag;

    /**
     * @param world logical world name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @param tag   block entity NBT
     */
    public BlockEntityUpdatePacket(String world, int x, int y, int z, CompoundTag tag) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tag = tag;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public BlockEntityUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.tag = buf.readNbt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeNbt(tag);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().chunks().applyRemoteBlockEntity(this);
    }
}
