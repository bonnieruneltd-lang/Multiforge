package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.util.Log;
import com.multiforge.protocol.messages.masterbound.StartMessage;
import com.multiforge.protocol.messages.serverbound.ServerStartedMessage;

/**
 * Handles {@link StartMessage}: sent by a Forge server once it finished starting
 * and knows the peer-to-peer port it listens on.
 *
 * <p>The master records the publicly reachable host/port so that other servers
 * can open peer connections to exchange chunk and entity data directly, and
 * broadcasts {@link ServerStartedMessage} so every server learns about the new
 * peer. Runs on the Netty event loop of the sending connection; the
 * {@link ServerConnection} registry it touches is thread-safe.</p>
 */
public class StartHandler {

    private StartHandler() {
    }

    /**
     * @param connection connection that announced it started
     * @param message    message containing the peer host and port
     */
    public static void handle(ServerConnection connection, StartMessage message) {
        if (!message.host.isEmpty() && !message.host.equals("0.0.0.0")) {
            StringBuilder line = new StringBuilder()
                    .append("Setting ").append(connection.getBungeeCordName())
                    .append("'s host to ").append(message.host)
                    .append(" with port ").append(message.port);
            if (!message.host.equals(connection.getHost())) {
                line.append(" (host address was ").append(connection.getHost()).append(')');
            }
            Log.info(line.toString());
            connection.setHost(message.host);
        }

        Log.info(connection.getBungeeCordName() + " (" + connection.getHost() + ":" + message.port
                + ") has started, broadcasting start to other servers...");
        connection.broadcastOthers(new ServerStartedMessage(connection.getBungeeCordName(), connection.getHost(), message.port));
    }
}
