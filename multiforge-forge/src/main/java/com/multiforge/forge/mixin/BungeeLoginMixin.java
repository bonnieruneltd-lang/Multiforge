package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the forwarded UUID and profile to the login profile.
 *
 * <p>Vanilla creates an offline {@code GameProfile} from the user name when
 * {@code online-mode=false} (required behind a proxy). With legacy forwarding the
 * proxy already authenticated the player, so the forwarded UUID and texture
 * properties must replace that profile before the player is constructed. Forge made
 * {@code ServerLoginPacketListenerImpl.gameProfile} public, which this hook uses.</p>
 *
 * <p>The disconnect hook drops pending forwarding data for connections that fail
 * before login; the connection-level hook covers connections closed even earlier.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class BungeeLoginMixin {

    /** Connection being logged in. */
    @Shadow
    @Final
    Connection connection;

    /**
     * @param packet       hello packet with the user name
     * @param callbackInfo mixin callback
     */
    @Inject(method = "handleHello", at = @At("RETURN"))
    private void multiforge$applyForwardedProfile(ServerboundHelloPacket packet, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bungee().onLogin((ServerLoginPacketListenerImpl) (Object) this, this.connection);
        }
    }

    /**
     * @param reason       disconnect reason
     * @param callbackInfo mixin callback
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void multiforge$forgetForwarding(Component reason, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bungee().onDisconnected(this.connection);
        }
    }
}
