package com.multiforge.forge.mixin;

import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read access to the private raid collections of vanilla.
 *
 * <p>1.20.1 keeps {@code Raids.raidMap} private, so the fight synchronizer cannot
 * iterate active raids without a patch. An accessor mixin is the smallest hook
 * that keeps the vanilla class untouched; the map is exposed read-only and only
 * mutated from the server thread by {@code FightSyncManager}.</p>
 */
@Mixin(Raids.class)
public interface RaidsAccessor {

    /**
     * @return live map of raid id to raid
     */
    @Accessor("raidMap")
    Map<Integer, Raid> multiforge$raidMap();
}
