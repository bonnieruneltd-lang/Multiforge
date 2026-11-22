package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Request that the master move a loaded chunk's ownership to this server.
 *
 * <p>Unlike {@link LockChunkMessage}, which only queues behind the current owner,
 * this starts a fenced handover: the master asks the current owner to flush its
 * chunk data, waits for a {@link ChunkHandoverReadyMessage} and only then grants
 * the lock. It is used when a real player walks into a chunk that another server
 * currently ticks, so the player's server becomes authoritative without waiting
 * for the other server to unload the chunk.</p>
 *
 * <p>Sent by the game server on the master connection. The requester must already
 * be subscribed to the chunk; the master rejects a request for a chunk it does
 * not have loaded. No reply message is required: completion arrives as
 * {@code ChunkHandoverCompleteMessage}.</p>
 *
 * @see ChunkHandoverReadyMessage
 */
public class RequestChunkHandoverMessage extends MasterBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int cx;
    /** Chunk Z coordinate. */
    public final int cz;

    /**
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     */
    public RequestChunkHandoverMessage(String world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public RequestChunkHandoverMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
