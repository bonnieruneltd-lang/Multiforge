package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class WriteTickTimeMessage extends MasterBoundMessage {

    public final long tickTime;
    public final float tps;

    public WriteTickTimeMessage(long tickTime, float tps) {
        this.tickTime = tickTime;
        this.tps = tps;
    }

    public WriteTickTimeMessage(ExtendedByteBuf byteBuf) {
        tickTime = byteBuf.readLong();
        tps = byteBuf.readFloat();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeLong(tickTime);
        byteBuf.writeFloat(tps);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
