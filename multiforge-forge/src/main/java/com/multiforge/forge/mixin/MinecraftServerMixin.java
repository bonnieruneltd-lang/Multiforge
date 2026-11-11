package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the server lifecycle.
 *
 * <p>MultiPaper starts its plugin before worlds are loaded (so {@code level.dat} can
 * come from the master) and stops it after the final save. Forge's server events
 * fire too late for the start and too early for the final flush, so the two points
 * are hooked directly:</p>
 * <ul>
 *   <li>{@code loadLevel} HEAD: bootstrap MultiForge and download the world data;</li>
 *   <li>{@code stopServer} HEAD: stop ownership and ticking, keep uploads alive;</li>
 *   <li>{@code stopServer} RETURN: flush uploads and close every connection.</li>
 * </ul>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * Boots MultiForge before vanilla reads the world data.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void multiforge$bootstrap(CallbackInfo callbackInfo) {
        if (((MinecraftServer) (Object) this).isDedicatedServer()) {
            MultiForgeServer.bootstrap((MinecraftServer) (Object) this);
        }
    }

    /**
     * Stops ownership decisions before vanilla saves chunks, so the final save is
     * uploaded instead of starting new ownership negotiations.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void multiforge$beginStop(CallbackInfo callbackInfo) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.beginStop();
        }
    }

    /**
     * Flushes the cluster connection after the final save completed.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void multiforge$shutdown(CallbackInfo callbackInfo) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null) {
            runtime.shutdown("server stopped");
        }
    }
}
