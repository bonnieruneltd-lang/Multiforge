package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Final result of a chunk handover, sent to the old and the new owner.
 *
 * <p>A successful completion is informational on the game server: the
 * authoritative ownership announcement is still {@link SetChunkOwnerMessage},
 * which carries the new epoch. The message exists so both sides can clear their
 * pending handover state and log why a request was refused or timed out.</p>
 *
 * <p>{@code owner} is the owner after the attempt: the requester on success and
 * the previous owner on refusal. {@code epoch} is the epoch that applies to that
 * owner.</p>
 */
public class ChunkHandoverCompleteMessage extends ServerBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int cx;
    /** Chunk Z coordinate. */
    public final int cz;
    /** Master-assigned handover id. */
    public final long handoverId;
    /** Whether ownership moved to the requester. */
    public final boolean success;
    /** Owner after the attempt; empty when the chunk is unowned. */
    public final String owner;
    /** Ownership epoch that applies to {@link #owner}. */
    public final long epoch;

    /**
     * @param world      logical world name
     * @param cx         chunk X
     * @param cz         chunk Z
     * @param handoverId master-assigned handover id
     * @param success    whether ownership moved
     * @param owner      owner after the attempt
     * @param epoch      current ownership epoch
     */
    public ChunkHandoverCompleteMessage(String world, int cx, int cz, long handoverId,
                                        boolean success, String owner, long epoch) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.handoverId = handoverId;
        this.success = success;
        this.owner = owner;
        this.epoch = epoch;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public ChunkHandoverCompleteMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        cx = byteBuf.readInt();
        cz = byteBuf.readInt();
        handoverId = byteBuf.readLong();
        success = byteBuf.readBoolean();
        owner = byteBuf.readString();
        epoch = byteBuf.readLong();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeInt(cx);
        byteBuf.writeInt(cz);
        byteBuf.writeLong(handoverId);
        byteBuf.writeBoolean(success);
        byteBuf.writeString(owner);
        byteBuf.writeLong(epoch);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
