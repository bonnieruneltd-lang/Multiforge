package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One serialized vanilla boss-event packet for a world.
 *
 * <p>Vanilla boss bars (the dragon fight, raids, plugins) are {@code ServerBossEvent}
 * instances that only exist on the server that created them. This packet forwards
 * the already encoded {@code ClientboundBossEventPacket} to the other servers,
 * which send it to their local players in the same world. Forwarding the vanilla
 * bytes keeps the add/remove/progress/name/style/properties semantics identical
 * without duplicating the protocol.</p>
 *
 * <p>Only the server that owns the boss event sends; the receiver never creates a
 * {@code ServerBossEvent} or ticks it, so there is no duplicate boss logic. The
 * world is part of the payload because the same UUID must only reach players in
 * that dimension.</p>
 */
public final class BossEventPacket extends PeerPacket {

    /** Upper bound for one encoded boss packet; real packets are tiny. */
    public static final int MAX_DATA_LENGTH = 1 << 20;

    /** Logical world name. */
    public final String world;
    /** Encoded {@code ClientboundBossEventPacket} including its packet id. */
    public final byte[] data;

    /**
     * @param world logical world name
     * @param data  encoded clientbound boss event packet
     */
    public BossEventPacket(String world, byte[] data) {
        this.world = world;
        this.data = data;
    }

    /**
     * @param buf payload positioned after the packet id
     */
    public BossEventPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf(256);
        this.data = buf.readByteArray(MAX_DATA_LENGTH);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world, 256);
        buf.writeByteArray(data);
    }

    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onRemote(world, data);
        }
    }
}
