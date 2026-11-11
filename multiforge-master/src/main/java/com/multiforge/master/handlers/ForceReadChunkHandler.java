package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

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

    /**
     * Resolves the storage directory of a chunk file.
     *
     * <p>Kept next to the chunk handlers because both the normal and the forced
     * read path need the same world-to-directory mapping.</p>
     *
     * @param world logical world name
     * @param path  storage type ({@code region}, {@code entities}, {@code poi})
     * @return directory that contains the region files
     */
    static File getWorldDir(String world, String path) {
        return MasterStorage.worldDir(world, path);
    }
}
