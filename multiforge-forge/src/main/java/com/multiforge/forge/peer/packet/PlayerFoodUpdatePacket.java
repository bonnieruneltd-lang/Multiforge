package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Replicates the hunger state of a player to the servers that hold a mirror
 * copy.
 *
 * <p>Port of MultiPaper's {@code PlayerFoodUpdatePacket}. Food level,
 * saturation and exhaustion are the fields {@code FoodData} needs to answer
 * {@code /data get entity}, to evaluate food-related game rules and to feed
 * other mods that read a mirrored player. MultiPaper also sends the packet from
 * {@code FoodData.eat}, {@code tick}, {@code setFoodLevel} and
 * {@code setSaturation}; Forge has no equivalent hook for all of those paths, so
 * MultiForge diffs the public {@code FoodData} getters once per tick on the
 * owning server instead (same approach as the inventory sync).</p>
 *
 * <p>The mirror's {@code FoodData} is never ticked: only the server that owns the
 * connection may simulate the player, so the values stay exactly as the owner
 * last reported them.</p>
 *
 * <p>Wire layout: player UUID, food level, saturation and exhaustion. Decoding
 * only reads the buffer; {@link #handle(PeerConnection)} hops to the server
 * thread.</p>
 */
public final class PlayerFoodUpdatePacket extends PeerPacket {

    private final UUID uuid;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;

    /**
     * @param uuid       owner player UUID
     * @param foodLevel  food level (0-20)
     * @param saturation saturation level (0-20)
     * @param exhaustion exhaustion level (grows until a food point is consumed)
     */
    public PlayerFoodUpdatePacket(UUID uuid, int foodLevel, float saturation, float exhaustion) {
        this.uuid = uuid;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerFoodUpdatePacket(FriendlyByteBuf byteBuf) {
        this.uuid = byteBuf.readUUID();
        this.foodLevel = byteBuf.readVarInt();
        this.saturation = byteBuf.readFloat();
        this.exhaustion = byteBuf.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeVarInt(foodLevel);
        byteBuf.writeFloat(saturation);
        byteBuf.writeFloat(exhaustion);
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return food level 0-20 */
    public int foodLevel() {
        return foodLevel;
    }

    /** @return saturation level */
    public float saturation() {
        return saturation;
    }

    /** @return exhaustion level */
    public float exhaustion() {
        return exhaustion;
    }

    /**
     * Applies the values on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerState().onRemoteFood(uuid, foodLevel, saturation, exhaustion);
        }
    }
}
