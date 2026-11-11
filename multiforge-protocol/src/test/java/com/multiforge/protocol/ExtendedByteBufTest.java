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
}
