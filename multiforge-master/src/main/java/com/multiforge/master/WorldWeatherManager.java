package com.multiforge.master;

import com.multiforge.master.util.Log;
import com.multiforge.protocol.messages.serverbound.WeatherStateMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Master-owned ordering of weather transitions.
 *
 * <p>Weather is the last world value that was still last-writer-wins between
 * peers: every server decrements the rain/thunder timers itself, so two
 * simultaneous transitions can be applied in a different order on each server.
 * This manager turns the master into a sequencer. A server reports a transition
 * with {@code ReportWeatherMessage}; the master assigns the next version for that
 * world, stores the state and broadcasts {@code WeatherStateMessage}. Receivers
 * drop a state with a version they already saw, so the final weather is the same
 * on every server. A server that connects later receives the current state during
 * the hello handshake.</p>
 *
 * <p>Threading: reports arrive on Netty threads. The map and its versions are
 * guarded by one monitor; messages are sent after the state is stored, inside the
 * same critical section so two reports cannot be broadcast out of order. No world
 * state is touched here.</p>
 */
public final class WorldWeatherManager {

    /** One world's sequenced weather. */
    private record State(long version, boolean raining, int rainTime,
                         boolean thundering, int thunderTime) {
    }

    private static final Object LOCK = new Object();
    private static final Map<String, State> STATES = new HashMap<>();

    private WorldWeatherManager() {
    }

    /**
     * Sequences one reported weather transition and broadcasts the result.
     *
     * <p>Only a semantic change (rain or thunder flag) advances the version and is
     * broadcast. Every server decrements the timers itself, so two servers that
     * already agree on the flags would otherwise keep reporting slightly different
     * timer values and reset each other forever. Timer values from an identical
     * report are stored for the hello replay without a broadcast.</p>
     *
     * @param world       logical world name
     * @param raining     whether it is raining
     * @param rainTime    remaining rain ticks
     * @param thundering  whether it is thundering
     * @param thunderTime remaining thunder ticks
     * @param sender      reporting server, for the log
     * @return the version that applies to this state
     */
    public static long report(String world, boolean raining, int rainTime,
                              boolean thundering, int thunderTime, String sender) {
        synchronized (LOCK) {
            State previous = STATES.get(world);
            if (previous != null
                    && previous.raining() == raining
                    && previous.thundering() == thundering) {
                // Same weather, only a timer difference: keep the stored timers
                // fresh for replay but do not broadcast.
                STATES.put(world, new State(previous.version(), raining, rainTime,
                        thundering, thunderTime));
                return previous.version();
            }
            long version = previous == null ? 1L : previous.version() + 1L;
            State state = new State(version, raining, rainTime, thundering, thunderTime);
            STATES.put(world, state);
            WeatherStateMessage message = new WeatherStateMessage(
                    world, version, raining, rainTime, thundering, thunderTime);
            for (ServerConnection connection : ServerConnection.getConnections()) {
                connection.send(message);
            }
            Log.info("Weather " + world + " version " + version + " from " + sender
                    + ": rain=" + raining + ", thunder=" + thundering);
            return version;
        }
    }

    /**
     * Sends the current weather of every known world to a newly connected server.
     *
     * @param connection connection that completed the hello handshake
     */
    public static void replay(ServerConnection connection) {
        List<WeatherStateMessage> snapshot = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, State> entry : STATES.entrySet()) {
                State state = entry.getValue();
                snapshot.add(new WeatherStateMessage(entry.getKey(), state.version(),
                        state.raining(), state.rainTime(), state.thundering(), state.thunderTime()));
            }
        }
        for (WeatherStateMessage message : snapshot) {
            connection.send(message);
        }
    }

    /**
     * @param world logical world name
     * @return current version, {@code 0} when the master never saw weather for it
     */
    public static long version(String world) {
        synchronized (LOCK) {
            State state = STATES.get(world);
            return state == null ? 0L : state.version();
        }
    }

    /** Forgets every state; used when the master stops and by tests. */
    public static void clear() {
        synchronized (LOCK) {
            STATES.clear();
        }
    }
}
