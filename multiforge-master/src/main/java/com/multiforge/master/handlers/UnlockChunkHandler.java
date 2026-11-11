package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.UnlockChunkMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class UnlockChunkHandler {
    public static void handle(ServerConnection connection, UnlockChunkMessage message) {
        ChunkSubscriptionManager.unlock(connection, message.world, message.cx, message.cz);
    }
}
