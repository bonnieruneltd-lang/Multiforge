package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Hooks menu opening and closing so a container owned by another server can be
 * forwarded to that server.
 *
 * <p>Vanilla opens the window on the server the player is connected to. That is
 * exactly what the client needs (its connection lives here), but the block
 * entity in the menu may be a mirror copy of a chunk another server owns. After
 * vanilla created the menu, {@code ContainerSyncManager} tells the owner which
 * container was opened, and every click forwards the changed container slots
 * back. See {@code docs/SYNCHRONIZATION.md}, "Containers".</p>
 *
 * <p>The {@code openMenu} injections run at RETURN so the old menu has already
 * gone through {@code doCloseContainer}; that avoids sending a close packet for
 * the menu that is being replaced. The hooks only run on the server thread and
 * only schedule peer writes.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMenuMixin {

    /**
     * @param provider provider passed by the block
     * @param callbackInfo mixin callback carrying whether a menu was opened
     */
    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At("RETURN"))
    private void multiforge$afterOpenMenu(MenuProvider provider,
                                          CallbackInfoReturnable<OptionalInt> callbackInfo) {
        multiforge$forwardIfRemote(provider, callbackInfo.getReturnValue());
    }

    /**
     * Forwards the open notification when the menu was actually created.
     *
     * @param provider provider that was opened
     * @param result   vanilla result
     */
    private void multiforge$forwardIfRemote(MenuProvider provider, OptionalInt result) {
        if (!result.isPresent()) {
            return;
        }
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.containers().onLocalMenuOpened((ServerPlayer) (Object) this, provider);
        }
    }

    /**
     * Sends the close notification for a forwarded window before vanilla clears
     * {@code containerMenu}.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void multiforge$beforeCloseContainer(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.containers().onLocalMenuClosed((ServerPlayer) (Object) this);
        }
    }
}
