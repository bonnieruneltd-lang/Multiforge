package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.WriteJsonMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class WriteJsonHandler {
    public static final HashMap<String, byte[]> writesInProgress = new HashMap<>();
    private static final Object writingLock = new Object();

    public static void handle(ServerConnection connection, WriteJsonMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                synchronized (writingLock) {
                    synchronized (writesInProgress) {
                        writesInProgress.put(message.file, message.data);
                    }

                    Files.write(MasterStorage.file(message.file).toPath(), message.data);

                    synchronized (writesInProgress) {
                        writesInProgress.remove(message.file);
                    }
                }

                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
