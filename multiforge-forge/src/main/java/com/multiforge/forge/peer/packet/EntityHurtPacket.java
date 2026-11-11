package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Forwards damage applied to a mirrored entity to the server that owns it.
 *
 * <p>A mirror copy is not ticked and its health is not authoritative. Without
 * this packet a hit on a mirrored mob would only change the local copy and be
 * overwritten by the owner's next metadata update, so the mob would not be
 * damageable from every server. MultiPaper forwards the whole
 * {@code DamageSource}; MultiForge carries the damage type key and the amount,
 * which covers the common melee/environment cases without serializing source
 * entities that do not exist on the owner. Attacker attribution in death
 * messages is therefore lost for cross-server hits (documented in
 * {@code KNOWN_ISSUES.md}).</p>
 *
 * <p>Wire layout: world name, entity UUID, amount, damage type id. Decoding only
 * reads the buffer; {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class EntityHurtPacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final float amount;
    private final String damageType;

    /**
     * @param world      logical world name
     * @param uuid       entity UUID
     * @param amount     damage amount
     * @param damageType registry id of the damage type, e.g. {@code minecraft:generic}
     */
    public EntityHurtPacket(String world, UUID uuid, float amount, String damageType) {
        this.world = world;
        this.uuid = uuid;
        this.amount = amount;
        this.damageType = damageType;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityHurtPacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.amount = byteBuf.readFloat();
        this.damageType = byteBuf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeFloat(amount);
        byteBuf.writeUtf(damageType);
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
            server.entities().onRemoteHurt(world, uuid, amount, damageType);
        }
    }
}
