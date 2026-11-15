package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Authoritative snapshot of a chunk's subscribers and owner, sent when a server
 * subscribes or asks for a resync.
 *
 * <p>The trailing {@code epoch} is the master's current ownership epoch for the
 * chunk (see {@link SetChunkOwnerMessage}). Receivers store it so block/block
 * entity packets from an older owner can be dropped. Decoding tolerates the
 * field being absent.</p>
 */
public class ChunkSubscribersSyncMessage extends ServerBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int cx;
    /** Chunk Z coordinate. */
    public final int cz;
    /** Current owner name, empty when unowned. */
    public final String owner;
    /** Servers that have the chunk loaded, excluding the receiver. */
    public final String[] subscribers;
    /** Monotonic ownership epoch. */
    public final long epoch;

    /**
     * @param world       logical world name
     * @param cx          chunk X
     * @param cz          chunk Z
     * @param owner       owner name, empty when unowned
     * @param subscribers subscriber names
     */
    public ChunkSubscribersSyncMessage(String world, int cx, int cz, String owner, String[] subscribers) {
        this(world, cx, cz, owner, subscribers, 0L);
    }

    /**
     * @param world       logical world name
     * @param cx          chunk X
     * @param cz          chunk Z
     * @param owner       owner name, empty when unowned
     * @param subscribers subscriber names
     * @param epoch       ownership epoch
     */
    public ChunkSubscribersSyncMessage(String world, int cx, int cz, String owner, String[] subscribers,
                                       long epoch) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.owner = owner;
        this.subscribers = subscribers;
        this.epoch = epoch;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public ChunkSubscribersSyncMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
        owner = byteBuf.readString();
        subscribers = new String[byteBuf.readVarInt()];
        for (int i = 0; i < subscribers.length; i++) {
            subscribers[i] = byteBuf.readString();
        }
        epoch = byteBuf.readableBytes() >= Long.BYTES ? byteBuf.readLong() : 0L;
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        byteBuf.writeString(owner);
        byteBuf.writeVarInt(subscribers.length);
        for (String subscriber : subscribers) {
            byteBuf.writeString(subscriber);
        }
        byteBuf.writeLong(epoch);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
