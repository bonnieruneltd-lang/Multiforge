package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.ChunkChangedStatusMessage;
import com.multiforge.protocol.messages.serverbound.ServerChangedChunkStatusMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class ChunkChangedStatusHandler {
    public static void handle(ServerConnection connection, ChunkChangedStatusMessage message) {
        for (ServerConnection subscriber : ChunkSubscriptionManager.getSubscribers(message.world, message.cx, message.cz)) {
            subscriber.send(new ServerChangedChunkStatusMessage(message.world, message.cx, message.cz, message.status, connection.getBungeeCordName()));
        }
    }
}
