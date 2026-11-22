package com.multiforge.protocol.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-chunk ordering state for every peer that sends block/block-entity updates.
 *
 * <p>A chunk has one authoritative ticker, but more than one server can change a
 * block in it: a player may interact before the ownership handover completes, and
 * the block change is broadcast by the server that executed it. Sharing one
 * sequence counter between those senders would make their independent numbers look
 * like duplicates and silently drop updates, so every sender gets its own
 * {@link SequenceTracker}.</p>
 *
 * <p>The table is small (one entry per peer that changed the chunk) and lives on
 * the chunk state. Threading: synchronized; the trackers are synchronized as
 * well.</p>
 */
public final class SenderSequenceTable {

    private final int window;
    private final Map<String, SequenceTracker> trackers = new HashMap<>();

    /**
     * @param window receive window per sender, see {@link SequenceTracker}
     */
    public SenderSequenceTable(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least one");
        }
        this.window = window;
    }

    /**
     * Classifies a received update from one sender.
     *
     * @param sender   server name that sent the update; {@code null} is treated as
     *                 the empty name (a packet before the hello, which should not
     *                 carry world state anyway)
     * @param epoch    ownership epoch stamped into the packet
     * @param sequence sequence number stamped into the packet
     * @return ordering decision
     */
    public synchronized SequenceTracker.Result observe(String sender, long epoch, long sequence) {
        String name = sender == null ? "" : sender;
        return trackers.computeIfAbsent(name, ignored -> new SequenceTracker(window))
                .observe(epoch, sequence);
    }

    /**
     * @param sender server name
     * @return highest contiguous sequence applied from that sender, {@code 0} when
     *         it never sent an ordered update
     */
    public synchronized long contiguous(String sender) {
        SequenceTracker tracker = trackers.get(sender == null ? "" : sender);
        return tracker == null ? 0L : tracker.contiguous();
    }

    /** Forgets every sender, for example when the chunk state is dropped. */
    public synchronized void clear() {
        trackers.clear();
    }

    /** @return number of senders with ordering state */
    public synchronized int senderCount() {
        return trackers.size();
    }
}
