# Required mod compatibility

This document is the compatibility record for the 35 targets that MultiForge must
support. It is not a wish list: every row names the exact artifact that was pinned,
where it belongs, what was executed against it and what is still unverified.

**Current result: 0/35 FULLY COMPATIBLE; 20/20 server targets installed and started in
one two-server cluster; 15/15 client targets pinned but not testable in this container
(no display, no GPU).** Read the status column before quoting a number; the target is
35/35 but the honest state today is below it.

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

All client artifacts are pinned in `compat/required-mods.json`. None could be executed
here: the verification container has no display server and no OpenGL driver, so neither
a Forge client nor Oculus shader compilation can run. The status is therefore
**NOT TESTED**, not "compatible". The required manual matrix is in section 6.

| # | Name | Kind / mod id | Pinned version | File | Deps | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Anti Cords Leaker | resource pack | v1.1.0 | `Anti Cords Leaker.zip` | — | NOT TESTED | Load as a resource pack; verify coordinates/F3 are hidden and that MultiForge packets do not re-expose them. |
| 2 | BadOptimizations | mod, `badoptimizations` | 2.4.1 | `BadOptimizations-2.4.1-1.20.1.jar` | — | NOT TESTED | Client-only; must not be installed on servers. |
| 3 | BSL Shaders | shader pack | 10.1.8 | `BSL_v10.1.8.zip` | Oculus | NOT TESTED | Enable alone under Oculus. |
| 4 | Complementary Shaders - Reimagined | shader pack | r5.9.3 | `ComplementaryReimagined_r5.9.3.zip` | Oculus | NOT TESTED | Enable alone under Oculus. |
| 5 | Cull Leaves | mod, `cullleaves` | 4.1.1+1.20.1-forge | `cullleaves-forge-4.1.1+1.20.1.jar` | — | NOT TESTED | Client rendering only. |
| 6 | Dynamic FPS | mod, `dynamic_fps` | 3.11.4 | `dynamic-fps-3.11.4+minecraft-1.20.0-forge.jar` | — | NOT TESTED | Game version list is `[1.20, 1.20.1]`. |
| 7 | Embeddium | mod, `embeddium` | 0.3.31+mc1.20.1 | `embeddium-0.3.31+mc1.20.1.jar` | — | NOT TESTED | Base of the Oculus stack. |
| 8 | Entity Culling | mod, `entityculling` | 1.11.3 | `entityculling-forge-1.11.3-mc1.20.1.jar` | — | NOT TESTED | Verify entities mirrored/synced by MultiForge are not wrongly culled. |
| 9 | ImmediatelyFast | mod, `immediatelyfast` | 1.5.5+1.20.4-forge | `ImmediatelyFast-Forge-1.5.5+1.20.4.jar` | — | NOT TESTED | Multi-version jar; declares `[1.20, 1.20.4]`. |
| 10 | Kerria | mod, `kerria` | 1.3.2+1.20.1-forge | `Kerria-1.3.2+1.20.1-forge.jar` | — | NOT TESTED | Client rendering only. |
| 11 | Optimized Block Entities [OBE] | mod, `obe` | 1.2.1-patch.3 | `obe+1.20.1-forge-1.2.1-patch.3-srg.jar` | — | NOT TESTED | Block entity rendering; must still render MultiForge-synced block entity data. |
| 12 | Oculus | mod, `oculus` | 1.20.1-1.8.0 | `oculus-mc1.20.1-1.8.0.jar` | Embeddium | NOT TESTED | Shader engine. |
| 13 | Remove Reloading Screen | mod, `rrls` | 4.0.6.1+mc1.20.1-forge | `rrls-4.0.6.1+mc1.20.1-forge.jar` | — | NOT TESTED | Server switch is the interesting path: no stuck reload overlay. |
| 14 | Embeddium (Rubidium) Extra | mod `embeddium_extra` | 0.5.4.4+mc1.20.1-build.131 | `rubidium-extra-0.5.4.4+mc1.20.1-build.131.jar` | Embeddium | NOT TESTED | Mod id is `embeddium_extra` (verified from the jar), not `rubidium_extra`. Do not pair with Embeddium's own options mod without checking duplicate mixins. |
| 15 | Sildur's Vibrant Shaders (Lite) | shader pack | 2.02 | `Sildur's Vibrant Shaders v2.02 Lite.zip` | Oculus | NOT TESTED | Enable alone under Oculus; the Lite variant is explicitly pinned. |

### Client test plan (not executed)

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
| 1 | Darceno's Minecarts | `mr_darcenos_minecarts` | 1.3 | `darcenos-minecarts-1.3.jar` | — | LOADING | Loaded in the full cluster. Minecart speed/chunk-loading behaviour needs a player-driven test. The 1.3 `+mod` variant is pinned, not the `slower` variant. |
| 2 | Just Player Heads | `justplayerheads` | 1.20.1-4.5 | `justplayerheads-1.20.1-4.5.jar` | Collective 8.86 | LOADING | Both mods loaded; head-drop behaviour needs a player kill. |
| 3 | Timber Strike | `mr_timber_strike` | 1.3 | `timber-strike-1.3.jar` | — | PARTIAL | 3.0 is listed for 1.20.1 but ships 1.20.2+ syntax (`return run ...`); all 15 tree functions fail to parse on 1.20.1. 1.3 parses cleanly (`Failed to load function` count 0) and loads. Tree breaking still needs a player test. |
| 4 | Lithostitched | `lithostitched` | 1.4.12 | `lithostitched-forge-1.20.1-1.4.12.jar` | — | PARTIAL | Loaded; Tectonic's worldgen and `/locate structure` ran over the shared world without errors. Its structure-injection edge cases were not enumerated. |
| 5 | Tectonic | `tectonic` | 3.0.17 | `tectonic-3.0.17-forge-1.20.1.jar` | Lithostitched | PARTIAL | Terrain generated on `live1`, was stored by the master and loaded by `live2`; `/locate structure village_plains` returned `[1408, ~, -256]` on the same world. Biome/height parity across servers needs a seed-diff test. |
| 6 | Fast Leaf Decay | `fastleafdecay` | 2.0.0 | `fastleafdecay-forge-1.20-26.3.jar` | — | LOADING | Multi-version jar that lists 1.20.1. Loaded; decay needs a player or scheduled-tick test. |
| 7 | AI Improvements | `aiimprovements` | 0.5.2 | `AI-Improvements-1.20-0.5.2.jar` | — | LOADING | Loaded. AI behaviour needs a mob-based test. |
| 8 | Async Locator | `asynclocator` | 1.20-1.3.0 | `async-locator-forge-1.20-1.3.0.jar` | — | FUNCTIONAL | `/locate structure minecraft:village_plains` returned a result in 1 ms on the live server; no tick-thread stall (20 TPS). |
| 9 | Get It Together, Drops! | `getittogetherdrops` | v1.3 | `getittogetherdrops-forge-1.20-1.3.jar` | — | LOADING | Loaded. Item-merging needs a drop test. |
| 10 | Ksyxis | `ksyxis` | 1.4.6 | `Ksyxis-1.4.6.jar` | — | FUNCTIONAL | With Ksyxis the server loaded 1 spawn chunk and reached `Done` in ~10 s; without it the same world loaded 554 chunks over ~69 s. Its stated function (remove unneeded spawn chunks) is observable. Note: with Ksyxis installed `/forceload` tickets were dropped immediately in this environment, so the cluster sync harness runs with Ksyxis removed on both servers; revisit if that interaction matters for your world. |
| 11 | Noisium | `noisium` | 2.3.0+mc1.20-1.20.1 | `noisium-forge-2.3.0+mc1.20-1.20.1.jar` | — | PARTIAL | Worldgen completed without errors on the shared world; no benchmark of its noise optimisation was made. |
| 12 | Radium | `radium` | 0.12.4 | `radium-mc1.20.1-0.12.4+git.26c9d8e.jar` | — | PARTIAL | Loaded and TPS stayed at 20. Mixin conflict with ServerCore: `ServerChunkCacheMixin->@Redirect::servercore$noList` was skipped because Radium's lithium redirect has higher priority (logged once, non-fatal). Radium also makes BetterAutoSave disable its sync chunk-load detector. |
| 13 | ServerCore | `servercore` | 1.5.2+1.20.1 | `servercore-forge-1.5.2+1.20.1.jar` | — | PARTIAL | Loaded. One optimisation loses to Radium's redirect (see row 12); the server is correct but that ServerCore optimisation is inactive. No config-only fix found yet. |
| 14 | Shinoyuki-BetterAutoSave | `shinoyuki_betterautosave` | 0.20.2 | `shinoyuki_betterautosave-0.20.2-all.jar` | — | PARTIAL | Pipeline installed, two chunk workers started, `save-all flush` completed, graceful shutdown logged. It prints `level.dat ... MISSING` during early startup because MultiForge downloads `level.dat` in `loadLevel` after BetterAutoSave's first probe; the file exists afterwards. With Radium present its sync chunk-load detector is disabled (its own diagnostic). |
| 15 | Structure Layout Optimizer | `structure_layout_optimizer` | 1.0.11 | `structure_layout_optimizer-forge-1.0.11.jar` | Resourceful Config 2.1.3 | LOADING | Requires Forge ≥ 47.3.12 (hence the 47.4.29 pin). Loaded and worldgen ran; structure layout behaviour needs structure-heavy chunks. |
| 16 | AntiXray | `antixray` | 1.4.6+1.20.1 | `antixray-forge-1.4.6+1.20.1.jar` | — | LOADING | Loaded. Obfuscation is a per-player packet transformation; it needs a real client to verify. |
| 17 | Beautified Chat [Server] | `beautifiedchatserver` | 1.20.1-4.0 | `beautifiedchatserver-1.20.1-4.0.jar` | — | LOADING | Loaded. Chat formatting needs a chat message from a player; MultiForge already forwards chat through its own peer packet, so this is the first target to re-test with a client. |
| 18 | Combatlog | `combatlog` | 2.8.5 | `combatlog-2.8.5-forge+1.20.1.jar` | — | LOADING | Loaded. Needs two players and PvP to verify logout punishment on the correct server. |
| 19 | TPA Teleport | `tpateleport` | 1.0.0 | `tpateleport-1.0.0.jar` | — | LOADING | Loaded. Teleports across servers must hand the player to the target server; needs a client. |
| 20 | UltimateAntiSeedCracker | data pack | 1.0.0 | `UltimateAntiSeedCracker.zip` | — | PARTIAL | Listed by `datapack list` on the live server. Its seed-cracking protection was not exercised (`/seed` and structure probing with a client). |

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
| Client mods (15) | Run the manual client matrix on a machine with a GPU. Automate launch + log checks if a headless client with software GL is available; shader compilation still needs a real GL context. |
| Server gameplay | Player-driven tests for minecarts, tree felling, leaf decay, head drops, item merging, combat log, TPA, chat, xray and the anti-seed datapack. These all need at least one real client or a fake-player harness. |
| Cross-server gameplay | Chunk ownership transfer while a player walks, player mirroring (mobs are mirrored, see TODO 2), inventory sync (TODO 3), server switch and reconnect. |
| Radium/ServerCore | Decide on a supported combination and add a documented config profile; do not claim both optimisations active. |
| Evidence | Append the client results and the player-driven results to this file with the exact `latest.log` snippets and dates. |

**Do not mark a row FULLY COMPATIBLE until the listed evidence exists.** The current
matrix deliberately keeps most rows below that bar.

## 7. Scoreboard

| Status | Client | Server | Total |
| --- | --- | --- | --- |
| NOT TESTED | 15 | 0 | 15 |
| LOADING | 0 | 10 | 10 |
| PARTIAL | 0 | 8 | 8 |
| FUNCTIONAL | 0 | 2 | 2 |
| FULLY COMPATIBLE | 0 | 0 | 0 |
| BLOCKED | 0 | 0 | 0 |

The server-side collection is therefore *integrated* (all 20 installed together and the
cluster runs), but it is not yet *fully compatible*. The next session should attack the
player-driven server tests first: they unlock most LOADING rows without needing a GPU.
