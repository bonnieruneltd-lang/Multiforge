package com.multiforge.forge.chunk;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.WorldDirectories;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.packet.BlockChangePacket;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;
import com.multiforge.forge.peer.packet.EntityChunkChangedPacket;
import com.multiforge.forge.peer.packet.SendChunkPacket;
import com.multiforge.protocol.ChunkKey;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
    private final Map<String, String> worldNames = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<BlockEntity> pendingBlockEntities = ConcurrentHashMap.newKeySet();

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
        if (ownedLocally.remove(key)) {
            server.master().send(new UnlockChunkMessage(key.getName(), key.getX(), key.getZ()));
            state.owner(null);
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
        }
    }

    /**
     * Called once per server tick. Applies deferred unsubscribes and forgets idle
     * chunk states.
     */
    public void tick() {
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
        ChunkKey key = keyOf(level, chunk.getPos());
        if (ownedLocally.contains(key)) {
            return true;
        }
        ChunkState state = state(key);
        String owner = state.owner();
        if (owner != null && !owner.equals(server.serverName())) {
            return false;
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
        ChunkState state = state(key);
        String previous = state.owner();
        String normalized = owner == null || owner.isEmpty() ? null : owner;
        if (java.util.Objects.equals(previous, normalized)) {
            // The master announces the owner to the requester and to subscribers,
            // so the same value can arrive twice.
            return;
        }
        state.owner(normalized);
        if (server.serverName().equals(normalized)) {
            ownedLocally.add(key);
            LOGGER.debug("Acquired ownership of {} (was {})", key, previous);
        } else {
            ownedLocally.remove(key);
            if (previous != null && previous.equals(server.serverName())) {
                LOGGER.debug("Lost ownership of {} to {}", key, normalized);
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
     * @return owner name, empty when unowned or unknown
     */
    public Optional<String> ownerOf(ChunkKey key) {
        ChunkState state = states.get(key);
        return state == null ? Optional.empty() : Optional.ofNullable(state.owner());
    }

    /**
     * @param key chunk
     * @return subscribed server names, including this server when loaded
     */
    public Collection<String> subscribersOf(ChunkKey key) {
        ChunkState state = states.get(key);
        return state == null ? List.of() : List.copyOf(state.subscribers());
    }

    /**
     * @param key chunk
     * @return servers that currently have the chunk's entities loaded, excluding
     *         this server (the master never reports the local subscriber)
     */
    public Collection<String> entitySubscribersOf(ChunkKey key) {
        ChunkState state = states.get(key);
        return state == null ? List.of() : List.copyOf(state.entitySubscribers());
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
        ChunkState state = state(key);
        state.subscribers().clear();
        for (String subscriber : subscribers) {
            state.subscribers().add(subscriber);
        }
        onOwnerChanged(key, owner);
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
        state.entitySubscribers().clear();
        for (String subscriber : subscribers) {
            state.entitySubscribers().add(subscriber);
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
        state(key).entitySubscribers().add(serverName);
        server.entities().onEntitySubscriberAdded(key, serverName);
    }

    /**
     * @param key    chunk
     * @param serverName server that unloaded the entities
     */
    public void onEntitySubscriberRemoved(ChunkKey key, String serverName) {
        ChunkState state = states.get(key);
        if (state != null) {
            state.entitySubscribers().remove(serverName);
        }
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
        // Peer connections are independent of the master: a master outage must not
        // stop block propagation between servers that still have a peer link.
        server.peers().broadcast(new BlockChangePacket(
                worldName(level), pos.getX(), pos.getY(), pos.getZ(), Block.getId(state)));
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
        server.peers().broadcast(new BlockEntityUpdatePacket(
                worldName((ServerLevel) blockEntity.getLevel()), pos.getX(), pos.getY(), pos.getZ(),
                blockEntity.saveWithFullMetadata()));
    }

    /**
     * Applies a block change received from a peer.
     *
     * <p>Runs on a Netty thread and hops to the server thread. Chunks that are not
     * loaded locally are ignored: they will be read from the cluster when they are
     * loaded.</p>
     *
     * @param packet received change
     */
    public void applyRemoteBlockChange(BlockChangePacket packet) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            if (level.getChunkSource().getChunkNow(packet.x >> 4, packet.z >> 4) == null) {
                return;
            }
            BlockState state = Block.stateById(packet.stateId);
            applyingRemote.set(Boolean.TRUE);
            try {
                level.setBlock(new BlockPos(packet.x, packet.y, packet.z), state, Block.UPDATE_ALL);
            } finally {
                applyingRemote.set(Boolean.FALSE);
            }
        });
    }

    /**
     * Applies a block entity update received from a peer.
     *
     * @param packet received update
     */
    public void applyRemoteBlockEntity(BlockEntityUpdatePacket packet) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null || level.getChunkSource().getChunkNow(packet.x >> 4, packet.z >> 4) == null) {
                return;
            }
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
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
        });
    }

    /**
     * Responds to a peer's chunk request with the in-memory chunk, serialized on
     * the server thread so no chunk state is touched concurrently.
     *
     * @param connection requesting peer
     * @param packet     request
     */
    public void respondToChunkRequest(PeerConnection connection, com.multiforge.forge.peer.packet.RequestChunkPacket packet) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            byte[] data = new byte[0];
            if (level != null) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(packet.x, packet.z);
                if (chunk != null) {
                    try {
                        CompoundTag tag = ChunkSerializer.write(level, chunk);
                        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
                        NbtIo.writeCompressed(tag, out);
                        data = out.toByteArray();
                    } catch (IOException | RuntimeException e) {
                        LOGGER.warn("Failed to serialize chunk for peer {}", connection.remoteName(), e);
                    }
                }
            }
            connection.send(new SendChunkPacket(packet.requestId, packet.world, packet.x, packet.z, data));
        });
    }

    /**
     * Handles a peer notification that entity data changed.
     *
     * <p>Entity data lives in the master's storage. A server that has the chunk
     * loaded re-reads it the next time the chunk is loaded, so the notification is
     * currently recorded for diagnostics only. Live entity movement replication is
     * tracked in {@code docs/KNOWN_ISSUES.md}.</p>
     *
     * @param packet notification
     */
    public void onRemoteEntityChunkChanged(EntityChunkChangedPacket packet) {
        if (server.config().debugLogging()) {
            LOGGER.info("Entity data for {} {},{},{} changed on a peer", packet.world, packet.x, packet.z);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ChunkState state(ChunkKey key) {
        return states.computeIfAbsent(key, ignored -> new ChunkState());
    }

    /**
     * @param level level
     * @param pos   chunk position
     * @return cluster key of the chunk
     */
    public ChunkKey keyOf(ServerLevel level, ChunkPos pos) {
        return new ChunkKey(worldName(level), pos.x, pos.z);
    }

    /**
     * @param level level
     * @return logical world name of the level
     */
    public String worldName(ServerLevel level) {
        return worldNames.computeIfAbsent(level.dimension().location().toString(),
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
    }
}
