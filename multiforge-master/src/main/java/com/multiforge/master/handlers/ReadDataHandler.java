package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.ReadDataMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ReadDataHandler {
    public static void handle(ServerConnection connection, ReadDataMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                byte[] b = FileLocker.readBytes(new File(message.path));
                connection.sendReply(new DataMessageReply(b), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
