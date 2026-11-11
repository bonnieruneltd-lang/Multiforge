package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SubscribeChunkMessage;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

import java.io.IOException;

public class SubscribeChunkHandler {
    public static void handle(ServerConnection connection, SubscribeChunkMessage message) {
        ChunkSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
    }
}
