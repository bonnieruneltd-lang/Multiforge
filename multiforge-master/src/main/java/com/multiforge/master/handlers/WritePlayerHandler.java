package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.WritePlayerMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class WritePlayerHandler {
    public static void handle(ServerConnection connection, WritePlayerMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                FileLocker.writeBytes(MasterStorage.worldFile(message.world, "playerdata", message.uuid + ".dat"), message.data);
                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
