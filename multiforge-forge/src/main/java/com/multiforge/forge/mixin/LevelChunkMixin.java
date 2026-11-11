package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Broadcasts block and block entity changes to the other servers.
 *
 * <p>MultiPaper hooks {@code Level.setBlock} and {@code LevelChunk.setBlockEntity}.
 * Using {@code LevelChunk} keeps the hook close to where the authoritative change
 * happens and lets the mixin verify that the chunk is already registered with the
 * level, which filters out changes made while a chunk is deserialized from disk or
 * generated (those must not be broadcast, they are part of the chunk payload).</p>
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    /**
     * Broadcasts a successful block state change.
     *
     * @param pos            changed position
     * @param state          new state
     * @param isMoving       vanilla moving flag
     * @param callbackInfo   mixin callback carrying the previous state
     */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void multiforge$setBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> callbackInfo) {
        if (callbackInfo.getReturnValue() == null) {
            return;
        }
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || !server.master().isConnected()) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getChunkSource().getChunkNow(self.getPos().x, self.getPos().z) != self) {
            return;
        }
        server.chunks().broadcastBlockChange(level, pos, state);
    }

    /**
     * Queues a block entity change. Batching to one update per tick is done by the
     * chunk manager.
     *
     * @param blockEntity  block entity that was added or replaced
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void multiforge$setBlockEntity(BlockEntity blockEntity, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.chunks().queueBlockEntity(blockEntity);
        }
    }
}
