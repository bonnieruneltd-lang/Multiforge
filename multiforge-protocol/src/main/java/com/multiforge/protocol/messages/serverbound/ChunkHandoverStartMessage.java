package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Tells the current chunk owner to drain its data for a handover.
 *
 * <p>The receiving server checks whether it still owns the chunk and whether a
 * real local player is inside it. Without a local player it saves the chunk
 * through the normal chunk IO path, waits until the master acknowledged the
 * writes and then answers {@link com.multiforge.protocol.messages.masterbound.ChunkHandoverReadyMessage}.
 * The master keeps ownership unchanged until that answer arrives.</p>
 *
 * <p>Threading on the game server: the message is decoded on a Netty thread; all
 * world access runs through {@code MultiForgeServer.execute}.</p>
 */
public class ChunkHandoverStartMessage extends ServerBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int cx;
    /** Chunk Z coordinate. */
    public final int cz;
    /** Master-assigned handover id, echoed in the ready message. */
    public final long handoverId;
    /** Server that will own the chunk after a successful handover. */
    public final String newOwner;

    /**
     * @param world      logical world name
     * @param cx         chunk X
     * @param cz         chunk Z
     * @param handoverId master-assigned handover id
     * @param newOwner   server that requested the chunk
     */
    public ChunkHandoverStartMessage(String world, int cx, int cz, long handoverId, String newOwner) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.handoverId = handoverId;
        this.newOwner = newOwner;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public ChunkHandoverStartMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
        handoverId = byteBuf.readLong();
        newOwner = byteBuf.readString();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        byteBuf.writeLong(handoverId);
        byteBuf.writeString(newOwner);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
