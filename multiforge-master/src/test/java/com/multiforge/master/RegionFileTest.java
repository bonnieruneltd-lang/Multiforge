package com.multiforge.master;

import com.multiforge.master.util.RegionFileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the master's region files use the compression their version byte
 * claims.
 *
 * <p>Region version 2 is zlib. The mod used to upload gzip bytes (the output of
 * {@code NbtIo.writeCompressed}) while {@code RegionFile.write} stamped them as
 * deflate, so external tools and a vanilla server could not open the master
 * world. These tests use a plain JDK inflater/gzip stream - the same algorithms
 * vanilla uses - and therefore prove the on-disk format without a Minecraft
 * runtime.</p>
 */
class RegionFileTest {

    /** @param text payload; a few hundred bytes is enough to expose the header. */
    private static byte[] payload() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            builder.append((char) ('a' + (i % 26)));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** @return true zlib data, as written by {@code ChunkSyncIo.encode}. */
    private static byte[] deflate(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(data);
        }
        return out.toByteArray();
    }

    /** @return gzip data, as written by the old {@code NbtIo.writeCompressed}. */
    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] data) throws Exception {
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(data))) {
            return inflater.readAllBytes();
        }
    }

    @Test
    void storedChunkIsTrueZlibAndReadableWithAStandardInflater(@TempDir Path temp) throws Exception {
        byte[] payload = payload();
        byte[] zlib = deflate(payload);
        File regionDir = temp.resolve("region").toFile();

        RegionFileCache.putChunkDeflatedDataAsync(regionDir, 0, 0, zlib).get(10, TimeUnit.SECONDS);

        File regionFile = regionDir.toPath().resolve("r.0.0.mca").toFile();
        try (RandomAccessFile file = new RandomAccessFile(regionFile, "r")) {
            int offset = file.readInt();
            int sectorNumber = offset >>> 8;
            assertEquals(1, offset & 0xFF, "the payload fits in one allocated sector");
            file.seek((long) sectorNumber * 4096L);
            int length = file.readInt();
            byte version = file.readByte();
            assertEquals(2, version, "the region header must say deflate/zlib");
            byte[] stored = new byte[length - 1];
            file.readFully(stored);
            assertFalse((stored[0] & 0xFF) == 0x1F && (stored[1] & 0xFF) == 0x8B,
                    "payload must not carry a gzip header");
            assertArrayEquals(payload, inflate(stored),
                    "a standard zlib reader must recover the chunk bytes");
        }

        try (DataInputStream stream = RegionFileCache.getChunkDataInputStream(regionDir, 0, 0)) {
            assertNotNull(stream);
            assertArrayEquals(payload, stream.readAllBytes());
        }
    }

    @Test
    void legacyGzipMislabelledAsDeflateIsConvertedOnRead(@TempDir Path temp) throws Exception {
        byte[] payload = payload();
        File regionDir = temp.resolve("legacy").toFile();

        // Simulates the old bug: gzip bytes stored with version 2.
        RegionFileCache.putChunkDeflatedDataAsync(regionDir, 0, 0, gzip(payload)).get(10, TimeUnit.SECONDS);

        byte[] deflated = RegionFileCache.getChunkDeflatedDataAsync(regionDir, 0, 0)
                .get(10, TimeUnit.SECONDS);
        assertArrayEquals(payload, inflate(deflated),
                "the read path must transcode legacy gzip to true zlib");

        try (DataInputStream stream = RegionFileCache.getChunkDataInputStream(regionDir, 0, 0)) {
            assertNotNull(stream);
            assertArrayEquals(payload, stream.readAllBytes());
        }
    }
}
