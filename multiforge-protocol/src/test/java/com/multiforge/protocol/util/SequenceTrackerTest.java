package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests per-chunk ordering, gap detection and epoch resets. */
class SequenceTrackerTest {

    @Test
    void sequentialUpdatesAreAccepted() {
        SequenceTracker tracker = new SequenceTracker(8);
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(1L, 1L));
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(1L, 2L));
        assertEquals(2L, tracker.contiguous());
    }

    @Test
    void duplicatesAndLateValuesAreDropped() {
        SequenceTracker tracker = new SequenceTracker(8);
        tracker.observe(1L, 1L);
        tracker.observe(1L, 2L);
        assertEquals(SequenceTracker.Result.DUPLICATE, tracker.observe(1L, 2L));
        assertEquals(SequenceTracker.Result.DUPLICATE, tracker.observe(1L, 1L));
        assertEquals(2L, tracker.contiguous());
    }

    @Test
    void aGapIsReportedAndClosedWhenTheMissingUpdateArrives() {
        SequenceTracker tracker = new SequenceTracker(8);
        tracker.observe(1L, 1L);
        assertEquals(SequenceTracker.Result.GAP, tracker.observe(1L, 3L));
        assertEquals(1L, tracker.contiguous(), "the hole must not be acknowledged as applied");
        assertEquals(1, tracker.pending());
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(1L, 2L));
        assertEquals(3L, tracker.contiguous(), "filling the hole advances through buffered values");
        assertEquals(0, tracker.pending());
    }

    @Test
    void updatesFromAnOldEpochAreRejectedAndANewEpochResetsTheWindow() {
        SequenceTracker tracker = new SequenceTracker(8);
        tracker.observe(4L, 1L);
        tracker.observe(4L, 2L);
        assertEquals(SequenceTracker.Result.STALE_EPOCH, tracker.observe(3L, 9L));
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(5L, 1L));
        assertEquals(5L, tracker.epoch());
        assertEquals(1L, tracker.contiguous());
    }

    @Test
    void legacyPacketsWithoutSequenceOrEpochAreStillAccepted() {
        SequenceTracker tracker = new SequenceTracker(8);
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(0L, 0L));
        assertEquals(SequenceTracker.Result.ACCEPTED, tracker.observe(1L, 0L));
        assertEquals(0L, tracker.contiguous());
    }

    @Test
    void outgoingSequenceRestartsWhenOwnershipChanges() {
        SequenceTracker tracker = new SequenceTracker(8);
        assertEquals(1L, tracker.nextOutgoing(7L));
        assertEquals(2L, tracker.nextOutgoing(7L));
        assertEquals(1L, tracker.nextOutgoing(8L));
        assertEquals(8L, tracker.epoch());
    }

    @Test
    void theWindowBoundsMemoryWhenAGapIsNeverFilled() {
        SequenceTracker tracker = new SequenceTracker(3);
        tracker.observe(1L, 1L);
        for (long sequence = 3; sequence <= 10; sequence++) {
            tracker.observe(1L, sequence);
        }
        assertEquals(1L, tracker.contiguous());
        assertEquals(3, tracker.pending());
    }

    @Test
    void anEvictedValueIsStillRecognisedAsDuplicateWhenItArrivesAgain() {
        SequenceTracker tracker = new SequenceTracker(2);
        tracker.observe(1L, 1L);
        assertEquals(SequenceTracker.Result.GAP, tracker.observe(1L, 3L));
        assertEquals(SequenceTracker.Result.GAP, tracker.observe(1L, 5L));
        // The window now holds two values; the next gap evicts the highest one.
        assertEquals(SequenceTracker.Result.GAP, tracker.observe(1L, 7L));
        assertEquals(2, tracker.pending());
        assertEquals(SequenceTracker.Result.DUPLICATE, tracker.observe(1L, 7L),
                "a value that was applied once must never be applied twice, even after eviction");
    }

    @Test
    void resetForgetsEverything() {
        SequenceTracker tracker = new SequenceTracker(8);
        tracker.observe(2L, 1L);
        assertEquals(1L, tracker.nextOutgoing(2L), "an unchanged epoch keeps the outgoing counter");
        tracker.reset();
        assertEquals(0L, tracker.epoch());
        assertEquals(0L, tracker.contiguous());
        assertEquals(1L, tracker.nextOutgoing(0L));
    }
}
