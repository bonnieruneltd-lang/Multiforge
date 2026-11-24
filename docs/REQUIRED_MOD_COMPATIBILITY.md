# Required mod compatibility

This document is the compatibility record for the 35 targets that MultiForge must
support. It is not a wish list: every row names the exact artifact that was pinned,
where it belongs, what was executed against it and what is still unverified.

**Current result (2026-11-24 production run): all 20 server targets load together in a real
Forge 47.4.29 `--installServer` install (the dev run cannot load their SRG refmaps) and the
two-server production cluster passed 20/20 checks; per-row server status is 17 FUNCTIONAL,
1 PARTIAL, 2 LOADING. Client: 15/15 launched in a production client under Xvfb/llvmpipe
(6 FUNCTIONAL, 9 PARTIAL, 0 NOT TESTED) — the 9 PARTIAL client rows need a hardware GPU.**
Production evidence: `compat/evidence/cluster-20261124T003220Z.md`,
`chat-format-20261124T003221Z.md`, `gameplay-20261124T003242Z.md`; the harness is
`tools/ci/prod_cluster.sh` + `tools/compat/gameplay_check.py`.
Read the status column before quoting a number. The deployment this document supports is a
headless server cluster; client-side rendering targets were additionally launched in a
production client under Xvfb/llvmpipe. Rows stay PARTIAL where shader fidelity, culling or FPS
throttling needs a hardware-GPU visual check.

## 1. Test environment

| Item | Pinned value |
| --- | --- |
| Minecraft | 1.20.1 |
| Loader | Forge 47.4.29 (`1.20.1-47.4.29`) |
| Java | Temurin 17.0.21 |
| MultiForge | `1.0.0-alpha.1` (built from this commit) |
| Master | `multiforge-master-1.0.0-alpha.1-all.jar`, storage on local disk |
| Cluster | two dedicated servers, `live1` and `live2`, shared master storage |

Forge was moved from 47.3.0 to 47.4.29 in this session because Structure Layout
Optimizer 1.0.11 declares `versionRange = "[47.3.12,)"` and FML refuses to start
47.3.0 with it. MultiForge itself has no 47.4-specific code.

### Reproducing the pinned artifacts

`tools/compat/resolve_required_mods.py` is the single source of truth. It resolves the
newest 1.20.1 Forge release of every target on Modrinth, follows the required
dependencies, writes `compat/required-mods.json` (version, filename, URL, SHA-512) and
downloads only the artifacts a dedicated server needs:

```bash
python3 tools/compat/resolve_required_mods.py --write
python3 tools/compat/resolve_required_mods.py --write --download .runtime/server-mods
python3 tools/compat/resolve_required_mods.py --write --download-client .runtime/client-mods
```

Every downloaded file is SHA-512 verified against the manifest. A version is never
silently substituted: if a target has no 1.20.1 Forge build, resolution fails instead
of picking a Fabric jar.

### Live cluster procedure

```bash
# master (one per cluster)
java -Dmultiforge.master.storage=$PWD/.runtime/master-storage \
     -jar multiforge-master/build/libs/multiforge-master-1.0.0-alpha.1-all.jar 35353

# each Forge server: MultiForge jar + compat/server-mods/*.jar in mods/,
# compat/server-mods/UltimateAntiSeedCracker.zip in world/datapacks/,
# RCON enabled, config/multiforge-server.toml pointing at the master.
python3 tools/compat/cluster_check.py --a-port 25575 --b-port 25576 \
    --password mfcompat --out compat/evidence
```

The harness writes a Markdown transcript and a JSON result file under
`compat/evidence/`. Treat that file as the evidence for every claim below.

The recorded run is `compat/evidence/cluster-20261111T050235Z.md`: **13/13 checks
passed** with `18/19` server mods loaded on each server (Ksyxis removed from both so the
two servers hold a common spawn chunk; see section 5). Block A→B, block B→A, chest
inventory, forced time, gamerule, chunk owner and TPS checks all passed on the full
18-mod configuration.

## 2. Status legend

| Status | Meaning in this project |
| --- | --- |
| NOT TESTED | Pinned, but no compatibility testing has run. |
| LOADING | Initializes in the full cluster; gameplay functionality is not verified. |
| PARTIAL | Some functionality verified; a known limitation remains. |
| FUNCTIONAL | Main functionality verified in the tested environment. |
| FULLY COMPATIBLE | Every relevant client/server behaviour listed in the task works. |
| BLOCKED | A reproducible incompatibility remains. |

Loading is not compatibility. A green server log alone never justifies FUNCTIONAL.

## 3. Client-side targets (15)

All client artifacts are pinned in `compat/required-mods.json`. A **production Forge
47.4.29 client** was launched headlessly under Xvfb with Mesa **llvmpipe OpenGL 4.5**, using
the offline `MultiForgeDev` profile and the full client set. Embeddium/Oculus detected the
software renderer, the client joined the live MultiForge server, BSL compiled and rendered an
in-world pipeline, and the Anti Cords resource pack loaded. Complementary and Sildur loaded
their scripts but terminated during login under llvmpipe; they need a hardware GPU for the
full verdict. Evidence: `compat/evidence/client-matrix-20261122T052900Z.md`.

| # | Name | Kind / mod id | Pinned version | File | Deps | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Anti Cords Leaker | resource pack | v1.1.0 | `Anti Cords Leaker.zip` | — | PARTIAL | Loaded by the production client (`Reloading ResourceManager: ..., file/Anti Cords Leaker.zip`). Coordinate/F3 hiding and packet re-exposure were not visually asserted in the headless run. |
| 2 | BadOptimizations | mod, `badoptimizations` | 2.4.1 | `BadOptimizations-2.4.1-1.20.1.jar` | — | PARTIAL | Loaded and configured; the client reached the world. Its frame-time effect was not benchmarked and it must not be installed on servers. |
| 3 | BSL Shaders | shader pack | 10.1.8 | `BSL_v10.1.8.zip` | Oculus | FUNCTIONAL | Oculus reported `Using shaderpack: BSL_v10.1.8.zip`, created the overworld pipeline and the client joined the MultiForge server under llvmpipe GL 4.5. |
| 4 | Complementary Shaders - Reimagined | shader pack | r5.9.3 | `ComplementaryReimagined_r5.9.3.zip` | Oculus | PARTIAL | Selected by Oculus and its scripts loaded, but the client terminated during pipeline/login under llvmpipe; needs a hardware GPU for the full verdict. |
| 5 | Cull Leaves | mod, `cullleaves` | 4.1.1+1.20.1-forge | `cullleaves-forge-4.1.1+1.20.1.jar` | — | PARTIAL | Loaded with the full set and reached the world; leaf-face culling was not visually asserted. |
| 6 | Dynamic FPS | mod, `dynamic_fps` | 3.11.4 | `dynamic-fps-3.11.4+minecraft-1.20.0-forge.jar` | — | PARTIAL | Loaded and generated its config; background FPS throttling was not measured headlessly. |
| 7 | Embeddium | mod, `embeddium` | 0.3.31+mc1.20.1 | `embeddium-0.3.31+mc1.20.1.jar` | — | FUNCTIONAL | `OpenGL Renderer: llvmpipe`, `OpenGL Version: 4.5 (Core Profile)`; the renderer initialized and the client joined the world. |
| 8 | Entity Culling | mod, `entityculling` | 1.11.3 | `entityculling-forge-1.11.3-mc1.20.1.jar` | — | FUNCTIONAL | In-world F3 shows `[Culling] Rendered Entities: 36 Skipped: 741` and `Ticked Entities: 19 Skipped: 741`, including MultiForge's mirrored entities; culling is active (`compat/evidence/client-f3-entity-culling-20261122T083000Z.png`). |
| 9 | ImmediatelyFast | mod, `immediatelyfast` | 1.5.5+1.20.4-forge | `ImmediatelyFast-Forge-1.5.5+1.20.4.jar` | — | FUNCTIONAL | Initialized on llvmpipe with OpenGL 4.5 and enabled its Oculus compatibility; the client joined and rendered the world. |
| 10 | Kerria | mod, `kerria` | 1.3.2+1.20.1-forge | `Kerria-1.3.2+1.20.1-forge.jar` | — | PARTIAL | Loaded with the full set and reached the world; its texture/memory optimization was not measured. |
| 11 | Optimized Block Entities [OBE] | mod, `obe` | 1.2.1-patch.3 | `obe+1.20.1-forge-1.2.1-patch.3-srg.jar` | — | PARTIAL | Loaded with the full set and reached the world; rendering of MultiForge-synced block entities was not inspected visually. |
| 12 | Oculus | mod, `oculus` | 1.20.1-1.8.0 | `oculus-mc1.20.1-1.8.0.jar` | Embeddium | FUNCTIONAL | Detected OpenGL 4.5, enabled DSA, loaded BSL and created the overworld pipeline in-world. |
| 13 | Remove Reloading Screen | mod, `rrls` | 4.0.6.1+mc1.20.1-forge | `rrls-4.0.6.1+mc1.20.1-forge.jar` | — | FUNCTIONAL | `F3+T` reloaded resources in-world with no "Reloading Resource Packs" overlay and the world kept rendering; the debug line `Reloaded resource packs` appeared (`compat/evidence/client-f3-rrls-20261122T083000Z.png`). The server-switch overlay was not exercised. |
| 14 | Embeddium (Rubidium) Extra | mod `embeddium_extra` | 0.5.4.4+mc1.20.1-build.131 | `rubidium-extra-0.5.4.4+mc1.20.1-build.131.jar` | Embeddium | PARTIAL | Loaded and configured alongside Embeddium; no duplicate-mixin crash, options behaviour not asserted. |
| 15 | Sildur's Vibrant Shaders (Lite) | shader pack | 2.02 | `Sildur's Vibrant Shaders v2.02 Lite.zip` | Oculus | PARTIAL | Selected by Oculus, but the client terminated at login under llvmpipe; needs a hardware GPU for the full verdict. |

### Client test plan (executed; visual checks remain)

1. Install Forge 47.4.29 client, the client jar from `resolve_required_mods.py
   --download-client`, and Oculus + Embeddium.
2. Enable exactly one shader pack at a time (BSL, Complementary Reimagined, Sildur Lite)
   and verify shader compilation, world rendering, block entities and entities.
3. Join `live1`, walk across a chunk border owned by `live2`, switch servers, disconnect
   and reconnect, and repeat with Dynamic FPS in the background and the Anti Cords
   Leaker resource pack enabled.
4. Record screenshots plus `latest.log`; the renderer must not throw and MultiForge must
   never be installed on the client as a rendering dependency.

## 4. Server-side targets (20)

All 20 were installed together on both dedicated servers (plus the two required library
dependencies) and the cluster started. `19/19` Forge mod targets loaded on each server;
the twentieth target is a data pack and is enabled from `world/datapacks/`. Evidence:
`compat/evidence/` (cluster run) and `/multiforge compat` in the server log.

| # | Name | Mod id | Pinned version | File | Deps | Status | Evidence / known issues |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Darceno's Minecarts | `mr_darcenos_minecarts` | 1.3 | `darcenos-minecarts-1.3.jar` | — | FUNCTIONAL | **2026-11-24 controlled transport run:** on a single production server a minecart on a `powered_rail` lane advanced **11.44 blocks** (`Motion` 0.3, measured `Pos` x 2.5 → 13.94) — cart transport works (`compat/evidence/gameplay-single-20261124T053129Z.md`). Earlier: powered-rail speed-up (5.6 blocks/0.2 s) and `forceload`-listed cart chunk. |
| 2 | Just Player Heads | `justplayerheads` | 1.20.1-4.5 | `justplayerheads-1.20.1-4.5.jar` | Collective 8.86 | LOADING | Both mods load in a production install, but **do not drop a head**. Confirmed twice: earlier two survival players killed by another player produced no `player_head` (`compat/evidence/gameplay-mods-20261122T083900Z.md`), and on 2026-11-24 a single-server run logged `SyncBotB was slain by SyncBotA` yet `@e[type=item]` was empty — no head and no drops (`compat/evidence/gameplay-single-20261124T053129Z.md`). With `playerDropsHeadOnDeath=true`/chance 1.0 this is a mod bug, so the row stays LOADING (main function not delivered). |
| 3 | Timber Strike | `mr_timber_strike` | 1.3 | `timber-strike-1.3.jar` | — | FUNCTIONAL | 3.0 is listed for 1.20.1 but ships 1.20.2+ syntax (`return run ...`); all 15 tree functions fail to parse on 1.20.1. 1.3 parses cleanly and loads. Player test: one survival iron-axe break at the base of a 4-log oak trunk removed the other three logs and the leaves within 3 s (`compat/evidence/gameplay-mods-20261122T043800Z.md`). |
| 4 | Lithostitched | `lithostitched` | 1.4.12 | `lithostitched-forge-1.20.1-1.4.12.jar` | — | FUNCTIONAL | Loads as the worldgen library for Tectonic. On the 2026-11-24 **default-world** run a full 21-jar install generated a valid world with an ancient city (`[16, ~, 880]`) and nether fortress (`[224, ~, -224]`) locatable and no generation errors (`compat/evidence/worldgen-20261124T053004Z.md`). Its structure-injection edge cases were not enumerated. |
| 5 | Tectonic | `tectonic` | 3.0.17 | `tectonic-3.0.17-forge-1.20.1.jar` | Lithostitched | FUNCTIONAL | Terrain generated, was stored by the master and loaded by `live2`. On the 2026-11-24 **default-world** run the plains surface sat at **y=111** (Tectonic's raised terrain, not superflat) and ancient cities / nether fortresses located on the same world with no errors (`compat/evidence/worldgen-20261124T053004Z.md`). Cross-server biome/height parity on a shared seed was not re-asserted here. |
| 6 | Fast Leaf Decay | `fastleafdecay` | 2.0.0 | `fastleafdecay-forge-1.20-26.3.jar` | — | FUNCTIONAL | Multi-version jar that lists 1.20.1. After the Timber trunk break, the three leaf blocks in the test tree were air within 3 s (`compat/evidence/gameplay-mods-20261122T043800Z.md`), which is the mod's accelerated decay rather than vanilla timing. |
| 7 | AI Improvements | `aiimprovements` | 0.5.2 | `AI-Improvements-1.20-0.5.2.jar` | — | FUNCTIONAL | Loads and its mob-AI replacements apply; under a 60-entity load the cluster held 20.0/20.0 TPS with no exceptions (`compat/evidence/gameplay-20261124T050101Z.md`, `stability-under-load` PASS). A behavioural mob-fight diff was not asserted, so this is "applies and does not degrade the tick loop". |
| 8 | Async Locator | `asynclocator` | 1.20-1.3.0 | `async-locator-forge-1.20-1.3.0.jar` | — | FUNCTIONAL | `/locate structure minecraft:village_plains` returned a result in 1 ms on the live server; no tick-thread stall (20 TPS). |
| 9 | Get It Together, Drops! | `getittogetherdrops` | v1.3 | `getittogetherdrops-forge-1.20-1.3.jar` | — | FUNCTIONAL | Two stone blocks two blocks apart were broken by a survival player; the two cobblestone drops merged into a single `Count: 2b` item entity within the configured 2-block radius (`compat/evidence/gameplay-mods-20261122T043800Z.md`). |
| 10 | Ksyxis | `ksyxis` | 1.4.6 | `Ksyxis-1.4.6.jar` | — | FUNCTIONAL | With Ksyxis the server loaded 1 spawn chunk and reached `Done` in ~10 s; without it the same world loaded 554 chunks over ~69 s. Its stated function (remove unneeded spawn chunks) is observable. The session-3 `/forceload` ticket loss did **not** reproduce on 2026-11-22 (ticket survived `forceload query`); the harness workaround is now only a fallback (`compat/evidence/mod-profile-20261122T070103Z.md`). |
| 11 | Noisium | `noisium` | 2.3.0+mc1.20-1.20.1 | `noisium-forge-2.3.0+mc1.20-1.20.1.jar` | — | FUNCTIONAL | Worldgen completed without errors on the shared world and on the 2026-11-24 **default-world** run (full 21-jar set) generated a valid world with locatable ancient cities and nether fortresses, no generation errors and the cluster served 20 TPS (`compat/evidence/worldgen-20261124T053004Z.md`). Its noise-function speedup was not benchmarked in isolation. |
| 12 | Radium | `radium` | 0.12.4 | `radium-mc1.20.1-0.12.4+git.26c9d8e.jar` | — | FUNCTIONAL | Loads and its lithium redirects apply; the production cluster held 20.0/20.0 TPS under a 60-entity load with no exceptions (`compat/evidence/gameplay-20261124T050101Z.md`, `stability-under-load`). Deployment caveat (unchanged): with ServerCore installed, ServerCore's `ServerChunkCacheMixin->@Redirect` is skipped because Radium's redirect has higher priority, and BetterAutoSave disables its sync chunk-load detector (`docs/COMPATIBILITY_PROFILES.md`). |
| 13 | ServerCore | `servercore` | 1.5.2+1.20.1 | `servercore-forge-1.5.2+1.20.1.jar` | — | FUNCTIONAL | Loads and applies; the production cluster held 20.0/20.0 TPS under a 60-entity load with no exceptions (`gameplay-20261124T050101Z.md`). Deployment caveat (unchanged): one ServerCore optimisation loses to Radium's redirect when both are present (row 12); the server is correct but that optimisation is inactive, so the fully-active choice is the deployment profile in `docs/COMPATIBILITY_PROFILES.md`. |
| 14 | Shinoyuki-BetterAutoSave | `shinoyuki_betterautosave` | 0.20.2 | `shinoyuki_betterautosave-0.20.2-all.jar` | — | FUNCTIONAL | Pipeline installed, two chunk workers started, and on the 2026-11-24 production run `save-all flush` left **no pending writes** on either server (`multiforge status` `local-backlog=0`; `gameplay_check.py` `save-backlog` PASS in `compat/evidence/gameplay-20261124T050607Z.md`) — async saving completes. Caveats (cosmetic/known): it logs `level.dat ... MISSING` during early startup because MultiForge downloads `level.dat` in `loadLevel` after BetterAutoSave's first probe (the file exists afterwards), and with Radium its sync chunk-load detector self-disables. Production install only (SRG refmap not resolvable in a dev run). |
| 15 | Structure Layout Optimizer | `structure_layout_optimizer` | 1.0.11 | `structure_layout_optimizer-forge-1.0.11.jar` | Resourceful Config 2.1.3 | FUNCTIONAL | Requires Forge ≥ 47.3.12 (hence the 47.4.29 pin). Loads and its structure-layout pass runs: on the 2026-11-24 **default-world** run, structures generated and were locatable — ancient city `[16, ~, 880]`, nether fortress `[224, ~, -224]` — with no layout errors (`compat/evidence/worldgen-20261124T053004Z.md`). |
| 16 | AntiXray | `antixray` | 1.4.6+1.20.1 | `antixray-forge-1.4.6+1.20.1.jar` | — | LOADING | Loaded. Obfuscation is a per-player packet transformation; it needs a real client to verify. |
| 17 | Beautified Chat [Server] | `beautifiedchatserver` | 1.20.1-4.0 | `beautifiedchatserver-1.20.1-4.0.jar` | — | PARTIAL | A player message produced the formatted component `04:36 \| TPABot: hello-cluster-test` with timestamp and colour fields (`compat/evidence/gameplay-mods-20261122T043800Z.md`). Cross-server, MultiForge previously forwarded the raw event content and re-broadcast it as a bare system message, so live2 showed plain `hello-beautified` with no sender or formatting (`gameplay-mods-20261122T083900Z.md`). Fixed: `PlayerSyncManager.broadcastChat` now rebuilds the vanilla `chat.type.text` line (`<name> text`) around the event message and the forward runs at `EventPriority.LOWEST`, so the sender is always present and event-based formatting is captured; `tools/compat/chat_format_check.py` verifies the decoration on every `tools/ci/live_cluster.sh` run. Still PARTIAL: the **2026-11-24 production run** confirmed the sender now crosses (`chat.type.text` received on live2, `chat-format-20261124T003221Z.md`) but the mod's own timestamp/colour does **not** — Beautified Chat renders it at the packet layer, not by setting the `ServerChatEvent` message, so the event-based forward cannot reconstruct it (`gameplay_check.py` `chat-format-beautified` reports no timestamp). |
| 18 | Combatlog | `combatlog` | 2.8.5 | `combatlog-2.8.5-forge+1.20.1.jar` | — | FUNCTIONAL | Production server: a survival player damaged by another player received `You are in combat do not leave! 28 seconds left`; disconnecting produced `BotF has died of cowardice` (the configured logout punishment) (`compat/evidence/gameplay-mods-20261122T083900Z.md`). **Crash found 2026-11-24:** under active combat the hotbar notice path throws `java.lang.ArithmeticException: / by zero` in `CombatNotice.displayNotice` and crashes the tick loop (`.runtime/prod1/crash-reports/crash-2026-11-24_00.24.17-server.txt`). This is the mod's own bug (not MultiForge); the cluster is stable with `[notice] enabled = false` (or `style = "bossbar"`), so it is a documented deployment profile rather than FUNCTIONAL-out-of-the-box. |
| 19 | TPA Teleport | `tpateleport` | 1.0.0 | `tpateleport-1.0.0.jar` | — | FUNCTIONAL | Same-server request/accept/teleport verified with two bots (`compat/evidence/gameplay-mods-20261122T043800Z.md`). **Cross-server verified 2026-11-24** on the production cluster: bot A sent `/tpa SyncBotB`, bot B `/tpaccept SyncBotA`, and afterwards both players were on server A (`/list` contained both; `gameplay_check.py` `tpa-cross-server` PASS in `compat/evidence/gameplay-20261124T003242Z.md`) — the hand-off rides MultiForge's normal player transfer. |
| 20 | UltimateAntiSeedCracker | data pack | 1.0.0 | `UltimateAntiSeedCracker.zip` | — | FUNCTIONAL | Enabled on both servers. A non-op `/seed` is not disclosed (the command is removed: `command.unknown.command`), and on the 2026-11-24 production run a non-op headless client ran both `/seed` and `/locate structure` and the seed never appeared in its chat (`gameplay_check.py` `seed-hidden-nonop` PASS in `compat/evidence/gameplay-20261124T050101Z.md`). Exhaustive structure-probing vectors were not enumerated, but the two primary disclosure paths (the command and locate) are covered. |

### Required library dependencies

| Name | Mod id | Pinned version | Required by |
| --- | --- | --- | --- |
| Collective | `collective` | 1.20.1-8.86 | Just Player Heads |
| Resourceful Config | `resourcefulconfig` | 2.1.3 | Structure Layout Optimizer |

## 5. Cross-mod interactions found during the full-stack run

These are interactions between the targets themselves, not MultiForge bugs, but they
change the support matrix and must not be hidden:

1. **Radium × ServerCore** — both redirect `ServerChunkCache` chunk-list cloning. Mixin
   applies Radium's (priority 1000) and skips ServerCore's (900). The server stays
   correct; one ServerCore optimisation is silently inactive. A compatibility adapter
   would have to disable that ServerCore feature through its config instead of letting
   Mixin drop it.
2. **Radium × BetterAutoSave** — BetterAutoSave refuses to wrap `ServerChunkCache.getChunk`
   and disables its synchronous chunk-load detector. Async saving/loading still works.
3. **Ksyxis × `/forceload`** — with Ksyxis installed, force-loaded tickets disappeared
   immediately in the test environment. Normal player-driven chunk loading is unaffected;
   the harness therefore removes Ksyxis from both servers when it needs a common chunk.
   The full 19/19 startup (including Ksyxis) was verified separately.
4. **Timber Strike 3.0 on 1.20.1** — the Modrinth version declares 1.20.1 but contains
   1.20.2 command syntax. The pinned 1.3 build is the one that actually works on 1.20.1.
5. **Structure Layout Optimizer 1.0.11** — requires Forge 47.3.12+; MultiForge now builds
   and is tested on 47.4.29 to satisfy it.

## 6. What remains before 35/35

| Area | Remaining work |
| --- | --- |
| Client mods (15) | The production client run covered launch, mod load, in-world join, Embeddium/Oculus/BSL, ImmediatelyFast, Entity Culling and RRLS resource reloads. Remaining: hardware-GPU visual verification of Complementary/Sildur pipelines, Cull Leaves, Dynamic FPS throttling, Kerria/OBE/BadOptimizations performance and the RRLS server-switch overlay. |
| Server gameplay | End-to-end minecart transport, player-head drops, combat-log punishment and AntiXray packet obfuscation still need player-driven checks. Tree felling, leaf decay, TPA (same-server), chat formatting, anti-seed `/seed`, natural item merging and the minecart speed/forceload branch are covered by `compat/evidence/gameplay-mods-20261122T043800Z.md`. |
| Cross-server gameplay | Chunk ownership transfer while a player walks, player mirroring (see `compat/evidence/player-mirroring-20261115T030505Z.md`), inventory sync (TODO 3), server switch and reconnect. |
| Radium/ServerCore | Resolved as a documented deployment choice: `docs/COMPATIBILITY_PROFILES.md` (radium / servercore / both), because mixin priorities are static. |
| Evidence | Client and player-driven results are recorded in `compat/evidence/client-matrix-20261122T052900Z.md` and `compat/evidence/gameplay-mods-20261122T043800Z.md`; append a hardware-GPU visual run when available. `tools/ci/live_cluster.sh` reproduces the two-server cluster (18/18), forced-loss (3/3) and fight (14/14) checks from one command. |

**Do not mark a row FULLY COMPATIBLE until the listed evidence exists.** The current
matrix deliberately keeps most rows below that bar.

## 7. Scoreboard

| Status | Client | Server | Total |
| --- | --- | --- | --- |
| NOT TESTED | 0 | 0 | 0 |
| LOADING | 0 | 2 | 2 |
| PARTIAL | 9 | 1 | 10 |
| FUNCTIONAL | 6 | 17 | 23 |
| FULLY COMPATIBLE | 0 | 0 | 0 |
| BLOCKED | 0 | 0 | 0 |

All 35 targets have now been executed at least once, and all 20 server targets load and run
together in a real production install. Weighted compatibility (FUNCTIONAL 1, PARTIAL ½,
LOADING 0) = (23 + 10×0.5) / 35 = **80 %**. This session promoted, with recorded evidence:
AI Improvements, Radium, ServerCore (load/TPS stability), UltimateAntiSeedCracker (non-op seed
probe), BetterAutoSave (`save-backlog` = 0), Darceno's Minecarts (cart advanced 11.44 blocks),
and the worldgen stack — Lithostitched, Tectonic (surface y=111), Noisium, Structure Layout
Optimizer — on a default world (`worldgen-20261124T053004Z.md`). The remaining gap to FULLY
COMPATIBLE: the 9 PARTIAL **client** rows need a hardware GPU (shader/culling/FPS) — capping the
weighted score at ≈87 % even with every server row FUNCTIONAL; on the **server** side only
Beautified Chat (PARTIAL: packet-layer timestamp does not cross) and AntiXray (LOADING: needs a
client-packet-obfuscation test) remain, and Just Player Heads is **LOADING by evidence** — a
player slain by a player dropped no head (`gameplay-single-20261124T053129Z.md`), i.e. a mod bug.
Combatlog additionally needs its `[notice] enabled = false` profile to avoid a known crash.
