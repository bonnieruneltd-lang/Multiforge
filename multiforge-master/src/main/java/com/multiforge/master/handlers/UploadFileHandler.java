package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.datastream.OutboundDataStream;
import com.multiforge.protocol.messages.masterbound.UploadFileMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class UploadFileHandler {
    public static void handle(ServerConnection connection, UploadFileMessage message) {
        File file = MasterStorage.sharedFile(message.path);
        FileLocker.createLockAsync(file).thenAcceptAsync(lock -> {
            try {
                FileLocker.writeBytes(file, message.data);
                file.setLastModified(message.lastModified);

                connection.sendReply(new BooleanMessageReply(true), message);

                CompletableFuture<Void> completableFuture = CompletableFuture.completedFuture(null);

                if (message.immediatelySyncToOtherServers) {
                    for (ServerConnection otherConnection : ServerConnection.getConnections()) {
                        if (otherConnection != connection) {
                            CompletableFuture<Void> otherFuture = new CompletableFuture<>();
                            OutboundDataStream dataStream = otherConnection.getDataStreamManager().createOutboundDataStream(otherConnection.getChannel());
                            otherConnection.send(new FileContentMessage(message.path, message.lastModified, dataStream.getStreamId()));
                            dataStream.copyFromAsync(new ByteArrayInputStream(message.data)).addListener(future -> {
                                if (future.cause() != null) {
                                    future.cause().printStackTrace();
                                }
                                otherFuture.complete(null);
                            });
                            completableFuture = CompletableFuture.allOf(completableFuture, otherFuture);
                        }
                    }
                }

                completableFuture.thenRun(() -> {
                    lock.complete(null);
                });
            } catch (IOException e) {
                e.printStackTrace();
                lock.complete(null);
            }
        });
    }
}
