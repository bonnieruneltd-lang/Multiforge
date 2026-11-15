package com.multiforge.forge.mixin;

import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw view of the vanilla player registry.
 *
 * <p>MultiForge adds and removes {@code ExternalPlayer} copies without running
 * the login/logout machinery. Paper exposes {@code addPlayer}/{@code removePlayer}
 * helpers in a patch; Forge has no equivalent, so this accessor mixin gives the
 * mirror manager the three maps it has to keep consistent:</p>
 * <ul>
 *   <li>{@code players}: iteration order used by the tab list and broadcasts;</li>
 *   <li>{@code playersByUUID}: duplicate-login checks and {@code getPlayer};</li>
 *   <li>{@code stats}/{@code advancements}: per-player objects that would
 *       otherwise stay registered after a mirror is discarded.</li>
 * </ul>
 *
 * <p>Only the server thread may call these methods.</p>
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {

    /**
     * @return the mutable player list
     */
    @Accessor("players")
    List<ServerPlayer> multiforge$players();

    /**
     * @return the mutable UUID index
     */
    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> multiforge$playersByUUID();

    /**
     * @return statistics objects, keyed by UUID; unused entries leak on removal
     */
    @Accessor("stats")
    Map<UUID, ServerStatsCounter> multiforge$stats();

    /**
     * @return advancement objects, keyed by UUID; their reload listeners must be
     *         stopped when a mirror copy is discarded
     */
    @Accessor("advancements")
    Map<UUID, PlayerAdvancements> multiforge$advancements();
}
