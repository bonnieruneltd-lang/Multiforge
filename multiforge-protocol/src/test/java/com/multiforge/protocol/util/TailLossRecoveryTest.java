package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the tail-loss heartbeat.
 *
 * <p>A receiver only sees sequence numbers that arrive, so a vanished <em>last</em>
 * update is invisible to {@link SequenceTracker}: there is no higher number to reveal
 * a gap. The chunk manager therefore re-acknowledges the highest contiguous value
 * once per second after the last update. This test models one burst: the sender
 * records 40 updates, the receiver gets the first 39, the ordinary ack names 39, and
 * the sender's history is expected to return the tail. Applying the retransmitted
 * update advances the contiguous value and empties the history.</p>
 */
class TailLossRecoveryTest {

    @Test
    void heartbeatAckReturnsTheLostTailAndConverges() {
        RetransmitBuffer<String> outbox = new RetransmitBuffer<>(128);
        SequenceTracker receiver = new SequenceTracker(128);
        long epoch = 7L;

        for (int sequence = 1; sequence <= 40; sequence++) {
            outbox.record(epoch, sequence, "update-" + sequence);
            if (sequence <= 39) {
                receiver.observe(epoch, sequence);
            }
        }
        assertEquals(39L, receiver.contiguous(), "the receiver saw 1..39");

        // This is the heartbeat (or the ack carried by update 39): the highest
        // contiguous value is still 39, and the sender must return the lost tail.
        List<String> missing = outbox.acknowledge(epoch, receiver.contiguous());
        assertEquals(List.of("update-40"), missing);

        // The retransmission arrives; there is nothing left above it.
        assertEquals(SequenceTracker.Result.ACCEPTED, receiver.observe(epoch, 40L));
        assertEquals(40L, receiver.contiguous());
        assertTrue(outbox.acknowledge(epoch, receiver.contiguous()).isEmpty());
        assertEquals(0, outbox.size(), "the acknowledged history is empty");
    }

    @Test
    void heartbeatDoesNotResendAcknowledgedUpdates() {
        RetransmitBuffer<String> outbox = new RetransmitBuffer<>(8);
        SequenceTracker receiver = new SequenceTracker(8);
        for (int sequence = 1; sequence <= 3; sequence++) {
            outbox.record(1L, sequence, "u" + sequence);
            receiver.observe(1L, sequence);
        }
        // Repeating the same heartbeat ack must not produce duplicates.
        assertTrue(outbox.acknowledge(1L, receiver.contiguous()).isEmpty());
        assertTrue(outbox.acknowledge(1L, receiver.contiguous()).isEmpty());
        assertEquals(0, outbox.size());
    }

    @Test
    void anEpochChangeDiscardsTheOldTail() {
        RetransmitBuffer<String> outbox = new RetransmitBuffer<>(8);
        SequenceTracker receiver = new SequenceTracker(8);
        outbox.record(1L, 1L, "old");
        receiver.observe(1L, 1L);
        // The chunk changes owner; the receiver's new tracker starts at sequence 1.
        SequenceTracker next = new SequenceTracker(8);
        assertEquals(SequenceTracker.Result.ACCEPTED, next.observe(2L, 1L));
        // A heartbeat for the old epoch must not resend old-owner state.
        assertTrue(outbox.acknowledge(2L, 1L).isEmpty());
        assertEquals(0, outbox.size());
    }
}
