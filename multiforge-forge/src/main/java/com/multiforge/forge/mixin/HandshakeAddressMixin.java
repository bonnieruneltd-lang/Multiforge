package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures the raw handshake host before Forge strips the forwarding suffix.
 *
 * <p>Forge's {@code ClientIntentionPacket(FriendlyByteBuf)} constructor reads the host
 * with {@code readUtf(255)} and then stores only {@code host.split("\0")[0]} so mods
 * never see BungeeCord/Velocity forwarding data. Redirecting that one call site keeps
 * the raw value available to {@code BungeeCordSupport} without changing what vanilla
 * stores or how the packet is written.</p>
 *
 * <p>The redirect runs on the connection's event loop thread during packet decoding.
 * The value is consumed by the {@code handleIntention} hook on the same thread in the
 * same read cycle and is discarded if no forwarding is present.</p>
 */
@Mixin(ClientIntentionPacket.class)
public abstract class HandshakeAddressMixin {

    /**
     * @param buf       buffer positioned at the host string
     * @param maxLength vanilla's maximum host length
     * @return the host exactly as it arrived
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;"))
    private String multiforge$captureRawHost(FriendlyByteBuf buf, int maxLength) {
        String rawHost = buf.readUtf(maxLength);
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bungee().captureRawHost(rawHost);
        }
        return rawHost;
    }
}
