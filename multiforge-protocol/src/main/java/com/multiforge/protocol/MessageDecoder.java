package com.multiforge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import com.multiforge.protocol.messages.Message;
import com.multiforge.protocol.messages.Protocol;

import java.util.List;

public class MessageDecoder<T extends Message<?>> extends ByteToMessageDecoder {

    private final Protocol<T> protocol;

    public MessageDecoder(Protocol<T> protocol) {
        this.protocol = protocol;
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        ExtendedByteBuf extendedByteBuf = new ExtendedByteBuf(byteBuf);
        int transactionId = extendedByteBuf.readVarInt();
        int messageId = extendedByteBuf.readVarInt();
        Message<?> message = protocol.getDeserializer(messageId).apply(extendedByteBuf);
        message.setTransactionId(transactionId);
        list.add(message);
    }
}
