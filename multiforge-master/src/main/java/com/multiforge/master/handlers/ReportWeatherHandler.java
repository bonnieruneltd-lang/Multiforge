package com.multiforge.master.handlers;

import com.multiforge.master.ServerConnection;
import com.multiforge.master.WorldWeatherManager;
import com.multiforge.protocol.messages.masterbound.ReportWeatherMessage;

/**
 * Entry point for {@link ReportWeatherMessage}.
 *
 * <p>The sequencing and broadcast live in {@link WorldWeatherManager}; this
 * handler keeps the dispatch pattern of the other master handlers.</p>
 */
public class ReportWeatherHandler {

    private ReportWeatherHandler() {
    }

    /**
     * @param connection reporting server
     * @param message    weather transition
     */
    public static void handle(ServerConnection connection, ReportWeatherMessage message) {
        WorldWeatherManager.report(message.world, message.raining, message.rainTime,
                message.thundering, message.thunderTime, connection.getBungeeCordName());
    }
}
