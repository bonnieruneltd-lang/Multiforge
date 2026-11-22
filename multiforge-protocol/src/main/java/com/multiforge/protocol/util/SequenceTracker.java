package com.multiforge.protocol.util;

import java.util.TreeSet;

/**
 * Per-chunk sequence arithmetic for the peer block/block-entity stream.
 *
 * <p>Ownership epochs alone drop updates from an <em>older</em> owner, but two
 * updates from the same owner can still be applied out of order when a sender
 * retransmits or a delayed packet arrives after the receiver already moved on.
 * This tracker adds a monotonically increasing sequence per chunk and owner epoch
 * and classifies every received update:</p>
 *
 * <ul>
 *   <li>{@link Result#ACCEPTED}: next expected value; apply it;</li>
 *   <li>{@link Result#GAP}: newer than expected but one or more predecessors are
 *       missing; apply it, acknowledge the highest contiguous value and let the
 *       sender retransmit the hole;</li>
 *   <li>{@link Result#DUPLICATE}: already seen; drop it;</li>
 *   <li>{@link Result#STALE_EPOCH}: from a previous owner; drop it.</li>
 * </ul>
 *
 * <p>Outgoing and incoming counters are independent: a server that owns a chunk
 * numbers its own broadcasts, and a server that mirrors the chunk orders what it
 * receives. Both live in the same tracker because they always belong to the same
 * chunk state and change together on ownership handover.</p>
 *
 * <p>Two bounded-resynchronisation rules keep the state finite and stop an
 * acknowledgement loop that can never be satisfied: a gap wider than
 * {@code window} adopts the newest value as the baseline, and the first packet
 * from a tracker that has never seen one is used as the baseline (a peer that
 * connected mid-stream). Both are reported as {@link Result#GAP} so the caller can
 * log the repair.</p>
 *
 * <p>Threading: all methods are synchronized. State is tiny and only touched on
 * the server thread, but keeping the class itself safe makes it testable and
 * usable from the chunk state without extra discipline.</p>
 */
public final class SequenceTracker {

    /** Outcome of {@link #observe(long, long)}. */
    public enum Result {
        /** The update is the next expected one; apply it. */
        ACCEPTED,
        /** The update is newer but some predecessors were not seen; apply it. */
        GAP,
        /** The update (or its whole epoch) was already applied; drop it. */
        DUPLICATE,
        /** The update belongs to an ownership epoch that was already superseded. */
        STALE_EPOCH
    }

    /** Maximum number of out-of-order sequence numbers kept per chunk. */
    private final int window;

    /** Current ownership epoch of this tracker. */
    private long epoch;

    /** Next sequence number to stamp on an outgoing update. */
    private long nextOutgoing = 1L;

    /** Highest contiguous sequence received in {@link #epoch}. */
    private long contiguous;

    /** Received values above {@link #contiguous}, bounded by {@link #window}. */
    private final TreeSet<Long> ahead = new TreeSet<>();

    /** @param window maximum number of out-of-order values to remember */
    public SequenceTracker(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least one");
        }
        this.window = window;
    }

    /**
     * Returns the next outgoing sequence number, resetting the counter when the
     * ownership epoch changed (a new owner starts a fresh numbering).
     *
     * @param currentEpoch ownership epoch the update is produced under
     * @return sequence number to stamp on the next outgoing update
     */
    public synchronized long nextOutgoing(long currentEpoch) {
        if (currentEpoch > epoch) {
            epoch = currentEpoch;
            nextOutgoing = 1L;
            contiguous = 0L;
            ahead.clear();
        }
        return nextOutgoing++;
    }

    /**
     * Classifies a received update.
     *
     * @param packetEpoch ownership epoch stamped into the packet; {@code 0} means
     *                    the sender has no epoch information and the packet is
     *                    accepted without touching the ordering state
     * @param sequence    per-chunk sequence number; {@code 0} means no sequence
     * @return classification, see {@link Result}
     */
    public synchronized Result observe(long packetEpoch, long sequence) {
        if (sequence <= 0L || packetEpoch <= 0L) {
            // Legacy peer or a change made before the master announced ownership.
            // Such a packet cannot be ordered, so it is applied as before.
            return Result.ACCEPTED;
        }
        if (packetEpoch < epoch) {
            return Result.STALE_EPOCH;
        }
        boolean firstEpoch = epoch == 0L;
        if (packetEpoch > epoch) {
            epoch = packetEpoch;
            contiguous = 0L;
            ahead.clear();
        }
        if (firstEpoch && contiguous == 0L && ahead.isEmpty() && sequence > 1L) {
            // First packet this tracker ever sees: the peer connected while the
            // chunk was already being updated, so earlier numbers were never sent
            // to it. Adopt this value as the baseline instead of reporting a gap
            // the sender's bounded history can no longer fill.
            contiguous = sequence - 1L;
        }
        if (sequence <= contiguous || (!ahead.isEmpty() && ahead.contains(sequence))) {
            return Result.DUPLICATE;
        }
        if (sequence - contiguous > window) {
            // The hole is larger than anything the sender's bounded history can
            // retransmit (or a peer reconnected after a long absence). Adopt the
            // newest value as the baseline instead of stalling forever: the
            // caller logs the gap and the next full chunk load repairs the state.
            contiguous = sequence;
            ahead.clear();
            return Result.GAP;
        }
        ahead.add(sequence);
        if (sequence == contiguous + 1L) {
            advance();
            return Result.ACCEPTED;
        }
        return Result.GAP;
    }

    private void advance() {
        while (ahead.remove(contiguous + 1L)) {
            contiguous++;
        }
    }

    /**
     * Forgets all ordering state, used when the chunk is dropped on shutdown.
     */
    public synchronized void reset() {
        epoch = 0L;
        nextOutgoing = 1L;
        contiguous = 0L;
        ahead.clear();
    }

    /** @return current ownership epoch known to this tracker */
    public synchronized long epoch() {
        return epoch;
    }

    /** @return highest contiguous sequence received in {@link #epoch} */
    public synchronized long contiguous() {
        return contiguous;
    }

    /** @return number of received values above the contiguous one */
    public synchronized int pending() {
        return ahead.size();
    }
}
