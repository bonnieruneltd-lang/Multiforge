package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class ServerInfoUpdateMessage extends ServerBoundMessage {

    public final String name;
    public final int averageTickTime;
    public final float tps;
    public final int playerCount;

    public ServerInfoUpdateMessage(String name, int averageTickTime, float tps, int playerCount) {
        this.name = name;
        this.averageTickTime = averageTickTime;
        this.tps = tps;
        this.playerCount = playerCount;
    }

    public ServerInfoUpdateMessage(ExtendedByteBuf byteBuf) {
        name = byteBuf.readString();
        averageTickTime = byteBuf.readInt();
        tps = byteBuf.readFloat();
        playerCount = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(name);
        byteBuf.writeInt(averageTickTime);
        byteBuf.writeFloat(tps);
        byteBuf.writeInt(playerCount);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
