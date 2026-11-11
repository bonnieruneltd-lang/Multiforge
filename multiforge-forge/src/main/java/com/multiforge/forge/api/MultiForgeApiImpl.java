package com.multiforge.forge.api;

import com.multiforge.api.ChunkLocation;
import com.multiforge.api.MultiForgeApi;
import com.multiforge.api.ServerInfo;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.protocol.ChunkKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Forge implementation of the public {@link MultiForgeApi}.
 *
 * <p>Reads live state from the master connection, the chunk manager and the player
 * registry. Every method is non-blocking and safe to call from any thread; results
 * are snapshots rather than live views.</p>
 */
public final class MultiForgeApiImpl implements MultiForgeApi {

    private final MultiForgeServer server;

    /**
     * @param server owning runtime
     */
    public MultiForgeApiImpl(MultiForgeServer server) {
        this.server = server;
    }

    @Override
    public String getLocalServerName() {
        return server.serverName();
    }

    @Override
    public boolean isConnectedToMaster() {
        return server.master().isConnected();
    }

    @Override
    public Collection<ServerInfo> getServers() {
        List<ServerInfo> servers = new ArrayList<>(server.master().getServers());
        // The master broadcasts ServerStartedMessage to the *other* servers only, so
        // this server's own entry can carry an empty peer address. Replace it with
        // the locally known address; that keeps /multiforge servers and the API
        // consistent on the server that is being queried.
        servers.removeIf(info -> info.name().equals(server.serverName()));
        servers.add(new ServerInfo(server.serverName(),
                server.server().getLocalIp(), server.peerServer().getBoundPort(),
                server.tps(), server.server().getPlayerList().getPlayerCount(),
                server.master().isConnected()));
        return List.copyOf(servers);
    }

    @Override
    public Optional<ServerInfo> getServer(String name) {
        return getServers().stream().filter(info -> info.name().equals(name)).findFirst();
    }

    @Override
    public boolean isChunkLocal(ChunkLocation chunk) {
        return server.chunks().isLocal(toKey(chunk));
    }

    @Override
    public Optional<String> getChunkOwner(ChunkLocation chunk) {
        return server.chunks().ownerOf(toKey(chunk));
    }

    @Override
    public Collection<String> getChunkSubscribers(ChunkLocation chunk) {
        return server.chunks().subscribersOf(toKey(chunk));
    }

    @Override
    public Optional<String> getPlayerServer(UUID player) {
        return server.players().getPlayerServer(player);
    }

    @Override
    public Collection<UUID> getAllOnlinePlayers() {
        return server.players().getAllOnlinePlayers();
    }

    private static ChunkKey toKey(ChunkLocation location) {
        return new ChunkKey(location.world(), location.x(), location.z());
    }
}
