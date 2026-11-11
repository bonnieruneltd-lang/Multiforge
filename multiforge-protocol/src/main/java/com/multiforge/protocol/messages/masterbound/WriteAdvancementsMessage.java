package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class WriteAdvancementsMessage extends MasterBoundMessage {

    public final String world;
    public final String uuid;
    public final byte[] data;

    public WriteAdvancementsMessage(String world, String uuid, byte[] data) {
        this.world = world;
        this.uuid = uuid;
        this.data = data;
    }

    public WriteAdvancementsMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        uuid = byteBuf.readString();
        data = new byte[byteBuf.readVarInt()];
        byteBuf.readBytes(data);
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeString(uuid);
        byteBuf.writeVarInt(data.length);
        byteBuf.writeBytes(data);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
