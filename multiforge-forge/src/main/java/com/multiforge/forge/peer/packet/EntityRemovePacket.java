package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Tells other servers that an entity they may mirror was removed.
 *
 * <p>Port of MultiPaper's {@code EntityRemovePacket}. The owning server removes
 * entities for many reasons; only reasons that change durable state are
 * broadcast. A chunk unload ({@code UNLOADED_TO_CHUNK}) does not remove the
 * entity from the world, so mirror copies stay until their own chunk unloads.
 * {@code UNLOADED_WITH_PLAYER} is carried explicitly because the receiver must
 * not add the entity to its removal cache (a player transfer can bring it back
 * immediately).</p>
 *
 * <p>Wire layout: world name, entity UUID, unloaded-with-player flag. No world
 * state is touched during decoding; {@link #handle(PeerConnection)} hops to the
 * server thread through {@link EntitySyncManager}.</p>
 */
public final class EntityRemovePacket extends PeerPacket {

    private final String world;
    private final UUID uuid;
    private final boolean unloadedWithPlayer;

    /**
     * @param world              logical world name
     * @param uuid               entity UUID
     * @param unloadedWithPlayer whether the removal was caused by its chunk (or
     *                           player) unloading rather than a discard or death
     */
    public EntityRemovePacket(String world, UUID uuid, boolean unloadedWithPlayer) {
        this.world = world;
        this.uuid = uuid;
        this.unloadedWithPlayer = unloadedWithPlayer;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public EntityRemovePacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
        this.unloadedWithPlayer = byteBuf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
        byteBuf.writeBoolean(unloadedWithPlayer);
    }

    /**
     * Applies the removal on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.entities().onRemoteRemove(world, uuid, unloadedWithPlayer);
        }
    }
}
