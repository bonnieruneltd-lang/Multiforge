package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.ForceReadChunkMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.ChunkLockManager;
import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.RegionFileCache;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Like ReadChunkHandler, but forces a read and won't redirect to another server that already has it loaded.
 */
public class ForceReadChunkHandler {
    public static void handle(ServerConnection connection, ForceReadChunkMessage message) {
        ChunkLockManager.waitForLock(message.world, message.cx, message.cz, () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                if (b == null) {
                    b = new byte[0];
                }
                connection.sendReply(new DataMessageReply(b), message);
            });
        });
    }

    static File getWorldDir(String world, String path) {
        File file = new File(world);

        if (world.endsWith("_nether")) {
            file = new File(file, "DIM-1");
        }

        if (world.endsWith("_the_end")) {
            file = new File(file, "DIM1");
        }

        return new File(file, path);
    }
}
