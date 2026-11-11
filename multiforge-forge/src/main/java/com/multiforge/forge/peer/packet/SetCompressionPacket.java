package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Sent by the accepting side to enable compression on the connection.
 *
 * <p>Only one compression scheme exists today (zlib, value {@code 1}); {@code 0}
 * means "no compression" and is accepted for forward compatibility.</p>
 */
public class SetCompressionPacket extends PeerPacket {

    /** No compression. */
    public static final int NONE = 0;
    /** zlib/deflate compression. */
    public static final int ZLIB = 1;

    /** Negotiated scheme. */
    public final int compressionType;

    /**
     * @param compressionType {@link #NONE} or {@link #ZLIB}
     */
    public SetCompressionPacket(int compressionType) {
        this.compressionType = compressionType;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public SetCompressionPacket(FriendlyByteBuf buf) {
        this.compressionType = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(compressionType);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.enableCompression(compressionType);
    }
}
