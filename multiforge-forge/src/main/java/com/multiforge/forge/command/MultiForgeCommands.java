package com.multiforge.forge.command;

import com.multiforge.api.ChunkLocation;
import com.multiforge.api.ServerInfo;
import com.multiforge.forge.MultiForgeServer;
import com.multiforge.forge.peer.PacketLossInjector;
import com.multiforge.forge.player.PlayerSyncManager;
import com.multiforge.protocol.ChunkKey;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.raid.Raid;
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
                .then(Commands.literal("map")
                        .executes(context -> map(context.getSource(), 8))
                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 32))
                                .executes(context -> map(context.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("borders").executes(context -> borders(context.getSource())))
                .then(Commands.literal("fights").executes(context -> fights(context.getSource())))
                .then(Commands.literal("drop")
                        .executes(context -> drop(context.getSource(), -1))
                        .then(Commands.argument("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                .executes(context -> drop(context.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "percent")))))
                .then(Commands.literal("startraid")
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(context -> startRaid(context.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
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

    /**
     * Toggles the chunk-border particle visualization for the invoking player.
     *
     * <p>{@code debug.chunkBorders} must be enabled in the config; the option is
     * documented in {@code docs/DEVELOPMENT.md}.</p>
     *
     * @param source command source
     * @return 1 when the state changed, 0 otherwise
     */
    /**
     * Prints a text map of chunk ownership around the player.
     *
     * <p>Port of MultiPaper's {@code /mpmap} without the client-side rendering:
     * one character per chunk, {@code @} for a chunk this server ticks, the
     * upper-case first letter of the owning server for a chunk another server
     * ticks, and {@code .} for a chunk without a known owner (idle, or not loaded
     * anywhere yet). The rows are ordinary chat lines, so the command works on a
     * normal client and can be copied into a bug report.</p>
     *
     * @param source command source
     * @param radius chunks in each direction, 1-32
     * @return number of owners that are not this server (for command result tools)
     */
    private static int map(CommandSourceStack source, int radius) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        ServerLevel level;
        ChunkPos center;
        try {
            ServerPlayer player = source.getPlayerOrException();
            level = (ServerLevel) player.level();
            center = player.chunkPosition();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run this command as a player."));
            return 0;
        }
        String local = runtime.serverName();
        int foreign = 0;
        source.sendSuccess(() -> Component.literal(String.format(
                "Chunk ownership around %d,%d (radius %d) - @ = this server, letters = other servers, . = unowned",
                center.x, center.z, radius)), false);
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder(radius * 2 + 1);
            for (int dx = -radius; dx <= radius; dx++) {
                ChunkKey key = runtime.chunks().keyOf(level, center.x + dx, center.z + dz);
                String owner = runtime.chunks().ownerName(key);
                if (owner == null) {
                    row.append('.');
                } else if (owner.equals(local)) {
                    row.append('@');
                } else {
                    row.append(Character.toUpperCase(owner.charAt(0)));
                    foreign++;
                }
            }
            source.sendSuccess(() -> Component.literal(row.toString()), false);
        }
        return foreign;
    }

    /**
     * Re-reads {@code config/multiforge-server.toml} and applies it without a restart.
     *
     * <p>Forge 47.4.29 does not fire config reload events on `/reload`, so this command
     * parses the file directly. Values that are bound or negotiated once (peer port, server
     * name, existing peer compression, scheduler periods) are logged as restart-only.</p>
     *
     * @param source command source
     * @return 1 when a config was loaded, 0 otherwise
     */
    private static int reload(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        if (!runtime.reloadConfigFromDisk()) {
            source.sendFailure(Component.literal(
                    "Could not read config/multiforge-server.toml; keeping the current settings."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "MultiForge configuration reloaded from disk (restart-only values are logged)."), false);
        return 1;
    }

    private static int borders(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        if (!runtime.config().debugChunkBorders()) {
            source.sendFailure(Component.literal(
                    "Chunk border visualization is disabled (set debug.chunkBorders=true)."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Only a player can toggle chunk borders."));
            return 0;
        }
        boolean enabled = runtime.borders().toggle(player);
        source.sendSuccess(() -> Component.literal("Chunk border visualization "
                + (enabled ? "on" : "off") + "."), false);
        return 1;
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

    /**
     * Prints the replicated raid and dragon-fight state.
     *
     * <p>One stable {@code key=value} line per raid and per loaded end level. The
     * {@code local=true} flag marks the server that ticks the fight; every other
     * server must show the same id/UUID and phase with {@code local=false}. The
     * live cluster harness parses these lines to prove state replication and a
     * handover without needing a graphical client.</p>
     *
     * @param source command source
     * @return number of printed lines
     */
    private static int fights(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        java.util.List<String> lines = runtime.fights().describe();
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No loaded raids or dragon fights."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Fight state (" + lines.size() + " entries):"), false);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /**
     * Starts a raid at a player's position, bypassing the village POI search.
     *
     * <p>Vanilla starts raids when a player with Bad Omen enters a village, which
     * makes a live raid test depend on world generation. This op-only command applies
     * Bad Omen and calls the same {@code Raids.createOrExtendRaid} the vanilla effect
     * uses, so a test (or an operator reproducing a report) can start a raid
     * anywhere. It is the only way {@code tools/compat/fight_sync_check.py} can
     * exercise the raid path on the flat CI world.</p>
     *
     * @param source command source
     * @param player player the raid is centred on
     * @return 1 when a raid exists afterwards
     */
    private static int startRaid(CommandSourceStack source, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        player.addEffect(new MobEffectInstance(MobEffects.BAD_OMEN, 1200, 1));
        Raid raid = level.getRaids().createOrExtendRaid(player);
        if (raid == null) {
            source.sendFailure(Component.literal("Could not start a raid for " + player.getName().getString()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started raid " + raid.getId()
                + " at " + raid.getCenter() + " for " + player.getName().getString()), false);
        return 1;
    }

    /**
     * Prints or changes the debug packet-loss percentage.
     *
     * <p>Only the first transmission of block and block-entity updates is dropped;
     * retransmissions are not, so the sequence/ack path repairs the stream. Used by
     * {@code tools/compat/packet_loss_check.py} to verify convergence under forced
     * loss on a live cluster. Default 0.</p>
     *
     * @param source  command source
     * @param percent new percentage, or a negative value to only print
     * @return current percentage
     */
    private static int drop(CommandSourceStack source, int percent) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        if (percent >= 0) {
            PacketLossInjector.setPercent(percent);
        }
        int current = PacketLossInjector.percent();
        source.sendSuccess(() -> Component.literal(
                "Chunk update packet loss injection: " + current
                        + "% (block/block-entity first transmissions; retransmits unaffected)"), false);
        return current;
    }

    private static int status(CommandSourceStack source) {
        MultiForgeServer runtime = runtime(source);
        if (runtime == null) {
            return 0;
        }
        String line = String.format(
                "%s | master=%s | tps=%.1f | chunks owned=%d loaded=%d | peers=%d | mirrored=%d "
                        + "| io reads=%d writes=%d failures=%d | seq gaps=%d dup=%d | local-backlog=%d | drop=%d",
                runtime.serverName(),
                runtime.master().isConnected() ? "connected" : "disconnected",
                runtime.tps(), runtime.chunks().ownedChunkCount(), runtime.chunks().loadedChunkCount(),
                runtime.peers().connections().size(), runtime.entities().mirroredEntityCount(),
                runtime.chunkIo().readCount(), runtime.chunkIo().writeCount(), runtime.chunkIo().failureCount(),
                runtime.chunks().sequenceGapCount(), runtime.chunks().sequenceDuplicateCount(),
                runtime.chunkIo().backlogCount(), PacketLossInjector.percent());
        source.sendSuccess(() -> Component.literal(line), false);
        Map<String, com.multiforge.forge.world.FileSyncer.DownloadState> downloads =
                runtime.files().activeDownloads();
        for (Map.Entry<String, com.multiforge.forge.world.FileSyncer.DownloadState> entry : downloads.entrySet()) {
            com.multiforge.forge.world.FileSyncer.DownloadState state = entry.getValue();
            source.sendSuccess(() -> Component.literal(String.format("download %s: %d%% (%d/%d bytes)",
                    entry.getKey(), state.percent(), state.transferred(), state.total())), false);
        }
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
