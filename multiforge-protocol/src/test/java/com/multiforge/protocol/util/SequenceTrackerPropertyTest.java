package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property test for the sequence arithmetic.
 *
 * <p>Runs thousands of random observations (including epoch changes, gaps,
 * duplicates and window eviction) against a small reference model and compares
 * every result, the contiguous value and the pending count. This is the bug hunt
 * for the ordering logic: any divergence between the model and the tracker is a
 * failure.</p>
 */
class SequenceTrackerPropertyTest {

    @Test
    void randomObservationsMatchTheReferenceModel() {
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            int window = 1 + random.nextInt(8);
            SequenceTracker tracker = new SequenceTracker(window);
            Model model = new Model(window);
            long epoch = 1L;
            long next = 1L;
            for (int step = 0; step < 4_000; step++) {
                int action = random.nextInt(100);
                if (action < 3) {
                    epoch++;
                }
                long sequence;
                if (action < 55) {
                    sequence = next;
                    next += 1 + (random.nextInt(4) == 0 ? 1 + random.nextInt(3) : 0);
                } else {
                    // Replay an older or future value, including values below the
                    // window's high-water mark that may have been evicted.
                    sequence = 1 + random.nextInt((int) Math.max(1L, next + 2L));
                }
                SequenceTracker.Result expected = model.observe(epoch, sequence);
                SequenceTracker.Result actual = tracker.observe(epoch, sequence);
                assertEquals(expected, actual,
                        "seed " + seed + " step " + step + " epoch " + epoch + " sequence " + sequence);
                assertEquals(model.contiguous, tracker.contiguous(),
                        "seed " + seed + " step " + step + " contiguous");
                assertEquals(model.ahead.size(), tracker.pending(),
                        "seed " + seed + " step " + step + " pending");
            }
        }
    }

    /** Deliberately simple reference implementation of the documented contract. */
    private static final class Model {
        private final int window;
        private final TreeSet<Long> ahead = new TreeSet<>();
        private final TreeSet<Long> evicted = new TreeSet<>();
        private long epoch;
        private long contiguous;

        private Model(int window) {
            this.window = window;
        }

        private SequenceTracker.Result observe(long packetEpoch, long sequence) {
            if (sequence <= 0L || packetEpoch <= 0L) {
                return SequenceTracker.Result.ACCEPTED;
            }
            if (packetEpoch < epoch) {
                return SequenceTracker.Result.STALE_EPOCH;
            }
            if (packetEpoch > epoch) {
                epoch = packetEpoch;
                contiguous = 0L;
                ahead.clear();
                evicted.clear();
            }
            if (sequence <= contiguous || ahead.contains(sequence) || evicted.contains(sequence)) {
                return SequenceTracker.Result.DUPLICATE;
            }
            ahead.add(sequence);
            if (sequence == contiguous + 1L) {
                while (ahead.remove(contiguous + 1L)) {
                    contiguous++;
                }
                return SequenceTracker.Result.ACCEPTED;
            }
            if (ahead.size() > window) {
                Long removed = ahead.pollLast();
                if (removed != null) {
                    evicted.add(removed);
                    while (evicted.size() > SequenceTracker.EVICTED_HISTORY) {
                        evicted.pollFirst();
                    }
                }
            }
            return SequenceTracker.Result.GAP;
        }
    }
}
