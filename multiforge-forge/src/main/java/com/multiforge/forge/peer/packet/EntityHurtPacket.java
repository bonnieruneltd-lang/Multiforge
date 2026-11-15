package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Forwards damage applied to a mirrored entity to the server that owns it.
 *
 * <p>A mirror copy is not ticked and its health is not authoritative. Without
 * this packet a hit on a mirrored mob would only change the local copy and be
 * overwritten by the owner's next metadata update, so the mob would not be
 * damageable from every server.</p>
 *
 * <p>MultiPaper forwards the whole {@code DamageSource}. MultiForge carries the
 * damage type key, the amount, the UUIDs of the causing and direct entities
 * (player, arrow, explosion source, ...) and, when the source has one, its
 * position. The owner resolves those UUIDs in its own level and rebuilds a real
 * {@code DamageSource}, so death messages, kill credit, projectile attribution
 * and knockback behave as if the hit had happened locally. Entities that do not
 * exist on the owner degrade gracefully: the UUID is omitted and the source is
 * built from the damage type alone.</p>
 *
 * <p>Wire layout: world name, entity UUID, amount, damage type id, optional
 * attacker UUID, optional direct-entity UUID, optional source position. The
 * optional fields are appended; a peer running an older build simply omits them.
 * Decoding only reads the buffer; {@link #handle(PeerConnection)} hops to the
 * server thread.</p>
 */
public final class EntityHurtPacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final float amount;
    private final String damageType;
    private final UUID attacker;
    private final UUID directEntity;
    private final double x;
    private final double y;
    private final double z;
    private final boolean hasPosition;

    /**
     * @param world      logical world name
     * @param uuid       entity UUID
     * @param amount     damage amount
     * @param damageType registry id of the damage type, e.g. {@code minecraft:generic}
     */
    public EntityHurtPacket(String world, UUID uuid, float amount, String damageType) {
        this(world, uuid, amount, damageType, null, null, (Double) null, null, null);
    }

    /**
     * @param world        logical world name
     * @param uuid         entity UUID
     * @param amount       damage amount
     * @param damageType   registry id of the damage type
     * @param attacker     UUID of the causing entity, {@code null} when unknown
     * @param directEntity UUID of the direct entity (arrow, fireball, ...),
     *                     {@code null} when unknown
     * @param position     source position, {@code null} when unknown
     */
    public EntityHurtPacket(String world, UUID uuid, float amount, String damageType,
                            UUID attacker, UUID directEntity, Vec3 position) {
        this(world, uuid, amount, damageType, attacker, directEntity,
                position == null ? null : position.x,
                position == null ? null : position.y,
                position == null ? null : position.z);
    }

    private EntityHurtPacket(String world, UUID uuid, float amount, String damageType,
                             UUID attacker, UUID directEntity, Double x, Double y, Double z) {
        this.world = world;
        this.uuid = uuid;
        this.amount = amount;
        this.damageType = damageType;
        this.attacker = attacker;
        this.directEntity = directEntity;
        this.hasPosition = x != null && y != null && z != null;
        this.x = hasPosition ? x : 0.0D;
        this.y = hasPosition ? y : 0.0D;
        this.z = hasPosition ? z : 0.0D;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityHurtPacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.amount = byteBuf.readFloat();
        this.damageType = byteBuf.readUtf();
        // Appended fields: tolerate a payload written by an older peer.
        this.attacker = readOptionalUuid(byteBuf);
        this.directEntity = readOptionalUuid(byteBuf);
        if (byteBuf.readableBytes() >= 1 && byteBuf.readBoolean()) {
            this.hasPosition = true;
            this.x = byteBuf.readDouble();
            this.y = byteBuf.readDouble();
            this.z = byteBuf.readDouble();
        } else {
            this.hasPosition = false;
            this.x = 0.0D;
            this.y = 0.0D;
            this.z = 0.0D;
        }
    }

    /**
     * @param byteBuf buffer positioned at the optional flag
     * @return the UUID, or {@code null} when the flag is absent/false
     */
    private static UUID readOptionalUuid(FriendlyByteBuf byteBuf) {
        if (byteBuf.readableBytes() < 1 || !byteBuf.readBoolean()) {
            return null;
        }
        return byteBuf.readUUID();
    }

    /**
     * @param byteBuf buffer to write to
     * @param value   UUID to write, or {@code null}
     */
    private static void writeOptionalUuid(FriendlyByteBuf byteBuf, UUID value) {
        byteBuf.writeBoolean(value != null);
        if (value != null) {
            byteBuf.writeUUID(value);
        }
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeFloat(amount);
        byteBuf.writeUtf(damageType);
        writeOptionalUuid(byteBuf, attacker);
        writeOptionalUuid(byteBuf, directEntity);
        byteBuf.writeBoolean(hasPosition);
        if (hasPosition) {
            byteBuf.writeDouble(x);
            byteBuf.writeDouble(y);
            byteBuf.writeDouble(z);
        }
    }

    /**
     * Applies the damage on the owner's server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onRemoteHurt(world, uuid, amount, damageType, attacker, directEntity,
                    hasPosition ? new Vec3(x, y, z) : null);
        }
    }
}
