package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class DataMessageReply extends ServerBoundMessage {

    public final byte[] data;

    public DataMessageReply(byte[] data) {
        this.data = data;
    }

    public DataMessageReply(ExtendedByteBuf byteBuf) {
        data = new byte[byteBuf.readVarInt()];
        byteBuf.readBytes(data);
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeVarInt(data.length);
        byteBuf.writeBytes(data);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        throw new UnsupportedOperationException("This message can only be handled in a reply");
    }
}
