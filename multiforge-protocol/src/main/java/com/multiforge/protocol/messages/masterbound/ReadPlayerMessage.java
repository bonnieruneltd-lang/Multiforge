package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class ReadPlayerMessage extends MasterBoundMessage {

    public final String world;
    public final String uuid;

    public ReadPlayerMessage(String world, String uuid) {
        this.world = world;
        this.uuid = uuid;
    }

    public ReadPlayerMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        uuid = byteBuf.readString();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeString(uuid);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
