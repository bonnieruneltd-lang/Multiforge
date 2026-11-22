package com.multiforge.forge.world;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.packet.BossEventPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Replicates vanilla boss bars (dragon fight, raids, plugin bars) between servers.
 *
 * <p>A {@code ServerBossEvent} is created and ticked by the server that owns the
 * fight; other servers have no object at all. The owner forwards the encoded
 * {@code ClientboundBossEventPacket} (add, progress, name, style, properties,
 * remove) to every peer, and the receiver sends the same vanilla packet to its
 * local players in the matching world. No {@code ServerBossEvent} is created on
 * the receiver, so the bar is displayed without duplicating the raid/dragon
 * logic or ticking it twice.</p>
 *
 * <p>The boss UUID to world mapping is captured when the first player is added to
 * the event, because {@code ServerBossEvent} itself has no level reference. A
 * boss bar with no player yet is not sent; the first add carries the complete
 * {@code ADD} packet.</p>
 *
 * <p>Threading: owner callbacks run on the server thread. Receiving moves to the
 * server thread before touching a level or player connection.</p>
 */
public final class BossEventSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/BossBar");

    private final MultiForgeServer server;
    /** Boss UUID to logical world; only the owning server has entries. */
    private final Map<UUID, String> worlds = new ConcurrentHashMap<>();

    /**
     * @param server owning server
     */
    public BossEventSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Records the first player of a boss event and broadcasts the ADD packet.
     *
     * @param event  owner boss event
     * @param player player that was added
     */
    public void onAdd(ServerBossEvent event, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        String world = server.chunks().worldName(level);
        if (worlds.putIfAbsent(event.getId(), world) != null) {
            // Other players just join the local bar; the add was sent once.
            return;
        }
        broadcast(world, ClientboundBossEventPacket.createAddPacket(event));
    }

    /**
     * Broadcasts the removal of a boss bar.
     *
     * @param event boss event that lost all players
     */
    public void onRemoveAll(ServerBossEvent event) {
        String world = worlds.remove(event.getId());
        if (world != null) {
            broadcast(world, ClientboundBossEventPacket.createRemovePacket(event.getId()));
        }
    }

    /**
     * Broadcasts one update packet for a known boss bar.
     *
     * @param event   owner boss event
     * @param factory packet factory for the changed field
     */
    public void onUpdate(ServerBossEvent event, Function<BossEvent, ClientboundBossEventPacket> factory) {
        String world = worlds.get(event.getId());
        if (world != null) {
            broadcast(world, factory.apply(event));
        }
    }

    /**
     * Sends a received boss packet to every local player in the world.
     *
     * @param world logical world name
     * @param data  encoded clientbound boss packet
     */
    public void onRemote(String world, byte[] data) {
        server.execute(() -> {
            ServerLevel level = server.level(world);
            if (level == null) {
                return;
            }
            Packet<?> packet = decode(data);
            if (!(packet instanceof ClientboundBossEventPacket boss)) {
                return;
            }
            for (ServerPlayer player : level.players()) {
                player.connection.send(boss);
            }
        });
    }

    /** Forgets all owner mappings; called when the server stops. */
    public void clear() {
        worlds.clear();
    }

    private void broadcast(String world, ClientboundBossEventPacket packet) {
        try {
            server.peers().broadcast(new BossEventPacket(world, encode(packet)));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not encode a boss event: {}", e.toString());
        }
    }

    /**
     * @param packet clientbound play packet
     * @return id-prefixed bytes
     */
    private static byte[] encode(Packet<?> packet) {
        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        Integer id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        if (id == null || id < 0) {
            throw new IllegalStateException("No clientbound packet id for " + packet.getClass());
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
        buffer.writeVarInt(id);
        packet.write(buffer);
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return data;
    }

    /**
     * @param data id-prefixed bytes
     * @return decoded packet, or {@code null} for a bad frame
     */
    private static Packet<?> decode(byte[] data) {
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            int id = buffer.readVarInt();
            return ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, buffer);
        } catch (RuntimeException e) {
            LOGGER.warn("Dropping a boss packet that could not be decoded: {}", e.toString());
            return null;
        }
    }
}
