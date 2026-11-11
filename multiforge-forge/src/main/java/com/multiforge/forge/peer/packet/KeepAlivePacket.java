package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Periodic liveness probe for a peer connection.
 *
 * <p>Peers are only useful while alive; unlike the master connection there is no
 * dedicated ping protocol in MultiPaper, so MultiForge adds an explicit keep
 * alive. A connection that has not been heard from for three intervals is
 * closed, which lets the recovery logic reconnect on demand.</p>
 */
public class KeepAlivePacket extends PeerPacket {

    /** Sender timestamp, echoed back so the receiver can measure round trips. */
    public final long timestamp;

    /**
     * @param timestamp {@link System#currentTimeMillis()} at send time
     */
    public KeepAlivePacket(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public KeepAlivePacket(FriendlyByteBuf buf) {
        this.timestamp = buf.readLong();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(timestamp);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.onKeepAlive(timestamp);
    }
}
