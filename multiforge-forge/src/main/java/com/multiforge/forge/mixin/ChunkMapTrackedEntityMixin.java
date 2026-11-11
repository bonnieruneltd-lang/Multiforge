package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures every clientbound entity packet the owning server broadcasts.
 *
 * <p>{@code ChunkMap.TrackedEntity.broadcast} is the exact point where vanilla
 * sends movement, metadata, equipment, passenger and effect packets to the
 * players tracking an entity. Hooking it gives MultiForge the same packet stream
 * MultiPaper captures in its {@code ChunkMap} patch, without duplicating the
 * tracker logic.</p>
 *
 * <p>The target is a package-private nested class, so it is addressed by name.
 * The handler runs on the server thread and only serializes the packet there; the
 * peer encoder sees bytes, never the entity. Injections into the nested class
 * require no access to the outer {@code ChunkMap} instance.</p>
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityMixin {

    /** Entity the tracked entry belongs to; Shadow of a package-private field. */
    @Shadow
    @Final
    Entity entity;

    /**
     * @param packet       packet vanilla is about to send to tracking players
     * @param callbackInfo mixin callback
     */
    @Inject(method = "broadcast", at = @At("HEAD"))
    private void multiforge$broadcast(Packet<?> packet, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onEntityPacket(this.entity, packet);
        }
    }
}
