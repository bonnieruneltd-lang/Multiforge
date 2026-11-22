package com.multiforge.forge.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to a raid's vanilla boss bar.
 *
 * <p>When a raid's centre chunk moves to another server, the old owner must stop
 * showing its own {@code ServerBossEvent}; otherwise the player sees the old bar
 * and the new owner's bar at the same time, because the two fight objects have
 * different UUIDs. The event itself is private, so this accessor exposes it for
 * {@code FightSyncManager#onChunkOwnershipLost}. It is never used to tick or
 * mutate the bar on a server that owns the raid.</p>
 */
@Mixin(Raid.class)
public interface RaidAccessor {

    /**
     * @return the raid's boss event
     */
    @Accessor("raidEvent")
    ServerBossEvent multiforge$raidEvent();
}
