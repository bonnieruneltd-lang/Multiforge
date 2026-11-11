package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class SyncChunkSubscribersHandler {
    public static void handle(ServerConnection connection, SyncChunkSubscribersMessage message) {
        ChunkSubscriptionManager.syncSubscribers(connection, message.world, message.cx, message.cz);
    }
}
