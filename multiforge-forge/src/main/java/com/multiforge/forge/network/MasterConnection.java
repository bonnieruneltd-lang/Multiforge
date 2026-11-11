package com.multiforge.forge.network;

import com.multiforge.api.ServerInfo;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.MessageBootstrap;
import com.multiforge.protocol.messages.masterbound.HelloMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundProtocol;
import com.multiforge.protocol.messages.masterbound.PingMessage;
import com.multiforge.protocol.messages.serverbound.AddChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.AddEntitySubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.DataUpdateMessage;
import com.multiforge.protocol.messages.serverbound.EntitySubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;
import com.multiforge.protocol.messages.serverbound.RemoveChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.RemoveEntitySubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundMessageHandler;
import com.multiforge.protocol.messages.serverbound.ServerBoundProtocol;
import com.multiforge.protocol.messages.serverbound.ServerChangedChunkStatusMessage;
import com.multiforge.protocol.messages.serverbound.ServerInfoUpdateMessage;
import com.multiforge.protocol.messages.serverbound.ServerStartedMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import com.multiforge.protocol.messages.serverbound.SetSecretMessage;
import com.multiforge.protocol.messages.serverbound.ShutdownMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The single connection from this server to the MultiForge master.
 *
 * <p>Ports {@code MultiPaperConnection} from the original project. It is the
 * control plane of the cluster:</p>
 * <ul>
 *   <li>authenticates with a hello and keeps the session alive with pings;</li>
 *   <li>requests chunk ownership and chunk/entity subscriptions;</li>
 *   <li>reads and writes world data (chunks, level.dat, player data, files);</li>
 *   <li>tracks the cluster server list, TPS and player locations;</li>
 *   <li>reconnects automatically and re-sends requests that were in flight.</li>
 * </ul>
 *
 * <p>Threading: all inbound handlers run on the Netty event loop. They only touch
 * concurrent collections and then dispatch to {@link MultiForgeServer#execute}
 * before any world state is modified. {@link #request} returns a future and never
 * blocks; callers that must block (chunk IO workers) do so on their own thread.</p>
 */
public final class MasterConnection extends ServerBoundMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Master");

    private final MultiForgeServer server;
    private final UUID serverUuid = UUID.randomUUID();
    private final MessageBootstrap<ServerBoundMessage, MasterBoundMessage> bootstrap;
    private final Map<Integer, MasterBoundMessage> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, RemoteServer> servers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MultiForge-MasterConnection");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Channel channel;
    private volatile boolean active;
    private volatile boolean stopping;
    private volatile long lastReply = System.currentTimeMillis();
    private volatile String secret = "";

    /**
     * @param server owning MultiForge server
     */
    public MasterConnection(MultiForgeServer server) {
        this.server = server;
        this.bootstrap = new MessageBootstrap<>(new ServerBoundProtocol(), new MasterBoundProtocol(),
                socketChannel -> socketChannel.pipeline().addLast(this));
    }

    /**
     * Starts connecting and schedules the ping loop. Safe to call once; repeat
     * calls are ignored while a connection attempt is already running.
     */
    public void start() {
        stopping = false;
        scheduler.scheduleAtFixedRate(this::ping, server.config().pingIntervalSeconds(),
                server.config().pingIntervalSeconds(), TimeUnit.SECONDS);
        connect();
    }

    private void connect() {
        if (stopping) {
            return;
        }
        String address = server.config().masterAddress();
        int separator = address.lastIndexOf(':');
        if (separator < 0) {
            LOGGER.error("Invalid master address '{}', expected host:port", address);
            return;
        }
        String host = address.substring(0, separator);
        int port;
        try {
            port = Integer.parseInt(address.substring(separator + 1));
        } catch (NumberFormatException e) {
            LOGGER.error("Invalid master port in '{}'", address);
            return;
        }
        LOGGER.info("Connecting to MultiForge master at {}:{}", host, port);
        bootstrap.connectTo(host, port).addListener(future -> {
            if (!future.isSuccess()) {
                LOGGER.warn("Could not reach master ({}), retrying in {}s",
                        future.cause() == null ? "unknown" : future.cause().toString(),
                        server.config().reconnectDelaySeconds());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (stopping) {
            return;
        }
        scheduler.schedule(this::connect, server.config().reconnectDelaySeconds(), TimeUnit.SECONDS);
    }

    /** @return {@code true} while the master channel is usable */
    public boolean isConnected() {
        Channel current = channel;
        return active && current != null && current.isActive();
    }

    /**
     * @return the Netty channel, or {@code null} while disconnected. Needed to
     *         attach inbound data streams for file downloads.
     */
    public Channel channel() {
        return channel;
    }

    /**
     * @return data stream manager of this connection, used to receive file
     *         contents streamed by the master
     */
    public com.multiforge.protocol.datastream.DataStreamManager<ServerBoundMessage> dataStreams() {
        return getDataStreamManager();
    }

    /** @return master-provided cluster secret (used by the built-in proxy) */
    public String secret() {
        return secret;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        LOGGER.info("Connected to master {}", channel.remoteAddress());
        channel.write(new HelloMessage(server.serverName(), serverUuid));
        // Re-send requests that were in flight when the connection dropped.
        for (MasterBoundMessage pending : pendingRequests.values()) {
            channel.write(pending);
        }
        lastReply = System.currentTimeMillis();
        active = true;
        channel.flush();
        server.onMasterConnected();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        active = false;
        LOGGER.warn("Lost connection to master, scheduling reconnect");
        pendingRequests.clear();
        server.onMasterDisconnected();
        scheduleReconnect();
    }

    /**
     * Sends a keep-alive and detects a dead master. A master that did not reply
     * for three intervals is treated as gone and the channel is closed so the
     * reconnect logic runs.
     */
    private void ping() {
        if (!isConnected()) {
            return;
        }
        long silentFor = System.currentTimeMillis() - lastReply;
        long timeout = server.config().pingIntervalSeconds() * 3000L;
        if (silentFor > timeout) {
            LOGGER.error("Master did not reply for {} ms, reconnecting", silentFor);
            Channel current = channel;
            if (current != null) {
                current.close();
            }
            return;
        }
        send(new PingMessage());
        send(new com.multiforge.protocol.messages.masterbound.WriteTickTimeMessage(
                (long) server.averageTickTimeMillis(), (float) server.tps()));
    }

    /**
     * Sends a fire-and-forget message. Dropped with a warning when disconnected;
     * callers that need delivery must use {@link #request}.
     *
     * @param message message to send
     */
    public void send(MasterBoundMessage message) {
        Channel current = channel;
        if (!isConnected()) {
            LOGGER.debug("Dropping {} because the master connection is down", message.getClass().getSimpleName());
            return;
        }
        current.writeAndFlush(message, current.voidPromise());
    }

    /**
     * Sends a request and completes when any reply arrives.
     *
     * <p>The message is retransmitted after a reconnect, so requests are expected
     * to be idempotent (all master operations are).</p>
     *
     * @param message request message
     * @return future completed with the master's reply
     */
    public CompletableFuture<ServerBoundMessage> request(MasterBoundMessage message) {
        CompletableFuture<ServerBoundMessage> future = new CompletableFuture<>();
        if (!isConnected()) {
            future.completeExceptionally(new IllegalStateException("Not connected to the master"));
            return future;
        }
        setCallback(message, reply -> {
            pendingRequests.remove(message.getTransactionId());
            future.complete(reply);
        });
        pendingRequests.put(message.getTransactionId(), message);
        send(message);
        return future.orTimeout(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    if (error != null) {
                        pendingRequests.remove(message.getTransactionId());
                    }
                });
    }

    /**
     * Sends a request and casts the reply.
     *
     * @param message  request message
     * @param expected expected reply type
     * @param <T>      reply type
     * @return future completed with the typed reply
     */
    public <T extends ServerBoundMessage> CompletableFuture<T> request(MasterBoundMessage message, Class<T> expected) {
        return request(message).thenApply(reply -> {
            if (!expected.isInstance(reply)) {
                throw new IllegalStateException("Expected " + expected.getSimpleName()
                        + " but received " + reply.getClass().getSimpleName());
            }
            return expected.cast(reply);
        });
    }

    /**
     * @param name server name
     * @return last known snapshot of that server
     */
    public Optional<ServerInfo> getServerInfo(String name) {
        RemoteServer remote = servers.get(name);
        return remote == null ? Optional.empty() : Optional.of(remote.snapshot());
    }

    /** @return snapshots of every server the master reported */
    public Collection<ServerInfo> getServers() {
        List<ServerInfo> result = new ArrayList<>(servers.size());
        for (RemoteServer remote : servers.values()) {
            result.add(remote.snapshot());
        }
        return List.copyOf(result);
    }

    /**
     * Stops the connection, closing the channel and the scheduler.
     */
    public void stop() {
        stopping = true;
        active = false;
        Channel current = channel;
        if (current != null) {
            current.close();
            channel = null;
        }
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Inbound master messages
    // ------------------------------------------------------------------

    @Override
    public void handle(ServerInfoUpdateMessage message) {
        lastReply = System.currentTimeMillis();
        servers.computeIfAbsent(message.name, RemoteServer::new)
                .update(message.averageTickTime, message.tps, message.playerCount);
        server.onServerInfoUpdated(message.name);
    }

    @Override
    public void handle(SetSecretMessage message) {
        lastReply = System.currentTimeMillis();
        secret = message.secret;
    }

    @Override
    public void handle(ShutdownMessage message) {
        LOGGER.warn("Master requested shutdown");
        server.requestShutdown("master requested shutdown");
    }

    @Override
    public void handle(ServerChangedChunkStatusMessage message) {
        lastReply = System.currentTimeMillis();
        server.chunks().onRemoteChunkStatus(message.world, message.cx, message.cz, message.status, message.server);
    }

    @Override
    public void handle(FileContentMessage message) {
        lastReply = System.currentTimeMillis();
        server.files().onFileContent(message);
    }

    @Override
    public void handle(PlayerUpdateMessage message) {
        lastReply = System.currentTimeMillis();
        server.players().onPlayerUpdate(message);
    }

    @Override
    public void handle(SetChunkOwnerMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onOwnerChanged(key, message.owner));
    }

    @Override
    public void handle(ServerStartedMessage message) {
        lastReply = System.currentTimeMillis();
        RemoteServer remote = servers.computeIfAbsent(message.name, RemoteServer::new);
        remote.host = message.host;
        remote.port = message.port;
        remote.lastUpdate = System.currentTimeMillis();
        LOGGER.info("Server {} is reachable at {}:{}", message.name, message.host, message.port);
        server.onServerStarted(message.name, message.host, message.port);
    }

    @Override
    public void handle(DataUpdateMessage message) {
        lastReply = System.currentTimeMillis();
        server.files().onDataUpdate(message);
    }

    @Override
    public void handle(AddChunkSubscriberMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onSubscriberAdded(key, message.server));
    }

    @Override
    public void handle(RemoveChunkSubscriberMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onSubscriberRemoved(key, message.server));
    }

    @Override
    public void handle(AddEntitySubscriberMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onEntitySubscriberAdded(key, message.server));
    }

    @Override
    public void handle(RemoveEntitySubscriberMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onEntitySubscriberRemoved(key, message.server));
    }

    @Override
    public void handle(ChunkSubscribersSyncMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onSubscribersSync(key, message.owner, message.subscribers));
    }

    @Override
    public void handle(EntitySubscribersSyncMessage message) {
        lastReply = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(message.world, message.cx, message.cz);
        server.execute(() -> server.chunks().onEntitySubscribersSync(key, message.subscribers));
    }

    /** Mutable cluster view of one remote server. */
    private static final class RemoteServer {
        private final String name;
        private volatile String host;
        private volatile int port = -1;
        private volatile double tps;
        private volatile int averageTickTime;
        private volatile int playerCount;
        private volatile long lastUpdate = System.currentTimeMillis();

        private RemoteServer(String name) {
            this.name = name;
        }

        private void update(int averageTickTime, float tps, int playerCount) {
            this.averageTickTime = averageTickTime;
            this.tps = tps;
            this.playerCount = playerCount;
            this.lastUpdate = System.currentTimeMillis();
        }

        private ServerInfo snapshot() {
            boolean alive = System.currentTimeMillis() - lastUpdate < 15_000L && tps >= 0;
            return new ServerInfo(name, host == null ? "" : host, port, tps, playerCount, alive);
        }
    }
}
