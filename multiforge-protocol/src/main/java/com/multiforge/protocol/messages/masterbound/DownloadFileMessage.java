package com.multiforge.protocol.messages.masterbound;

import com.multiforge.protocol.ExtendedByteBuf;

public class DownloadFileMessage extends MasterBoundMessage {

    public final String path;

    public DownloadFileMessage(String name) {
        this.path = name;
    }

    public DownloadFileMessage(ExtendedByteBuf byteBuf) {
        path = byteBuf.readString();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(path);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
