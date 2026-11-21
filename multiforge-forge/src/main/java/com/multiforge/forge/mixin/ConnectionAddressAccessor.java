package com.multiforge.forge.mixin;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.SocketAddress;

/**
 * Writes the player's forwarded address into a connection.
 *
 * <p>Paper's BungeeCord mode replaces {@code Connection.address} with the address
 * from the handshake. Forge exposes no setter, so the field is accessed through this
 * mixin. It is only used by {@code BungeeCordSupport} when
 * {@code server.bungeeCord=true}, after the address was validated as an IP literal.</p>
 */
@Mixin(Connection.class)
public interface ConnectionAddressAccessor {

    /**
     * @param address client address reported by the trusted proxy
     */
    @Accessor("address")
    void multiforge$setAddress(SocketAddress address);
}
