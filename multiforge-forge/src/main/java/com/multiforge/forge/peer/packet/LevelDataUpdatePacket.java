package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Broadcasts a change of the level's own data (difficulty, spawn point, ...).
 *
 * <p>{@code level.dat} itself is stored on the master and only read at startup;
 * this packet keeps the in-memory {@code ServerLevelData} of already running
 * servers consistent without a restart. Receivers apply the NBT and mark the
 * level data dirty so it is written back through the master on save.</p>
 */
public class LevelDataUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Serialized {@code level.dat} data tag. */
    public final byte[] data;

    /**
     * @param world logical world name
     * @param data  compressed NBT of the level data
     */
    public LevelDataUpdatePacket(String world, byte[] data) {
        this.world = world;
        this.data = data;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public LevelDataUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.data = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeByteArray(data);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteLevelData(this);
    }
}
