package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.WillSaveEntitiesLaterMessage;
import com.multiforge.master.EntitiesLockManager;
import com.multiforge.master.ServerConnection;

public class WillSaveEntitiesHandler {
    public static void handle(ServerConnection connection, WillSaveEntitiesLaterMessage message) {
        EntitiesLockManager.lockUntilWrite(message.world, message.cx, message.cz);
    }
}
