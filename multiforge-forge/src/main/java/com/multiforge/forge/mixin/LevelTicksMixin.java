package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.common.CurrentTickLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

/**
 * Filters scheduled block and fluid ticks by chunk ownership.
 *
 * <p>Redstone, hoppers, scheduled fluids and every other "tick later" mechanic are
 * executed by {@code LevelTicks.tick(long, int, BiConsumer)}, which calls the
 * consumer once per due position. MultiPaper patches the iterator so only the
 * owning server runs those ticks. MultiForge redirects the consumer invocation and
 * drops positions that belong to chunks this server does not own.</p>
 *
 * <p>The level is unknown to {@code LevelTicks}, so it is taken from
 * {@link CurrentTickLevel}, which is set by {@link ServerLevelMixin} around the
 * level tick. When no level is set (client side, tests) the call is forwarded
 * unchanged.</p>
 *
 * <p>{@code @Redirect} is used instead of wrapping the consumer argument because
 * Mixin 0.8.5 cannot reliably capture the {@code BiConsumer} argument at HEAD.</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin {

    /**
     * Redirects a single scheduled tick execution.
     *
     * @param consumer original consumer supplied by {@code ServerLevel.tick}
     * @param position position of the due tick
     * @param type     block or fluid type
     */
    @SuppressWarnings("unchecked")
    @Redirect(method = "runCollectedTicks",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void multiforge$filterTick(BiConsumer<Object, Object> consumer, Object position, Object type) {
        ServerLevel level = CurrentTickLevel.get();
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (level == null || server == null || server.isStopping() || !server.config().chunkSync()) {
            consumer.accept(position, type);
            return;
        }
        BlockPos pos = (BlockPos) position;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || server.chunks().shouldTick(level, chunk)) {
            consumer.accept(position, type);
        }
    }
}
