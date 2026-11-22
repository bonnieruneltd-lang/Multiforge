package com.multiforge.forge.chunk;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.WorldDirectories;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import com.multiforge.forge.peer.packet.BlockChangePacket;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;
import com.multiforge.forge.peer.packet.ChunkSequenceAckPacket;
import com.multiforge.forge.peer.packet.EntityChunkChangedPacket;
import com.multiforge.forge.peer.packet.SendChunkPacket;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.util.BoundedTaskExecutor;
import com.multiforge.protocol.util.RetransmitBuffer;
import com.multiforge.protocol.util.SequenceTracker;
import com.multiforge.protocol.messages.masterbound.ChunkChangedStatusMessage;
import com.multiforge.protocol.messages.masterbound.LockChunkMessage;
import com.multiforge.protocol.messages.masterbound.RequestChunkOwnershipMessage;
import com.multiforge.protocol.messages.masterbound.SubscribeChunkMessage;
import com.multiforge.protocol.messages.masterbound.SubscribeEntitiesMessage;
import com.multiforge.protocol.messages.masterbound.UnlockChunkMessage;
import com.multiforge.protocol.messages.masterbound.UnsubscribeChunkMessage;
import com.multiforge.protocol.messages.masterbound.UnsubscribeEntitiesMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk ownership, subscription and update coordination.
 *
 * <p>Ports the server half of MultiPaper's chunk system
 * ({@code MultiPaperChunkHandler} plus the lock/subscription state that lived in
 * the master and the Paper patches):</p>
 * <ul>
 *   <li>A loaded chunk is <b>subscribed</b> at the master so every block and block
 *       entity change made anywhere is forwarded to all servers that have it.</li>
 *   <li>Only the <b>owner</b> ticks a chunk (random ticks, block ticks, entities).
 *       Ownership is requested lazily the first time a loaded chunk would tick and
 *       is released when the chunk unloads; if the owner disconnects the master
 *       hands ownership to the next requesting server, so nothing is lost.</li>
 *   <li>Peers can fetch the newest copy of a chunk directly from memory instead of
 *       waiting for the next save to the master.</li>
 * </ul>
 *
 * <p>Threading: except for {@link #applyRemoteBlockChange} and
 * {@link #respondToChunkRequest}, which are called from Netty threads and hop to
 * the server thread, every method must run on the server thread. Cross-thread
 * state is held in concurrent collections and volatile fields.</p>
 */
public final class ChunkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Chunks");

    private final MultiForgeServer server;
    private final Map<ChunkKey, ChunkState> states = new ConcurrentHashMap<>();
    private final Set<ChunkKey> ownedLocally = ConcurrentHashMap.newKeySet();
    private final Map<ChunkKey, Integer> unsubscribeAtTick = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Integer> lastOwnershipRequest = new ConcurrentHashMap<>();
    /**
     * Cache of the logical world name per dimension.
     *
     * <p>Keyed by {@link ResourceKey} instead of the rendered dimension string:
     * {@code dimension().location().toString()} allocated a fresh {@code String}
     * on every call, and this method is on the per-chunk-tick path. The key is the
     * level's own immutable {@code ResourceKey} instance, so lookups allocate
     * nothing.</p>
     */
    private final Map<ResourceKey<Level>, String> worldNames = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<BlockEntity> pendingBlockEntities = ConcurrentHashMap.newKeySet();
    /**
     * Number of known chunks with at least one entity subscriber.
     *
     * <p>Maintained on the server thread together with the subscription sets. It
     * gives the per-entity-tick forwarding path an O(1) answer to "would anybody
     * receive this?" without building a chunk key first. Mutations are tiny and
     * infrequent, so an atomic counter is cheaper than scanning the state map.</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger chunksWithEntitySubscribers =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Maximum number of sent updates kept per chunk for gap retransmission. */
    private static final int OUTBOX_CAPACITY = 128;

    /** Gap detections on the received block/block-entity stream, for `/multiforge status`. */
    private final java.util.concurrent.atomic.AtomicLong sequenceGaps =
            new java.util.concurrent.atomic.AtomicLong();

    /** Duplicate or late peer updates dropped because of their sequence number. */
    private final java.util.concurrent.atomic.AtomicLong sequenceDuplicates =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Received updates waiting to be acknowledged, at most one per chunk.
     *
     * <p>Acknowledging once per chunk per tick keeps the cost proportional to the
     * number of changed chunks instead of the number of changed blocks.</p>
     */
    private final Map<ChunkKey, PendingAck> pendingAcks = new ConcurrentHashMap<>();

    /**
     * Bounded history of sent block/block-entity updates per chunk.
     *
     * <p>Only entries above the peer's highest contiguous acknowledgement are
     * retransmitted, so a healthy connection normally keeps nothing.</p>
     */
    private final Map<ChunkKey, RetransmitBuffer<PeerPacket>> outbox = new ConcurrentHashMap<>();

    /**
     * Compression pool for peer chunk replies.
     *
     * <p>Serializing the chunk must run on the server thread, but gzip only needs
     * the immutable snapshot. When the pool is saturated the caller compresses
     * inline, so a reply is never dropped (see
     * {@link BoundedTaskExecutor#execute(Runnable)}).</p>
     */
    private final BoundedTaskExecutor compression =
            new BoundedTaskExecutor(2, 64, "MultiForge-ChunkCompress");

    /** Direct-mapped cache for the per-entity-tick chunk key (server thread only). */
    private final ChunkKeyCache keyCache = new ChunkKeyCache(512);

    /**
     * @param server owning server
     */
    public ChunkManager(MultiForgeServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Load / unload lifecycle
    // ------------------------------------------------------------------

    /**
     * Registers a newly loaded chunk and subscribes it at the master.
     *
     * @param level level the chunk belongs to
     * @param chunk loaded chunk
     */
    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        ChunkKey key = keyOf(level, chunk.getPos());
        ChunkState state = state(key);
        state.loaded(true);
        unsubscribeAtTick.remove(key);
        if (server.master().isConnected()) {
            subscribe(key, state);
            server.master().send(new ChunkChangedStatusMessage(key.getName(), key.getX(), key.getZ(), "loaded"));
        }
        if (server.config().debugLogging()) {
            LOGGER.info("Chunk {} loaded locally", key);
        }
    }

    /**
     * Marks a chunk as unloaded and schedules the unsubscribe after the configured
     * grace period. The grace period avoids unsubscribe/subscribe churn when a
     * player crosses a chunk border back and forth.
     *
     * @param level level the chunk belonged to
     * @param pos   chunk position
     */
    public void onChunkUnload(ServerLevel level, ChunkPos pos) {
        ChunkKey key = keyOf(level, pos);
        ChunkState state = states.get(key);
        if (state == null) {
            return;
        }
        state.loaded(false);
        lastOwnershipRequest.remove(key);
        if (ownedLocally.remove(key)) {
            server.master().send(new UnlockChunkMessage(key.getName(), key.getX(), key.getZ()));
            state.owner(null);
            state.localOwner(false);
            LOGGER.debug("Released ownership of {} on unload", key);
        }
        int delay = server.config().chunkUnloadGraceTicks();
        if (delay <= 0) {
            unsubscribe(key, state);
        } else {
            unsubscribeAtTick.put(key, server.server().getTickCount() + delay);
        }
        server.master().send(new ChunkChangedStatusMessage(key.getName(), key.getX(), key.getZ(), "unloaded"));
    }

    private void subscribe(ChunkKey key, ChunkState state) {
        if (!state.subscribed()) {
            server.master().send(new SubscribeChunkMessage(key.getName(), key.getX(), key.getZ()));
            state.subscribed(true);
        }
        if (server.config().entitySync() && !state.entitySubscribed()) {
            server.master().send(new SubscribeEntitiesMessage(key.getName(), key.getX(), key.getZ()));
            state.entitySubscribed(true);
        }
    }

    private void unsubscribe(ChunkKey key, ChunkState state) {
        if (state.loaded()) {
            return;
        }
        if (state.subscribed()) {
            server.master().send(new UnsubscribeChunkMessage(key.getName(), key.getX(), key.getZ()));
            state.subscribed(false);
        }
        if (state.entitySubscribed()) {
            server.master().send(new UnsubscribeEntitiesMessage(key.getName(), key.getX(), key.getZ()));
            state.entitySubscribed(false);
        }
        if (state.isIdle()) {
            states.remove(key, state);
            outbox.remove(key);
            pendingAcks.remove(key);
        }
    }

    /**
     * Called once per server tick. Applies deferred unsubscribes and forgets idle
     * chunk states.
     */
    public void tick() {
        flushAcks();
        drainBlockEntityUpdates();
        if (unsubscribeAtTick.isEmpty()) {
            return;
        }
        int now = server.server().getTickCount();
        for (Map.Entry<ChunkKey, Integer> entry : unsubscribeAtTick.entrySet()) {
            if (entry.getValue() > now) {
                continue;
            }
            ChunkKey key = entry.getKey();
            unsubscribeAtTick.remove(key, entry.getValue());
            ChunkState state = states.get(key);
            if (state != null) {
                unsubscribe(key, state);
            }
        }
    }

    /**
     * Re-subscribes every locally loaded chunk. Called after the master connection
     * was re-established, because the master forgot all subscriptions when the
     * connection dropped.
     */
    public void onMasterReconnected() {
        for (Map.Entry<ChunkKey, ChunkState> entry : states.entrySet()) {
            ChunkState state = entry.getValue();
            if (!state.loaded()) {
                continue;
            }
            state.subscribed(false);
            state.entitySubscribed(false);
            subscribe(entry.getKey(), state);
            server.master().send(new ChunkChangedStatusMessage(
                    entry.getKey().getName(), entry.getKey().getX(), entry.getKey().getZ(), "loaded"));
        }
    }

    // ------------------------------------------------------------------
    // Ownership / ticking
    // ------------------------------------------------------------------

    /**
     * Decides whether this server may tick the chunk, requesting ownership when
     * nobody owns it yet.
     *
     * <p>Called from the {@code ServerLevel.tickChunk} mixin, on the server thread,
     * once per chunk per tick.</p>
     *
     * @param level level being ticked
     * @param chunk chunk that would tick
     * @return {@code true} when this server owns the chunk
     */
    public boolean shouldTick(ServerLevel level, LevelChunk chunk) {
        return shouldTick(level, chunk.getPos().x, chunk.getPos().z);
    }

    /**
     * Entity overload of {@link #shouldTick(ServerLevel, LevelChunk)}.
     *
     * <p>Called from {@code ServerLevel.tickNonPassenger} for every non-player
     * entity of every tick. The chunk coordinates are derived from the entity
     * position directly, which avoids the {@link ChunkPos} allocation of
     * {@code entity.chunkPosition()} and the chunk-map lookup the mixin used to
     * perform. An entity that is ticking is by definition in a loaded chunk.</p>
     *
     * @param level  level being ticked
     * @param entity entity that would tick
     * @return {@code true} when this server owns the entity's chunk
     */
    public boolean shouldTick(ServerLevel level, Entity entity) {
        return shouldTick(level, Mth.floor(entity.getX()) >> 4, Mth.floor(entity.getZ()) >> 4);
    }

    /**
     * Shared hot path of the two overloads above.
     *
     * <p>A single map lookup answers the common cases: owned locally, owned by a
     * peer, or unknown. An unknown chunk is not materialized in {@link #states}
     * here; the state is created when the master answers the ownership request.
     * Only when the chunk is unowned does an ownership request go out.</p>
     *
     * @param level  level of the chunk
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return {@code true} when this server owns the chunk
     */
    private boolean shouldTick(ServerLevel level, int chunkX, int chunkZ) {
        ChunkKey key = keyCache.get(level, worldName(level), chunkX, chunkZ);
        ChunkState state = states.get(key);
        if (state != null) {
            if (state.localOwner()) {
                return true;
            }
            if (state.owner() != null) {
                return false;
            }
        }
        requestOwnership(key);
        return false;
    }

    /**
     * Sends an ownership request unless one was sent recently.
     *
     * @param key chunk to claim
     */
    public void requestOwnership(ChunkKey key) {
        int now = server.server().getTickCount();
        Integer last = lastOwnershipRequest.get(key);
        if (last != null && now - last < server.config().chunkOwnershipRequestIntervalTicks()) {
            return;
        }
        lastOwnershipRequest.put(key, now);
        if (server.config().debugLogging()) {
            LOGGER.info("Requesting ownership of {}", key);
        }
        // MultiPaper grants ownership with a lock request (first come, first
        // served); the master answers with SetChunkOwnerMessage which flips the
        // local flag through onOwnerChanged.
        server.master().send(new LockChunkMessage(key.getName(), key.getX(), key.getZ()));
    }

    /**
     * Forcefully takes ownership of chunks that another server holds. MultiPaper
     * does this before running redstone that reaches into a foreign chunk; the
     * master moves the requester to the front of the lock queue.
     *
     * @param keys chunks to take over
     */
    public void forceTakeOwnership(java.util.Collection<ChunkKey> keys) {
        if (keys.isEmpty()) {
            return;
        }
        ChunkKey first = keys.iterator().next();
        server.master().send(new RequestChunkOwnershipMessage(first.getName(), keys.toArray(new ChunkKey[0])));
    }

    /**
     * Applies an ownership change announced by the master.
     *
     * @param key   chunk
     * @param owner new owner, empty string when it became unowned
     */
    public void onOwnerChanged(ChunkKey key, String owner) {
        onOwnerChanged(key, owner, 0L);
    }

    /**
     * Applies an ownership change and the epoch it belongs to.
     *
     * <p>The epoch is stored on the {@link ChunkState} even when the owner is
     * unchanged, because a reconnect can replay the announcement and a state
     * may have been recreated from scratch. Peer block/block-entity packets
     * older than this value are dropped in {@link #applyRemoteBlockChange} and
     * {@link #applyRemoteBlockEntity}.</p>
     *
     * @param key   chunk
     * @param owner new owner, empty string when it became unowned
     * @param epoch master ownership epoch, {@code 0} when unknown
     */
    public void onOwnerChanged(ChunkKey key, String owner, long epoch) {
        ChunkState state = state(key);
        state.ownershipEpoch(epoch);
        String previous = state.owner();
        String normalized = owner == null || owner.isEmpty() ? null : owner;
        if (java.util.Objects.equals(previous, normalized)) {
            // The master announces the owner to the requester and to subscribers,
            // so the same value can arrive twice.
            return;
        }
        state.owner(normalized);
        boolean local = server.serverName().equals(normalized);
        state.localOwner(local);
        if (local) {
            ownedLocally.add(key);
            // The request bookkeeping is only needed while a request is pending.
            lastOwnershipRequest.remove(key);
            LOGGER.debug("Acquired ownership of {} (epoch {}, was {})", key, epoch, previous);
            // Re-send entity snapshots so this server's copy becomes authoritative
            // on every mirror (ownership-boundary resync).
            server.entities().onChunkOwnershipGained(key);
        } else {
            ownedLocally.remove(key);
            if (previous != null && previous.equals(server.serverName())) {
                LOGGER.debug("Lost ownership of {} to {} (epoch {})", key, normalized, epoch);
            }
        }
    }

    /**
     * @param key chunk
     * @return {@code true} when this server currently owns the chunk
     */
    public boolean isLocal(ChunkKey key) {
        return ownedLocally.contains(key);
    }

    /**
     * @param key chunk
     * @return owner name, or {@code null} when the chunk is unowned or unknown
     */
    public String ownerName(ChunkKey key) {
        ChunkState state = states.get(key);
        return state == null ? null : state.owner();
    }

    /**
     * @param key chunk
     * @return owner name, empty when unowned or unknown
     */
    public Optional<String> ownerOf(ChunkKey key) {
        return Optional.ofNullable(ownerName(key));
    }

    /**
     * @param key chunk
     * @return subscribed server names, including this server when loaded
     */
    public Collection<String> subscribersOf(ChunkKey key) {
        ChunkState state = states.get(key);
        if (state == null || state.subscribers().isEmpty()) {
            return List.of();
        }
        return List.copyOf(state.subscribers());
    }

    /**
     * @param key chunk
     * @return servers that currently have the chunk's entities loaded, excluding
     *         this server (the master never reports the local subscriber)
     */
    public Collection<String> entitySubscribersOf(ChunkKey key) {
        ChunkState state = states.get(key);
        if (state == null || state.entitySubscribers().isEmpty()) {
            return List.of();
        }
        return List.copyOf(state.entitySubscribers());
    }

    // ------------------------------------------------------------------
    // Master notifications
    // ------------------------------------------------------------------

    /**
     * Applies a full subscription snapshot for a chunk.
     *
     * @param key         chunk
     * @param owner       owner name (may be empty)
     * @param subscribers servers with the chunk loaded
     */
    public void onSubscribersSync(ChunkKey key, String owner, String[] subscribers) {
        onSubscribersSync(key, owner, subscribers, 0L);
    }

    /**
     * Applies a full subscription snapshot, including the master's ownership
     * epoch, for a chunk.
     *
     * @param key         chunk
     * @param owner       owner name (may be empty)
     * @param subscribers servers with the chunk loaded
     * @param epoch       master ownership epoch, {@code 0} when unknown
     */
    public void onSubscribersSync(ChunkKey key, String owner, String[] subscribers, long epoch) {
        ChunkState state = state(key);
        state.subscribers().clear();
        for (String subscriber : subscribers) {
            state.subscribers().add(subscriber);
        }
        onOwnerChanged(key, owner, epoch);
    }

    /**
     * @param key    chunk
     * @param serverName server that loaded the chunk
     */
    public void onSubscriberAdded(ChunkKey key, String serverName) {
        state(key).subscribers().add(serverName);
    }

    /**
     * @param key    chunk
     * @param serverName server that unloaded the chunk
     */
    public void onSubscriberRemoved(ChunkKey key, String serverName) {
        ChunkState state = states.get(key);
        if (state != null) {
            state.subscribers().remove(serverName);
        }
    }

    /**
     * @param key         chunk
     * @param subscribers servers with the chunk's entities loaded
     */
    public void onEntitySubscribersSync(ChunkKey key, String[] subscribers) {
        ChunkState state = state(key);
        Set<String> current = state.entitySubscribers();
        boolean wasEmpty = current.isEmpty();
        current.clear();
        for (String subscriber : subscribers) {
            current.add(subscriber);
        }
        if (wasEmpty && !current.isEmpty()) {
            chunksWithEntitySubscribers.incrementAndGet();
        } else if (!wasEmpty && current.isEmpty()) {
            chunksWithEntitySubscribers.decrementAndGet();
        }
        for (String subscriber : subscribers) {
            server.entities().onEntitySubscriberAdded(key, subscriber);
        }
    }

    /**
     * @param key    chunk
     * @param serverName server that loaded the entities
     */
    public void onEntitySubscriberAdded(ChunkKey key, String serverName) {
        Set<String> subscribers = state(key).entitySubscribers();
        if (subscribers.add(serverName) && subscribers.size() == 1) {
            chunksWithEntitySubscribers.incrementAndGet();
        }
        server.entities().onEntitySubscriberAdded(key, serverName);
    }

    /**
     * @param key    chunk
     * @param serverName server that unloaded the entities
     */
    public void onEntitySubscriberRemoved(ChunkKey key, String serverName) {
        ChunkState state = states.get(key);
        if (state != null) {
            Set<String> subscribers = state.entitySubscribers();
            if (subscribers.remove(serverName) && subscribers.isEmpty()) {
                chunksWithEntitySubscribers.decrementAndGet();
            }
        }
    }

    /**
     * @return {@code true} when at least one chunk has a remote entity subscriber,
     *         i.e. entity updates produced here have a destination
     */
    public boolean hasEntitySubscribers() {
        return chunksWithEntitySubscribers.get() > 0;
    }

    /**
     * Tracks the status another server reported for a chunk. Purely informational:
     * it lets debug tooling explain why a chunk was not requested from a peer.
     *
     * @param world  logical world name
     * @param x      chunk X
     * @param z      chunk Z
     * @param status reported status
     * @param server server that reported it
     */
    public void onRemoteChunkStatus(String world, int x, int z, String status, String server) {
        state(new ChunkKey(world, x, z)).remoteStatus(server + ":" + status);
    }

    // ------------------------------------------------------------------
    // Block / block entity updates
    // ------------------------------------------------------------------

    /**
     * Broadcasts a block change made on this server to all peers.
     *
     * @param level level of the block
     * @param pos   block position
     * @param state new block state
     */
    public void broadcastBlockChange(ServerLevel level, BlockPos pos, BlockState state) {
        if (Boolean.TRUE.equals(applyingRemote.get())) {
            return;
        }
        // Without a live peer there is nobody to tell. The chunk data plane is the
        // fallback: peers read the newest copy when they load the chunk. Building
        // the packet allocates a world string and a packet object per changed
        // block, which matters for explosions and large redstone updates.
        if (!server.peers().hasConnections()) {
            return;
        }
        // Peer connections are independent of the master: a master outage must not
        // stop block propagation between servers that still have a peer link.
        String world = worldName(level);
        ChunkKey key = keyCache.get(level, world, pos.getX() >> 4, pos.getZ() >> 4);
        ChunkState chunkState = state(key);
        long epoch = chunkState.ownershipEpoch();
        long sequence = chunkState.nextSequence(epoch);
        BlockChangePacket packet = new BlockChangePacket(
                key.getName(), pos.getX(), pos.getY(), pos.getZ(), Block.getId(state), epoch, sequence);
        recordForRetransmit(key, epoch, sequence, packet);
        server.peers().broadcast(packet);
    }

    /**
     * Queues a block entity whose NBT changed. Batching happens because a chest
     * can change dozens of times per tick; only one update per block entity per
     * tick is sent.
     *
     * @param blockEntity changed block entity
     */
    public void queueBlockEntity(BlockEntity blockEntity) {
        if (Boolean.TRUE.equals(applyingRemote.get())) {
            return;
        }
        if (server.containers().suppressesBroadcast(blockEntity)) {
            // A forwarded container click is in progress. The owner applies the
            // same slot changes and broadcasts the authoritative NBT; sending a
            // second copy from here would race with it.
            return;
        }
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide()) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getChunkSource().getChunkNow(blockEntity.getBlockPos().getX() >> 4,
                blockEntity.getBlockPos().getZ() >> 4) == null) {
            return;
        }
        pendingBlockEntities.add(blockEntity);
    }

    /** Sends all queued block entity updates. Called once per tick. */
    private void drainBlockEntityUpdates() {
        if (pendingBlockEntities.isEmpty()) {
            return;
        }
        if (!server.peers().hasConnections()) {
            // Serializing NBT for a packet that has no receiver is wasted work; the
            // master's stored copy is what peers read when they load the chunk.
            pendingBlockEntities.clear();
            return;
        }
        for (BlockEntity blockEntity : pendingBlockEntities) {
            broadcastBlockEntity(blockEntity);
        }
        pendingBlockEntities.clear();
    }

    /**
     * Broadcasts a block entity's NBT to all peers.
     *
     * @param blockEntity changed block entity
     */
    public void broadcastBlockEntity(BlockEntity blockEntity) {
        if (Boolean.TRUE.equals(applyingRemote.get())) {
            return;
        }
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide()) {
            return;
        }
        // As with block changes, peer traffic does not depend on the master link.
        BlockPos pos = blockEntity.getBlockPos();
        ServerLevel level = (ServerLevel) blockEntity.getLevel();
        ChunkKey key = keyCache.get(level, worldName(level), pos.getX() >> 4, pos.getZ() >> 4);
        ChunkState chunkState = state(key);
        long epoch = chunkState.ownershipEpoch();
        long sequence = chunkState.nextSequence(epoch);
        BlockEntityUpdatePacket packet = new BlockEntityUpdatePacket(
                key.getName(), pos.getX(), pos.getY(), pos.getZ(),
                blockEntity.saveWithFullMetadata(), epoch, sequence);
        recordForRetransmit(key, epoch, sequence, packet);
        server.peers().broadcast(packet);
    }

    /**
     * Applies a block change received from a peer.
     *
     * <p>Runs on a Netty thread and hops to the server thread. Chunks that are not
     * loaded locally are ignored: they will be read from the cluster when they are
     * loaded.</p>
     *
     * @param packet     received change
     * @param connection peer that sent the change, used for the acknowledgement
     */
    public void applyRemoteBlockChange(BlockChangePacket packet, PeerConnection connection) {
        server.execute(() -> {
            ChunkKey key = new ChunkKey(packet.world, packet.x >> 4, packet.z >> 4);
            ChunkState chunkState = state(key);
            if (!chunkState.observeEpoch(packet.epoch)) {
                LOGGER.debug("Dropped stale block change for {} at {},{} (epoch {} < {})",
                        key, packet.x, packet.z, packet.epoch,
                        Math.max(chunkState.ownershipEpoch(), chunkState.lastAppliedEpoch()));
                return;
            }
            SequenceTracker.Result ordering = chunkState.observeSequence(packet.epoch, packet.sequence);
            if (ordering == SequenceTracker.Result.STALE_EPOCH) {
                LOGGER.debug("Dropped block change for {} from an older epoch ({})", key, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.DUPLICATE) {
                sequenceDuplicates.incrementAndGet();
                LOGGER.debug("Dropped duplicate block change for {} (epoch {}, sequence {})",
                        key, packet.epoch, packet.sequence);
                queueAck(key, connection, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.GAP) {
                sequenceGaps.incrementAndGet();
                LOGGER.warn("Missing block updates for {} before sequence {} (epoch {}); asking {} to retransmit",
                        key, packet.sequence, packet.epoch, connection.remoteName());
            }
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            if (level.getChunkSource().getChunkNow(packet.x >> 4, packet.z >> 4) == null) {
                // The chunk is not loaded here; it will be read from the cluster
                // when it is, so the update does not have to be kept.
                queueAck(key, connection, packet.epoch);
                return;
            }
            BlockState state = Block.stateById(packet.stateId);
            applyingRemote.set(Boolean.TRUE);
            try {
                level.setBlock(new BlockPos(packet.x, packet.y, packet.z), state, Block.UPDATE_ALL);
            } finally {
                applyingRemote.set(Boolean.FALSE);
            }
            queueAck(key, connection, packet.epoch);
        });
    }

    /**
     * Applies a block entity update received from a peer.
     *
     * @param packet     received update
     * @param connection peer that sent the update, used for the acknowledgement
     */
    public void applyRemoteBlockEntity(BlockEntityUpdatePacket packet, PeerConnection connection) {
        server.execute(() -> {
            ChunkKey key = new ChunkKey(packet.world, packet.x >> 4, packet.z >> 4);
            ChunkState chunkState = state(key);
            if (!chunkState.observeEpoch(packet.epoch)) {
                LOGGER.debug("Dropped stale block entity update for {} at {},{} (epoch {} < {})",
                        key, packet.x, packet.z, packet.epoch,
                        Math.max(chunkState.ownershipEpoch(), chunkState.lastAppliedEpoch()));
                return;
            }
            SequenceTracker.Result ordering = chunkState.observeSequence(packet.epoch, packet.sequence);
            if (ordering == SequenceTracker.Result.STALE_EPOCH) {
                LOGGER.debug("Dropped block entity update for {} from an older epoch ({})", key, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.DUPLICATE) {
                sequenceDuplicates.incrementAndGet();
                LOGGER.debug("Dropped duplicate block entity update for {} (epoch {}, sequence {})",
                        key, packet.epoch, packet.sequence);
                queueAck(key, connection, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.GAP) {
                sequenceGaps.incrementAndGet();
                LOGGER.warn("Missing block entity updates for {} before sequence {} (epoch {}); asking {} to retransmit",
                        key, packet.sequence, packet.epoch, connection.remoteName());
            }
            ServerLevel level = server.level(packet.world);
            if (level == null || level.getChunkSource().getChunkNow(packet.x >> 4, packet.z >> 4) == null) {
                queueAck(key, connection, packet.epoch);
                return;
            }
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                queueAck(key, connection, packet.epoch);
                return;
            }
            applyingRemote.set(Boolean.TRUE);
            try {
                blockEntity.load(packet.tag);
                blockEntity.setChanged();
                level.sendBlockUpdated(pos, blockEntity.getBlockState(), blockEntity.getBlockState(), Block.UPDATE_ALL);
            } finally {
                applyingRemote.set(Boolean.FALSE);
            }
            queueAck(key, connection, packet.epoch);
        });
    }

    /**
     * Applies an acknowledgement of the block/block-entity stream of a chunk.
     *
     * <p>Runs on the Netty thread. When the peer reports a contiguous sequence
     * below what this server sent, the missing updates are still in the bounded
     * history and are retransmitted on the same connection.</p>
     *
     * @param packet     acknowledgement
     * @param connection peer that acknowledged
     */
    public void onSequenceAck(ChunkSequenceAckPacket packet, PeerConnection connection) {
        ChunkKey key = new ChunkKey(packet.world, packet.x, packet.z);
        RetransmitBuffer<PeerPacket> buffer = outbox.get(key);
        if (buffer == null) {
            return;
        }
        List<PeerPacket> missing = buffer.acknowledge(packet.epoch, packet.contiguous);
        if (missing.isEmpty()) {
            return;
        }
        LOGGER.debug("Retransmitting {} block update(s) for {} to {}",
                missing.size(), key, connection.remoteName());
        for (PeerPacket update : missing) {
            connection.send(update);
        }
    }

    /**
     * Queues the acknowledgement of a received update for the next tick.
     *
     * @param key        chunk
     * @param connection sender
     * @param epoch      ownership epoch the update was ordered under
     */
    private void queueAck(ChunkKey key, PeerConnection connection, long epoch) {
        if (connection == null || !connection.isActive()) {
            return;
        }
        ChunkState current = states.get(key);
        if (current == null) {
            return;
        }
        long contiguous = current.contiguousSequence();
        pendingAcks.merge(key, new PendingAck(connection, epoch, contiguous), (old, candidate) -> {
            if (candidate.epoch > old.epoch) {
                return candidate;
            }
            if (candidate.epoch < old.epoch) {
                return old;
            }
            return candidate.contiguous >= old.contiguous ? candidate : old;
        });
    }

    /** Sends the batched acknowledgements. Called once per tick on the server thread. */
    private void flushAcks() {
        if (pendingAcks.isEmpty()) {
            return;
        }
        for (Map.Entry<ChunkKey, PendingAck> entry : pendingAcks.entrySet()) {
            PendingAck ack = pendingAcks.remove(entry.getKey());
            if (ack == null || !ack.connection.isActive()) {
                continue;
            }
            ChunkKey key = entry.getKey();
            ack.connection.send(new ChunkSequenceAckPacket(
                    key.getName(), key.getX(), key.getZ(), ack.epoch, ack.contiguous));
        }
    }

    /**
     * Remembers a sent update so a peer can ask for it again.
     *
     * @param key      chunk
     * @param epoch    ownership epoch of the update
     * @param sequence sequence number of the update
     * @param packet   update to resend on request
     */
    private void recordForRetransmit(ChunkKey key, long epoch, long sequence, PeerPacket packet) {
        RetransmitBuffer<PeerPacket> buffer = outbox.computeIfAbsent(
                key, ignored -> new RetransmitBuffer<>(OUTBOX_CAPACITY));
        if (buffer.record(epoch, sequence, packet) > 0) {
            LOGGER.debug("Retransmit history for {} overflowed; the oldest update was dropped", key);
        }
    }

    /** @return number of detected gaps in the block/block-entity stream */
    public long sequenceGapCount() {
        return sequenceGaps.get();
    }

    /** @return number of duplicate or late peer updates that were dropped */
    public long sequenceDuplicateCount() {
        return sequenceDuplicates.get();
    }

    /**
     * Responds to a peer's chunk request with the in-memory chunk, serialized on
     * the server thread so no chunk state is touched concurrently.
     *
     * <p>Only {@code ChunkSerializer.write} runs on the server thread; the gzip
     * compression and the reply are handed to the bounded IO pool. Serializing
     * one chunk is cheap compared to compressing it, and a large chunk response
     * used to stall the tick that happened to receive the request. When the pool
     * is saturated the caller compresses inline instead of dropping the reply.</p>
     *
     * @param connection requesting peer
     * @param packet     request
     */
    public void respondToChunkRequest(PeerConnection connection, com.multiforge.forge.peer.packet.RequestChunkPacket packet) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            CompoundTag snapshot = null;
            if (level != null) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(packet.x, packet.z);
                if (chunk != null) {
                    try {
                        snapshot = ChunkSerializer.write(level, chunk);
                    } catch (RuntimeException e) {
                        LOGGER.warn("Failed to serialize chunk for peer {}", connection.remoteName(), e);
                    }
                }
            }
            if (snapshot == null) {
                connection.send(new SendChunkPacket(packet.requestId, packet.world, packet.x, packet.z, new byte[0]));
                return;
            }
            CompoundTag immutableSnapshot = snapshot;
            if (!compression.execute(() -> sendSerializedChunk(connection, packet, immutableSnapshot))) {
                // Saturated or shutting down: the peer still gets its reply.
                sendSerializedChunk(connection, packet, immutableSnapshot);
            }
        });
    }

    /**
     * Compresses a serialized chunk and sends it. Runs on the compression pool or
     * on the calling thread when that pool is saturated.
     *
     * @param connection requesting peer
     * @param packet     request
     * @param tag        immutable chunk snapshot taken on the server thread
     */
    private void sendSerializedChunk(PeerConnection connection,
                                     com.multiforge.forge.peer.packet.RequestChunkPacket packet,
                                     CompoundTag tag) {
        byte[] data = new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            NbtIo.writeCompressed(tag, out);
            data = out.toByteArray();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to compress chunk for peer {}", connection.remoteName(), e);
        }
        connection.send(new SendChunkPacket(packet.requestId, packet.world, packet.x, packet.z, data));
    }

    /**
     * Stops the compression pool after draining the queued chunk replies.
     *
     * @param timeoutMillis maximum time to wait for the queue
     */
    public void close(long timeoutMillis) {
        compression.close(timeoutMillis);
    }

    /**
     * Handles a peer notification that entity data changed.
     *
     * <p>Entity data lives in the master's storage. A server that owns the chunk
     * is the authoritative simulation, so it re-sends NBT snapshots to the entity
     * subscribers; a server that only mirrors the chunk has nothing to do because
     * the owner keeps sending live updates. This replaced the previous log-only
     * stub (see {@code docs/KNOWN_ISSUES.md}).</p>
     *
     * @param packet notification
     */
    public void onRemoteEntityChunkChanged(EntityChunkChangedPacket packet) {
        server.entities().onRemoteEntityDataChanged(packet.world, packet.x, packet.z);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ChunkState state(ChunkKey key) {
        return states.computeIfAbsent(key, ignored -> new ChunkState());
    }

    /**
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     * @return ownership epoch currently known for the chunk, {@code 0} when unknown
     */
    public long epochOf(String world, int x, int z) {
        ChunkState state = states.get(new ChunkKey(world, x, z));
        return state == null ? 0L : state.ownershipEpoch();
    }

    /**
     * @param level level
     * @param x     chunk X
     * @param z     chunk Z
     * @return cluster key of the chunk
     */
    public ChunkKey keyOf(ServerLevel level, int x, int z) {
        return new ChunkKey(worldName(level), x, z);
    }

    /**
     * @param level level
     * @param pos   chunk position
     * @return cluster key of the chunk
     */
    public ChunkKey keyOf(ServerLevel level, ChunkPos pos) {
        return keyOf(level, pos.x, pos.z);
    }

    /**
     * @param level level
     * @return logical world name of the level
     */
    public String worldName(ServerLevel level) {
        return worldNames.computeIfAbsent(level.dimension(),
                ignored -> WorldDirectories.worldName(level));
    }

    /**
     * @return number of locally owned chunks
     */
    public int ownedChunkCount() {
        return ownedLocally.size();
    }

    /**
     * @return number of chunks this server currently has loaded and subscribed
     */
    public int loadedChunkCount() {
        int count = 0;
        for (ChunkState state : states.values()) {
            if (state.loaded()) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return snapshot of all known chunk states, for commands and tests
     */
    public Map<ChunkKey, ChunkState> snapshot() {
        return Map.copyOf(states);
    }

    /**
     * @return list of locally owned chunks, for debugging
     */
    public List<ChunkKey> ownedChunks() {
        return new ArrayList<>(ownedLocally);
    }

    /** Forgets all state. Called when the server stops. */
    public void clear() {
        pendingBlockEntities.clear();
        states.clear();
        ownedLocally.clear();
        unsubscribeAtTick.clear();
        lastOwnershipRequest.clear();
        worldNames.clear();
        pendingAcks.clear();
        outbox.clear();
        keyCache.clear();
        chunksWithEntitySubscribers.set(0);
    }

    /**
     * Acknowledgement waiting for the next tick.
     *
     * <p>Only the newest epoch and the highest contiguous value per chunk are
     * kept: acknowledging a value twice is harmless but wasteful.</p>
     */
    private static final class PendingAck {

        private final PeerConnection connection;
        private final long epoch;
        private final long contiguous;

        private PendingAck(PeerConnection connection, long epoch, long contiguous) {
            this.connection = connection;
            this.epoch = epoch;
            this.contiguous = contiguous;
        }
    }
}
