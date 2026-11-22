package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Navigation and combat target of a mob whose chunk changes owner.
 *
 * <p>Vanilla never persists a mob's current path or target in NBT, so the entity
 * snapshot that normally follows an ownership change cannot carry them. Without
 * this packet a mob stops at the border and stands still (or forgets its target)
 * until its AI picks a new goal on the new owner. The old owner sends this packet
 * together with the entity snapshots before the fence; the new owner restarts the
 * navigation towards the same block position and restores the combat target.</p>
 *
 * <p>Only the destination and speed are transferred, not the individual path
 * nodes: the new owner recomputes the path against its own world state, which is
 * the same shared chunk data. That is deliberate; copying path nodes across a
 * region-file boundary is not safe when the chunk was saved at a different tick.</p>
 */
public final class MobNavigationPacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Mob UUID. */
    public final UUID uuid;
    /** Whether the mob had an active path. */
    public final boolean hasPath;
    /** Path destination X. */
    public final double x;
    /** Path destination Y. */
    public final double y;
    /** Path destination Z. */
    public final double z;
    /** Speed modifier passed to {@code PathNavigation.moveTo}. */
    public final double speed;
    /** Whether the mob had a living combat target. */
    public final boolean hasTarget;
    /** Combat target UUID, all zero when {@link #hasTarget} is false. */
    public final UUID target;

    /**
     * @param world     logical world name
     * @param uuid      mob UUID
     * @param hasPath   whether a path was active
     * @param x         path destination X
     * @param y         path destination Y
     * @param z         path destination Z
     * @param speed     speed modifier
     * @param hasTarget whether a combat target was set
     * @param target    combat target UUID, may be {@code null}
     */
    public MobNavigationPacket(String world, UUID uuid, boolean hasPath,
                               double x, double y, double z, double speed,
                               boolean hasTarget, UUID target) {
        this.world = world;
        this.uuid = uuid;
        this.hasPath = hasPath;
        this.x = x;
        this.y = y;
        this.z = z;
        this.speed = speed;
        this.hasTarget = hasTarget;
        this.target = target == null ? new UUID(0L, 0L) : target;
    }

    /**
     * @param buf payload positioned after the packet id
     */
    public MobNavigationPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf(256);
        this.uuid = buf.readUUID();
        this.hasPath = buf.readBoolean();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.speed = buf.readDouble();
        this.hasTarget = buf.readBoolean();
        this.target = buf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world, 256);
        buf.writeUUID(uuid);
        buf.writeBoolean(hasPath);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(speed);
        buf.writeBoolean(hasTarget);
        buf.writeUUID(target);
    }

    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onRemoteMobNavigation(this);
        }
    }
}
