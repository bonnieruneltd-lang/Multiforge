package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.Log;
import com.multiforge.protocol.messages.masterbound.PlayerConnectMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;

import java.util.List;

/**
 * Handles a player login notification.
 *
 * <p>Two servers must never hold the same player at the same time. The master
 * keeps a cluster-wide set of player UUIDs and rejects the login (reply
 * {@code false}) if any other live connection already has that player. This is
 * the same conflict prevention MultiPaper implements.</p>
 *
 * <p>On success the join is broadcast as a {@link PlayerUpdateMessage} so every
 * server can update its cluster-wide player list. Runs on the Netty thread of
 * the requesting connection; the connection registry and player sets are
 * synchronized.</p>
 */
public class PlayerConnectHandler {

    private PlayerConnectHandler() {
    }

    /**
     * @param connection server the player is connecting to
     * @param message    login notification containing UUID and name
     */
    public static void handle(ServerConnection connection, PlayerConnectMessage message) {
        List<ServerConnection> connections = ServerConnection.getConnections();

        synchronized (connections) {
            for (ServerConnection otherConnection : connections) {
                if (otherConnection != connection && otherConnection.hasPlayer(message.uuid)) {
                    connection.sendReply(new BooleanMessageReply(false), message);
                    return;
                }
            }

            connection.addPlayer(message.uuid);
        }

        connection.sendReply(new BooleanMessageReply(true), message);
        ServerConnection.broadcastAll(new PlayerUpdateMessage(
                message.uuid, message.playerName, connection.getBungeeCordName(), true));
        Log.info(message.playerName + " joined " + connection.getBungeeCordName());
    }
}
