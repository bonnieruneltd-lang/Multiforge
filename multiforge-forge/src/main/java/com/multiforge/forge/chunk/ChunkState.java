package com.multiforge.forge.chunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable cluster view of a single chunk.
 *
 * <p>One instance exists per chunk this server knows about, whether it is loaded
 * locally or only observed through master notifications. Fields are mutated on the
 * server thread (ownership, subscriptions) and read from the API/commands, so the
 * mutable collections are concurrent and the scalar fields are volatile.</p>
 */
public final class ChunkState {

    /** Name of the server that ticks the chunk, {@code null} when unowned. */
    private volatile String owner;

    /**
     * Derived fast-path flag: {@code true} when {@link #owner} is this server.
     *
     * <p>{@code shouldTick} runs once per chunk per tick and once per entity per
     * tick, so it reads this single flag instead of comparing owner strings or
     * probing a second concurrent set.</p>
     */
    private volatile boolean localOwner;

    /** Whether the chunk is currently loaded on this server. */
    private volatile boolean loaded;

    /** Whether this server subscribed to block changes of the chunk. */
    private volatile boolean subscribed;

    /** Whether this server subscribed to entity changes of the chunk. */
    private volatile boolean entitySubscribed;

    /** Last status reported by any server (loaded, unloading, unloaded). */
    private volatile String remoteStatus = "unknown";

    /**
     * Master ownership epoch of the chunk.
     *
     * <p>The master increments it on every owner change and announces it with
     * {@code SetChunkOwnerMessage}/{@code ChunkSubscribersSyncMessage}. Outgoing
     * block and block entity packets are stamped with it. A value of {@code 0}
     * means "no epoch information" (older master or a chunk that was never
     * owned); such packets are always applied.</p>
     */
    private volatile long ownershipEpoch;

    /**
     * Highest epoch observed on an applied peer packet.
     *
     * <p>A peer packet can arrive before the master's ownership announcement
     * because the two planes are independent. Keeping the maximum of both
     * values means a late packet from the previous owner is still dropped.
     * Guarded by {@link #observeEpoch(long)}; only the server thread mutates
     * it.</p>
     */
    private volatile long lastAppliedEpoch;

    /** Server names that have the chunk loaded. */
    private final Set<String> subscribers = ConcurrentHashMap.newKeySet();

    /** Server names that have the chunk's entities loaded. */
    private final Set<String> entitySubscribers = ConcurrentHashMap.newKeySet();

    /**
     * Per-chunk block/block-entity ordering and outgoing sequence counter.
     *
     * <p>Ownership epochs reject updates from an older owner; the sequence
     * numbers reject duplicate or late updates from the current owner and let a
     * receiver report a gap so the sender can retransmit it. Incoming numbers are
     * tracked per sender because more than one server can change a block in the
     * same chunk while ownership settles. Counters reset when the ownership epoch
     * changes.</p>
     */
    private final com.multiforge.protocol.util.SequenceTracker outboundSequences =
            new com.multiforge.protocol.util.SequenceTracker(256);

    /** Ordering state of every peer that sent updates for this chunk. */
    private final com.multiforge.protocol.util.SenderSequenceTable inboundSequences =
            new com.multiforge.protocol.util.SenderSequenceTable(256);

    /** Maximum number of sent updates kept per chunk for gap retransmission. */
    public static final int RETRANSMIT_HISTORY = 128;

    /**
     * Sent updates of this chunk that one peer may still ask for, keyed by peer
     * name.
     *
     * <p>The history is per peer: an acknowledgement from one server must not
     * discard entries another server still needs to fill its gap, and the entry
     * for a peer survives a reconnect with the same cluster name.</p>
     */
    private final Map<String, com.multiforge.protocol.util.RetransmitBuffer<com.multiforge.forge.peer.PeerPacket>> retransmit =
            new ConcurrentHashMap<>();

    /** @return owning server name, or {@code null} */
    public String owner() {
        return owner;
    }

    /**
     * @param owner new owner, {@code null} when the chunk became unowned
     */
    public void owner(String owner) {
        this.owner = owner;
    }

    /** @return whether this server is the current owner */
    public boolean localOwner() {
        return localOwner;
    }

    /** @param localOwner whether this server is the current owner */
    public void localOwner(boolean localOwner) {
        this.localOwner = localOwner;
    }

    /** @return whether the chunk is loaded here */
    public boolean loaded() {
        return loaded;
    }

    /** @param loaded new local load state */
    public void loaded(boolean loaded) {
        this.loaded = loaded;
    }

    /** @return whether block changes are subscribed */
    public boolean subscribed() {
        return subscribed;
    }

    /** @param subscribed new subscription state */
    public void subscribed(boolean subscribed) {
        this.subscribed = subscribed;
    }

    /** @return whether entity changes are subscribed */
    public boolean entitySubscribed() {
        return entitySubscribed;
    }

    /** @param entitySubscribed new entity subscription state */
    public void entitySubscribed(boolean entitySubscribed) {
        this.entitySubscribed = entitySubscribed;
    }

    /** @return last reported remote status */
    public String remoteStatus() {
        return remoteStatus;
    }

    /** @param remoteStatus new remote status */
    public void remoteStatus(String remoteStatus) {
        this.remoteStatus = remoteStatus;
    }

    /** @return master ownership epoch, {@code 0} when unknown */
    public long ownershipEpoch() {
        return ownershipEpoch;
    }

    /**
     * Records a newer ownership epoch announced by the master.
     *
     * @param epoch epoch from the master; smaller values are ignored because an
     *              announcement can be delivered out of order on reconnect
     */
    public void ownershipEpoch(long epoch) {
        if (epoch > ownershipEpoch) {
            ownershipEpoch = epoch;
        }
    }

    /**
     * Decides whether a peer update stamped with {@code epoch} may be applied.
     *
     * <p>Rejects anything older than the newest epoch known from the master or
     * from an already applied peer packet. Accepting a newer epoch also records
     * it, so a delayed packet from the previous owner is dropped even when the
     * master's announcement has not arrived yet.</p>
     *
     * @param epoch epoch stamped into the peer packet
     * @return {@code true} when the update is current and may be applied
     */
    public boolean observeEpoch(long epoch) {
        if (epoch <= 0L) {
            // No epoch information (older peer or a change made before the
            // master announced ownership). Such packets are always applied;
            // they cannot be ordered against a positive epoch.
            return true;
        }
        long known = Math.max(ownershipEpoch, lastAppliedEpoch);
        if (epoch < known) {
            return false;
        }
        if (epoch > lastAppliedEpoch) {
            lastAppliedEpoch = epoch;
        }
        return true;
    }

    /** @return highest epoch applied from a peer packet */
    public long lastAppliedEpoch() {
        return lastAppliedEpoch;
    }

    /**
     * Returns the next sequence number for an update this server broadcasts.
     *
     * <p>The counter restarts at one when the ownership epoch changes, so a new
     * owner is never mistaken for a late sender of the previous owner's
     * numbering.</p>
     *
     * @param epoch ownership epoch the update is produced under
     * @return sequence number to stamp into the packet
     */
    public long nextSequence(long epoch) {
        return outboundSequences.nextOutgoing(epoch);
    }

    /**
     * Classifies a received block or block entity update from one sender.
     *
     * @param sender   server that sent the update
     * @param epoch    ownership epoch stamped into the packet
     * @param sequence sequence number stamped into the packet; {@code 0} means
     *                 the sender had no sequence information
     * @return ordering decision, see {@link com.multiforge.protocol.util.SequenceTracker.Result}
     */
    public com.multiforge.protocol.util.SequenceTracker.Result observeSequence(
            String sender, long epoch, long sequence) {
        return inboundSequences.observe(sender, epoch, sequence);
    }

    /**
     * @param sender server that sent the updates
     * @return highest contiguous sequence received from that sender for the
     *         current epoch
     */
    public long contiguousSequence(String sender) {
        return inboundSequences.contiguous(sender);
    }

    /**
     * Bounded history of sent block/block-entity updates of this chunk for one
     * peer.
     *
     * <p>Lives on the chunk state instead of a separate map so the broadcast path
     * does not need another concurrent lookup. Entries are dropped when that peer
     * acknowledges them; the buffer is cleared when the state is dropped.</p>
     *
     * @param peer peer name
     * @return retransmit history for this chunk and peer
     */
    public com.multiforge.protocol.util.RetransmitBuffer<com.multiforge.forge.peer.PeerPacket> retransmitBuffer(String peer) {
        return retransmit.computeIfAbsent(peer,
                ignored -> new com.multiforge.protocol.util.RetransmitBuffer<>(RETRANSMIT_HISTORY));
    }

    /** @return live set of servers that have the chunk loaded */
    public Set<String> subscribers() {
        return subscribers;
    }

    /** @return live set of servers that have the chunk's entities loaded */
    public Set<String> entitySubscribers() {
        return entitySubscribers;
    }

    /** @return whether this state carries no information worth keeping */
    public boolean isIdle() {
        return !loaded && !subscribed && !entitySubscribed && owner == null
                && subscribers.isEmpty() && entitySubscribers.isEmpty();
    }
}
