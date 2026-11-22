package com.multiforge.master.handlers;

import com.multiforge.master.ChunkHandoverManager;
import com.multiforge.master.ServerConnection;
import com.multiforge.protocol.messages.masterbound.RequestChunkHandoverMessage;

/**
 * Entry point for {@link RequestChunkHandoverMessage}.
 *
 * <p>All validation and the transfer state machine live in
 * {@link ChunkHandoverManager}; this handler only forwards the request so the
 * message dispatch stays uniform with the other master handlers.</p>
 */
public class RequestChunkHandoverHandler {

    private RequestChunkHandoverHandler() {
    }

    /**
     * @param connection requesting server
     * @param message    handover request
     */
    public static void handle(ServerConnection connection, RequestChunkHandoverMessage message) {
        ChunkHandoverManager.request(connection, message.world, message.cx, message.cz);
    }
}
