package com.multiforge.master;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Configuration for the standalone MultiForge master process.
 *
 * <p>The original MultiPaper master was configured through command line
 * arguments and (when embedded in BungeeCord/Velocity) through the proxy's
 * config file. MultiForge keeps the command line interface but additionally
 * reads a {@code multiforge-master.properties} file, which is friendlier for
 * containerised deployments. Precedence is: command line &gt; system property
 * &gt; properties file &gt; default.</p>
 *
 * <p>This class is immutable after {@link #load()} and therefore safe to share
 * between threads.</p>
 */
public final class MasterConfig {

    /** Default port used by MultiPaper and therefore by MultiForge. */
    public static final int DEFAULT_PORT = 35353;

    /** File name of the optional properties file. */
    public static final String FILE_NAME = "multiforge-master.properties";

    private String address = "";
    private int port = DEFAULT_PORT;
    private int proxyPort = -1;
    private boolean allowMultipleConnections = false;
    private long pingTimeoutMillis = 60_000L;
    private long generationLeaseMillis = 15_000L;
    private long generationWaitMillis = 2_000L;

    private MasterConfig() {
    }

    /**
     * Loads the configuration from the properties file (if present) and applies
     * system property overrides.
     *
     * @return a fully populated, immutable configuration
     * @throws IOException if the properties file exists but cannot be read
     */
    public static MasterConfig load() throws IOException {
        MasterConfig config = new MasterConfig();
        Path file = Path.of(System.getProperty("multiforge.master.config", FILE_NAME));

        if (Files.isRegularFile(file)) {
            config.read(file);
        } else if (!Files.exists(file)) {
            config.writeDefault(file);
        }

        config.address = System.getProperty("multiforge.master.address", config.address);
        config.port = Integer.getInteger("multiforge.master.port", config.port);
        config.proxyPort = Integer.getInteger("multiforge.master.proxyPort", config.proxyPort);
        config.allowMultipleConnections = Boolean.parseBoolean(System.getProperty(
                "multiforge.master.allowMultipleConnections",
                Boolean.toString(config.allowMultipleConnections)));
        config.pingTimeoutMillis = Long.getLong("multiforge.master.pingTimeoutMillis", config.pingTimeoutMillis);
        config.generationLeaseMillis = Long.getLong("multiforge.master.generationLeaseMillis",
                config.generationLeaseMillis);
        config.generationWaitMillis = Long.getLong("multiforge.master.generationWaitMillis",
                config.generationWaitMillis);
        return config;
    }

    /**
     * Reads the supported keys from a properties file. Unknown keys are ignored
     * so that newer configs can be read by older masters.
     *
     * @param file properties file to read
     * @throws IOException if reading fails
     */
    private void read(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        address = properties.getProperty("address", address).trim();
        port = intValue(properties, "port", port);
        proxyPort = intValue(properties, "proxy-port", proxyPort);
        allowMultipleConnections = Boolean.parseBoolean(properties.getProperty(
                "allow-multiple-connections", Boolean.toString(allowMultipleConnections)));
        pingTimeoutMillis = longValue(properties, "ping-timeout-millis", pingTimeoutMillis);
        generationLeaseMillis = longValue(properties, "generation-lease-millis", generationLeaseMillis);
        generationWaitMillis = longValue(properties, "generation-wait-millis", generationWaitMillis);
    }

    /**
     * Writes a commented default configuration next to the master so operators
     * have something to edit on first start.
     *
     * @param file destination file
     * @throws IOException if writing fails
     */
    private void writeDefault(Path file) throws IOException {
        String contents = """
                # MultiForge master configuration
                # Bind address; empty means all interfaces.
                address=
                # Port the Forge servers connect to.
                port=35353
                # Optional built-in TCP load balancing proxy. -1 disables it.
                proxy-port=-1
                # Allow two servers with the same name (only for tests).
                allow-multiple-connections=false
                # A server that has not pinged for this long is considered dead.
                ping-timeout-millis=60000
                # Only one server may generate a never-seen chunk; this is how long
                # the lease may stay incomplete before another server may take over.
                # 0 disables generation leases.
                generation-lease-millis=15000
                # How long a second server waits for the generator's copy before it
                # generates the chunk itself. 0 means "never wait".
                generation-wait-millis=2000
                """;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static int intValue(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** @return bind address, empty string for all interfaces */
    public String getAddress() {
        return address;
    }

    /** @return master port */
    public int getPort() {
        return port;
    }

    /** @return proxy port, or {@code -1} when the built-in proxy is disabled */
    public int getProxyPort() {
        return proxyPort;
    }

    /** @return whether duplicate server names are tolerated */
    public boolean isAllowMultipleConnections() {
        return allowMultipleConnections;
    }

    /** @return time in milliseconds after which a silent server is considered offline */
    public long getPingTimeoutMillis() {
        return pingTimeoutMillis;
    }

    /** @return how long a chunk generation lease may stay incomplete, {@code 0} disables it */
    public long getGenerationLeaseMillis() {
        return generationLeaseMillis;
    }

    /** @return how long a second server waits for another server's chunk generation */
    public long getGenerationWaitMillis() {
        return generationWaitMillis;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "MasterConfig[address=%s, port=%d, proxyPort=%d, allowMultipleConnections=%s, "
                        + "pingTimeout=%dms, generationLease=%dms, generationWait=%dms]",
                address.isEmpty() ? "0.0.0.0" : address, port, proxyPort, allowMultipleConnections,
                pingTimeoutMillis, generationLeaseMillis, generationWaitMillis);
    }
}
