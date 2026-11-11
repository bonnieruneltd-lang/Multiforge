package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.UnsubscribeChunkMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class UnsubscribeChunkHandler {
    public static void handle(ServerConnection connection, UnsubscribeChunkMessage message) {
        ChunkSubscriptionManager.unsubscribe(connection, message.world, message.cx, message.cz);

        connection.sendReply(new BooleanMessageReply(true), message);
    }
}
