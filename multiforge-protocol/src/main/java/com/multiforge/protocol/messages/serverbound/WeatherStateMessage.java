package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Master-ordered weather state for one world.
 *
 * <p>The master increments {@link #version} for every accepted
 * {@code ReportWeatherMessage} and broadcasts the result to every server. A
 * receiver applies the state only when the version is newer than the last one it
 * saw, so a delayed broadcast can never roll weather back. A newly connected
 * server receives the current state from the master replay.</p>
 */
public class WeatherStateMessage extends ServerBoundMessage {

    /** Logical world name. */
    public final String world;
    /** Monotonically increasing version assigned by the master. */
    public final long version;
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
     * @param version     master version
     * @param raining     whether it is raining
     * @param rainTime    remaining rain ticks
     * @param thundering  whether it is thundering
     * @param thunderTime remaining thunder ticks
     */
    public WeatherStateMessage(String world, long version, boolean raining, int rainTime,
                               boolean thundering, int thunderTime) {
        this.world = world;
        this.version = version;
        this.raining = raining;
        this.rainTime = rainTime;
        this.thundering = thundering;
        this.thunderTime = thunderTime;
    }

    /**
     * @param byteBuf payload positioned after the message id
     */
    public WeatherStateMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        version = byteBuf.readLong();
        raining = byteBuf.readBoolean();
        rainTime = byteBuf.readInt();
        thundering = byteBuf.readBoolean();
        thunderTime = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeLong(version);
        byteBuf.writeBoolean(raining);
        byteBuf.writeInt(rainTime);
        byteBuf.writeBoolean(thundering);
        byteBuf.writeInt(thunderTime);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
