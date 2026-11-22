package com.multiforge.master;

import com.multiforge.master.util.ChunkLock;
import com.multiforge.master.util.Log;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.messages.serverbound.ChunkHandoverCompleteMessage;
import com.multiforge.protocol.messages.serverbound.ChunkHandoverStartMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fenced, acknowledged ownership transfer between two game servers.
 *
 * <p>The ordinary lock queue only grants a chunk after the current owner unlocks
 * it, which happens when its last local player leaves and the chunk unloads. A
 * player who walks from server A into a chunk that server B ticks would therefore
 * keep seeing B's authoritative copy until B unloads it. This manager moves
 * ownership immediately when A asks, but in a controlled order: the master asks B
 * to drain, B saves the chunk through the normal IO path and answers ready, and
 * only then does the master force-lock the chunk for A (which advances the epoch
 * and announces it with {@code SetChunkOwnerMessage}).</p>
 *
 * <p>A refusal (B still has a real player in the chunk), a disconnect or the
 * timeout ({@value #DEFAULT_TIMEOUT_MILLIS} ms, configurable with
 * {@code -Dmultiforge.master.handoverTimeoutMillis}) leaves the chunk with its
 * previous owner, so a stalled peer can never strand a chunk unowned. The handover
 * id fences answers that arrive after a timeout.</p>
 *
 * <p>Threading: requests and answers arrive on Netty threads. Per-chunk state is
 * guarded by the same {@link ChunkLock} used by the subscription manager, and the
 * timeout runs on a daemon timer thread. No world state is touched here.</p>
 */
public final class ChunkHandoverManager {

    /** Default time the old owner has to drain its chunk before the attempt is abandoned. */
    public static final long DEFAULT_TIMEOUT_MILLIS =
            Long.getLong("multiforge.master.handoverTimeoutMillis", 5_000L);

    private static final AtomicLong IDS = new AtomicLong();

    /** Current drain budget; tests shorten it with {@link #configureTimeout(long)}. */
    private static volatile long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    /** Handovers currently waiting for the old owner's answer. */
    private static final Map<ChunkKey, Handover> ACTIVE = new ConcurrentHashMap<>();

    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong TIMED_OUT = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MultiForge-Handover");
                thread.setDaemon(true);
                return thread;
            });

    private ChunkHandoverManager() {
    }

    /**
     * Starts a handover, or grants the chunk directly when there is nobody to ask.
     *
     * <p>The requester must already be subscribed to the chunk. That is the
     * master-side proof that the chunk is loaded and can be served locally after
     * the transfer; a request from a server that does not hold the chunk is
     * rejected instead of creating an owner with no data.</p>
     *
     * @param requester server that wants ownership
     * @param world     logical world name
     * @param cx        chunk X
     * @param cz        chunk Z
     * @return {@code true} when a handover was started or ownership was granted
     *         directly; {@code false} when the request was rejected or is already
     *         in progress
     */
    public static boolean request(ServerConnection requester, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        if (!ChunkSubscriptionManager.isSubscribed(requester, key)) {
            REJECTED.incrementAndGet();
            Log.info(requester.getBungeeCordName() + " requested handover of unloaded chunk " + key);
            return false;
        }

        synchronized (ChunkLock.getChunkLock(key)) {
            ServerConnection owner = ChunkSubscriptionManager.getOwner(world, cx, cz);
            if (owner == null || owner == requester) {
                ChunkSubscriptionManager.lock(requester, world, cx, cz, true);
                requester.send(new ChunkHandoverCompleteMessage(world, cx, cz, 0L, true,
                        requester.getBungeeCordName(),
                        ChunkSubscriptionManager.currentEpoch(world, cx, cz)));
                COMPLETED.incrementAndGet();
                return true;
            }
            if (ACTIVE.containsKey(key)) {
                // The requester retries on a throttle; a second attempt while the
                // first is still draining must not create a second state machine.
                REJECTED.incrementAndGet();
                return false;
            }

            long id = IDS.incrementAndGet();
            Handover handover = new Handover(id, key, owner, requester);
            ACTIVE.put(key, handover);
            owner.send(new ChunkHandoverStartMessage(world, cx, cz, id,
                    requester.getBungeeCordName()));
            handover.timeout = TIMER.schedule(
                    () -> timeout(key, handover), timeoutMillis, TimeUnit.MILLISECONDS);
            Log.info("Handover " + id + " of " + key + " from " + owner.getBungeeCordName()
                    + " to " + requester.getBungeeCordName() + " started");
            return true;
        }
    }

    /**
     * Handles the current owner's answer.
     *
     * <p>Only the recorded owner and handover id are accepted. A stale answer (the
     * handover timed out or the chunk changed owner meanwhile) is ignored, which is
     * what makes the timeout safe.</p>
     *
     * @param sender server that answered
     * @param world  logical world name
     * @param cx     chunk X
     * @param cz     chunk Z
     * @param id     handover id echoed by the sender
     * @param ready  whether the sender flushed and may lose ownership
     */
    public static void ready(ServerConnection sender, String world, int cx, int cz, long id, boolean ready) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        Handover handover = ACTIVE.get(key);
        if (handover == null || handover.id != id || handover.oldOwner != sender) {
            return;
        }
        if (!ready) {
            finish(key, handover, false, sender.getBungeeCordName());
            REFUSED.incrementAndGet();
            Log.info("Handover " + id + " of " + key + " declined by " + sender.getBungeeCordName()
                    + " (local player inside the chunk)");
            return;
        }

        synchronized (ChunkLock.getChunkLock(key)) {
            if (ACTIVE.get(key) != handover) {
                return;
            }
            ServerConnection currentOwner = ChunkSubscriptionManager.getOwner(world, cx, cz);
            if (currentOwner != handover.oldOwner) {
                // The old owner unloaded or lost the chunk while draining; there is
                // nothing to transfer and the normal lock path already ran.
                finish(key, handover, false, currentOwner == null ? "" : currentOwner.getBungeeCordName());
                return;
            }
            ChunkSubscriptionManager.lock(handover.newOwner, world, cx, cz, true);
            long epoch = ChunkSubscriptionManager.currentEpoch(world, cx, cz);
            ACTIVE.remove(key, handover);
            handover.cancelTimeout();
            ChunkHandoverCompleteMessage complete = new ChunkHandoverCompleteMessage(
                    world, cx, cz, id, true, handover.newOwner.getBungeeCordName(), epoch);
            handover.oldOwner.send(complete);
            handover.newOwner.send(new ChunkHandoverCompleteMessage(
                    world, cx, cz, id, true, handover.newOwner.getBungeeCordName(), epoch));
            COMPLETED.incrementAndGet();
            Log.info("Handover " + id + " of " + key + " completed to "
                    + handover.newOwner.getBungeeCordName() + " (epoch " + epoch + ")");
        }
    }

    /**
     * Abandons every handover involving a connection that just closed.
     *
     * <p>When the old owner dies its chunk data may be one tick stale, but leaving
     * the chunk unowned strands every player in it, so the recorded requester is
     * granted ownership. When the requester dies the old owner keeps the chunk.</p>
     *
     * @param connection connection that closed
     */
    public static void onServerDisconnected(ServerConnection connection) {
        for (Map.Entry<ChunkKey, Handover> entry : ACTIVE.entrySet()) {
            ChunkKey key = entry.getKey();
            Handover handover = entry.getValue();
            if (handover.oldOwner == connection) {
                synchronized (ChunkLock.getChunkLock(key)) {
                    if (ACTIVE.get(key) != handover) {
                        continue;
                    }
                    ACTIVE.remove(key, handover);
                    handover.cancelTimeout();
                    ChunkSubscriptionManager.lock(handover.newOwner, key.world, key.x, key.z, true);
                    long epoch = ChunkSubscriptionManager.currentEpoch(key.world, key.x, key.z);
                    handover.newOwner.send(new ChunkHandoverCompleteMessage(
                            key.world, key.x, key.z, handover.id, true,
                            handover.newOwner.getBungeeCordName(), epoch));
                    COMPLETED.incrementAndGet();
                    Log.info("Handover " + handover.id + " of " + key
                            + " completed after the old owner disconnected");
                }
            } else if (handover.newOwner == connection) {
                synchronized (ChunkLock.getChunkLock(key)) {
                    if (ACTIVE.get(key) == handover) {
                        finish(key, handover, false, handover.oldOwner.getBungeeCordName());
                    }
                }
            }
        }
    }

    /** @return number of successful transfers (including direct grants) */
    public static long completedCount() {
        return COMPLETED.get();
    }

    /** @return number of handovers the old owner declined because it had a local player */
    public static long refusedCount() {
        return REFUSED.get();
    }

    /** @return number of handovers abandoned by the timeout */
    public static long timeoutCount() {
        return TIMED_OUT.get();
    }

    /** @return number of requests rejected (unloaded chunk or duplicate attempt) */
    public static long rejectedCount() {
        return REJECTED.get();
    }

    /** @return number of handovers currently draining */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * Changes the drain budget. Tests use a short value; production keeps the
     * default unless the system property was set.
     *
     * @param millis new budget in milliseconds
     */
    public static void configureTimeout(long millis) {
        timeoutMillis = millis;
    }

    /** @return the current drain budget in milliseconds */
    public static long timeoutMillis() {
        return timeoutMillis;
    }

    /** Abandons all in-flight handovers and resets counters; used by tests and shutdown. */
    public static void clear() {
        for (Handover handover : ACTIVE.values()) {
            handover.cancelTimeout();
        }
        ACTIVE.clear();
        COMPLETED.set(0L);
        REFUSED.set(0L);
        TIMED_OUT.set(0L);
        REJECTED.set(0L);
        timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    }

    private static void timeout(ChunkKey key, Handover handover) {
        synchronized (ChunkLock.getChunkLock(key)) {
            if (ACTIVE.get(key) != handover) {
                return;
            }
            finish(key, handover, false, handover.oldOwner.getBungeeCordName());
            TIMED_OUT.incrementAndGet();
            Log.info("Handover " + handover.id + " of " + key
                    + " timed out; " + handover.oldOwner.getBungeeCordName() + " keeps ownership");
        }
    }

    /**
     * Ends a handover without moving ownership and tells both sides.
     *
     * @param key      chunk
     * @param handover state to remove
     * @param success  always {@code false} for this path
     * @param owner    owner to report to both sides
     */
    private static void finish(ChunkKey key, Handover handover, boolean success, String owner) {
        if (!ACTIVE.remove(key, handover)) {
            return;
        }
        handover.cancelTimeout();
        long epoch = ChunkSubscriptionManager.currentEpoch(key.world, key.x, key.z);
        handover.oldOwner.send(new ChunkHandoverCompleteMessage(
                key.world, key.x, key.z, handover.id, success, owner, epoch));
        handover.newOwner.send(new ChunkHandoverCompleteMessage(
                key.world, key.x, key.z, handover.id, success, owner, epoch));
    }

    /**
     * One in-flight handover.
     *
     * <p>The timeout future is assigned after the state is published; a very fast
     * answer can therefore see {@code null}, and {@link #cancelTimeout()} treats
     * that as "nothing to cancel".</p>
     */
    private static final class Handover {
        private final long id;
        private final ChunkKey key;
        private final ServerConnection oldOwner;
        private final ServerConnection newOwner;
        private volatile ScheduledFuture<?> timeout;

        private Handover(long id, ChunkKey key, ServerConnection oldOwner, ServerConnection newOwner) {
            this.id = id;
            this.key = key;
            this.oldOwner = oldOwner;
            this.newOwner = newOwner;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeout;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}


