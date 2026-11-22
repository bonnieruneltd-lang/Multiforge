package com.multiforge.forge.peer;

import com.multiforge.forge.peer.packet.BlockChangePacket;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug-only loss injector for the sequenced block/block-entity stream.
 *
 * <p>The acknowledgement/retransmit logic is covered by unit and property tests, but
 * those tests drive the classes directly. This injector makes the same path
 * observable on a live cluster: an operator (or the compatibility harness) sets a
 * drop percentage with {@code /multiforge drop <percent>}, and a fraction of the
 * <em>first</em> transmissions of {@link BlockChangePacket} and
 * {@link BlockEntityUpdatePacket} is silently discarded per peer.</p>
 *
 * <p>Only these two packet types are affected on purpose. They are recorded in the
 * per-peer {@code RetransmitBuffer} before the broadcast, so the receiver detects a
 * sequence gap, acknowledges the highest contiguous sequence, and the sender
 * retransmits the missing entries. Retransmissions themselves are not filtered, so
 * the stream always converges; dropping handshake, chunk or player packets would
 * test unrelated recovery paths and could make a run flaky for the wrong reason.</p>
 *
 * <p>Threading: the percentage is an atomic integer read on the sending thread; no
 * locks. Default 0 means no drops, so production behaviour is unchanged unless an
 * operator opts in.</p>
 */
public final class PacketLossInjector {

    private static final AtomicInteger PERCENT = new AtomicInteger();

    private PacketLossInjector() {
    }

    /**
     * Sets the drop percentage used for new block/block-entity transmissions.
     *
     * @param percent 0-100, clamped
     */
    public static void setPercent(int percent) {
        PERCENT.set(Math.max(0, Math.min(100, percent)));
    }

    /** @return current drop percentage (0 when disabled) */
    public static int percent() {
        return PERCENT.get();
    }

    /**
     * Decides whether one transmission of a packet is discarded for one peer.
     *
     * <p>Called once per peer inside the broadcast loop, so two peers can see
     * different losses. Non-sequenced packets are never dropped.</p>
     *
     * <p>The first sequenced update of a stream is never dropped. A receiver that has
     * not seen a sender before adopts the first sequence it receives as its baseline
     * (the mid-stream rule in {@code SequenceTracker}), so dropping sequence 1 would
     * make the receiver silently skip it instead of asking for a retransmit. That is
     * a documented limit of the baseline rule, not something this debug injector
     * should trip over; the harness proves gap repair from sequence 2 onwards.</p>
     *
     * @param packet packet about to be sent
     * @return {@code true} when this transmission should be skipped
     */
    public static boolean shouldDrop(Object packet) {
        int percent = PERCENT.get();
        if (percent == 0) {
            return false;
        }
        long sequence;
        if (packet instanceof BlockChangePacket blockChange) {
            sequence = blockChange.sequence;
        } else if (packet instanceof BlockEntityUpdatePacket blockEntity) {
            sequence = blockEntity.sequence;
        } else {
            return false;
        }
        if (sequence <= 1L) {
            return false;
        }
        return ThreadLocalRandom.current().nextInt(100) < percent;
    }
}
