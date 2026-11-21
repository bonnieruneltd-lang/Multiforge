package com.multiforge.master.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Minimal Prometheus text endpoint for the master process.
 *
 * <p>The master previously had no way to be observed except its log and a manual
 * console. Operators running a cluster want the usual counters in their existing
 * monitoring stack (server count, players, owned/subscribed chunks, outstanding
 * entity writes and the entity-id counter). This class exposes them as
 * Prometheus text format on {@code /metrics}, using the JDK's built-in HTTP
 * server so the master stays dependency free.</p>
 *
 * <p>The endpoint is opt-in: {@link com.multiforge.master.MasterConfig} disables
 * it with a negative port (the default), so an existing deployment never opens a
 * new listening socket unless it asks for one. The snapshot supplier is called
 * once per scrape; every value must therefore be a cheap, lock-free or
 * briefly-synchronized read. The HTTP server runs on its own daemon threads, so
 * it neither blocks Netty event loops nor keeps the JVM alive after the master
 * shut down.</p>
 *
 * <p>This class is thread-safe: {@link HttpServer} handles one exchange per
 * thread and the supplier is expected to be thread-safe as well.</p>
 */
public final class MetricsServer implements AutoCloseable {

    /** Path that returns the Prometheus text exposition. */
    public static final String PATH = "/metrics";

    private final HttpServer server;
    private final Supplier<Snapshot> snapshotSupplier;

    /**
     * Creates and starts the endpoint.
     *
     * @param address bind address, empty or {@code null} for all interfaces
     * @param port    TCP port, {@code 0} picks a free port (used by tests)
     * @param snapshotSupplier snapshot of the master state, called once per scrape
     * @throws IOException if the address cannot be bound
     */
    public MetricsServer(String address, int port, Supplier<Snapshot> snapshotSupplier) throws IOException {
        this.snapshotSupplier = snapshotSupplier;
        InetSocketAddress bind = (address == null || address.isEmpty())
                ? new InetSocketAddress(port)
                : new InetSocketAddress(address, port);
        this.server = HttpServer.create(bind, 0);
        this.server.createContext(PATH, this::handleMetrics);
        this.server.setExecutor(command -> {
            Thread thread = new Thread(command, "MultiForge-Metrics");
            thread.setDaemon(true);
            thread.start();
        });
        this.server.start();
    }

    /**
     * @return the port the endpoint is listening on
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Handles one {@code GET /metrics} request.
     *
     * @param exchange HTTP exchange
     * @throws IOException if the response cannot be written
     */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "method not allowed\n");
                return;
            }
            byte[] body;
            try {
                body = format(snapshotSupplier.get()).getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                // A broken counter must not kill the scrape loop with a stack trace
                // per request; answer 500 so the monitoring system can alert on it.
                Log.error("Could not build the metrics snapshot", e);
                send(exchange, 500, "metrics unavailable\n");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * @param exchange exchange to answer
     * @param status   HTTP status code
     * @param body     plain-text body
     * @throws IOException if the response cannot be written
     */
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Renders a snapshot in the Prometheus text exposition format.
     *
     * <p>Kept static and side-effect free so the format can be unit tested without
     * opening a socket.</p>
     *
     * @param snapshot values to render
     * @return exposition text, one metric family per value
     */
    public static String format(Snapshot snapshot) {
        StringBuilder out = new StringBuilder(1024);
        append(out, "multiforge_uptime_seconds", "gauge",
                "Seconds since the master process started.", snapshot.uptimeSeconds());
        append(out, "multiforge_servers_online", "gauge",
                "Forge servers currently connected and pinging.", snapshot.serversOnline());
        append(out, "multiforge_players_online", "gauge",
                "Players currently online across all servers.", snapshot.playersOnline());
        append(out, "multiforge_chunks_owned", "gauge",
                "Chunks that currently have an owner.", snapshot.chunksOwned());
        append(out, "multiforge_chunks_subscribed", "gauge",
                "Chunks that currently have at least one subscriber.", snapshot.chunksSubscribed());
        append(out, "multiforge_entity_writes_pending", "gauge",
                "Entity chunk writes that peers are still waiting for.", snapshot.pendingEntityWrites());
        append(out, "multiforge_next_entity_id", "gauge",
                "Next entity id the master will hand out, or -1 before the first block.", snapshot.nextEntityId());
        return out.toString();
    }

    /**
     * Appends one gauge family.
     *
     * @param out    destination
     * @param name   metric name
     * @param type   Prometheus metric type
     * @param help   help text
     * @param value  value to render
     */
    private static void append(StringBuilder out, String name, String type, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append(' ');
        out.append(String.format(Locale.ROOT, "%d", value));
        out.append('\n');
    }

    /** Stops the endpoint and releases the port. */
    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * Immutable set of values exposed by one scrape.
     *
     * @param uptimeSeconds        seconds since the master started
     * @param serversOnline        connected and pinging Forge servers
     * @param playersOnline        players across all servers
     * @param chunksOwned          chunks with an owner
     * @param chunksSubscribed     chunks with at least one subscriber
     * @param pendingEntityWrites  entity writes peers are waiting for
     * @param nextEntityId         next entity id, {@code -1} before the first block
     */
    public record Snapshot(long uptimeSeconds, int serversOnline, int playersOnline,
                           int chunksOwned, int chunksSubscribed, int pendingEntityWrites,
                           long nextEntityId) {
    }
}
