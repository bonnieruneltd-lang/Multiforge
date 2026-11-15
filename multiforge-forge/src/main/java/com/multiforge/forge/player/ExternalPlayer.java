package com.multiforge.forge.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Server-side copy of a player that is online on another cluster server.
 *
 * <p>Port of MultiPaper's {@code ExternalPlayer}. The entity lives in the real
 * level of the mirror server, so vanilla's entity tracker sends the spawn,
 * movement, metadata and equipment packets to local clients exactly as it does
 * for a local player. Its {@link #tick()} and {@link #doTick()} are empty: only
 * the server that accepted the connection may simulate the player. All visible
 * state arrives through {@code EntityUpdatePacket} and is applied by
 * {@code EntitySyncManager} before the tracker forwards it to clients.</p>
 *
 * <p>The fake {@link ServerGamePacketListenerImpl} makes the object a valid
 * {@code ServerPlayer} for the tab list and vanilla bookkeeping, while its
 * connection drops every outgoing packet instead of writing to a channel. The
 * player is registered in {@code PlayerList} by
 * {@link PlayerMirrorManager}, which also creates and removes it on the server
 * thread; no other thread may touch the instance.</p>
 */
public final class ExternalPlayer extends ServerPlayer {

    /** Server that really hosts the player. */
    private final String originServer;

    /**
     * @param server       running server
     * @param level        level the player is currently in
     * @param profile      full profile including skin properties
     * @param originServer server that owns the connection
     */
    public ExternalPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                          String originServer) {
        super(server, level, profile);
        this.originServer = originServer;
        this.connection = new ServerGamePacketListenerImpl(server, new VoidConnection(), this);
    }

    /** @return server that really hosts the player */
    public String originServer() {
        return originServer;
    }

    /**
     * Remote players are never simulated locally; their movement is applied from
     * the owner's packets.
     */
    @Override
    public void tick() {
        // Never simulate a player that is ticked by another server.
    }

    /**
     * {@code PlayerList.tick} calls {@code doTick} for every listed player. The
     * vanilla body would run player physics, container and connection logic.
     */
    @Override
    public void doTick() {
        // See tick().
    }

    /**
     * @return a loopback address; the real address belongs to the origin server
     */
    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    /**
     * Prevents the mirror copy from being written into the shared entity storage
     * of the chunk it happens to stand in.
     *
     * @return always {@code false}
     */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /**
     * @param component message that would be sent to the player
     */
    @Override
    public void sendSystemMessage(net.minecraft.network.chat.Component component) {
        // The player is not connected to this server.
    }

    /**
     * Sink for packets the mirror server would send to the fake player.
     *
     * <p>No channel exists. Without this override {@code Connection.send} would
     * queue every packet forever, which the tracker would do on every tick.</p>
     */
    private static final class VoidConnection extends Connection {

        private VoidConnection() {
            super(PacketFlow.CLIENTBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            // Dropped: the real player is connected to the origin server.
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            // Dropped for the same reason.
        }

        @Override
        public boolean isConnected() {
            // Report connected so vanilla does not try to reconnect/queue.
            return true;
        }
    }
}
