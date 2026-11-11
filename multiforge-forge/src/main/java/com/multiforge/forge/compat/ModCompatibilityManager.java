package com.multiforge.forge.compat;

import com.multiforge.forge.MultiForgeServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the required compatibility targets and reports their state.
 *
 * <p>MultiForge is a server-side mod, so a dedicated server can only observe the
 * server-side half of the required compatibility list. This manager therefore
 * does two things and deliberately nothing more:</p>
 *
 * <ol>
 *   <li>it verifies at runtime that each required <b>server</b> target is actually
 *       installed, records its version and logs a single summary line. That turns
 *       the pinned manifest in {@code compat/required-mods.json} into evidence:
 *       the versions in the log can be compared with the manifest;</li>
 *   <li>it reports known, version-independent conflicts between the targets
 *       themselves and the Forge version MultiForge was built against.</li>
 * </ol>
 *
 * <p>It does <b>not</b> claim functional compatibility. Loading a mod is not
 * compatibility; the functional checks live in
 * {@code docs/REQUIRED_MOD_COMPATIBILITY.md} and the live harness in
 * {@code tools/compat}.</p>
 *
 * <p>Threading: {@link #initialize()} must run on the server thread after Forge
 * finished loading mods; the snapshot is immutable afterwards and can be read
 * from any thread (commands, API, logs).</p>
 */
public final class ModCompatibilityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MultiForge/Compat");

    /** Forge release MultiForge is built and tested against. */
    public static final String TESTED_FORGE_VERSION = "47.4.29";

    /** Distribution side of a compatibility target. */
    public enum Side {
        /** Only needed on a dedicated server. */
        SERVER,
        /** Only needed on a client. */
        CLIENT,
        /** Needed on both sides. */
        BOTH
    }

    /**
     * One declared compatibility target.
     *
     * @param modId Forge mod id, or {@code null} for packs/data packs that are
     *              not mods
     * @param name  human readable name used in reports
     * @param side  where the target belongs
     * @param kind  distribution kind ({@code mod}, {@code resourcepack},
     *              {@code shader}, {@code datapack})
     */
    public record Target(String modId, String name, Side side, String kind) {
    }

    /**
     * Result of looking one target up in the running game.
     *
     * @param target  declared target
     * @param loaded  whether a mod with the target id is loaded
     * @param version installed version, or an empty string
     */
    public record Result(Target target, boolean loaded, String version) {
    }

    /** Required client-side targets (15). */
    private static final List<Target> CLIENT_TARGETS = List.of(
            new Target(null, "Anti Cords Leaker", Side.CLIENT, "resourcepack"),
            new Target("badoptimizations", "BadOptimizations", Side.CLIENT, "mod"),
            new Target(null, "BSL Shaders", Side.CLIENT, "shader"),
            new Target(null, "Complementary Shaders - Reimagined", Side.CLIENT, "shader"),
            new Target("cullleaves", "Cull Leaves", Side.CLIENT, "mod"),
            new Target("dynamic_fps", "Dynamic FPS", Side.CLIENT, "mod"),
            new Target("embeddium", "Embeddium", Side.CLIENT, "mod"),
            new Target("entityculling", "Entity Culling", Side.CLIENT, "mod"),
            new Target("immediatelyfast", "ImmediatelyFast", Side.CLIENT, "mod"),
            new Target("kerria", "Kerria", Side.CLIENT, "mod"),
            new Target("obe", "Optimized Block Entities [OBE]", Side.CLIENT, "mod"),
            new Target("oculus", "Oculus", Side.CLIENT, "mod"),
            new Target("rrls", "Remove Reloading Screen", Side.CLIENT, "mod"),
            new Target("embeddium_extra", "Embeddium (Rubidium) Extra", Side.CLIENT, "mod"),
            new Target(null, "Sildur's Vibrant Shaders (Lite)", Side.CLIENT, "shader")
    );

    /** Required server-side targets (20). */
    private static final List<Target> SERVER_TARGETS = List.of(
            new Target("mr_darcenos_minecarts", "Darceno's Minecarts", Side.SERVER, "mod"),
            new Target("justplayerheads", "Just Player Heads", Side.SERVER, "mod"),
            new Target("mr_timber_strike", "Timber Strike", Side.SERVER, "mod"),
            new Target("lithostitched", "Lithostitched", Side.SERVER, "mod"),
            new Target("tectonic", "Tectonic", Side.SERVER, "mod"),
            new Target("fastleafdecay", "Fast Leaf Decay", Side.SERVER, "mod"),
            new Target("aiimprovements", "AI Improvements", Side.SERVER, "mod"),
            new Target("asynclocator", "Async Locator", Side.SERVER, "mod"),
            new Target("getittogetherdrops", "Get It Together, Drops!", Side.SERVER, "mod"),
            new Target("ksyxis", "Ksyxis", Side.SERVER, "mod"),
            new Target("noisium", "Noisium", Side.SERVER, "mod"),
            new Target("radium", "Radium", Side.SERVER, "mod"),
            new Target("servercore", "ServerCore", Side.SERVER, "mod"),
            new Target("shinoyuki_betterautosave", "Shinoyuki-BetterAutoSave", Side.SERVER, "mod"),
            new Target("structure_layout_optimizer", "Structure Layout Optimizer", Side.SERVER, "mod"),
            new Target("antixray", "AntiXray", Side.SERVER, "mod"),
            new Target("beautifiedchatserver", "Beautified Chat [Server]", Side.SERVER, "mod"),
            new Target("combatlog", "Combatlog", Side.SERVER, "mod"),
            new Target("tpateleport", "TPA Teleport", Side.SERVER, "mod"),
            new Target(null, "UltimateAntiSeedCracker", Side.SERVER, "datapack")
    );

    private final MultiForgeServer server;
    private final Map<String, Result> results = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private volatile boolean initialized;

    /**
     * @param server runtime this manager belongs to
     */
    public ModCompatibilityManager(MultiForgeServer server) {
        this.server = server;
    }

    /**
     * Looks up every declared target once. Called after Forge finished loading
     * mods, before the first player can join.
     *
     * <p>Side effects: fills the result snapshot and writes one summary line plus
     * one warning per detected conflict to the log. Later calls are no-ops.</p>
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        ModList mods = ModList.get();
        for (Target target : concat(CLIENT_TARGETS, SERVER_TARGETS)) {
            String version = "";
            boolean loaded = false;
            if (target.modId() != null && mods != null) {
                var container = mods.getModContainerById(target.modId());
                if (container.isPresent()) {
                    loaded = true;
                    version = container.get().getModInfo().getVersion().toString();
                }
            }
            results.put(target.name(), new Result(target, loaded, version));
        }
        detectConflicts();
        initialized = true;

        int serverMods = 0;
        int serverLoaded = 0;
        for (Result result : results.values()) {
            if (result.target().side() == Side.SERVER && result.target().modId() != null) {
                serverMods++;
                if (result.loaded()) {
                    serverLoaded++;
                }
            }
        }
        LOGGER.info("Required compatibility targets on this dedicated server: {}/{} server mods loaded "
                        + "(Forge {}, MultiForge tested against Forge {})",
                serverLoaded, serverMods, ForgeVersion.getVersion(), TESTED_FORGE_VERSION);
        for (Result result : results.values()) {
            if (result.target().side() == Side.SERVER && result.target().modId() != null) {
                LOGGER.info("  {} {} {}", result.loaded() ? "[loaded]" : "[missing]",
                        result.target().name(), result.version());
            }
        }
        for (String warning : warnings) {
            LOGGER.warn("Compatibility: {}", warning);
        }
    }

    private void detectConflicts() {
        if (isLoaded("radium") && isLoaded("canary")) {
            warnings.add("Radium and Canary are both installed; Radium declares Canary incompatible. "
                    + "Remove one of them.");
        }
        if (isLoaded("tectonic") && !isLoaded("lithostitched")) {
            warnings.add("Tectonic is installed without Lithostitched; Tectonic requires it.");
        }
        if (isLoaded("structure_layout_optimizer") && !isLoaded("resourcefulconfig")) {
            warnings.add("Structure Layout Optimizer is installed without Resourceful Config; it requires it.");
        }
        if (isLoaded("justplayerheads") && !isLoaded("collective")) {
            warnings.add("Just Player Heads is installed without Collective; it requires it.");
        }
        for (Result result : results.values()) {
            if (result.target().side() == Side.CLIENT && result.loaded()) {
                warnings.add(result.target().name() + " is a client-only mod but is installed on the "
                        + "dedicated server; remove it from the server mods directory.");
            }
        }
        if (!ForgeVersion.getVersion().equals(TESTED_FORGE_VERSION)) {
            warnings.add("Forge " + ForgeVersion.getVersion() + " differs from the tested "
                    + TESTED_FORGE_VERSION + "; compatibility evidence was produced on the tested version.");
        }
    }

    private static List<Target> concat(List<Target> first, List<Target> second) {
        List<Target> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    /**
     * @param modId Forge mod id
     * @return whether that mod is loaded
     */
    public boolean isLoaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    /**
     * @return immutable target name -> result snapshot, in declaration order
     */
    public Map<String, Result> results() {
        return Collections.unmodifiableMap(results);
    }

    /**
     * @return number of required server-side mod targets that are loaded
     */
    public int loadedServerTargets() {
        int count = 0;
        for (Result result : results.values()) {
            if (result.target().side() == Side.SERVER && result.target().modId() != null && result.loaded()) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return number of required server-side targets that are Forge mods
     */
    public int serverTargetCount() {
        int count = 0;
        for (Result result : results.values()) {
            if (result.target().side() == Side.SERVER && result.target().modId() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return detected conflicts and deployment warnings, never {@code null}
     */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Builds the text used by {@code /multiforge compat} and the log summary.
     *
     * @return one line per target (server targets first) plus warnings
     */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        lines.add("MultiForge compatibility (Forge " + ForgeVersion.getVersion()
                + ", tested " + TESTED_FORGE_VERSION + ")");
        lines.add("Required server targets loaded: " + loadedServerTargets() + "/" + serverTargetCount());
        addSection(lines, "Server targets", SERVER_TARGETS);
        addSection(lines, "Client targets", CLIENT_TARGETS);
        if (warnings.isEmpty()) {
            lines.add("No conflicts detected.");
        } else {
            lines.add("Warnings:");
            for (String warning : warnings) {
                lines.add(" - " + warning);
            }
        }
        return lines;
    }

    private void addSection(List<String> lines, String title, List<Target> targets) {
        lines.add(title + ":");
        for (Target target : targets) {
            if (target.modId() == null) {
                lines.add("  [pack] " + target.name() + " (" + target.kind()
                        + ", not detectable from the server)");
                continue;
            }
            Result result = results.get(target.name());
            String version = result == null || result.version().isEmpty() ? "" : " " + result.version();
            lines.add("  " + (result != null && result.loaded() ? "[loaded] " : "[missing] ")
                    + target.name() + version);
        }
    }

    /** @return the runtime this manager belongs to */
    public MultiForgeServer server() {
        return server;
    }
}
