package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Reports a local weather transition to the master.
 *
 * <p>Every server decrements the rain/thunder timers itself, so peer-to-peer
 * weather updates can arrive in different orders at different servers and the
 * last writer wins. The master sequences these reports instead: it assigns a
 * monotonically increasing version per world and broadcasts
 * {@link com.multiforge.protocol.messages.serverbound.WeatherStateMessage}. A
 * server only reports a state change or a command reset, not the countdown.</p>
 *
 * <p>Sent by the game server when the master connection is live; when it is down,
 * the existing peer {@code WeatherUpdatePacket} remains the fallback.</p>
 */
public class ReportWeatherMessage extends MasterBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Whether it is raining. */
    public final boolean raining;
    /** Remaining rain ticks. */
    public final int rainTime;
    /** Whether it is thundering. */
    public final boolean thundering;
    /** Remaining thunder ticks. */
    public final int thunderTime;

    /**
     * @param world       logical world name
     * @param raining     whether it is raining
     * @param rainTime    remaining rain ticks
     * @param thundering  whether it is thundering
     * @param thunderTime remaining thunder ticks
     */
    public ReportWeatherMessage(String world, boolean raining, int rainTime,
                                boolean thundering, int thunderTime) {
        this.world = world;
        this.raining = raining;
        this.rainTime = rainTime;
        this.thundering = thundering;
        this.thunderTime = thunderTime;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public ReportWeatherMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        raining = byteBuf.readBoolean();
        rainTime = byteBuf.readInt();
        thundering = byteBuf.readBoolean();
        thunderTime = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeBoolean(raining);
        byteBuf.writeInt(rainTime);
        byteBuf.writeBoolean(thundering);
        byteBuf.writeInt(thunderTime);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
