package com.multiforge.forge.entity;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.mixin.LivingEntityAccessor;
import com.multiforge.forge.mixin.MobAccessor;
import com.multiforge.forge.peer.packet.EntityHurtPacket;
import com.multiforge.forge.peer.packet.EntityRemovePacket;
import com.multiforge.forge.peer.packet.EntityUpdateNBTPacket;
import com.multiforge.forge.peer.packet.EntityUpdatePacket;
import com.multiforge.forge.peer.packet.EntityUpdateWithDependenciesPacket;
import com.multiforge.protocol.ChunkKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicates live entities between the servers of a cluster.
 *
 * <p>Only the server that owns a chunk ticks and simulates the entities in it
 * (see {@code ServerLevelMixin}). Every other server keeps the chunk loaded but
 * must still show the same mobs, items and vehicles to its players. This manager
 * is the receiving and sending half of that mirroring, ported from MultiPaper's
 * {@code MultiPaperEntitiesHandler} plus {@code EntityUpdatePacket},
 * {@code EntityRemovePacket} and {@code EntityUpdateWithDependenciesPacket}:</p>
 *
 * <ul>
 *   <li><b>Owner -> peers:</b> every clientbound packet the owner broadcasts for
 *       a synced entity is serialized once (server thread) and sent to the
 *       servers subscribed to that chunk's entities. New entities additionally
 *       get an NBT snapshot, so a mirror can create a copy that was never saved
 *       to the shared entity storage; removals become
 *       {@link EntityRemovePacket}.</li>
 *   <li><b>Peer -> mirror:</b> the receiving server decodes the vanilla packet,
 *       looks the entity up by UUID and applies movement, teleport, head
 *       rotation, velocity, metadata, attributes, equipment, animation, entity
 *       events and passenger/leash/take-item links. Local clients then receive
 *       the change through the normal tracker, because the entity's position and
 *       synched data are updated on the server.</li>
 * </ul>
 *
 * <p>Threading: outbound hooks run on the server thread. Inbound packet
 * {@code handle} methods run on Netty threads and only call
 * {@link MultiForgeServer#execute}; every world access happens in the executed
 * task. A thread-local flag suppresses echo (a packet applied from a peer is
 * not re-broadcast) and is always cleared in a finally block.</p>
 *
 * <p>Scope: players are not mirrored yet (TODO 2), which is why every method
 * ignores {@link ServerPlayer}. Entities that cross an ownership boundary may
 * freeze until the new owner starts sending updates; this is documented in
 * {@code KNOWN_ISSUES.md}.</p>
 */
public final class EntitySyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Entities");

    /** Ticks an inbound update is retried while its entity or dependency is missing. */
    private static final int MAX_RETRIES = 5;

    /** Ticks a removed UUID is remembered to drop late spawn/update packets. */
    private static final int REMOVED_CACHE_TICKS = 600;

    /** Upper bound of spawns handled per tick, so a burst cannot stall the tick. */
    private static final int MAX_SPAWNS_PER_TICK = 512;

    private static final ThreadLocal<Boolean> APPLYING_REMOTE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final MultiForgeServer server;
    private final Deque<Entity> pendingSpawns = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Map<UUID, Integer> removedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> mirrored = new ConcurrentHashMap<>();

    /** Server tick counter used for the retry and removal caches. */
    private int tickCounter;

    /**
     * @param server owning server
     */
    public EntitySyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * @return {@code true} while a packet from another server is being applied;
     *         used to suppress re-broadcasting and entity id reallocation
     */
    public static boolean isApplyingRemote() {
        return APPLYING_REMOTE.get();
    }

    /**
     * @return number of currently known remote copies, for diagnostics
     */
    public int mirroredEntityCount() {
        return mirrored.size();
    }

    // ------------------------------------------------------------------
    // Owner side: hooks called on the server thread
    // ------------------------------------------------------------------

    /**
     * Called for every entity that joins a server level.
     *
     * <p>Records remote copies and queues the NBT snapshot that lets mirrors
     * create a copy of an entity that was never saved to the shared entity
     * storage. The snapshot is sent on the next tick, after the entity finished
     * joining and its passengers are attached. Entity ids are assigned by the
     * join event handler, not here.</p>
     *
     * @param entity entity that is joining
     */
    public void onEntityJoined(Entity entity) {
        if (server.isStopping() || !server.config().entitySync()) {
            return;
        }
        if (isApplyingRemote()) {
            // A mirror copy: it must not be broadcast back to its owner.
            mirrored.put(entity.getUUID(), tickCounter);
            return;
        }
        if (!shouldMirror(entity)) {
            return;
        }
        pendingSpawns.addLast(entity);
    }

    /**
     * Called when a synced entity is removed from this server.
     *
     * <p>Only the owner may announce a removal, and only for reasons that end the
     * entity's life in the cluster. Chunk unloads are skipped: the entity still
     * exists on the owner's disk and other servers keep their copies until their
     * own chunk unloads (MultiPaper does the same).</p>
     *
     * @param entity entity about to be removed
     * @param reason vanilla removal reason
     */
    public void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            // The entity still exists on disk; the mirror only dropped its copy.
            mirrored.remove(entity.getUUID());
            return;
        }
        if (server.isStopping() || !server.config().entitySync() || isApplyingRemote()) {
            return;
        }
        if (!shouldMirror(entity)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ChunkKey key = server.chunks().keyOf(level, entity.chunkPosition());
        if (!isReplicationAuthority(key)) {
            return;
        }
        rememberRemoved(entity.getUUID());
        mirrored.remove(entity.getUUID());
        Collection<String> subscribers = server.chunks().entitySubscribersOf(key);
        if (subscribers.isEmpty()) {
            return;
        }
        server.peers().sendTo(subscribers,
                new EntityRemovePacket(server.chunks().worldName(level), entity.getUUID(),
                        reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER));
    }

    /**
     * Forwards one clientbound entity packet to the servers that mirror it.
     *
     * <p>Called from the {@code ChunkMap.TrackedEntity.broadcast} mixin, which is
     * exactly where vanilla sends the packet to local clients. Packets that
     * reference other entities by id are converted to
     * {@link EntityUpdateWithDependenciesPacket}; all others use
     * {@link EntityUpdatePacket}. The serialized payload is built here, on the
     * server thread, so the Netty encoder never reads entity state.</p>
     *
     * @param entity entity the packet is about
     * @param packet vanilla packet
     */
    public void onEntityPacket(Entity entity, Packet<?> packet) {
        if (server.isStopping() || !server.config().entitySync() || isApplyingRemote()) {
            return;
        }
        if (!shouldMirror(entity) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket) {
            // The block data plane already handled this; forwarding the entity
            // broadcast would duplicate it.
            return;
        }
        ChunkKey key = server.chunks().keyOf(level, entity.chunkPosition());
        if (!isReplicationAuthority(key)) {
            return;
        }
        Collection<String> subscribers = server.chunks().entitySubscribersOf(key);
        if (subscribers.isEmpty()) {
            return;
        }
        String world = server.chunks().worldName(level);
        int chunkX = entity.chunkPosition().x;
        int chunkZ = entity.chunkPosition().z;
        try {
            if (packet instanceof ClientboundSetPassengersPacket passengers) {
                List<Entity> dependencies = new ArrayList<>();
                for (int id : passengers.getPassengers()) {
                    Entity dependency = level.getEntity(id);
                    if (!isMirrorableDependency(dependency)) {
                        return;
                    }
                    dependencies.add(dependency);
                }
                sendWithDependencies(world, entity, chunkX, chunkZ, dependencies, packet, subscribers);
            } else if (packet instanceof ClientboundSetEntityLinkPacket link) {
                Entity dependency = link.getDestId() == 0 ? null : level.getEntity(link.getDestId());
                if (dependency instanceof ServerPlayer) {
                    return;
                }
                List<Entity> dependencies = new ArrayList<>();
                if (dependency != null) {
                    dependencies.add(dependency);
                }
                sendWithDependencies(world, entity, chunkX, chunkZ, dependencies, packet, subscribers);
            } else if (packet instanceof ClientboundTakeItemEntityPacket) {
                // The referenced player does not exist on the mirror; the item
                // removal itself is announced by EntityRemovePacket.
                return;
            } else {
                server.peers().sendTo(subscribers, new EntityUpdatePacket(
                        world, entity.getUUID(), chunkX, chunkZ, encode(packet)));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not forward entity packet {} for {}: {}",
                    packet.getClass().getSimpleName(), entity.getUUID(), e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Mirror side: called from Netty threads, world access via execute
    // ------------------------------------------------------------------

    /**
     * Forwards damage on a mirrored entity to the server that owns it.
     *
     * <p>Called from the {@code LivingEntity.hurt} mixin at HEAD. Returning
     * {@code true} tells the mixin to cancel local damage handling: only the
     * owner may change health, and its metadata update will reach this server
     * through the normal entity packet stream. When no owner is known yet (or the
     * entity is local) this returns {@code false} and vanilla handles the hit.</p>
     *
     * @param entity hurt entity
     * @param source vanilla damage source
     * @param amount damage amount
     * @return {@code true} when the hit was forwarded and must not apply locally
     */
    public boolean onEntityHurt(LivingEntity entity, DamageSource source, float amount) {
        if (server.isStopping() || !server.config().entitySync() || isApplyingRemote()
                || entity instanceof ServerPlayer || !shouldMirror(entity)
                || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        ChunkKey key = server.chunks().keyOf(level, entity.chunkPosition());
        String owner = server.chunks().ownerOf(key).orElse(null);
        if (owner == null || owner.equals(server.serverName())) {
            return false;
        }
        String damageType = source.typeHolder().unwrapKey()
                .map(type -> type.location().toString())
                .orElse("minecraft:generic");
        return server.peers().sendTo(List.of(owner), new EntityHurtPacket(
                server.chunks().worldName(level), entity.getUUID(), amount, damageType));
    }

    /**
     * Applies a forwarded hit on the owner.
     *
     * @param world      logical world name
     * @param uuid       entity UUID
     * @param amount     damage amount
     * @param damageType registry id of the damage type
     */
    public void onRemoteHurt(String world, UUID uuid, float amount, String damageType) {
        schedule("hurt " + uuid, () -> {
            ServerLevel level = server.level(world);
            if (level == null) {
                return true;
            }
            Entity entity = level.getEntity(uuid);
            if (entity == null) {
                return isRemoved(uuid);
            }
            if (!(entity instanceof LivingEntity living)) {
                return true;
            }
            applyRemote(() -> living.hurt(damageSource(level, damageType), amount));
            return true;
        });
    }

    /**
     * Resolves a damage type id in the owner's registry.
     *
     * @param level      level supplying registry access
     * @param damageType registry id
     * @return the matching damage source, or generic damage when unknown
     */
    private static DamageSource damageSource(ServerLevel level, String damageType) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(damageType);
            if (id == null) {
                return level.damageSources().generic();
            }
            ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, id);
            Holder<DamageType> holder = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolder(key)
                    .orElse(null);
            if (holder != null) {
                return new DamageSource(holder);
            }
        } catch (RuntimeException ignored) {
            // Unknown or malformed id: generic damage is still better than dropping the hit.
        }
        return level.damageSources().generic();
    }

    /**
     * Applies a spawn/refresh snapshot received from the owner.
     *
     * @param world  logical world name
     * @param uuid   entity UUID
     * @param chunkX chunk of the entity
     * @param chunkZ chunk of the entity
     * @param tag    entity NBT
     */
    public void onRemoteSpawn(String world, UUID uuid, int chunkX, int chunkZ, CompoundTag tag) {
        schedule("spawn " + uuid, () -> applySpawn(world, uuid, chunkX, chunkZ, tag));
    }

    /**
     * Applies a vanilla packet received from the owner.
     *
     * @param world  logical world name
     * @param uuid   entity UUID
     * @param chunkX chunk of the entity when the packet was sent
     * @param chunkZ chunk of the entity when the packet was sent
     * @param data   serialized clientbound packet
     */
    public void onRemoteUpdate(String world, UUID uuid, int chunkX, int chunkZ, byte[] data) {
        schedule("update " + uuid, () -> applyUpdate(world, uuid, chunkX, chunkZ, data, new UUID[0]));
    }

    /**
     * Applies an entity update that references other entities.
     *
     * @param world        logical world name
     * @param uuid         main entity UUID
     * @param chunkX       chunk of the main entity
     * @param chunkZ       chunk of the main entity
     * @param dependencies UUIDs of the referenced entities
     * @param data         serialized clientbound packet
     */
    public void onRemoteDependencies(String world, UUID uuid, int chunkX, int chunkZ,
                                     UUID[] dependencies, byte[] data) {
        schedule("dependencies " + uuid, () -> applyUpdate(world, uuid, chunkX, chunkZ, data, dependencies));
    }

    /**
     * Removes a mirrored entity after the owner announced it.
     *
     * @param world              logical world name
     * @param uuid               entity UUID
     * @param unloadedWithPlayer whether the entity left with an unloaded chunk
     */
    public void onRemoteRemove(String world, UUID uuid, boolean unloadedWithPlayer) {
        schedule("remove " + uuid, () -> {
            if (!unloadedWithPlayer) {
                rememberRemoved(uuid);
            }
            mirrored.remove(uuid);
            ServerLevel level = server.level(world);
            if (level == null) {
                return true;
            }
            Entity entity = level.getEntity(uuid);
            if (entity == null || entity instanceof ServerPlayer) {
                return true;
            }
            applyRemote(() -> entity.setRemoved(unloadedWithPlayer
                    ? Entity.RemovalReason.UNLOADED_WITH_PLAYER
                    : Entity.RemovalReason.DISCARDED));
            return true;
        });
    }

    /**
     * Sends the current state of every entity in a chunk to a server that just
     * subscribed to its entities.
     *
     * <p>A server that loads a chunk after the owner spawned an entity may read a
     * stale entity chunk from the shared storage. Replaying the NBT snapshot when
     * the subscription is established closes that window without waiting for the
     * next save. Only root entities are sent; passengers travel inside their
     * vehicle's tag.</p>
     *
     * @param key        chunk that gained an entity subscriber
     * @param subscriber server name that subscribed
     */
    public void onEntitySubscriberAdded(ChunkKey key, String subscriber) {
        if (!server.config().entitySync() || subscriber == null || subscriber.equals(server.serverName())) {
            return;
        }
        ServerLevel level = server.level(key.getName());
        if (level == null) {
            return;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity.chunkPosition().x != key.getX() || entity.chunkPosition().z != key.getZ()
                    || entity.isPassenger() || !shouldMirror(entity)) {
                continue;
            }
            ResourceLocation typeId = EntityType.getKey(entity.getType());
            if (typeId == null) {
                continue;
            }
            try {
                CompoundTag tag = new CompoundTag();
                entity.saveWithoutId(tag);
                server.peers().sendTo(List.of(subscriber), new EntityUpdateNBTPacket(
                        key.getName(), entity, tag, typeId));
            } catch (RuntimeException e) {
                LOGGER.warn("Could not snapshot entity {} for {}: {}", entity.getUUID(), subscriber, e.toString());
            }
        }
    }

    /**
     * Runs one server tick of mirror maintenance: drains queued spawn snapshots,
     * retries updates whose entity or dependency has not arrived yet and expires
     * the removal cache.
     *
     * <p>Called from {@link MultiForgeServer#tick()} on the server thread.</p>
     */
    public void tick() {
        tickCounter++;
        if (server.isStopping()) {
            pendingSpawns.clear();
            pending.clear();
            return;
        }
        if (!server.config().entitySync()) {
            pendingSpawns.clear();
            pending.clear();
            removedUntil.clear();
            return;
        }
        drainSpawns();
        retryPending();
        removedUntil.entrySet().removeIf(entry -> entry.getValue() <= tickCounter);
    }

    /** Forgets every cached state. Called when the server stops. */
    public void clear() {
        pendingSpawns.clear();
        pending.clear();
        removedUntil.clear();
        mirrored.clear();
        tickCounter = 0;
    }

    // ------------------------------------------------------------------
    // Applying remote state
    // ------------------------------------------------------------------

    /**
     * Creates or refreshes a mirror copy from an NBT snapshot.
     *
     * @return {@code true} when the packet was consumed, {@code false} when the
     *         chunk or entity is not ready yet and the packet should be retried
     */
    private boolean applySpawn(String world, UUID uuid, int chunkX, int chunkZ, CompoundTag tag) {
        if (isRemoved(uuid)) {
            return true;
        }
        ServerLevel level = server.level(world);
        if (level == null) {
            return true;
        }
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null
                || !level.areEntitiesLoaded(ChunkPos.asLong(chunkX, chunkZ))) {
            return false;
        }
        Entity existing = level.getEntity(uuid);
        if (existing != null) {
            if (!(existing instanceof ServerPlayer)) {
                applyRemote(() -> existing.load(tag));
                mirrored.put(uuid, tickCounter);
            }
            return true;
        }
        applyRemote(() -> {
            EntityType.loadEntityRecursive(tag, level, entity -> {
                if (level.getEntity(entity.getUUID()) != null) {
                    return entity;
                }
                return level.addFreshEntity(entity) ? entity : null;
            });
        });
        Entity created = level.getEntity(uuid);
        if (created == null) {
            return false;
        }
        mirrored.put(uuid, tickCounter);
        if (created instanceof Mob mob) {
            ((MobAccessor) mob).multiforge$restoreLeashFromSave();
        }
        return true;
    }

    /**
     * Applies one serialized vanilla packet to the mirrored entity.
     *
     * @return {@code true} when consumed, {@code false} to retry next tick
     */
    private boolean applyUpdate(String world, UUID uuid, int chunkX, int chunkZ, byte[] data, UUID[] dependencies) {
        if (isRemoved(uuid)) {
            return true;
        }
        ServerLevel level = server.level(world);
        if (level == null) {
            return true;
        }
        Entity entity = level.getEntity(uuid);
        if (entity == null) {
            return false;
        }
        if (entity instanceof ServerPlayer) {
            return true;
        }
        Entity[] dependencyEntities = new Entity[dependencies.length];
        for (int i = 0; i < dependencies.length; i++) {
            Entity dependency = level.getEntity(dependencies[i]);
            if (dependency == null) {
                return isRemoved(dependencies[i]);
            }
            dependencyEntities[i] = dependency;
        }
        Packet<?> packet = decode(data);
        if (packet == null) {
            return true;
        }
        applyRemote(() -> applyPacket(level, entity, dependencyEntities, packet));
        return true;
    }

    /**
     * Applies the clientbound packet fields MultiForge mirrors and forwards the
     * result to local clients through the normal entity tracker.
     *
     * @param level        level of the entity
     * @param entity       mirrored entity
     * @param dependencies entities referenced by a dependency packet
     * @param packet       decoded vanilla packet
     */
    private void applyPacket(ServerLevel level, Entity entity, Entity[] dependencies, Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket move) {
            VecDeltaCodec codec = new VecDeltaCodec();
            codec.setBase(entity.position());
            Vec3 target = codec.decode(move.getXa(), move.getYa(), move.getZa());
            if (!isChunkLoaded(level, target.x, target.z)) {
                removeRecursive(entity);
                return;
            }
            setFallDistance(entity, target.y);
            if (move.hasRotation()) {
                entity.moveTo(target.x, target.y, target.z,
                        move.getyRot() * 360.0F / 256.0F, move.getxRot() * 360.0F / 256.0F);
            } else {
                entity.moveTo(target.x, target.y, target.z, entity.getYRot(), entity.getXRot());
            }
            entity.setOnGround(move.isOnGround());
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (!isChunkLoaded(level, teleport.getX(), teleport.getZ())) {
                removeRecursive(entity);
                return;
            }
            setFallDistance(entity, teleport.getY());
            entity.moveTo(teleport.getX(), teleport.getY(), teleport.getZ(),
                    teleport.getyRot() * 360.0F / 256.0F, teleport.getxRot() * 360.0F / 256.0F);
            entity.setOnGround(teleport.isOnGround());
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            entity.setDeltaMovement(motion.getXa() / 8000.0D, motion.getYa() / 8000.0D,
                    motion.getZa() / 8000.0D);
        } else if (packet instanceof ClientboundRotateHeadPacket rotateHead) {
            entity.setYHeadRot(rotateHead.getYHeadRot() * 360.0F / 256.0F);
        } else if (packet instanceof ClientboundSetEntityDataPacket metadata) {
            applyMetadata(level, entity, metadata);
        } else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            applyAttributes(entity, attributes);
        } else if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            applyEquipment(level, entity, equipment);
        } else if (packet instanceof ClientboundAnimatePacket animate) {
            level.getChunkSource().broadcast(entity, new ClientboundAnimatePacket(entity, animate.getAction()));
        } else if (packet instanceof ClientboundEntityEventPacket event) {
            level.getChunkSource().broadcast(entity, new ClientboundEntityEventPacket(entity, event.getEventId()));
        } else if (packet instanceof ClientboundDamageEventPacket damage) {
            level.getChunkSource().broadcast(entity,
                    new ClientboundDamageEventPacket(entity, damage.getSource(level)));
        } else if (packet instanceof ClientboundSetPassengersPacket) {
            applyPassengers(level, entity, dependencies);
        } else if (packet instanceof ClientboundSetEntityLinkPacket) {
            applyLeash(level, entity, dependencies);
        } else if (packet instanceof ClientboundTakeItemEntityPacket take) {
            applyTakeItem(level, entity, dependencies, take.getAmount());
        } else if (server.config().debugLogging()) {
            LOGGER.info("Unhandled entity packet {} for {}", packet.getClass().getSimpleName(), entity.getUUID());
        }
    }

    // ------------------------------------------------------------------
    // Field application helpers
    // ------------------------------------------------------------------

    private void applyMetadata(ServerLevel level, Entity entity, ClientboundSetEntityDataPacket metadata) {
        List<SynchedEntityData.DataValue<?>> values = metadata.packedItems();
        entity.getEntityData().assignValues(values);
        if (entity instanceof LivingEntity living) {
            int healthId = LivingEntityAccessor.multiforge$dataHealthId().getId();
            for (SynchedEntityData.DataValue<?> value : values) {
                if (value.id() == healthId && value.value() instanceof Float health) {
                    living.setHealth(health);
                }
            }
        }
        // assignValues does not mark the values dirty, so the tracker would not
        // resend them; send them to the local tracking players explicitly.
        level.getChunkSource().broadcast(entity,
                new ClientboundSetEntityDataPacket(entity.getId(), values));
    }

    private void applyAttributes(Entity entity, ClientboundUpdateAttributesPacket attributes) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes.getValues()) {
            AttributeInstance instance = living.getAttribute(snapshot.getAttribute());
            if (instance == null) {
                continue;
            }
            instance.setBaseValue(snapshot.getBase());
            instance.removeModifiers();
            for (AttributeModifier modifier : snapshot.getModifiers()) {
                instance.addPermanentModifier(modifier);
            }
        }
    }

    private void applyEquipment(ServerLevel level, Entity entity, ClientboundSetEquipmentPacket equipment) {
        for (com.mojang.datafixers.util.Pair<EquipmentSlot, net.minecraft.world.item.ItemStack> slot
                : equipment.getSlots()) {
            entity.setItemSlot(slot.getFirst(), slot.getSecond());
        }
        level.getChunkSource().broadcast(entity,
                new ClientboundSetEquipmentPacket(entity.getId(), equipment.getSlots()));
    }

    private void applyPassengers(ServerLevel level, Entity vehicle, Entity[] passengers) {
        for (Entity riding : List.copyOf(vehicle.getPassengers())) {
            if (!java.util.Arrays.asList(passengers).contains(riding)) {
                riding.stopRiding();
            }
        }
        for (Entity passenger : passengers) {
            if (passenger != null && !vehicle.getPassengers().contains(passenger)) {
                passenger.startRiding(vehicle, true);
            }
        }
        level.getChunkSource().broadcast(vehicle, new ClientboundSetPassengersPacket(vehicle));
    }

    private void applyLeash(ServerLevel level, Entity entity, Entity[] dependencies) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        Entity holder = dependencies.length == 0 ? null : dependencies[0];
        mob.setLeashedTo(holder, true);
        level.getChunkSource().broadcast(entity, new ClientboundSetEntityLinkPacket(entity, holder));
    }

    private void applyTakeItem(ServerLevel level, Entity item, Entity[] dependencies, int amount) {
        if (dependencies.length == 0 || !(dependencies[0] instanceof LivingEntity taker)
                || !(item instanceof net.minecraft.world.entity.item.ItemEntity)) {
            return;
        }
        taker.take(item, amount);
        level.getChunkSource().broadcast(item,
                new ClientboundTakeItemEntityPacket(item.getId(), taker.getId(), amount));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * Runs a world-mutating task with the remote-application flag set, so the
     * outbound hooks and the entity id assignment do not treat a mirrored change
     * as local gameplay.
     *
     * @param task task to run on the current (server) thread
     */
    private void applyRemote(Runnable task) {
        boolean previous = APPLYING_REMOTE.get();
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            APPLYING_REMOTE.set(previous);
        }
    }

    /**
     * Serializes a clientbound packet into {@code id + body}.
     *
     * <p>Runs on the server thread. The packet id comes from
     * {@link ConnectionProtocol#getPacketId}; the receiver decodes it with the
     * same protocol, so both sides must run the same Minecraft version (which the
     * cluster handshake and the protocol version in {@code HelloPacket} assume).</p>
     *
     * @param packet packet to serialize
     * @return serialized bytes
     * @throws IllegalStateException when the packet is not part of the play protocol
     */
    private static byte[] encode(Packet<?> packet) {
        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        Integer id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        if (id == null || id < 0) {
            throw new IllegalStateException("No play packet id for " + packet.getClass().getName());
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(id);
        packet.write(buffer);
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return data;
    }

    /**
     * Decodes a clientbound play packet. Constructing the packet only reads the
     * buffer; no world state is touched.
     *
     * @param data serialized packet
     * @return decoded packet, or {@code null} when it cannot be decoded
     */
    private static Packet<?> decode(byte[] data) {
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            int id = buffer.readVarInt();
            return ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, buffer);
        } catch (RuntimeException e) {
            LOGGER.warn("Dropping an entity packet that could not be decoded: {}", e.toString());
            return null;
        }
    }

    private void sendWithDependencies(String world, Entity entity, int chunkX, int chunkZ,
                                      List<Entity> dependencies, Packet<?> packet,
                                      Collection<String> subscribers) {
        UUID[] uuids = dependencies.stream().map(Entity::getUUID).toArray(UUID[]::new);
        server.peers().sendTo(subscribers, new EntityUpdateWithDependenciesPacket(
                world, entity.getUUID(), chunkX, chunkZ, uuids, encode(packet)));
    }

    private static boolean isChunkLoaded(ServerLevel level, double x, double z) {
        int chunkX = BlockPos.containing(x, 0.0D, z).getX() >> 4;
        int chunkZ = BlockPos.containing(x, 0.0D, z).getZ() >> 4;
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    private static void setFallDistance(Entity entity, double newY) {
        double oldY = entity.getY();
        if (newY >= oldY) {
            entity.fallDistance = 0.0F;
        } else {
            entity.fallDistance += (float) (oldY - newY);
        }
    }

    private void removeRecursive(Entity entity) {
        for (Entity passenger : List.copyOf(entity.getPassengers())) {
            if (!(passenger instanceof ServerPlayer)) {
                removeRecursive(passenger);
            }
        }
        applyRemote(() -> entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK));
    }

    /**
     * @param entity entity to test
     * @return whether this manager mirrors the entity at all
     */
    private static boolean shouldMirror(Entity entity) {
        if (entity instanceof ServerPlayer || entity.isRemoved()) {
            return false;
        }
        if (!entity.shouldBeSaved() && !(entity instanceof LightningBolt)
                && !(entity instanceof LeashFenceKnotEntity)) {
            return false;
        }
        return EntityType.getKey(entity.getType()) != null;
    }

    /**
     * @param dependency entity referenced by another entity packet
     * @return whether the mirror can resolve it (players are not mirrored yet)
     */
    private static boolean isMirrorableDependency(Entity dependency) {
        return dependency != null && !(dependency instanceof ServerPlayer) && !dependency.isRemoved();
    }

    /**
     * @param key chunk of an entity
     * @return whether this server simulates (or is about to own) the chunk
     */
    private boolean isReplicationAuthority(ChunkKey key) {
        String owner = server.chunks().ownerOf(key).orElse(null);
        return owner == null || owner.equals(server.serverName());
    }

    private void rememberRemoved(UUID uuid) {
        removedUntil.put(uuid, tickCounter + REMOVED_CACHE_TICKS);
        mirrored.remove(uuid);
    }

    private boolean isRemoved(UUID uuid) {
        Integer until = removedUntil.get(uuid);
        return until != null && until > tickCounter;
    }

    /**
     * One retryable inbound packet.
     *
     * <p>Only touched on the server thread.</p>
     */
    private static final class Pending {

        private final String description;
        private final Retryable action;
        private int attempts;

        private Pending(String description, Retryable action) {
            this.description = description;
            this.action = action;
        }
    }

    /** Action that reports whether it finished or should be retried. */
    @FunctionalInterface
    private interface Retryable {

        /**
         * @return {@code true} when the action is complete
         */
        boolean apply();
    }

    /**
     * Runs a mirror action on the server thread, retrying it when the entity or a
     * dependency has not arrived yet.
     *
     * @param description short description for debug logging
     * @param action      action returning {@code true} when it is complete
     */
    private void schedule(String description, Retryable action) {
        if (server.isStopping() || server.isIoClosed()) {
            return;
        }
        server.execute(() -> {
            if (tryApply(description, action)) {
                return;
            }
            if (!server.isStopping()) {
                pending.add(new Pending(description, action));
            }
        });
    }

    private boolean tryApply(String description, Retryable action) {
        try {
            return action.apply();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not apply entity {}: {}", description, e.toString());
            return true;
        }
    }

    private void retryPending() {
        for (java.util.Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
            Pending entry = iterator.next();
            if (tryApply(entry.description, entry.action)) {
                iterator.remove();
                continue;
            }
            if (++entry.attempts >= MAX_RETRIES) {
                iterator.remove();
                if (server.config().debugLogging()) {
                    LOGGER.info("Dropping entity {} after {} retries", entry.description, entry.attempts);
                }
            }
        }
    }

    /**
     * Drains the spawn queue. The NBT snapshot is built here, on the server
     * thread, so the packet only carries bytes.
     */
    private void drainSpawns() {
        int handled = 0;
        while (!pendingSpawns.isEmpty() && handled++ < MAX_SPAWNS_PER_TICK) {
            Entity entity = pendingSpawns.pollFirst();
            if (entity == null || entity.isRemoved() || entity.isPassenger() || !shouldMirror(entity)) {
                continue;
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            ChunkKey key = server.chunks().keyOf(level, entity.chunkPosition());
            if (!isReplicationAuthority(key)) {
                continue;
            }
            Collection<String> subscribers = server.chunks().entitySubscribersOf(key);
            if (subscribers.isEmpty()) {
                continue;
            }
            ResourceLocation typeId = EntityType.getKey(entity.getType());
            if (typeId == null) {
                continue;
            }
            try {
                CompoundTag tag = new CompoundTag();
                entity.saveWithoutId(tag);
                server.peers().sendTo(subscribers, new EntityUpdateNBTPacket(
                        server.chunks().worldName(level), entity, tag, typeId));
            } catch (RuntimeException e) {
                LOGGER.warn("Could not snapshot entity {} for a peer: {}", entity.getUUID(), e.toString());
            }
        }
    }
}
