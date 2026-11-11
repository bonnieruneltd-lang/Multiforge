package com.multiforge.master.handlers;

import com.multiforge.master.util.MasterStorage;

import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.master.*; 
import com.multiforge.master.ChunkGenerationManager;
import com.multiforge.master.util.Log;
import com.multiforge.master.util.RegionFileCache;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ReadChunkHandler {

    /** Reused payload for "the cluster has no copy, vanilla must generate". */
    private static final byte[] EMPTY = new byte[0];

    /**
     * Serves a chunk read from the cluster.
     *
     * <p>Order of answers, cheapest first: a peer that already has the chunk in
     * memory, then the master's stored copy, then "generate locally". The last
     * case is arbitrated by {@link ChunkGenerationManager} so exactly one server
     * generates a never-seen chunk; a second reader is answered later, from the
     * normal peer path, when the generator made its copy available. The deferred
     * reply is bounded by the lease and wait timeouts, so a stalled generator
     * cannot keep a chunk load pending indefinitely.</p>
     *
     * @param connection server that asked
     * @param message    read request; its transaction id is reused for every reply
     */
    public static void handle(ServerConnection connection, ReadChunkMessage message) {
        if (message.path.equals("region")) {
            // While another server generates this chunk it is only a subscriber,
            // not a holder: redirecting to it would ask a server without the chunk
            // and reading the master would answer "empty" and duplicate the work.
            Optional<CompletableFuture<ChunkGenerationManager.Decision>> pending =
                    ChunkGenerationManager.waitForCurrentGeneration(
                            connection, message.world, message.cx, message.cz);
            if (pending.isPresent()) {
                ChunkSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
                pending.get().thenAccept(decision -> {
                    if (decision == ChunkGenerationManager.Decision.RETRY_READ) {
                        handle(connection, message);
                    } else {
                        connection.sendReply(new DataMessageReply(EMPTY), message);
                    }
                });
                return;
            }
        }

        if (checkIfLoadedOnAnotherServer(connection, message.world, message.path, message.cx, message.cz, message)) {
            return;
        }

        Runnable callback = () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                byte[] data = b == null ? EMPTY : b;
                if (data.length > 0) {
                    if (message.path.equals("region")) {
                        // A stored copy exists: no lease can be valid for this chunk.
                        ChunkGenerationManager.complete(message.world, message.cx, message.cz, null);
                    }
                    connection.sendReply(new DataMessageReply(data), message);
                    return;
                }
                if (!message.path.equals("region")) {
                    // Entities and POI have no separate generation step; the
                    // region lease owns the whole chunk.
                    connection.sendReply(new DataMessageReply(data), message);
                    return;
                }
                ChunkGenerationManager.acquire(connection, message.world, message.cx, message.cz)
                        .thenAccept(decision -> {
                            if (decision == ChunkGenerationManager.Decision.RETRY_READ) {
                                // The generator finished (or went away): repeat the
                                // read so the peer redirect or stored copy is used.
                                handle(connection, message);
                            } else {
                                connection.sendReply(new DataMessageReply(EMPTY), message);
                            }
                        });
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
                String peer = alreadyLoadedChunk.getBungeeCordName();
                if (ChunkStatusTracker.isUnloading(world, cx, cz, peer)) {
                    // The peer is dropping its copy: asking it would cost a round-trip
                    // that returns an empty payload and falls back to the master.
                    Log.info("Chunk " + world + " " + cx + "," + cz + " is unloading on " + peer
                            + ", serving the master copy instead");
                } else {
                    connection.sendReply(new ChunkLoadedOnAnotherServerMessage(peer), message);
                    return true;
                }
            }
        }

        if (path.equals("entities")) {
            ServerConnection alreadyLoadedEntities = EntitiesSubscriptionManager.getSubscriber(world, cx, cz);
            EntitiesSubscriptionManager.subscribe(connection, world, cx, cz);
            if (alreadyLoadedEntities != null && alreadyLoadedEntities != connection) {
                String peer = alreadyLoadedEntities.getBungeeCordName();
                if (ChunkStatusTracker.isUnloading(world, cx, cz, peer)) {
                    Log.info("Entity data for " + world + " " + cx + "," + cz + " is unloading on " + peer
                            + ", serving the master copy instead");
                } else {
                    connection.sendReply(new ChunkLoadedOnAnotherServerMessage(peer), message);
                    return true;
                }
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
