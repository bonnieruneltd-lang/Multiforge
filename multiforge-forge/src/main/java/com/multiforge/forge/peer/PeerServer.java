package com.multiforge.forge.peer;

import com.multiforge.forge.MultiForgeServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

/**
 * Listens for peer connections from other MultiForge servers.
 *
 * <p>MultiPaper opens a listening socket per game server (the port is announced
 * to the master with {@code StartMessage}) so peers can fetch chunks directly
 * instead of through the master. This class owns that socket.</p>
 *
 * <p>Threading: one dedicated Netty event loop with daemon threads. All world
 * access happens through {@code MultiForgeServer.execute}, never on these
 * threads. The server socket is closed on shutdown by {@link #stop()}.</p>
 */
public final class PeerServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Peer");

    private final MultiForgeServer server;
    private final int requestedPort;

    private EventLoopGroup group;
    private Channel serverChannel;
    private int boundPort;

    /**
     * @param server        owning MultiForge server
     * @param requestedPort port from the config; {@code 0} selects a free port
     */
    public PeerServer(MultiForgeServer server, int requestedPort) {
        this.server = server;
        this.requestedPort = requestedPort;
    }

    /**
     * Binds the listening socket.
     *
     * @return the port that was actually bound
     * @throws IllegalStateException when binding fails
     */
    public int start() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "MultiForge-PeerListener");
            thread.setDaemon(true);
            return thread;
        };
        group = new NioEventLoopGroup(1, factory);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        PeerConnection connection = new PeerConnection(server, server.peers(), channel, false);
                        channel.pipeline()
                                .addLast("multiforge-frame-decoder", new PeerFrameCodec.Decoder())
                                .addLast("multiforge-frame-encoder", new PeerFrameCodec.Encoder(connection))
                                .addLast("multiforge-peer-handler", new Handler(connection));
                        channel.closeFuture().addListener(future -> connection.onClosed());
                    }
                });

        try {
            serverChannel = bootstrap.bind(requestedPort).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding peer port", e);
        }
        boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        LOGGER.info("Peer server listening on port {}", boundPort);
        return boundPort;
    }

    /** @return the bound port, or {@code -1} before {@link #start()} */
    public int getBoundPort() {
        return boundPort;
    }

    /** Closes the listening socket and releases the event loop. */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }

    /** Inbound handler: dispatches packets onto their handler methods. */
    private static final class Handler extends SimpleChannelInboundHandler<PeerPacket> {

        private final PeerConnection connection;

        private Handler(PeerConnection connection) {
            this.connection = connection;
        }

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
            if (ctx.channel().isOpen()) {
                LOGGER.warn("Peer connection error ({}): {}", connection.remoteName(), cause.toString());
            }
            ctx.close();
        }
    }
}
