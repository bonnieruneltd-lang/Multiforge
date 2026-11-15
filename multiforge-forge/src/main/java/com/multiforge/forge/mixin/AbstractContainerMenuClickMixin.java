package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards the container-slot result of a click on a menu whose container lives
 * in another server's chunk.
 *
 * <p>Vanilla's click implementation handles cursor, shift-click, drag and
 * quick-move logic and is therefore kept on the player's server. The mixin takes
 * a snapshot of the container slots at HEAD, lets vanilla run, and sends the
 * difference at RETURN. Player-inventory slots are deliberately excluded: they
 * belong to the player and are already synchronized by
 * {@code PlayerStateSyncManager}.</p>
 *
 * <p>While the click runs, the block entity broadcast for the affected chunk is
 * suppressed, because the owner writes the same change and broadcasts the
 * authoritative NBT. This prevents both copies from racing.</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuClickMixin {

    /**
     * Captures the container slots before vanilla changes them.
     *
     * @param slotId  clicked slot
     * @param button  mouse button
     * @param type    click type
     * @param player  clicking player
     * @param callbackInfo mixin callback
     */
    @Inject(method = "clicked", at = @At("HEAD"))
    private void multiforge$beginClick(int slotId, int button, ClickType type, Player player,
                                       CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.containers().beginClick((AbstractContainerMenu) (Object) this, player);
        }
    }

    /**
     * Sends the changed container slots to the owner.
     *
     * @param slotId  clicked slot
     * @param button  mouse button
     * @param type    click type
     * @param player  clicking player
     * @param callbackInfo mixin callback
     */
    @Inject(method = "clicked", at = @At("RETURN"))
    private void multiforge$endClick(int slotId, int button, ClickType type, Player player,
                                     CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.containers().endClick((AbstractContainerMenu) (Object) this, player);
        }
    }
}
