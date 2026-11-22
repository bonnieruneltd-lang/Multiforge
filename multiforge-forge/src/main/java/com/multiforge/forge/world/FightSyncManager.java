package com.multiforge.forge.world;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.mixin.RaidAccessor;
import com.multiforge.forge.mixin.RaidsAccessor;
import com.multiforge.forge.peer.packet.DragonFightStatePacket;
import com.multiforge.forge.peer.packet.RaidStatePacket;
import com.multiforge.protocol.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicates the raid and ender-dragon fight state that does not live in a chunk.
 *
 * <p>A {@code Raid} and the {@code EndDragonFight} are per-level {@code SavedData}:
 * the raid id, wave counters, raider UUIDs and the raid centre, and the dragon
 * UUID, kill flags, gateway list and respawn phase. They are written to
 * {@code data/raids.dat} and the level data, never to the chunk payload, so the
 * chunk data plane, the entity stream and the boss-bar packet cannot move them
 * when a fight's area changes owner. This manager closes that gap, matching
 * MultiPaper's {@code Sync raids} and {@code Sync ender dragon fight} patches.</p>
 *
 * <p>Only the server that owns the fight's centre chunk serializes state
 * ({@link com.multiforge.forge.chunk.ChunkManager#isLocallyOwned}). It compares
 * its serialized state once per second, broadcasts a {@link RaidStatePacket} when
 * a raid changed (and one removal packet when it stopped) and a
 * {@link DragonFightStatePacket} when the fight data changed. The receiver never
 * ticks the fight (see {@code RaidMixin} and {@code EndDragonFightMixin}); it only
 * keeps its copy current, so exactly one server simulates a fight at any time.</p>
 *
 * <p>Handover: the old owner sends the relevant snapshots directly to the new
 * owner before the fence ({@link #sendRelevantTo}); the periodic broadcast also
 * repairs a packet lost during the transfer. Ownership epochs fence late
 * snapshots, and a server that already owns the centre chunk ignores remote state
 * because it is authoritative.</p>
 *
 * <p>Threading: {@link #tick()} and {@link #onChunkOwnershipLost} run on the
 * server thread, and remote packets are marshalled there with
 * {@code MultiForgeServer.execute}. The hash maps are concurrent because shutdown
 * and status queries may read them from another thread.</p>
 */
public final class FightSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Fights");

    /** How often the owner compares its serialized fight state with what it sent. */
    private static final int SYNC_INTERVAL_TICKS = 20;

    /**
     * Chunk radius around a raid centre that receives the raid snapshot on a
     * handover. Raid waves span several chunks, so a player crossing the border can
     * be a few chunks away from the centre; the snapshot is small.
     */
    private static final int RAID_TRANSFER_RADIUS_CHUNKS = 3;

    /** Upper bound for a decoded fight snapshot, matching the packet limit. */
    private static final int MAX_FIGHT_BYTES = 1 << 20;

    private final MultiForgeServer server;

    /** Last serialized state per raid ({@code world#id}); only the owner writes. */
    private final Map<String, Integer> raidHashes = new ConcurrentHashMap<>();

    /** Last serialized state per world; only the owner writes. */
    private final Map<String, Integer> dragonHashes = new ConcurrentHashMap<>();

    /**
     * @param server owning server
     */
    public FightSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Compares and broadcasts active fights once per second.
     *
     * <p>Runs on the server thread from {@code MultiForgeServer.tick}. The cost is
     * one save per active raid and one per loaded end level per second; both are
     * small and bounded by the number of fights, not by the number of chunks.</p>
     */
    public void tick() {
        if (!server.config().worldSync() || server.isStopping()) {
            return;
        }
        int now = server.server().getTickCount();
        if (now % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : server.server().getAllLevels()) {
            try {
                syncRaids(level);
                syncDragon(level);
            } catch (RuntimeException e) {
                // A malformed or half-saved fight must not break the server tick.
                LOGGER.warn("Could not synchronize fight state for {}",
                        level.dimension().location(), e);
            }
        }
    }

    /**
     * Sends every fight snapshot that can affect a chunk directly to the new owner.
     *
     * <p>Called by {@code ChunkManager.onHandoverStart} before the old owner saves
     * and answers ready. The periodic broadcast would catch up within a second; this
     * closes the window where the new owner starts ticking with stale raid or dragon
     * data.</p>
     *
     * @param key      chunk being handed over
     * @param newOwner server that will own the chunk
     */
    public void sendRelevantTo(ChunkKey key, String newOwner) {
        if (!server.config().worldSync() || newOwner == null
                || newOwner.equals(server.serverName()) || server.isStopping()) {
            return;
        }
        ServerLevel level = server.level(key.getName());
        if (level == null) {
            return;
        }
        String world = key.getName();
        try {
            for (Raid raid : raidMap(level).values()) {
                if (raid.isStopped()) {
                    continue;
                }
                BlockPos center = raid.getCenter();
                int dx = Math.abs((center.getX() >> 4) - key.getX());
                int dz = Math.abs((center.getZ() >> 4) - key.getZ());
                if (dx <= RAID_TRANSFER_RADIUS_CHUNKS && dz <= RAID_TRANSFER_RADIUS_CHUNKS) {
                    server.peers().sendTo(List.of(newOwner), new RaidStatePacket(
                            world, raid.getId(), epochAt(level, center.getX(), center.getZ()),
                            raid.save(new CompoundTag())));
                }
            }
            EndDragonFight fight = level.getDragonFight();
            if (fight != null) {
                CompoundTag tag = encodeDragon(fight.saveData());
                if (tag != null) {
                    server.peers().sendTo(List.of(newOwner), new DragonFightStatePacket(
                            world, epochAt(level, 0, 0), tag));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not send fight state of {} to {}", key, newOwner, e);
        }
    }

    /**
     * Clears this server's copy of a fight after it lost the fight's chunk.
     *
     * <p>The boss bar is a per-object {@code ServerBossEvent}: if the losing owner
     * kept it, the player would see two dragon/raid bars, because the new owner's
     * freshly loaded fight object has its own UUID. Removing the bar players here
     * sends the normal vanilla remove packet, which {@code BossEventSyncManager}
     * forwards, so the new owner's add packet restores a single bar.</p>
     *
     * @param key chunk whose ownership was lost
     */
    public void onChunkOwnershipLost(ChunkKey key) {
        if (!server.config().worldSync()) {
            return;
        }
        ServerLevel level = server.level(key.getName());
        if (level == null) {
            return;
        }
        try {
            for (Raid raid : raidMap(level).values()) {
                if (raid.isStopped()) {
                    continue;
                }
                BlockPos center = raid.getCenter();
                if ((center.getX() >> 4) == key.getX() && (center.getZ() >> 4) == key.getZ()) {
                    ((RaidAccessor) raid).multiforge$raidEvent().removeAllPlayers();
                    raidHashes.remove(raidKey(key.getName(), raid.getId()));
                }
            }
            if (key.getX() == 0 && key.getZ() == 0) {
                EndDragonFight fight = level.getDragonFight();
                if (fight != null) {
                    for (ServerPlayer player : level.players()) {
                        fight.removePlayer(player);
                    }
                    dragonHashes.remove(key.getName());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not clear fight state after losing {}", key, e);
        }
    }

    /**
     * Applies a raid snapshot received from the owner.
     *
     * @param packet raid state or removal
     */
    public void onRemoteRaid(RaidStatePacket packet) {
        server.execute(() -> applyRaid(packet));
    }

    /**
     * Applies a dragon-fight snapshot received from the owner.
     *
     * @param packet fight state
     */
    public void onRemoteDragon(DragonFightStatePacket packet) {
        server.execute(() -> applyDragon(packet));
    }

    /** Drops all remembered hashes; called when the server stops. */
    public void clear() {
        raidHashes.clear();
        dragonHashes.clear();
    }

    /**
     * Broadcasts a raid immediately after a mutation (wave spawn, raider join/leave,
     * bad omen absorption).
     *
     * <p>Called from {@code RaidMixin} on the server thread. The periodic once-per-second
     * comparison remains as a safety net for missed calls and for a state change made by
     * vanilla code that has no hook, but the observable updates no longer wait for it.
     * Only the server that owns the raid's centre chunk sends.</p>
     *
     * @param raid changed raid
     */
    public void onRaidChanged(Raid raid) {
        if (raid == null || !server.config().worldSync() || server.isStopping()) {
            return;
        }
        if (raid.isStopped()) {
            onRaidStopped(raid);
            return;
        }
        if (!(raid.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos center = raid.getCenter();
        if (!server.chunks().isLocallyOwned(level, center.getX(), center.getZ())) {
            return;
        }
        try {
            String world = server.chunks().worldName(level);
            CompoundTag tag = raid.save(new CompoundTag());
            int hash = tag.hashCode();
            String key = raidKey(world, raid.getId());
            if (!Integer.valueOf(hash).equals(raidHashes.put(key, hash))) {
                server.peers().broadcast(new RaidStatePacket(world, raid.getId(),
                        epochAt(level, center.getX(), center.getZ()), tag));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not broadcast changed raid {}", raid.getId(), e);
        }
    }

    /**
     * Withdraws a raid from the peers as soon as it stops.
     *
     * @param raid stopped raid
     */
    public void onRaidStopped(Raid raid) {
        if (raid == null || !server.config().worldSync() || server.isStopping()
                || !(raid.getLevel() instanceof ServerLevel level)) {
            return;
        }
        String world = server.chunks().worldName(level);
        if (raidHashes.remove(raidKey(world, raid.getId())) != null) {
            server.peers().broadcast(new RaidStatePacket(world, raid.getId(), 0L, null));
        }
    }

    /**
     * Broadcasts the dragon fight immediately after a phase mutation.
     *
     * <p>Called from {@code EndDragonFightMixin} for dragon killed, respawn stage,
     * respawn attempt and dragon update. Only the end island's owner sends; the
     * periodic comparison is the safety net.</p>
     *
     * @param level level of the fight
     * @param fight changed fight
     */
    public void onDragonChanged(ServerLevel level, EndDragonFight fight) {
        if (level == null || fight == null || !server.config().worldSync() || server.isStopping()) {
            return;
        }
        if (!server.chunks().isLocallyOwned(level, 0, 0)) {
            return;
        }
        try {
            String world = server.chunks().worldName(level);
            CompoundTag tag = encodeDragon(fight.saveData());
            if (tag == null) {
                return;
            }
            int hash = tag.hashCode();
            if (!Integer.valueOf(hash).equals(dragonHashes.put(world, hash))) {
                server.peers().broadcast(new DragonFightStatePacket(world,
                        epochAt(level, 0, 0), tag));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not broadcast the dragon fight for {}", level.dimension().location(), e);
        }
    }

    /**
     * Describes every raid and dragon fight in the loaded levels.
     *
     * <p>Used by {@code /multiforge fights} and by the live cluster harness. The
     * lines are deliberately stable, single-line {@code key=value} records so a
     * test can assert that two servers agree on the dragon UUID and raid wave while
     * only one of them reports {@code local=true} (the ticking server).</p>
     *
     * @return one line per raid and per loaded end level, never {@code null}
     */
    public java.util.List<String> describe() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (ServerLevel level : server.server().getAllLevels()) {
            String world = server.chunks().worldName(level);
            try {
                for (Raid raid : raidMap(level).values()) {
                    BlockPos center = raid.getCenter();
                    lines.add("raid world=" + world
                            + " id=" + raid.getId()
                            + " center=" + center.getX() + "," + center.getY() + "," + center.getZ()
                            + " wave=" + raid.getGroupsSpawned()
                            + " active=" + raid.isActive()
                            + " stopped=" + raid.isStopped()
                            + " epoch=" + epochAt(level, center.getX(), center.getZ())
                            + " local=" + server.chunks().isLocallyOwned(
                                    level, center.getX(), center.getZ()));
                }
                EndDragonFight fight = level.getDragonFight();
                if (fight != null) {
                    EndDragonFight.Data data = fight.saveData();
                    lines.add("dragon world=" + world
                            + " uuid=" + data.dragonUUID().orElse(null)
                            + " killed=" + data.dragonKilled()
                            + " previously=" + data.previouslyKilled()
                            + " respawning=" + data.isRespawning()
                            + " gateways=" + data.gateways().map(java.util.List::size).orElse(0)
                            + " portal=" + data.exitPortalLocation().orElse(null)
                            + " epoch=" + epochAt(level, 0, 0)
                            + " local=" + server.chunks().isLocallyOwned(level, 0, 0));
                }
            } catch (RuntimeException e) {
                lines.add("error world=" + world + " message=" + e);
            }
        }
        return lines;
    }

    private void syncRaids(ServerLevel level) {
        Map<Integer, Raid> raids = raidMap(level);
        if (raids.isEmpty()) {
            return;
        }
        String world = server.chunks().worldName(level);
        Set<String> seen = new HashSet<>();
        for (Raid raid : raids.values()) {
            String key = raidKey(world, raid.getId());
            seen.add(key);
            if (raid.isStopped()) {
                if (raidHashes.remove(key) != null) {
                    // The raid ended: the receiver drops its copy. The boss bar itself
                    // was removed by the owner's ServerBossEvent.removeAllPlayers().
                    server.peers().broadcast(new RaidStatePacket(world, raid.getId(), 0L, null));
                }
                continue;
            }
            BlockPos center = raid.getCenter();
            if (!server.chunks().isLocallyOwned(level, center.getX(), center.getZ())) {
                continue;
            }
            CompoundTag tag = raid.save(new CompoundTag());
            int hash = tag.hashCode();
            if (!Integer.valueOf(hash).equals(raidHashes.put(key, hash))) {
                server.peers().broadcast(new RaidStatePacket(world, raid.getId(),
                        epochAt(level, center.getX(), center.getZ()), tag));
            }
        }
        // A raid that vanished from the map without passing through isStopped()
        // (its centre chunk unloaded on this server) still has to be withdrawn.
        for (String key : raidHashes.keySet()) {
            if (!seen.contains(key) && key.startsWith(world + "#")) {
                raidHashes.remove(key);
                server.peers().broadcast(new RaidStatePacket(world,
                        Integer.parseInt(key.substring(key.indexOf('#') + 1)), 0L, null));
            }
        }
    }

    private void syncDragon(ServerLevel level) {
        EndDragonFight fight = level.getDragonFight();
        if (fight == null || !server.chunks().isLocallyOwned(level, 0, 0)) {
            return;
        }
        String world = server.chunks().worldName(level);
        CompoundTag tag = encodeDragon(fight.saveData());
        if (tag == null) {
            return;
        }
        int hash = tag.hashCode();
        if (!Integer.valueOf(hash).equals(dragonHashes.put(world, hash))) {
            server.peers().broadcast(new DragonFightStatePacket(world, epochAt(level, 0, 0), tag));
        }
    }

    private void applyRaid(RaidStatePacket packet) {
        ServerLevel level = server.level(packet.world);
        if (level == null) {
            return;
        }
        Map<Integer, Raid> raids = raidMap(level);
        Raid existing = raids.get(packet.raidId);
        BlockPos center = existing != null ? existing.getCenter() : centerFromTag(packet.data);
        if (center != null && server.chunks().isLocallyOwned(level, center.getX(), center.getZ())) {
            // This server is authoritative; a snapshot from another server is either
            // late or the result of a handover race. Never overwrite local state.
            return;
        }
        if (center != null && isStale(packet.epoch, epochAt(level, center.getX(), center.getZ()))) {
            return;
        }
        if (packet.data == null) {
            if (raids.remove(packet.raidId) != null) {
                LOGGER.debug("Removed raid {} in {} (owner reported it ended)",
                        packet.raidId, packet.world);
            }
            raidHashes.remove(raidKey(packet.world, packet.raidId));
            return;
        }
        try {
            Raid raid = new Raid(level, packet.data.copy());
            raids.put(raid.getId(), raid);
        } catch (RuntimeException e) {
            LOGGER.warn("Dropping an unreadable raid snapshot {} in {}",
                    packet.raidId, packet.world, e);
        }
    }

    private void applyDragon(DragonFightStatePacket packet) {
        ServerLevel level = server.level(packet.world);
        if (level == null) {
            return;
        }
        if (server.chunks().isLocallyOwned(level, 0, 0)) {
            return;
        }
        if (isStale(packet.epoch, epochAt(level, 0, 0))) {
            return;
        }
        if (packet.data == null || packet.data.sizeInBytes() > MAX_FIGHT_BYTES) {
            return;
        }
        EndDragonFight.Data data = decodeDragon(packet.data);
        if (data == null) {
            LOGGER.warn("Dropping an unreadable dragon-fight snapshot for {}", packet.world);
            return;
        }
        try {
            EndDragonFight current = level.getDragonFight();
            if (current != null) {
                // The replacement fight object has its own boss event UUID; clear the
                // old one so players never see the bar twice.
                for (ServerPlayer player : level.players()) {
                    current.removePlayer(player);
                }
            }
            level.setDragonFight(new EndDragonFight(level, level.getSeed(), data));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not apply the dragon-fight snapshot for {}", packet.world, e);
        }
    }

    /**
     * @param level level to read
     * @return live raid map of the level (empty when the level has no raid data)
     */
    private static Map<Integer, Raid> raidMap(ServerLevel level) {
        return ((RaidsAccessor) level.getRaids()).multiforge$raidMap();
    }

    /**
     * @param tag raid snapshot, may be {@code null}
     * @return raid centre encoded in the snapshot, or {@code null}
     */
    private static BlockPos centerFromTag(CompoundTag tag) {
        if (tag == null || !tag.contains("CX") || !tag.contains("CZ")) {
            return null;
        }
        return new BlockPos(tag.getInt("CX"), tag.getInt("CY"), tag.getInt("CZ"));
    }

    /**
     * @param incomingEpoch epoch of the received snapshot
     * @param knownEpoch    epoch this server knows for the chunk
     * @return {@code true} when the snapshot belongs to a superseded owner
     */
    private static boolean isStale(long incomingEpoch, long knownEpoch) {
        return incomingEpoch != 0L && knownEpoch > incomingEpoch;
    }

    /**
     * @param level level of the chunk
     * @param x     block X
     * @param z     block Z
     * @return ownership epoch, or {@code 0} when unknown
     */
    private long epochAt(ServerLevel level, int x, int z) {
        return server.chunks().chunkEpoch(level, x, z);
    }

    /**
     * @param world logical world name
     * @param id    raid id
     * @return map key used for the last-sent hash
     */
    private static String raidKey(String world, int id) {
        return world + '#' + id;
    }

    /**
     * Encodes vanilla fight data to NBT with its own codec.
     *
     * @param data fight data
     * @return NBT, or {@code null} when the codec rejected it
     */
    private static CompoundTag encodeDragon(EndDragonFight.Data data) {
        Tag tag = EndDragonFight.Data.CODEC.encodeStart(NbtOps.INSTANCE, data).result().orElse(null);
        return tag instanceof CompoundTag compound ? compound : null;
    }

    /**
     * Decodes vanilla fight data from NBT.
     *
     * @param tag snapshot
     * @return data, or {@code null} when the codec rejected it
     */
    private static EndDragonFight.Data decodeDragon(CompoundTag tag) {
        return EndDragonFight.Data.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
    }
}
