package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

import java.util.UUID;

/**
 * Broadcast by the master whenever a player joins or leaves any server.
 *
 * <p>MultiPaper kept a cluster-wide player registry inside the master and only
 * exposed it through Bukkit's {@code getAllOnlinePlayers}. Forge servers need the
 * same information to answer "which server is this player on?", to render a
 * global player list and to reject duplicate logins, so the master broadcasts
 * this message to every connection (including the origin server, which keeps the
 * logic uniform).</p>
 *
 * <p>Instances are immutable and safe to read from any thread; the mod
 * re-dispatches them onto the server thread before touching world state.</p>
 */
public class PlayerUpdateMessage extends ServerBoundMessage {

    /** Player UUID. */
    public final UUID uuid;
    /** Last known player name. */
    public final String playerName;
    /** Server the player is on, or the server they left. */
    public final String server;
    /** {@code true} for join, {@code false} for leave. */
    public final boolean online;

    public PlayerUpdateMessage(UUID uuid, String playerName, String server, boolean online) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.server = server;
        this.online = online;
    }

    public PlayerUpdateMessage(ExtendedByteBuf byteBuf) {
        uuid = byteBuf.readUUID();
        playerName = byteBuf.readString();
        server = byteBuf.readString();
        online = byteBuf.readBoolean();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeString(playerName);
        byteBuf.writeString(server);
        byteBuf.writeBoolean(online);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
