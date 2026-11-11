package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class ReadLevelMessage extends MasterBoundMessage {

    public final String world;

    public ReadLevelMessage(String world) {
        this.world = world;
    }

    public ReadLevelMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
