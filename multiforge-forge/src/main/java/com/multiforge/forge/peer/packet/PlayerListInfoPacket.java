package com.multiforge.forge.peer.packet;

import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Live tab-list display name and game mode of a player hosted by another server.
 *
 * <p>{@code PlayerCreatePacket} carries both values once, when the mirror is
 * created. A player who changes game mode (or whose server changes the tab-list
 * display name, for example through a chat/rank plugin) would otherwise keep the
 * old icon on every other server until a full re-announce. This packet is sent
 * only when either value changes and is idempotent on the receiver.</p>
 *
 * <p>The display name is a JSON chat component; an empty string means "no custom
 * display name" and clears it. The receiver applies the values to the
 * {@code ExternalPlayer} and broadcasts a
 * {@code ClientboundPlayerInfoUpdatePacket} so local clients update the tab
 * list.</p>
 */
public final class PlayerListInfoPacket extends PeerPacket {

    /** Maximum accepted display-name JSON, matching a vanilla component string. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 32767;

    /** Player UUID. */
    public final UUID player;
    /** Display name JSON component, empty for "no custom name". */
    public final String displayName;
    /** Vanilla game mode id (0 survival, 1 creative, 2 adventure, 3 spectator). */
    public final int gameMode;

    /**
     * @param player      player UUID
     * @param displayName display name JSON, empty for none
     * @param gameMode    vanilla game mode id
     */
    public PlayerListInfoPacket(UUID player, String displayName, int gameMode) {
        this.player = player;
        this.displayName = displayName == null ? "" : displayName;
        this.gameMode = gameMode;
    }

    /**
     * @param buf payload positioned after the packet id
     */
    public PlayerListInfoPacket(FriendlyByteBuf buf) {
        this.player = buf.readUUID();
        this.displayName = buf.readUtf(MAX_DISPLAY_NAME_LENGTH);
        this.gameMode = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(player);
        buf.writeUtf(displayName, MAX_DISPLAY_NAME_LENGTH);
        buf.writeVarInt(gameMode);
    }

    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerMirrors().onRemoteListInfo(player, displayName, gameMode);
        }
    }
}
