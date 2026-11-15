package com.multiforge.forge.mixin;

import com.multiforge.forge.MultiForgeServer;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Uploads whitelist/ops/ban changes immediately.
 *
 * <p>{@link StoredUserList#add} and {@link StoredUserList#remove} write the JSON
 * file before they return. The periodic scanner would notice the changed file
 * within a second, but an op change should reach the cluster without waiting for
 * the next scan. This hook hands the file name to {@code FileSyncer}, which
 * uploads it through the master; the master then broadcasts a
 * {@code DataUpdateMessage} and every other server reloads its in-memory list
 * (see {@code PlayerSyncManager#reloadUserList}).</p>
 *
 * <p>The constructor of {@code StoredUserList} calls {@code load()}, not
 * {@code add()}, so loading a file does not echo back to the master. All methods
 * run on the server thread.</p>
 */
@Mixin(StoredUserList.class)
public abstract class StoredUserListMixin {

    /**
     * @param entry      entry that was added
     * @param callbackInfo mixin callback
     */
    @Inject(method = "add(Lnet/minecraft/server/players/StoredUserEntry;)V", at = @At("RETURN"))
    private void multiforge$afterAdd(StoredUserEntry<?> entry, CallbackInfo callbackInfo) {
        multiforge$upload();
    }

    /**
     * Handles the typed overload used by callers that already have an entry.
     *
     * @param entry      entry that was removed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "remove(Lnet/minecraft/server/players/StoredUserEntry;)V", at = @At("RETURN"))
    private void multiforge$afterRemoveEntry(StoredUserEntry<?> entry, CallbackInfo callbackInfo) {
        multiforge$upload();
    }

    /**
     * Handles the key overload used by {@code /pardon} and {@code /whitelist remove}.
     *
     * @param key        key that was removed
     * @param callbackInfo mixin callback
     */
    @Inject(method = "remove(Ljava/lang/Object;)V", at = @At("RETURN"))
    private void multiforge$afterRemoveKey(Object key, CallbackInfo callbackInfo) {
        multiforge$upload();
    }

    /** Uploads the list file when MultiForge is running. */
    private void multiforge$upload() {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server == null) {
            return;
        }
        java.io.File file = ((StoredUserList<?, ?>) (Object) this).getFile();
        if (file != null) {
            server.files().onUserListChanged(file.getName());
        }
    }
}
