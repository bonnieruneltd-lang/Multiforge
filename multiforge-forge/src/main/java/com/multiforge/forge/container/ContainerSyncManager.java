package com.multiforge.forge.container;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.packet.BlockEntityUpdatePacket;
import com.multiforge.forge.peer.packet.ContainerClickPacket;
import com.multiforge.forge.peer.packet.ContainerClickPacket.SlotChange;
import com.multiforge.forge.peer.packet.ContainerClosePacket;
import com.multiforge.forge.peer.packet.OpenContainerPacket;
import com.multiforge.protocol.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards container windows opened on a server that does not own the container's
 * chunk.
 *
 * <p>Why a forwarding layer is needed: only the owner ticks the chunk, and only
 * the owner is allowed to change the authoritative block entity. The player,
 * however, is connected to another server, and vanilla opens the window there.
 * The menu therefore runs on the player's server against its mirrored block
 * entity; the owner is kept in the loop so its copy stays authoritative.</p>
 *
 * <p>Protocol (ids 27-29, appended after the live player-state family):</p>
 * <ol>
 *   <li>{@link OpenContainerPacket} - the player's server tells the owner which
 *       container was opened. The owner replies with a fresh
 *       {@link BlockEntityUpdatePacket} and records the open window.</li>
 *   <li>{@link ContainerClickPacket} - after every menu click, the player's
 *       server sends the changed container slots. The owner writes them into its
 *       container, marks it changed and broadcasts the new NBT.</li>
 *   <li>{@link ContainerClosePacket} - sent when the menu closes (or is replaced)
 *       so the owner drops the open-window record.</li>
 * </ol>
 *
 * <p>The menu is not cancelled on the player's server: vanilla click logic,
 * cursor handling, shift-click, drag distribution and quick-move all run there.
 * Only the container slots are forwarded; the player's own inventory is
 * authoritative on the player's server and travels through
 * {@code PlayerStateSyncManager} as usual.</p>
 *
 * <p>While a forwarded click is being evaluated, block entity broadcasts for
 * that chunk are suppressed on the player's server, because the owner will
 * broadcast the canonical result. Otherwise both servers would race to write
 * the same NBT.</p>
 *
 * <p>Threading: all state is owned by the server thread. Packet handlers run on
 * the Netty thread and marshal through {@link MultiForgeServer#execute(Runnable)}.
 * Maps are concurrent only because the peer-disconnect hook may run on a Netty
 * thread; their contents are mutated on the server thread.</p>
 */
public final class ContainerSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Containers");

    private final MultiForgeServer server;

    /** Player's server: player -> container currently open in a foreign chunk. */
    private final Map<UUID, RemoteContainer> localOpen = new ConcurrentHashMap<>();
    /** Snapshot of the container slots taken at the start of a click. */
    private final Map<UUID, List<SlotValue>> pendingClicks = new ConcurrentHashMap<>();
    /** Chunks whose block entity broadcasts are suppressed during a forwarded click. */
    private final Set<ChunkKey> forwardedChunks = ConcurrentHashMap.newKeySet();

    /** Owner's server: player -> containers that player has open from a peer. */
    private final Map<UUID, Set<RemoteOpen>> remoteOpens = new ConcurrentHashMap<>();

    /**
     * @param server owning MultiForge server
     */
    public ContainerSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Player's server (menu owner)
    // ------------------------------------------------------------------

    /**
     * Called after a menu was opened locally. If the menu belongs to a block
     * entity in a chunk owned by another server, the owner is notified.
     *
     * @param player   player that opened the menu
     * @param provider provider passed to {@code ServerPlayer.openMenu}
     */
    public void onLocalMenuOpened(ServerPlayer player, MenuProvider provider) {
        if (server.isStopping() || !(provider instanceof BlockEntity blockEntity)) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel level)
                || blockEntity.getLevel() != player.level()) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        ChunkKey key = server.chunks().keyOf(level, pos.getX() >> 4, pos.getZ() >> 4);
        String owner = server.chunks().ownerName(key);
        if (owner == null || owner.equals(server.serverName())) {
            localOpen.remove(player.getUUID());
            return;
        }
        String world = server.chunks().worldName(level);
        localOpen.put(player.getUUID(), new RemoteContainer(world, pos, owner));
        server.peers().sendTo(List.of(owner),
                new OpenContainerPacket(world, pos.getX(), pos.getY(), pos.getZ(), player.getUUID()));
        if (server.config().debugLogging()) {
            LOGGER.info("Forwarded container open at {} {} to owner {}", world, pos, owner);
        }
    }

    /**
     * Called at the start of {@code AbstractContainerMenu.clicked}. Captures the
     * container slots so the result can be diffed after vanilla handled the click.
     *
     * @param menu   clicked menu
     * @param player clicking player
     */
    public void beginClick(AbstractContainerMenu menu, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        RemoteContainer remote = localOpen.get(serverPlayer.getUUID());
        if (remote == null) {
            return;
        }
        List<SlotValue> stale = pendingClicks.put(serverPlayer.getUUID(), snapshot(menu, player));
        if (stale != null) {
            // A previous click threw before its RETURN hook; clear the suppression
            // for whatever chunk it belonged to.
            forwardedChunks.remove(new ChunkKey(remote.world, remote.pos.getX() >> 4, remote.pos.getZ() >> 4));
        }
        forwardedChunks.add(new ChunkKey(remote.world, remote.pos.getX() >> 4, remote.pos.getZ() >> 4));
    }

    /**
     * Called at the end of {@code AbstractContainerMenu.clicked}. Sends the
     * changed container slots to the current owner.
     *
     * @param menu   clicked menu
     * @param player clicking player
     */
    public void endClick(AbstractContainerMenu menu, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        UUID uuid = serverPlayer.getUUID();
        List<SlotValue> before = pendingClicks.remove(uuid);
        RemoteContainer remote = localOpen.get(uuid);
        if (remote == null || before == null) {
            return;
        }
        ChunkKey key = new ChunkKey(remote.world, remote.pos.getX() >> 4, remote.pos.getZ() >> 4);
        try {
            List<SlotChange> changes = diff(menu, player, before);
            if (changes.isEmpty()) {
                return;
            }
            String owner = server.chunks().ownerName(key);
            if (owner == null || owner.equals(server.serverName())) {
                // Ownership moved to this server (or away entirely) while the menu
                // was open; the local block entity is authoritative from now on.
                localOpen.remove(uuid);
                return;
            }
            if (!owner.equals(remote.owner)) {
                server.peers().sendTo(List.of(remote.owner), new ContainerClosePacket(
                        remote.world, remote.pos.getX(), remote.pos.getY(), remote.pos.getZ(), uuid));
                server.peers().sendTo(List.of(owner), new OpenContainerPacket(
                        remote.world, remote.pos.getX(), remote.pos.getY(), remote.pos.getZ(), uuid));
                remote = new RemoteContainer(remote.world, remote.pos, owner);
                localOpen.put(uuid, remote);
            }
            server.peers().sendTo(List.of(owner), new ContainerClickPacket(
                    remote.world, remote.pos.getX(), remote.pos.getY(), remote.pos.getZ(), uuid, changes));
            if (server.config().debugLogging()) {
                LOGGER.info("Forwarded {} container slot change(s) at {} to owner {}",
                        changes.size(), remote.pos, owner);
            }
        } finally {
            forwardedChunks.remove(key);
        }
    }
    /**
     * Called when the local player closes a menu; tells the owner and forgets the
     * mapping.
     *
     * @param player player that closed the menu
     */
    public void onLocalMenuClosed(ServerPlayer player) {
        RemoteContainer remote = localOpen.remove(player.getUUID());
        pendingClicks.remove(player.getUUID());
        if (remote == null) {
            return;
        }
        forwardedChunks.remove(new ChunkKey(remote.world, remote.pos.getX() >> 4, remote.pos.getZ() >> 4));
        server.peers().sendTo(List.of(remote.owner), new ContainerClosePacket(
                remote.world, remote.pos.getX(), remote.pos.getY(), remote.pos.getZ(), player.getUUID()));
    }

    /**
     * Sends the close packet when a local player leaves the server without going
     * through {@code doCloseContainer} (for example a crash during shutdown).
     *
     * @param player leaving player
     */
    public void onLocalPlayerLeft(ServerPlayer player) {
        onLocalMenuClosed(player);
    }

    /**
     * @param blockEntity block entity about to be broadcast
     * @return {@code true} when a forwarded click currently owns the change, so
     *         the generic block entity broadcast must be skipped
     */
    public boolean suppressesBroadcast(BlockEntity blockEntity) {
        if (forwardedChunks.isEmpty() || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = blockEntity.getBlockPos();
        return forwardedChunks.contains(new ChunkKey(
                server.chunks().worldName(level), pos.getX() >> 4, pos.getZ() >> 4));
    }

    // ------------------------------------------------------------------
    // Owner's server
    // ------------------------------------------------------------------

    /**
     * Handles {@link OpenContainerPacket}: validates the window, records it and
     * replies with the owner's current block entity NBT.
     *
     * @param packet     open notification
     * @param connection connection the packet arrived on
     */
    public void onRemoteOpen(OpenContainerPacket packet, PeerConnection connection) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            ChunkKey key = server.chunks().keyOf(level, packet.x >> 4, packet.z >> 4);
            if (!server.chunks().isLocal(key)) {
                // Ownership moved away; the player's server will retarget on the
                // next click (or reopen the menu locally).
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return;
            }
            remoteOpens.computeIfAbsent(packet.player, ignored -> ConcurrentHashMap.newKeySet())
                    .add(new RemoteOpen(packet.world, pos, connection.remoteName()));
            connection.send(new BlockEntityUpdatePacket(packet.world, packet.x, packet.y, packet.z,
                    blockEntity.saveWithFullMetadata(),
                    server.chunks().epochOf(packet.world, packet.x >> 4, packet.z >> 4)));
            if (server.config().debugLogging()) {
                LOGGER.info("Player {} opened remote container {} {}", packet.player, packet.world, pos);
            }
        });
    }

    /**
     * Handles {@link ContainerClickPacket}: writes the changed slots into the
     * authoritative container and broadcasts the new NBT.
     *
     * @param packet     click result
     * @param connection connection the packet arrived on
     */
    public void onRemoteClick(ContainerClickPacket packet, PeerConnection connection) {
        server.execute(() -> {
            ServerLevel level = server.level(packet.world);
            if (level == null) {
                return;
            }
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            if (!isOpen(packet.player, packet.world, pos, connection.remoteName())) {
                LOGGER.debug("Ignoring click from {} on {}: container is not open", packet.player, pos);
                return;
            }
            ChunkKey key = server.chunks().keyOf(level, packet.x >> 4, packet.z >> 4);
            if (!server.chunks().isLocal(key)) {
                return;
            }
            Container container = containerAt(level, pos);
            if (container == null) {
                return;
            }
            boolean changed = false;
            for (SlotChange change : packet.changes) {
                int index = change.index();
                if (index < 0 || index >= container.getContainerSize()) {
                    continue;
                }
                container.setItem(index, change.stack());
                changed = true;
            }
            if (!changed) {
                return;
            }
            container.setChanged();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                server.chunks().broadcastBlockEntity(blockEntity);
            }
            if (server.config().debugLogging()) {
                LOGGER.info("Applied {} remote slot change(s) at {} from {}",
                        packet.changes.size(), pos, packet.player);
            }
        });
    }

    /**
     * Handles {@link ContainerClosePacket}: forgets the open-window record.
     *
     * @param packet     close notification
     * @param connection connection the packet arrived on
     */
    public void onRemoteClose(ContainerClosePacket packet, PeerConnection connection) {
        server.execute(() -> {
            BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
            removeOpen(packet.player, packet.world, pos, connection.remoteName());
        });
    }

    /**
     * Drops every window record that came from a peer that disconnected.
     *
     * @param peerName disconnected peer
     */
    public void onPeerDisconnected(String peerName) {
        if (peerName == null) {
            return;
        }
        localOpen.entrySet().removeIf(entry -> entry.getValue().owner.equals(peerName));
        for (Map.Entry<UUID, Set<RemoteOpen>> entry : remoteOpens.entrySet()) {
            entry.getValue().removeIf(open -> open.server.equals(peerName));
            if (entry.getValue().isEmpty()) {
                remoteOpens.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * @return number of remote windows this server is tracking as the owner
     */
    public int remoteOpenCount() {
        int count = 0;
        for (Set<RemoteOpen> opens : remoteOpens.values()) {
            count += opens.size();
        }
        return count;
    }

    /** Forgets all state. Called when the server stops. */
    public void clear() {
        localOpen.clear();
        pendingClicks.clear();
        forwardedChunks.clear();
        remoteOpens.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isOpen(UUID player, String world, BlockPos pos, String serverName) {
        Set<RemoteOpen> opens = remoteOpens.get(player);
        if (opens == null) {
            return false;
        }
        for (RemoteOpen open : opens) {
            if (open.world.equals(world) && open.pos.equals(pos) && open.server.equals(serverName)) {
                return true;
            }
        }
        return false;
    }

    private void removeOpen(UUID player, String world, BlockPos pos, String serverName) {
        Set<RemoteOpen> opens = remoteOpens.get(player);
        if (opens != null) {
            opens.removeIf(open -> open.world.equals(world) && open.pos.equals(pos)
                    && open.server.equals(serverName));
            if (opens.isEmpty()) {
                remoteOpens.remove(player, opens);
            }
        }
    }

    /**
     * Resolves the authoritative container of a block entity.
     *
     * <p>Most block entities are containers themselves. A double chest is a
     * {@link net.minecraft.world.CompoundContainer} of both halves; reconstructing
     * it from the block state keeps container slot indices above 26 pointing at
     * the correct half.</p>
     *
     * @param level level
     * @param pos   block position
     * @return authoritative container, or {@code null} when the block has none
     */
    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container chestContainer = ChestBlock.getContainer(chest, state, level, pos, true);
            if (chestContainer != null) {
                return chestContainer;
            }
        }
        return blockEntity instanceof Container container ? container : null;
    }

    /**
     * @return container slots of the menu with the values at click start
     */
    private static List<SlotValue> snapshot(AbstractContainerMenu menu, Player player) {
        List<SlotValue> values = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            values.add(new SlotValue(slot.getContainerSlot(), slot.getItem().copy()));
        }
        return values;
    }

    /**
     * @return slots whose item changed while vanilla handled the click
     */
    private static List<SlotChange> diff(AbstractContainerMenu menu, Player player, List<SlotValue> before) {
        List<SlotChange> changes = new ArrayList<>();
        int index = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            if (index >= before.size()) {
                break;
            }
            SlotValue previous = before.get(index++);
            ItemStack current = slot.getItem();
            if (!ItemStack.matches(previous.stack, current)) {
                changes.add(new SlotChange(previous.containerSlot, current.copy()));
            }
        }
        return changes;
    }

    /** Container the player has open on a foreign server. */
    private record RemoteContainer(String world, BlockPos pos, String owner) {
    }

    /** Container a remote player has open on this (owning) server. */
    private record RemoteOpen(String world, BlockPos pos, String server) {
    }

    /** Container slot value used for click diffing. */
    private record SlotValue(int containerSlot, ItemStack stack) {
    }
}


