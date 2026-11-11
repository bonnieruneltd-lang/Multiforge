package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.LockChunkMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class LockChunkHandler {
    public static void handle(ServerConnection connection, LockChunkMessage message) {
        ServerConnection owner = ChunkSubscriptionManager.lock(connection, message.world, message.cx, message.cz);

        connection.send(new SetChunkOwnerMessage(message.world, message.cx, message.cz, owner.getBungeeCordName()));
    }
}
