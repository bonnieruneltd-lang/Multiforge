package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SubscribeEntitiesMessage;
import com.multiforge.master.EntitiesSubscriptionManager;
import com.multiforge.master.ServerConnection;

public class SubscribeEntitiesHandler {
    public static void handle(ServerConnection connection, SubscribeEntitiesMessage message) {
        EntitiesSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
    }
}
