package com.multiforge.forge.connect;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.mixin.ConnectionAddressAccessor;
import com.multiforge.protocol.proxy.BungeeCordForwarding;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements Velocity's modern player-info forwarding on Forge.
 *
 * <p>Modern forwarding does not put anything in the handshake. The backend asks
 * Velocity for the player's details with a login plugin request on
 * {@code velocity:player_info}, and Velocity answers with an HMAC-SHA256 signed blob
 * containing the protocol version, client address, UUID, name and profile properties.
 * The shared secret is Velocity's {@code forwarding.secret}. This is more secure than
 * legacy BungeeCord forwarding (the client never sees or supplies the data) and is the
 * recommended Velocity mode.</p>
 *
 * <p>The request announces forwarding version 1, whose payload is a varint version,
 * address string, UUID, name and the property list. The response is a 32-byte HMAC
 * followed by that payload. A missing or invalid signature disconnects the login; the
 * profile and client address are only applied after verification. Only v1 is requested,
 * so the key/respawn variants (v2-v4) are not negotiated.</p>
 *
 * <p>The vanilla login listener would accept the player on the next tick before the
 * plugin answer arrives, so {@code VelocityLoginMixin} pauses {@code tick} while a
 * request is outstanding and fails the login after the configured IO timeout. The same
 * mixin intercepts the answer and leaves unrelated login plugin channels to Forge.</p>
 *
 * <p>Security: enable only behind a trusted proxy/firewall, exactly like legacy
 * forwarding. Never commit the secret; it is read from the config file.</p>
 */
public final class VelocityForwardingSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Velocity");

    /** Channel Velocity uses for modern forwarding. */
    private static final ResourceLocation CHANNEL =
            new ResourceLocation("velocity", "player_info");

    /** Forwarding protocol version requested from Velocity. */
    private static final int FORWARDING_VERSION = 1;

    /** Upper bound for the property list, so a malformed answer cannot allocate freely. */
    private static final int MAX_PROPERTIES = 1024;

    private final MultiForgeServer server;
    private final AtomicInteger transactionIds = new AtomicInteger();
    private final Map<Connection, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedAboutSecret = new AtomicBoolean();

    /**
     * @param server runtime that owns this support
     */
    public VelocityForwardingSupport(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * @return {@code true} when modern forwarding is enabled with a non-empty secret
     */
    public boolean enabled() {
        return server.config().velocityModern()
                && server.config().velocitySecret() != null
                && !server.config().velocitySecret().isBlank();
    }

    /**
     * Sends the login plugin request that asks Velocity for the player details.
     *
     * @param listener   login listener
     * @param connection connection being logged in
     * @return {@code true} when a request was sent and the login must wait
     */
    public boolean onHello(ServerLoginPacketListenerImpl listener, Connection connection) {
        if (!server.config().velocityModern()) {
            return false;
        }
        if (!enabled()) {
            if (warnedAboutSecret.compareAndSet(false, true)) {
                LOGGER.warn("server.velocityModern is enabled but server.velocitySecret is empty; "
                        + "modern forwarding is disabled");
            }
            return false;
        }
        int transactionId = transactionIds.incrementAndGet();
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer(1));
        data.writeByte(FORWARDING_VERSION);
        pending.put(connection, new Pending(transactionId, System.currentTimeMillis(), listener));
        connection.send(new ClientboundCustomQueryPacket(transactionId, CHANNEL, data));
        return true;
    }


    /**
     * Handles the plugin answer from Velocity.
     *
     * @param listener   login listener
     * @param connection connection being logged in
     * @param packet     answer packet
     * @return {@code true} when this answer belonged to modern forwarding (consumed)
     */
    public boolean onAnswer(ServerLoginPacketListenerImpl listener, Connection connection,
                            ServerboundCustomQueryPacket packet) {
        Pending request = pending.get(connection);
        if (request == null || request.transactionId() != packet.getTransactionId()) {
            return false;
        }
        pending.remove(connection);
        try {
            FriendlyByteBuf answer = packet.getData();
            byte[] bytes = new byte[answer.readableBytes()];
            answer.readBytes(bytes);
            if (bytes.length < 33) {
                fail(listener, "Velocity forwarding answer is too short");
                return true;
            }
            byte[] signature = java.util.Arrays.copyOfRange(bytes, 0, 32);
            byte[] payload = java.util.Arrays.copyOfRange(bytes, 32, bytes.length);
            if (!verify(payload, signature)) {
                fail(listener, "Velocity forwarding signature mismatch");
                return true;
            }
            applyPayload(listener, connection, payload);
        } catch (RuntimeException | GeneralSecurityException e) {
            fail(listener, "Malformed Velocity forwarding answer: " + e.getMessage());
        }
        return true;
    }

    /**
     * Applies a verified forwarding payload to the login session.
     *
     * @param listener   login listener
     * @param connection connection being logged in
     * @param payload    verified payload (version, address, uuid, name, properties)
     */
    private void applyPayload(ServerLoginPacketListenerImpl listener, Connection connection,
                              byte[] payload) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        int version = data.readVarInt();
        if (version != FORWARDING_VERSION) {
            fail(listener, "Unsupported Velocity forwarding version " + version);
            return;
        }
        String address = data.readUtf(64);
        UUID uuid = data.readUUID();
        String name = data.readUtf(16);
        int count = data.readVarInt();
        if (count < 0 || count > MAX_PROPERTIES) {
            fail(listener, "Invalid Velocity property count " + count);
            return;
        }
        GameProfile profile = new GameProfile(uuid, name);
        for (int i = 0; i < count; i++) {
            String propertyName = data.readUtf(64);
            String value = data.readUtf(32767);
            String signature = data.readBoolean() ? data.readUtf(32767) : null;
            profile.getProperties().put(propertyName, new Property(propertyName, value, signature));
        }
        listener.gameProfile = profile;
        applyAddress(connection, address);
        if (server.config().debugLogging()) {
            LOGGER.info("Applied Velocity forwarding for {} (uuid {}, address {})",
                    name, uuid, address);
        }
    }

    /**
     * Replaces the connection address with the verified client address.
     *
     * @param connection connection
     * @param address    forwarded address
     */
    private void applyAddress(Connection connection, String address) {
        if (!BungeeCordForwarding.isIpLiteral(address)) {
            LOGGER.warn("Ignoring invalid Velocity address '{}'", address);
            return;
        }
        SocketAddress remote = connection.getRemoteAddress();
        int port = remote instanceof InetSocketAddress socketAddress ? socketAddress.getPort() : 0;
        try {
            ((ConnectionAddressAccessor) connection)
                    .multiforge$setAddress(new InetSocketAddress(address, port));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not apply the Velocity address {}: {}", address, e.toString());
        }
    }

    /**
     * @param payload   payload covered by the signature
     * @param signature received HMAC
     * @return {@code true} when the HMAC matches the configured secret
     * @throws GeneralSecurityException if HmacSHA256 is unavailable (never in practice)
     */
    private boolean verify(byte[] payload, byte[] signature) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(server.config().velocitySecret()
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return MessageDigest.isEqual(mac.doFinal(payload), signature);
    }

    /**
     * Disconnects a login that cannot be forwarded.
     *
     * @param listener login listener
     * @param reason   diagnostic message
     */
    private static void fail(ServerLoginPacketListenerImpl listener, String reason) {
        LOGGER.warn("Rejecting Velocity login: {}", reason);
        listener.disconnect(Component.literal("Unable to verify player details"));
    }

    /**
     * @param connection connection
     * @return {@code true} while a forwarding answer is still outstanding
     */
    public boolean isPending(Connection connection) {
        return pending.containsKey(connection);
    }

    /**
     * Fails logins whose forwarding answer did not arrive within the IO timeout.
     */
    public void tickTimeouts() {
        long timeout = server.config().ioTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<Connection, Pending> entry : pending.entrySet()) {
            if (now - entry.getValue().startedAt() > timeout) {
                if (pending.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().listener().disconnect(
                            Component.literal("Timed out waiting for Velocity forwarding"));
                }
            }
        }
    }

    /**
     * Forgets a connection that closed before its forwarding answer arrived.
     *
     * @param connection closed connection
     */
    public void onDisconnected(Connection connection) {
        if (connection != null) {
            pending.remove(connection);
        }
    }

    /** Drops all pending requests, called when the server stops. */
    public void clear() {
        pending.clear();
    }

    /**
     * One outstanding forwarding request.
     *
     * @param transactionId plugin message id
     * @param startedAt     wall-clock start, for the timeout
     * @param listener      login listener to disconnect on timeout
     */
    private record Pending(int transactionId, long startedAt,
                           ServerLoginPacketListenerImpl listener) {
    }
}
