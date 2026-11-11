package com.multiforge.forge.player;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.ChatMessagePacket;
import com.multiforge.protocol.messages.masterbound.PlayerConnectMessage;
import com.multiforge.protocol.messages.masterbound.PlayerDisconnectMessage;
import com.multiforge.protocol.messages.masterbound.ReadAdvancementMessage;
import com.multiforge.protocol.messages.masterbound.ReadPlayerMessage;
import com.multiforge.protocol.messages.masterbound.ReadStatsMessage;
import com.multiforge.protocol.messages.masterbound.WriteAdvancementsMessage;
import com.multiforge.protocol.messages.masterbound.WritePlayerMessage;
import com.multiforge.protocol.messages.masterbound.WriteStatsMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cluster-wide player registry and player data synchronization.
 *
 * <p>Ports MultiPaper's {@code MultiPaperPlayerHandler} and the player half of
 * {@code MultiPaperInventoryHandler}: the master owns the player {@code .dat},
 * statistics and advancement files, so a player can reconnect to any server and
 * keep their data. It also implements duplicate-login prevention and cluster-wide
 * chat.</p>
 *
 * <p>Threading: {@link #onPlayerUpdate} runs on the Netty thread and only touches
 * concurrent maps. Methods marked "blocking" are called from the login path on the
 * server thread and wait for the master with the configured IO timeout; they return
 * a sensible default when the cluster is unavailable so a standalone server keeps
 * working.</p>
 */
public final class PlayerSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Players");

    private final MultiForgeServer server;
    private final Map<UUID, PlayerLocation> players = new ConcurrentHashMap<>();

    /**
     * @param server owning server
     */
    public PlayerSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Registry
    // ------------------------------------------------------------------

    /**
     * Applies a join/leave broadcast from the master.
     *
     * @param message update
     */
    public void onPlayerUpdate(PlayerUpdateMessage message) {
        if (message.online) {
            players.put(message.uuid, new PlayerLocation(message.playerName, message.server, true));
        } else {
            players.remove(message.uuid);
        }
    }

    /**
     * @param uuid player UUID
     * @return server the player is on, empty when offline
     */
    public Optional<String> getPlayerServer(UUID uuid) {
        PlayerLocation location = players.get(uuid);
        return location == null ? Optional.empty() : Optional.of(location.server);
    }

    /**
     * @param name player name, case insensitive
     * @return server the player is on, empty when offline
     */
    public Optional<String> getPlayerServerByName(String name) {
        for (PlayerLocation location : players.values()) {
            if (location.name.equalsIgnoreCase(name)) {
                return Optional.of(location.server);
            }
        }
        return Optional.empty();
    }

    /** @return UUIDs of all players online anywhere in the cluster */
    public Collection<UUID> getAllOnlinePlayers() {
        return List.copyOf(players.keySet());
    }

    /** @return snapshot of the cluster player registry */
    public Map<UUID, PlayerLocation> snapshot() {
        return Map.copyOf(players);
    }

    // ------------------------------------------------------------------
    // Login / logout
    // ------------------------------------------------------------------

    /**
     * Claims a player for this server. Fails when another live server already
     * holds the player, which prevents item duplication through double logins.
     *
     * <p>Blocking: called from the login thread. Returns {@code true} when the
     * cluster is unavailable (standalone fallback).</p>
     *
     * @param player joining player
     * @return {@code true} when the login may proceed
     */
    public boolean claimPlayer(ServerPlayer player) {
        if (!server.config().playerSync() || !server.master().isConnected()) {
            return true;
        }
        try {
            BooleanMessageReply reply = server.master()
                    .request(new PlayerConnectMessage(player.getUUID(), player.getGameProfile().getName()), BooleanMessageReply.class)
                    .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            return reply.result;
        } catch (Exception e) {
            LOGGER.warn("Could not claim player {} at the master, allowing login: {}",
                    player.getGameProfile().getName(), e.toString());
            return true;
        }
    }

    /**
     * Releases the player at the master so they can join another server.
     *
     * @param player leaving player
     */
    public void releasePlayer(ServerPlayer player) {
        if (!server.config().playerSync()) {
            return;
        }
        server.master().send(new PlayerDisconnectMessage(player.getUUID()));
        players.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    // Player data
    // ------------------------------------------------------------------

    /**
     * Loads the authoritative player data from the master.
     *
     * <p>Blocking; called from {@code PlayerList.load}. Returns empty when the
     * master has no data (first join) or is unavailable, in which case the local
     * file is used.</p>
     *
     * @param player player being loaded
     * @return player data tag
     */
    public Optional<CompoundTag> loadPlayerData(ServerPlayer player) {
        if (!server.config().syncPlayerData() || !server.master().isConnected()) {
            return Optional.empty();
        }
        String world = server.chunks().worldName((net.minecraft.server.level.ServerLevel) player.level());
        try {
            DataMessageReply reply = server.master()
                    .request(new ReadPlayerMessage(world, player.getUUID().toString()), DataMessageReply.class)
                    .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            if (reply.data.length == 0) {
                return Optional.empty();
            }
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(reply.data));
            return Optional.ofNullable(tag);
        } catch (Exception e) {
            LOGGER.warn("Failed to load player data for {}, using local copy: {}",
                    player.getGameProfile().getName(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Uploads the current player data to the master. The local file is still
     * written by vanilla, acting as a backup.
     *
     * @param player player being saved
     */
    public void savePlayerData(ServerPlayer player) {
        if (!server.config().syncPlayerData() || !server.master().isConnected()) {
            return;
        }
        try {
            CompoundTag tag = player.saveWithoutId(new CompoundTag());
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            NbtIo.writeCompressed(tag, out);
            String world = server.chunks().worldName((net.minecraft.server.level.ServerLevel) player.level());
            server.master().send(new WritePlayerMessage(world, player.getUUID().toString(), out.toByteArray()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to upload player data for {}", player.getGameProfile().getName(), e);
        }
    }

    /**
     * Copies statistics and advancements from the master into the local files
     * before vanilla reads them.
     *
     * <p>Vanilla creates the stats/advancement objects inside the
     * {@code ServerPlayer} constructor, so this must run before that constructor
     * completes (the mixin calls it at the start).</p>
     *
     * @param uuid   player UUID
     * @param playerName player name, for logging only
     */
    public void prefetchPlayerFiles(UUID uuid, String playerName) {
        if (!server.master().isConnected() || !server.config().syncStatistics() && !server.config().syncAdvancements()) {
            return;
        }
        String world = com.multiforge.forge.common.WorldDirectories.baseWorldName(server.server());
        if (server.config().syncStatistics()) {
            fetchAndWrite(uuid, playerName, world,
                    new ReadStatsMessage(world, uuid.toString()),
                    server.server().getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json"));
        }
        if (server.config().syncAdvancements()) {
            fetchAndWrite(uuid, playerName, world,
                    new ReadAdvancementMessage(world, uuid.toString()),
                    server.server().getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json"));
        }
    }

    private void fetchAndWrite(UUID uuid, String playerName, String world,
                               com.multiforge.protocol.messages.masterbound.MasterBoundMessage request,
                               Path destination) {
        try {
            DataMessageReply reply = server.master().request(request, DataMessageReply.class)
                    .get(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS);
            if (reply.data.length == 0) {
                return;
            }
            Files.createDirectories(destination.getParent());
            Files.write(destination, reply.data);
            LOGGER.debug("Prefetched {} for {}", destination.getFileName(), playerName);
        } catch (Exception e) {
            LOGGER.debug("Could not prefetch {} for {}: {}", destination.getFileName(), playerName, e.toString());
        }
    }

    /**
     * Uploads a statistics file.
     *
     * @param uuid player UUID
     * @param json serialized statistics
     */
    public void uploadStats(UUID uuid, String json) {
        if (!server.config().syncStatistics() || !server.master().isConnected()) {
            return;
        }
        String world = com.multiforge.forge.common.WorldDirectories.baseWorldName(server.server());
        server.master().send(new WriteStatsMessage(world, uuid.toString(), json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Uploads an advancements file.
     *
     * @param uuid player UUID
     * @param json serialized advancements
     */
    public void uploadAdvancements(UUID uuid, String json) {
        if (!server.config().syncAdvancements() || !server.master().isConnected()) {
            return;
        }
        String world = com.multiforge.forge.common.WorldDirectories.baseWorldName(server.server());
        server.master().send(new WriteAdvancementsMessage(world, uuid.toString(), json.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * Broadcasts a chat message to all other servers.
     *
     * @param sender  sending player
     * @param message rendered message
     */
    public void broadcastChat(ServerPlayer sender, Component message) {
        if (!server.config().playerSync()) {
            return;
        }
        String json = Component.Serializer.toJson(message);
        server.peers().broadcast(new ChatMessagePacket(json, sender.getUUID(), server.serverName()));
    }

    /**
     * Shows a chat message that originated on another server.
     *
     * @param packet chat packet
     */
    public void onRemoteChat(ChatMessagePacket packet) {
        if (packet.originServer.equals(server.serverName())) {
            return;
        }
        server.execute(() -> {
            Component component = Component.Serializer.fromJson(packet.componentJson);
            if (component != null) {
                server.server().getPlayerList().broadcastSystemMessage(component, false);
            }
        });
    }

    /** Removes all registry entries. Called on shutdown. */
    public void clear() {
        players.clear();
    }

    /**
     * Immutable location entry of the cluster player registry.
     *
     * @param name   last known name
     * @param server server name
     * @param online always {@code true} for entries in the map
     */
    public record PlayerLocation(String name, String server, boolean online) {
    }
}
