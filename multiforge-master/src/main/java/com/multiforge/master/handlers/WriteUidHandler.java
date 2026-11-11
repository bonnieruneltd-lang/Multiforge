package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.WriteUidMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

public class WriteUidHandler {
    public static void handle(ServerConnection connection, WriteUidMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                File worldDir = MasterStorage.worldDir(message.world);
                if (!worldDir.exists()) worldDir.mkdirs();
                Files.write(new File(worldDir, "uid.dat").toPath(), message.data);
                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
