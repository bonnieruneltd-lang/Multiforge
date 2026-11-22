package com.multiforge.bungee;

import com.multiforge.master.MultiForgeMaster;
import com.multiforge.master.ServerConnection;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BungeeCord plugin that hosts the MultiForge master and balances players.
 *
 * <p>The BungeeCord counterpart of {@code MultiForgeVelocity} and of MultiPaper's
 * {@code MultiPaperBungee}. It starts the embedded master on the configured control
 * port (game servers keep pointing at that port), stores the world under
 * {@code plugins/MultiForgeBungee/storage} and sends a connect that targets a
 * MultiForge backend to the live backend with the lowest average tick time.</p>
 *
 * <p>{@code /server <name>} with the {@code multiforge.directserverconnect}
 * permission bypasses balancing once, so an operator can pin a player to a server
 * for debugging, matching the upstream plugin. The built-in proxy, leader election
 * and metrics endpoint are not started here; use the standalone master for those.</p>
 *
 * <p>Failure policy: an unusable configuration or port is logged and the proxy
 * keeps running; players then connect without balancing.</p>
 */
public final class MultiForgeBungee extends Plugin implements Listener {

    private static final int DEFAULT_PORT = 35353;

    private volatile boolean masterStarted;
    private volatile boolean balanceNodes = true;
    /** Players that asked for a specific server with /server; bypass balancing once. */
    private final Set<UUID> usingServerCommand = new HashSet<>();

    @Override
    public void onEnable() {
        getProxy().getPluginManager().registerListener(this, this);

        Map<String, Object> config = loadConfig();
        int port = parsePort(config.get("port"));
        balanceNodes = Boolean.parseBoolean(String.valueOf(config.getOrDefault("balanceNodes", "true")));

        Path storage = getDataFolder().toPath().resolve("storage");
        try {
            Files.createDirectories(storage);
            System.setProperty("multiforge.master.storage", storage.toString());
            System.setProperty("multiforge.master.config",
                    getDataFolder().toPath().resolve("multiforge-master.properties").toString());
            MultiForgeMaster.startEmbedded("0.0.0.0", port);
            masterStarted = true;
            getLogger().info("MultiForge master embedded in BungeeCord on port " + port
                    + " (storage " + storage + ")");
        } catch (RuntimeException | IOException e) {
            masterStarted = false;
            getLogger().severe("Could not start the embedded MultiForge master; "
                    + "players will connect without balancing: " + e);
        }
    }

    /**
     * Remembers an explicit {@code /server} request so the next connect is not
     * balanced away from the requested server.
     *
     * @param event chat event
     */
    @EventHandler
    public void onChat(ChatEvent event) {
        if (event.getMessage().startsWith("/server ")
                && event.getSender() instanceof ProxiedPlayer player
                && player.hasPermission("multiforge.directserverconnect")) {
            synchronized (usingServerCommand) {
                usingServerCommand.add(player.getUniqueId());
            }
        }
    }

    /**
     * Balances a connect for a MultiForge backend to the live backend with the
     * lowest average tick time.
     *
     * @param event connect event
     */
    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (usingServerCommand.remove(event.getPlayer().getUniqueId())) {
            // Explicit /server: let the proxy honour it.
            return;
        }
        if (!masterStarted || !balanceNodes) {
            return;
        }
        ServerInfo target = event.getTarget();
        if (ServerConnection.getConnection(target.getName()) == null) {
            // Not a MultiForge backend (hub, lobby, ...): leave the proxy's choice.
            return;
        }

        ServerInfo best = null;
        long lowestTickTime = Long.MAX_VALUE;
        for (ServerInfo info : getProxy().getServers().values()) {
            String name = info.getName();
            ServerConnection connection = ServerConnection.getConnection(name);
            if (connection == null || !ServerConnection.isAlive(name)) {
                continue;
            }
            long average = connection.getTimer().averageInMillis();
            if (average < lowestTickTime) {
                lowestTickTime = average;
                best = info;
            }
        }
        if (best != null) {
            event.setTarget(best);
        }
    }

    /**
     * Loads {@code config.yml}, copying the packaged defaults on first run.
     *
     * @return parsed configuration, or an empty map when unreadable
     */
    private Map<String, Object> loadConfig() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            Files.createDirectories(getDataFolder().toPath());
            if (!Files.isRegularFile(file)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Map<String, Object> parsed = new Yaml().load(reader);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Could not read " + file + "; using defaults: " + e);
        }
        return Map.of();
    }

    /**
     * @param value configured port (a YAML integer)
     * @return a valid port, or the default when the value is unusable
     */
    private int parsePort(Object value) {
        if (value instanceof Number number) {
            int port = number.intValue();
            if (port > 0 && port <= 65535) {
                return port;
            }
        }
        getLogger().warning("Invalid master port '" + value + "'; using " + DEFAULT_PORT);
        return DEFAULT_PORT;
    }
}
