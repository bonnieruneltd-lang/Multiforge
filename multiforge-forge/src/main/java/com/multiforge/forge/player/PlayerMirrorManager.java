package com.multiforge.forge.player;

import com.mojang.authlib.GameProfile;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.mixin.PlayerListAccessor;
import com.multiforge.forge.mixin.ServerPlayerAccessor;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.packet.PlayerCreatePacket;
import com.multiforge.forge.peer.packet.PlayerListInfoPacket;
import com.multiforge.forge.peer.packet.PlayerRemovePacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates, refreshes and removes the mirror copies of players from other servers.
 *
 * <p>Port of MultiPaper's player half of {@code MultiPaperEntitiesHandler} and
 * {@code ExternalPlayer}. A player is only simulated by the server that accepted
 * the connection, but every server that has the player's chunk loaded must show
 * them. This manager therefore:</p>
 * <ul>
 *   <li>announces a local player to the other servers when they join, respawn or
 *       change dimension, and on entity subscription when a peer loads their
 *       chunk later;</li>
 *   <li>constructs an {@link ExternalPlayer}, registers it in the vanilla
 *       {@code PlayerList} and level and broadcasts its tab list entry on the
 *       receiving side;</li>
 *   <li>removes the copy on logout or when the origin peer disconnects;</li>
 *   <li>retries a create while the target chunk is still loading.</li>
 * </ul>
 *
 * <p>Threading: packets arrive on a Netty thread and only enqueue a create in a
 * concurrent map. Every world access, the vanilla player list and the entity
 * tracker run on the server thread through {@link MultiForgeServer#execute}.
 * {@link #tick()} is called once per server tick.</p>
 */
public final class PlayerMirrorManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Players");

    /** How long a create is retried while the target chunk loads (ticks). */
    private static final int MAX_CREATE_RETRIES = 100;

    /**
     * Ticks between full re-announcements of every local player.
     *
     * <p>A teleport into a chunk that another server already has loaded does not
     * fire an entity-subscription event there, so the normal "announce when the
     * peer subscribes" path can miss the player and no mirror is created. A
     * periodic create is idempotent on the receiver (it updates an existing
     * mirror) and also repairs a mirror lost during a peer reconnect.</p>
     */
    private static final int REANNOUNCE_INTERVAL_TICKS = 100;


    private final MultiForgeServer server;

    /** Mirrored players by UUID. Written on the server thread; read for diagnostics. */
    private final Map<UUID, ExternalPlayer> externalPlayers = new ConcurrentHashMap<>();

    /** Creates waiting for their level or chunk. Written on the server thread. */
    private final Map<UUID, PendingCreate> pending = new ConcurrentHashMap<>();

    /** UUIDs whose removal is in progress, to stop PlayerList.remove recursion. */
    private final Set<UUID> removing = ConcurrentHashMap.newKeySet();

    /** Server tick counter used by diagnostics and retry accounting. */
    private int tickCounter;

    /**
     * @param server owning server
     */
    public PlayerMirrorManager(MultiForgeServer server) {
        this.server = server;
    }

    /** @return number of remotely hosted players currently mirrored */
    public int mirroredPlayerCount() {
        return externalPlayers.size();
    }


    // ------------------------------------------------------------------
    // Owner side
    // ------------------------------------------------------------------

    /**
     * Announces a local player to every peer.
     *
     * <p>Called after login, respawn and dimension change. The player data tag is
     * built here, on the server thread, so the peer encoder only sees bytes.</p>
     *
     * @param player local player that changed
     */
    public void onLocalPlayerChanged(ServerPlayer player) {
        announce(player, null);
    }

    /**
     * Announces a local player to one peer, used when that peer subscribes to the
     * entity's chunk after the player already joined.
     *
     * @param player local player
     * @param peer   target server name
     */
    public void announceTo(ServerPlayer player, String peer) {
        if (peer != null) {
            announce(player, List.of(peer));
        }
    }

    /**
     * Drops a mirror copy when a real connection with the same UUID is about to
     * log in on this server.
     *
     * <p>The master prevents the same player from being online twice, so this is
     * a defensive transfer cleanup: the logout packet of the previous server may
     * not have arrived yet. The real connection must always win.</p>
     *
     * @param uuid UUID of the joining player
     */
    public void onLocalPlayerJoining(UUID uuid) {
        server.execute(() -> {
            pending.remove(uuid);
            ExternalPlayer player = externalPlayers.get(uuid);
            if (player != null) {
                removeExternal(player);
            }
        });
    }

    /**
     * Tells every peer that a local player left the cluster.
     *
     * @param player local player that is logging out
     */
    public void onLocalPlayerLeft(ServerPlayer player) {
        if (server.isStopping() || !server.config().playerSync() || player instanceof ExternalPlayer
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        server.peers().broadcast(new PlayerRemovePacket(
                server.chunks().worldName(level), player.getUUID()));
    }

    /**
     * Announces every local player to a peer that just connected or reconnected.
     *
     * <p>A reconnect can happen after the previous connection was reaped, which
     * removes the copies it hosted. Entity subscriptions are not repeated on a
     * mere peer reconnect, so the create packets have to be replayed here.</p>
     *
     * @param peerName name of the peer that became active
     */
    public void onPeerConnected(String peerName) {
        if (peerName == null) {
            return;
        }
        server.execute(() -> {
            if (server.isStopping() || !server.config().playerSync()) {
                return;
            }
            for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
                if (!(player instanceof ExternalPlayer)) {
                    announceTo(player, peerName);
                }
            }
        });
    }

    /**
     * Removes every mirror copy that came from a peer that just disconnected.
     *
     * <p>Called by {@code PeerManager} on the Netty thread; the removals themselves
     * run on the server thread. Without this a crashed server would leave ghost
     * players and tab list entries behind until our own restart.</p>
     *
     * @param originServer name of the server whose connection closed
     */
    public void onPeerDisconnected(String originServer) {
        if (originServer == null) {
            return;
        }
        server.execute(() -> {
            pending.entrySet().removeIf(entry -> originServer.equals(entry.getValue().origin));
            for (ExternalPlayer player : List.copyOf(externalPlayers.values())) {
                if (originServer.equals(player.originServer())) {
                    removeExternal(player);
                }
            }
        });
    }

    /**
     * Snapshots and sends one local player.
     *
     * @param player  local player
     * @param targets peers to send to, or {@code null} for every peer
     */
    private void announce(ServerPlayer player, Collection<String> targets) {
        if (server.isStopping() || !server.config().playerSync()
                || player instanceof ExternalPlayer || player.isRemoved()
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        try {
            CompoundTag tag = new CompoundTag();
            player.saveWithoutId(tag);
            PlayerCreatePacket packet = new PlayerCreatePacket(
                    server.chunks().worldName(level), player, tag);
            PlayerListInfoPacket info = new PlayerListInfoPacket(
                    player.getUUID(), displayNameJson(player),
                    player.gameMode.getGameModeForPlayer().getId());
            if (targets != null) {
                server.peers().sendTo(targets, packet);
                server.peers().sendTo(targets, info);
                return;
            }
            Set<String> destinations = new HashSet<>();
            for (PeerConnection connection : server.peers().connections()) {
                if (connection.remoteName() != null) {
                    destinations.add(connection.remoteName());
                }
            }
            // Subscribers whose connection is currently down still get the player
            // through sendTo's on-demand connect.
            destinations.addAll(server.chunks().entitySubscribersOf(
                    server.chunks().keyOf(level, player.chunkPosition())));
            server.peers().sendTo(destinations, packet);
            // The create packet carries the game mode; the info packet adds the
            // custom tab-list display name that vanilla does not persist in NBT.
            server.peers().sendTo(destinations, info);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not announce player {}: {}",
                    player.getGameProfile().getName(), e.toString());
        }
    }


    // ------------------------------------------------------------------
    // Mirror side
    // ------------------------------------------------------------------

    /**
     * Applies a tab-list display name / game-mode change from the hosting server.
     *
     * <p>Runs on the server thread. The values are written to the cached fields of
     * the mirror and broadcast to local clients through the vanilla player-info
     * packet, so the tab list icon and custom name update without a reconnect.</p>
     *
     * @param uuid        player UUID
     * @param displayName display name JSON, empty for none
     * @param gameMode    vanilla game mode id
     */
    public void onRemoteListInfo(UUID uuid, String displayName, int gameMode) {
        server.execute(() -> {
            ExternalPlayer player = externalPlayers.get(uuid);
            if (player == null) {
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                if (gameMode >= 0 && gameMode <= 3) {
                    GameType type = PlayerCreatePacket.gameType(gameMode);
                    if (player.gameMode.getGameModeForPlayer() != type) {
                        player.setGameMode(type);
                    }
                }
                Component name = null;
                if (displayName != null && !displayName.isEmpty()) {
                    try {
                        name = Component.Serializer.fromJson(displayName);
                    } catch (RuntimeException e) {
                        LOGGER.warn("Ignoring invalid display name for mirrored player {}", uuid, e);
                    }
                }
                ((ServerPlayerAccessor) (Object) player).multiforge$tabListDisplayName(name);
                ((ServerPlayerAccessor) (Object) player).multiforge$hasTabListName(true);
            });
            server.server().getPlayerList().broadcastAll(
                    new ClientboundPlayerInfoUpdatePacket(
                            java.util.EnumSet.of(
                                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
                            List.of(player)));
        });
    }

    /**
     * @param player local player
     * @return display-name JSON, empty when the player has no custom name
     */
    private static String displayNameJson(ServerPlayer player) {
        Component displayName = player.getTabListDisplayName();
        return displayName == null ? "" : Component.Serializer.toJson(displayName);
    }

    /**
     * Queues a create received from a peer.
     *
     * @param origin connection name of the owning server
     * @param packet create packet
     */
    public void onRemoteCreate(String origin, PlayerCreatePacket packet) {
        GameProfile profile = packet.profile();
        if (profile == null || profile.getId() == null) {
            return;
        }
        server.execute(() -> {
            if (server.isStopping() || !server.config().playerSync()) {
                return;
            }
            pending.put(profile.getId(), new PendingCreate(origin, packet));
        });
    }

    /**
     * Removes a mirrored player after the owner announced that it left.
     *
     * @param world logical world the player was in
     * @param uuid  player UUID
     */
    public void onRemoteRemove(String world, UUID uuid) {
        server.execute(() -> {
            pending.remove(uuid);
            ExternalPlayer player = externalPlayers.get(uuid);
            if (player != null) {
                removeExternal(player);
            }
        });
    }

    /**
     * Drops bookkeeping when a mirrored entity was removed by the level.
     *
     * <p>A chunk unload removes the entity without a logout. The player stays in
     * the cluster player list, and the next subscription or create packet builds
     * a fresh copy.</p>
     *
     * @param entity removed entity
     */
    public void onMirrorRemoved(Entity entity) {
        if (entity instanceof ExternalPlayer player && !removing.contains(player.getUUID())) {
            removeExternal(player);
        }
    }

    /**
     * Retries pending creates and expires stale state.
     *
     * <p>Called from {@link MultiForgeServer#tick()} on the server thread.</p>
     */
    public void tick() {
        tickCounter++;
        if (server.isStopping()) {
            pending.clear();
            return;
        }
        if (!server.config().playerSync()) {
            // /reload may have disabled player mirroring; drop the copies instead
            // of leaving them in the player list with no updates.
            pending.clear();
            for (ExternalPlayer player : List.copyOf(externalPlayers.values())) {
                removeExternal(player);
            }
            return;
        }
        if (tickCounter % REANNOUNCE_INTERVAL_TICKS == 0) {
            for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
                if (player instanceof ExternalPlayer || !(player.level() instanceof ServerLevel level)) {
                    continue;
                }
                // Only peers that already entity-subscribe the player's chunk can
                // create a mirror; re-announcing to everyone would send full player
                // NBT to peers that do not load the chunk at all.
                com.multiforge.protocol.ChunkKey key =
                        server.chunks().keyOf(level, player.chunkPosition());
                Collection<String> subscribers = server.chunks().entitySubscribersOf(key);
                if (!subscribers.isEmpty()) {
                    announce(player, subscribers);
                }
            }
        }
        for (Map.Entry<UUID, PendingCreate> entry : pending.entrySet()) {
            PendingCreate create = entry.getValue();
            if (applyCreate(create.origin, create.packet)) {
                pending.remove(entry.getKey(), create);
            } else if (++create.attempts >= MAX_CREATE_RETRIES) {
                pending.remove(entry.getKey(), create);
                if (server.config().debugLogging()) {
                    LOGGER.info("Dropping player create for {} after {} retries",
                            entry.getKey(), create.attempts);
                }
            }
        }
    }

    /** Removes every mirror copy. Called when the server stops. */
    public void clear() {
        pending.clear();
        externalPlayers.clear();
        tickCounter = 0;
    }


    /**
     * Applies one create packet on the server thread.
     *
     * @return {@code true} when consumed, {@code false} to retry next tick
     */
    private boolean applyCreate(String origin, PlayerCreatePacket packet) {
        GameProfile profile = packet.profile();
        UUID uuid = profile.getId();
        ServerLevel level = server.level(packet.world());
        if (level == null) {
            // This server does not have the world. The owner announces the player
            // again when this server subscribes to their chunk.
            return true;
        }
        int chunkX = ((int) Math.floor(packet.x())) >> 4;
        int chunkZ = ((int) Math.floor(packet.z())) >> 4;
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return false;
        }

        ExternalPlayer existing = externalPlayers.get(uuid);
        if (existing != null && (existing.isRemoved() || existing.level() != level)) {
            removeExternal(existing);
            existing = null;
        }
        if (existing == null) {
            ServerPlayer local = server.server().getPlayerList().getPlayer(uuid);
            if (local instanceof ExternalPlayer stale) {
                // A copy whose entity was removed by a chunk unload but whose
                // PlayerList entry survived an older build/removal path.
                removeExternal(stale);
                local = null;
            }
            if (local != null) {
                // A real connection always wins; the master should have prevented
                // both from being online, so this is a login race, not a mirror.
                return true;
            }
            return createExternal(origin, packet, level, profile);
        }
        refreshExternal(existing, packet);
        return true;
    }

    private boolean createExternal(String origin, PlayerCreatePacket packet,
                                   ServerLevel level, GameProfile profile) {
        ExternalPlayer player;
        try {
            player = new ExternalPlayer(server.server(), level, profile, origin);
            player.load(packet.tag());
            player.moveTo(packet.x(), packet.y(), packet.z(), packet.yaw(), packet.pitch());
            player.setYHeadRot(packet.yaw());
            player.setOldPosAndRot();
            GameType type = PlayerCreatePacket.gameType(packet.gameMode());
            if (player.gameMode.getGameModeForPlayer() != type) {
                player.gameMode.changeGameModeForPlayer(type);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not construct a mirror for player {}: {}",
                    profile.getName(), e.toString());
            return true;
        }

        EntitySyncManager.applyRemote(() -> {
            addToPlayerList(player);
            level.addNewPlayer(player);
        });
        if (level.getEntity(profile.getId()) != player) {
            // The level refused the entity (chunk unloaded between the check and
            // the call). Undo the list entry and retry when the chunk is back.
            detachFromPlayerList(player);
            externalPlayers.remove(profile.getId(), player);
            return false;
        }
        externalPlayers.put(profile.getId(), player);
        broadcastPlayerInfo(player);
        LOGGER.info("Mirroring player {} ({}) from {}", profile.getName(), profile.getId(), origin);
        return true;
    }

    private void refreshExternal(ExternalPlayer player, PlayerCreatePacket packet) {
        EntitySyncManager.applyRemote(() -> {
            player.load(packet.tag());
            player.moveTo(packet.x(), packet.y(), packet.z(), packet.yaw(), packet.pitch());
            player.setYHeadRot(packet.yaw());
            player.setOldPosAndRot();
            GameType type = PlayerCreatePacket.gameType(packet.gameMode());
            if (player.gameMode.getGameModeForPlayer() != type) {
                player.gameMode.changeGameModeForPlayer(type);
            }
        });
        broadcastPlayerInfo(player);
    }


    /**
     * Adds a mirror copy to the vanilla player list without running login logic.
     *
     * <p>The list is what makes the player appear in the tab list of clients that
     * join later ({@code PlayerList.placeNewPlayer} sends initializing packets for
     * every entry) and what {@code /list} and the public API read.</p>
     *
     * @param player mirror copy
     */
    private void addToPlayerList(ExternalPlayer player) {
        PlayerListAccessor accessor = accessor();
        accessor.multiforge$players().add(player);
        accessor.multiforge$playersByUUID().put(player.getUUID(), player);
    }

    private PlayerListAccessor accessor() {
        return (PlayerListAccessor) (Object) server.server().getPlayerList();
    }

    /**
     * Sends (or refreshes) the player's tab list entry on every local client.
     *
     * @param player mirror copy
     */
    private void broadcastPlayerInfo(ExternalPlayer player) {
        ClientboundPlayerInfoUpdatePacket packet =
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        for (ServerPlayer local : localPlayers()) {
            local.connection.send(packet);
        }
    }

    /**
     * @return every player with a real connection on this server
     */
    private List<ServerPlayer> localPlayers() {
        List<ServerPlayer> locals = new ArrayList<>();
        for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
            if (!(player instanceof ExternalPlayer)) {
                locals.add(player);
            }
        }
        return locals;
    }

    /**
     * Removes a mirror copy through the vanilla logout path so the player list,
     * stats/advancement maps and tab list entry are cleaned up consistently.
     *
     * @param player mirror copy to remove
     */
    private void removeExternal(ExternalPlayer player) {
        UUID uuid = player.getUUID();
        if (!removing.add(uuid)) {
            return;
        }
        try {
            externalPlayers.remove(uuid, player);
            pending.remove(uuid);
            if (player.isRemoved()) {
                detachFromPlayerList(player);
                return;
            }
            // PlayerList.remove fires PlayerLoggedOutEvent; the event handler
            // ignores ExternalPlayer instances, and PlayerListMixin.save skips
            // them, so no player data is written for a mirror.
            server.server().getPlayerList().remove(player);
        } finally {
            removing.remove(uuid);
        }
    }

    /**
     * Removes the list/stat/advancement bookkeeping of a mirror whose entity was
     * already removed by its level (chunk unload or a failed add).
     *
     * @param player mirror copy
     */
    private void detachFromPlayerList(ExternalPlayer player) {
        UUID uuid = player.getUUID();
        PlayerListAccessor accessor = accessor();
        accessor.multiforge$players().remove(player);
        accessor.multiforge$playersByUUID().remove(uuid, player);
        accessor.multiforge$stats().remove(uuid);
        accessor.multiforge$advancements().remove(uuid);
        player.getAdvancements().stopListening();
        ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(List.of(uuid));
        for (ServerPlayer local : localPlayers()) {
            local.connection.send(remove);
        }
    }

    /**
     * One queued create and its retry counter. Only touched on the server thread.
     */
    private static final class PendingCreate {

        private final String origin;
        private final PlayerCreatePacket packet;
        private int attempts;

        private PendingCreate(String origin, PlayerCreatePacket packet) {
            this.origin = origin;
            this.packet = packet;
        }
    }
}
