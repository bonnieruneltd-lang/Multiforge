package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Response to {@link RequestChunkPacket}.
 *
 * <p>{@code data} is a zlib compressed NBT chunk exactly as it would be stored in
 * a region file. An empty array means the peer no longer has the chunk loaded;
 * the requester then falls back to the master's stored copy.</p>
 */
public class SendChunkPacket extends PeerPacket {

    /** Correlation id from the request. */
    public final int requestId;
    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int x;
    /** Chunk Z coordinate. */
    public final int z;
    /** Compressed chunk NBT, possibly empty. */
    public final byte[] data;

    /**
     * @param requestId correlation id
     * @param world     logical world name
     * @param x         chunk X
     * @param z         chunk Z
     * @param data      compressed NBT
     */
    public SendChunkPacket(int requestId, String world, int x, int z, byte[] data) {
        this.requestId = requestId;
        this.world = world;
        this.x = x;
        this.z = z;
        this.data = data;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public SendChunkPacket(FriendlyByteBuf buf) {
        this.requestId = buf.readVarInt();
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.data = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeByteArray(data);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.onSendChunk(this);
    }
}
