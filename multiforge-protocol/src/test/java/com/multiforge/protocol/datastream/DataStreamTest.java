package com.multiforge.protocol.datastream;

import com.multiforge.protocol.messages.Message;
import com.multiforge.protocol.messages.MessageHandler;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the data stream implementation in isolation.
 *
 * <p>Data streams carry files and large chunk payloads. Two properties matter and are
 * covered here:</p>
 * <ul>
 *   <li>a payload larger than a single frame arrives completely and in order;</li>
 *   <li>frames that arrive before the receiver registered the stream are buffered and
 *       replayed, including the close marker.</li>
 * </ul>
 *
 * <p>The test uses an {@link EmbeddedChannel} so no sockets are involved; outbound
 * messages are replayed manually into a second stream manager, exactly like a network
 * would deliver them.</p>
 */
class DataStreamTest {

    @Test
    void payloadLargerThanOneFrameRoundTripsInOrder() throws Exception {
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 13 + 7);
        }

        EmbeddedChannel outboundChannel = new EmbeddedChannel();
        TestHandler outboundHandler = new TestHandler();
        OutboundDataStream outbound = outboundHandler.streams().createOutboundDataStream(outboundChannel);
        io.netty.channel.ChannelFuture sent = outbound.copyFromAsync(new ByteArrayInputStream(payload));

        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 1000 && !sent.isDone(); i++) {
            outboundChannel.runPendingTasks();
            Object message;
            while ((message = outboundChannel.readOutbound()) != null) {
                frames.add(((DataStreamMessage<?>) message).data.clone());
            }
            Thread.sleep(1L);
        }
        outboundChannel.runPendingTasks();
        Object message;
        while ((message = outboundChannel.readOutbound()) != null) {
            frames.add(((DataStreamMessage<?>) message).data.clone());
        }
        assertTrue(sent.isDone(), "stream should be fully sent");
        assertTrue(frames.size() >= 2, "300 KB must be split into several frames");

        // Register the inbound stream *after* delivering every frame, to prove buffering.
        TestHandler inboundHandler = new TestHandler();
        EmbeddedChannel inboundChannel = new EmbeddedChannel();
        for (byte[] frame : frames) {
            inboundHandler.streams().handleInboundData(7, frame);
        }

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        inboundHandler.streams().createInboundDataStream(inboundChannel, 7,
                data -> received.write(data, 0, data.length),
                () -> result.complete(received.toByteArray()));

        byte[] actual = result.get(5, TimeUnit.SECONDS);
        assertEquals(payload.length, actual.length, "no bytes may be lost");
        assertArrayEquals(payload, actual);
    }

    @Test
    void chunksAreNotTruncatedAtFrameBoundaries() throws Exception {
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        EmbeddedChannel outboundChannel = new EmbeddedChannel();
        TestHandler outboundHandler = new TestHandler();
        OutboundDataStream outbound = outboundHandler.streams().createOutboundDataStream(outboundChannel);
        io.netty.channel.ChannelFuture sent = outbound.copyFromAsync(new ByteArrayInputStream(payload));
        for (int i = 0; i < 100 && !sent.isDone(); i++) {
            outboundChannel.runPendingTasks();
            Thread.sleep(1L);
        }
        outboundChannel.runPendingTasks();

        List<byte[]> frames = new ArrayList<>();
        Object message;
        while ((message = outboundChannel.readOutbound()) != null) {
            byte[] frame = ((DataStreamMessage<?>) message).data.clone();
            if (frame.length > 0) {
                frames.add(frame);
            }
        }
        assertEquals(1, frames.size(), "exactly one data frame is needed for 64 KiB");
        assertArrayEquals(payload, frames.get(0));
    }

    /** Minimal handler that knows how to build data stream messages. */
    private static final class TestHandler extends MessageHandler<TestMessage> {

        private TestHandler() {
        }

        DataStreamManager<TestMessage> streams() {
            return getDataStreamManager();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <X extends Message<?>> X createDataStreamMessage(int streamId, byte[] data, int offset, int length) {
            return (X) new DataStreamMessage<>(streamId, data, offset, length);
        }

        @Override
        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, TestMessage message) {
            // Not used by the test.
        }
    }

    /** Dummy message type parameter for the handler. */
    private static final class TestMessage extends Message<MessageHandler<TestMessage>> {
        @Override
        public void write(com.multiforge.protocol.ExtendedByteBuf byteBuf) {
        }

        @Override
        public void handle(MessageHandler<TestMessage> handler) {
        }
    }
}
