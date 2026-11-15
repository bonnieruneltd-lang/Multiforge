package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Tells other servers that a mirrored player left the cluster.
 *
 * <p>Port of MultiPaper's {@code PlayerRemovePacket}. The owning server sends it
 * on logout; a receiver drops the {@code ExternalPlayer}, removes its tab list
 * entry and stops forwarding updates to it. A chunk unload of the mirrored copy
 * does not send this packet: the player is still online, the copy is simply
 * recreated when the chunk becomes visible again.</p>
 *
 * <p>Wire layout: logical world name and player UUID. The world is informational;
 * the receiver removes the copy from whichever level currently holds it, so a
 * dimension change cannot leave a stale copy behind.</p>
 */
public final class PlayerRemovePacket extends PeerPacket {

    private final String world;
    private final UUID uuid;

    /**
     * @param world  logical world the player was in
     * @param uuid   player UUID
     */
    public PlayerRemovePacket(String world, UUID uuid) {
        this.world = world;
        this.uuid = uuid;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerRemovePacket(FriendlyByteBuf byteBuf) {
        this.world = byteBuf.readUtf();
        this.uuid = byteBuf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUtf(world);
        byteBuf.writeUUID(uuid);
    }

    /** @return logical world the player was in */
    public String world() {
        return world;
    }

    /** @return player UUID */
    public UUID uuid() {
        return uuid;
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
            server.playerMirrors().onRemoteRemove(world, uuid);
        }
    }
}
