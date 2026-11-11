package com.multiforge.forge.peer;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Base class of every server-to-server (peer) packet.
 *
 * <p>This mirrors MultiPaper's {@code ExternalServerPacket}: peers exchange
 * Minecraft flavoured data directly instead of routing everything through the
 * master. Packets are {@linkplain FriendlyByteBuf written} on the sending
 * thread (callers may be on the server thread) and {@linkplain #handle handled}
 * on the Netty thread of the receiving connection. Implementations must not
 * mutate world state directly: they call {@code MultiForgeServer.execute} to hop
 * onto the server thread.</p>
 *
 * <p>Instances are short lived and never shared between connections, so they do
 * not need to be thread-safe.</p>
 */
public abstract class PeerPacket {

    /**
     * Serializes the packet payload (without id or length prefix).
     *
     * @param buf destination buffer owned by the encoder
     */
    public abstract void write(FriendlyByteBuf buf);

    /**
     * Handles the packet after it was decoded.
     *
     * @param connection connection the packet arrived on
     */
    public abstract void handle(PeerConnection connection);
}
