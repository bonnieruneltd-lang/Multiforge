package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Notifies the owning server that a player opened a container that is in a chunk
 * owned by that server.
 *
 * <p>The menu itself is opened on the server the player is connected to, so the
 * client keeps a normal vanilla window and all slot-click handling stays on that
 * server (MultiPaper's "open menus on the player's local server" behaviour). The
 * owner is still authoritative for the container contents, so it needs to know
 * which player is looking at which block entity:</p>
 * <ul>
 *   <li>it replies with a fresh {@link BlockEntityUpdatePacket} so the opening
 *       server's menu starts from the owner's current contents;</li>
 *   <li>it records the open window so {@link ContainerClickPacket} deltas are
 *       accepted only for a container the player really opened.</li>
 * </ul>
 *
 * <p>Wire layout: world name, block position, player UUID. Decoding is a pure
 * buffer read; {@link #handle(PeerConnection)} hops to the server thread inside
 * {@code ContainerSyncManager}.</p>
 */
public final class OpenContainerPacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Container block X. */
    public final int x;
    /** Container block Y. */
    public final int y;
    /** Container block Z. */
    public final int z;
    /** Player that opened the container. */
    public final UUID player;

    /**
     * @param world  logical world name
     * @param x      block X
     * @param y      block Y
     * @param z      block Z
     * @param player player UUID
     */
    public OpenContainerPacket(String world, int x, int y, int z, UUID player) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.player = player;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public OpenContainerPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.player = buf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeUUID(player);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().containers().onRemoteOpen(this, connection);
    }
}
