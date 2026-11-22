package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.messages.MessageHandler;
import com.multiforge.protocol.messages.masterbound.MasterBoundDataStreamMessage;
import com.multiforge.protocol.messages.masterbound.MasterBoundMessage;

public abstract class ServerBoundMessageHandler extends MessageHandler<ServerBoundMessage> {

    @Override
    public MasterBoundMessage createDataStreamMessage(int streamId, byte[] data, int offset, int length) {
        return new MasterBoundDataStreamMessage(streamId, data, offset, length);
    }

    public abstract void handle(ServerInfoUpdateMessage message);

    public abstract void handle(SetSecretMessage message);

    public abstract void handle(ShutdownMessage message);

    public abstract void handle(ServerChangedChunkStatusMessage message);

    public abstract void handle(FileContentMessage message);

    public abstract void handle(PlayerUpdateMessage message);

    public abstract void handle(SetChunkOwnerMessage message);

    public abstract void handle(ServerStartedMessage message);

    public abstract void handle(DataUpdateMessage message);

    public abstract void handle(AddChunkSubscriberMessage message);

    public abstract void handle(RemoveChunkSubscriberMessage message);

    public abstract void handle(AddEntitySubscriberMessage message);

    public abstract void handle(RemoveEntitySubscriberMessage message);

    public abstract void handle(ChunkSubscribersSyncMessage message);

    public abstract void handle(EntitySubscribersSyncMessage message);

    /**
     * @param message progress of an in-flight download
     */
    public abstract void handle(DownloadProgressMessage message);

    /**
     * @param message instruction to drain a chunk for a handover
     */
    public abstract void handle(ChunkHandoverStartMessage message);

    /**
     * @param message final result of a handover
     */
    public abstract void handle(ChunkHandoverCompleteMessage message);
}
