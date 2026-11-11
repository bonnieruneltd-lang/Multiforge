package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.PlayerAdvancements;
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
 * Keeps player advancements on the master.
 *
 * <p>Same two-hook pattern as {@link ServerStatsCounterMixin}: prefetch before the
 * constructor reads the file, upload after {@code save()} wrote it. The player UUID
 * comes from the constructor argument, so no shadow of the mutable player field is
 * needed.</p>
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow
    @Final
    private Path playerSavePath;

    /**
     * Uploads the freshly written advancements file.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void multiforge$uploadAdvancements(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || playerSavePath == null || !Files.isRegularFile(playerSavePath)) {
            return;
        }
        try {
            String json = Files.readString(playerSavePath, StandardCharsets.UTF_8);
            // The file name is <uuid>.json; reuse it to avoid a second player field.
            String name = playerSavePath.getFileName().toString();
            java.util.UUID uuid = java.util.UUID.fromString(name.substring(0, name.length() - ".json".length()));
            server.players().uploadAdvancements(uuid, json);
        } catch (IOException | IllegalArgumentException e) {
            // Advancements are best effort; vanilla already has the local copy.
        }
    }
}
