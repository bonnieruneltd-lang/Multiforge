package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * First packet on every peer connection.
 *
 * <p>Announces the sender and the compression schemes it supports. The
 * {@code nanoTime} value mirrors MultiPaper's connection-age heuristic: when two
 * servers connect to each other simultaneously both connections announce
 * themselves, and the newer connection wins. That avoids two half-open
 * connections being used at once.</p>
 */
public class HelloPacket extends PeerPacket {

    /** Compression flag: zlib/deflate is supported. */
    public static final int ZLIB_COMPRESSION = 1;

    /** Name of the sending server. */
    public final String serverName;
    /** {@link System#nanoTime()} of the sending connection. */
    public final long nanoTime;
    /** Bit mask of supported compression schemes. */
    public final int supportedCompression;

    /**
     * @param serverName          sender name
     * @param nanoTime            connection timestamp
     * @param supportedCompression compression bit mask
     */
    public HelloPacket(String serverName, long nanoTime, int supportedCompression) {
        this.serverName = serverName;
        this.nanoTime = nanoTime;
        this.supportedCompression = supportedCompression;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public HelloPacket(FriendlyByteBuf buf) {
        this.serverName = buf.readUtf();
        this.nanoTime = buf.readLong();
        this.supportedCompression = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(serverName);
        buf.writeLong(nanoTime);
        buf.writeVarInt(supportedCompression);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.onHello(this);
    }
}
