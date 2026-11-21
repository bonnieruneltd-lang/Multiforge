package com.multiforge.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the primitives of the wire format.
 *
 * <p>{@link ExtendedByteBuf} is the only place where MultiForge defines how values
 * are laid out on the wire. A regression here breaks every message at once, so the
 * primitives get their own focused tests.</p>
 */
class ExtendedByteBufTest {

    @Test
    void varIntRoundTripsAtBoundaries() {
        int[] values = { 0, 1, 127, 128, 300, 16384, Integer.MAX_VALUE, -1, -2147483648 };
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        for (int value : values) {
            out.writeVarInt(value);
        }
        for (int value : values) {
            assertEquals(value, out.readVarInt());
        }
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void stringsAndUuidsRoundTrip() {
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        UUID uuid = UUID.randomUUID();
        out.writeString("world_the_end");
        out.writeString("");
        out.writeUUID(uuid);
        out.writeBoolean(true);

        assertEquals("world_the_end", out.readString());
        assertEquals("", out.readString());
        assertEquals(uuid, out.readUUID());
        assertTrue(out.readBoolean());
    }

    @Test
    void byteArraysRoundTrip() {
        byte[] data = new byte[70000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        out.writeVarInt(data.length);
        out.writeBytes(data);
        byte[] read = new byte[out.readVarInt()];
        out.readBytes(read);
        org.junit.jupiter.api.Assertions.assertArrayEquals(data, read);
    }

    @Test
    void readByteArrayUsesTheSafeReader() {
        byte[] data = { 1, 2, 3 };
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        out.writeVarInt(data.length);
        out.writeBytes(data);
        org.junit.jupiter.api.Assertions.assertArrayEquals(data, out.readByteArray());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void announcedLengthsAreRejectedBeforeAllocation() {
        // A byte array that claims far more bytes than the frame contains.
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        out.writeVarInt(Integer.MAX_VALUE);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, out::readByteArray);

        // A string with a multi-gigabyte length used to reach Netty's temporary
        // decode buffer before the bounds check, which is the remote OOM that
        // ProtocolFuzzTest caught.
        ByteBuf stringBuffer = Unpooled.buffer();
        ExtendedByteBuf stringOut = new ExtendedByteBuf(stringBuffer);
        stringOut.writeVarInt(Integer.MAX_VALUE);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, stringOut::readString);

        // Negative lengths are invalid for both readers.
        ByteBuf negative = Unpooled.buffer();
        ExtendedByteBuf negativeOut = new ExtendedByteBuf(negative);
        negativeOut.writeVarInt(-1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, negativeOut::readByteArray);
        negativeOut.writeVarInt(-1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, negativeOut::readString);
    }

    @Test
    void announcedArrayCountsAreRejectedBeforeAllocation() {
        // Every string needs at least one byte, so a count above the readable
        // bytes cannot be satisfied by any valid frame.
        ByteBuf strings = Unpooled.buffer();
        ExtendedByteBuf stringsOut = new ExtendedByteBuf(strings);
        stringsOut.writeVarInt(1_000_000);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                stringsOut::readStringArray);

        // A chunk key is at least nine bytes (empty world + X + Z).
        ByteBuf keys = Unpooled.buffer();
        ExtendedByteBuf keysOut = new ExtendedByteBuf(keys);
        keysOut.writeVarInt(1_000_000);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                keysOut::readChunkKeyArray);
    }

    @Test
    void stringArraysRoundTrip() {
        ByteBuf buffer = Unpooled.buffer();
        ExtendedByteBuf out = new ExtendedByteBuf(buffer);
        out.writeVarInt(3);
        out.writeString("survival1");
        out.writeString("");
        out.writeString("survival2");
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[] { "survival1", "", "survival2" }, out.readStringArray());
    }
}
