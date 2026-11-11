package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Broadcasts a single block change to every other server.
 *
 * <p>MultiPaper sends the full block update packet payload; MultiForge sends the
 * global block state id, which is registry-order stable across servers running
 * the same mods. Receivers resolve it with {@code Block.stateById} and apply it
 * through {@code Level.setBlock}, which keeps light, neighbour updates and block
 * entities consistent.</p>
 *
 * <p>Echo loops are prevented by the {@code applyingRemote} flag in the chunk
 * manager: changes applied from the network are never re-broadcast.</p>
 */
public class BlockChangePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Block position. */
    public final int x;
    /** Block position. */
    public final int y;
    /** Block position. */
    public final int z;
    /** Global block state id ({@code Block.getId}). */
    public final int stateId;

    /**
     * @param world   logical world name
     * @param x       block X
     * @param y       block Y
     * @param z       block Z
     * @param stateId global block state id
     */
    public BlockChangePacket(String world, int x, int y, int z, int stateId) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.stateId = stateId;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public BlockChangePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.stateId = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeVarInt(stateId);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().chunks().applyRemoteBlockChange(this);
    }
}
