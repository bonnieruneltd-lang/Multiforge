package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Spawns or refreshes a lightweight copy of an entity on another server.
 *
 * <p>Port of MultiPaper's {@code EntityUpdateNBTPacket}. Chunk data is durable
 * through the shared entity storage, but an entity that spawned after the last
 * save of its chunk is not in that storage yet. The owning server therefore
 * sends the entity's saved NBT to every server that has the chunk's entities
 * loaded, so those servers can create a copy immediately. The same packet also
 * repairs a copy after a chunk reload or an ownership change.</p>
 *
 * <p>The NBT is built on the server thread in the constructor
 * ({@link Entity#saveWithoutId(CompoundTag)} plus the {@code id} field that
 * {@link EntityType#loadEntityRecursive} requires). Passengers are included by
 * {@code saveWithoutId}; the receiver adds every entity of the tree. Entity ids
 * are deliberately not part of the tag: each server allocates its own
 * cluster-unique id when the copy joins the level, and updates are matched by
 * UUID, not by id.</p>
 *
 * <p>Wire layout: world name, UUID, chunk coordinates, NBT. Decoding only reads
 * the buffer; {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class EntityUpdateNBTPacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final int chunkX;
    private final int chunkZ;
    private final CompoundTag tag;

    /**
     * Builds the packet from an entity, including the type id required by the
     * entity loader.
     *
     * @param world  logical world name
     * @param entity entity to snapshot
     * @param tag    pre-filled {@code saveWithoutId} tag
     * @param typeId entity type id written as {@code id}
     */
    public EntityUpdateNBTPacket(String world, Entity entity, CompoundTag tag, ResourceLocation typeId) {
        this.world = world;
        this.uuid = entity.getUUID();
        this.chunkX = entity.chunkPosition().x;
        this.chunkZ = entity.chunkPosition().z;
        tag.putString("id", typeId.toString());
        this.tag = tag;
    }

    /**
     * @param chunkX chunk the entity is in
     * @param chunkZ chunk the entity is in
     * @param world  logical world name
     * @param uuid   entity UUID
     * @param tag    full entity NBT
     */
    public EntityUpdateNBTPacket(String world, UUID uuid, int chunkX, int chunkZ, CompoundTag tag) {
        this.world = world;
        this.uuid = uuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.tag = tag;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityUpdateNBTPacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.chunkX = byteBuf.readVarInt();
        this.chunkZ = byteBuf.readVarInt();
        this.tag = byteBuf.readNbt();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeVarInt(chunkX);
        byteBuf.writeVarInt(chunkZ);
        byteBuf.writeNbt(tag);
    }

    /**
     * Applies the spawn on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onRemoteSpawn(world, uuid, chunkX, chunkZ, tag);
        }
    }
}
