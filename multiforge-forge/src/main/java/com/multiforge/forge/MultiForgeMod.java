package com.multiforge.forge;

import com.multiforge.forge.command.MultiForgeCommands;
import com.multiforge.forge.config.MultiForgeConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge entry point of MultiForge.
 *
 * <p>The mod is server side only. It registers the {@code SERVER} configuration and
 * the event handlers that drive the lifecycle of {@link MultiForgeServer}. The
 * actual start happens earlier than the Forge server events (world data must be
 * read from the master before vanilla loads it), which is why
 * {@code MinecraftServerMixin.loadLevel} calls {@link MultiForgeServer#bootstrap}.</p>
 *
 * <p>Threading: the constructor runs on the mod loading thread; event handlers run
 * on the server thread unless documented otherwise.</p>
 */
@Mod(MultiForgeMod.MOD_ID)
public final class MultiForgeMod {

    /** Mod id used by Forge, the mixin config and the config file name. */
    public static final String MOD_ID = "multiforge";

    /** Human readable version, kept in sync with gradle.properties. */
    public static final String VERSION = "1.0.0-alpha.1";

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge");

    /** Registers configuration and event handlers. */
    public MultiForgeMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MultiForgeConfig.SPEC, "multiforge-server.toml");
        MinecraftForge.EVENT_BUS.register(ForgeEventHandlers.class);
        LOGGER.info("MultiForge {} loaded", VERSION);
    }

    /**
     * @return the configuration spec, exposed for tests and integrations
     */
    public static MultiForgeConfig.Snapshot config() {
        return MultiForgeConfig.snapshot();
    }
}
