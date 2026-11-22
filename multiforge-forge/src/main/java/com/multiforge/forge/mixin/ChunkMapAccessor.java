package com.multiforge.forge.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for the vanilla per-chunk save.
 *
 * <p>{@code ChunkMap.save(ChunkAccess)} is private, but it is exactly the
 * operation a fenced handover needs: it writes the region, entity and POI data of
 * one chunk through the normal {@code ChunkStorage} path, which MultiForge already
 * intercepts. {@code ServerChunkCache.save(boolean)} would save every loaded chunk
 * and stall the tick for hundreds of writes, so the handover uses this accessor
 * instead.</p>
 *
 * <p>Server thread only; the method touches world state.</p>
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    /**
     * Saves one chunk through the vanilla path.
     *
     * @param chunk chunk to save
     * @return whether the chunk was written
     */
    @Invoker("save")
    boolean multiforge$save(ChunkAccess chunk);
}
