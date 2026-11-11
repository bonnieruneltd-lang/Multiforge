package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

import java.util.UUID;

public class PlayerConnectMessage extends MasterBoundMessage {

    public final UUID uuid;
    public final String playerName;

    public PlayerConnectMessage(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
    }

    public PlayerConnectMessage(ExtendedByteBuf byteBuf) {
        uuid = byteBuf.readUUID();
        playerName = byteBuf.readString();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeString(playerName);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
