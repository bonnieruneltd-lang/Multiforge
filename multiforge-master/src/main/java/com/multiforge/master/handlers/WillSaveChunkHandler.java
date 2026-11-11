package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.WillSaveChunkLaterMessage;
import com.multiforge.master.ChunkLockManager;
import com.multiforge.master.ServerConnection;

public class WillSaveChunkHandler {
    public static void handle(ServerConnection connection, WillSaveChunkLaterMessage message) {
        ChunkLockManager.lockUntilWrite(message.world, message.cx, message.cz);
    }
}
