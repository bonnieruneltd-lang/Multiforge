package com.multiforge.forge.connect;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.mixin.ConnectionAddressAccessor;
import com.multiforge.protocol.proxy.BungeeCordForwarding;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies BungeeCord/Velocity legacy IP, UUID and profile forwarding on Forge.
 *
 * <p>Paper has this built into {@code spigot.yml: bungeecord}; Forge has no such
 * switch, so a Forge server behind BungeeCord sees the proxy as the player: bans,
 * whitelist logs and {@code getIpAddress()} would show the proxy, and with
 * {@code online-mode=false} every player would get an offline UUID derived from the
 * name instead of the authenticated UUID, breaking skins and player data across the
 * cluster.</p>
 *
 * <p>The handshake mixin parses the forwarded host name with
 * {@link BungeeCordForwarding} (IP only, IP+UUID or IP+UUID+properties) and replaces
 * the connection address with the reported client address. The login mixin then
 * replaces the offline {@link GameProfile} with the forwarded UUID and profile
 * properties. Both run only when {@code server.bungeeCord=true}.</p>
 *
 * <p><b>Security.</b> Legacy forwarding is not authenticated: anyone who can reach
 * the backend port can claim any IP and UUID. Enable this only when the backend port
 * is reachable solely from the proxy (firewall or private network), exactly like
 * Paper's {@code bungeecord: true}. The backend must run
 * {@code online-mode=false}; otherwise vanilla authentication overwrites the
 * forwarded UUID. Velocity's modern forwarding (login plugin channel + secret) is
 * not implemented; configure Velocity for legacy {@code bungeecord} forwarding.</p>
 *
 * <p>Threading: handshake and login handlers run on Netty threads. The pending map
 * is concurrent and connection identity based; no world state is touched. The UUID
 * is applied before the player is created, so the server thread later sees the
 * forwarded identity.</p>
 */
public final class BungeeCordSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/BungeeCord");

    private static final Gson GSON = new Gson();

    /**
     * Raw host name captured while the handshake packet is decoded.
     *
     * <p>Forge's {@code ClientIntentionPacket} constructor strips everything after the
     * first NUL before the packet reaches {@code handleIntention}, so the forwarding data
     * must be captured at the {@code FriendlyByteBuf.readUtf} call site. Packet decoding
     * and the handler invocation for one connection run synchronously on the same event
     * loop thread, so a thread-local is safe and is always consumed/cleared by
     * {@link #onHandshake(Connection, String)}.</p>
     */
    private static final ThreadLocal<String> RAW_HOST = new ThreadLocal<>();

    private final MultiForgeServer server;

    /** Connection -> forwarding data waiting to be applied at login. */
    private final Map<Connection, BungeeCordForwarding.ForwardedData> pending =
            new ConcurrentHashMap<>();

    /**
     * @param server runtime that owns this support
     */
    public BungeeCordSupport(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Parses forwarding data from a handshake and applies the client address.
     *
     * <p>Called from {@code ServerHandshakePacketListenerImpl.handleIntention} before
     * vanilla processes the packet. A host name without forwarding data is a normal
     * direct connection and is ignored.</p>
     *
     * @param connection connection that sent the handshake
     * @param hostName   raw host name from the handshake packet
     */
    public void onHandshake(Connection connection, String hostName) {
        if (connection == null || server.config().velocityModern()) {
            // Modern Velocity forwarding is negotiated in the login phase instead.
            return;
        }
        String raw = RAW_HOST.get();
        RAW_HOST.remove();
        if (raw != null && raw.indexOf(BungeeCordForwarding.SEPARATOR) >= 0) {
            hostName = raw;
        }
        boolean forwarded = hostName != null
                && hostName.indexOf(BungeeCordForwarding.SEPARATOR) >= 0;
        if (!server.config().bungeeCord()) {
            if (forwarded) {
                LOGGER.warn("Received BungeeCord forwarding data from {} but server.bungeeCord=false; "
                        + "ignoring it (enable the option behind a trusted proxy)",
                        connection.getRemoteAddress());
            }
            return;
        }
        if (!forwarded) {
            return;
        }
        Optional<BungeeCordForwarding.ForwardedData> parsed = BungeeCordForwarding.parse(hostName);
        if (parsed.isEmpty()) {
            LOGGER.warn("Rejected malformed BungeeCord forwarding data from {}",
                    connection.getRemoteAddress());
            return;
        }
        BungeeCordForwarding.ForwardedData data = parsed.get();
        SocketAddress remote = connection.getRemoteAddress();
        int port = remote instanceof InetSocketAddress socketAddress ? socketAddress.getPort() : 0;
        try {
            InetAddress address = InetAddress.getByName(data.address());
            ((ConnectionAddressAccessor) connection)
                    .multiforge$setAddress(new InetSocketAddress(address, port));
        } catch (UnknownHostException e) {
            LOGGER.warn("Rejected BungeeCord forwarding address {}: {}", data.address(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            // If the accessor mixin is ever unavailable, keep the UUID/profile forwarding
            // working instead of failing the login; the IP then stays the proxy address.
            LOGGER.warn("Could not apply the forwarded address {}: {}", data.address(), e.toString());
        }
        pending.put(connection, data);
        if (server.config().debugLogging()) {
            LOGGER.info("Forwarded connection from {} (uuid {})", data.address(), data.uuid());
        }
    }

    /**
     * Stores the raw handshake host for the imminent {@code handleIntention} call.
     *
     * @param rawHost host name exactly as it arrived (including forwarding data)
     */
    public void captureRawHost(String rawHost) {
        if (rawHost != null && rawHost.indexOf(BungeeCordForwarding.SEPARATOR) >= 0) {
            RAW_HOST.set(rawHost);
        }
    }

    /**
     * Applies the forwarded UUID and profile properties to the login profile.
     *
     * <p>Called at the end of {@code ServerLoginPacketListenerImpl.handleHello}, when
     * vanilla has created the (offline) profile but before the player is constructed.
     * The profile is replaced on the listener, which vanilla uses in
     * {@code handleAcceptedLogin}. The forwarded properties are validated as JSON;
     * a broken value is logged and ignored, so it can never break the login.</p>
     *
     * @param listener   login listener that owns the profile
     * @param connection connection being logged in
     */
    public void onLogin(ServerLoginPacketListenerImpl listener, Connection connection) {
        if (!server.config().bungeeCord() || connection == null) {
            return;
        }
        BungeeCordForwarding.ForwardedData data = pending.remove(connection);
        if (data == null || listener.gameProfile == null) {
            return;
        }
        GameProfile current = listener.gameProfile;
        if (data.uuid() == null && data.propertiesJson() == null) {
            // IP-only forwarding (MultiForge's built-in proxy): leave the profile to
            // vanilla. Rebuilding it here would use the null UUID that
            // ServerLoginPacketListenerImpl has before handleAcceptedLogin and break
            // the player data file/registry lookup.
            return;
        }
        UUID uuid = current.getId();
        if (data.uuid() != null) {
            try {
                uuid = UUID.fromString(data.uuid());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring invalid forwarded UUID '{}'", data.uuid());
            }
        }
        if (uuid == null) {
            // Vanilla's offline UUID algorithm; a properties-only forwarding entry
            // still needs a complete profile.
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + current.getName())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        GameProfile profile = new GameProfile(uuid, current.getName());
        profile.getProperties().putAll(current.getProperties());
        if (data.propertiesJson() != null) {
            applyProperties(profile, data.propertiesJson());
        }
        listener.gameProfile = profile;
        if (server.config().debugLogging()) {
            LOGGER.info("Applied forwarded profile for {} (uuid {})", profile.getName(), profile.getId());
        }
    }

    /**
     * Parses and copies the forwarded profile properties (texture, cape, ...).
     *
     * @param profile        profile to fill
     * @param propertiesJson JSON array of {@code {name, value, signature}}
     */
    private static void applyProperties(GameProfile profile, String propertiesJson) {
        try {
            Property[] properties = GSON.fromJson(propertiesJson, Property[].class);
            if (properties == null) {
                return;
            }
            for (Property property : properties) {
                if (property != null && property.getName() != null && property.getValue() != null) {
                    profile.getProperties().put(property.getName(), property);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Ignoring malformed forwarded profile properties: {}", e.getMessage());
        }
    }

    /**
     * Forgets forwarding data for a connection that closed before login finished.
     *
     * @param connection closed connection
     */
    public void onDisconnected(Connection connection) {
        RAW_HOST.remove();
        if (connection != null) {
            pending.remove(connection);
        }
    }

    /** Drops pending forwarding data, called when the server stops. */
    public void clear() {
        pending.clear();
    }
}
