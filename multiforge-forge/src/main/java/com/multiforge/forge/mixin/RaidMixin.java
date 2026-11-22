package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restricts raid simulation to the server that owns the raid's centre chunk.
 *
 * <p>A raid is per-level {@code SavedData}: every server that has the world loaded
 * has a {@code Raid} object with the same id, and vanilla ticks all of them. In a
 * cluster that would spawn every wave on every server and duplicate raiders,
 * totem drops and boss-bar progress. {@code FightSyncManager} keeps the copies in
 * sync; this mixin makes only the owner's copy advance, matching MultiPaper's
 * {@code active = isChunkLocal(...)} change. The decision is
 * {@code ChunkManager.shouldTickAt}, the same one used for chunk and entity
 * ticking, so an unowned chunk requests ownership instead of freezing and a
 * draining chunk stops producing changes.</p>
 *
 * <p>The check is skipped when MultiForge is not running or chunk sync is
 * disabled, so a standalone server behaves exactly like vanilla. It runs at the
 * head of {@code tick}, before any field is mutated.</p>
 */
@Mixin(Raid.class)
public abstract class RaidMixin {

    /** Level the raid belongs to. */
    @Shadow
    @Final
    private ServerLevel level;

    /** Raid centre; the chunk that decides which server may tick it. */
    @Shadow
    private BlockPos center;

    /**
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void multiforge$tickOnlyOnOwner(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || server.isStopping() || !server.config().chunkSync()) {
            return;
        }
        if (!server.chunks().shouldTickAt(level, center.getX(), center.getZ())) {
            callbackInfo.cancel();
        }
    }
}
