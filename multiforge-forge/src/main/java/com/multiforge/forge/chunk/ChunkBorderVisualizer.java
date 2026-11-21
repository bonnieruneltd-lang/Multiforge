package com.multiforge.forge.chunk;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.protocol.ChunkKey;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3f;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the ownership state of the chunk a player stands in.
 *
 * <p>This is the implementation behind {@code debug.chunkBorders}, which used to
 * be a declared but silent no-op option (TODO 16). MultiPaper's {@code /mpdebug}
 * has the same idea: particle lines on the current chunk border tell an operator
 * at a glance whether the chunk is owned by this server or by another one,
 * without reading the log.</p>
 *
 * <p>The colours are:</p>
 * <ul>
 *   <li><b>green</b> - owned by this server (it ticks the chunk);</li>
 *   <li><b>red</b> - owned by another server (this one only mirrors it);</li>
 *   <li><b>white</b> - no known owner (unloaded/idle chunk).</li>
 * </ul>
 *
 * <p>Particles are sent to the toggling player only, once every ten ticks, at the
 * player's feet. That is cheap enough for a debug feature and does not spam other
 * players. The feature is opt-in twice: {@code debug.chunkBorders} must be true in
 * the config, and a player must run {@code /multiforge borders}. Toggling the
 * config off clears the viewer set.</p>
 *
 * <p>Threading: the command and {@link #tick()} run on the server thread; the
 * viewer set is concurrent so a command can never race shutdown.</p>
 */
public final class ChunkBorderVisualizer {

    /** Ticks between particle refreshes; 10 = twice per second, enough to follow a walking player. */
    private static final int REFRESH_TICKS = 10;

    private static final DustParticleOptions OWNED =
            new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.2F), 1.0F);
    private static final DustParticleOptions FOREIGN =
            new DustParticleOptions(new Vector3f(1.0F, 0.2F, 0.2F), 1.0F);
    private static final DustParticleOptions UNOWNED =
            new DustParticleOptions(new Vector3f(1.0F, 1.0F, 1.0F), 1.0F);

    private final MultiForgeServer server;
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private int tickCounter;

    /**
     * @param server runtime that owns this visualizer
     */
    public ChunkBorderVisualizer(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Toggles the visualization for one player.
     *
     * @param player player that ran the command
     * @return {@code true} when the visualization is now on, {@code false} when it
     *         was turned off or the feature is disabled in the config
     */
    public boolean toggle(ServerPlayer player) {
        if (!server.config().debugChunkBorders()) {
            return false;
        }
        UUID id = player.getUUID();
        if (viewers.remove(id)) {
            return false;
        }
        viewers.add(id);
        return true;
    }

    /**
     * @param player player to check
     * @return {@code true} when the player enabled the borders
     */
    public boolean isEnabled(ServerPlayer player) {
        return viewers.contains(player.getUUID());
    }

    /**
     * Sends the border particles for every enabled player.
     *
     * <p>Called from {@link MultiForgeServer#tick()} on the server thread.</p>
     */
    public void tick() {
        if (!server.config().debugChunkBorders()) {
            viewers.clear();
            return;
        }
        if (viewers.isEmpty() || ++tickCounter % REFRESH_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
            if (isEnabled(player) && player.level() instanceof ServerLevel level) {
                draw(player, level);
            }
        }
    }

    /**
     * Draws the border of the player's current chunk.
     *
     * @param player player to send particles to
     * @param level  level the player is in
     */
    private void draw(ServerPlayer player, ServerLevel level) {
        ChunkPos pos = player.chunkPosition();
        ChunkKey key = server.chunks().keyOf(level, pos);
        String owner = server.chunks().ownerName(key);
        DustParticleOptions particle = owner == null ? UNOWNED
                : (server.serverName().equals(owner) ? OWNED : FOREIGN);
        double y = Mth.floor(player.getY()) + 1.0;
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (int offset = 0; offset <= 16; offset += 2) {
            send(player, particle, minX + offset, y, minZ);
            send(player, particle, minX + offset, y, minZ + 16);
            send(player, particle, minX, y, minZ + offset);
            send(player, particle, minX + 16, y, minZ + offset);
        }
    }

    /**
     * Sends one particle to one player, ignoring distance limits.
     *
     * @param player   receiver
     * @param particle particle type and colour
     * @param x        world X
     * @param y        world Y
     * @param z        world Z
     */
    private static void send(ServerPlayer player, DustParticleOptions particle, double x, double y, double z) {
        player.connection.send(new ClientboundLevelParticlesPacket(
                particle, true, x, y, z, 0.0F, 0.0F, 0.0F, 0.0F, 1));
    }

    /** Clears the viewer set, called when the server stops. */
    public void clear() {
        viewers.clear();
    }
}
