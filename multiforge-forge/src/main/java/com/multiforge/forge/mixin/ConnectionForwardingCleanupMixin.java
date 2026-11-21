package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops pending BungeeCord forwarding data when a connection closes.
 *
 * <p>A client can disconnect after the handshake but before the login hello (for
 * example a ping, an unsupported version or a proxy timeout). The forwarding data is
 * kept in {@code BungeeCordSupport} until login; without this hook those entries
 * would leak for the lifetime of the server.</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionForwardingCleanupMixin {

    /**
     * @param context      channel context
     * @param callbackInfo mixin callback
     */
    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void multiforge$forgetForwarding(ChannelHandlerContext context, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bungee().onDisconnected((Connection) (Object) this);
        }
    }
}
