package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Notifies peers that the entity data of a chunk changed.
 *
 * <p>Entity data is stored and read through the master (see the entity storage
 * notes in {@code docs/CHUNK_MANAGEMENT.md}); this notification lets a server
 * that has the chunk loaded invalidate any cached entity view. It deliberately
 * carries no payload: peers re-read the authoritative data from the master.</p>
 */
public class EntityChunkChangedPacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Chunk X coordinate. */
    public final int x;
    /** Chunk Z coordinate. */
    public final int z;

    /**
     * @param world logical world name
     * @param x     chunk X
     * @param z     chunk Z
     */
    public EntityChunkChangedPacket(String world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public EntityChunkChangedPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(z);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().chunks().onRemoteEntityChunkChanged(this);
    }
}
