package com.multiforge.forge.peer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Length framing, optional zlib compression and packet id handling for peer connections.
 *
 * <p>Frame layout (identical in spirit to Minecraft's compressed packet format):</p>
 * <pre>
 *   varint frameLength          number of bytes that follow
 *   varint uncompressedLength   0 when the payload is stored verbatim
 *   varint packetId
 *   payload
 * </pre>
 *
 * <p>The decoder is stateless with respect to compression: a non-zero
 * {@code uncompressedLength} tells it to inflate, so mismatched timings during
 * the handshake cannot corrupt the stream. The encoder only compresses once the
 * connection negotiated zlib via {@code SetCompressionPacket}.</p>
 *
 * <p>Both codecs are stateful per connection (the encoder reads the negotiated
 * compression setting) and must be added to exactly one pipeline. They run on
 * the connection's event loop, so no additional synchronization is required.</p>
 */
public final class PeerFrameCodec {

    private PeerFrameCodec() {
    }

    /** Encoder half of the peer framing protocol. */
    public static final class Encoder extends MessageToByteEncoder<PeerPacket> {

        private final PeerConnection connection;

        /**
         * @param connection connection whose negotiated compression state is used
         */
        public Encoder(PeerConnection connection) {
            this.connection = connection;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, PeerPacket msg, ByteBuf out) {
            FriendlyByteBuf payload = new FriendlyByteBuf(ctx.alloc().buffer());
            try {
                writeVarInt(payload, PeerProtocol.idOf(msg));
                msg.write(payload);

                int rawLength = payload.readableBytes();
                boolean compress = connection.isCompressionEnabled() && rawLength >= connection.compressionThreshold();
                if (compress) {
                    byte[] compressed = deflate(payload, rawLength);
                    int headerLength = varIntSize(rawLength);
                    writeVarInt(out, headerLength + compressed.length);
                    writeVarInt(out, rawLength);
                    out.writeBytes(compressed);
                } else {
                    writeVarInt(out, 1 + rawLength);
                    writeVarInt(out, 0);
                    out.writeBytes(payload, payload.readerIndex(), rawLength);
                }
            } finally {
                payload.release();
            }
        }
    }

    /** Decoder half of the peer framing protocol. */
    public static final class Decoder extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            in.markReaderIndex();
            int frameLength;
            try {
                frameLength = readVarInt(in);
            } catch (RuntimeException e) {
                in.resetReaderIndex();
                return;
            }
            if (frameLength < 1) {
                throw new IllegalStateException("Peer frame too small: " + frameLength);
            }
            if (in.readableBytes() < frameLength) {
                in.resetReaderIndex();
                return;
            }
            int frameEnd = in.readerIndex() + frameLength;
            int uncompressedLength = readVarInt(in);
            ByteBuf payload;
            if (uncompressedLength > 0) {
                int compressedLength = frameEnd - in.readerIndex();
                payload = ctx.alloc().buffer(uncompressedLength);
                try {
                    inflate(in, compressedLength, payload, uncompressedLength);
                } catch (DataFormatException e) {
                    payload.release();
                    throw new IllegalStateException("Failed to inflate peer frame", e);
                }
            } else {
                payload = in.readRetainedSlice(frameEnd - in.readerIndex());
            }

            try {
                FriendlyByteBuf friendly = new FriendlyByteBuf(payload);
                int packetId = readVarInt(friendly);
                out.add(PeerProtocol.create(packetId, friendly));
            } catch (RuntimeException e) {
                payload.release();
                throw e;
            }
        }

        private static void inflate(ByteBuf source, int compressedLength, ByteBuf target, int uncompressedLength)
                throws DataFormatException {
            Inflater inflater = new Inflater();
            try {
                byte[] input = new byte[compressedLength];
                source.readBytes(input);
                inflater.setInput(input);
                byte[] output = new byte[uncompressedLength];
                int written = 0;
                while (written < uncompressedLength && !inflater.finished()) {
                    int count = inflater.inflate(output, written, uncompressedLength - written);
                    if (count == 0 && inflater.needsInput()) {
                        break;
                    }
                    written += count;
                }
                if (written != uncompressedLength) {
                    throw new DataFormatException("Expected " + uncompressedLength + " bytes, inflated " + written);
                }
                target.writeBytes(output);
            } finally {
                inflater.end();
            }
        }
    }

    private static byte[] deflate(FriendlyByteBuf payload, int rawLength) {
        byte[] input = new byte[rawLength];
        payload.getBytes(payload.readerIndex(), input);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            // Deflate can expand incompressible input by a few bytes per block.
            byte[] buffer = new byte[rawLength + rawLength / 16 + 128];
            int length = 0;
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer, length, buffer.length - length);
                if (written == 0) {
                    break;
                }
                length += written;
            }
            byte[] result = new byte[length];
            System.arraycopy(buffer, 0, result, 0, length);
            return result;
        } finally {
            deflater.end();
        }
    }

    /**
     * Writes a Minecraft style variable length integer.
     *
     * @param buf   destination
     * @param value value to write
     */
    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Reads a Minecraft style variable length integer.
     *
     * @param buf source
     * @return decoded value
     * @throws IllegalArgumentException when the value is longer than 5 bytes
     */
    static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        while (true) {
            byte current = buf.readByte();
            value |= (current & 127) << position++ * 7;
            if (position > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
            if ((current & 128) != 128) {
                return value;
            }
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & -128) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
