package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Reports the progress of an in-flight file download.
 *
 * <p>Downloads are streamed ({@code FileContentMessage} plus data-stream frames), so
 * a large file previously gave no signal at all until the close marker arrived:
 * the requester could not tell a slow transfer from a stuck one, and neither the
 * console nor an operator had anything to show. The master now sends this message
 * after every MiB (and once more with {@code transferred == total}), which lets the
 * receiving server log or display real progress without changing the data path.</p>
 *
 * <p>The message is advisory only. The stream close marker remains the completion
 * signal and the byte count is not used to validate the payload, so an older or
 * newer peer that ignores progress frames still transfers files correctly. Ids are
 * append-only; this message is registered after every earlier serverbound id.</p>
 */
public final class DownloadProgressMessage extends ServerBoundMessage {

    /** Path of the file being downloaded, relative to the shared-file root. */
    public final String path;
    /** Data stream that carries the bytes. */
    public final int streamId;
    /** Bytes handed to the channel so far. */
    public final long transferred;
    /** Total file size in bytes, {@code -1} when unknown. */
    public final long total;

    /**
     * @param path        relative path of the file
     * @param streamId    data stream id
     * @param transferred bytes transferred so far
     * @param total       total size, {@code -1} when unknown
     */
    public DownloadProgressMessage(String path, int streamId, long transferred, long total) {
        this.path = path;
        this.streamId = streamId;
        this.transferred = transferred;
        this.total = total;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public DownloadProgressMessage(ExtendedByteBuf byteBuf) {
        this.path = byteBuf.readString();
        this.streamId = byteBuf.readVarInt();
        this.transferred = byteBuf.readLong();
        this.total = byteBuf.readLong();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(path);
        byteBuf.writeVarInt(streamId);
        byteBuf.writeLong(transferred);
        byteBuf.writeLong(total);
    }

    /**
     * @return completion in percent, or {@code -1} when the total is unknown
     */
    public int percent() {
        if (total <= 0) {
            return -1;
        }
        return (int) Math.min(100L, transferred * 100L / total);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
