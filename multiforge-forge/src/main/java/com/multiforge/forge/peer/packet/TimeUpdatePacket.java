package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Synchronizes the day time of one level.
 *
 * <p>Time is authoritative on whichever server last changed it; MultiForge uses a
 * last-writer-wins policy, which matches MultiPaper's behaviour for
 * {@code /time set} and sleeping.</p>
 */
public class TimeUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Level day time. */
    public final long dayTime;

    /**
     * @param world   logical world name
     * @param dayTime new day time
     */
    public TimeUpdatePacket(String world, long dayTime) {
        this.world = world;
        this.dayTime = dayTime;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public TimeUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.dayTime = buf.readLong();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeLong(dayTime);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteTime(this);
    }
}
