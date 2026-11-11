package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SubscribeChunkMessage;
import com.multiforge.master.ChunkGenerationManager;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

import java.io.IOException;

public class SubscribeChunkHandler {
    public static void handle(ServerConnection connection, SubscribeChunkMessage message) {
        ChunkSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
        // A subscriber either loaded the chunk from storage or just finished
        // generating it; in both cases its in-memory copy is now the freshest one
        // and readers that waited for generation can read it from this peer.
        ChunkGenerationManager.complete(message.world, message.cx, message.cz, connection);
    }
}
