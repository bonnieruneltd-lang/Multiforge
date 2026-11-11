package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Carries one vanilla clientbound entity packet from the owning server to the
 * servers that mirror the entity.
 *
 * <p>Port of MultiPaper's {@code EntityUpdatePacket}. The payload is the packet
 * id plus the packet body exactly as the owning server would send it to a
 * client. The receiver decodes it with {@code ConnectionProtocol.PLAY}, looks
 * the entity up by UUID and applies the fields it understands (movement,
 * teleport, head rotation, motion, metadata, attributes, equipment, animation).
 * Forwarding the vanilla packet instead of inventing a MultiForge-specific
 * update keeps the port close to upstream and automatically covers every field
 * those packets contain.</p>
 *
 * <p>Serialization happens in the manager on the owning server thread; this
 * class only stores the resulting bytes, so the Netty encoder never touches an
 * {@code Entity}. Decoding builds a packet object without world access; the
 * application runs on the server thread via {@link EntitySyncManager}.</p>
 *
 * <p>Wire layout: world name, entity UUID, chunk X/Z, packet bytes.</p>
 */
public final class EntityUpdatePacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final int chunkX;
    private final int chunkZ;
    private final byte[] data;

    /**
     * @param world  logical world name
     * @param uuid   entity UUID
     * @param chunkX chunk the entity was in when the packet was sent
     * @param chunkZ chunk the entity was in when the packet was sent
     * @param data   serialized clientbound packet (id + body)
     */
    public EntityUpdatePacket(String world, UUID uuid, int chunkX, int chunkZ, byte[] data) {
        this.world = world;
        this.uuid = uuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.data = data;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityUpdatePacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.chunkX = byteBuf.readVarInt();
        this.chunkZ = byteBuf.readVarInt();
        this.data = byteBuf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeVarInt(chunkX);
        byteBuf.writeVarInt(chunkZ);
        byteBuf.writeByteArray(data);
    }

    /**
     * @return serialized vanilla packet
     */
    public byte[] data() {
        return data;
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
            server.entities().onRemoteUpdate(world, uuid, chunkX, chunkZ, data);
        }
    }
}
