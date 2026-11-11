package com.multiforge.forge.peer.packet;

import com.multiforge.forge.peer.PeerConnection;
import com.multiforge.forge.peer.PeerPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Forwards a chat message to the other servers so every player sees it.
 *
 * <p>MultiPaper replaces {@code Bukkit.broadcastMessage} with a cluster wide
 * broadcast. Forge has no equivalent API, so MultiForge broadcasts
 * {@code ServerChatEvent} results as JSON components and injects received
 * messages with {@code PlayerList.broadcastSystemMessage}.</p>
 */
public class ChatMessagePacket extends PeerPacket {

    /** JSON serialized chat component. */
    public final String componentJson;
    /** UUID of the sending player. */
    public final UUID sender;
    /** Name of the sending server, used to avoid echo. */
    public final String originServer;

    /**
     * @param componentJson JSON chat component
     * @param sender        sending player UUID
     * @param originServer  server that produced the message
     */
    public ChatMessagePacket(String componentJson, UUID sender, String originServer) {
        this.componentJson = componentJson;
        this.sender = sender;
        this.originServer = originServer;
    }

    /**
     * @param buf buffer positioned after the packet id
     */
    public ChatMessagePacket(FriendlyByteBuf buf) {
        this.componentJson = buf.readUtf(262144);
        this.sender = buf.readUUID();
        this.originServer = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(componentJson, 262144);
        buf.writeUUID(sender);
        buf.writeUtf(originServer);
    }

    @Override
    public void handle(PeerConnection connection) {
        connection.server().players().onRemoteChat(this);
    }
}
