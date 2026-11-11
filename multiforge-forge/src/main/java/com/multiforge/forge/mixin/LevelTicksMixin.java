package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.CurrentTickLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.BiConsumer;

/**
 * Filters scheduled block and fluid ticks by chunk ownership.
 *
 * <p>Redstone, hoppers, scheduled fluids and every other "tick later" mechanic are
 * executed by {@code LevelTicks.tick(long, int, BiConsumer)}. MultiPaper patches
 * this iterator so only the owning server runs those ticks. Instead of rewriting
 * the iterator, MultiForge wraps the consumer and drops positions that belong to
 * chunks this server does not own.</p>
 *
 * <p>The level is unknown to {@code LevelTicks}, so it is taken from
 * {@link CurrentTickLevel}, which is set by {@link ServerLevelMixin} around the
 * level tick. When no level is set (client side, tests) the consumer is passed
 * through unchanged.</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin {

    /**
     * Wraps the tick consumer.
     *
     * @param original consumer supplied by {@code ServerLevel.tick}
     * @return filtered consumer
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ModifyVariable(method = "tick", at = @At("HEAD"), argsOnly = true, index = 3)
    private BiConsumer multiforge$filterByOwnership(BiConsumer original) {
        ServerLevel level = CurrentTickLevel.get();
        if (level == null) {
            return original;
        }
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || server.isStopping() || !server.config().chunkSync()) {
            return original;
        }
        return (BiConsumer<BlockPos, Object>) (pos, type) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && server.chunks().shouldTick(level, chunk)) {
                original.accept(pos, type);
            }
        };
    }
}
