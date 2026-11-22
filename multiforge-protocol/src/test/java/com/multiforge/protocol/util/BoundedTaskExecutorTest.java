package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the bounded executor used to move chunk compression off the server thread. */
class BoundedTaskExecutorTest {

    @Test
    void runsTasksAndReportsSaturationToTheCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        BoundedTaskExecutor executor = new BoundedTaskExecutor(1, 1, "test-io");
        try {
            assertTrue(executor.execute(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.add(1);
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(executor.execute(() -> ran.add(2)), "one queue slot is still free");
            assertFalse(executor.execute(() -> ran.add(3)),
                    "a full queue must be reported so the caller can run the task inline");
        } finally {
            release.countDown();
            executor.close(5_000L);
        }
        assertEquals(List.of(1, 2), ran);
    }

    @Test
    void closeDrainsQueuedTasksAndRejectsNewOnes() {
        BoundedTaskExecutor executor = new BoundedTaskExecutor(1, 32, "test-drain");
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            assertTrue(executor.execute(ran::incrementAndGet));
        }
        executor.close(10_000L);
        assertEquals(20, ran.get(), "queued replies must not be dropped at shutdown");
        assertTrue(executor.isShutdown());
        assertFalse(executor.execute(ran::incrementAndGet));
        assertEquals(20, ran.get());
    }

    @Test
    void rejectsInvalidSizes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BoundedTaskExecutor(0, 1, "test"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BoundedTaskExecutor(1, 0, "test"));
    }
}
