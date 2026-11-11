package com.multiforge.protocol.datastream;

import io.netty.channel.Channel;
import com.multiforge.protocol.messages.Message;
import com.multiforge.protocol.messages.MessageHandler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Routes streamed payloads (files, large chunk transfers) over the message channel.
 *
 * <p>A data stream is a sequence of data messages followed by an empty message that
 * closes it. The receiver must register an {@link InboundDataStream} for the stream
 * id announced by a normal message (for example {@code FileContentMessage}). Because
 * the sender starts streaming immediately, data can arrive before the receiving
 * thread reacted to the announcement. This class buffers such frames in arrival
 * order and replays them when the stream is created, which guarantees that the
 * close marker can never overtake earlier data.</p>
 *
 * <p>Threading: frames arrive on the Netty event loop; streams are created from
 * handler threads. All state is concurrent. Buffers are bounded so a misbehaving
 * peer cannot exhaust memory.</p>
 */
public class DataStreamManager<T extends Message> {

    /** Maximum number of buffered frames per unknown stream. */
    private static final int MAX_PENDING_FRAMES = 1024;
    /** Maximum buffered bytes per unknown stream. */
    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;

    private final AtomicInteger streamIdIncrementer = new AtomicInteger(1);
    final Map<Integer, InboundDataStream> inboundDataStreams = new ConcurrentHashMap<>();
    private final Map<Integer, PendingFrames> pendingFrames = new ConcurrentHashMap<>();
    /** Guards stream registration and dispatch so no frame can be lost or reordered. */
    private final Object streamLock = new Object();
    private final MessageHandler<T> messageHandler;

    /**
     * @param messageHandler handler used to create outbound data stream messages
     */
    public DataStreamManager(MessageHandler<T> messageHandler) {
        this.messageHandler = messageHandler;
    }

    /**
     * @param channel channel to stream over
     * @return outbound stream with a fresh id
     */
    public OutboundDataStream createOutboundDataStream(Channel channel) {
        return new OutboundDataStream(this, channel, streamIdIncrementer.incrementAndGet());
    }

    /**
     * @param channel channel the stream belongs to
     * @param streamId id announced by the peer
     * @return inbound stream that already received any data that arrived early
     */
    public InboundDataStream createInboundDataStream(Channel channel, int streamId) {
        return createInboundDataStream(channel, streamId, data -> { }, () -> { });
    }

    /**
     * Creates an inbound stream and replays frames that arrived before it existed.
     *
     * @param channel channel the stream belongs to
     * @param streamId id announced by the peer
     * @param onData   consumer for each frame
     * @param onClose  callback when the peer closed the stream
     * @return inbound stream
     */
    public InboundDataStream createInboundDataStream(Channel channel, int streamId, Consumer<byte[]> onData, Runnable onClose) {
        InboundDataStream stream = new InboundDataStream(this, channel, streamId).onData(onData).onClose(onClose);
        // Registration and replay must be atomic with respect to incoming frames:
        // otherwise a frame can observe "no stream", go to the pending buffer, and be
        // lost when the buffer is removed by this method (observed as a truncated file
        // download). Both paths synchronize on `streamLock`; frame handlers run under
        // the same lock and therefore cannot reorder data or a close marker.
        synchronized (streamLock) {
            inboundDataStreams.put(streamId, stream);
            PendingFrames pending = pendingFrames.remove(streamId);
            if (pending != null) {
                for (byte[] frame : pending.frames) {
                    stream.doData(frame);
                }
            }
        }
        return stream;
    }

    /**
     * Dispatches a frame. Unknown stream ids are buffered for later replay.
     *
     * @param streamId stream the frame belongs to
     * @param data     frame payload, empty for the close marker
     */
    public void handleInboundData(int streamId, byte[] data) {
        synchronized (streamLock) {
            InboundDataStream dataStream = inboundDataStreams.get(streamId);
            if (dataStream != null) {
                dataStream.doData(data);
                return;
            }

            PendingFrames pending = pendingFrames.computeIfAbsent(streamId, ignored -> new PendingFrames());
            if (!pending.offer(data)) {
                pendingFrames.remove(streamId, pending);
                throw new IllegalStateException("Too much data buffered for unknown stream " + streamId);
            }
        }
    }

    /**
     * @param streamId stream id
     * @param data     frame payload
     * @param offset   offset inside {@code data}
     * @param length   payload length
     * @return outbound message for the frame
     */
    public T createDataStreamMessage(int streamId, byte[] data, int offset, int length) {
        return messageHandler.createDataStreamMessage(streamId, data, offset, length);
    }

    /** Frames buffered per unknown stream. */
    private static final class PendingFrames {
        private final Deque<byte[]> frames = new ArrayDeque<>();
        private long bytes;

        private synchronized boolean offer(byte[] frame) {
            if (frames.size() >= MAX_PENDING_FRAMES || bytes + frame.length > MAX_PENDING_BYTES) {
                return false;
            }
            frames.addLast(frame);
            bytes += frame.length;
            return true;
        }
    }
}
