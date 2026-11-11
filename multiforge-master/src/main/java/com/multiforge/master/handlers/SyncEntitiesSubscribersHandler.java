package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SyncEntitiesSubscribersMessage;
import com.multiforge.master.EntitiesSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class SyncEntitiesSubscribersHandler {
    public static void handle(ServerConnection connection, SyncEntitiesSubscribersMessage message) {
        EntitiesSubscriptionManager.syncSubscribers(connection, message.world, message.cx, message.cz);
    }
}
