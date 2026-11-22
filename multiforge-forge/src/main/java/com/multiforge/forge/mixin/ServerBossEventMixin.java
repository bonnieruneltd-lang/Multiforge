package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards every owner-side boss-bar mutation to the other servers.
 *
 * <p>Vanilla creates a {@code ServerBossEvent} only on the server that owns the
 * fight. The injected methods cover the mutations that change what players see:
 * adding the first player (ADD), removing the bar, progress, name, style and the
 * darken/music/fog properties. {@code setVisible} needs no hook because vanilla
 * implements it by adding/removing players, which is already covered.</p>
 *
 * <p>All injections run on the server thread and only enqueue peer writes through
 * {@link com.multiforge.forge.world.BossEventSyncManager}.</p>
 */
@Mixin(ServerBossEvent.class)
public abstract class ServerBossEventMixin {

    /**
     * @param player       player added to the bar
     * @param callbackInfo mixin callback
     */
    @Inject(method = "addPlayer", at = @At("TAIL"))
    private void multiforge$addPlayer(ServerPlayer player, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onAdd((ServerBossEvent) (Object) this, player);
        }
    }

    /**
     * @param callbackInfo mixin callback
     */
    @Inject(method = "removeAllPlayers", at = @At("TAIL"))
    private void multiforge$removeAll(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onRemoveAll((ServerBossEvent) (Object) this);
        }
    }

    /**
     * @param progress     new progress
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setProgress", at = @At("TAIL"))
    private void multiforge$setProgress(float progress, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onUpdate((ServerBossEvent) (Object) this,
                    ClientboundBossEventPacket::createUpdateProgressPacket);
        }
    }

    /**
     * @param name         new name
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setName", at = @At("TAIL"))
    private void multiforge$setName(net.minecraft.network.chat.Component name, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onUpdate((ServerBossEvent) (Object) this,
                    ClientboundBossEventPacket::createUpdateNamePacket);
        }
    }

    /**
     * @param color        new colour
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setColor", at = @At("TAIL"))
    private void multiforge$setColor(net.minecraft.world.BossEvent.BossBarColor color, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onUpdate((ServerBossEvent) (Object) this,
                    ClientboundBossEventPacket::createUpdateStylePacket);
        }
    }

    /**
     * @param overlay      new overlay
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setOverlay", at = @At("TAIL"))
    private void multiforge$setOverlay(net.minecraft.world.BossEvent.BossBarOverlay overlay, CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onUpdate((ServerBossEvent) (Object) this,
                    ClientboundBossEventPacket::createUpdateStylePacket);
        }
    }

    /**
     * @param value        new darken flag
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setDarkenScreen", at = @At("TAIL"))
    private void multiforge$setDarken(boolean value, CallbackInfoReturnable<net.minecraft.world.BossEvent> callbackInfo) {
        multiforge$properties();
    }

    /**
     * @param value        new music flag
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setPlayBossMusic", at = @At("TAIL"))
    private void multiforge$setMusic(boolean value, CallbackInfoReturnable<net.minecraft.world.BossEvent> callbackInfo) {
        multiforge$properties();
    }

    /**
     * @param value        new fog flag
     * @param callbackInfo mixin callback
     */
    @Inject(method = "setCreateWorldFog", at = @At("TAIL"))
    private void multiforge$setFog(boolean value, CallbackInfoReturnable<net.minecraft.world.BossEvent> callbackInfo) {
        multiforge$properties();
    }

    /** Broadcasts the current darken/music/fog properties. */
    private void multiforge$properties() {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.bossEvents().onUpdate((ServerBossEvent) (Object) this,
                    ClientboundBossEventPacket::createUpdatePropertiesPacket);
        }
    }
}
