package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards hits on mirrored entities to the server that owns the chunk.
 *
 * <p>Only the owner may change health, but a player connected to another server
 * can still hit the copy standing in front of them. Cancelling local damage and
 * sending {@code EntityHurtPacket} keeps one authoritative health value; the
 * owner's metadata update then reaches the mirror through the normal entity
 * packet stream. This mirrors MultiPaper's {@code LivingEntity.hurt} patch
 * (which forwards the full {@code DamageSource}).</p>
 *
 * <p>Subclasses that override {@code hurt} without calling {@code super} (for
 * example {@code Player}, which is not mirrored) are not covered; ordinary
 * animals and monsters reach {@link LivingEntity#hurt}. The injection runs on
 * the server thread and only schedules a peer write.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {

    /**
     * @param source       damage source
     * @param amount       damage amount
     * @param callbackInfo mixin callback carrying vanilla's result
     */
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void multiforge$forwardHurt(DamageSource source, float amount,
                                        CallbackInfoReturnable<Boolean> callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && server.entities().onEntityHurt((LivingEntity) (Object) this, source, amount)) {
            // The owner will apply the damage; report success so the attacker's
            // command/hit handling behaves as if the hit landed.
            callbackInfo.setReturnValue(true);
        }
    }
}
