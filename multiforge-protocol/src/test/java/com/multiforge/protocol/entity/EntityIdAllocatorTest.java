package com.multiforge.protocol.entity;

import com.multiforge.protocol.messages.serverbound.IntegerPairMessageReply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the client half of the entity id block protocol.
 *
 * <p>The master integration test covers the real request/reply path; these tests
 * pin the allocator contract: ids stay inside reserved blocks, two servers never
 * share an id, prefetching avoids a blocking request on the next refill, and a
 * dead master degrades to the documented fallback instead of freezing spawns.</p>
 */
class EntityIdAllocatorTest {

    /**
     * Simulates the master's serialized block allocation shared by all callers.
     *
     * @param shared    shared master counter
     * @param blockSize ids per block
     * @return requester handing out adjacent blocks
     */
    private static EntityIdAllocator.BlockRequester sharedMaster(AtomicInteger shared, int blockSize) {
        return () -> {
            int start = shared.getAndAdd(blockSize);
            return CompletableFuture.completedFuture(new IntegerPairMessageReply(start, start + blockSize));
        };
    }

    @Test
    void twoServersReceiveDisjointBlocks() {
        AtomicInteger master = new AtomicInteger();
        List<String> warningsA = new ArrayList<>();
        List<String> warningsB = new ArrayList<>();
        EntityIdAllocator serverA = new EntityIdAllocator(() -> sharedMaster(master, 16).requestBlock(), warningsA::add);
        EntityIdAllocator serverB = new EntityIdAllocator(() -> sharedMaster(master, 16).requestBlock(), warningsB::add);

        Set<Integer> idsA = new HashSet<>();
        Set<Integer> idsB = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            idsA.add(serverA.nextId());
            idsB.add(serverB.nextId());
        }

        assertEquals(100, idsA.size(), "one server must not hand out an id twice");
        assertEquals(100, idsB.size(), "one server must not hand out an id twice");
        assertTrue(java.util.Collections.disjoint(idsA, idsB),
                "two servers must never receive the same entity id");
        assertTrue(idsA.stream().allMatch(id -> id >= 0), "ids must stay non-negative");
        assertTrue(idsB.stream().allMatch(id -> id >= 0), "ids must stay non-negative");
        assertTrue(warningsA.isEmpty() && warningsB.isEmpty(), "a healthy master must not warn");
    }

    @Test
    void refillUsesThePrefetchedBlockWithoutAnotherWait() {
        AtomicInteger master = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        EntityIdAllocator allocator = new EntityIdAllocator(() -> {
            requests.incrementAndGet();
            return sharedMaster(master, 4).requestBlock();
        }, message -> {
        });

        int first = allocator.nextId();
        allocator.prefetch().join();
        assertEquals(2, requests.get(), "the allocator keeps exactly one block prefetched");

        // Exhaust the first block; the fifth id must come from the prefetched
        // block, which was already buffered, so no request has to wait first.
        allocator.nextId();
        allocator.nextId();
        allocator.nextId();
        int fifth = allocator.nextId();
        assertNotEquals(first, fifth);
        assertEquals(3, requests.get(), "installing the buffered block starts the next prefetch");
        assertEquals(3, allocator.remainingInBlock(), "the refilled block has three ids left");
    }

    @Test
    void unavailableMasterFallsBackAndReportsIt() {
        AtomicReference<String> warning = new AtomicReference<>();
        EntityIdAllocator allocator = new EntityIdAllocator(
                () -> CompletableFuture.failedFuture(new IllegalStateException("Not connected to the master")),
                warning::set);

        int first = allocator.nextId();
        int second = allocator.nextId();

        assertTrue(first >= Integer.MAX_VALUE - 1_000_000, "fallback ids come from the high range");
        assertNotEquals(first, second);
        assertTrue(warning.get() != null && warning.get().contains("falling back"),
                "the fallback must be reported once instead of silently reusing ids");
        assertFalse(allocator.isRequesting(), "a failed request must not stay pending");
    }

    @Test
    void concurrentAllocationNeverRepeatsAnId() throws Exception {
        AtomicInteger master = new AtomicInteger();
        EntityIdAllocator allocator = new EntityIdAllocator(() -> sharedMaster(master, 64).requestBlock(), message -> {
        });
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                futures.add(executor.submit(() -> {
                    List<Integer> ids = new ArrayList<>(250);
                    for (int i = 0; i < 250; i++) {
                        ids.add(allocator.nextId());
                    }
                    return ids;
                }));
            }
            Set<Integer> all = new HashSet<>();
            for (Future<List<Integer>> future : futures) {
                for (int id : future.get(10, TimeUnit.SECONDS)) {
                    assertTrue(all.add(id), "id " + id + " was handed out twice");
                }
            }
            assertEquals(1000, all.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
