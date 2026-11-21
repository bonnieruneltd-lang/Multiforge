package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SetPortMessage;
import com.multiforge.protocol.messages.serverbound.ServerStartedMessage;
import com.multiforge.master.ServerConnection;

public class SetPortHandler {
    public static void handle(ServerConnection connection, SetPortMessage message) {
        // This is the peer-to-peer listener; the game port comes from StartMessage.
        connection.setPeerPort(message.port);
        if (connection.getPort() > 0 && connection.getHost() != null) {
            // StartMessage may have arrived first. Re-broadcast so peers always get the
            // real peer port instead of the game port.
            connection.broadcastOthers(new ServerStartedMessage(connection.getBungeeCordName(),
                    connection.getHost(), message.port));
        }
    }
}
