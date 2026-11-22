package com.multiforge.forge.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes the cached tab-list display name of a player.
 *
 * <p>Vanilla only refreshes the field through {@code refreshTabListName()}, which
 * queries the Forge event; a mirror copy must adopt the value sent by the server
 * that hosts the player instead. Setting {@code hasTabListName} first stops the
 * next {@code getTabListDisplayName()} call from overwriting it with the local
 * (empty) Forge event result.</p>
 *
 * <p>Server thread only.</p>
 */
@Mixin(ServerPlayer.class)
public interface ServerPlayerAccessor {

    /**
     * @param displayName display name to cache
     */
    @Accessor("tabListDisplayName")
    void multiforge$tabListDisplayName(Component displayName);

    /**
     * @param hasName whether the cached display name is authoritative
     */
    @Accessor("hasTabListName")
    void multiforge$hasTabListName(boolean hasName);
}
