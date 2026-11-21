package com.multiforge.protocol;

import com.multiforge.protocol.messages.Protocol;
import com.multiforge.protocol.messages.masterbound.MasterBoundProtocol;
import com.multiforge.protocol.messages.masterbound.RequestChunkOwnershipMessage;
import com.multiforge.protocol.messages.masterbound.WriteChunkMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Fuzz and malformed-payload tests for the master protocol.
 *
 * <p>Every message decoder runs on bytes that arrive from a remote TCP peer. A
 * decoder bug is therefore a remote crash (an unbounded allocation, an infinite
 * loop) or a desynchronized connection, not just a failed test. The existing
 * codec tests cover well-formed round trips, truncated frames and oversized
 * frames; this class adds the two remaining attack surfaces:</p>
 *
 * <ul>
 *   <li><b>Announced lengths:</b> a byte array or string array whose length field
 *       claims more data than the frame contains must be rejected <em>before</em>
 *       the allocation, not by an {@link OutOfMemoryError}.</li>
 *   <li><b>Arbitrary payloads:</b> for every registered message id, random bodies
 *       must either decode to a message or raise a normal {@link Exception}. They
 *       must never hang, recurse without bound or leak a buffer.</li>
 * </ul>
 *
 * <p>The random generator uses a fixed seed so a failure is reproducible, and the
 * run is bounded to a few thousand frames so it fits in the normal unit-test
 * suite. This is a regression net, not a long-running fuzz campaign.</p>
 */
class ProtocolFuzzTest {

    /** Random bodies decoded per registered message id. */
    private static final int RANDOM_FRAMES_PER_MESSAGE = 24;

    /** Largest random body; keeps most announced lengths inside the frame. */
    private static final int MAX_PAYLOAD_BYTES = 192;

    /**
     * Encodes and decodes one frame.
     *
     * @param protocol protocol under test
     * @param messageId message id to put in the frame
     * @param payload   writer for everything after the message id
     * @return the decoder failure, or {@code null} when the frame decoded
     */
    private static Throwable decode(Protocol<?> protocol, int messageId, Consumer<ExtendedByteBuf> payload) {
        ByteBuf body = Unpooled.buffer();
        ExtendedByteBuf buf = new ExtendedByteBuf(body);
        buf.writeVarInt(0);
        buf.writeVarInt(messageId);
        payload.accept(buf);
        EmbeddedChannel framer = new EmbeddedChannel(new MessageLengthEncoder());
        framer.writeOutbound(body);
        ByteBuf frame = framer.readOutbound();
        EmbeddedChannel channel = new EmbeddedChannel(
                new MessageLengthDecoder(), new MessageDecoder<>(protocol));
        try {
            channel.writeInbound(frame);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            // The decoder keeps a partial frame in its cumulation buffer when the
            // body ends early; finishing the channel tries to decode it again and
            // logs/throws. The outcome was already recorded above.
            try {
                channel.finishAndReleaseAll();
            } catch (RuntimeException ignored) {
                // expected for a deliberately truncated frame
            }
            framer.finishAndReleaseAll();
        }
    }

    /**
     * @param protocol protocol to inspect
     * @return number of registered message ids
     */
    private static int messageCount(Protocol<?> protocol) {
        int count = 0;
        while (true) {
            try {
                protocol.getDeserializer(count);
                count++;
            } catch (IndexOutOfBoundsException e) {
                return count;
            }
        }
    }

    /**
     * Asserts that a failure or one of its causes has the expected type.
     *
     * <p>Netty wraps non-{@code DecoderException} throwables in a
     * {@code DecoderException}, so the assertion walks the cause chain.</p>
     *
     * @param failure  observed failure, may be {@code null}
     * @param expected expected type somewhere in the chain
     */
    private static void assertFailsWith(Throwable failure, Class<? extends Throwable> expected) {
        if (failure == null) {
            throw new AssertionError("malformed frame was accepted");
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (expected.isInstance(cause)) {
                return;
            }
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " but got "
                + failure.getClass().getName(), failure);
    }

    @Test
    void randomPayloadsNeverHangOrRaiseErrors() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            Random random = new Random(0x5EED_1234L);
            int decoded = 0;
            int rejected = 0;
            List<Protocol<?>> protocols = List.of(new MasterBoundProtocol(), new ServerBoundProtocol());
            for (Protocol<?> protocol : protocols) {
                int messages = messageCount(protocol);
                for (int messageId = 0; messageId < messages; messageId++) {
                    for (int iteration = 0; iteration < RANDOM_FRAMES_PER_MESSAGE; iteration++) {
                        byte[] bytes = new byte[random.nextInt(MAX_PAYLOAD_BYTES + 1)];
                        random.nextBytes(bytes);
                        Throwable failure = decode(protocol, messageId,
                                buf -> buf.writeBytes(bytes));
                        if (failure == null) {
                            decoded++;
                        } else {
                            rejected++;
                            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                                if (cause instanceof Error error && !(cause instanceof AssertionError)) {
                                    throw new AssertionError("Random body for message " + messageId
                                            + " raised " + error.getClass().getName(), error);
                                }
                            }
                        }
                    }
                }
            }
            // Both outcomes are normal; the point is that the run finishes and only
            // ordinary exceptions escape. Loose bounds keep the test stable when a
            // message is added or a decoder becomes more lenient.
            if (decoded == 0 || rejected == 0) {
                throw new AssertionError("expected both decoded and rejected frames, got "
                        + decoded + " and " + rejected);
            }
        });
    }

    @Test
    void byteArrayLengthLargerThanTheFrameIsRejectedBeforeAllocation() {
        MasterBoundProtocol protocol = new MasterBoundProtocol();
        int id = protocol.getMessageId(new WriteChunkMessage("world", "region", 0, 0, new byte[0]));
        Throwable failure = decode(protocol, id, buf -> {
            buf.writeString("world");
            buf.writeString("region");
            buf.writeInt(0);
            buf.writeInt(0);
            buf.writeVarInt(Integer.MAX_VALUE);
        });
        assertFailsWith(failure, IllegalArgumentException.class);
    }

    @Test
    void negativeByteArrayLengthIsRejected() {
        MasterBoundProtocol protocol = new MasterBoundProtocol();
        int id = protocol.getMessageId(new WriteChunkMessage("world", "region", 0, 0, new byte[0]));
        Throwable failure = decode(protocol, id, buf -> {
            buf.writeString("world");
            buf.writeString("region");
            buf.writeInt(0);
            buf.writeInt(0);
            buf.writeVarInt(-1);
        });
        assertFailsWith(failure, IllegalArgumentException.class);
    }

    @Test
    void stringArrayCountLargerThanTheFrameIsRejectedBeforeAllocation() {
        ServerBoundProtocol protocol = new ServerBoundProtocol();
        int id = protocol.getMessageId(new ChunkSubscribersSyncMessage("world", 0, 0, "", new String[0]));
        Throwable failure = decode(protocol, id, buf -> {
            buf.writeString("world");
            buf.writeInt(0);
            buf.writeInt(0);
            buf.writeString("");
            buf.writeVarInt(Integer.MAX_VALUE);
        });
        assertFailsWith(failure, IllegalArgumentException.class);
    }

    @Test
    void chunkKeyArrayCountLargerThanTheFrameIsRejectedBeforeAllocation() {
        MasterBoundProtocol protocol = new MasterBoundProtocol();
        int id = protocol.getMessageId(new RequestChunkOwnershipMessage("world", new ChunkKey[0]));
        Throwable failure = decode(protocol, id, buf -> {
            buf.writeString("world");
            buf.writeVarInt(Integer.MAX_VALUE);
        });
        assertFailsWith(failure, IllegalArgumentException.class);
    }
}
