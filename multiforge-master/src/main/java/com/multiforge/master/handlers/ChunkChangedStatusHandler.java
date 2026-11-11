package com.multiforge.master.handlers;

import com.multiforge.protocol.messages.masterbound.ChunkChangedStatusMessage;
import com.multiforge.protocol.messages.serverbound.ServerChangedChunkStatusMessage;
import com.multiforge.master.ChunkStatusTracker;
import com.multiforge.master.ChunkSubscriptionManager;
import com.multiforge.master.ServerConnection;

/**
 * Records a chunk's load status and relays it to the chunk's subscribers.
 *
 * <p>The master needs the status itself: {@code ReadChunkHandler} avoids
 * redirecting a read to a server that is already unloading the chunk. Relaying it
 * keeps the client-side {@code ChunkState.remoteStatus} view accurate for commands
 * and diagnostics. Both happen on the Netty thread.</p>
 */
public class ChunkChangedStatusHandler {

    private ChunkChangedStatusHandler() {
    }

    /**
     * @param connection server that reported the status
     * @param message    status message
     */
    public static void handle(ServerConnection connection, ChunkChangedStatusMessage message) {
        ChunkStatusTracker.update(message.world, message.cx, message.cz,
                connection.getBungeeCordName(), message.status);
        for (ServerConnection subscriber : ChunkSubscriptionManager.getSubscribers(message.world, message.cx, message.cz)) {
            subscriber.send(new ServerChangedChunkStatusMessage(message.world, message.cx, message.cz, message.status, connection.getBungeeCordName()));
        }
    }
}
