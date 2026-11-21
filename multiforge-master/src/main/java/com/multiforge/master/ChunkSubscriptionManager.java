package com.multiforge.master;

import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.messages.serverbound.AddChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.RemoveChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import com.multiforge.master.util.ChunkLock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkSubscriptionManager {

    private static final Queue<List<ServerConnection>> objectPool = new LinkedList<>();

    // Index 0 in the list is the current chunk owning it
    private static final Map<ChunkKey, List<ServerConnection>> chunkLocks = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, List<ServerConnection>> chunkSubscribers = new ConcurrentHashMap<>();

    /**
     * Ownership epoch per chunk.
     *
     * <p>Incremented whenever the owner name changes (including to "unowned").
     * The value is announced in {@link SetChunkOwnerMessage} and stamped into
     * peer block/block-entity packets by the mod, which lets receivers discard
     * updates produced under a previous owner. Values are never reset, not even
     * when a chunk becomes idle: a re-lock must still produce an epoch larger
     * than anything a peer has already seen for that chunk.</p>
     */
    private static final Map<ChunkKey, Long> chunkEpochs = new ConcurrentHashMap<>();
    /** Owner name the current epoch belongs to; {@code null} means "never locked". */
    private static final Map<ChunkKey, String> epochOwners = new ConcurrentHashMap<>();

    private static final Map<ServerConnection, Set<ChunkKey>> lockedChunks = new HashMap<>();
    private static final Map<ServerConnection, Set<ChunkKey>> subscribedChunks = new HashMap<>();

    public static ServerConnection getOwner(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }
        return null;
    }

    public static ServerConnection getOwnerOrSubscriber(String world, int cx, int cz) {
        ServerConnection owner = getOwner(world, cx, cz);
        if (owner != null) {
            return owner;
        }

        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }

        return null;
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz) {
        return lock(serverConnection, world, cx, cz, false);
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz, boolean force) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.computeIfAbsent(key, key2 -> {
                synchronized (objectPool) {
                    List<ServerConnection> list = objectPool.poll();
                    if (list != null) {
                        return list;
                    }
                }
                return new ArrayList<>();
            });

            if (force && serverConnections.indexOf(serverConnection) != 0) {
                serverConnections.remove(serverConnection);
            }

            if (!serverConnections.contains(serverConnection)) {
                if (force) {
                    serverConnections.add(0, serverConnection);
                } else {
                    serverConnections.add(serverConnection);
                }

                lockedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);

                if (serverConnections.size() == 1 || force) {
                    // Advance the epoch even when nobody subscribed yet: the owner's
                    // block packets must carry the epoch, and a later subscriber must
                    // see the same value the owner already uses.
                    advanceEpoch(key, serverConnections.get(0).getBungeeCordName());
                    if (chunkSubscribers.get(key) != null) {
                        updateOwner(serverConnections.get(0), chunkSubscribers.get(key), key.world, key.x, key.z);
                    }
                }
            }

            return serverConnections.get(0);
        }
    }

    public static void unlock(ServerConnection serverConnection, String world, int cx, int cz) {
        unlock(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unlock(ServerConnection serverConnection, ChunkKey key) {
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null) {
                for (int i = 0; i < serverConnections.size(); i++) {
                    if (serverConnections.get(i) == serverConnection) {
                        serverConnections.remove(i--);

                        if (i == -1) {
                            String newOwner = serverConnections.isEmpty() ? ""
                                    : serverConnections.get(0).getBungeeCordName();
                            // Record the change even without subscribers; otherwise the
                            // epoch would stay at 0 until somebody subscribed, and the
                            // next owner's packets could not be fenced.
                            advanceEpoch(key, newOwner);
                            if (chunkSubscribers.get(key) != null) {
                                if (serverConnections.isEmpty()) {
                                    updateOwner(null, chunkSubscribers.get(key), key.world, key.x, key.z);
                                } else {
                                    updateOwner(serverConnections.get(0), chunkSubscribers.get(key), key.world, key.x, key.z);
                                }
                            }
                        }
                    }
                }
                if (serverConnections.isEmpty()) {
                    chunkLocks.remove(key);
                    synchronized (objectPool) {
                        objectPool.add(serverConnections);
                    }
                }
            }

            Set<ChunkKey> chunks = lockedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }
    }

    /**
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     * @return current ownership epoch, {@code 0} when the chunk was never owned
     */
    public static long currentEpoch(String world, int cx, int cz) {
        return chunkEpochs.getOrDefault(new ChunkKey(world, cx, cz), 0L);
    }

    /**
     * Advances the epoch when the owner name changed and returns the value that
     * applies to the new announcement.
     *
     * <p>Must be called while holding the chunk lock (all callers of
     * {@link #updateOwner} already do). A repeated announcement for the same
     * owner (for example when a new subscriber is told the current owner) does
     * not advance the epoch.</p>
     *
     * @param key   chunk
     * @param owner normalised owner name, empty when unowned
     * @return epoch to stamp into the announcement
     */
    private static long advanceEpoch(ChunkKey key, String owner) {
        String previous = epochOwners.get(key);
        if (java.util.Objects.equals(previous, owner)) {
            return chunkEpochs.getOrDefault(key, 0L);
        }
        epochOwners.put(key, owner);
        long next = chunkEpochs.getOrDefault(key, 0L) + 1L;
        chunkEpochs.put(key, next);
        return next;
    }

    private static void updateOwner(ServerConnection ownerConnection, List<ServerConnection> serverConnections, String world, int cx, int cz) {
        String owner = ownerConnection == null ? "" : ownerConnection.getBungeeCordName();
        long epoch = advanceEpoch(new ChunkKey(world, cx, cz), owner);
        for (ServerConnection connection : serverConnections) {
            connection.send(new SetChunkOwnerMessage(world, cx, cz, owner, epoch));
        }
    }


    public static void subscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.computeIfAbsent(key, key2 -> {
                synchronized (objectPool) {
                    List<ServerConnection> list = objectPool.poll();
                    if (list != null) {
                        return list;
                    }
                }
                return new ArrayList<>();
            });

            if (!serverConnections.isEmpty()) {
                List<ServerConnection> singletonList = Collections.singletonList(serverConnection);
                for (ServerConnection subscriber : serverConnections) {
                    if (subscriber != serverConnection) {
                        updateSubscriberAdd(singletonList, subscriber, world, cx, cz);
                    }
                }
            }

            if (!serverConnections.contains(serverConnection)) {
                updateSubscriberAdd(serverConnections, serverConnection, world, cx, cz);
                serverConnections.add(serverConnection);
                subscribedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);
            }

            if (chunkLocks.get(key) != null && !chunkLocks.get(key).isEmpty()) {
                updateOwner(chunkLocks.get(key).get(0), Collections.singletonList(serverConnection), key.world, key.x, key.z);
            }
        }
    }

    public static void unsubscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        unsubscribe(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unsubscribe(ServerConnection serverConnection, ChunkKey key) {
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null) {
                if (serverConnections.remove(serverConnection)) {
                    updateSubscriberRemoved(serverConnections, serverConnection, key.world, key.x, key.z);
                }
                if (serverConnections.isEmpty()) {
                    chunkSubscribers.remove(key);
                    ChunkStatusTracker.forgetChunk(key.world, key.x, key.z);
                    synchronized (objectPool) {
                        objectPool.add(serverConnections);
                    }
                }
            }

            Set<ChunkKey> chunks = subscribedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }
    }

    private static void updateSubscriberAdd(List<ServerConnection> serverConnections, ServerConnection serverConnection, String world, int cx, int cz) {
        String subscriber = serverConnection.getBungeeCordName();
        for (ServerConnection connection : serverConnections) {
            if (connection != serverConnection) {
                connection.send(new AddChunkSubscriberMessage(world, cx, cz, subscriber));
            }
        }
    }

    private static void updateSubscriberRemoved(List<ServerConnection> serverConnections, ServerConnection serverConnection, String world, int cx, int cz) {
        String unsubscriber = serverConnection.getBungeeCordName();
        for (ServerConnection connection : serverConnections) {
            connection.send(new RemoveChunkSubscriberMessage(world, cx, cz, unsubscriber));
        }
    }

    public static void syncSubscribers(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            if (!chunkSubscribers.containsKey(key) || !chunkSubscribers.get(key).contains(serverConnection)) {
                subscribe(serverConnection, world, cx, cz);
            }

            String[] subscribers = chunkSubscribers.get(key).stream().filter(subscriber -> subscriber != serverConnection).map(ServerConnection::getBungeeCordName).toArray(String[]::new);

            String owner = chunkLocks.get(key) != null && !chunkLocks.get(key).isEmpty()
                    ? chunkLocks.get(key).get(0).getBungeeCordName() : "";
            serverConnection.send(new ChunkSubscribersSyncMessage(world, cx, cz, owner, subscribers,
                    chunkEpochs.getOrDefault(key, 0L)));
        }
    }

    public static void unsubscribeAndUnlockAll(ServerConnection serverConnection) {
        Set<ChunkKey> lockedChunks = ChunkSubscriptionManager.lockedChunks.remove(serverConnection);
        if (lockedChunks != null) {
            lockedChunks.forEach(chunk -> unlock(serverConnection, chunk));
        }
        Set<ChunkKey> subscribedChunks = ChunkSubscriptionManager.subscribedChunks.remove(serverConnection);
        if (subscribedChunks != null) {
            subscribedChunks.forEach(chunk -> unsubscribe(serverConnection, chunk));
        }
    }

    /**
     * Returns a snapshot of the chunk's subscribers.
     *
     * <p>The returned list must be a copy: handlers iterate it while other
     * connections subscribe or unsubscribe on their own Netty threads. Returning
     * the live {@code ArrayList} caused a {@code ConcurrentModificationException}
     * when a reconnecting cluster exchanged chunk status messages.</p>
     *
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     * @return immutable snapshot of the subscribers (possibly empty)
     */
    public static List<ServerConnection> getSubscribers(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> subscribers = chunkSubscribers.get(key);
            return subscribers == null ? List.of() : List.copyOf(subscribers);
        }
    }

    /**
     * @return number of chunks that currently have an owner, for metrics
     */
    public static int ownedChunkCount() {
        return chunkLocks.size();
    }

    /**
     * @return number of chunks that currently have at least one subscriber, for metrics
     */
    public static int subscribedChunkCount() {
        return chunkSubscribers.size();
    }
}
