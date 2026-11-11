package com.multiforge.protocol.messages.serverbound;

import com.multiforge.protocol.ExtendedByteBuf;

/**
 * Broadcast by the master when a server announces it finished starting.
 *
 * <p>MultiForge adds the server name to MultiPaper's {@code (host, port)}
 * payload: peer connections are addressed by name (the master resolves the
 * address), so every server needs the name-to-address mapping without polling.
 * </p>
 */
public class ServerStartedMessage extends ServerBoundMessage {

    /** Name of the server that started. */
    public final String name;
    /** Host other servers should use for peer connections. */
    public final String host;
    /** Peer-to-peer port of the server. */
    public final int port;

    /**
     * @param name server name
     * @param host reachable host
     * @param port peer port
     */
    public ServerStartedMessage(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /**
     * @param byteBuf buffer positioned after the message id
     */
    public ServerStartedMessage(ExtendedByteBuf byteBuf) {
        name = byteBuf.readString();
        host = byteBuf.readString();
        port = byteBuf.readInt();
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(name);
        byteBuf.writeString(host);
        byteBuf.writeInt(port);
    }

    @Override
    public void handle(ServerBoundMessageHandler handler) {
        handler.handle(this);
    }
}
