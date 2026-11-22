package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Acknowledges the highest contiguous block/block-entity sequence of a chunk.
 *
 * <p>Receivers answer every applied update with their highest contiguous
 * sequence. A value below what the sender has already sent tells the sender that
 * a packet is missing; it then retransmits the entries still in its bounded
 * history (see {@link com.multiforge.protocol.util.RetransmitBuffer}). The
 * acknowledgement is batched once per chunk per tick, so a busy chunk costs one
 * extra small packet per tick.</p>
 *
 * <p>The epoch in the acknowledgement is the ownership epoch the receiver
 * ordered the update under. When it is newer than the sender's epoch the chunk
 * changed owner and the sender discards its history instead of retransmitting
 * meaningless updates.</p>
 */
public class ChunkSequenceAckPacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Chunk X. */
    public final int x;
    /** Chunk Z. */
    public final int z;
    /** Ownership epoch the acknowledged updates were ordered under. */
    public final long epoch;
    /** Highest contiguous sequence the receiver applied. */
    public final long contiguous;

    /**
     * @param world      logical world name
     * @param x          chunk X
     * @param z          chunk Z
     * @param epoch      ownership epoch
     * @param contiguous highest contiguous sequence applied
     */
    public ChunkSequenceAckPacket(String world, int x, int z, long epoch, long contiguous) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.epoch = epoch;
        this.contiguous = contiguous;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public ChunkSequenceAckPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.epoch = buf.readLong();
        this.contiguous = buf.readLong();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeLong(epoch);
        buf.writeLong(contiguous);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().chunks().onSequenceAck(this, connection);
    }
}
