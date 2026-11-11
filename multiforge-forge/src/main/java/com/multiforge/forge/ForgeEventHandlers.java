package com.multiforge.forge;

import com.multiforge.forge.chunk.ChunkStorageRegistry;
import com.multiforge.forge.config.MultiForgeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge event bridge for MultiForge.
 *
 * <p>Keeps the mod class small and documents every hook MultiForge relies on. All
 * handlers except the config events run on the server thread; chunk load/unload
 * events are used instead of a {@code ChunkMap} mixin because Forge already emits
 * them at the right moment.</p>
 */
public final class ForgeEventHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge");

    private ForgeEventHandlers() {
    }

    /**
     * Registers the /multiforge commands.
     *
     * @param event command registration event
     */
    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        com.multiforge.forge.command.MultiForgeCommands.onRegisterCommands(event);
    }

    /**
     * Bakes the configuration when the server config is loaded.
     *
     * @param event config event
     */
    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == MultiForgeConfig.SPEC) {
            MultiForgeConfig.bake();
        }
    }

    /**
     * Re-bakes the configuration on {@code /reload}.
     *
     * @param event config event
     */
    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == MultiForgeConfig.SPEC) {
            MultiForgeConfig.bake();
        }
    }

    /**
     * Registers a level and its chunk storages once it is fully loaded.
     *
     * @param event level load event
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkStorageRegistry.registerLevel(level);
            MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
            if (runtime != null) {
                runtime.onLevelCreated(level);
            }
        }
    }

    /**
     * Subscribes a chunk when it becomes a full chunk on this server.
     *
     * @param event chunk load event
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime == null || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            runtime.chunks().onChunkLoad(level, chunk);
        }
    }

    /**
     * Releases ownership and schedules the unsubscribe when a chunk unloads.
     *
     * @param event chunk unload event
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime == null || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk) {
            runtime.chunks().onChunkUnload(level, event.getChunk().getPos());
        }
    }

    /**
     * Assigns a cluster-unique entity id when an entity joins a server level.
     *
     * <p>Forge fires {@link EntityJoinLevelEvent} from
     * {@code PersistentEntitySectionManager.addEntity} before the entity is
     * inserted into the section storage, so changing the id here is safe for the
     * server's id map and for clients (they have not seen the entity yet). The id
     * comes from the block the master reserved for this server, which is what
     * keeps two servers from handing the same id to different entities.</p>
     *
     * <p>Client levels are ignored; entity ids are a server-side concept.</p>
     *
     * @param event entity join event
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime == null || event.getLevel().isClientSide() || runtime.isStopping()
                || !runtime.config().entitySync()) {
            return;
        }
        event.getEntity().setId(runtime.entityIds().nextId());
        runtime.entities().onEntityJoined(event.getEntity());
    }

    /**
     * Drives the per-tick work of the runtime.
     *
     * @param event server tick event
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.tick();
        }
    }

    /**
     * Marks the runtime ready once the server accepted players.
     *
     * @param event server started event
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.onServerStarted();
        } else {
            LOGGER.warn("MultiForge runtime was not bootstrapped; cluster features are disabled");
        }
    }

    /**
     * Stops ownership and ticking before vanilla saves the world.
     *
     * @param event server stopping event
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.beginStop();
        }
    }

    /**
     * Forwards chat to the other servers so the cluster behaves like one server.
     *
     * @param event chat event
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.players().broadcastChat(event.getPlayer(), event.getMessage());
        }
    }
}
