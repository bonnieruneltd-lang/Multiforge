package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Randomised loss/reconnect test for the sender buffer and the receiver tracker.
 *
 * <p>Every run sends a few hundred updates, drops a random subset, then plays the
 * acknowledgement/retransmission loop the chunk manager implements and checks
 * that the receiver ends up with exactly the sent stream and no duplicate
 * application.</p>
 */
class SequenceRecoveryRandomTest {

    @Test
    void randomLossIsAlwaysRepairedByAcknowledgements() {
        for (long seed = 1; seed <= 20; seed++) {
            Random random = new Random(seed);
            int total = 300;
            RetransmitBuffer<Integer> outbox = new RetransmitBuffer<>(total);
            SequenceTracker receiver = new SequenceTracker(total);
            List<Integer> applied = new ArrayList<>();
            boolean[] delivered = new boolean[total + 1];

            for (int sequence = 1; sequence <= total; sequence++) {
                outbox.record(1L, sequence, sequence);
            }
            // First delivery: every 5th packet is lost, the rest arrive in order.
            for (int sequence = 1; sequence <= total; sequence++) {
                if (sequence % 5 == 0) {
                    continue;
                }
                deliver(receiver, applied, delivered, sequence);
            }

            int rounds = 0;
            while (receiver.contiguous() < total && rounds++ < 10_000) {
                List<Integer> missing = outbox.acknowledge(1L, receiver.contiguous());
                if (missing.isEmpty()) {
                    break;
                }
                boolean progressed = false;
                for (Integer sequence : missing) {
                    if (!delivered[sequence]) {
                        progressed |= deliver(receiver, applied, delivered, sequence);
                    }
                }
                if (!progressed) {
                    break;
                }
            }

            assertEquals(total, receiver.contiguous(), "seed " + seed + ": every gap must close");
            assertEquals(total, deliveredCount(delivered), "seed " + seed + ": every update arrives");
            assertEquals(total, applied.size(), "seed " + seed + ": no update is applied twice");
            assertEquals(total, applied.stream().distinct().count(),
                    "seed " + seed + ": the applied stream must contain every sequence once");
        }
    }

    private static boolean deliver(SequenceTracker receiver, List<Integer> applied,
                                   boolean[] delivered, int sequence) {
        boolean wasDelivered = delivered[sequence];
        SequenceTracker.Result result = receiver.observe(1L, sequence);
        if (result == SequenceTracker.Result.ACCEPTED || result == SequenceTracker.Result.GAP) {
            if (wasDelivered) {
                throw new AssertionError("sequence " + sequence + " was applied twice");
            }
            delivered[sequence] = true;
            applied.add(sequence);
            return true;
        }
        return false;
    }

    private static int deliveredCount(boolean[] delivered) {
        int count = 0;
        for (int index = 1; index < delivered.length; index++) {
            if (delivered[index]) {
                count++;
            }
        }
        return count;
    }
}
