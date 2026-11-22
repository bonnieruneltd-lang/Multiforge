package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the sender history and the receiver tracker together, the way the chunk
 * manager uses them: a lossy reconnect produces a gap, the acknowledgement names
 * the contiguous value, and the retransmitted entries close it.
 */
class SequenceRecoveryTest {

    @Test
    void aLostUpdateIsDetectedAcknowledgedAndRepaired() {
        RetransmitBuffer<String> outbox = new RetransmitBuffer<>(16);
        SequenceTracker receiver = new SequenceTracker(16);
        List<String> applied = new ArrayList<>();

        // Sender produces three updates; the second one never arrives.
        for (int sequence = 1; sequence <= 3; sequence++) {
            outbox.record(1L, sequence, "block-" + sequence);
        }
        apply(receiver, applied, 1L, 1L);
        assertEquals(SequenceTracker.Result.GAP, receiver.observe(1L, 3L));
        applied.add("block-3");
        // The receiver acknowledges its highest contiguous value, 1.
        List<String> missing = outbox.acknowledge(1L, receiver.contiguous());
        assertEquals(List.of("block-2", "block-3"), missing);

        // Retransmission arrives in order; the duplicate 3 is dropped.
        apply(receiver, applied, 1L, 2L);
        assertEquals(SequenceTracker.Result.DUPLICATE, receiver.observe(1L, 3L));
        assertEquals(3L, receiver.contiguous());
        assertEquals(List.of("block-1", "block-3", "block-2"), applied);
    }

    @Test
    void anOwnershipChangeRestartsBothSides() {
        RetransmitBuffer<String> outbox = new RetransmitBuffer<>(16);
        SequenceTracker receiver = new SequenceTracker(16);
        List<String> applied = new ArrayList<>();

        outbox.record(4L, 1L, "old-1");
        apply(receiver, applied, 4L, 1L);

        // Owner change: a fresh counter starts at 1 again.
        outbox.record(5L, 1L, "new-1");
        assertEquals(SequenceTracker.Result.ACCEPTED, receiver.observe(5L, 1L));
        applied.add("new-1");
        assertEquals(1L, receiver.contiguous());
        // An acknowledgement from the new epoch drops the old history.
        assertTrue(outbox.acknowledge(4L, 1L).isEmpty(),
                "an acknowledgement from the previous owner is ignored");
        assertEquals(1, outbox.size());
        assertEquals("new-1", outbox.get(1L));
        assertTrue(outbox.acknowledge(5L, 1L).isEmpty());
        assertEquals(0, outbox.size());
    }

    private static void apply(SequenceTracker tracker, List<String> applied, long epoch, long sequence) {
        SequenceTracker.Result result = tracker.observe(epoch, sequence);
        assertTrue(result == SequenceTracker.Result.ACCEPTED || result == SequenceTracker.Result.GAP,
                "expected an applied update, got " + result);
        applied.add("block-" + sequence);
    }
}
