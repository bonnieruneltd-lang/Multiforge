package com.multiforge.protocol.entity;

import com.multiforge.protocol.messages.serverbound.IntegerPairMessageReply;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hands out cluster-unique entity ids from blocks reserved at the master.
 *
 * <p>Vanilla entity ids are a per-process counter, so two servers that both
 * simulate or mirror entities would assign the same id to different entities.
 * Clients identify entities by that id, which leads to despawns, teleports and
 * wrong metadata when ids collide. MultiPaper solves this by reserving blocks of
 * ids at the master ({@code RequestEntityIdBlock} replies {@code [start, end)})
 * and allocating locally inside a block.</p>
 *
 * <p>This class is the client half of that contract. It keeps the current block,
 * prefetches one block ahead so an id is always available without waiting, and
 * only blocks on the master connection when the current block is exhausted and
 * the prefetched block is still in flight. The upstream
 * {@code MultiPaperEntityIdManager} uses the same design; it lives in the
 * protocol module here so it can be integration tested against a real master
 * without a Minecraft runtime.</p>
 *
 * <p>Threading: {@link #nextId()} is safe to call from any thread. The fast path
 * is one atomic increment; block changes are serialized on {@link #lock}. A
 * caller that has to wait for the master blocks at most for the configured IO
 * timeout of the request future, exactly like a chunk read on an IO thread. The
 * master connection itself never calls {@link #nextId()}.</p>
 *
 * <p>Failure behaviour: if the master is unreachable, no block can be reserved.
 * Blocking every entity spawn on a dead master would freeze the server, so the
 * allocator falls back to a high, descending range
 * ({@link Integer#MAX_VALUE} downwards) and reports the fallback through the
 * warning consumer. Those ids cannot collide with master blocks in practice
 * (the master counter starts at zero and grows by one block per request), but
 * they are not persisted; a restart reserves a fresh block from the master.</p>
 */
public final class EntityIdAllocator {

    /** First id handed out by the master-unreachable fallback. */
    private static final int EMERGENCY_START = Integer.MAX_VALUE - 1;

    /** Lowest id the fallback hands out before it refuses to allocate. */
    private static final int EMERGENCY_FLOOR = Integer.MAX_VALUE - 1_000_000;

    /**
     * Requests one block of ids from the master.
     *
     * <p>The mod supplies {@code () -> master.request(new RequestEntityIdBlock(),
     * IntegerPairMessageReply.class)}; tests supply a deterministic fake.</p>
     */
    @FunctionalInterface
    public interface BlockRequester {

        /**
         * @return future completed with the reserved {@code [x, y)} block
         */
        CompletableFuture<IntegerPairMessageReply> requestBlock();
    }

    private final BlockRequester requester;
    private final Consumer<String> warnings;
    private final Object lock = new Object();
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger emergencyCounter = new AtomicInteger(EMERGENCY_START);

    /** Current block, {@code null} before the first reservation. Guarded by {@link #lock}. */
    private Block current;

    /** Prefetched block, {@code null} while a request is in flight or missing. Guarded by {@link #lock}. */
    private Block next;

    /** In-flight prefetch, {@code null} when idle. Guarded by {@link #lock}. */
    private CompletableFuture<Void> pendingRequest;

    /** Identifies the newest request; stale completions must not publish blocks. */
    private long requestGeneration;

    /** Set once the fallback was reported, so a dead master does not log per entity. */
    private boolean fallbackReported;

    /**
     * @param requester source of reserved blocks, usually the master connection
     * @param warnings  sink for allocation problems (the mod passes its logger)
     */
    public EntityIdAllocator(BlockRequester requester, Consumer<String> warnings) {
        this.requester = Objects.requireNonNull(requester, "requester");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /**
     * Allocates the next id.
     *
     * <p>Returns immediately while the current block has ids left. When it is
     * exhausted the prefetched block is installed and a new prefetch starts, so
     * the next call is fast again. Only when no prefetched block exists (startup
     * or a slow master) does this block on the request future.</p>
     *
     * @return a positive id that no other server in the cluster can allocate
     * @throws IllegalStateException when both the master and the fallback range
     *                               are exhausted (ids are never reused)
     */
    public int nextId() {
        CompletableFuture<Void> request = null;
        synchronized (lock) {
            Block block = current;
            if (block != null && block.contains(counter.get())) {
                return counter.getAndIncrement();
            }
            if (next != null) {
                return installNext();
            }
            request = pendingRequest != null ? pendingRequest : startRequest();
        }
        // The request completion needs the lock to publish the block, so it must
        // not be awaited while the lock is held.
        try {
            request.join();
        } catch (RuntimeException e) {
            reportFallback("could not reserve an entity id block: " + rootMessage(e));
        }
        synchronized (lock) {
            Block block = current;
            if (block != null && block.contains(counter.get())) {
                return counter.getAndIncrement();
            }
            if (next != null) {
                return installNext();
            }
        }
        return emergencyId();
    }

    /**
     * Requests the next block in the background, if none is buffered yet.
     *
     * <p>Called when the master connection is (re)established so the first entity
     * after a reconnect does not have to wait. Safe to call repeatedly.</p>
     *
     * @return future completed when a prefetched block is available, or
     *         exceptionally when the request failed
     */
    public CompletableFuture<Void> prefetch() {
        synchronized (lock) {
            if (next != null) {
                return CompletableFuture.completedFuture(null);
            }
            if (pendingRequest != null) {
                return pendingRequest;
            }
            return startRequest();
        }
    }

    /**
     * @return number of ids in the current block that are still available; used
     *         by diagnostics and tests
     */
    public int remainingInBlock() {
        synchronized (lock) {
            if (current == null) {
                return 0;
            }
            return Math.max(0, current.end - counter.get());
        }
    }

    /**
     * @return {@code true} while a block request is in flight
     */
    public boolean isRequesting() {
        synchronized (lock) {
            return pendingRequest != null;
        }
    }

    /**
     * Drops all buffered blocks and resets the fallback counter. Called from
     * tests and when a server shuts down; a new allocator instance is created on
     * the next start.
     */
    public void clear() {
        synchronized (lock) {
            current = null;
            next = null;
            pendingRequest = null;
            requestGeneration++;
            counter.set(0);
            emergencyCounter.set(EMERGENCY_START);
            fallbackReported = false;
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Installs the prefetched block, starts the next prefetch and returns the
     * first id of the new block. Must be called with {@link #lock} held.
     *
     * @return allocated id
     */
    private int installNext() {
        Block buffered = next;
        next = null;
        current = buffered;
        counter.set(buffered.start);
        startRequest();
        return counter.getAndIncrement();
    }

    /**
     * Starts one block request. Must be called with {@link #lock} held; the
     * request completion runs on a Netty or master-connection thread and only
     * touches the guarded fields through synchronized blocks.
     *
     * @return future completed when the block was buffered
     */
    private CompletableFuture<Void> startRequest() {
        final long generation = ++requestGeneration;
        CompletableFuture<Void> result = new CompletableFuture<>();
        pendingRequest = result;
        CompletableFuture<IntegerPairMessageReply> requested;
        try {
            requested = requester.requestBlock();
        } catch (RuntimeException e) {
            requested = CompletableFuture.failedFuture(e);
        }
        final CompletableFuture<IntegerPairMessageReply> raw = requested != null
                ? requested
                : CompletableFuture.failedFuture(new IllegalStateException("requester returned no future"));
        raw.whenComplete((reply, error) -> {
            if (error == null && (reply == null || reply.y <= reply.x)) {
                error = new IllegalStateException("master returned an empty entity id block");
            }
            synchronized (lock) {
                if (requestGeneration == generation) {
                    if (error == null) {
                        next = new Block(reply.x, reply.y);
                    }
                    pendingRequest = null;
                }
            }
            if (error != null) {
                reportFallback("entity id block request failed: " + rootMessage(error));
                result.completeExceptionally(error);
            } else {
                result.complete(null);
            }
        });
        return result;
    }

    /**
     * @return an id from the descending fallback range used while the master is
     *         unreachable
     * @throws IllegalStateException when the fallback range is used up
     */
    private int emergencyId() {
        int id = emergencyCounter.getAndDecrement();
        if (id <= EMERGENCY_FLOOR) {
            throw new IllegalStateException(
                    "Entity id space exhausted (master unreachable and fallback range used)");
        }
        return id;
    }

    private void reportFallback(String message) {
        synchronized (lock) {
            if (fallbackReported) {
                return;
            }
            fallbackReported = true;
        }
        warnings.accept(message + "; falling back to the high local entity id range");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * Reserved id interval {@code [start, end)}.
     *
     * @param start first id of the block
     * @param end   first id after the block
     */
    private record Block(int start, int end) {

        boolean contains(int id) {
            return id >= start && id < end;
        }
    }
}

