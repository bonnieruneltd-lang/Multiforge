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
 * Keeps player statistics on the master.
 *
 * <p>Two hooks are needed because vanilla reads the statistics file immediately
 * when the counter is constructed and writes it when it is saved:</p>
 * <ul>
 *   <li>constructor HEAD: copy the master's file to disk so the vanilla parser
 *       sees the cluster-wide values;</li>
 *   <li>{@code save} RETURN: upload the file vanilla just wrote.</li>
 * </ul>
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
        UUID uuid = uuidOf(file);
        if (uuid == null) {
            return;
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            server.players().uploadStats(uuid, json);
        } catch (IOException e) {
            // Statistics are best effort; vanilla already has the local copy.
        }
    }

    private static UUID uuidOf(File file) {
        String name = file.getName();
        if (!name.endsWith(".json")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
