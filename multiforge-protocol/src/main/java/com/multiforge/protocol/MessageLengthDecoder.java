package com.multiforge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;

import java.util.List;

/**
 * Splits the byte stream into frames using the MultiPaper length prefix.
 *
 * <p>A partial frame is left in the cumulation buffer until the rest arrives; the
 * decoder never consumes bytes it cannot deliver. Frames larger than
 * {@link #MAX_FRAME_BYTES} are rejected instead of being buffered, so a peer (or a
 * corrupted stream) cannot make the process allocate an unbounded buffer. The limit
 * is deliberately larger than any chunk NBT or data-stream frame; whole-file
 * uploads are the only payloads near it, and those should use a data stream.</p>
 */
public class MessageLengthDecoder extends ByteToMessageDecoder {

    /** Maximum accepted frame size; override with {@code -Dmultiforge.protocol.maxFrameBytes}. */
    public static final int MAX_FRAME_BYTES =
            Integer.getInteger("multiforge.protocol.maxFrameBytes", 64 * 1024 * 1024);

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        int length = 0;
        int j = 0;

        byte b0;

        byteBuf.markReaderIndex();

        do {
            if (byteBuf.readableBytes() < 1) {
                byteBuf.resetReaderIndex();
                return;
            }

            b0 = byteBuf.readByte();
            length |= (b0 & 127) << j++ * 7;
            if (j > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b0 & 128) == 128);

        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new TooLongFrameException("Frame length " + length + " exceeds " + MAX_FRAME_BYTES + " bytes");
        }

        if (byteBuf.readableBytes() < length) {
            byteBuf.resetReaderIndex();
            return;
        }

        list.add(byteBuf.readBytes(length));
    }
}

