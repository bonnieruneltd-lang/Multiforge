package com.multiforge.master;

import com.multiforge.protocol.MessageBootstrap;
import com.multiforge.protocol.datastream.InboundDataStream;
import com.multiforge.protocol.messages.masterbound.HelloMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundProtocol;
import com.multiforge.protocol.messages.serverbound.AddChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.AddEntitySubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.DataUpdateMessage;
import com.multiforge.protocol.messages.serverbound.EntitySubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.FileContentMessage;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;
import com.multiforge.protocol.messages.serverbound.RemoveChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.RemoveEntitySubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundDataStreamMessage;
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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Test harness that plays the role of a Forge server speaking to a real master.
 *
 * <p>It uses the production protocol stack ({@link MessageBootstrap},
 * {@link ServerBoundProtocol}, {@link MasterBoundProtocol}) over a real TCP socket
 * so the tests exercise the framing, encoding and master handlers exactly as a
 * game server would. Inbound messages are recorded so tests can assert on
 * broadcasts; {@link #await(Class, Predicate, long)} polls the recording.</p>
 *
 * <p>Threading: messages arrive on the Netty event loop and are appended to a
 * synchronized list; test threads poll. Closing the client closes the channel and
 * therefore triggers the master's disconnect cleanup.</p>
 */
final class TestServerClient extends ServerBoundMessageHandler implements AutoCloseable {

    private final String name;
    private final UUID uuid = UUID.randomUUID();
    private final MessageBootstrap<ServerBoundMessage, MasterBoundMessage> bootstrap;
    private final List<ServerBoundMessage> received = new ArrayList<>();
    private volatile Channel channel;
    private volatile boolean connected;

    TestServerClient(String name) {
        this.name = name;
        this.bootstrap = new MessageBootstrap<>(new ServerBoundProtocol(), new MasterBoundProtocol(),
                socketChannel -> socketChannel.pipeline().addLast(this));
    }

    /**
     * Connects and performs the hello handshake.
     *
     * @param port master port
     * @return this client
     * @throws Exception when the connection or handshake fails
     */
    TestServerClient connect(int port) throws Exception {
        bootstrap.connectTo("127.0.0.1", port).sync().channel();
        long deadline = System.currentTimeMillis() + 5000L;
        while (!connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        if (!connected) {
            throw new IllegalStateException("Client did not connect");
        }
        send(new HelloMessage(name, uuid));
        // The master always answers a hello with the cluster secret.
        await(SetSecretMessage.class, message -> true, 5000L);
        return this;
    }

    String name() {
        return name;
    }

    /**
     * Sends a message without waiting for a reply.
     *
     * @param message message to send
     */
    void send(MasterBoundMessage message) {
        channel.writeAndFlush(message);
    }

    /**
     * Sends a request and returns a typed reply handle.
     *
     * <p>Returning a small handle instead of a {@link CompletableFuture} lets tests
     * write {@code BooleanMessageReply reply = client.request(msg).get()} without
     * specifying the reply type: {@link Reply#get()} infers it from the assignment
     * target.</p>
     *
     * @param message request message
     * @return reply handle
     */
    Reply request(MasterBoundMessage message) {
        CompletableFuture<ServerBoundMessage> future = new CompletableFuture<>();
        setCallback(message, future::complete);
        send(message);
        return new Reply(future);
    }

    /** Reply handle with target-typed {@link #get()}. */
    static final class Reply {
        private final CompletableFuture<ServerBoundMessage> future;

        private Reply(CompletableFuture<ServerBoundMessage> future) {
            this.future = future;
        }

        /**
         * Waits for the reply and casts it to the expected type.
         *
         * @param <T> expected reply type
         * @return the reply
         * @throws Exception when the reply did not arrive within ten seconds
         */
        @SuppressWarnings("unchecked")
        <T extends ServerBoundMessage> T get() throws Exception {
            return (T) future.get(10, TimeUnit.SECONDS);
        }

        /**
         * @return {@code true} when the reply already arrived, for tests that
         *         assert a request is deliberately still pending
         */
        boolean done() {
            return future.isDone();
        }
    }

    /**
     * Waits for a recorded message matching the predicate.
     *
     * @param type      expected type
     * @param predicate additional filter
     * @param timeoutMs timeout in milliseconds
     * @param <T>       expected type
     * @return the matching message
     */
    <T extends ServerBoundMessage> T await(Class<T> type, Predicate<T> predicate, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (received) {
                for (ServerBoundMessage message : received) {
                    if (type.isInstance(message) && predicate.test(type.cast(message))) {
                        return type.cast(message);
                    }
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for " + type.getSimpleName() + " on " + name);
    }

    /**
     * Returns whether a matching message was recorded, waiting at most
     * {@code windowMs} for it. Unlike {@link #await} this never throws, which is
     * what race tests need when a message is expected only by the winner.
     *
     * @param type      expected type
     * @param predicate additional filter
     * @param windowMs  how long to wait
     * @param <T>       expected type
     * @return {@code true} when a match was seen
     */
    <T extends ServerBoundMessage> boolean seen(Class<T> type, Predicate<T> predicate, long windowMs) throws Exception {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (received) {
                for (ServerBoundMessage message : received) {
                    if (type.isInstance(message) && predicate.test(type.cast(message))) {
                        return true;
                    }
                }
            }
            Thread.sleep(10L);
        }
        return false;
    }

    /**
     * Downloads a file and collects the data stream.
     *
     * @param path file path
     * @return future with the file bytes
     */
    CompletableFuture<byte[]> download(String path) throws Exception {
        FileContentMessage content = request(new com.multiforge.protocol.messages.masterbound.DownloadFileMessage(path)).get();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        InboundDataStream stream = getDataStreamManager().createInboundDataStream(channel, content.streamId,
                data -> buffer.write(data, 0, data.length),
                () -> result.complete(buffer.toByteArray()));
        return stream == null ? CompletableFuture.failedFuture(new IllegalStateException("no stream")) : result;
    }

    /** Removes all recorded messages. */
    void clear() {
        synchronized (received) {
            received.clear();
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        connected = true;
    }

    private void record(ServerBoundMessage message) {
        synchronized (received) {
            received.add(message);
        }
    }

    // ------------------------------------------------------------------
    // Inbound handlers (all record; data streams are additionally dispatched)
    // ------------------------------------------------------------------

    @Override
    public void handle(ServerInfoUpdateMessage message) {
        record(message);
    }

    @Override
    public void handle(SetSecretMessage message) {
        record(message);
    }

    @Override
    public void handle(ShutdownMessage message) {
        record(message);
    }

    @Override
    public void handle(ServerChangedChunkStatusMessage message) {
        record(message);
    }

    @Override
    public void handle(FileContentMessage message) {
        record(message);
    }

    @Override
    public void handle(PlayerUpdateMessage message) {
        record(message);
    }

    @Override
    public void handle(SetChunkOwnerMessage message) {
        record(message);
    }

    @Override
    public void handle(ServerStartedMessage message) {
        record(message);
    }

    @Override
    public void handle(DataUpdateMessage message) {
        record(message);
    }

    @Override
    public void handle(AddChunkSubscriberMessage message) {
        record(message);
    }

    @Override
    public void handle(RemoveChunkSubscriberMessage message) {
        record(message);
    }

    @Override
    public void handle(AddEntitySubscriberMessage message) {
        record(message);
    }

    @Override
    public void handle(RemoveEntitySubscriberMessage message) {
        record(message);
    }

    @Override
    public void handle(ChunkSubscribersSyncMessage message) {
        record(message);
    }

    @Override
    public void handle(EntitySubscribersSyncMessage message) {
        record(message);
    }

    /**
     * Records every inbound message and feeds data stream frames to the stream
     * manager.
     *
     * @param message inbound message
     * @return always {@code true}: the test client consumes every message
     */
    @Override
    public boolean onMessage(ServerBoundMessage message) {
        if (message instanceof ServerBoundDataStreamMessage dataStream) {
            getDataStreamManager().handleInboundData(dataStream.streamId, dataStream.data);
        }
        record(message);
        return true;
    }

}
