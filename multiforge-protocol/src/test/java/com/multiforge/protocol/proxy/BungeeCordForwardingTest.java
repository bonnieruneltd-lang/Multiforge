package com.multiforge.protocol.proxy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the BungeeCord/Velocity legacy forwarding parser.
 *
 * <p>The parser is the trust boundary for {@code server.bungeeCord=true}: everything
 * after the NUL separator is attacker-controlled unless the backend port is
 * firewalled to the proxy. These tests pin the accepted shapes (IP only, IP+UUID,
 * IP+UUID+properties), the host name extraction and the rejection of malformed or
 * non-literal addresses, which is what prevents a DNS lookup or an arbitrary
 * {@code SocketAddress}.</p>
 */
class BungeeCordForwardingTest {

    @Test
    void ipOnlyForwardingIsAccepted() {
        Optional<BungeeCordForwarding.ForwardedData> parsed =
                BungeeCordForwarding.parse("mc.example.org\u0000192.168.0.10");
        assertTrue(parsed.isPresent());
        assertEquals("mc.example.org", parsed.get().host());
        assertEquals("192.168.0.10", parsed.get().address());
        assertEquals(null, parsed.get().uuid());
        assertEquals(null, parsed.get().propertiesJson());
    }

    @Test
    void ipUuidAndPropertiesAreAccepted() {
        String properties = "[{\"name\":\"textures\",\"value\":\"abc\",\"signature\":\"def\"}]";
        Optional<BungeeCordForwarding.ForwardedData> parsed = BungeeCordForwarding.parse(
                "play.example.org\u00002001:db8::1\u000012345678-1234-1234-1234-123456789abc\u0000"
                        + properties);
        assertTrue(parsed.isPresent());
        assertEquals("play.example.org", parsed.get().host());
        assertEquals("2001:db8::1", parsed.get().address());
        assertEquals("12345678-1234-1234-1234-123456789abc", parsed.get().uuid());
        assertEquals(properties, parsed.get().propertiesJson());
    }

    @Test
    void directConnectionsHaveNoForwarding() {
        assertTrue(BungeeCordForwarding.parse("localhost").isEmpty());
        assertTrue(BungeeCordForwarding.parse("").isEmpty());
        assertTrue(BungeeCordForwarding.parse(null).isEmpty());
    }

    @Test
    void malformedForwardingIsRejected() {
        assertTrue(BungeeCordForwarding.parse("host\u0000").isEmpty(), "empty address");
        assertTrue(BungeeCordForwarding.parse("host\u0000not-an-ip").isEmpty(), "host name as address");
        assertTrue(BungeeCordForwarding.parse("host\u0000999.1.1.1").isEmpty(), "invalid IPv4");
        assertTrue(BungeeCordForwarding.parse("host\u00001.2.3.4\u0000uuid\u0000props\u0000extra")
                .isEmpty(), "too many fields");
        assertTrue(BungeeCordForwarding.parse("\u00001.2.3.4").isEmpty(), "empty host");
    }

    @Test
    void onlyLiteralAddressesCount() {
        assertTrue(BungeeCordForwarding.isIpLiteral("127.0.0.1"));
        assertTrue(BungeeCordForwarding.isIpLiteral("::1"));
        assertTrue(BungeeCordForwarding.isIpLiteral("2001:db8::dead:beef"));
        assertFalse(BungeeCordForwarding.isIpLiteral("example.org"));
        assertFalse(BungeeCordForwarding.isIpLiteral("127.0.0.1.example.org"));
        assertFalse(BungeeCordForwarding.isIpLiteral(""));
        assertFalse(BungeeCordForwarding.isIpLiteral(null));
    }
}
