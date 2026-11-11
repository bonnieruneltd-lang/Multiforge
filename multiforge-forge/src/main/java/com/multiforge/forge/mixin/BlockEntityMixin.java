package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects block entity content changes (chest contents, signs, ...).
 *
 * <p>{@code BlockEntity.setChanged()} is called whenever a block entity marks
 * itself dirty, which is the point where MultiPaper schedules a block entity sync.
 * The change is queued and sent once per tick per block entity.</p>
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    /**
     * Queues the block entity for the next synchronization pass.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setChanged", at = @At("HEAD"))
    private void multiforge$setChanged(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.chunks().queueBlockEntity((BlockEntity) (Object) this);
        }
    }
}
