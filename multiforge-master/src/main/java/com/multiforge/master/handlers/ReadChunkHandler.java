package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.*;
import com.multiforge.master.util.RegionFileCache;

import java.io.File;

public class ReadChunkHandler {
    public static void handle(ServerConnection connection, ReadChunkMessage message) {
        if (checkIfLoadedOnAnotherServer(connection, message.world, message.path, message.cx, message.cz, message)) {
            return;
        }

        Runnable callback = () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                if (b == null) {
                    b = new byte[0];
                }
                connection.sendReply(new DataMessageReply(b), message);
            });
        };

        if (message.path.equals("region")) {
            ChunkLockManager.waitForLock(message.world, message.cx, message.cz, callback);
        } else if (message.path.equals("entities")) {
            EntitiesLockManager.waitForLock(message.world, message.cx, message.cz, callback);
        } else {
            callback.run();
        }
    }

    private static boolean checkIfLoadedOnAnotherServer(ServerConnection connection, String world, String path, int cx, int cz, ReadChunkMessage message) {
        if (path.equals("region")) {
            ServerConnection alreadyLoadedChunk = ChunkSubscriptionManager.getOwnerOrSubscriber(world, cx, cz);
            ChunkSubscriptionManager.subscribe(connection, world, cx, cz);
            if (alreadyLoadedChunk != null && alreadyLoadedChunk != connection) {
                connection.sendReply(new ChunkLoadedOnAnotherServerMessage(alreadyLoadedChunk.getBungeeCordName()), message);
                return true;
            }
        }

        if (path.equals("entities")) {
            ServerConnection alreadyLoadedEntities = EntitiesSubscriptionManager.getSubscriber(world, cx, cz);
            EntitiesSubscriptionManager.subscribe(connection, world, cx, cz);
            if (alreadyLoadedEntities != null && alreadyLoadedEntities != connection) {
                connection.sendReply(new ChunkLoadedOnAnotherServerMessage(alreadyLoadedEntities.getBungeeCordName()), message);
                return true;
            }
        }

        return false;
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
