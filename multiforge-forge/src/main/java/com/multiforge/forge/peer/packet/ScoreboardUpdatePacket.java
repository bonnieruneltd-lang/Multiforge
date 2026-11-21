package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Forwards one vanilla scoreboard packet to the other servers of the cluster.
 *
 * <p>Port of MultiPaper's {@code ScoreboardUpdatePacket}. The main scoreboard of
 * a server is not part of the world storage; vanilla keeps it in
 * {@code scoreboard.dat} and only broadcasts changes to the clients connected to
 * the same server. A command block or plugin that updates the scoreboard on
 * server A would therefore leave the players of server B with a stale sidebar,
 * team colours and list entries.</p>
 *
 * <p>Instead of re-modelling objectives, scores and teams, the packet carries the
 * exact clientbound bytes vanilla already produced
 * ({@code ClientboundSetObjectivePacket}, {@code ClientboundSetScorePacket},
 * {@code ClientboundSetDisplayObjectivePacket} or
 * {@code ClientboundSetPlayerTeamPacket}). The receiving server decodes them with
 * the vanilla packet registry and applies the same mutation to its own
 * {@code ServerScoreboard}; clients then receive the normal packet from their own
 * server. This is the same design as upstream and keeps every display slot,
 * render type and team option byte-for-byte identical without duplicating the
 * packet classes.</p>
 *
 * <p>The {@code criteria} field is only set for a new objective
 * ({@code ClientboundSetObjectivePacket} with {@code METHOD_ADD}), because the
 * clientbound packet does not carry the criteria and the receiving scoreboard
 * cannot create a usable {@code Objective} without it.</p>
 *
 * <p>Wire layout: criteria (empty when absent), then the packet id and body as a
 * length-prefixed byte array. Encoding happens once on the sending server
 * thread; decoding only reads the buffer, and the world is only touched on the
 * server thread inside {@code ScoreboardSyncManager}.</p>
 */
public final class ScoreboardUpdatePacket extends PeerPacket {

    private final String criteria;
    private final byte[] payload;

    /**
     * Creates a packet from a vanilla clientbound scoreboard packet.
     *
     * @param packet   vanilla packet that was just broadcast locally
     * @param criteria objective criteria for a new objective, {@code null} otherwise
     */
    public ScoreboardUpdatePacket(Packet<?> packet, String criteria) {
        this.criteria = criteria == null ? "" : criteria;
        this.payload = encode(packet);
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public ScoreboardUpdatePacket(FriendlyByteBuf buf) {
        this.criteria = buf.readUtf();
        this.payload = buf.readByteArray();
    }

    /**
     * Serializes a vanilla packet including its wire id.
     *
     * @param packet packet to serialize
     * @return wire id followed by the packet body
     */
    private static byte[] encode(Packet<?> packet) {
        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol for " + packet.getClass().getName());
        }
        int id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        ByteBuf buffer = Unpooled.buffer(256);
        FriendlyByteBuf out = new FriendlyByteBuf(buffer);
        out.writeVarInt(id);
        packet.write(out);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    /**
     * @return criteria of a new objective, empty when the packet does not carry one
     */
    public String criteria() {
        return criteria;
    }

    /** @return size of the encoded vanilla packet payload, for diagnostics */
    public int payloadLength() {
        return payload.length;
    }

    /**
     * @return vanilla packet reconstructed from the payload
     */
    public Packet<?> packet() {
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        int id = in.readVarInt();
        return ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, in);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(criteria);
        buf.writeByteArray(payload);
    }

    /**
     * Applies the scoreboard change on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onRemotePacket(this);
        }
    }
}
