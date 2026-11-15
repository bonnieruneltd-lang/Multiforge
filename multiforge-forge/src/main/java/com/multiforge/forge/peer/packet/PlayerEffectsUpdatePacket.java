package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Replicates one status effect change of a player to the servers that hold a
 * mirror copy.
 *
 * <p>Port of MultiPaper's {@code EntityUpdateEffectPacket} and
 * {@code MultiPaperEffectsHandler}. Upstream hooks
 * {@code LivingEntity.onEffectAdded/onEffectUpdated/onEffectRemoved}; MultiForge
 * has no such hook without another mixin, so the owning server compares the
 * player's effect collection once per tick ({@code PlayerStateSyncManager}) and
 * sends an add/update when an effect appears, changes its amplifier or flags, or
 * has its duration extended. The duration is deliberately not re-sent while it
 * counts down: that would emit a packet every tick and the mirror never ticks the
 * effect itself.</p>
 *
 * <p>An update carries the whole {@link net.minecraft.world.effect.MobEffectInstance}
 * as NBT (vanilla {@code save}/{@code load}), so amplifier, duration, ambient,
 * visible and icon flags all transfer. A removal only needs the effect id, which
 * keeps the packet small; the mirror calls {@code removeEffect} with the same
 * registry entry.</p>
 *
 * <p>Wire layout: player UUID, remove flag, effect registry id and (for an
 * add/update) the effect tag. Decoding only reads the buffer;
 * {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class PlayerEffectsUpdatePacket extends PeerPacket {

    private final UUID uuid;
    private final boolean remove;
    private final String effectId;
    private final CompoundTag data;

    /**
     * @param uuid     owner player UUID
     * @param remove   {@code true} when the effect ended
     * @param effectId registry id, e.g. {@code minecraft:speed}
     * @param data     {@code MobEffectInstance.save} tag; ignored when {@code remove}
     */
    public PlayerEffectsUpdatePacket(UUID uuid, boolean remove, String effectId, CompoundTag data) {
        this.uuid = uuid;
        this.remove = remove;
        this.effectId = effectId;
        this.data = data;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerEffectsUpdatePacket(FriendlyByteBuf byteBuf) {
        this.uuid = byteBuf.readUUID();
        this.remove = byteBuf.readBoolean();
        this.effectId = byteBuf.readUtf();
        this.data = byteBuf.readNbt();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeBoolean(remove);
        byteBuf.writeUtf(effectId);
        byteBuf.writeNbt(data);
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return {@code true} when the effect ended */
    public boolean remove() {
        return remove;
    }

    /** @return registry id of the effect */
    public String effectId() {
        return effectId;
    }

    /** @return serialized {@code MobEffectInstance}, {@code null} for a removal */
    public CompoundTag data() {
        return data;
    }

    /**
     * Applies the change on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerState().onRemoteEffect(uuid, remove, effectId, data);
        }
    }
}
