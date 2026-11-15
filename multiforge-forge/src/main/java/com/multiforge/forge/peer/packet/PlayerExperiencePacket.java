package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Replicates the experience state of a player to the servers that hold a mirror
 * copy.
 *
 * <p>Port of the experience half of MultiPaper's {@code MultiPaperInventoryHandler}:
 * upstream forwards a {@code ClientboundSetExperiencePacket} and applies its
 * three fields to the copy. MultiForge carries the fields directly so the wire
 * format does not depend on a vanilla packet id, and the owning server diffs them
 * once per tick (there is no Forge event for experience gain that covers every
 * source, including commands and other mods).</p>
 *
 * <p>The values matter server-side ({@code /data get entity}, enchanting table
 * checks, mods reading the player) but are not rendered for another player, so no
 * client packet is produced on the mirror.</p>
 *
 * <p>Wire layout: player UUID, progress towards the next level, level and total
 * experience. Decoding only reads the buffer; {@link #handle(PeerConnection)}
 * hops to the server thread.</p>
 */
public final class PlayerExperiencePacket extends PeerPacket {

    private final UUID uuid;
    private final float progress;
    private final int level;
    private final int total;

    /**
     * @param uuid     owner player UUID
     * @param progress progress towards the next level (0.0-1.0)
     * @param level    experience level
     * @param total    total experience points
     */
    public PlayerExperiencePacket(UUID uuid, float progress, int level, int total) {
        this.uuid = uuid;
        this.progress = progress;
        this.level = level;
        this.total = total;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerExperiencePacket(FriendlyByteBuf byteBuf) {
        this.uuid = byteBuf.readUUID();
        this.progress = byteBuf.readFloat();
        this.level = byteBuf.readVarInt();
        this.total = byteBuf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeFloat(progress);
        byteBuf.writeVarInt(level);
        byteBuf.writeVarInt(total);
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return progress towards the next level */
    public float progress() {
        return progress;
    }

    /** @return experience level */
    public int level() {
        return level;
    }

    /** @return total experience points */
    public int total() {
        return total;
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
            server.playerState().onRemoteExperience(uuid, progress, level, total);
        }
    }
}
