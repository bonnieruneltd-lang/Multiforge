package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency bug hunt for the bounded executor.
 *
 * <p>Six producers race against a small pool and queue; rejected tasks are run
 * inline exactly like the chunk manager does. Afterwards every task must have run
 * exactly once: a lost task is a dropped chunk reply and a duplicate is a double
 * send. Closing the pool while tasks are still queued must not lose accepted
 * work either.</p>
 */
class BoundedTaskExecutorStressTest {

    @Test
    void concurrentSaturationNeverLosesOrDuplicatesATask() throws Exception {
        int producers = 6;
        int perProducer = 500;
        int total = producers * perProducer;
        BoundedTaskExecutor executor = new BoundedTaskExecutor(2, 8, "stress");
        Set<Integer> completed = ConcurrentHashMap.newKeySet();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        for (int producer = 0; producer < producers; producer++) {
            int base = producer * perProducer;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < perProducer; index++) {
                    int id = base + index;
                    Runnable task = () -> {
                        executions.incrementAndGet();
                        completed.add(id);
                    };
                    if (!executor.execute(task)) {
                        rejected.incrementAndGet();
                        task.run();
                    }
                }
                done.countDown();
            }, "producer-" + producer);
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "producers must finish");
        executor.close(30_000L);

        assertEquals(total, completed.size(), "every task must run");
        assertEquals(total, executions.get(), "no task may run twice");
        assertTrue(rejected.get() > 0, "the small queue must have exercised the inline fallback");
    }

    @Test
    void aFailingTaskDoesNotKillTheWorker() throws Exception {
        BoundedTaskExecutor executor = new BoundedTaskExecutor(1, 4, "failing");
        CountDownLatch ranAfterFailure = new CountDownLatch(1);
        try {
            assertTrue(executor.execute(() -> {
                throw new IllegalStateException("expected test failure");
            }));
            // Give the worker a moment to absorb the exception, then prove it still
            // accepts and runs work.
            Thread.sleep(100L);
            assertTrue(executor.execute(ranAfterFailure::countDown));
            assertTrue(ranAfterFailure.await(5, TimeUnit.SECONDS),
                    "the worker thread must survive a task that throws");
        } finally {
            executor.close(5_000L);
        }
    }

    @Test
    void closeIsIdempotent() {
        BoundedTaskExecutor executor = new BoundedTaskExecutor(1, 4, "idempotent");
        executor.close(1_000L);
        executor.close(1_000L);
        assertTrue(executor.isShutdown());
    }
}
