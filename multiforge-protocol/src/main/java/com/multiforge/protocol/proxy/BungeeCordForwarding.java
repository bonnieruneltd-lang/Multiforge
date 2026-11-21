package com.multiforge.protocol.proxy;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Parses the legacy BungeeCord handshake forwarding format.
 *
 * <p>When {@code ip_forward} (BungeeCord) or {@code bungeecord: true} (Velocity
 * legacy mode) is enabled, the proxy appends data to the host name in the
 * handshake packet, separated by NUL characters:</p>
 *
 * <pre>
 *   host \0 address [ \0 uuid [ \0 properties-json ] ]
 * </pre>
 *
 * <p>Paper's {@code spigot.yml: bungeecord} support, which MultiPaper builds on,
 * accepts three shapes: IP only (its own built-in proxy sends this because it does
 * not authenticate players), IP plus UUID, and IP plus UUID plus the player's
 * profile properties (textures). This class parses all three and rejects anything
 * that does not start with a literal IP address, so a malicious client cannot make
 * the server resolve an attacker-controlled hostname or use an arbitrary
 * {@code SocketAddress}.</p>
 *
 * <p>The class is deliberately Minecraft-free so it can be unit tested and shared
 * by the Forge handshake/login mixins. UUID and property validation happens in the
 * caller, which owns the authlib types and the logging policy.</p>
 */
public final class BungeeCordForwarding {

    /** Separator used by BungeeCord and Velocity legacy forwarding. */
    public static final char SEPARATOR = '\u0000';

    private BungeeCordForwarding() {
    }

    /**
     * Parses a handshake host name.
     *
     * @param hostName host name exactly as it arrived in the handshake packet
     * @return forwarded data, or empty when the host name has no (valid) forwarding
     *         data and the connection should be treated as a direct connection
     */
    public static Optional<ForwardedData> parse(String hostName) {
        if (hostName == null || hostName.indexOf(SEPARATOR) < 0) {
            return Optional.empty();
        }
        String[] parts = hostName.split("\u0000", -1);
        if (parts.length < 2 || parts.length > 4) {
            return Optional.empty();
        }
        String host = parts[0];
        String address = parts[1];
        if (host.isEmpty() || !isIpLiteral(address)) {
            return Optional.empty();
        }
        String uuid = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        String properties = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null;
        return Optional.of(new ForwardedData(host, address, uuid, properties));
    }

    /**
     * Checks that a value is an IP literal and not a host name.
     *
     * <p>Only IPv4 digits/dots and IPv6 hex/colon/dot characters are considered, so
     * {@link InetAddress#getByName(String)} can never perform a DNS lookup for an
     * attacker-supplied value.</p>
     *
     * @param value value from the forwarding data
     * @return {@code true} when the value is a literal IPv4 or IPv6 address
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return false;
        }
        boolean ipv6 = value.indexOf(':') >= 0;
        String allowed = ipv6 ? "[0-9a-fA-F:.]+" : "[0-9.]+";
        if (!value.matches(allowed)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return ipv6 ? address instanceof Inet6Address : address instanceof Inet4Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Forwarded handshake data.
     *
     * @param host           original host name with the forwarding suffix removed
     * @param address        client IP reported by the proxy
     * @param uuid           authenticated UUID, {@code null} when only the IP was sent
     * @param propertiesJson profile properties JSON, {@code null} when absent
     */
    public record ForwardedData(String host, String address, String uuid, String propertiesJson) {
    }
}
