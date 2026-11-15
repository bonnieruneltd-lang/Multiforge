package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Forwards the container-slot result of a menu click to the server that owns the
 * container.
 *
 * <p>The click is evaluated by the vanilla {@code AbstractContainerMenu} on the
 * server the player is connected to (that server holds the client connection and
 * the player's authoritative inventory). Only the slots that belong to the
 * remote container are sent; the owner writes them into its own container, marks
 * it changed and broadcasts the resulting block entity NBT to every subscriber,
 * including the opening server. That keeps the owner authoritative while the
 * player sees an ordinary responsive menu.</p>
 *
 * <p>The payload is a list of {@code (container slot index, item stack)} pairs.
 * The index is {@code Slot.getContainerSlot()}, i.e. the index inside the block
 * entity's container, not the menu slot id. Empty stacks are encoded as such and
 * remove the item. The owner reconstructs a double chest container from the block
 * state, so indices above 26 address the second half correctly.</p>
 */
public final class ContainerClickPacket extends PeerPacket {

    /** One changed container slot. */
    public record SlotChange(int index, ItemStack stack) {
    }

    /** Logical world name. */
    public final String world;
    /** Container block X. */
    public final int x;
    /** Container block Y. */
    public final int y;
    /** Container block Z. */
    public final int z;
    /** Player that clicked. */
    public final UUID player;
    /** Changed container slots (index inside the container, new stack). */
    public final List<SlotChange> changes;

    /**
     * @param world   logical world name
     * @param x       block X
     * @param y       block Y
     * @param z       block Z
     * @param player  player UUID
     * @param changes changed container slots
     */
    public ContainerClickPacket(String world, int x, int y, int z, UUID player, List<SlotChange> changes) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.player = player;
        this.changes = List.copyOf(changes);
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public ContainerClickPacket(FriendlyByteBuf buf) {
        this.world = buf.readUtf();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.player = buf.readUUID();
        int size = buf.readVarInt();
        List<SlotChange> decoded = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            decoded.add(new SlotChange(buf.readVarInt(), buf.readItem()));
        }
        this.changes = List.copyOf(decoded);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(world);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeUUID(player);
        buf.writeVarInt(changes.size());
        for (SlotChange change : changes) {
            buf.writeVarInt(change.index());
            buf.writeItem(change.stack());
        }
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().containers().onRemoteClick(this, connection);
    }
}
