package com.multiforge.master;

import com.multiforge.master.proxy.ProxyServer;
import com.multiforge.master.util.Log;
import com.multiforge.protocol.MessageBootstrap;
import com.multiforge.protocol.messages.masterbound.MasterBoundMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundProtocol;
import com.multiforge.protocol.messages.serverbound.ServerBoundMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundProtocol;
import io.netty.channel.ChannelFuture;

import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Entry point of the MultiForge master server.
 *
 * <p>The master is the equivalent of {@code MultiPaper-Master} in the original
 * project: a standalone process that owns the authoritative world data and
 * arbitrates chunk/entity ownership. Every Forge server keeps one connection to
 * this process; the master relays ownership decisions and file requests.</p>
 *
 * <p>Start it with {@code java -jar multiforge-master-all.jar [address:]port
 * [proxyPort]}. Optional settings live in {@code multiforge-master.properties}.
 * The process must be started in the directory that contains the shared world
 * folders (for example {@code world}, {@code world_nether}, {@code world_the_end}),
 * exactly like the original master.</p>
 *
 * <p>This class is thread-safe to use as a singleton after construction. The
 * message handlers run on Netty event loop threads.</p>
 */
public class MultiForgeMaster extends MessageBootstrap<MasterBoundMessage, ServerBoundMessage> {

    /** Secret handed to servers; used by the built-in proxy to validate hellos. */
    public static final String SECRET = UUID.randomUUID().toString();

    private static volatile MultiForgeMaster instance;
    private static volatile MasterConfig config;

    /**
     * Starts the master process.
     *
     * @param args optional {@code [address:]port} and optional proxy port
     * @throws InterruptedException if the console reader is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        // The master must keep the JVM alive; Netty threads are daemons otherwise.
        MessageBootstrap.DAEMON = false;

        try {
            config = MasterConfig.load();
        } catch (Exception e) {
            Log.error("Failed to load " + MasterConfig.FILE_NAME + ", using defaults", e);
            System.exit(1);
            return;
        }

        ChunkGenerationManager.configure(config.getGenerationLeaseMillis(), config.getGenerationWaitMillis());
        Log.info("Chunk generation lease: " + ChunkGenerationManager.leaseMillis()
                + "ms lease, " + ChunkGenerationManager.waitMillis() + "ms waiter budget");

        String address = config.getAddress();
        int port = config.getPort();
        int proxyPort = config.getProxyPort();

        // Backwards compatible positional arguments, exactly like MultiPaper.
        if (args.length > 0) {
            String first = args[0];
            if (first.contains(":")) {
                address = first.substring(0, first.indexOf(':'));
                first = first.substring(first.indexOf(':') + 1);
            }
            try {
                port = Integer.parseInt(first);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java -jar multiforge-master-all.jar <[address:]port> [proxy port]");
                System.exit(1);
                return;
            }
        }

        if (args.length > 1) {
            try {
                proxyPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java -jar multiforge-master-all.jar <[address:]port> [proxy port]");
                System.exit(1);
                return;
            }
        }

        setupGracefulShutdown();

        if (proxyPort >= 0) {
            ProxyServer.openServer(proxyPort);
        }

        instance = new MultiForgeMaster(address, port);

        if (new CommandLineInput().run()) {
            System.exit(0);
        }
    }

    /**
     * Creates the master and starts listening. Kept public so integration tests
     * can boot a master on an ephemeral port without spawning a JVM.
     *
     * @param address bind address, {@code null} or empty for all interfaces
     * @param port    port to listen on, {@code 0} picks a free port
     */
    public MultiForgeMaster(String address, int port) {
        super(new MasterBoundProtocol(), new ServerBoundProtocol(),
                channel -> channel.pipeline().addLast(new ServerConnection(channel)));

        ChannelFuture future = (address == null || address.isEmpty())
                ? listenOn(port)
                : listenOn(address, port);

        future.addListener(f -> {
            if (f.cause() != null) {
                Log.error("Failed to bind master to " + (address == null ? "0.0.0.0" : address) + ":" + port, f.cause());
            } else {
                int boundPort = ((java.net.InetSocketAddress) future.channel().localAddress()).getPort();
                Log.info("MultiForge master listening on "
                        + (address == null || address.isEmpty() ? "0.0.0.0" : address) + ":" + boundPort);
            }
        });
    }

    /**
     * @return port the master is listening on, or {@code -1} when the bind did not
     *         finish yet. Used by tests that bind to an ephemeral port.
     */
    public int getBoundPort() {
        io.netty.channel.Channel channel = getServerChannel();
        if (channel == null || !(channel.localAddress() instanceof java.net.InetSocketAddress address)) {
            return -1;
        }
        return address.getPort();
    }

    /** @return the running master, or {@code null} before {@link #main(String[])} started it */
    public static MultiForgeMaster getInstance() {
        return instance;
    }

    /** @return the effective master configuration */
    public static MasterConfig getConfig() {
        return config;
    }

    /**
     * Waits until all queued region file writes have completed. Called by the
     * shutdown hook so that a restart never truncates the shared world.
     */
    private static void awaitAsyncTasks() {
        Log.info("Waiting for async tasks...");
        if (!ForkJoinPool.commonPool().awaitQuiescence(10, TimeUnit.SECONDS)) {
            // Bound the wait: a lingering file task must not keep the process (and
            // the port) alive forever. Chunk data was already flushed by the
            // server-side shutdown protocol.
            Log.warn("Some file tasks are still running after 10s, continuing with shutdown");
        }
    }

    /**
     * Installs a JVM shutdown hook that asks every connected server to save and
     * disconnect before the event loop is stopped.
     */
    private static void setupGracefulShutdown() {
        Thread shutdownListener = new Thread(() -> {
            Log.info("Exiting safely...");
            ServerConnection.shutdown();
            awaitAsyncTasks();
            Log.info("Shutting down event loop...");
            try {
                MessageBootstrap.getEventLoopGroup().shutdownGracefully().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.error("Interrupted while waiting for the event loop to stop", e);
            }
            awaitAsyncTasks();
        }, "MultiForge-Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownListener);
    }
}
