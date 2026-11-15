package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards hits on a mirrored player to the server that hosts the connection.
 *
 * <p>{@code ServerPlayer.hurt} performs invulnerability, game mode and Forge
 * attack-event checks before calling {@code Player.hurt}, which in turn checks
 * more before {@code LivingEntity.hurt}. Those checks reflect this server's copy
 * of the player (which is never simulated), not the authoritative state on the
 * origin. A hit on the copy must therefore be forwarded at HEAD, before any
 * local rejection, so the origin can apply its own PvP and invulnerability
 * rules.</p>
 *
 * <p>{@link EntitySyncManager#onEntityHurt} only forwards when the player is an
 * {@code ExternalPlayer}; a real local player returns {@code false} and vanilla
 * handles the hit. The injection runs on the server thread and only schedules a
 * peer write.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerHurtMixin {

    /**
     * @param source       damage source
     * @param amount       damage amount
     * @param callbackInfo mixin callback carrying vanilla's result
     */
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void multiforge$forwardHurt(DamageSource source, float amount,
                                        CallbackInfoReturnable<Boolean> callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && server.entities().onEntityHurt((ServerPlayer) (Object) this, source, amount)) {
            // The owner applies the damage; report success so the attacker's
            // command/hit handling behaves as if the hit landed.
            callbackInfo.setReturnValue(true);
        }
    }
}
