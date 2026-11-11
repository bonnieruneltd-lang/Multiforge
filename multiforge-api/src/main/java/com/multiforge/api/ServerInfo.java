package com.multiforge.api;

/**
 * Immutable snapshot of another MultiForge server in the cluster.
 *
 * <p>Instances are produced by {@link MultiForgeApi#getServers()} and are safe
 * to publish to other threads. A server whose last heartbeat is older than the
 * configured timeout is reported with {@code alive == false}.</p>
 *
 * @param name         unique server name as configured in {@code multiforge-server.toml}
 * @param host         host other servers use to open peer connections
 * @param port         peer-to-peer port, {@code -1} while the server is still starting
 * @param tps          last reported ticks per second, {@code 0} when unknown
 * @param playerCount  number of players connected to that server
 * @param alive        whether the server responded to the master recently
 */
public record ServerInfo(String name, String host, int port, double tps, int playerCount, boolean alive) {

    /** @return {@code true} if this snapshot describes the local server */
    public boolean isLocal() {
        return name.equals(MultiForgeApiProvider.api().getLocalServerName());
    }

    /**
     * @return {@code host:port} usable for a peer connection, or an empty
     *         string when the peer port is not known yet
     */
    public String address() {
        return port > 0 ? host + ":" + port : "";
    }
}
