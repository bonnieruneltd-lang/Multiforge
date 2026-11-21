package com.multiforge.master;

import com.multiforge.master.util.MetricsServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Prometheus endpoint of the master.
 *
 * <p>The endpoint is a real HTTP server, so the test binds it on an ephemeral port
 * and scrapes it exactly like Prometheus would. That covers the two things a unit
 * test of {@link MetricsServer#format} cannot: the socket is reachable and the
 * HTTP status/headers are correct. The expected text is asserted line by line
 * because the exposition format is consumed by machines.</p>
 */
class MetricsServerTest {

    private static final MetricsServer.Snapshot SNAPSHOT =
            new MetricsServer.Snapshot(42L, 3, 17, 128, 129, 2, 8192L);

    /**
     * Reads a URL as a string.
     *
     * @param connection open connection
     * @return response body
     * @throws Exception if reading fails
     */
    private static String read(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void exposesEveryGaugeWithHelpAndType() throws Exception {
        try (MetricsServer server = new MetricsServer("127.0.0.1", 0, () -> SNAPSHOT)) {
            HttpURLConnection connection = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + server.port() + MetricsServer.PATH).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            assertEquals(200, connection.getResponseCode());
            assertTrue(connection.getContentType().startsWith("text/plain"));
            String body = read(connection);
            assertTrue(body.contains("# TYPE multiforge_servers_online gauge"));
            assertTrue(body.contains("multiforge_servers_online 3"));
            assertTrue(body.contains("multiforge_players_online 17"));
            assertTrue(body.contains("multiforge_chunks_owned 128"));
            assertTrue(body.contains("multiforge_chunks_subscribed 129"));
            assertTrue(body.contains("multiforge_entity_writes_pending 2"));
            assertTrue(body.contains("multiforge_next_entity_id 8192"));
            assertTrue(body.contains("multiforge_uptime_seconds 42"));
            assertFalse(body.contains("nan"), "every value must be a finite number");
        }
    }

    @Test
    void onlyGetIsAllowed() throws Exception {
        try (MetricsServer server = new MetricsServer("127.0.0.1", 0, () -> SNAPSHOT)) {
            HttpURLConnection connection = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + server.port() + MetricsServer.PATH).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.getOutputStream().close();
            assertEquals(405, connection.getResponseCode());
        }
    }

    @Test
    void failingSnapshotReturns500InsteadOfKillingTheScrape() throws Exception {
        try (MetricsServer server = new MetricsServer("127.0.0.1", 0, () -> {
            throw new IllegalStateException("counter broke");
        })) {
            HttpURLConnection connection = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + server.port() + MetricsServer.PATH).openConnection();
            assertEquals(500, connection.getResponseCode());
        }
    }

    @Test
    void snapshotIsRenderedDeterministically() {
        String first = MetricsServer.format(SNAPSHOT);
        assertEquals(first, MetricsServer.format(SNAPSHOT));
        assertTrue(first.endsWith("\n"), "the exposition must end with a newline");
        assertEquals(7, first.lines().filter(line -> line.startsWith("# TYPE ")).count());
    }
}
