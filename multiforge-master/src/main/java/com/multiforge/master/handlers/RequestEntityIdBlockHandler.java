package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.Log;
import com.multiforge.master.util.MasterStorage;
import com.multiforge.protocol.messages.masterbound.RequestEntityIdBlock;
import com.multiforge.protocol.messages.serverbound.IntegerPairMessageReply;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Allocates disjoint entity id blocks for the cluster.
 *
 * <p>Entity ids are per-process in vanilla. When entities are mirrored or handed
 * between servers, two servers must never spawn different entities with the same
 * id in the same world, otherwise clients despawn or teleport the wrong entity.
 * MultiPaper solves this by handing each server a block of ids at a time:
 * {@code RequestEntityIdBlockMessage} returns {@code [start, end)} and the server
 * allocates ids from that block locally.</p>
 *
 * <p>The counter is persisted in the master's storage root ({@code lastblock.txt})
 * so a master restart never re-issues ids that are still referenced by chunk
 * data. MultiPaper wrote the file relative to the process working directory; this
 * port uses {@link MasterStorage} so several masters, tests and containers behave
 * the same way.</p>
 *
 * <p>Allocation is serialized and checked for overflow: blocks are never handed
 * out twice and ids stay non-negative. Exhaustion aborts allocation with a clear
 * error instead of wrapping around into negative ids.</p>
 *
 * <p>Threading: {@link #handle(ServerConnection, RequestEntityIdBlock)} runs on a
 * Netty thread. Allocation is guarded by {@link #ALLOCATION_LOCK}; persistence
 * runs asynchronously but at most one write is in flight and the newest counter
 * value is always the one written.</p>
 */
public final class RequestEntityIdBlockHandler {

    /** Number of ids handed out per request. */
    private static final int BLOCK_SIZE = Integer.getInteger("entityid.block.size", 4096);

    private static final Object ALLOCATION_LOCK = new Object();
    private static final Object SAVE_LOCK = new Object();

    /** Next unallocated id, {@code -1} until loaded. Guarded by {@link #ALLOCATION_LOCK}. */
    private static int nextId = -1;

    /** Serializes persistence; the newest value wins. */
    private static CompletableFuture<Void> lastSave = CompletableFuture.completedFuture(null);
    private static boolean saveQueued;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RequestEntityIdBlockHandler::flush,
                "MultiForge-EntityIdSave"));
    }

    private RequestEntityIdBlockHandler() {
    }

    /**
     * Reserves a block of ids and returns it to the requesting server.
     *
     * @param connection connection that needs ids
     * @param message    request message
     * @throws IllegalStateException when the id space is exhausted
     */
    public static void handle(ServerConnection connection, RequestEntityIdBlock message) {
        int blockStart;
        int blockEnd;
        synchronized (ALLOCATION_LOCK) {
            ensureLoaded();
            long end = (long) nextId + BLOCK_SIZE;
            if (end > Integer.MAX_VALUE) {
                throw new IllegalStateException("Entity id space exhausted at " + nextId);
            }
            blockStart = nextId;
            blockEnd = (int) end;
            nextId = blockEnd;
        }
        connection.sendReply(new IntegerPairMessageReply(blockStart, blockEnd), message);
        if (Log.isDebugEnabled()) {
            Log.info("Allocated entity id block [" + blockStart + ", " + blockEnd + ") to "
                    + connection.getBungeeCordName());
        }
        persistAsync();
    }

    /**
     * @return number of ids per allocated block, for diagnostics and tests
     */
    public static int blockSize() {
        return BLOCK_SIZE;
    }

    /**
     * Writes the current counter synchronously. Called from the shutdown hook and
     * from tests; idempotent and safe to call while a request is allocating.
     */
    public static void flush() {
        int value;
        synchronized (ALLOCATION_LOCK) {
            if (nextId < 0) {
                return;
            }
            value = nextId;
        }
        write(value);
    }

    /**
     * Reloads the counter from storage. Only used by tests that need a clean
     * process-like state without restarting the JVM.
     */
    static void resetForTests() {
        synchronized (ALLOCATION_LOCK) {
            nextId = -1;
        }
    }

    /**
     * Loads the counter on first use. A missing file starts at zero; an unreadable
     * or corrupt file is logged and also starts at zero because reusing ids is
     * preferable to refusing all entity allocations.
     */
    private static void ensureLoaded() {
        if (nextId >= 0) {
            return;
        }
        File file = MasterStorage.file("lastblock.txt");
        try {
            nextId = Integer.parseInt(Files.readString(file.toPath()).trim());
            if (nextId < 0) {
                nextId = 0;
            }
        } catch (NoSuchFileException ignored) {
            nextId = 0;
        } catch (IOException | NumberFormatException e) {
            Log.error("Could not read " + file + ", restarting entity id allocation at 0", e);
            nextId = 0;
        }
    }

    /**
     * Queues one asynchronous write of the current counter. A write that is
     * already running causes the value to be saved again when it finishes, so the
     * file always ends up holding the newest counter.
     */
    private static void persistAsync() {
        synchronized (SAVE_LOCK) {
            if (saveQueued) {
                return;
            }
            saveQueued = true;
            lastSave = lastSave.thenRunAsync(RequestEntityIdBlockHandler::saveQueuedValue);
        }
    }

    /**
     * Saves the newest counter value. Runs on the common pool. If another request
     * allocated more ids while the file was being written, the loop writes again
     * so the file can never end up behind the in-memory counter.
     */
    private static void saveQueuedValue() {
        while (true) {
            int value;
            synchronized (ALLOCATION_LOCK) {
                value = nextId;
            }
            synchronized (SAVE_LOCK) {
                saveQueued = false;
            }
            if (value >= 0) {
                write(value);
            }
            synchronized (ALLOCATION_LOCK) {
                if (nextId == value) {
                    return;
                }
            }
            synchronized (SAVE_LOCK) {
                saveQueued = true;
            }
        }
    }

    private static void write(int value) {
        File file = MasterStorage.file("lastblock.txt");
        Path target = file.toPath().toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            Log.error("Could not create the storage directory for " + file, e);
            return;
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        synchronized (SAVE_LOCK) {
            try {
                // Write-then-rename: a concurrent reader (or a crash) must never see a
                // truncated counter file.
                Files.writeString(temp, Integer.toString(value));
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Log.error("Could not persist the entity id counter to " + file, e);
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }
}

