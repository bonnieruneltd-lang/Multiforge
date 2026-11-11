package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class BooleanMessageReply extends ServerBoundMessage {

    public final boolean result;

    public BooleanMessageReply(boolean result) {
        this.result = result;
    }

    public BooleanMessageReply(ExtendedByteBuf byteBuf) {
        result = byteBuf.readBoolean();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeBoolean(result);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        throw new UnsupportedOperationException("This message can only be handled in a reply");
    }
}
