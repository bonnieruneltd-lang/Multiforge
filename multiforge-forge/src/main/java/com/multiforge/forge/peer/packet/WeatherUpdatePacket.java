package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Synchronizes rain and thunder state of one level.
 *
 * <p>Weather timers are included because the client renders rain based on them;
 * without the timers every server would roll its own weather duration and the
 * cluster would flicker between states.</p>
 */
public class WeatherUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Rain state. */
    public final boolean raining;
    /** Ticks until the rain state may change. */
    public final int rainTime;
    /** Thunder state. */
    public final boolean thundering;
    /** Ticks until the thunder state may change. */
    public final int thunderTime;

    /**
     * @param world       logical world name
     * @param raining     rain state
     * @param rainTime    rain timer
     * @param thundering  thunder state
     * @param thunderTime thunder timer
     */
    public WeatherUpdatePacket(String world, boolean raining, int rainTime, boolean thundering, int thunderTime) {
        this.world = world;
        this.raining = raining;
        this.rainTime = rainTime;
        this.thundering = thundering;
        this.thunderTime = thunderTime;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public WeatherUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.raining = buf.readBoolean();
        this.rainTime = buf.readVarInt();
        this.thundering = buf.readBoolean();
        this.thunderTime = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeBoolean(raining);
        buf.writeVarInt(rainTime);
        buf.writeBoolean(thundering);
        buf.writeVarInt(thunderTime);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteWeather(this);
    }
}
