package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Entity update whose application also needs other entities.
 *
 * <p>Port of MultiPaper's {@code EntityUpdateWithDependenciesPacket}. Three
 * vanilla packets reference more than one entity by id: passengers link a
 * vehicle to its riders, the entity link packet attaches a leash, and the
 * take-item packet refers to the player who picked an item up. Entity ids are
 * server-local, so the receiver cannot resolve those ids; the owning server
 * therefore adds the UUIDs of the referenced entities and the receiver resolves
 * them locally.</p>
 *
 * <p>Dependencies are resolved again when the packet is applied; if one is not
 * present yet (spawn packet still in flight) the packet is retried for a few
 * ticks and then dropped. The payload itself is the same id + body encoding as
 * {@link EntityUpdatePacket}, serialized on the server thread.</p>
 *
 * <p>Wire layout: world name, entity UUID, chunk X/Z, dependency count, UUIDs,
 * packet bytes.</p>
 */
public final class EntityUpdateWithDependenciesPacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final int chunkX;
    private final int chunkZ;
    private final UUID[] dependencies;
    private final byte[] data;

    /**
     * @param world        logical world name
     * @param uuid         main entity UUID
     * @param chunkX       chunk of the main entity
     * @param chunkZ       chunk of the main entity
     * @param dependencies UUIDs of the entities the packet references
     * @param data         serialized clientbound packet (id + body)
     */
    public EntityUpdateWithDependenciesPacket(String world, UUID uuid, int chunkX, int chunkZ,
                                              UUID[] dependencies, byte[] data) {
        this.world = world;
        this.uuid = uuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dependencies = dependencies;
        this.data = data;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityUpdateWithDependenciesPacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.chunkX = byteBuf.readVarInt();
        this.chunkZ = byteBuf.readVarInt();
        this.dependencies = new UUID[byteBuf.readVarInt()];
        for (int i = 0; i < dependencies.length; i++) {
            dependencies[i] = byteBuf.readUUID();
        }
        this.data = byteBuf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeVarInt(chunkX);
        byteBuf.writeVarInt(chunkZ);
        byteBuf.writeVarInt(dependencies.length);
        for (UUID dependency : dependencies) {
            byteBuf.writeUUID(dependency);
        }
        byteBuf.writeByteArray(data);
    }

    /**
     * @return serialized vanilla packet
     */
    public byte[] data() {
        return data;
    }

    /**
     * @return UUIDs of the referenced entities
     */
    public UUID[] dependencies() {
        return dependencies.clone();
    }

    /**
     * Applies the update on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onRemoteDependencies(world, uuid, chunkX, chunkZ, dependencies, data);
        }
    }
}
