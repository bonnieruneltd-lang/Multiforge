package com.multiforge.master.handlers;

import com.multiforge.master.util.Log;

import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.messages.masterbound.RequestChunkOwnershipMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class RequestChunkOwnershipHandler {
    public static void handle(ServerConnection connection, RequestChunkOwnershipMessage message) {
        boolean hasAtLeastOneChunkLocked = false;
        for (ChunkKey key : message.chunks) {
            if (ChunkSubscriptionManager.getOwner(key.world, key.x, key.z) == connection) {
                hasAtLeastOneChunkLocked = true;
            }
        }

        Log.info(connection.getBungeeCordName() + " is requesting " + Arrays.toString(message.chunks) + " " + hasAtLeastOneChunkLocked);

        if (hasAtLeastOneChunkLocked) {
            for (ChunkKey key : message.chunks) {
                ChunkSubscriptionManager.lock(connection, key.world, key.x, key.z, true);
            }

            CompletableFuture.runAsync(() -> {
                // Use runAsync to run this after it's sent all the other lock data
                connection.sendReply(new BooleanMessageReply(true), message);
            });
        } else {
            connection.sendReply(new BooleanMessageReply(false), message);
        }
    }
}
