package com.multiforge.master;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the opt-in master leader election.
 *
 * <p>Two masters that share a storage root would corrupt ownership, entity id
 * blocks and region files, so only the holder of {@code master-leader.lock} may
 * run. The test exercises the same {@link java.nio.channels.FileLock} path in one
 * JVM: the second channel gets an {@link java.nio.channels.OverlappingFileLockException}
 * and must stand by, then take over once the first instance is closed.</p>
 */
class LeaderElectionTest {

    @TempDir
    Path storage;

    @Test
    void onlyOneMasterHoldsTheStorageLock() throws Exception {
        Path lockFile = storage.resolve("nested").resolve(LeaderElection.LOCK_FILE);
        try (LeaderElection first = new LeaderElection(lockFile, 50L);
             LeaderElection second = new LeaderElection(lockFile, 50L)) {
            assertTrue(first.tryAcquire("first 127.0.0.1:35353"), "first master must win");
            assertFalse(second.tryAcquire("second 127.0.0.1:35354"), "second master must stand by");
            assertTrue(first.isLeader());
            assertFalse(second.isLeader());
            assertTrue(Files.isRegularFile(lockFile), "the lock file must be created");

            String description = Files.readString(lockFile, StandardCharsets.UTF_8);
            assertTrue(description.contains("first 127.0.0.1:35353"), description);

            first.close();
            assertFalse(first.isLeader());
            assertTrue(second.tryAcquire("second 127.0.0.1:35354"),
                    "the standby must take over after the leader released the lock");
            assertEquals("second 127.0.0.1:35354",
                    Files.readString(lockFile, StandardCharsets.UTF_8).trim());
        }
    }

    @Test
    void awaitLeadershipBlocksUntilTheCurrentLeaderStops() throws Exception {
        Path lockFile = storage.resolve(LeaderElection.LOCK_FILE);
        LeaderElection leader = new LeaderElection(lockFile, 25L);
        LeaderElection standby = new LeaderElection(lockFile, 25L);
        try {
            assertTrue(leader.tryAcquire("leader"));
            CompletableFuture<Boolean> acquired = CompletableFuture.supplyAsync(() -> {
                try {
                    return standby.awaitLeadership("standby");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(100L);
            assertFalse(acquired.isDone(), "standby must not win while the leader is alive");
            leader.close();
            assertTrue(acquired.get(5, TimeUnit.SECONDS), "standby must win after the leader stopped");
            assertTrue(standby.isLeader());
        } finally {
            leader.close();
            standby.close();
        }
    }

    @Test
    void repeatedAcquireAndCloseAreIdempotent() throws Exception {
        LeaderElection election = new LeaderElection(storage.resolve(LeaderElection.LOCK_FILE), 50L);
        assertTrue(election.tryAcquire("one"));
        assertTrue(election.tryAcquire("two"), "acquiring again from the leader is a no-op");
        assertEquals("one", Files.readString(election.path(), StandardCharsets.UTF_8).trim());
        election.close();
        election.close();
        assertFalse(election.isLeader());
        assertTrue(election.tryAcquire("three"), "a closed election can be reopened");
        election.close();
    }
}
