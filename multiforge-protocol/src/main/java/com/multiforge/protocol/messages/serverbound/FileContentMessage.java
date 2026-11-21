package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Announces a shared-file download on a data stream.
 *
 * <p>A negative {@code streamId} means the master could not provide the file (missing or
 * outside the shared root); no data follows and the requester must not create a file.</p>
 */
public class FileContentMessage extends ServerBoundMessage {

    public final String path;
    public final long lastModified;
    public final int streamId;

    public FileContentMessage(String path, long lastModified, int streamId) {
        this.path = path;
        this.lastModified = lastModified;
        this.streamId = streamId;
    }

    public FileContentMessage(ExtendedByteBuf byteBuf) {
        path = byteBuf.readString();
        lastModified = byteBuf.readLong();
        streamId = byteBuf.readVarInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(path);
        byteBuf.writeLong(lastModified);
        byteBuf.writeVarInt(streamId);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
