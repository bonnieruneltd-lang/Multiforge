package com.multiforge.velocity;

import com.google.inject.Inject;
import com.multiforge.master.MultiForgeMaster;
import com.multiforge.master.ServerConnection;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Velocity plugin that hosts the MultiForge master and balances players.
 *
 * <p>This is the port of MultiPaper's {@code MultiPaperVelocity} plugin. The
 * standalone master remains the default deployment; this plugin is for networks
 * that already run Velocity and do not want a second process. It starts an
 * embedded master on the configured control port (game servers still point at
 * that port), stores the world under {@code plugins/multiforge-velocity/storage}
 * and, for every connect that targets a MultiForge backend, sends the player to
 * the live backend with the lowest average tick time.</p>
 *
 * <p>The plugin does not start the master's built-in Minecraft proxy (Velocity is
 * the proxy) and does not enable leader election or the metrics endpoint; use the
 * standalone master for those. Backend names in {@code velocity.toml} must match
 * the Forge servers' {@code server.name} values, exactly as the standalone
 * built-in proxy requires.</p>
 *
 * <p>Failure policy: if the master cannot start (port in use, unreadable
 * configuration) the error is logged and players keep connecting without
 * balancing rather than taking the proxy down.</p>
 */
@Plugin(
        id = "multiforge-velocity",
        name = "MultiForge Velocity",
        version = "1.0.0",
        authors = {"MultiForge contributors"},
        description = "Runs the MultiForge master inside Velocity and balances players by tick time."
)
public final class MultiForgeVelocity {

    /** Default control port, matching the standalone master. */
    private static final int DEFAULT_PORT = 35353;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    /** Whether the embedded master started; balancing is skipped otherwise. */
    private volatile boolean masterStarted;
    /** Whether to send players to the best backend instead of the configured one. */
    private volatile boolean balanceNodes = true;

    /**
     * @param proxy         proxy server
     * @param logger        plugin logger
     * @param dataDirectory plugin data directory
     */
    @Inject
    public MultiForgeVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Reads the plugin configuration and starts the embedded master.
     *
     * @param event proxy initialization event
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Properties properties = loadConfig();
        int port = parsePort(properties.getProperty("port", Integer.toString(DEFAULT_PORT)));
        balanceNodes = Boolean.parseBoolean(properties.getProperty("balance-nodes", "true"));

        Path storage = dataDirectory.resolve("storage");
        try {
            Files.createDirectories(storage);
            // MasterConfig and MasterStorage read these at class initialisation and
            // at startEmbedded; set them before touching either class.
            System.setProperty("multiforge.master.storage", storage.toString());
            System.setProperty("multiforge.master.config",
                    dataDirectory.resolve("multiforge-master.properties").toString());
            MultiForgeMaster.startEmbedded("0.0.0.0", port);
            masterStarted = true;
            logger.info("MultiForge master embedded in Velocity on port {} (storage {})",
                    port, storage);
        } catch (IOException | RuntimeException e) {
            masterStarted = false;
            logger.error("Could not start the embedded MultiForge master; "
                    + "players will connect without balancing", e);
        }
    }

    /**
     * Redirects a connect for a MultiForge backend to the live backend with the
     * lowest average tick time.
     *
     * @param event pre-connect event
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!masterStarted || !balanceNodes) {
            return;
        }
        Optional<RegisteredServer> requested = event.getResult().getServer();
        if (requested.isEmpty()) {
            return;
        }
        String targetName = requested.get().getServerInfo().getName();
        if (ServerConnection.getConnection(targetName) == null) {
            // Not a MultiForge backend (hub, lobby, ...): leave the proxy's choice.
            return;
        }

        RegisteredServer best = null;
        long lowestTickTime = Long.MAX_VALUE;
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            ServerConnection connection = ServerConnection.getConnection(name);
            if (connection == null || !ServerConnection.isAlive(name)) {
                continue;
            }
            long average = connection.getTimer().averageInMillis();
            if (average < lowestTickTime) {
                lowestTickTime = average;
                best = server;
            }
        }
        if (best != null) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(best));
        }
    }

    /**
     * Loads the plugin properties, copying the packaged defaults on first run.
     *
     * @return configuration
     */
    private Properties loadConfig() {
        Properties properties = new Properties();
        Path file = dataDirectory.resolve("config.properties");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.isRegularFile(file)) {
                try (InputStream in = getClass().getClassLoader()
                        .getResourceAsStream("config.properties")) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read {}; using defaults", file, e);
        }
        return properties;
    }

    /**
     * @param value configured port
     * @return a valid port, or the default when the value is unusable
     */
    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        logger.warn("Invalid master port '{}'; using {}", value, DEFAULT_PORT);
        return DEFAULT_PORT;
    }
}
