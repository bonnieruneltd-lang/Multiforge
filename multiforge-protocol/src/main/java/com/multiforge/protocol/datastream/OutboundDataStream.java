package com.multiforge.protocol.datastream;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class OutboundDataStream {

    private final DataStreamManager<?> manager;
    private final Channel channel;
    private final int streamId;

    OutboundDataStream(DataStreamManager<?> manager, Channel channel, int streamId) {
        this.manager = manager;
        this.channel = channel;
        this.streamId = streamId;
    }

    public int getStreamId() {
        return streamId;
    }

    public ChannelFuture send(byte[] bytes, int offset, int length) {
        if (length > 0) {
            // Copy the slice: callers stream from a reusable buffer and Netty may
            // encode the message on another thread after the next read overwrote it.
            byte[] slice = new byte[length];
            System.arraycopy(bytes, offset, slice, 0, length);
            return channel.writeAndFlush(manager.createDataStreamMessage(streamId, slice, 0, length));
        } else {
            return new DefaultChannelPromise(channel).setSuccess();
        }
    }

    public ChannelFuture send(byte[] bytes) {
        return send(bytes, 0, bytes.length);
    }

    public ChannelFuture endStream() {
        return channel.writeAndFlush(manager.createDataStreamMessage(streamId, new byte[0], 0, 0));
    }

    public OutputStream createOutputSteam() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b});
            }

            @Override
            public void write(byte[] b, int o, int l) throws IOException {
                try {
                    send(b, o, l).await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                endStream();
            }
        };
    }

    /**
     * Streams an input stream without progress reporting.
     *
     * @param in source, closed when the stream finishes or fails
     * @return future completed when the close marker was written
     */
    public ChannelFuture copyFromAsync(InputStream in) {
        return copyFromAsync(in, null);
    }

    /**
     * Streams an input stream and reports progress after every chunk.
     *
     * <p>The callback runs on the channel's event loop after each 64 KiB chunk was
     * accepted by the channel, with the cumulative byte count. It is advisory:
     * failures are not propagated into the copy and the close marker stays the
     * completion signal.</p>
     *
     * @param in       source, closed when the stream finishes or fails
     * @param progress cumulative byte consumer, may be {@code null}
     * @return future completed when the close marker was written
     */
    public ChannelFuture copyFromAsync(InputStream in, java.util.function.LongConsumer progress) {
        ChannelPromise promise = new DefaultChannelPromise(channel);
        byte[] buffer = new byte[64 * 1024];
        doCopyFrom(in, promise, buffer, progress, 0L);
        return promise;
    }

    private void doCopyFrom(InputStream in, ChannelPromise promise, byte[] buffer,
                            java.util.function.LongConsumer progress, long transferred) {
        try {
            int length = in.read(buffer);
            if (length <= 0) {
                in.close();
                endStream().addListener(future -> promise.setSuccess());
                return;
            }

            long next = transferred + length;
            send(buffer, 0, length).addListener(future -> {
                if (progress != null) {
                    try {
                        progress.accept(next);
                    } catch (RuntimeException ignored) {
                        // A progress callback must never abort a transfer.
                    }
                }
                doCopyFrom(in, promise, buffer, progress, next);
            });
        } catch (IOException e) {
            promise.setFailure(e);
        }
    }

}
