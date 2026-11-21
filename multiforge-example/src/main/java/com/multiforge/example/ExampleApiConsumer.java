package com.multiforge.example;

import com.multiforge.api.ChunkLocation;
import com.multiforge.api.MultiForgeApi;
import com.multiforge.api.ServerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal example of consuming the MultiForge API from another mod.
 *
 * <p>A mod only needs a compile/runtime dependency on {@code multiforge-api} and to call
 * {@link com.multiforge.api.MultiForgeApiProvider#api()}. Everything here is
 * Minecraft-free, so the example is compiled and unit tested by the normal build; it acts
 * as an integration test for the public API surface (TODO 20).</p>
 */
public final class ExampleApiConsumer {

    private ExampleApiConsumer() {
    }

    /**
     * Formats the cluster server list for a command or a log line.
     *
     * @param api live API
     * @return sorted {@code name address players} lines
     */
    public static List<String> describeServers(MultiForgeApi api) {
        List<String> lines = new ArrayList<>();
        for (ServerInfo info : api.getServers()) {
            lines.add(String.format("%s %s %d players%s",
                    info.name(),
                    info.address().isEmpty() ? "(no peer port)" : info.address(),
                    info.playerCount(),
                    info.alive() ? "" : " (offline)"));
        }
        lines.sort(Comparator.naturalOrder());
        return List.copyOf(lines);
    }

    /**
     * @param api   live API
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     * @return {@code true} when this server ticks the chunk
     */
    public static boolean isLocalChunk(MultiForgeApi api, String world, int x, int z) {
        return api.isChunkLocal(new ChunkLocation(world, x, z));
    }

    /**
     * @param api   live API
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     * @return owning server, empty when the chunk is unowned
     */
    public static Optional<String> chunkOwner(MultiForgeApi api, String world, int x, int z) {
        return api.getChunkOwner(new ChunkLocation(world, x, z));
    }

    /**
     * @param api live API
     * @return number of players online anywhere in the cluster
     */
    public static int clusterPlayerCount(MultiForgeApi api) {
        return api.getAllOnlinePlayers().size();
    }

    /**
     * @param api live API
     * @param player player UUID
     * @return server hosting the player, empty when offline
     */
    public static Optional<String> playerServer(MultiForgeApi api, UUID player) {
        return api.getPlayerServer(player);
    }
}
