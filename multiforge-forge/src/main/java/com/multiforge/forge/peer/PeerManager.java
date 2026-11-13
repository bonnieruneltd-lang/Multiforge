package com.multiforge.forge.peer;

import com.multiforge.api.ServerInfo;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.RequestChunkPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns every peer connection of this server.
 *
 * <p>Responsibilities, mirroring MultiPaper's {@code MultiPaperConnection} peer
 * half:</p>
 * <ul>
 *   <li>lazily opening a connection to a peer that holds data we need;</li>
 *   <li>deduplicating simultaneous connections (newest {@code nanoTime} wins);</li>
 *   <li>broadcasting block/chat/world updates to all peers;</li>
 *   <li>pumping keep-alives and dropping dead connections.</li>
 * </ul>
 *
 * <p>Thread-safety: the registry is a {@link ConcurrentHashMap}; connection
 * establishment and keep-alive bookkeeping happen on dedicated threads. Never
 * blocks the server thread: {@link #requestChunk} returns a future.</p>
 */
public final class PeerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Peer");

    private final MultiForgeServer server;
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PeerConnection>> connecting = new ConcurrentHashMap<>();
    private final AtomicInteger requestIds = new AtomicInteger(1);

    private EventLoopGroup clientGroup;
    private ScheduledExecutorService scheduler;
    private ExecutorService connector;

    /**
     * @param server owning server
     */
    public PeerManager(MultiForgeServer server) {
        this.server = server;
    }

    /** Creates the client event loop and the keep-alive scheduler. */
    public void start() {
        clientGroup = new NioEventLoopGroup(1, runnable -> {
            Thread thread = new Thread(runnable, "MultiForge-PeerClient");
            thread.setDaemon(true);
            return thread;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MultiForge-PeerKeepAlive");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::tickAll, 10, 10, TimeUnit.SECONDS);
        // Entity updates are produced on the server thread; opening a connection
        // blocks in Bootstrap.connect(...).sync(), so it must not run there.
        connector = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MultiForge-PeerConnector");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Closes every connection and releases the threads. */
    public void shutdown() {
        if (connector != null) {
            connector.shutdownNow();
            connector = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        peers.values().forEach(PeerConnection::close);
        peers.clear();
        connecting.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("MultiForge is shutting down")));
        connecting.clear();
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    /**
     * Sends keep-alives and reaps dead connections. Runs on the scheduler thread.
     */
    private void tickAll() {
        for (PeerConnection connection : peers.values()) {
            try {
                connection.tickKeepAlive();
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to tick peer connection {}", connection.remoteName(), e);
            }
        }
    }

    /**
     * Registers a connection once its hello was received.
     *
     * @param connection new connection
     */
    void register(PeerConnection connection) {
        String name = connection.remoteName();
        if (name == null || name.equals(server.serverName())) {
            connection.close();
            return;
        }
        peers.merge(name, connection, (existing, candidate) -> {
            if (candidate.nanoTime() >= existing.nanoTime()) {
                LOGGER.debug("Replacing peer connection to {} with newer connection", name);
                existing.close();
                return candidate;
            }
            candidate.close();
            return existing;
        });
    }

    /**
     * Removes a connection if it is still the registered one.
     *
     * @param connection closed connection
     */
    void unregister(PeerConnection connection) {
        if (connection.remoteName() != null) {
            peers.remove(connection.remoteName(), connection);
            CompletableFuture<PeerConnection> pending = connecting.get(connection.remoteName());
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new IllegalStateException("Connection closed during handshake"));
            }
        }
    }

    /**
     * Schedules keep-alives for a freshly established connection.
     *
     * @param connection connection that became active
     */
    void scheduleKeepAlive(PeerConnection connection) {
        // The shared scheduler ticks every connection; nothing else to do here.
    }

    /**
     * @return all live peer connections, excluding the local server
     */
    public Collection<PeerConnection> connections() {
        return List.copyOf(peers.values());
    }

    /**
     * @param name server name
     * @return live connection when one exists
     */
    public Optional<PeerConnection> get(String name) {
        PeerConnection connection = peers.get(name);
        return connection != null && connection.isActive() ? Optional.of(connection) : Optional.empty();
    }

    /**
     * Returns a live connection to the named server, connecting if necessary.
     *
     * <p>Address information comes from the master's server list, so the caller
     * only has to know the name.</p>
     *
     * @param serverName target server
     * @return future completed with a handshaken connection
     */
    public CompletableFuture<PeerConnection> connect(String serverName) {
        PeerConnection existing = peers.get(serverName);
        if (existing != null && existing.isActive()) {
            return CompletableFuture.completedFuture(existing);
        }
        Optional<ServerInfo> info = server.master().getServerInfo(serverName);
        if (info.isEmpty() || info.get().port() <= 0) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No peer address known for server " + serverName));
        }
        if (clientGroup == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Peer manager is not running"));
        }

        return connecting.computeIfAbsent(serverName, name -> {
            CompletableFuture<PeerConnection> future = new CompletableFuture<>();
            ServerInfo target = info.get();
            Bootstrap bootstrap = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            PeerConnection connection = new PeerConnection(server, PeerManager.this, channel, true);
                            channel.pipeline()
                                    .addLast("multiforge-frame-decoder", new PeerFrameCodec.Decoder())
                                    .addLast("multiforge-frame-encoder", new PeerFrameCodec.Encoder(connection))
                                    .addLast("multiforge-peer-handler", new SimpleChannelInboundHandler<PeerPacket>() {
                                        @Override
                                        public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
                                            connection.onChannelActive();
                                        }

                                        @Override
                                        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, PeerPacket msg) {
                                            msg.handle(connection);
                                        }

                                        @Override
                                        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                                            LOGGER.debug("Peer connection to {} failed: {}", target.name(), cause.toString());
                                            ctx.close();
                                        }
                                    });
                            channel.closeFuture().addListener(f -> connection.onClosed());
                            connection.ready().whenComplete((ready, error) -> {
                                if (error != null) {
                                    future.completeExceptionally(error);
                                } else {
                                    future.complete(ready);
                                }
                            });
                        }
                    });

            Channel channel;
            try {
                channel = bootstrap.connect(target.host(), target.port()).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
                return future;
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
                return future;
            }
            LOGGER.debug("Connecting to peer {} at {}:{}", target.name(), target.host(), target.port());
            future.orTimeout(server.config().ioTimeoutSeconds(), TimeUnit.SECONDS)
                    .whenComplete((result, error) -> connecting.remove(name));
            return future;
        });
    }

    /**
     * Requests a chunk from a peer, connecting first when needed.
     *
     * @param peer  server name
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     * @return future completed with compressed chunk NBT; empty when unavailable
     */
    public CompletableFuture<byte[]> requestChunk(String peer, String world, int x, int z) {
        return connect(peer).thenCompose(connection -> connection.requestChunk(world, x, z))
                .exceptionally(error -> {
                    LOGGER.debug("Peer chunk request to {} for {},{},{} failed: {}", peer, world, x, z, error.toString());
                    return new byte[0];
                });
    }

    /**
     * Sends a packet to specific peers, connecting on demand.
     *
     * <p>Entity updates are only useful to servers that have the chunk's
     * entities loaded, so they are addressed by name instead of broadcast. A
     * peer connection is opened lazily (the same path a chunk request uses) when
     * no live connection exists. Failures are logged at debug level: the mirror
     * server reads the entity data again the next time the chunk loads, so a
     * missed live update is not data loss.</p>
     *
     * @param names  target server names; this server is ignored
     * @param packet packet to send
     * @return {@code true} when at least one delivery was attempted, {@code false}
     *         when there was no usable peer
     */
    public boolean sendTo(Collection<String> names, PeerPacket packet) {
        boolean queued = false;
        for (String name : names) {
            if (name == null || name.equals(server.serverName())) {
                continue;
            }
            queued = true;
            PeerConnection connection = peers.get(name);
            if (connection != null && connection.isActive()) {
                connection.send(packet);
                continue;
            }
            ExecutorService executor = connector;
            Runnable delivery = () -> connect(name)
                    .thenAccept(target -> target.send(packet))
                    .exceptionally(error -> {
                        LOGGER.debug("Could not deliver {} to peer {}: {}",
                                packet.getClass().getSimpleName(), name, error.toString());
                        return null;
                    });
            if (executor == null) {
                delivery.run();
            } else {
                executor.execute(delivery);
            }
        }
        return queued;
    }

    /**
     * @return {@code true} when at least one peer connection is established
     *
     * <p>Cheap guard for the per-tick broadcast paths: without a connection no
     * packet can be delivered, and building it (world string, NBT, entity packet
     * encoding) is pure overhead. Peers that connect later receive the newest
     * state through the chunk data plane and the entity snapshot on subscription.</p>
     */
    public boolean hasConnections() {
        return !peers.isEmpty();
    }

    /**
     * Broadcasts a packet to every live peer.
     *
     * @param packet packet to send
     */
    public void broadcast(PeerPacket packet) {
        for (PeerConnection connection : peers.values()) {
            connection.send(packet);
        }
    }

    /** @return a fresh request id for chunk requests initiated locally */
    public int nextRequestId() {
        return requestIds.getAndIncrement();
    }
}
