package com.multiforge.protocol.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the bounded retransmit history used by the block/block-entity stream. */
class RetransmitBufferTest {

    @Test
    void acknowledgementDropsDeliveredEntriesAndReturnsTheMissingOnes() {
        RetransmitBuffer<String> buffer = new RetransmitBuffer<>(8);
        buffer.record(1L, 1L, "one");
        buffer.record(1L, 2L, "two");
        buffer.record(1L, 3L, "three");

        assertEquals(List.of("two", "three"), buffer.acknowledge(1L, 1L),
                "everything above the acknowledged value has to be resent");
        assertEquals(2, buffer.size(), "the acknowledged entry is dropped, later ones stay");
        assertEquals(List.of(), buffer.acknowledge(1L, 3L));
        assertEquals(0, buffer.size());
    }

    @Test
    void retransmittedEntriesCanBeLookedUpById() {
        RetransmitBuffer<String> buffer = new RetransmitBuffer<>(8);
        buffer.record(2L, 7L, "seven");
        assertEquals("seven", buffer.get(7L));
        assertEquals(2L, buffer.epoch());
    }

    @Test
    void theBufferEvictsOldestEntriesWhenFull() {
        RetransmitBuffer<Integer> buffer = new RetransmitBuffer<>(2);
        assertEquals(0, buffer.record(1L, 1L, 1));
        assertEquals(0, buffer.record(1L, 2L, 2));
        assertEquals(1, buffer.record(1L, 3L, 3));
        assertEquals(2, buffer.size());
        assertEquals(null, buffer.get(1));
        assertEquals(List.of(2, 3), buffer.acknowledge(1L, 0L));
    }

    @Test
    void anAcknowledgementFromANewerEpochDiscardsTheHistory() {
        RetransmitBuffer<String> buffer = new RetransmitBuffer<>(8);
        buffer.record(1L, 1L, "old");
        assertEquals(List.of(), buffer.acknowledge(2L, 1L));
        assertEquals(0, buffer.size());
        assertEquals(2L, buffer.epoch());
    }

    @Test
    void anAcknowledgementFromAnOlderEpochIsIgnored() {
        RetransmitBuffer<String> buffer = new RetransmitBuffer<>(8);
        buffer.record(3L, 1L, "current");
        assertEquals(List.of(), buffer.acknowledge(2L, 0L));
        assertEquals(1, buffer.size());
        assertTrue(buffer.snapshot().containsValue("current"));
    }

    @Test
    void resetClearsTheEpochAndTheEntries() {
        RetransmitBuffer<String> buffer = new RetransmitBuffer<>(8);
        buffer.record(4L, 1L, "value");
        buffer.reset();
        assertEquals(0, buffer.size());
        assertEquals(0L, buffer.epoch());
    }
}
