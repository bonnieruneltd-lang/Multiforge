package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.ForceReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.ChunkGenerationManager;
import com.multiforge.master.ChunkLockManager;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.RegionFileCache;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Like {@link ReadChunkHandler}, but serves the master's stored copy without the
 * initial peer redirect.
 *
 * <p>The only exception is a chunk that another server is currently generating:
 * that server is a subscriber without a loaded copy, so the forced read waits for
 * the generation lease and then re-enters the normal read path (which may redirect
 * to the now ready peer).</p>
 */
public class ForceReadChunkHandler {

    /** Reused payload for "the cluster has no copy, vanilla must generate". */
    private static final byte[] EMPTY = new byte[0];

    /**
     * Serves a forced read, waiting for a generation lease like the normal read.
     *
     * <p>The mod reaches this handler after a peer answered with an empty payload
     * (it has the chunk subscribed but not loaded yet). If that peer is currently
     * generating the chunk, the master must not answer "empty": that would make
     * the requester generate the same chunk in parallel. Waiting for the lease is
     * the correct answer and the deferred reply is bounded like in
     * {@code ReadChunkHandler}.</p>
     *
     * @param connection server that asked
     * @param message    read request
     */
    public static void handle(ServerConnection connection, ForceReadChunkMessage message) {
        if (message.path.equals("region")) {
            Optional<CompletableFuture<ChunkGenerationManager.Decision>> pending =
                    ChunkGenerationManager.waitForCurrentGeneration(
                            connection, message.world, message.cx, message.cz);
            if (pending.isPresent()) {
                ChunkSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
                pending.get().thenAccept(decision -> {
                    if (decision == ChunkGenerationManager.Decision.RETRY_READ) {
                        // Re-run through the normal read path: the generator may
                        // serve the chunk from memory (peer redirect) instead of a
                        // still empty stored copy. The transaction id is preserved
                        // so the waiting requester accepts the reply.
                        ReadChunkMessage read = new ReadChunkMessage(
                                message.world, message.path, message.cx, message.cz);
                        read.setTransactionId(message.getTransactionId());
                        ReadChunkHandler.handle(connection, read);
                    } else {
                        connection.sendReply(new DataMessageReply(EMPTY), message);
                    }
                });
                return;
            }
        }

        ChunkLockManager.waitForLock(message.world, message.cx, message.cz, () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                byte[] data = b == null ? EMPTY : b;
                if (data.length > 0 && message.path.equals("region")) {
                    ChunkGenerationManager.complete(message.world, message.cx, message.cz, null);
                }
                connection.sendReply(new DataMessageReply(data), message);
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
