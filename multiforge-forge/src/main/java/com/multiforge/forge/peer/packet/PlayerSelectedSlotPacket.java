package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Replicates the selected hotbar slot of a player to the servers that hold a
 * mirror copy.
 *
 * <p>Port of the hotbar half of MultiPaper's {@code MultiPaperInventoryHandler}:
 * upstream forwards a {@code ClientboundSetCarriedItemPacket} to every external
 * server and applies it to the copy there. MultiForge only needs the slot index,
 * so the packet carries it directly instead of the whole vanilla packet.</p>
 *
 * <p>The slot decides which main-inventory stack is the mirrored player's main
 * hand, so local clients on a mirror server render the item the owner is
 * holding. Equipment is forwarded separately by the owner's
 * {@code ClientboundSetEquipmentPacket}, but a hotbar switch changes the selected
 * index without changing an inventory slot, hence this packet.</p>
 *
 * <p>Wire layout: player UUID and slot index. Decoding only reads the buffer;
 * {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class PlayerSelectedSlotPacket extends PeerPacket {

    private final UUID uuid;
    private final int slot;

    /**
     * @param uuid owner player UUID
     * @param slot hotbar index (0-8)
     */
    public PlayerSelectedSlotPacket(UUID uuid, int slot) {
        this.uuid = uuid;
        this.slot = slot;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerSelectedSlotPacket(FriendlyByteBuf byteBuf) {
        this.uuid = byteBuf.readUUID();
        this.slot = byteBuf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeVarInt(slot);
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return hotbar index */
    public int slot() {
        return slot;
    }

    /**
     * Applies the selection on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerState().onRemoteSelectedSlot(uuid, slot);
        }
    }
}
