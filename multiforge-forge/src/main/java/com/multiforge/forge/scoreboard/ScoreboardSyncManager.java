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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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
 * <p>The sync works at the packet level: every clientbound scoreboard packet
 * vanilla broadcasts is also sent to the peers ({@link #onLocalPacket}), and the
 * receiving server replays the same mutation on its own
 * {@link ServerScoreboard} ({@link #onRemotePacket}). Replaying through the
 * vanilla scoreboard API rather than writing a save file means the local dirty
 * listeners, display-slot broadcasts and sidebar ordering behave exactly as if
 * an admin had run the command locally.</p>
 *
 * <p>Only the main scoreboard is synchronized, matching upstream: per-player
 * sidebars and scoreboards created by mods are not part of the world and would
 * need an ownership model of their own. Boss bars are also not synchronized;
 * vanilla keeps them per player and MultiPaper has no equivalent either (see
 * {@code docs/TODO.md}).</p>
 *
 * <p>Threading: {@link #onLocalPacket} runs on the server thread inside the
 * {@code PlayerList.broadcastAll} hook. A remote packet only hops to the server
 * thread and then replays the vanilla mutation. A thread-local
 * {@code APPLYING} flag suppresses echo so a replayed change is not sent back to
 * the peer that produced it. All mutations happen on the server thread; clients
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
     * Forwards a packet vanilla just broadcast to the other servers.
     *
     * <p>Called from the {@code PlayerList.broadcastAll} mixin. Packets that are
     * not scoreboard updates, packets replayed from a peer and every packet while
     * {@code synchronization.scoreboards} is off are ignored.</p>
     *
     * @param packet packet vanilla broadcast locally
     */
    public void onLocalPacket(Packet<?> packet) {
        if (!server.config().syncScoreboards() || Boolean.TRUE.equals(APPLYING.get())) {
            return;
        }
        if (!isScoreboardPacket(packet)) {
            return;
        }
        try {
            server.peers().broadcast(new ScoreboardUpdatePacket(packet, criteriaOf(packet)));
        } catch (RuntimeException e) {
            // A modded packet subclass may not be registered with the vanilla
            // protocol; never let scoreboard propagation break the tick.
            LOGGER.warn("Could not forward scoreboard packet {}: {}",
                    packet.getClass().getName(), e.toString());
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
            Set<Objective> sent = new HashSet<>();
            for (int slot = 0; slot < Scoreboard.DISPLAY_SLOTS; slot++) {
                Objective objective = scoreboard.getDisplayObjective(slot);
                if (objective == null || !sent.add(objective)) {
                    continue;
                }
                for (Packet<?> packet : scoreboard.getStartTrackingPackets(objective)) {
                    String criteria = packet instanceof ClientboundSetObjectivePacket
                            ? objective.getCriteria().getName() : null;
                    sendTo(peerName, new ScoreboardUpdatePacket(packet, criteria));
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
     * @param packet any broadcast packet
     * @return {@code true} for the four clientbound scoreboard packet types
     */
    private static boolean isScoreboardPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetObjectivePacket
                || packet instanceof ClientboundSetScorePacket
                || packet instanceof ClientboundSetDisplayObjectivePacket
                || packet instanceof ClientboundSetPlayerTeamPacket;
    }

    /**
     * @param packet broadcast packet
     * @return criteria of a newly added objective, {@code null} for other packets
     */
    private String criteriaOf(Packet<?> packet) {
        if (packet instanceof ClientboundSetObjectivePacket objective
                && objective.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD) {
            Objective local = server.server().getScoreboard().getObjective(objective.getObjectiveName());
            if (local != null) {
                return local.getCriteria().getName();
            }
        }
        return null;
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

