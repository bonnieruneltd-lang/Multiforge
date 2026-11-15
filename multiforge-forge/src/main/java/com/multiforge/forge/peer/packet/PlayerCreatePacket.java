package com.multiforge.forge.peer.packet;

import com.mojang.authlib.GameProfile;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Creates (or refreshes) the mirror copy of a player on another server.
 *
 * <p>Port of MultiPaper's {@code PlayerCreatePacket}. Ordinary entities are
 * mirrored from their saved NBT ({@link EntityUpdateNBTPacket}); a player cannot
 * be reconstructed that way because {@code EntityType.PLAYER} has no factory and
 * the game profile is not part of the entity tag. The owning server therefore
 * sends the profile, the live position and the player's {@code saveWithoutId}
 * data, and the receiver constructs an
 * {@link com.multiforge.forge.player.ExternalPlayer}.</p>
 *
 * <p>The packet is sent when a player joins and again after a respawn or a
 * dimension change. Receivers ignore it for worlds they do not have loaded and
 * retry while the target chunk is not ready. It is never sent back to the
 * origin server.</p>
 *
 * <p>Wire layout: game profile (UUID, name and skin properties), logical world
 * name, position, rotation, game mode id and the player data tag. Decoding only
 * reads the buffer; {@link #handle(PeerConnection)} hops to the server thread.</p>
 */
public final class PlayerCreatePacket extends PeerPacket {

    private final GameProfile profile;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final int gameMode;
    private final CompoundTag tag;

    /**
     * Snapshots a live player on the owning server thread.
     *
     * <p>{@code saveWithoutId} includes the inventory, health, effects and ender
     * chest, so the mirror starts with the same visible state as the owner.</p>
     *
     * @param world  logical world name
     * @param player player to snapshot
     * @param tag    pre-filled {@code saveWithoutId} tag
     */
    public PlayerCreatePacket(String world, ServerPlayer player, CompoundTag tag) {
        this(player.getGameProfile(), world, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer().getId(), tag);
    }

    /**
     * @param profile         full game profile including skin properties
     * @param world           logical world name
     * @param x               player X
     * @param y               player Y
     * @param z               player Z
     * @param yaw             body yaw in degrees
     * @param pitch           head pitch in degrees
     * @param gameMode        game mode id
     * @param tag             player data tag
     */
    public PlayerCreatePacket(GameProfile profile, String world, double x, double y, double z,
                              float yaw, float pitch, int gameMode, CompoundTag tag) {
        this.profile = profile;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.tag = tag;
    }

    /**
     * @param byteBuf payload positioned after the packet id
     */
    public PlayerCreatePacket(FriendlyByteBuf byteBuf) {
        this.profile = byteBuf.readGameProfile();
        this.world = byteBuf.readUtf();
        this.x = byteBuf.readDouble();
        this.y = byteBuf.readDouble();
        this.z = byteBuf.readDouble();
        this.yaw = byteBuf.readFloat();
        this.pitch = byteBuf.readFloat();
        this.gameMode = byteBuf.readVarInt();
        this.tag = byteBuf.readNbt();
    }

    @Override
    public void write(FriendlyByteBuf byteBuf) {
        byteBuf.writeGameProfile(profile);
        byteBuf.writeUtf(world);
        byteBuf.writeDouble(x);
        byteBuf.writeDouble(y);
        byteBuf.writeDouble(z);
        byteBuf.writeFloat(yaw);
        byteBuf.writeFloat(pitch);
        byteBuf.writeVarInt(gameMode);
        byteBuf.writeNbt(tag);
    }

    /** @return the profile the mirror uses for skins and the tab list */
    public GameProfile profile() {
        return profile;
    }

    /** @return logical world name */
    public String world() {
        return world;
    }

    /** @return X coordinate at send time */
    public double x() {
        return x;
    }

    /** @return Y coordinate at send time */
    public double y() {
        return y;
    }

    /** @return Z coordinate at send time */
    public double z() {
        return z;
    }

    /** @return body yaw in degrees */
    public float yaw() {
        return yaw;
    }

    /** @return head pitch in degrees */
    public float pitch() {
        return pitch;
    }

    /** @return game mode id */
    public int gameMode() {
        return gameMode;
    }

    /** @return player data tag */
    public CompoundTag tag() {
        return tag;
    }

    /**
     * @param gameModeId vanilla game mode id
     * @return matching game mode, or survival when the id is unknown
     */
    public static GameType gameType(int gameModeId) {
        GameType type = GameType.byId(gameModeId);
        return type == null ? GameType.SURVIVAL : type;
    }

    /**
     * Applies the create on the server thread.
     *
     * @param connection connection the packet arrived on
     */
    @Override
    public void handle(PeerConnection connection) {
        MultiForgeServer server = MultiForgeServer.instanceOrNull();
        if (server != null) {
            server.playerMirrors().onRemoteCreate(connection.remoteName(), this);
        }
    }
}
