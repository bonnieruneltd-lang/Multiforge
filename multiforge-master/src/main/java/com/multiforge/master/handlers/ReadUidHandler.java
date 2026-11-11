package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.ReadUidMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletableFuture;

public class ReadUidHandler {
    public static void handle(ServerConnection connection, ReadUidMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                try {
                    byte[] b = Files.readAllBytes(MasterStorage.worldFile(message.world, "uid.dat").toPath());
                    connection.sendReply(new DataMessageReply(b), message);
                } catch (NoSuchFileException e) {
                    connection.sendReply(new DataMessageReply(new byte[0]), message);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
