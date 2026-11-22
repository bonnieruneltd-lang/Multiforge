package com.multiforge.forge.player;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.entity.EntitySyncManager;
import com.multiforge.forge.peer.packet.PlayerEffectsUpdatePacket;
import com.multiforge.forge.peer.packet.PlayerExperiencePacket;
import com.multiforge.forge.peer.packet.PlayerFoodUpdatePacket;
import com.multiforge.forge.peer.packet.PlayerInventoryUpdatePacket;
import com.multiforge.forge.peer.packet.PlayerListInfoPacket;
import com.multiforge.forge.peer.packet.PlayerSelectedSlotPacket;
import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the inventory, ender chest, hotbar selection, hunger, experience and
 * status effects of mirrored players in sync with the server that owns them.
 *
 * <p>This is the MultiForge equivalent of MultiPaper's
 * {@code MultiPaperInventoryHandler}, {@code MultiPaperEnderChestHandler} and
 * {@code MultiPaperEffectsHandler}. Upstream hooks the vanilla mutation points
 * (container listeners, {@code FoodData} setters, {@code LivingEntity} effect
 * callbacks); Forge has no event for most of those paths (commands, other mods
 * and direct field writes all change player state), so this manager diffs a
 * snapshot once per tick on the owning server instead. That is the same
 * "compare, then transmit the delta" model MultiPaper uses for its inventory
 * dirty lists, just driven by the tick loop instead of listeners. The deviation
 * is recorded in {@code docs/PORTING_NOTES.md}.</p>
 *
 * <p><b>Owner side.</b> {@link #tick()} walks every local player, compares the
 * state against the last transmitted snapshot and broadcasts one peer packet per
 * changed value. The snapshot starts at the state the player had when it was
 * first seen, so a newly joined player never replays their whole inventory: the
 * receiving mirror was created from {@code PlayerCreatePacket}, whose
 * {@code saveWithoutId} tag already contains inventory, ender chest, hunger,
 * experience and effects. When no peer connection exists the snapshots are
 * dropped and rebuilt after the next handshake, because
 * {@code PlayerMirrorManager.onPeerConnected} re-announces every local player
 * with a full create packet.</p>
 *
 * <p><b>Mirror side.</b> The {@code onRemote*} methods look up the
 * {@link ExternalPlayer} by UUID and apply the changed value. A packet for a
 * player that is not mirrored here (or is hosted locally, which is
 * authoritative) is ignored. Inventory changes are followed by one equipment
 * packet to local clients so a held or worn item that arrived through the
 * inventory channel is rendered immediately; a live owner normally sends the
 * equipment packet as well, so the extra packet is idempotent.</p>
 *
 * <p><b>Threading.</b> {@link #tick()} runs on the server thread from
 * {@link MultiForgeServer#tick()}. The packet handlers run on a Netty thread and
 * marshal through {@link MultiForgeServer#execute}; the actual world mutation is
 * wrapped in {@link EntitySyncManager#applyRemote(Runnable)} so nothing is
 * re-broadcast. Snapshots are only touched on the server thread; the map is a
 * {@link ConcurrentHashMap} so commands and tests can inspect the count from
 * another thread.</p>
 */
public final class PlayerStateSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Players");

    /** Number of hotbar slots; also the valid range of the selected slot. */
    private static final int HOTBAR_SLOTS = 9;

    /** Equipment slots a client understands, in the order vanilla sends them. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final MultiForgeServer server;

    /**
     * Last transmitted state per local player UUID. Written and read on the
     * server thread; concurrent so the count can be read from any thread.
     */
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    /**
     * @param server owning server
     */
    public PlayerStateSyncManager(MultiForgeServer server) {
        this.server = server;
    }

    /** @return number of players whose state is being tracked */
    public int trackedPlayerCount() {
        return states.size();
    }

    // ------------------------------------------------------------------
    // Owner side
    // ------------------------------------------------------------------

    /**
     * Compares every local player against its last transmitted snapshot and
     * broadcasts the differences.
     *
     * <p>Called once per server tick. The whole pass is skipped when player
     * mirroring is disabled or no peer connection is live, which keeps the
     * snapshot comparison off the tick path on a standalone server.</p>
     */
    public void tick() {
        if (server.isStopping() || !server.config().playerSync() || !server.peers().hasConnections()) {
            // Mirrors are (re)created with a full PlayerCreatePacket, so dropping
            // the snapshots cannot lose state; it only avoids a burst of stale
            // deltas after the next handshake.
            states.clear();
            return;
        }
        List<ServerPlayer> local = new ArrayList<>();
        for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
            if (!(player instanceof ExternalPlayer) && player.level() instanceof ServerLevel) {
                local.add(player);
            }
        }
        List<UUID> online = new ArrayList<>(local.size());
        for (ServerPlayer player : local) {
            online.add(player.getUUID());
        }
        states.keySet().retainAll(online);
        for (ServerPlayer player : local) {
            PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> PlayerState.of(player));
            try {
                diff(player, state);
            } catch (RuntimeException e) {
                // A broken stack (another mod) must not stop the tick loop or the
                // synchronization of the remaining players.
                LOGGER.warn("Could not synchronize player state of {}: {}",
                        player.getGameProfile().getName(), e.toString());
            }
        }
    }

    /**
     * Forgets every snapshot. Called on shutdown.
     */
    public void clear() {
        states.clear();
    }

    /**
     * Drops the snapshot of one player, e.g. after a logout.
     *
     * @param uuid player UUID
     */
    public void forget(UUID uuid) {
        states.remove(uuid);
    }

    /**
     * Compares one local player with its snapshot and sends the differences.
     *
     * @param player local player
     * @param state  last transmitted state of the same player
     */
    private void diff(ServerPlayer player, PlayerState state) {
        UUID uuid = player.getUUID();
        Inventory inventory = player.getInventory();

        diffComponent(uuid, PlayerInventoryUpdatePacket.ITEMS, state.items, inventory.items);
        diffComponent(uuid, PlayerInventoryUpdatePacket.ARMOR, state.armor, inventory.armor);
        diffComponent(uuid, PlayerInventoryUpdatePacket.OFFHAND, state.offhand, inventory.offhand);
        diffEnderChest(uuid, state, player);

        if (state.selected != inventory.selected) {
            state.selected = inventory.selected;
            server.peers().broadcast(new PlayerSelectedSlotPacket(uuid, inventory.selected));
        }

        FoodData food = player.getFoodData();
        if (state.foodLevel != food.getFoodLevel()
                || state.saturation != food.getSaturationLevel()
                || state.exhaustion != food.getExhaustionLevel()) {
            state.foodLevel = food.getFoodLevel();
            state.saturation = food.getSaturationLevel();
            state.exhaustion = food.getExhaustionLevel();
            server.peers().broadcast(new PlayerFoodUpdatePacket(
                    uuid, state.foodLevel, state.saturation, state.exhaustion));
        }

        if (state.experienceLevel != player.experienceLevel
                || state.experienceProgress != player.experienceProgress
                || state.totalExperience != player.totalExperience) {
            state.experienceLevel = player.experienceLevel;
            state.experienceProgress = player.experienceProgress;
            state.totalExperience = player.totalExperience;
            server.peers().broadcast(new PlayerExperiencePacket(
                    uuid, state.experienceProgress, state.experienceLevel, state.totalExperience));
        }

        diffEffects(player, state);
        diffListInfo(player, state);
    }

    /**
     * Sends tab-list display name and game mode changes.
     *
     * <p>Both values are cached in the snapshot so a player who switches game mode
     * or gets a custom tab-list name is reflected on every server immediately,
     * instead of waiting for the next full player announcement. The values are sent
     * together because a single packet carries both.</p>
     *
     * @param player local player
     * @param state  snapshot of the same player
     */
    private void diffListInfo(ServerPlayer player, PlayerState state) {
        Component displayName = player.getTabListDisplayName();
        String json = displayName == null ? "" : Component.Serializer.toJson(displayName);
        int gameMode = player.gameMode.getGameModeForPlayer().getId();
        if (json.equals(state.displayNameJson) && gameMode == state.gameMode) {
            return;
        }
        state.displayNameJson = json;
        state.gameMode = gameMode;
        server.peers().broadcast(new PlayerListInfoPacket(player.getUUID(), json, gameMode));
    }

    /**
     * Compares one inventory component and broadcasts every changed slot.
     *
     * @param uuid      owner UUID
     * @param type      component id used by {@link PlayerInventoryUpdatePacket}
     * @param snapshot  last transmitted stacks, updated in place
     * @param component live component
     */
    private void diffComponent(UUID uuid, byte type, ItemStack[] snapshot, List<ItemStack> component) {
        for (int slot = 0; slot < snapshot.length && slot < component.size(); slot++) {
            ItemStack current = component.get(slot);
            if (ItemStack.matches(snapshot[slot], current)) {
                continue;
            }
            snapshot[slot] = current.copy();
            server.peers().broadcast(new PlayerInventoryUpdatePacket(uuid, type, slot, current.copy()));
        }
    }

    /**
     * Compares the ender chest with the snapshot and broadcasts changed slots.
     *
     * <p>The ender chest is a {@code Container} rather than a {@code NonNullList},
     * so it needs its own loop; the component id and slot numbering match the
     * packet used for the main inventory.</p>
     *
     * @param uuid   owner UUID
     * @param state  snapshot of the owner
     * @param player owner player
     */
    private void diffEnderChest(UUID uuid, PlayerState state, ServerPlayer player) {
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        for (int slot = 0; slot < state.enderChest.length && slot < enderChest.getContainerSize(); slot++) {
            ItemStack current = enderChest.getItem(slot);
            if (ItemStack.matches(state.enderChest[slot], current)) {
                continue;
            }
            state.enderChest[slot] = current.copy();
            server.peers().broadcast(new PlayerInventoryUpdatePacket(
                    uuid, PlayerInventoryUpdatePacket.ENDER_CHEST, slot, current.copy()));
        }
    }

    /**
     * Compares the player's effects with the snapshot and broadcasts changes.
     *
     * <p>Duration is retransmitted once per second while an effect is active so the
     * mirror's countdown stays close to the owner's. A potion refresh sends
     * immediately; the periodic refresh is the fallback because the mirror does not
     * tick effects itself.</p>
     *
     * @param player local player
     * @param state  snapshot of the same player
     */
    private void diffEffects(ServerPlayer player, PlayerState state) {
        int now = server.server().getTickCount();
        // The mirror never ticks effects, so a duration that only counts down is
        // not visible there. Resending every second keeps the mirror's duration
        // close to the owner's without a packet per tick.
        boolean refresh = now - state.effectsSentAtTick >= 20;
        Map<MobEffect, MobEffectInstance> current = new HashMap<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            current.put(effect.getEffect(), effect);
        }
        for (MobEffectInstance effect : current.values()) {
            MobEffectInstance previous = state.effects.get(effect.getEffect());
            if (!refresh
                    && previous != null
                    && previous.getAmplifier() == effect.getAmplifier()
                    && previous.isAmbient() == effect.isAmbient()
                    && previous.isVisible() == effect.isVisible()
                    && previous.showIcon() == effect.showIcon()
                    && effect.getDuration() <= previous.getDuration()) {
                continue;
            }
            state.effects.put(effect.getEffect(), new MobEffectInstance(effect));
            state.effectsSentAtTick = now;
            server.peers().broadcast(new PlayerEffectsUpdatePacket(
                    player.getUUID(), false, effectId(effect.getEffect()), effect.save(new CompoundTag())));
        }
        state.effects.keySet().removeIf(effect -> {
            if (current.containsKey(effect)) {
                return false;
            }
            state.effectsSentAtTick = now;
            server.peers().broadcast(new PlayerEffectsUpdatePacket(
                    player.getUUID(), true, effectId(effect), null));
            return true;
        });
    }

    /**
     * @param effect registered effect
     * @return registry id used on the wire
     */
    private static String effectId(MobEffect effect) {
        return ForgeRegistries.MOB_EFFECTS.getKey(effect).toString();
    }

    // ------------------------------------------------------------------
    // Mirror side
    // ------------------------------------------------------------------

    /**
     * Applies one inventory slot received from the owning server.
     *
     * @param uuid      owner UUID
     * @param component inventory component id
     * @param slot      slot index
     * @param item      new stack, empty clears the slot
     */
    public void onRemoteInventory(UUID uuid, byte component, int slot, ItemStack item) {
        server.execute(() -> {
            ExternalPlayer player = mirror(uuid);
            if (player == null) {
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                Inventory inventory = player.getInventory();
                if (component == PlayerInventoryUpdatePacket.ENDER_CHEST) {
                    if (slot >= 0 && slot < player.getEnderChestInventory().getContainerSize()) {
                        player.getEnderChestInventory().setItem(slot, item);
                    }
                    return;
                }
                List<ItemStack> target = switch (component) {
                    case PlayerInventoryUpdatePacket.ITEMS -> inventory.items;
                    case PlayerInventoryUpdatePacket.ARMOR -> inventory.armor;
                    case PlayerInventoryUpdatePacket.OFFHAND -> inventory.offhand;
                    default -> null;
                };
                if (target != null && slot >= 0 && slot < target.size()) {
                    target.set(slot, item);
                    broadcastEquipment(player);
                }
            });
        });
    }

    /**
     * Applies the hotbar selection received from the owning server.
     *
     * @param uuid owner UUID
     * @param slot hotbar index
     */
    public void onRemoteSelectedSlot(UUID uuid, int slot) {
        server.execute(() -> {
            ExternalPlayer player = mirror(uuid);
            if (player == null || slot < 0 || slot >= HOTBAR_SLOTS) {
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                player.getInventory().selected = slot;
                broadcastEquipment(player);
            });
        });
    }

    /**
     * Applies the hunger values received from the owning server.
     *
     * @param uuid       owner UUID
     * @param foodLevel  food level
     * @param saturation saturation level
     * @param exhaustion exhaustion level
     */
    public void onRemoteFood(UUID uuid, int foodLevel, float saturation, float exhaustion) {
        server.execute(() -> {
            ExternalPlayer player = mirror(uuid);
            if (player == null) {
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                FoodData food = player.getFoodData();
                food.setFoodLevel(foodLevel);
                food.setSaturation(saturation);
                food.setExhaustion(exhaustion);
            });
        });
    }

    /**
     * Applies the experience values received from the owning server.
     *
     * @param uuid     owner UUID
     * @param progress progress towards the next level
     * @param level    experience level
     * @param total    total experience
     */
    public void onRemoteExperience(UUID uuid, float progress, int level, int total) {
        server.execute(() -> {
            ExternalPlayer player = mirror(uuid);
            if (player == null) {
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                player.experienceProgress = progress;
                player.experienceLevel = level;
                player.totalExperience = total;
            });
        });
    }

    /**
     * Applies one effect add/update or removal received from the owning server.
     *
     * <p>{@code addEffect} on the mirror marks the effect metadata dirty, so the
     * entity tracker forwards the new effect colour to local clients, and applies
     * the attribute modifiers with their effect UUID. Those modifiers are also
     * present in the owner's {@code ClientboundUpdateAttributesPacket}; a second
     * modifier with the same UUID is rejected by {@code AttributeInstance}, so the
     * mirrored values cannot be applied twice.</p>
     *
     * @param uuid     owner UUID
     * @param remove   {@code true} to remove the effect
     * @param effectId registry id of the effect
     * @param data     serialized {@code MobEffectInstance}, ignored when removing
     */
    public void onRemoteEffect(UUID uuid, boolean remove, String effectId, CompoundTag data) {
        server.execute(() -> {
            ExternalPlayer player = mirror(uuid);
            if (player == null) {
                return;
            }
            ResourceLocation id = ResourceLocation.tryParse(effectId);
            MobEffect effect = id == null ? null : ForgeRegistries.MOB_EFFECTS.getValue(id);
            if (effect == null) {
                if (server.config().debugLogging()) {
                    LOGGER.info("Ignoring effect update with unknown effect id {}", effectId);
                }
                return;
            }
            EntitySyncManager.applyRemote(() -> {
                if (remove) {
                    player.removeEffect(effect);
                    return;
                }
                MobEffectInstance instance = data == null ? null : MobEffectInstance.load(data);
                if (instance != null && instance.getEffect() == effect) {
                    player.addEffect(instance);
                }
            });
        });
    }

    /**
     * @param uuid owner UUID
     * @return the mirror copy, or {@code null} when the player is not mirrored or
     *         is hosted locally (the local player is authoritative)
     */
    private ExternalPlayer mirror(UUID uuid) {
        if (server.isStopping() || !server.config().playerSync()) {
            return null;
        }
        ServerPlayer player = server.server().getPlayerList().getPlayer(uuid);
        return player instanceof ExternalPlayer external ? external : null;
    }

    /**
     * Sends the mirror's equipment to the clients tracking it.
     *
     * <p>Called after the inventory channel changed a stack or the hotbar
     * selection, so a held or worn item is visible immediately instead of on the
     * owner's next equipment packet.</p>
     *
     * @param player mirror copy
     */
    private void broadcastEquipment(ExternalPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>(EQUIPMENT_SLOTS.length);
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            slots.add(Pair.of(slot, player.getItemBySlot(slot)));
        }
        level.getChunkSource().broadcast(player, new ClientboundSetEquipmentPacket(player.getId(), slots));
    }

    /**
     * Last transmitted state of one local player. Only touched on the server
     * thread.
     */
    private static final class PlayerState {

        private final ItemStack[] items;
        private final ItemStack[] armor;
        private final ItemStack[] offhand;
        private final ItemStack[] enderChest;
        private final Map<MobEffect, MobEffectInstance> effects = new HashMap<>();

        /** Tick when the effect map was last transmitted; drives the 1 s refresh. */
        private int effectsSentAtTick;
        private int selected;
        private int foodLevel;
        private float saturation;
        private float exhaustion;
        private float experienceProgress;
        private int experienceLevel;
        private int totalExperience;
        /** Last transmitted tab-list display name JSON; empty means none. */
        private String displayNameJson = "";
        /** Last transmitted vanilla game mode id, -1 before the first diff. */
        private int gameMode = -1;

        private PlayerState(int itemCount, int armorCount, int offhandCount, int enderChestCount) {
            this.items = new ItemStack[itemCount];
            this.armor = new ItemStack[armorCount];
            this.offhand = new ItemStack[offhandCount];
            this.enderChest = new ItemStack[enderChestCount];
            Arrays.fill(items, ItemStack.EMPTY);
            Arrays.fill(armor, ItemStack.EMPTY);
            Arrays.fill(offhand, ItemStack.EMPTY);
            Arrays.fill(enderChest, ItemStack.EMPTY);
        }

        /**
         * Captures the current state of a player without sending anything.
         *
         * @param player local player
         * @return snapshot
         */
        private static PlayerState of(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            PlayerState state = new PlayerState(
                    inventory.items.size(), inventory.armor.size(), inventory.offhand.size(),
                    enderChest.getContainerSize());
            copy(inventory.items, state.items);
            copy(inventory.armor, state.armor);
            copy(inventory.offhand, state.offhand);
            for (int slot = 0; slot < state.enderChest.length; slot++) {
                state.enderChest[slot] = enderChest.getItem(slot).copy();
            }
            state.selected = inventory.selected;
            FoodData food = player.getFoodData();
            state.foodLevel = food.getFoodLevel();
            state.saturation = food.getSaturationLevel();
            state.exhaustion = food.getExhaustionLevel();
            state.experienceProgress = player.experienceProgress;
            state.experienceLevel = player.experienceLevel;
            state.totalExperience = player.totalExperience;
            Component displayName = player.getTabListDisplayName();
            state.displayNameJson = displayName == null ? "" : Component.Serializer.toJson(displayName);
            state.gameMode = player.gameMode.getGameModeForPlayer().getId();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                state.effects.put(effect.getEffect(), new MobEffectInstance(effect));
            }
            return state;
        }

        private static void copy(List<ItemStack> source, ItemStack[] destination) {
            for (int i = 0; i < destination.length && i < source.size(); i++) {
                destination[i] = source.get(i).copy();
            }
        }
    }
}
