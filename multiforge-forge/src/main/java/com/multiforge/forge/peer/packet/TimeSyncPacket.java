package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Synchronizes level time with the same semantics as MultiPaper's
 * {@code TimeUpdatePacket}: periodic updates only correct a server that is more
 * than a second behind, while a forced update (a {@code /time set}, sleep skip or
 * clock change) is applied unconditionally.
 *
 * <p>{@code gameTime} is the monotonically increasing world tick counter and is
 * used for the lag check; {@code dayTime} is what clients render and can be frozen
 * by {@code doDaylightCycle=false}. Without the force flag, a command that sets the
 * time on one server is immediately overwritten by the other servers' periodic
 * broadcasts, which was observed live.</p>
 *
 * <p>Appended as peer packet id 14. {@link TimeUpdatePacket} (id 8) remains
 * registered for older peers but is no longer broadcast.</p>
 */
public class TimeSyncPacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Level game time (world tick counter). */
    public final long gameTime;
    /** Level day time. */
    public final long dayTime;
    /** Whether the receiver must apply the values even when it is not behind. */
    public final boolean force;

    /**
     * @param world    logical world name
     * @param gameTime level game time
     * @param dayTime  level day time
     * @param force    {@code true} for a command/sleep jump
     */
    public TimeSyncPacket(String world, long gameTime, long dayTime, boolean force) {
        this.world = world;
        this.gameTime = gameTime;
        this.dayTime = dayTime;
        this.force = force;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public TimeSyncPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.gameTime = buf.readLong();
        this.dayTime = buf.readLong();
        this.force = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeLong(gameTime);
        buf.writeLong(dayTime);
        buf.writeBoolean(force);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteTimeSync(this);
    }
}
