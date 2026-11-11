package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.PingMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.ServerConnection;

import java.util.concurrent.CompletableFuture;

public class PingHandler {
    public static void handle(ServerConnection connection, PingMessage message) {
        CompletableFuture.runAsync(() -> {
            connection.sendReply(new BooleanMessageReply(true), message);
        });
    }
}
