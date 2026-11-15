package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Replicates one changed player inventory slot to the servers that hold a mirror
 * copy of the player.
 *
 * <p>Port of MultiPaper's {@code PlayerInventoryUpdatePacket}, reduced to one slot
 * per packet. MultiPaper sends a list of changed slots of one component and also
 * carries the previous item so the owner can detect duplication; MultiForge
 * compares the last transmitted state on the owning server instead
 * ({@code PlayerStateSyncManager}), which needs neither the previous item nor a
 * revision number on the wire.</p>
 *
 * <p>The component names the same container MultiPaper uses so the packets stay
 * comparable:</p>
 * <ul>
 *   <li>{@link #ITEMS} - main inventory, slots 0-35 (hotbar first);</li>
 *   <li>{@link #ARMOR} - armor slots 0-3 (feet to head);</li>
 *   <li>{@link #OFFHAND} - the off hand slot (slot 0);</li>
 *   <li>{@link #ENDER_CHEST} - the player's ender chest, slots 0-26.</li>
 * </ul>
 *
 * <p>An empty stack clears the slot. The initial state is not sent with this
 * packet: a newly mirrored player receives {@link PlayerCreatePacket}, whose
 * {@code saveWithoutId} tag already contains the whole inventory and ender
 * chest.</p>
 *
 * <p>Wire layout: player UUID, component, slot index and the item stack
 * (vanilla optional item encoding). Decoding only reads the buffer;
 * {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class PlayerInventoryUpdatePacket extends PeerPacket {

    /** Main inventory component ({@code Inventory.items}, slots 0-35). */
    public static final byte ITEMS = 0;
    /** Armor component ({@code Inventory.armor}, slots 0-3). */
    public static final byte ARMOR = 1;
    /** Off hand component ({@code Inventory.offhand}, slot 0). */
    public static final byte OFFHAND = 2;
    /** Ender chest component ({@code PlayerEnderChestContainer}, slots 0-26). */
    public static final byte ENDER_CHEST = 3;

    private final UUID uuid;
    private final byte component;
    private final int slot;
    private final ItemStack item;

    /**
     * @param uuid      owner player UUID
     * @param component one of {@link #ITEMS}, {@link #ARMOR}, {@link #OFFHAND},
     *                  {@link #ENDER_CHEST}
     * @param slot      slot index inside the component
     * @param item      new stack; {@link ItemStack#EMPTY} clears the slot
     */
    public PlayerInventoryUpdatePacket(UUID uuid, byte component, int slot, ItemStack item) {
        this.uuid = uuid;
        this.component = component;
        this.slot = slot;
        this.item = item;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerInventoryUpdatePacket(FriendlyByteBuf byteBuf) {
        this.uuid = byteBuf.readUUID();
        this.component = byteBuf.readByte();
        this.slot = byteBuf.readVarInt();
        this.item = byteBuf.readItem();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeUUID(uuid);
        byteBuf.writeByte(component);
        byteBuf.writeVarInt(slot);
        byteBuf.writeItem(item);
    }

    /** @return owner player UUID */
    public UUID uuid() {
        return uuid;
    }

    /** @return inventory component id */
    public byte component() {
        return component;
    }

    /** @return slot index inside the component */
    public int slot() {
        return slot;
    }

    /** @return new stack; empty means the slot was cleared */
    public ItemStack item() {
        return item;
    }

    /**
     * Applies the slot on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerState().onRemoteInventory(uuid, component, slot, item);
        }
    }
}
