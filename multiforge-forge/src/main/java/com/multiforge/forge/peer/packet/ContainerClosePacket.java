package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Tells the owning server that a player closed a forwarded container window.
 *
 * <p>The owner drops the open-window record so a delayed {@link ContainerClickPacket}
 * that arrives after the menu closed is ignored. The packet is also generated
 * when the player switches to another container, because vanilla closes the old
 * menu first.</p>
 */
public final class ContainerClosePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Container block X. */
    public final int x;
    /** Container block Y. */
    public final int y;
    /** Container block Z. */
    public final int z;
    /** Player that closed the container. */
    public final UUID player;

    /**
     * @param world  logical world name
     * @param x      block X
     * @param y      block Y
     * @param z      block Z
     * @param player player UUID
     */
    public ContainerClosePacket(String world, int x, int y, int z, UUID player) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.player = player;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public ContainerClosePacket(FriendlyByteBuf buf) {
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
        connection.server().containers().onRemoteClose(this, connection);
    }
}
