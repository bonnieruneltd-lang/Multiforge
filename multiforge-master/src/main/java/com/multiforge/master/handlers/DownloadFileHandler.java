package com.multiforge.master.handlers;

import com.multiforge.protocol.datastream.OutboundDataStream;
import com.multiforge.protocol.messages.masterbound.DownloadFileMessage;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class DownloadFileHandler {
    public static void handle(ServerConnection connection, DownloadFileMessage message) {
        OutboundDataStream dataStream = connection.getDataStreamManager().createOutboundDataStream(connection.getChannel());
        File file = new File("synced-server-files", message.path);
        FileLocker.createLockAsync(file).thenAcceptAsync(lock -> {
            try {
                connection.sendReply(new FileContentMessage(message.path, file.lastModified(), dataStream.getStreamId()), message);
                dataStream.copyFromAsync(new FileInputStream(file)).addListener(future -> {
                    lock.complete(null);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
