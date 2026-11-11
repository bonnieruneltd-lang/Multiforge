package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Announces entity removals to the other servers.
 *
 * <p>{@code Entity.setRemoved} is the single funnel through which vanilla
 * discards, kills or unloads an entity. The owning server forwards life-ending
 * removals to the servers that mirror the entity; the
 * {@code EntitySyncManager} filters chunk unloads (which do not end the entity)
 * and removals already applied from a peer, so this hook cannot echo.</p>
 *
 * <p>Injection is at HEAD because the entity's UUID, level and chunk position
 * are still valid there; after the call the entity is marked removed but the
 * fields are still readable, so a later injection point would also work. The
 * handler runs on the server thread.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /**
     * @param reason       vanilla removal reason
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void multiforge$onRemoved(Entity.RemovalReason reason, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onEntityRemoved((Entity) (Object) this, reason);
        }
    }
}
