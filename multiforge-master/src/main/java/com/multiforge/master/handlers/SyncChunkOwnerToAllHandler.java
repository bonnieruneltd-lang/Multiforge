package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SyncChunkOwnerToAllMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class SyncChunkOwnerToAllHandler {
    public static void handle(ServerConnection connection, SyncChunkOwnerToAllMessage message) {
        ServerConnection owner = ChunkSubscriptionManager.getOwner(message.world, message.cx, message.cz);

        ServerConnection.broadcastAll(new SetChunkOwnerMessage(message.world, message.cx, message.cz, owner == null ? "" : owner.getBungeeCordName()));
    }
}
