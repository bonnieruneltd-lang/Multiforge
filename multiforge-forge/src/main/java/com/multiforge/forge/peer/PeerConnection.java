package com.multiforge.forge.peer;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.HelloPacket;
import com.multiforge.forge.peer.packet.KeepAlivePacket;
import com.multiforge.forge.peer.packet.RequestChunkPacket;
import com.multiforge.forge.peer.packet.SendChunkPacket;
import com.multiforge.forge.peer.packet.SetCompressionPacket;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One live server-to-server connection.
 *
 * <p>MultiPaper calls this {@code ExternalServerConnection}: a raw Netty channel
 * between two game servers over which chunk data and entity/block updates flow.
 * A connection is either outgoing (this server initiated it because it needed
 * data) or incoming (another server needed data from us). Both directions use the
 * same pipeline.</p>
 *
 * <p>Threading: writes are thread-safe (Netty serializes them on the event loop).
 * Reads are delivered on the event loop; world mutations are marshalled to the
 * server thread by the packet handlers. Mutable fields are volatile because the
 * master connection thread also inspects them when broadcasting.</p>
 */
public final class PeerConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Peer");

    /** Time without any packet after which the connection is considered dead. */
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;

    private final MultiForgeServer server;
    private final PeerManager manager;
    private final Channel channel;
    private final boolean outbound;
    private final long nanoTime = System.nanoTime();
    private final CompletableFuture<PeerConnection> ready = new CompletableFuture<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<byte[]>> pendingChunkRequests = new ConcurrentHashMap<>();
    /** Set while a coalesced flush is queued on the event loop. */
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    private volatile String remoteName;
    private volatile long lastReceived = System.currentTimeMillis();
    private volatile boolean compressionEnabled;
    private volatile boolean helloReceived;
    private volatile boolean closing;

    /**
     * @param server   owning MultiForge server
     * @param manager  manager that owns this connection
     * @param channel  established Netty channel
     * @param outbound {@code true} when this server opened the connection
     */
    public PeerConnection(MultiForgeServer server, PeerManager manager, Channel channel, boolean outbound) {
        this.server = server;
        this.manager = manager;
        this.channel = channel;
        this.outbound = outbound;
    }

    /** @return the owning server */
    public MultiForgeServer server() {
        return server;
    }

    /** @return channel used by this connection */
    public Channel channel() {
        return channel;
    }

    /** @return name announced by the remote side, {@code null} before the hello */
    public String remoteName() {
        return remoteName;
    }

    /** @return {@link System#nanoTime()} captured when the connection was created */
    public long nanoTime() {
        return nanoTime;
    }

    /** @return whether zlib compression is active for outgoing frames */
    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    /** @return configured compression threshold in bytes */
    public int compressionThreshold() {
        return server.config().compressionThreshold();
    }

    /** @return future completed once both sides exchanged hellos */
    public CompletableFuture<PeerConnection> ready() {
        return ready;
    }

    /** @return {@code true} while the channel is open */
    public boolean isActive() {
        return channel.isActive() && !closing;
    }

    /**
     * Sends a packet. Silently drops it when the channel already closed, because
     * every caller has a fallback path (master read or local file) and a failed
     * broadcast must not break the caller's tick.
     *
     * <p>Writes are coalesced: {@code writeAndFlush} performed one socket flush
     * per packet, and entity mirroring emits one packet per tracked entity per
     * tick. The write is queued and a single flush runs on the event loop after
     * the writes already queued there, so latency stays in the sub-millisecond
     * range while syscalls drop to at most one per event-loop burst.</p>
     *
     * @param packet packet to send
     */
    public void send(PeerPacket packet) {
        if (!isActive()) {
            return;
        }
        channel.write(packet, channel.voidPromise());
        scheduleFlush();
    }

    /**
     * Queues one flush on the event loop unless one is already queued.
     *
     * <p>The flag is cleared inside the event-loop task, so a write that arrives
     * between the flush and the flag reset simply schedules the next flush.</p>
     */
    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            try {
                channel.eventLoop().execute(() -> {
                    flushScheduled.set(false);
                    channel.flush();
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Event loop already shutting down; the channel is closing anyway.
                flushScheduled.set(false);
            }
        }
    }

    /**
     * Called when the channel becomes active. Outgoing connections announce
     * themselves immediately; incoming connections wait for the peer's hello.
     */
    public void onChannelActive() {
        if (outbound) {
            sendHello();
        }
        manager.scheduleKeepAlive(this);
    }

    private void sendHello() {
        int flags = server.config().compressionThreshold() > 0 ? HelloPacket.ZLIB_COMPRESSION : 0;
        send(new HelloPacket(server.serverName(), nanoTime, flags));
    }

    /**
     * Handles the peer's hello: learns its name, answers when we accepted the
     * connection and negotiates compression.
     *
     * @param packet hello packet
     */
    public void onHello(HelloPacket packet) {
        lastReceived = System.currentTimeMillis();
        remoteName = packet.serverName;

        if (helloReceived) {
            // Duplicate hello (both sides connected at the same time); keep the
            // connection, the manager resolves the duplicate by nanoTime.
            return;
        }
        helloReceived = true;

        if (!outbound) {
            // We accepted this connection, so we owe the peer a hello and decide
            // whether compression is used for the session.
            sendHello();
            if (server.config().compressionThreshold() > 0
                    && (packet.supportedCompression & HelloPacket.ZLIB_COMPRESSION) != 0
                    && !isLoopback()) {
                channel.writeAndFlush(new SetCompressionPacket(SetCompressionPacket.ZLIB))
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                compressionEnabled = true;
                                LOGGER.debug("Peer compression enabled with {}", remoteName);
                            }
                        });
            }
        }

        manager.register(this);
        ready.complete(this);
    }

    private boolean isLoopback() {
        return channel.remoteAddress() instanceof java.net.InetSocketAddress address
                && address.getAddress() != null
                && address.getAddress().isLoopbackAddress();
    }

    /**
     * Enables compression after the peer requested it.
     *
     * @param type negotiated scheme, {@link SetCompressionPacket#NONE} disables it
     */
    public void enableCompression(int type) {
        compressionEnabled = type == SetCompressionPacket.ZLIB;
        LOGGER.debug("Peer compression {} for {}", compressionEnabled ? "enabled" : "disabled", remoteName);
    }

    /**
     * Handles a keep alive. Only the receive timestamp is tracked; each side sends
     * its own probes, so answering would double the traffic.
     *
     * @param timestamp remote timestamp
     */
    public void onKeepAlive(long timestamp) {
        lastReceived = System.currentTimeMillis();
    }

    /**
     * Called periodically by the manager: sends a keep alive when idle and closes
     * the connection when the peer stayed silent for too long.
     */
    void tickKeepAlive() {
        long now = System.currentTimeMillis();
        if (now - lastReceived > IDLE_TIMEOUT_MILLIS) {
            LOGGER.info("Peer {} timed out after {} ms, closing connection", remoteName, now - lastReceived);
            close();
            return;
        }
        send(new KeepAlivePacket(now));
    }

    /**
     * Requests a chunk from this peer.
     *
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     * @return future completed with the compressed chunk NBT, or empty when the
     *         peer no longer has the chunk
     */
    public CompletableFuture<byte[]> requestChunk(String world, int x, int z) {
        if (!isActive()) {
            return CompletableFuture.completedFuture(new byte[0]);
        }
        int requestId = nextRequestId.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingChunkRequests.put(requestId, future);
        send(new RequestChunkPacket(requestId, world, x, z));
        return future.orTimeout(server.config().ioTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((data, error) -> pendingChunkRequests.remove(requestId));
    }

    /**
     * Handles an incoming chunk request by delegating to the chunk manager, which
     * serializes loaded chunks on the server thread.
     *
     * @param packet request
     */
    public void onRequestChunk(RequestChunkPacket packet) {
        lastReceived = System.currentTimeMillis();
        server.chunks().respondToChunkRequest(this, packet);
    }

    /**
     * Completes a pending request.
     *
     * @param packet response
     */
    public void onSendChunk(SendChunkPacket packet) {
        lastReceived = System.currentTimeMillis();
        CompletableFuture<byte[]> future = pendingChunkRequests.remove(packet.requestId);
        if (future != null) {
            future.complete(packet.data);
        }
    }

    /**
     * Called when the channel closes: fails pending requests so callers can fall
     * back and removes the connection from the manager.
     */
    public void onClosed() {
        closing = true;
        lastReceived = 0;
        pendingChunkRequests.forEach((id, future) -> future.complete(new byte[0]));
        pendingChunkRequests.clear();
        if (!ready.isDone()) {
            ready.completeExceptionally(new IllegalStateException("Peer connection closed before hello"));
        }
        manager.unregister(this);
    }

    /** Closes the connection, running the cleanup callback on the event loop. */
    public void close() {
        closing = true;
        channel.close();
    }
}
