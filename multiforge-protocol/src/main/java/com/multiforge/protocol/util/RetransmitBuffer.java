package com.multiforge.protocol.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded history of sent updates, used to fill gaps reported by peers.
 *
 * <p>When a receiver detects a {@link SequenceTracker.Result#GAP} it acknowledges
 * only the highest <em>contiguous</em> sequence. The sender looks the missing
 * entries up here and resends them. Entries are dropped as soon as the peer
 * acknowledges them, so a healthy connection keeps only the in-flight tail.</p>
 *
 * <p>The buffer belongs to one chunk and one ownership epoch. An acknowledgement
 * from a newer epoch means the chunk changed owner, so the history is discarded:
 * the new owner starts its own numbering and the old entries are meaningless.</p>
 *
 * <p>Threading: all methods are synchronized; the buffer is small and touched
 * from the server thread and from Netty handlers.</p>
 *
 * @param <T> payload type (a peer packet in production)
 */
public final class RetransmitBuffer<T> {

    private final int capacity;
    private final TreeMap<Long, T> entries = new TreeMap<>();
    private long epoch;

    /** @param capacity maximum number of remembered updates */
    public RetransmitBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least one");
        }
        this.capacity = capacity;
    }

    /**
     * Stores an update that was just sent.
     *
     * @param currentEpoch ownership epoch of the update
     * @param sequence     sequence number of the update
     * @param payload      packet to resend when the receiver reports a gap
     * @return number of entries evicted because the buffer is full
     */
    public synchronized int record(long currentEpoch, long sequence, T payload) {
        if (currentEpoch > epoch) {
            epoch = currentEpoch;
            entries.clear();
        }
        if (currentEpoch < epoch) {
            return 0;
        }
        entries.put(sequence, payload);
        int evicted = 0;
        while (entries.size() > capacity) {
            entries.pollFirstEntry();
            evicted++;
        }
        return evicted;
    }

    /**
     * Applies an acknowledgement from a peer.
     *
     * @param ackEpoch  ownership epoch the peer acknowledged
     * @param contiguous highest contiguous sequence the peer applied
     * @return updates the peer is missing, in ascending sequence order; empty
     *         when nothing has to be retransmitted
     */
    public synchronized List<T> acknowledge(long ackEpoch, long contiguous) {
        if (ackEpoch > epoch) {
            entries.clear();
            epoch = ackEpoch;
            return List.of();
        }
        if (ackEpoch < epoch) {
            return List.of();
        }
        entries.headMap(contiguous, true).clear();
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    /** Clears the history, for example when the chunk unloads. */
    public synchronized void reset() {
        entries.clear();
        epoch = 0L;
    }

    /** @return number of remembered updates */
    public synchronized int size() {
        return entries.size();
    }

    /** @return ownership epoch of the remembered updates */
    public synchronized long epoch() {
        return epoch;
    }

    /**
     * @param sequence sequence number
     * @return remembered payload, or {@code null}
     */
    public synchronized T get(long sequence) {
        return entries.get(sequence);
    }

    /** @return the remembered entries, oldest first */
    public synchronized Map<Long, T> snapshot() {
        return Map.copyOf(entries);
    }
}
