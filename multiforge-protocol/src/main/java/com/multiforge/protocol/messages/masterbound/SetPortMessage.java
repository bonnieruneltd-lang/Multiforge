package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class SetPortMessage extends MasterBoundMessage {

    public final int port;

    public SetPortMessage(int port) {
        this.port = port;
    }

    public SetPortMessage(ExtendedByteBuf byteBuf) {
        port = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeInt(port);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
