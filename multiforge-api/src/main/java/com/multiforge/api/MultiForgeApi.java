package com.multiforge.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Public, Minecraft independent view of a running MultiForge cluster.
 *
 * <p>Implementations must be thread-safe: they are called from the server
 * thread, from Netty threads and from third party mods. Every method returns
 * immediately; network work happens asynchronously.</p>
 *
 * <p>Mirrors the parts of the original {@code MultiPaper API} that make sense
 * outside of Bukkit: chunk locality, server listings and cross-server player
 * lookups.</p>
 */
public interface MultiForgeApi {

    /** @return this server's configured name */
    String getLocalServerName();

    /** @return {@code true} once the master connection is established */
    boolean isConnectedToMaster();

    /** @return immutable snapshot of all servers known to the master, including this one */
    Collection<ServerInfo> getServers();

    /**
     * Looks up a server by name.
     *
     * @param name server name
     * @return snapshot when the master knows the server
     */
    Optional<ServerInfo> getServer(String name);

    /**
     * @param chunk chunk to inspect
     * @return {@code true} if this server currently ticks the chunk
     */
    boolean isChunkLocal(ChunkLocation chunk);

    /**
     * @param chunk chunk to inspect
     * @return name of the server that owns (ticks) the chunk, empty when unowned
     */
    Optional<String> getChunkOwner(ChunkLocation chunk);

    /**
     * @param chunk chunk to inspect
     * @return names of all servers that have the chunk loaded
     */
    Collection<String> getChunkSubscribers(ChunkLocation chunk);

    /**
     * @param player player UUID
     * @return name of the server the player is connected to, empty when offline
     */
    Optional<String> getPlayerServer(UUID player);

    /**
     * @return every player that is online anywhere in the cluster
     */
    Collection<UUID> getAllOnlinePlayers();
}
