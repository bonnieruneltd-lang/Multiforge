package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.SetPortMessage;
import com.multiforge.master.ServerConnection;

public class SetPortHandler {
    public static void handle(ServerConnection connection, SetPortMessage message) {
        // This is the peer-to-peer listener; the game port comes from StartMessage.
        connection.setPeerPort(message.port);
    }
}
