package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Master announcement of a chunk's authoritative owner.
 *
 * <p>The message carries an {@code epoch} that the master increments every time
 * the owning server changes (including becoming unowned). Peer block and block
 * entity packets are stamped with the epoch they were produced under; a receiver
 * drops packets whose epoch is older than the newest epoch it has seen for that
 * chunk. That closes the window in which a delayed packet from a previous owner
 * could overwrite the new owner's state.</p>
 *
 * <p>The epoch is appended to the payload. Decoding tolerates the missing field
 * so a connection to an older master still works (epoch {@code 0}, i.e. no
 * filtering).</p>
 */
public class SetChunkOwnerMessage extends ServerBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int cx;
    /** Chunk Z coordinate. */
    public final int cz;
    /** Name of the owning server, empty when the chunk became unowned. */
    public final String owner;
    /** Monotonic ownership epoch, incremented by the master on every owner change. */
    public final long epoch;

    /**
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     * @param owner owner name, empty when unowned
     */
    public SetChunkOwnerMessage(String world, int cx, int cz, String owner) {
        this(world, cx, cz, owner, 0L);
    }

    /**
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     * @param owner owner name, empty when unowned
     * @param epoch ownership epoch
     */
    public SetChunkOwnerMessage(String world, int cx, int cz, String owner, long epoch) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.owner = owner;
        this.epoch = epoch;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public SetChunkOwnerMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
        owner = byteBuf.readString();
        // Appended field: tolerate a payload written by an older master.
        epoch = byteBuf.readableBytes() >= Long.BYTES ? byteBuf.readLong() : 0L;
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        byteBuf.writeString(owner);
        byteBuf.writeLong(epoch);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
