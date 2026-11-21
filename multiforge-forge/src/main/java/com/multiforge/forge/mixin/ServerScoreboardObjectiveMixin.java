package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards every main-scoreboard mutation to the peers.
 *
 * <p>Relying on {@code PlayerList.broadcastAll} is not enough on 1.20.1: objective
 * additions/removals are not broadcast at all, and score/display/team packets are
 * only broadcast for players that already track the main scoreboard. A server with no
 * players (or a freshly started peer) would therefore miss changes. These hooks cover
 * the {@code ServerScoreboard} callbacks directly, which run for commands, data packs
 * and mods alike, and the manager sends the same vanilla packets to the peers.</p>
 *
 * <p>Replays from a peer are suppressed by the manager's thread-local flag, so the
 * receiver does not echo a change back. The local clients are still served by
 * vanilla's own broadcast logic.</p>
 */
@Mixin(ServerScoreboard.class)
public abstract class ServerScoreboardObjectiveMixin {

    /**
     * @param objective    objective that was added
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onObjectiveAdded", at = @At("RETURN"))
    private void multiforge$objectiveAdded(Objective objective, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onObjectiveAdded(objective);
        }
    }

    /**
     * @param objective    objective whose display name or render type changed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onObjectiveChanged", at = @At("RETURN"))
    private void multiforge$objectiveChanged(Objective objective, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onObjectiveChanged(objective);
        }
    }

    /**
     * @param objective    objective that was removed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onObjectiveRemoved", at = @At("RETURN"))
    private void multiforge$objectiveRemoved(Objective objective, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onObjectiveRemoved(objective);
        }
    }

    /**
     * @param score        score that changed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onScoreChanged", at = @At("RETURN"))
    private void multiforge$scoreChanged(Score score, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onScoreChanged(score);
        }
    }

    /**
     * @param owner        score owner
     * @param objective    objective
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onPlayerScoreRemoved", at = @At("RETURN"))
    private void multiforge$scoreRemoved(String owner, Objective objective, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onPlayerScoreRemoved(owner, objective);
        }
    }
    /**
     * @param slot         display slot
     * @param objective    objective shown in the slot, may be null
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setDisplayObjective", at = @At("RETURN"))
    private void multiforge$displayObjective(int slot, Objective objective, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onDisplayObjective(slot, objective);
        }
    }

    /**
     * @param team         team that was added
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onTeamAdded", at = @At("RETURN"))
    private void multiforge$teamAdded(PlayerTeam team, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onTeamChanged(team);
        }
    }

    /**
     * @param team         team that changed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onTeamChanged", at = @At("RETURN"))
    private void multiforge$teamChanged(PlayerTeam team, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onTeamChanged(team);
        }
    }

    /**
     * @param team         team that was removed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onTeamRemoved", at = @At("RETURN"))
    private void multiforge$teamRemoved(PlayerTeam team, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onTeamRemoved(team);
        }
    }

    /**
     * @param player       player name
     * @param team         team
     * @param callbackInfo mixin callback with vanilla's success flag
     */
    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void multiforge$playerAddedToTeam(String player, PlayerTeam team,
                                               CallbackInfoReturnable<Boolean> callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && Boolean.TRUE.equals(callbackInfo.getReturnValue())) {
            server.scoreboards().onPlayerTeamChanged(team, player, true);
        }
    }

    /**
     * @param player       player name
     * @param team         team
     * @param callbackInfo mixin callback
     */
    @Inject(method = "removePlayerFromTeam", at = @At("RETURN"))
    private void multiforge$playerRemovedFromTeam(String player, PlayerTeam team, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onPlayerTeamChanged(team, player, false);
        }
    }

}
