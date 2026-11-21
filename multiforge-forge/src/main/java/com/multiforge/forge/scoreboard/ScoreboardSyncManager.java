package com.multiforge.forge.scoreboard;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.ScoreboardUpdatePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Keeps the cluster's main scoreboard identical on every server.
 *
 * <p>Port of MultiPaper's {@code ScoreboardUpdatePacket} handling. Vanilla
 * scoreboards are per-process state: objectives, scores, teams and display slots
 * only exist on the server where they were created, and a client receives them
 * from its own server. On a MultiForge cluster that means a sidebar created by a
 * command on {@code survival1} is invisible on {@code survival2}, and a player who
 * moves between them loses the sidebar, the team colour and the list header.</p>
 *
 * <p>The sync works at the packet level: the {@code ServerScoreboard} hooks in
 * {@code ServerScoreboardObjectiveMixin} report every mutation, the manager sends the
 * equivalent vanilla clientbound packet to the peers, and the receiving server replays
 * the mutation on its own {@link ServerScoreboard} ({@link #onRemotePacket}). Hooking the
 * callbacks instead of {@code PlayerList.broadcastAll} is required on 1.20.1: objective
 * additions/removals are not broadcast at all and score/display/team packets are only
 * broadcast to players that already track the main scoreboard, so a peer with no players
 * would otherwise miss changes. Replaying through the vanilla scoreboard API means the
 * local dirty listeners, display-slot broadcasts and sidebar ordering behave exactly as
 * if an admin had run the command locally.</p>
 *
 * <p>Only the main scoreboard is synchronized, matching upstream: per-player
 * sidebars and scoreboards created by mods are not part of the world and would
 * need an ownership model of their own. Boss bars are also not synchronized;
 * vanilla keeps them per player and MultiPaper has no equivalent either (see
 * {@code docs/TODO.md}).</p>
 *
 * <p>Threading: the {@code ServerScoreboard} hooks run on the server thread. A remote
 * packet only hops to the server thread and then replays the vanilla mutation. A
 * thread-local {@code APPLYING} flag suppresses echo so a replayed change is not sent
 * back to the peer that produced it. All mutations happen on the server thread; clients
 * of this server still receive the vanilla broadcast.</p>
 */
public final class ScoreboardSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Scoreboard");

    /** {@code true} while a packet from a peer is being applied. */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final MultiForgeServer server;

    /**
     * @param server runtime that owns this manager
     */
    public ScoreboardSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Forwards a newly added objective.
     *
     * <p>Mojang's {@code ServerScoreboard.onObjectiveAdded} only marks the scoreboard
     * dirty in 1.20.1; it does not broadcast a clientbound packet (Paper differs, and
     * MultiPaper's patch could rely on that broadcast). Without this explicit hook a
     * new objective would exist only on the server where it was created and every
     * later score/display packet would be dropped by the receivers.</p>
     *
     * @param objective objective that was added
     */
    public void onObjectiveAdded(Objective objective) {
        if (!server.config().syncScoreboards() || objective == null
                || Boolean.TRUE.equals(APPLYING.get())) {
            return;
        }
        broadcast(new ScoreboardUpdatePacket(
                new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD),
                objective.getCriteria().getName()));
    }

    /**
     * Forwards a removed objective (also not broadcast by vanilla).
     *
     * @param objective objective that was removed
     */
    public void onObjectiveRemoved(Objective objective) {
        if (!server.config().syncScoreboards() || objective == null
                || Boolean.TRUE.equals(APPLYING.get())) {
            return;
        }
        broadcast(new ScoreboardUpdatePacket(
                new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE),
                null));
    }

    /**
     * Forwards an objective edit (display name or render type).
     *
     * @param objective objective that changed
     */
    public void onObjectiveChanged(Objective objective) {
        if (objective != null) {
            forward(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE),
                    objective.getCriteria().getName());
        }
    }

    /**
     * Forwards a score change.
     *
     * @param score score that changed
     */
    public void onScoreChanged(Score score) {
        if (score == null || score.getObjective() == null) {
            return;
        }
        if (server.config().debugLogging()) {
            LOGGER.info("Local score change: {} {} = {}", score.getOwner(),
                    score.getObjective().getName(), score.getScore());
        }
        forward(new ClientboundSetScorePacket(ServerScoreboard.Method.CHANGE,
                score.getObjective().getName(), score.getOwner(), score.getScore()), null);
    }

    /**
     * Forwards a score removal.
     *
     * @param owner     score owner
     * @param objective objective the score belonged to
     */
    public void onPlayerScoreRemoved(String owner, Objective objective) {
        if (objective == null) {
            return;
        }
        forward(new ClientboundSetScorePacket(ServerScoreboard.Method.REMOVE,
                objective.getName(), owner, 0), null);
    }

    /**
     * Forwards a display-slot change.
     *
     * @param slot      display slot index
     * @param objective objective to show, {@code null} to clear the slot
     */
    public void onDisplayObjective(int slot, Objective objective) {
        forward(new ClientboundSetDisplayObjectivePacket(slot, objective), null);
    }

    /**
     * Forwards a team creation or edit.
     *
     * @param team team that changed
     */
    public void onTeamChanged(PlayerTeam team) {
        if (team != null) {
            forward(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), null);
        }
    }

    /**
     * Forwards a team removal.
     *
     * @param team team that was removed
     */
    public void onTeamRemoved(PlayerTeam team) {
        if (team != null) {
            forward(ClientboundSetPlayerTeamPacket.createRemovePacket(team), null);
        }
    }

    /**
     * Forwards a player joining or leaving a team.
     *
     * @param team   team
     * @param player player name
     * @param add    {@code true} when the player joined, {@code false} when they left
     */
    public void onPlayerTeamChanged(PlayerTeam team, String player, boolean add) {
        if (team != null && player != null) {
            forward(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, player,
                    add ? ClientboundSetPlayerTeamPacket.Action.ADD
                            : ClientboundSetPlayerTeamPacket.Action.REMOVE), null);
        }
    }

    /**
     * Sends one scoreboard mutation to every peer unless this packet is the replay of a
     * remote mutation.
     *
     * @param packet   vanilla packet describing the mutation
     * @param criteria criteria for a new objective, otherwise {@code null}
     */
    private void forward(Packet<?> packet, String criteria) {
        if (!server.config().syncScoreboards() || Boolean.TRUE.equals(APPLYING.get())) {
            return;
        }
        if (server.config().debugLogging()) {
            LOGGER.info("Forwarding {}", packet.getClass().getSimpleName());
        }
        broadcast(new ScoreboardUpdatePacket(packet, criteria));
    }

    /**
     * @param packet scoreboard update to send to every peer
     */
    private void broadcast(ScoreboardUpdatePacket packet) {
        try {
            server.peers().broadcast(packet);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not broadcast a scoreboard change: {}", e.toString());
        }
    }

    /**
     * Sends the current main scoreboard to a peer that just connected.
     *
     * <p>Only changes are broadcast, so a server that starts (or reconnects) after
     * the sidebar was created would otherwise stay empty until the next score
     * change. This replays the same packets vanilla sends to a joining player:
     * every team, and the objectives currently shown in a display slot (with their
     * criteria). Called by {@code PeerManager} when a peer registered.</p>
     *
     * @param peerName name of the connected peer
     */
    public void onPeerConnected(String peerName) {
        if (peerName == null || !server.config().syncScoreboards()) {
            return;
        }
        server.execute(() -> {
            if (server.isStopping()) {
                return;
            }
            ServerScoreboard scoreboard = server.server().getScoreboard();
            for (PlayerTeam team : scoreboard.getPlayerTeams()) {
                sendTo(peerName, new ScoreboardUpdatePacket(
                        ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), null));
            }
            // Send every objective and its scores, not only the displayed ones: an
            // objective created before this peer connected must exist on the receiver
            // or later score/display packets are dropped. The display slots are sent
            // last so the receiver has the objectives they reference.
            for (Objective objective : scoreboard.getObjectives()) {
                sendTo(peerName, new ScoreboardUpdatePacket(
                        new ClientboundSetObjectivePacket(objective,
                                ClientboundSetObjectivePacket.METHOD_ADD),
                        objective.getCriteria().getName()));
                for (Score score : scoreboard.getPlayerScores(objective)) {
                    sendTo(peerName, new ScoreboardUpdatePacket(
                            new ClientboundSetScorePacket(ServerScoreboard.Method.CHANGE,
                                    objective.getName(), score.getOwner(), score.getScore()),
                            null));
                }
            }
            for (int slot = 0; slot < Scoreboard.DISPLAY_SLOTS; slot++) {
                Objective objective = scoreboard.getDisplayObjective(slot);
                if (objective != null) {
                    sendTo(peerName, new ScoreboardUpdatePacket(
                            new ClientboundSetDisplayObjectivePacket(slot, objective), null));
                }
            }
        });
    }

    /**
     * Sends one scoreboard packet to one peer.
     *
     * @param peerName receiving server
     * @param packet   packet to send
     */
    private void sendTo(String peerName, ScoreboardUpdatePacket packet) {
        server.peers().sendTo(java.util.List.of(peerName), packet);
    }

    /**
     * Drops no state: the scoreboard itself is the state and vanilla persists it
     * with the world. Present for symmetry with the other managers.
     */
    public void clear() {
        // Nothing buffered.
    }

    /**
     * Schedules a scoreboard packet from a peer on the server thread.
     *
     * @param packet received packet
     */
    public void onRemotePacket(ScoreboardUpdatePacket packet) {
        if (server.config().debugLogging()) {
            LOGGER.info("Received scoreboard update from a peer ({} payload bytes, criteria='{}')",
                    packet.payloadLength(), packet.criteria());
        }
        server.execute(() -> apply(packet));
    }

    /**
     * Replays a remote scoreboard mutation locally.
     *
     * @param packet received packet
     */
    private void apply(ScoreboardUpdatePacket packet) {
        if (!server.config().syncScoreboards()) {
            return;
        }
        APPLYING.set(Boolean.TRUE);
        try {
            ServerScoreboard scoreboard = server.server().getScoreboard();
            apply(scoreboard, packet.criteria(), packet.packet());
        } catch (RuntimeException e) {
            LOGGER.warn("Rejected scoreboard update from a peer: {}", e.toString());
        } finally {
            APPLYING.set(Boolean.FALSE);
        }
    }

    /**
     * Applies one decoded vanilla packet to a scoreboard.
     *
     * @param scoreboard receiving main scoreboard
     * @param criteria   criteria for a new objective, empty when absent
     * @param packet     decoded vanilla packet
     */
    private static void apply(ServerScoreboard scoreboard, String criteria, Packet<?> packet) {
        if (packet instanceof ClientboundSetObjectivePacket objectivePacket) {
            applyObjective(scoreboard, criteria, objectivePacket);
        } else if (packet instanceof ClientboundSetScorePacket scorePacket) {
            applyScore(scoreboard, scorePacket);
        } else if (packet instanceof ClientboundSetDisplayObjectivePacket displayPacket) {
            scoreboard.setDisplayObjective(displayPacket.getSlot(),
                    scoreboard.getObjective(displayPacket.getObjectiveName()));
        } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
            applyTeam(scoreboard, teamPacket);
        }
    }

    /**
     * Creates, removes or edits an objective.
     *
     * @param scoreboard receiving scoreboard
     * @param criteria   criteria for a new objective
     * @param packet     vanilla objective packet
     */
    private static void applyObjective(ServerScoreboard scoreboard, String criteria,
                                       ClientboundSetObjectivePacket packet) {
        Objective objective = scoreboard.getObjective(packet.getObjectiveName());
        if (packet.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
            if (objective != null) {
                scoreboard.removeObjective(objective);
            }
            return;
        }
        if (objective == null) {
            if (packet.getMethod() != ClientboundSetObjectivePacket.METHOD_ADD || criteria == null
                    || criteria.isEmpty()) {
                LOGGER.warn("Ignoring scoreboard objective {} without criteria", packet.getObjectiveName());
                return;
            }
            Optional<ObjectiveCriteria> parsed = ObjectiveCriteria.byName(criteria);
            if (parsed.isEmpty()) {
                LOGGER.warn("Unknown scoreboard criteria {} for objective {}", criteria,
                        packet.getObjectiveName());
                return;
            }
            objective = scoreboard.addObjective(packet.getObjectiveName(), parsed.get(),
                    packet.getDisplayName(), packet.getRenderType());
        }
        objective.setDisplayName(packet.getDisplayName());
        objective.setRenderType(packet.getRenderType());
    }

    /**
     * Applies a score change or removal.
     *
     * @param scoreboard receiving scoreboard
     * @param packet     vanilla score packet
     */
    private static void applyScore(ServerScoreboard scoreboard, ClientboundSetScorePacket packet) {
        Objective objective = scoreboard.getObjective(packet.getObjectiveName());
        if (objective == null) {
            return;
        }
        if (packet.getMethod() == ServerScoreboard.Method.CHANGE) {
            Score score = scoreboard.getOrCreatePlayerScore(packet.getOwner(), objective);
            score.setScore(packet.getScore());
        } else {
            scoreboard.resetPlayerScore(packet.getOwner(), objective);
        }
    }

    /**
     * Creates, removes or edits a team and its members.
     *
     * @param scoreboard receiving scoreboard
     * @param packet     vanilla team packet
     */
    private static void applyTeam(ServerScoreboard scoreboard, ClientboundSetPlayerTeamPacket packet) {
        PlayerTeam team = scoreboard.getPlayerTeam(packet.getName());
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            if (team == null) {
                team = scoreboard.addPlayerTeam(packet.getName());
            }
        } else if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            if (team != null) {
                scoreboard.removePlayerTeam(team);
            }
            return;
        }
        if (team == null) {
            return;
        }
        try {
            if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String player : packet.getPlayers()) {
                    scoreboard.addPlayerToTeam(player, team);
                }
            } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String player : packet.getPlayers()) {
                    scoreboard.removePlayerFromTeam(player, team);
                }
            }
        } catch (RuntimeException e) {
            // A player can join/leave concurrently with the packet; the next team
            // broadcast repairs the membership.
            LOGGER.debug("Team membership race for {}: {}", packet.getName(), e.toString());
        }
        PlayerTeam target = team;
        packet.getParameters().ifPresent(parameters -> {
            target.setDisplayName(parameters.getDisplayName());
            target.setPlayerPrefix(parameters.getPlayerPrefix());
            target.setPlayerSuffix(parameters.getPlayerSuffix());
            target.setNameTagVisibility(Team.Visibility.byName(parameters.getNametagVisibility()));
            target.setCollisionRule(Team.CollisionRule.byName(parameters.getCollisionRule()));
            target.setColor(parameters.getColor());
            target.unpackOptions(parameters.getOptions());
        });
    }
}

