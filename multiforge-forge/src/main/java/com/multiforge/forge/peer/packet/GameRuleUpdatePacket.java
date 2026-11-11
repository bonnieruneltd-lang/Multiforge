package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Propagates a single gamerule change.
 *
 * <p>Values are transmitted as the same strings the {@code /gamerule} command
 * accepts, which keeps the packet independent of the gamerule value types.</p>
 */
public class GameRuleUpdatePacket extends PeerPacket {

    /** Logical world name. */
    public final String world;
    /** Gamerule id, e.g. {@code doDaylightCycle}. */
    public final String rule;
    /** Serialized value. */
    public final String value;

    /**
     * @param world logical world name
     * @param rule  gamerule id
     * @param value serialized value
     */
    public GameRuleUpdatePacket(String world, String rule, String value) {
        this.world = world;
        this.rule = rule;
        this.value = value;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public GameRuleUpdatePacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.rule = buf.readUtf();
        this.value = buf.readUtf(32767);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeUtf(rule);
        buf.writeUtf(value);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().world().onRemoteGameRule(this);
    }
}
