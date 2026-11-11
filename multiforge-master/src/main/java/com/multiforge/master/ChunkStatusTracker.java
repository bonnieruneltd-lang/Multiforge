package com.multiforge.master;

import com.multiforge.protocol.ChunkKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the load status every server reported for its chunks.
 *
 * <p>MultiPaper uses {@code ChunkChangedStatusMessage} to avoid redirecting a
 * chunk read to a server that is already unloading the chunk: its in-memory copy
 * is about to disappear, and asking it would cost a round-trip that returns an
 * empty payload and falls back to the master anyway. The master previously only
 * relayed the notification to subscribers; this tracker records it so
 * {@code ReadChunkHandler} can choose a peer that still has the chunk loaded.</p>
 *
 * <p>Keys are the cluster chunk key plus the server name. Entries are removed when
 * the server disconnects and when a chunk's last subscription disappears, so the
 * map cannot grow without bound in a long-running cluster.</p>
 *
 * <p>Threading: all methods are safe to call from Netty handler threads and from
 * tests. The map is concurrent and values are immutable strings.</p>
 */
public final class ChunkStatusTracker {

    /** Status reported when a chunk finished loading and is fully usable. */
    public static final String STATUS_LOADED = "loaded";
    /** Status reported when a chunk is being removed from the server. */
    public static final String STATUS_UNLOADED = "unloaded";

    /** Chunk -> server name -> last status. */
    private static final Map<ChunkKey, Map<String, String>> STATUSES = new ConcurrentHashMap<>();

    private ChunkStatusTracker() {
    }

    /**
     * Records the status one server reported for a chunk.
     *
     * @param world  logical world name
     * @param cx     chunk X
     * @param cz     chunk Z
     * @param server reporting server
     * @param status reported status, for example {@code loaded} or {@code unloaded}
     */
    public static void update(String world, int cx, int cz, String server, String status) {
        if (server == null || server.isEmpty() || status == null) {
            return;
        }
        ChunkKey key = new ChunkKey(world, cx, cz);
        if (STATUS_UNLOADED.equals(status)) {
            // Remember the unload, but drop the entry as soon as the chunk is
            // loaded again so a later read may use the peer.
            STATUSES.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(server, STATUS_UNLOADED);
        } else if (STATUS_LOADED.equals(status)) {
            Map<String, String> servers = STATUSES.get(key);
            if (servers != null) {
                servers.remove(server);
                if (servers.isEmpty()) {
                    STATUSES.remove(key, servers);
                }
            }
        } else {
            STATUSES.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(server, status);
        }
    }

    /**
     * @param world  logical world name
     * @param cx     chunk X
     * @param cz     chunk Z
     * @param server server that would serve the chunk
     * @return {@code true} when the server reported the chunk as unloading or any
     *         state other than {@code loaded}
     */
    public static boolean isUnloading(String world, int cx, int cz, String server) {
        Map<String, String> servers = STATUSES.get(new ChunkKey(world, cx, cz));
        if (servers == null) {
            return false;
        }
        String status = servers.get(server);
        return status != null && !STATUS_LOADED.equals(status);
    }

    /**
     * Forgets every status reported by a server that disconnected.
     *
     * @param server server name
     */
    public static void forget(String server) {
        if (server == null || server.isEmpty()) {
            return;
        }
        for (Map.Entry<ChunkKey, Map<String, String>> entry : STATUSES.entrySet()) {
            Map<String, String> servers = entry.getValue();
            servers.remove(server);
            if (servers.isEmpty()) {
                STATUSES.remove(entry.getKey(), servers);
            }
        }
    }

    /**
     * Forgets all statuses of a chunk once nobody subscribes to it anymore.
     *
     * @param world logical world name
     * @param cx    chunk X
     * @param cz    chunk Z
     */
    public static void forgetChunk(String world, int cx, int cz) {
        STATUSES.remove(new ChunkKey(world, cx, cz));
    }

    /** @return number of chunks with reported statuses, for diagnostics and tests */
    public static int trackedChunks() {
        return STATUSES.size();
    }
}
