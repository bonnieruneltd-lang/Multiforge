package com.multiforge.forge.command;

import com.multiforge.api.ChunkLocation;
import com.multiforge.api.ServerInfo;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.player.PlayerSyncManager;
import com.multiforge.protocol.ChunkKey;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Operator commands of MultiForge.
 *
 * <p>Ports MultiPaper's {@code /servers}, {@code /slist} and {@code /mpdebug}
 * style commands. All commands require permission level 2 (op) and run on the
 * server thread.</p>
 */
public final class MultiForgeCommands {

    private MultiForgeCommands() {
    }

    /**
     * Registers {@code /multiforge} and its subcommands.
     *
     * @param event command registration event
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("multiforge")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("servers").executes(context -> servers(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("players").executes(context -> players(context.getSource())))
                .then(Commands.literal("chunk")
                        .executes(context -> chunk(context.getSource()))
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(context -> chunkAt(context.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z"))))))
                .then(Commands.literal("chunks").executes(context -> chunks(context.getSource())))
                .then(Commands.literal("compat").executes(context -> compat(context.getSource())))
                .then(Commands.literal("download")
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> download(context.getSource(),
                                        StringArgumentType.getString(context, "path"))))));

        // Short aliases mirroring MultiPaper's command names.
        dispatcher.register(Commands.literal("mfservers")
                .requires(source -> source.hasPermission(2))
                .executes(context -> servers(context.getSource())));
        dispatcher.register(Commands.literal("mfslist")
                .requires(source -> source.hasPermission(2))
                .executes(context -> players(context.getSource())));
    }

    private static int servers(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Servers in this MultiForge cluster:"), false);
        for (ServerInfo info : runtime.api().getServers()) {
            String line = String.format(" - %s %s tps=%.1f players=%d %s",
                    info.name(), info.address().isEmpty() ? "(no peer port)" : info.address(),
                    info.tps(), info.playerCount(), info.alive() ? "alive" : "offline");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        String line = String.format(
                "%s | master=%s | tps=%.1f | chunks owned=%d loaded=%d | peers=%d | io reads=%d writes=%d failures=%d",
                runtime.serverName(),
                runtime.master().isConnected() ? "connected" : "disconnected",
                runtime.tps(), runtime.chunks().ownedChunkCount(), runtime.chunks().loadedChunkCount(),
                runtime.peers().connections().size(),
                runtime.chunkIo().readCount(), runtime.chunkIo().writeCount(), runtime.chunkIo().failureCount());
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int players(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        Map<UUID, PlayerSyncManager.PlayerLocation> registry = runtime.players().snapshot();
        source.sendSuccess(() -> Component.literal("Players online in the cluster: " + registry.size()), false);
        for (Map.Entry<UUID, PlayerSyncManager.PlayerLocation> entry : registry.entrySet()) {
            PlayerSyncManager.PlayerLocation location = entry.getValue();
            String line = " - " + location.name() + " @ " + location.server() + " (" + entry.getKey() + ")";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return registry.size();
    }

    private static int chunk(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        ChunkPos pos;
        try {
            pos = new ChunkPos(source.getPlayerOrException().blockPosition());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run this command as a player, or use /multiforge chunk <x> <z>"));
            return 0;
        }
        return chunkAt(source, pos.x, pos.z);
    }

    /**
     * Prints the cluster view of one chunk without requiring a player. The
     * coordinates are chunk coordinates, which makes the command usable from the
     * server console and from the compatibility harness.
     *
     * @param source command source
     * @param cx     chunk x
     * @param cz     chunk z
     * @return 1 when the chunk state was printed
     */
    private static int chunkAt(CommandSourceStack source, int cx, int cz) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        ChunkPos pos = new ChunkPos(cx, cz);
        ChunkKey key = runtime.chunks().keyOf(level, pos);
        String owner = runtime.chunks().ownerOf(key).orElse("unowned");
        boolean local = runtime.chunks().isLocal(key);
        var state = runtime.chunks().snapshot().get(key);
        boolean loaded = state != null && state.loaded();
        String line = String.format("Chunk %s owner=%s local=%s loaded=%s subscribers=%s",
                key, owner, local, loaded, runtime.chunks().subscribersOf(key));
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int chunks(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Chunks owned by " + runtime.serverName() + ": "
                + runtime.chunks().ownedChunkCount()), false);
        int shown = 0;
        for (ChunkKey key : runtime.chunks().ownedChunks()) {
            if (shown++ >= 50) {
                source.sendSuccess(() -> Component.literal("... (truncated)"), false);
                break;
            }
            source.sendSuccess(() -> Component.literal(" - " + key), false);
        }
        // The loaded list is always printed: ownership only exists while a chunk
        // ticks, so without a player the owned list alone is not enough to know
        // which chunks this server currently holds and could serve to peers.
        source.sendSuccess(() -> Component.literal("Loaded chunks on this server:"), false);
        int loadedShown = 0;
        for (Map.Entry<ChunkKey, com.multiforge.forge.chunk.ChunkState> entry
                : runtime.chunks().snapshot().entrySet()) {
            if (!entry.getValue().loaded()) {
                continue;
            }
            if (loadedShown++ >= 50) {
                source.sendSuccess(() -> Component.literal("... (truncated)"), false);
                break;
            }
            String line = " - " + entry.getKey() + " owner="
                    + (entry.getValue().owner() == null ? "unowned" : entry.getValue().owner());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return shown + loadedShown;
    }

    /**
     * Prints the required-target report (loaded/missing versions and any
     * deployment conflicts) generated by the compatibility manager.
     *
     * @param source command source
     * @return number of report lines sent
     */
    private static int compat(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        var lines = runtime.compat().report();
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    private static int download(CommandSourceStack source, String path) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        runtime.files().downloadFile(path);
        source.sendSuccess(() -> Component.literal("Requested " + path + " from the master"), false);
        return 1;
    }

    private static MultiForgeServer runtime(CommandSourceStack source) {
        MultiForgeServer runtime = MultiForgeServer.instanceOrNull();
        if (runtime == null) {
            source.sendFailure(Component.literal("MultiForge is not active on this server"));
        }
        return runtime;
    }

    // Kept for API symmetry with the original /mpdebug chunk lookup helper.
    static ChunkLocation location(ServerLevel level, ChunkPos pos) {
        return new ChunkLocation(level.dimension().location().toString(), pos.x, pos.z);
    }
}
