package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Asks a peer for the most recent copy of a chunk.
 *
 * <p>The master answers a chunk read with
 * {@code ChunkLoadedOnAnotherServerMessage} when another server currently holds
 * the chunk. The requester then sends this packet directly to that server, which
 * can serialize the chunk from memory and therefore return data newer than the
 * master's stored copy.</p>
 *
 * <p>{@code requestId} is chosen by the requester and echoed in
 * {@link SendChunkPacket} so concurrent requests may be outstanding.</p>
 */
public class RequestChunkPacket extends PeerPacket {

    /** Correlates the response with this request. */
    public final int requestId;
    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int x;
    /** Chunk Z coordinate. */
    public final int z;

    /**
     * @param requestId correlation id
     * @param world     logical world name
     * @param x         chunk X
     * @param z         chunk Z
     */
    public RequestChunkPacket(int requestId, String world, int x, int z) {
        this.requestId = requestId;
        this.world = world;
        this.x = x;
        this.z = z;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public RequestChunkPacket(FriendlyByteBuf buf) {
        this.requestId = buf.readVarInt();
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(z);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.onRequestChunk(this);
    }
}
