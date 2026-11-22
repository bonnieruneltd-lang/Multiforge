package com.multiforge.forge.chunk;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.WorldDirectories;
import com.multiforge.forge.mixin.ChunkMapAccessor;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import com.multiforge.forge.player.ExternalPlayer;
import com.multiforge.forge.peer.packet.BlockChangePacket;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;
import com.multiforge.forge.peer.packet.ChunkSequenceAckPacket;
import com.multiforge.forge.peer.packet.EntityChunkChangedPacket;
import com.multiforge.forge.peer.packet.SendChunkPacket;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.util.BoundedTaskExecutor;
import com.multiforge.protocol.util.SequenceTracker;
import com.multiforge.protocol.messages.masterbound.ChunkChangedStatusMessage;
import com.multiforge.protocol.messages.masterbound.ChunkHandoverReadyMessage;
import com.multiforge.protocol.messages.masterbound.LockChunkMessage;
import com.multiforge.protocol.messages.masterbound.RequestChunkHandoverMessage;
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
import net.minecraft.server.level.ServerPlayer;
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
 *   <li>A real player walking into a chunk owned by another server starts a
 *       <b>fenced handover</b> ({@link #requestHandover}): the old owner freezes,
 *       saves that chunk and acknowledges before the master advances the epoch.
 *       The old owner refuses while it still hosts a real player in the chunk.</li>
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

    /**
     * Ticks this server waits for its chunk uploads before it declines a handover.
     * One second shorter than the master's five-second drain budget, so the master
     * normally receives a clean refusal instead of timing out.
     */
    private static final int HANDOVER_DRAIN_TIMEOUT_TICKS = 80;

    /**
     * Ticks a failed handover suppresses a new request for the same chunk.
     *
     * <p>A refusal is expected when the current owner also hosts a real player in
     * the chunk; retrying every ownership interval would spam the master and the
     * peer link. Ten seconds still reacts quickly when that player leaves.</p>
     */
    private static final int HANDOVER_RETRY_BACKOFF_TICKS = 200;



    private final MultiForgeServer server;
    private final Map<ChunkKey, ChunkState> states = new ConcurrentHashMap<>();
    private final Set<ChunkKey> ownedLocally = ConcurrentHashMap.newKeySet();
    private final Map<ChunkKey, Integer> unsubscribeAtTick = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Integer> lastOwnershipRequest = new ConcurrentHashMap<>();
    /**
     * Handovers this server is draining. Only the server thread mutates it.
     *
     * <p>The write that flushes the chunk is asynchronous, so the ready answer is
     * sent from {@link #tick()} once the master acknowledged every pending chunk
     * write. The entry carries the master-assigned id so a late answer after a
     * timeout can be recognised and discarded.</p>
     */
    private final Map<ChunkKey, PendingHandover> pendingHandovers = new ConcurrentHashMap<>();

    /**
     * Tick before which a new handover request for a chunk is suppressed after a
     * refusal or timeout. Server thread only.
     */
    private final Map<ChunkKey, Integer> handoverBackoff = new ConcurrentHashMap<>();


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
    /**
     * Last level and logical name returned by {@link #worldName(ServerLevel)}.
     *
     * <p>Entities of one level tick consecutively, so a single-entry cache removes
     * the {@link ConcurrentHashMap} lookup from the per-entity-tick path. The pair
     * is immutable and published through one volatile field, so a reader can never
     * combine a level with another level's name.</p>
     */
    private volatile WorldName worldNameCache;
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
     * number of changed chunks instead of the number of changed blocks. Only the
     * server thread touches this map (through {@code queueAck} and {@code flushAcks}).</p>
     */
    private final Map<ChunkKey, Map<PeerConnection, PendingAck>> pendingAcks = new ConcurrentHashMap<>();

    /** Chunks with acknowledgements that still have to be sent this tick. */
    private final Set<ChunkKey> dirtyAckChunks = ConcurrentHashMap.newKeySet();

    /**
     * Compression pool for peer chunk replies.
     *
     * <p>Serializing the chunk must run on the server thread, but gzip only needs
     * the immutable snapshot. When the pool is saturated the caller compresses
     * inline, so a reply is never dropped (see
     * {@link BoundedTaskExecutor#execute(Runnable)}). The queue is deliberately
     * small: every queued task holds one uncompressed chunk snapshot, and a large
     * chunk with many block entities can be several megabytes.</p>
     */
    private final BoundedTaskExecutor compression =
            new BoundedTaskExecutor(2, 16, "MultiForge-ChunkCompress");

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
            pendingAcks.remove(key);
            dirtyAckChunks.remove(key);
        }
    }

    /**
     * Called once per server tick. Applies deferred unsubscribes and forgets idle
     * chunk states.
     */
    public void tick() {
        flushAcks();
        drainBlockEntityUpdates();
        tickHandovers();
        requestHandoversForPlayers();
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
        int chunkX = Mth.floor(entity.getX()) >> 4;
        int chunkZ = Mth.floor(entity.getZ()) >> 4;
        boolean mayTick = shouldTick(level, chunkX, chunkZ);
        if (!mayTick && entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            // A projectile that crosses into a chunk another server owns would stop
            // being simulated (only the owner ticks entities). Follow it: request a
            // fenced handover so the server that holds the real projectile keeps
            // simulating it. The throttle and the ten-second backoff prevent churn.
            ChunkKey key = keyCache.get(level, worldName(level), chunkX, chunkZ);
            ChunkState state = states.get(key);
            if (state != null && state.owner() != null && !state.localOwner()) {
                requestHandover(key);
            }
        }
        return mayTick;
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
                // A frozen chunk is being drained for a handover. It must not
                // produce another block/entity change after it was saved.
                return !state.draining();
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
     * Asks the master for a fenced handover of a chunk another server owns.
     *
     * <p>Throttled with the same interval as {@link #requestOwnership} so a player
     * standing on an ownership border does not produce a request per tick. The
     * master answers with {@code ChunkHandoverCompleteMessage}; the actual owner
     * change still arrives as {@code SetChunkOwnerMessage}.</p>
     *
     * @param key chunk to take over
     */
    public void requestHandover(ChunkKey key) {
        int now = server.server().getTickCount();
        Integer retryAfter = handoverBackoff.get(key);
        if (retryAfter != null) {
            if (retryAfter > now) {
                return;
            }
            handoverBackoff.remove(key, retryAfter);
        }
        Integer last = lastOwnershipRequest.get(key);
        if (last != null && now - last < server.config().chunkOwnershipRequestIntervalTicks()) {
            return;
        }
        lastOwnershipRequest.put(key, now);
        if (server.config().debugLogging()) {
            LOGGER.info("Requesting handover of {}", key);
        }
        server.master().send(new RequestChunkHandoverMessage(key.getName(), key.getX(), key.getZ()));
    }

    /**
     * Requests ownership of every chunk that contains a real local player but is
     * ticked by another server.
     *
     * <p>This is the dynamic ownership half of the handover: without it a player
     * who walks across a server boundary keeps seeing the previous server's
     * authoritative copy until that server unloads the chunk. Mirrored players are
     * skipped because they do not represent a connection on this server.</p>
     *
     * <p>Runs once per tick on the server thread; the cost is one pass over the
     * (small) local player list per level.</p>
     */
    private void requestHandoversForPlayers() {
        for (ServerLevel level : server.server().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (player instanceof ExternalPlayer) {
                    continue;
                }
                ChunkKey key = keyCache.get(level, worldName(level),
                        Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4);
                ChunkState state = states.get(key);
                if (state == null || state.localOwner() || state.owner() == null
                        || state.owner().equals(server.serverName())) {
                    continue;
                }
                requestHandover(key);
            }
        }
    }

    /**
     * Freezes the chunk and flushes it so another server can become authoritative.
     *
     * <p>Called on the server thread from the master connection handler. The chunk
     * is saved through the vanilla per-chunk path, whose {@code ChunkStorage}
     * writes MultiForge already intercepts; the ready answer is delayed until those
     * uploads are acknowledged (see {@link #tickHandovers()}). A chunk with a real
     * local player inside is refused: the current server must stay authoritative
     * for that player.</p>
     *
     * @param key        chunk to drain
     * @param handoverId master-assigned id, echoed in the ready message
     * @param newOwner   server that will own the chunk
     */
    public void onHandoverStart(ChunkKey key, long handoverId, String newOwner) {
        ChunkState state = states.get(key);
        if (state == null || !state.localOwner()) {
            sendHandoverReady(key, handoverId, false);
            return;
        }
        ServerLevel level = server.level(key.getName());
        if (level == null || hasLocalPlayerIn(level, key)) {
            sendHandoverReady(key, handoverId, false);
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(key.getX(), key.getZ());
        if (chunk == null) {
            sendHandoverReady(key, handoverId, false);
            return;
        }

        state.draining(true);
        // Send the live entity state to the requester before the fence. The old
        // owner simulated these entities; without this the new owner would resume
        // from its older mirror copy (visible for projectiles and mobs that moved).
        server.entities().sendChunkSnapshotsTo(key, newOwner);
        try {
            ((ChunkMapAccessor) level.getChunkSource().chunkMap).multiforge$save(chunk);
        } catch (RuntimeException e) {
            state.draining(false);
            LOGGER.warn("Failed to drain chunk {} for handover {}", key, handoverId, e);
            sendHandoverReady(key, handoverId, false);
            return;
        }
        pendingHandovers.put(key, new PendingHandover(handoverId, server.server().getTickCount()));
    }

    /**
     * Clears the local handover bookkeeping after the master reports the result.
     *
     * @param key        chunk
     * @param handoverId master-assigned id
     * @param success    whether ownership moved to the requester
     * @param owner      owner after the attempt
     * @param epoch      ownership epoch after the attempt
     */
    public void onHandoverComplete(ChunkKey key, long handoverId, boolean success, String owner, long epoch) {
        pendingHandovers.remove(key);
        ChunkState state = states.get(key);
        if (state != null) {
            state.draining(false);
        }
        if (success) {
            handoverBackoff.remove(key);
        } else {
            handoverBackoff.put(key, server.server().getTickCount() + HANDOVER_RETRY_BACKOFF_TICKS);
        }
        if (server.config().debugLogging()) {
            LOGGER.info("Handover {} of {} finished: success={} owner={} epoch={}",
                    handoverId, key, success, owner, epoch);
        }
    }

    /**
     * Sends the ready answers whose chunk writes the master has acknowledged, and
     * fails the ones that outlive the drain budget.
     *
     * <p>Runs once per tick on the server thread. The budget is one second shorter
     * than the master timeout so a slow upload produces a clean refusal before the
     * master abandons the handover.</p>
     */
    private void tickHandovers() {
        if (pendingHandovers.isEmpty()) {
            return;
        }
        int now = server.server().getTickCount();
        for (Map.Entry<ChunkKey, PendingHandover> entry : pendingHandovers.entrySet()) {
            ChunkKey key = entry.getKey();
            PendingHandover pending = entry.getValue();
            // Two ticks give the peer connection time to deliver the entity snapshots
            // sent before the save, so the new owner applies them before it ticks.
            if (now - pending.startedAt >= 2 && server.chunkIo().pendingWriteCount() == 0) {
                if (pendingHandovers.remove(key, pending)) {
                    sendHandoverReady(key, pending.id, true);
                }
            } else if (now - pending.startedAt > HANDOVER_DRAIN_TIMEOUT_TICKS) {
                if (pendingHandovers.remove(key, pending)) {
                    ChunkState state = states.get(key);
                    if (state != null) {
                        state.draining(false);
                    }
                    sendHandoverReady(key, pending.id, false);
                    LOGGER.warn("Timed out draining chunk {} for handover {}", key, pending.id);
                }
            }
        }
    }

    /**
     * @param key        chunk
     * @param handoverId master-assigned id
     * @param ready      whether the drain completed
     */
    private void sendHandoverReady(ChunkKey key, long handoverId, boolean ready) {
        server.master().send(new ChunkHandoverReadyMessage(
                key.getName(), key.getX(), key.getZ(), handoverId, ready));
    }

    /**
     * @param level level of the chunk
     * @param key   chunk
     * @return {@code true} when a real (not mirrored) player is inside the chunk
     */
    private static boolean hasLocalPlayerIn(ServerLevel level, ChunkKey key) {
        for (ServerPlayer player : level.players()) {
            if (player instanceof ExternalPlayer) {
                continue;
            }
            if ((Mth.floor(player.getX()) >> 4) == key.getX()
                    && (Mth.floor(player.getZ()) >> 4) == key.getZ()) {
                return true;
            }
        }
        return false;
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
        if (!local) {
            // A handover this server was draining for is over (completed or
            // refused); there is nothing left to freeze.
            state.draining(false);
        }
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
        recordForRetransmit(chunkState, epoch, sequence, packet);
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
        recordForRetransmit(chunkState, epoch, sequence, packet);
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
        String sender = senderName(connection);
        server.execute(() -> {
            ChunkKey key = new ChunkKey(packet.world, packet.x >> 4, packet.z >> 4);
            ChunkState chunkState = state(key);
            if (!chunkState.observeEpoch(packet.epoch)) {
                LOGGER.debug("Dropped stale block change for {} at {},{} (epoch {} < {})",
                        key, packet.x, packet.z, packet.epoch,
                        Math.max(chunkState.ownershipEpoch(), chunkState.lastAppliedEpoch()));
                return;
            }
            SequenceTracker.Result ordering = chunkState.observeSequence(sender, packet.epoch, packet.sequence);
            if (ordering == SequenceTracker.Result.STALE_EPOCH) {
                LOGGER.debug("Dropped block change for {} from an older epoch ({})", key, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.DUPLICATE) {
                sequenceDuplicates.incrementAndGet();
                LOGGER.debug("Dropped duplicate block change for {} (epoch {}, sequence {})",
                        key, packet.epoch, packet.sequence);
                queueAck(key, connection, packet.epoch, packet.sequence);
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
                queueAck(key, connection, packet.epoch, packet.sequence);
                return;
            }
            BlockState state = Block.stateById(packet.stateId);
            applyingRemote.set(Boolean.TRUE);
            try {
                level.setBlock(new BlockPos(packet.x, packet.y, packet.z), state, Block.UPDATE_ALL);
            } finally {
                applyingRemote.set(Boolean.FALSE);
            }
            queueAck(key, connection, packet.epoch, packet.sequence);
        });
    }

    /**
     * Applies a block entity update received from a peer.
     *
     * @param packet     received update
     * @param connection peer that sent the update, used for the acknowledgement
     */
    public void applyRemoteBlockEntity(BlockEntityUpdatePacket packet, PeerConnection connection) {
        String sender = senderName(connection);
        server.execute(() -> {
            ChunkKey key = new ChunkKey(packet.world, packet.x >> 4, packet.z >> 4);
            ChunkState chunkState = state(key);
            if (!chunkState.observeEpoch(packet.epoch)) {
                LOGGER.debug("Dropped stale block entity update for {} at {},{} (epoch {} < {})",
                        key, packet.x, packet.z, packet.epoch,
                        Math.max(chunkState.ownershipEpoch(), chunkState.lastAppliedEpoch()));
                return;
            }
            SequenceTracker.Result ordering = chunkState.observeSequence(sender, packet.epoch, packet.sequence);
            if (ordering == SequenceTracker.Result.STALE_EPOCH) {
                LOGGER.debug("Dropped block entity update for {} from an older epoch ({})", key, packet.epoch);
                return;
            }
            if (ordering == SequenceTracker.Result.DUPLICATE) {
                sequenceDuplicates.incrementAndGet();
                LOGGER.debug("Dropped duplicate block entity update for {} (epoch {}, sequence {})",
                        key, packet.epoch, packet.sequence);
                queueAck(key, connection, packet.epoch, packet.sequence);
                return;
            }
            if (ordering == SequenceTracker.Result.GAP) {
                sequenceGaps.incrementAndGet();
                LOGGER.warn("Missing block entity updates for {} before sequence {} (epoch {}); asking {} to retransmit",
                        key, packet.sequence, packet.epoch, connection.remoteName());
            }
            ServerLevel level = server.level(packet.world);
            if (level == null || level.getChunkSource().getChunkNow(packet.x >> 4, packet.z >> 4) == null) {
                queueAck(key, connection, packet.epoch, packet.sequence);
                return;
            }
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                queueAck(key, connection, packet.epoch, packet.sequence);
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
            queueAck(key, connection, packet.epoch, packet.sequence);
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
        ChunkState state = states.get(key);
        if (state == null) {
            return;
        }
        String peer = senderName(connection);
        List<PeerPacket> missing = state.retransmitBuffer(peer)
                .acknowledge(packet.epoch, packet.contiguous);
        if (missing.isEmpty()) {
            return;
        }
        LOGGER.debug("Retransmitting {} block update(s) for {} to {}",
                missing.size(), key, peer);
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
    private void queueAck(ChunkKey key, PeerConnection connection, long epoch, long sequence) {
        if (epoch <= 0L || sequence <= 0L) {
            // No ownership epoch or sequence information (older peer, or a change
            // made before the master announced ownership). Such a packet is applied
            // as before but cannot be acknowledged: the ack would carry contiguous 0
            // and make the sender retransmit the same updates forever.
            return;
        }
        if (connection == null || !connection.isActive()) {
            return;
        }
        ChunkState current = states.get(key);
        if (current == null) {
            return;
        }
        long contiguous = current.contiguousSequence(senderName(connection));
        Map<PeerConnection, PendingAck> byPeer =
                pendingAcks.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        PendingAck ack = byPeer.computeIfAbsent(connection,
                ignored -> new PendingAck(epoch, contiguous));
        ack.merge(epoch, contiguous);
        ack.dirty = true;
        dirtyAckChunks.add(key);
    }

    /** Sends the batched acknowledgements. Called once per tick on the server thread. */
    private void flushAcks() {
        if (dirtyAckChunks.isEmpty()) {
            return;
        }
        for (ChunkKey key : dirtyAckChunks) {
            Map<PeerConnection, PendingAck> byPeer = pendingAcks.get(key);
            if (byPeer == null) {
                continue;
            }
            for (Map.Entry<PeerConnection, PendingAck> entry : byPeer.entrySet()) {
                PeerConnection connection = entry.getKey();
                PendingAck ack = entry.getValue();
                if (!connection.isActive()) {
                    byPeer.remove(connection);
                    continue;
                }
                if (ack.dirty) {
                    ack.send(key, connection);
                    ack.dirty = false;
                }
            }
            if (byPeer.isEmpty()) {
                pendingAcks.remove(key, byPeer);
            }
        }
        dirtyAckChunks.clear();
    }

    /**
     * @param connection peer connection
     * @return peer name used as the ordering key, never {@code null}
     */
    private static String senderName(PeerConnection connection) {
        String name = connection.remoteName();
        return name == null ? "" : name;
    }

    /**
     * Remembers a sent update for every connected peer so one of them can ask for
     * it again.
     *
     * <p>The history is recorded per peer before the broadcast; an acknowledgement
     * from one peer only drops that peer's copy.</p>
     *
     * @param state    chunk state holding the bounded histories
     * @param epoch    ownership epoch of the update
     * @param sequence sequence number of the update
     * @param packet   update to resend on request
     */
    private void recordForRetransmit(ChunkState state, long epoch, long sequence, PeerPacket packet) {
        if (epoch <= 0L) {
            // Updates without an epoch are never acknowledged (see queueAck), so
            // keeping them would only fill the bounded history.
            return;
        }
        for (PeerConnection connection : server.peers().connections()) {
            String peer = connection.remoteName();
            if (peer == null) {
                continue;
            }
            if (state.retransmitBuffer(peer).record(epoch, sequence, packet) > 0) {
                LOGGER.debug("Retransmit history for peer {} overflowed; the oldest update was dropped",
                        peer);
            }
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
        WorldName cached = worldNameCache;
        if (cached != null && cached.level == level) {
            return cached.name;
        }
        String name = worldNames.computeIfAbsent(level.dimension(),
                ignored -> WorldDirectories.worldName(level));
        worldNameCache = new WorldName(level, name);
        return name;
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
        worldNameCache = null;
        pendingAcks.clear();
        dirtyAckChunks.clear();
        pendingHandovers.clear();
        handoverBackoff.clear();
        keyCache.clear();
        chunksWithEntitySubscribers.set(0);
    }

    /**
     * A chunk this server saved for a handover and whose uploads are still in
     * flight. Immutable; replaced, never mutated.
     */
    private static final class PendingHandover {

        private final long id;
        private final int startedAt;

        private PendingHandover(long id, int startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
    }

    /**
     * One level/name pair; immutable, so a caller cannot observe a mix of two
     * levels through the cache.
     */
    private static final class WorldName {

        private final ServerLevel level;
        private final String name;

        private WorldName(ServerLevel level, String name) {
            this.level = level;
            this.name = name;
        }
    }

    /**
     * Acknowledgement waiting for the next tick.
     *
     * <p>One instance per chunk and peer, mutated on the server thread, so a busy
     * chunk does not allocate an object per update. The per-peer map is needed
     * because a chunk can receive updates from more than one server while
     * ownership settles, and each sender needs its own acknowledgement.</p>
     */
    private static final class PendingAck {

        private long epoch;
        private long contiguous;
        private boolean dirty;

        private PendingAck(long epoch, long contiguous) {
            this.epoch = epoch;
            this.contiguous = contiguous;
        }

        private void merge(long candidateEpoch, long candidateContiguous) {
            if (candidateEpoch > epoch) {
                epoch = candidateEpoch;
                contiguous = candidateContiguous;
            } else if (candidateEpoch == epoch && candidateContiguous > contiguous) {
                contiguous = candidateContiguous;
            }
        }

        private void send(ChunkKey key, PeerConnection connection) {
            if (connection.isActive()) {
                connection.send(new ChunkSequenceAckPacket(
                        key.getName(), key.getX(), key.getZ(), epoch, contiguous));
            }
        }
    }
}
