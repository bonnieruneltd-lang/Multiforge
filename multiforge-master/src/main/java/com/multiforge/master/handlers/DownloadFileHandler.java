package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.datastream.OutboundDataStream;
import com.multiforge.protocol.messages.masterbound.DownloadFileMessage;
import com.multiforge.protocol.messages.serverbound.DownloadProgressMessage;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.master.FileLocker;
import com.multiforge.master.ServerConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class DownloadFileHandler {

    /** Minimum number of bytes between progress messages; the final one is always sent. */
    private static final long PROGRESS_INTERVAL_BYTES = 1024L * 1024L;

    public static void handle(ServerConnection connection, DownloadFileMessage message) {
        OutboundDataStream dataStream = connection.getDataStreamManager().createOutboundDataStream(connection.getChannel());
        File file = MasterStorage.sharedFile(message.path);
        FileLocker.createLockAsync(file).thenAcceptAsync(lock -> {
            try {
                long total = file.length();
                java.util.concurrent.atomic.AtomicLong reported =
                        new java.util.concurrent.atomic.AtomicLong();
                connection.sendReply(new FileContentMessage(message.path, file.lastModified(), dataStream.getStreamId()), message);
                dataStream.copyFromAsync(new FileInputStream(file), transferred -> {
                    long last = reported.get();
                    if (transferred >= total || transferred - last >= PROGRESS_INTERVAL_BYTES) {
                        reported.set(transferred);
                        connection.send(new DownloadProgressMessage(
                                message.path, dataStream.getStreamId(), transferred, total));
                    }
                }).addListener(future -> {
                    if (future.isSuccess() && total > 0 && reported.get() < total) {
                        // Always finish with an exact "100%" message, even when the
                        // file size is not a multiple of the chunk size.
                        connection.send(new DownloadProgressMessage(
                                message.path, dataStream.getStreamId(), total, total));
                    }
                    lock.complete(null);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
