package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures every statistic increase so it can be mirrored to the cluster.
 *
 * <p>{@code StatsCounter.increment} is the single funnel for statistics that grow
 * (blocks mined, items crafted, deaths, ...). The hook only records the delta;
 * vanilla still writes the value and the file, and
 * {@code PlayerProgressSyncManager} batches the deltas into one peer packet every
 * ten seconds. Injecting at HEAD (rather than replacing the method) keeps
 * vanilla's clamping and dirty tracking untouched.</p>
 */
@Mixin(StatsCounter.class)
public abstract class StatsCounterIncrementMixin {

    /**
     * @param player       player whose statistic grows
     * @param stat         statistic
     * @param amount       increase
     * @param callbackInfo mixin callback
     */
    @Inject(method = "increment", at = @At("HEAD"))
    private void multiforge$onStatIncrease(Player player, Stat<?> stat, int amount,
                                           CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.progress().onStatIncrease(player, stat, amount);
        }
    }
}
