package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class ReadStatsMessage extends MasterBoundMessage {

    public final String world;
    public final String uuid;

    public ReadStatsMessage(String world, String uuid) {
        this.world = world;
        this.uuid = uuid;
    }

    public ReadStatsMessage(ExtendedByteBuf byteBuf) {
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
