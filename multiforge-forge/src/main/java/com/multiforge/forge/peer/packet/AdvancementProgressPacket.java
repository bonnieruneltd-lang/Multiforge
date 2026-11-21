package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Replicates one advancement criterion award to the other servers.
 *
 * <p>Port of MultiPaper's {@code AdvancementGrantProgressPacket}. Advancement
 * files are uploaded when the player data is saved, so without this packet a
 * player who earns an advancement on one server and immediately moves to another
 * can lose the toast, the chat announcement and the progress bar until the next
 * save. The owning server sends the award as soon as it happens; a server that
 * already accepted the player (transfer window) applies it to its local player,
 * which runs the normal vanilla listener/visibility logic and shows the toast.
 * A server that only holds a mirror copy ignores the packet.</p>
 *
 * <p>Only a criterion name is sent, not the whole progress file: vanilla's
 * {@code PlayerAdvancements.award} is idempotent for an already-completed
 * criterion, and the next save replaces the file through the master anyway.</p>
 *
 * <p>Wire layout: player UUID, advancement id, criterion name.</p>
 */
public final class AdvancementProgressPacket extends PeerPacket {

    private final UUID uuid;
    private final String advancement;
    private final String criterion;

    /**
     * @param uuid        owner player
     * @param advancement advancement registry id
     * @param criterion   criterion that was granted
     */
    public AdvancementProgressPacket(UUID uuid, String advancement, String criterion) {
        this.uuid = uuid;
        this.advancement = advancement;
        this.criterion = criterion;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public AdvancementProgressPacket(FriendlyByteBuf buf) {
        this.uuid = buf.readUUID();
        this.advancement = buf.readUtf();
        this.criterion = buf.readUtf();
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return advancement registry id */
    public String advancement() {
        return advancement;
    }

    /** @return granted criterion */
    public String criterion() {
        return criterion;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeUtf(advancement);
        buf.writeUtf(criterion);
    }

    /**
     * Applies the award on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.progress().onRemoteAdvancement(this);
        }
    }
}
