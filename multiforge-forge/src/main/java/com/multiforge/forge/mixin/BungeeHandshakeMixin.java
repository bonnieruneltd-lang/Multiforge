package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures BungeeCord/Velocity legacy forwarding from the handshake.
 *
 * <p>The proxy appends {@code \0ip[\0uuid\0properties]} to the host name. This hook
 * runs before vanilla processes the intention packet, so the connection address is
 * already the client's real address when the login and player list code reads it.
 * Without {@code server.bungeeCord=true} nothing happens.</p>
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class BungeeHandshakeMixin {

    /** Connection that sent the handshake. */
    @Shadow
    @Final
    private Connection connection;

    /**
     * @param packet       handshake intention
     * @param callbackInfo mixin callback
     */
    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void multiforge$forwardAddress(ClientIntentionPacket packet, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bungee().onHandshake(this.connection, packet.getHostName());
        }
    }
}
