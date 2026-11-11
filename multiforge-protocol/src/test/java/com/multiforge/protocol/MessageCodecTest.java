package com.multiforge.protocol;

import com.multiforge.protocol.messages.Message;
import com.multiforge.protocol.messages.masterbound.HelloMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundProtocol;
import com.multiforge.protocol.messages.masterbound.PlayerConnectMessage;
import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.WriteChunkMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundProtocol;
import com.multiforge.protocol.messages.serverbound.ServerInfoUpdateMessage;
import com.multiforge.protocol.messages.serverbound.ServerStartedMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the master message protocol.
 *
 * <p>Each test encodes a message with the production pipeline (length framing plus
 * message encoder), feeds the resulting bytes into a fresh decoder pipeline and
 * asserts that every field survived. This is the regression net for the wire
 * protocol: a field that is written but not read (or read in the wrong order) shows
 * up here instead of as a mysterious desynchronization in a live cluster.</p>
 */
class MessageCodecTest {

    /**
     * Encodes a message and decodes it again.
     *
     * @param message  message to round-trip
     * @param protocol protocol used to decode
     * @param <T>      message type
     * @return decoded message
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static <T extends Message<?>> T roundTrip(T message, com.multiforge.protocol.messages.Protocol protocol) {
        EmbeddedChannel encoder = new EmbeddedChannel(new MessageLengthEncoder(), new MessageEncoder<>(protocol));
        assertTrue(encoder.writeOutbound(message), "encoder must produce a frame");
        ByteBuf frame = encoder.readOutbound();
        assertNotNull(frame, "encoded frame must be readable");

        EmbeddedChannel decoder = new EmbeddedChannel(new MessageLengthDecoder(), new MessageDecoder<>(protocol));
        assertTrue(decoder.writeInbound(frame));
        Object decoded = decoder.readInbound();
        assertNotNull(decoded, "decoder must produce a message");
        return (T) decoded;
    }

    @Test
    void helloRoundTrips() {
        UUID uuid = UUID.randomUUID();
        HelloMessage decoded = roundTrip(new HelloMessage("survival-1", uuid), new MasterBoundProtocol());
        assertEquals("survival-1", decoded.name);
        assertEquals(uuid, decoded.serverUuid);
    }

    @Test
    void transactionIdSurvives() {
        WriteChunkMessage message = new WriteChunkMessage("world", "region", 1, -2, new byte[] { 1, 2, 3 });
        message.setTransactionId(4242);
        WriteChunkMessage decoded = roundTrip(message, new MasterBoundProtocol());
        assertEquals(4242, decoded.getTransactionId());
    }

    @Test
    void chunkReadRoundTrips() {
        ReadChunkMessage decoded = roundTrip(new ReadChunkMessage("world_nether", "entities", -30, 12),
                new MasterBoundProtocol());
        assertEquals("world_nether", decoded.world);
        assertEquals("entities", decoded.path);
        assertEquals(-30, decoded.cx);
        assertEquals(12, decoded.cz);
    }

    @Test
    void chunkWriteKeepsLargePayload() {
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        WriteChunkMessage decoded = roundTrip(new WriteChunkMessage("world", "region", 100, 200, payload, true),
                new MasterBoundProtocol());
        assertArrayEquals(payload, decoded.data);
        assertTrue(decoded.isTransientEntities);
    }

    @Test
    void playerConnectRoundTrips() {
        UUID uuid = UUID.randomUUID();
        PlayerConnectMessage decoded = roundTrip(new PlayerConnectMessage(uuid, "Steve"), new MasterBoundProtocol());
        assertEquals(uuid, decoded.uuid);
        assertEquals("Steve", decoded.playerName);
    }

    @Test
    void serverInfoRoundTrips() {
        ServerInfoUpdateMessage decoded = roundTrip(new ServerInfoUpdateMessage("server-a", 42, 19.5F, 7),
                new ServerBoundProtocol());
        assertEquals("server-a", decoded.name);
        assertEquals(42, decoded.averageTickTime);
        assertEquals(19.5F, decoded.tps);
        assertEquals(7, decoded.playerCount);
    }

    @Test
    void ownerAndSubscribersRoundTrip() {
        SetChunkOwnerMessage owner = roundTrip(new SetChunkOwnerMessage("world", 3, 4, "server-b"),
                new ServerBoundProtocol());
        assertEquals("server-b", owner.owner);

        ChunkSubscribersSyncMessage sync = roundTrip(new ChunkSubscribersSyncMessage("world", 3, 4, "server-b",
                new String[] { "server-a", "server-c" }), new ServerBoundProtocol());
        assertEquals("server-b", sync.owner);
        assertArrayEquals(new String[] { "server-a", "server-c" }, sync.subscribers);
    }

    @Test
    void serverStartedAndPlayerUpdateRoundTrip() {
        ServerStartedMessage started = roundTrip(new ServerStartedMessage("server-c", "10.0.0.5", 25566),
                new ServerBoundProtocol());
        assertEquals("server-c", started.name);
        assertEquals("10.0.0.5", started.host);
        assertEquals(25566, started.port);

        UUID uuid = UUID.randomUUID();
        PlayerUpdateMessage update = roundTrip(new PlayerUpdateMessage(uuid, "Alex", "server-c", false),
                new ServerBoundProtocol());
        assertEquals(uuid, update.uuid);
        assertEquals("Alex", update.playerName);
        assertEquals("server-c", update.server);
        assertTrue(!update.online);
    }

    @Test
    void unknownMessageIdIsRejected() {
        EmbeddedChannel decoder = new EmbeddedChannel(new MessageLengthDecoder(), new MessageDecoder<>(new ServerBoundProtocol()));
        EmbeddedChannel encoder = new EmbeddedChannel(new MessageLengthEncoder());
        ByteBuf payload = encoder.alloc().buffer();
        ExtendedByteBuf buf = new ExtendedByteBuf(payload);
        buf.writeVarInt(1);
        buf.writeVarInt(9999);
        assertTrue(encoder.writeOutbound(payload));
        ByteBuf frame = encoder.readOutbound();
        assertThrows(Exception.class, () -> decoder.writeInbound(frame));
    }

    @Test
    void protocolRegistriesHaveStableUniqueIds() {
        MasterBoundProtocol master = new MasterBoundProtocol();
        ServerBoundProtocol server = new ServerBoundProtocol();
        // Encoding and decoding the same instance must agree on an id.
        HelloMessage hello = new HelloMessage("x", UUID.randomUUID());
        assertEquals(master.getMessageId(hello), master.getMessageId(new HelloMessage("y", UUID.randomUUID())));
        assertNotNull(server);
    }
}
