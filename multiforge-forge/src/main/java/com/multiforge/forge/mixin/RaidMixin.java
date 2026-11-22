package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
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
        // A raid whose centre chunk is not loaded here cannot be simulated; do not
        // request ownership for a chunk this server does not even hold (vanilla
        // uses getChunkIfLoaded for the same reason).
        if (level.getChunkSource().getChunkNow(center.getX() >> 4, center.getZ() >> 4) == null) {
            callbackInfo.cancel();
            return;
        }
        if (!server.chunks().shouldTickAt(level, center.getX(), center.getZ())) {
            callbackInfo.cancel();
        }
    }

    /**
     * Pushes a stopped raid to the peers immediately.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "stop", at = @At("TAIL"))
    private void multiforge$afterStop(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRaidStopped((Raid) (Object) this);
        }
    }

    /**
     * Pushes a newly spawned wave immediately.
     *
     * @param pos          spawn position
     * @param callbackInfo mixin callback
     */
    @Inject(method = "spawnGroup", at = @At("TAIL"))
    private void multiforge$afterSpawnGroup(BlockPos pos, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRaidChanged((Raid) (Object) this);
        }
    }

    /**
     * Pushes a raider join immediately.
     *
     * @param wave         wave index
     * @param raider       raider that joined
     * @param pos          join position
     * @param existing     whether the raider already existed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "joinRaid", at = @At("TAIL"))
    private void multiforge$afterJoin(int wave, Raider raider, BlockPos pos, boolean existing,
                                      CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRaidChanged((Raid) (Object) this);
        }
    }

    /**
     * Pushes a raider removal immediately.
     *
     * @param raider       raider that left
     * @param countHealth  whether its health was counted
     * @param callbackInfo mixin callback
     */
    @Inject(method = "removeFromRaid", at = @At("TAIL"))
    private void multiforge$afterRemove(Raider raider, boolean countHealth, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRaidChanged((Raid) (Object) this);
        }
    }

    /**
     * Pushes bad omen absorption (the raid's wave budget changes) immediately.
     *
     * @param player       player with bad omen
     * @param callbackInfo mixin callback
     */
    @Inject(method = "absorbBadOmen", at = @At("TAIL"))
    private void multiforge$afterBadOmen(Player player, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.fights().onRaidChanged((Raid) (Object) this);
        }
    }
}
