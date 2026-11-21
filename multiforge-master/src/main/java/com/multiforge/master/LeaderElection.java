package com.multiforge.master;

import com.multiforge.master.util.Log;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Optional single-leader election for masters that share a storage root.
 *
 * <p>Several masters can be pointed at the same storage directory (for a manual
 * failover setup), but only one may serve servers: two masters would hand out
 * overlapping chunk ownership, entity id blocks and player claims, and could
 * write the same region file concurrently. MultiPaper relies on file locks around
 * individual files; this class adds the missing cluster-wide leader.</p>
 *
 * <p>The election uses an exclusive OS file lock on
 * {@code master-leader.lock} in the storage root. The lock is held for the
 * lifetime of the process and released by the operating system when the process
 * exits, including crashes, so no heartbeat or timeout heuristics are needed. A
 * standby master retries until the lock becomes free and then takes over. The file
 * also records which address the current leader bound, purely for diagnostics.</p>
 *
 * <p>The feature is <b>opt-in</b> ({@code leader-election=false} by default): an
 * existing deployment that starts two independent masters on separate storage must
 * keep working unchanged. Enable it when two processes share one storage root.</p>
 *
 * <p>Threading: {@link #tryAcquire(String)} and {@link #close()} are
 * synchronized; {@link #isLeader()} is a volatile read. The lock file contains no
 * secret.</p>
 */
public final class LeaderElection implements AutoCloseable {

    /** Name of the lock file inside the storage root. */
    public static final String LOCK_FILE = "master-leader.lock";

    private final Path path;
    private final long retryMillis;
    private volatile boolean leader;
    private FileChannel channel;
    private FileLock lock;

    /**
     * @param path        lock file to hold (in the storage root)
     * @param retryMillis delay between acquisition attempts while standing by
     */
    public LeaderElection(Path path, long retryMillis) {
        this.path = path.toAbsolutePath();
        this.retryMillis = Math.max(100L, retryMillis);
    }


    /**
     * Tries to become the leader once.
     *
     * <p>An {@link OverlappingFileLockException} means another master in this JVM
     * holds the lock; it is treated exactly like a lock held by another process.</p>
     *
     * @param description free-form diagnostic written into the lock file
     * @return {@code true} when this instance now holds the lock
     * @throws IOException if the lock file cannot be created or opened
     */
    public synchronized boolean tryAcquire(String description) throws IOException {
        if (leader) {
            return true;
        }
        if (channel == null) {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        }
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return false;
        }
        if (lock == null) {
            return false;
        }
        leader = true;
        writeDescription(description);
        return true;
    }

    /**
     * Retries until this instance is the leader.
     *
     * @param description free-form diagnostic written into the lock file
     * @return {@code true} when leadership was acquired
     * @throws IOException          if the lock file cannot be created or opened
     * @throws InterruptedException if the thread was interrupted while waiting
     */
    public boolean awaitLeadership(String description) throws IOException, InterruptedException {
        int attempts = 0;
        while (!tryAcquire(description)) {
            attempts++;
            if (attempts == 1 || attempts % 12 == 0) {
                Log.info("Another master holds the storage lock; standing by (" + path + ")");
            }
            Thread.sleep(retryMillis);
        }
        return true;
    }

    /**
     * Writes the diagnostic description while the lock is held.
     *
     * @param description diagnostic written after acquiring the lock
     */
    private void writeDescription(String description) {
        if (lock == null || description == null) {
            return;
        }
        byte[] bytes = (description + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try {
            channel.truncate(0);
            channel.position(0);
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(false);
        } catch (IOException e) {
            // The lock is what matters; a failed diagnostic must not stop the master.
            Log.warn("Could not write the leader description to " + path + ": " + e.getMessage());
        }
    }

    /** @return {@code true} while this instance holds the exclusive lock */
    public boolean isLeader() {
        return leader;
    }

    /** @return lock file used by this instance */
    public Path path() {
        return path;
    }

    /** Releases the lock and closes the channel. Safe to call more than once. */
    @Override
    public synchronized void close() {
        leader = false;
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException e) {
            Log.warn("Could not release the leader lock: " + e.getMessage());
        } finally {
            lock = null;
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            Log.warn("Could not close the leader lock file: " + e.getMessage());
        } finally {
            channel = null;
        }
    }
}
