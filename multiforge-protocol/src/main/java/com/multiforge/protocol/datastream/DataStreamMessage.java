package com.multiforge.protocol.datastream;

import com.multiforge.protocol.ExtendedByteBuf;
import com.multiforge.protocol.messages.Message;
import com.multiforge.protocol.messages.MessageHandler;

public class DataStreamMessage<T extends MessageHandler<? extends Message<?>>> extends Message<T> {
    public final int streamId;
    public final byte[] data;
    private final int offset;
    private final int length;

    public DataStreamMessage(int streamId, byte[] data, int offset, int length) {
        this.streamId = streamId;
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    public DataStreamMessage(ExtendedByteBuf byteBuf) {
        streamId = byteBuf.readVarInt();
        data = new byte[byteBuf.readVarInt()];
        byteBuf.readBytes(data);
        offset = 0;
        length = data.length;
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeVarInt(streamId);
        byteBuf.writeVarInt(length);
        byteBuf.writeBytes(data, offset, length);
    }

    @Override
    public void handle(T handler) {
        handler.getDataStreamManager().handleInboundData(streamId, data);
    }
}
