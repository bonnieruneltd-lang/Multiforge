package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Synchronizes the world border of one level.
 *
 * <p>All numeric attributes that affect gameplay are carried, so a receiver can
 * reconstruct the border exactly. The lerp state is intentionally not sent:
 * borders set through commands settle quickly and MultiForge re-broadcasts the
 * final state when it changes.</p>
 */
public class WorldBorderUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Center X. */
    public final double centerX;
    /** Center Z. */
    public final double centerZ;
    /** Current size. */
    public final double size;
    /** Damage per block outside the safe zone. */
    public final double damagePerBlock;
    /** Damage safe zone width. */
    public final double damageSafeZone;
    /** Warning distance in blocks. */
    public final int warningBlocks;
    /** Warning time in seconds. */
    public final int warningTime;

    /**
     * @param world           logical world name
     * @param centerX         center X
     * @param centerZ         center Z
     * @param size            size
     * @param damagePerBlock  damage per block
     * @param damageSafeZone  safe zone width
     * @param warningBlocks   warning distance
     * @param warningTime     warning time
     */
    public WorldBorderUpdatePacket(String world, double centerX, double centerZ, double size,
                                   double damagePerBlock, double damageSafeZone,
                                   int warningBlocks, int warningTime) {
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = size;
        this.damagePerBlock = damagePerBlock;
        this.damageSafeZone = damageSafeZone;
        this.warningBlocks = warningBlocks;
        this.warningTime = warningTime;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public WorldBorderUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.centerX = buf.readDouble();
        this.centerZ = buf.readDouble();
        this.size = buf.readDouble();
        this.damagePerBlock = buf.readDouble();
        this.damageSafeZone = buf.readDouble();
        this.warningBlocks = buf.readVarInt();
        this.warningTime = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeDouble(centerX);
        buf.writeDouble(centerZ);
        buf.writeDouble(size);
        buf.writeDouble(damagePerBlock);
        buf.writeDouble(damageSafeZone);
        buf.writeVarInt(warningBlocks);
        buf.writeVarInt(warningTime);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteWorldBorder(this);
    }
}
