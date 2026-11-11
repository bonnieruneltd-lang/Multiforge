package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads advancements after vanilla flushed them to disk.
 *
 * <p>{@code PlayerAdvancements.save()} writes {@code advancements/<uuid>.json}; the
 * file is then uploaded to the master so the player keeps progress when joining
 * another server.</p>
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow
    @Final
    private Path playerSavePath;

    @Shadow
    private ServerPlayer player;

    /**
     * Uploads the freshly written advancements file.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void multiforge$uploadAdvancements(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || player == null || playerSavePath == null) {
            return;
        }
        try {
            if (!Files.isRegularFile(playerSavePath)) {
                return;
            }
            String json = Files.readString(playerSavePath, StandardCharsets.UTF_8);
            server.players().uploadAdvancements(player.getUUID(), json);
        } catch (IOException e) {
            // Advancements are best effort; vanilla already has the local copy.
        }
    }
}
