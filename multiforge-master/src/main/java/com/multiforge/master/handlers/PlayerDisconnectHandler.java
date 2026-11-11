package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.Log;
import com.multiforge.protocol.messages.masterbound.PlayerDisconnectMessage;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;

import java.util.UUID;

/**
 * Handles a player logout notification and broadcasts it to the cluster.
 *
 * <p>Removing the UUID from the origin server's set is what allows the player to
 * log in again on another server. The broadcast keeps the cluster-wide player
 * list in sync without polling.</p>
 */
public class PlayerDisconnectHandler {

    private PlayerDisconnectHandler() {
    }

    /**
     * @param connection server the player left
     * @param message    logout notification
     */
    public static void handle(ServerConnection connection, PlayerDisconnectMessage message) {
        connection.removePlayer(message.uuid);
        ServerConnection.broadcastAll(new PlayerUpdateMessage(
                message.uuid, resolveName(connection, message.uuid), connection.getBungeeCordName(), false));
        Log.info(message.uuid + " left " + connection.getBungeeCordName());
    }

    /**
     * The disconnect message only carries the UUID; the name is optional metadata
     * for the global list, so a placeholder is acceptable here.
     */
    private static String resolveName(ServerConnection connection, UUID uuid) {
        return uuid.toString();
    }
}
