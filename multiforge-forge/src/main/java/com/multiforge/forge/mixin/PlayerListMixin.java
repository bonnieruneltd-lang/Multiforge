package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Player data synchronization and duplicate login prevention.
 *
 * <p>Ports three MultiPaper behaviours:</p>
 * <ul>
 *   <li>{@code placeNewPlayer}: a player may only be online on one server at a
 *       time. The master arbitrates; the losing server disconnects the player.</li>
 *   <li>{@code load}: the master's player data replaces the local file, so a
 *       player keeps their inventory when switching servers.</li>
 *   <li>{@code save}: the current data is uploaded to the master; vanilla still
 *       writes the local file as a backup.</li>
 * </ul>
 *
 * <p>These hooks run on the server thread. The master calls block with the
 * configured IO timeout; on a standalone/unreachable master they degrade to the
 * vanilla local behaviour.</p>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    /**
     * Prefetches the player's statistics and advancement files before vanilla
     * constructs the {@code ServerPlayer}.
     *
     * @param profile      profile of the logging in player
     * @param callbackInfo mixin callback
     */
    @Inject(method = "getPlayerForLogin", at = @At("HEAD"))
    private void multiforge$prefetchFiles(com.mojang.authlib.GameProfile profile,
                                          CallbackInfoReturnable<ServerPlayer> callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && profile != null) {
            server.players().prefetchPlayerFiles(profile.getId(), profile.getName());
        }
    }

    /**
     * Prevents a second login of the same player elsewhere in the cluster.
     *
     * @param connection   player connection
     * @param player       joining player
     * @param callbackInfo mixin callback
     */
    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void multiforge$claimPlayer(Connection connection, ServerPlayer player, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null) {
            return;
        }
        if (!server.players().claimPlayer(player)) {
            connection.disconnect(Component.literal(
                    "You are already connected to another server in this cluster. Please try again in a moment."));
            callbackInfo.cancel();
        }
    }

    /**
     * Replaces the locally loaded player data with the master's copy.
     *
     * @param player       player being loaded
     * @param callbackInfo mixin callback carrying the local tag
     */
    @Inject(method = "load", at = @At("RETURN"), cancellable = true)
    private void multiforge$loadPlayerData(ServerPlayer player, CallbackInfoReturnable<CompoundTag> callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null) {
            return;
        }
        Optional<CompoundTag> remote = server.players().loadPlayerData(player);
        if (remote.isPresent()) {
            callbackInfo.setReturnValue(remote.get());
        }
    }

    /**
     * Uploads player data before vanilla writes the local backup file.
     *
     * @param player       player being saved
     * @param callbackInfo mixin callback
     */
    @Inject(method = "save", at = @At("HEAD"))
    private void multiforge$savePlayerData(ServerPlayer player, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.players().savePlayerData(player);
        }
    }
}
