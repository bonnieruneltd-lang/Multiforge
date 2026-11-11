package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.WriteDataMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.DataUpdateMessage;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class WriteDataHandler {
    public static void handle(ServerConnection connection, WriteDataMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                FileLocker.writeBytes(new File(message.path), message.data);
                connection.sendReply(new BooleanMessageReply(true), message);

                if (message.path.contains("scoreboard")) {
                    // Scoreboards are synced with other methods
                    return;
                }

                connection.broadcastOthers(new DataUpdateMessage(message.path, message.data));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
