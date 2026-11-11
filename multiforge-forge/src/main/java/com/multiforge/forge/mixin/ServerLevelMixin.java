package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.CurrentTickLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Enforces MultiForge's chunk ownership for ticking.
 *
 * <p>MultiPaper decides per chunk whether the local server may tick it; servers
 * that do not own a chunk keep it loaded and synchronized but do not simulate it.
 * That is what prevents two servers from running the same redstone circuit or mob
 * AI. This mixin gates the two entry points where simulation happens:</p>
 * <ul>
 *   <li>{@code tickChunk}: random ticks, ice/snow, thunder and spawning;</li>
 *   <li>{@code tickNonPassenger}: entity AI, movement and block interactions.</li>
 * </ul>
 *
 * <p>Block and fluid ticks are gated in {@link LevelTicksMixin}. Players always tick
 * locally, because the server that accepted the connection must keep the player
 * simulated even when it does not own the chunk (it immediately requests ownership
 * through {@code tickChunk} in that case).</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * Publishes the currently ticking level for {@link LevelTicksMixin} and clears
     * it afterwards.
     *
     * @param hasTimeLeft  vanilla time budget supplier
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void multiforge$beginTick(BooleanSupplier hasTimeLeft, CallbackInfo callbackInfo) {
        CurrentTickLevel.set((ServerLevel) (Object) this);
    }

    /**
     * Clears the ticking-level thread local.
     *
     * @param hasTimeLeft  vanilla time budget supplier
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void multiforge$endTick(BooleanSupplier hasTimeLeft, CallbackInfo callbackInfo) {
        CurrentTickLevel.clear();
    }

    /**
     * Prevents ticking chunks this server does not own, requesting ownership when
     * the chunk is up for simulation.
     *
     * @param chunk           chunk that would tick
     * @param randomTickSpeed random tick speed
     * @param callbackInfo    mixin callback
     */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void multiforge$tickChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || server.isStopping() || !server.config().chunkSync()) {
            return;
        }
        if (!server.chunks().shouldTick((ServerLevel) (Object) this, chunk)) {
            callbackInfo.cancel();
        }
    }

    /**
     * Prevents entity simulation in chunks owned by another server.
     *
     * @param entity       entity that would tick
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void multiforge$tickEntity(Entity entity, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || server.isStopping() || !server.config().chunkSync()) {
            return;
        }
        if (entity instanceof Player) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        LevelChunk chunk = level.getChunkSource().getChunkNow(entity.chunkPosition().x, entity.chunkPosition().z);
        if (chunk == null) {
            return;
        }
        if (!server.chunks().shouldTick(level, chunk)) {
            callbackInfo.cancel();
        }
    }
}
