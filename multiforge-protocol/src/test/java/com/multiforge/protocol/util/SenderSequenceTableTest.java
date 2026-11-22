package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests per-sender ordering for a chunk that more than one server changed. */
class SenderSequenceTableTest {

    @Test
    void sendersUseIndependentCounters() {
        SenderSequenceTable table = new SenderSequenceTable(8);
        assertEquals(SequenceTracker.Result.ACCEPTED, table.observe("live1", 1L, 1L));
        assertEquals(SequenceTracker.Result.ACCEPTED, table.observe("live2", 1L, 1L),
                "the second sender starts its own numbering at one");
        assertEquals(SequenceTracker.Result.ACCEPTED, table.observe("live1", 1L, 2L));
        assertEquals(SequenceTracker.Result.ACCEPTED, table.observe("live2", 1L, 2L));
        assertEquals(2L, table.contiguous("live1"));
        assertEquals(2L, table.contiguous("live2"));
        assertEquals(2, table.senderCount());
    }

    @Test
    void aDuplicateFromOneSenderDoesNotAdvanceTheOther() {
        SenderSequenceTable table = new SenderSequenceTable(8);
        table.observe("live1", 1L, 1L);
        table.observe("live1", 1L, 2L);
        table.observe("live2", 1L, 1L);
        assertEquals(SequenceTracker.Result.DUPLICATE, table.observe("live1", 1L, 2L));
        assertEquals(2L, table.contiguous("live1"));
        assertEquals(1L, table.contiguous("live2"));
    }

    @Test
    void anEpochChangeOnlyResetsThatSender() {
        SenderSequenceTable table = new SenderSequenceTable(8);
        table.observe("live1", 1L, 1L);
        table.observe("live2", 1L, 1L);
        assertEquals(SequenceTracker.Result.GAP, table.observe("live1", 2L, 3L),
                "a new epoch restarts the sender's window");
        assertEquals(0L, table.contiguous("live1"));
        assertEquals(1L, table.contiguous("live2"));
    }

    @Test
    void anUnknownSenderHasNoContiguousValueAndNullIsTreatedAsEmptyName() {
        SenderSequenceTable table = new SenderSequenceTable(8);
        assertEquals(0L, table.contiguous("never-sent"));
        assertEquals(SequenceTracker.Result.ACCEPTED, table.observe(null, 1L, 1L));
        assertEquals(1L, table.contiguous(""));
        assertEquals(1L, table.contiguous(null));
    }

    @Test
    void clearForgetsEverySender() {
        SenderSequenceTable table = new SenderSequenceTable(8);
        table.observe("live1", 1L, 1L);
        table.clear();
        assertEquals(0, table.senderCount());
        assertEquals(0L, table.contiguous("live1"));
    }
}
