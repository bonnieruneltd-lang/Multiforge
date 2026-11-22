package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restricts the ender dragon fight to the server that owns the end island.
 *
 * <p>The fight object exists on every server that loaded the end dimension, and
 * vanilla ticks it locally: it scans the arena, spawns and heals the dragon,
 * advances the respawn animation and removes gateway crystals. Two servers doing
 * that would duplicate the dragon and fight phase progress. The fight is
 * transferred by {@code FightSyncManager}; only a server whose
 * {@code ChunkManager.shouldTickAt} allows the island centre chunk (0, 0) — the
 * same predicate chunks and entities use — may tick it.</p>
 *
 * <p>A server without a player in the end never owns that chunk, so it also never
 * adds the dragon's boss bar; {@code BossEventSyncManager} forwards the owner's bar
 * instead. The check is skipped when MultiForge is not active or chunk sync is
 * off.</p>
 */
@Mixin(EndDragonFight.class)
public abstract class EndDragonFightMixin {

    /** Level the fight belongs to. */
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void multiforge$tickOnlyOnOwner(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || server.isStopping() || !server.config().chunkSync()) {
            return;
        }
        if (!server.chunks().shouldTickAt(level, 0, 0)) {
            callbackInfo.cancel();
        }
    }
}
