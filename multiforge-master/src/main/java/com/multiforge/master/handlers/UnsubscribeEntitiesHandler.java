package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.UnsubscribeEntitiesMessage;
import com.multiforge.master.EntitiesSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class UnsubscribeEntitiesHandler {
    public static void handle(ServerConnection connection, UnsubscribeEntitiesMessage message) {
        EntitiesSubscriptionManager.unsubscribe(connection, message.world, message.cx, message.cz);
    }
}
