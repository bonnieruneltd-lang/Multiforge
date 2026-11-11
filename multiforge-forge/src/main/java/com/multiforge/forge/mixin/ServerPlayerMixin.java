package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies statistics and advancement files from the master before vanilla reads
 * them.
 *
 * <p>{@code ServerPlayer}'s constructor creates {@code ServerStatsCounter} and
 * {@code PlayerAdvancements}, which read their files immediately. The only way to
 * let a player keep their progress across servers is to have the files on disk
 * before that happens, hence this constructor hook.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    /**
     * Prefetches the per-player files.
     *
     * @param server       server instance
     * @param level        level the player spawns in
     * @param profile      game profile
     * @param callbackInfo mixin callback
     */
    @Inject(method = "<init>", at = @At("HEAD"))
    private void multiforge$prefetchPlayerFiles(MinecraftServer server, ServerLevel level, GameProfile profile,
                                                CallbackInfo callbackInfo) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime != null && profile != null) {
            runtime.players().prefetchPlayerFiles(profile.getId(), profile.getName());
        }
    }
}
