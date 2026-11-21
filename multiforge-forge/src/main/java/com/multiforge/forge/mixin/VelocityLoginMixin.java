package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pauses the vanilla login until Velocity's modern forwarding answer arrives.
 *
 * <p>{@code handleHello} sets the login state to READY and the next {@code tick}
 * accepts the player. Modern forwarding needs one round trip to the proxy, so while a
 * request is outstanding {@code tick} is cancelled (with a timeout handled by
 * {@code VelocityForwardingSupport}). The plugin answer is consumed before Forge's own
 * login-channel handling; answers for other channels are left untouched.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class VelocityLoginMixin {

    /** Connection being logged in. */
    @Shadow
    @Final
    Connection connection;

    /**
     * @param packet       hello packet with the user name
     * @param callbackInfo mixin callback
     */
    @Inject(method = "handleHello", at = @At("RETURN"))
    private void multiforge$requestVelocityForwarding(ServerboundHelloPacket packet,
                                                      CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.velocity().onHello((ServerLoginPacketListenerImpl) (Object) this, this.connection);
        }
    }

    /**
     * @param packet       plugin answer
     * @param callbackInfo mixin callback
     */
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void multiforge$applyVelocityForwarding(ServerboundCustomQueryPacket packet,
                                                    CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && server.velocity().onAnswer(
                (ServerLoginPacketListenerImpl) (Object) this, this.connection, packet)) {
            callbackInfo.cancel();
        }
    }

    /**
     * @param callbackInfo mixin callback
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void multiforge$waitForVelocity(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null && server.velocity().isPending(this.connection)) {
            server.velocity().tickTimeouts();
            callbackInfo.cancel();
        }
    }
}
