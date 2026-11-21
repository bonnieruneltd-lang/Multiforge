package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards scoreboard broadcasts to the other servers of the cluster.
 *
 * <p>In 1.20.1 {@code ServerScoreboard} sends objective, score, display-slot and
 * team updates with {@code PlayerList.broadcastAll}. Hooking that one method
 * gives MultiForge the same capture point MultiPaper adds to
 * {@code ServerScoreboard.broadcastAll} in its patch set, without duplicating
 * nine callbacks (add/change/remove for objectives, scores and teams). The
 * handler filters on the four clientbound scoreboard packet types; everything
 * else (chat, entity packets, ...) is ignored.</p>
 *
 * <p>Sending happens on the server thread and only serializes the packet; the
 * peer writes run on their own event loops.</p>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListBroadcastMixin {

    /**
     * @param packet       packet vanilla is about to broadcast
     * @param callbackInfo mixin callback
     */
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void multiforge$broadcastScoreboard(Packet<?> packet, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.scoreboards().onLocalPacket(packet);
        }
    }
}
