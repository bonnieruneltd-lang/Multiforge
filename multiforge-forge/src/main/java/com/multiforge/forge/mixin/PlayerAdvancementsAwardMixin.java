package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures a successful advancement criterion award.
 *
 * <p>{@code PlayerAdvancements.award} returns {@code true} only when the
 * criterion was not already completed, so the injection at RETURN reports real
 * progress and not a duplicate. The player field is shadowed because
 * {@code PlayerAdvancements} has no public getter for it.</p>
 *
 * <p>The hook runs on the server thread. The manager suppresses the echo of an
 * award that arrived from a peer; only the server hosting the connection
 * originates packets.</p>
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsAwardMixin {

    /** Owner of these advancements. */
    @Shadow
    private ServerPlayer player;

    /**
     * @param advancement  advancement that was awarded
     * @param criterion    criterion name
     * @param callbackInfo mixin callback with vanilla's result
     */
    @Inject(method = "award", at = @At("RETURN"))
    private void multiforge$onAward(Advancement advancement, String criterion,
                                    CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!Boolean.TRUE.equals(callbackInfo.getReturnValue())) {
            return;
        }
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && player != null) {
            server.progress().onAdvancementAwarded(player, advancement, criterion);
        }
    }
}
