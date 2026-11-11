package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Uploads statistics after vanilla flushed them to disk.
 *
 * <p>{@code ServerStatsCounter.save()} writes {@code stats/<uuid>.json}. Uploading in
 * the same call keeps the master's copy at most one save interval behind, which is
 * the same guarantee MultiPaper gives.</p>
 */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterMixin {

    @Shadow
    @Final
    private File file;

    /**
     * Uploads the freshly written statistics file.
     *
     * @param callbackInfo mixin callback
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void multiforge$uploadStats(CallbackInfo callbackInfo) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null || file == null || !file.isFile()) {
            return;
        }
        try {
            String name = file.getName();
            UUID uuid = UUID.fromString(name.substring(0, name.length() - ".json".length()));
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            server.players().uploadStats(uuid, json);
        } catch (IOException | IllegalArgumentException e) {
            // Statistics are best effort; vanilla already has the local copy.
        }
    }
}
