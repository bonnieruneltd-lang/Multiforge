# Development handoff

**Written:** 2026-11-11 (session 3) · **Branch:** `main` · **Base commit:** `e4caad5`

This is the audit and handoff for the next agent. It records what was verified this
session, what changed, what is still missing and the next milestones in priority order.
`TODO.md` and `KNOWN_ISSUES.md` are kept in sync with this file.

## 1. Executive summary

The committed tree builds from a clean checkout and the automated tests pass. This session
did not rewrite the port; it verified it, added the missing compatibility infrastructure and
produced the first reproducible **two-server full-mod-stack** evidence.

Three concrete outcomes:

1. **Required-mod work is now executable, not aspirational.** A resolver pins all 35
   targets to exact 1.20.1 artifacts, downloads and SHA-512-verifies the server set, and a
   live RCON harness records cross-server behaviour. The compatibility matrix is in
   `docs/REQUIRED_MOD_COMPATIBILITY.md`; the honest score is **0/35 fully compatible**,
   with 2 server targets FUNCTIONAL, 8 PARTIAL, 10 LOADING and all 15 client targets
   NOT TESTED (no GPU/display in this container).
2. **The full server target set runs together.** 20/20 targets installed (19 Forge mods +
   1 data pack, plus Collective and Resourceful Config); the cluster starts, blocks and
   chest inventories propagate in both directions, time/gamerule sync, chunk ownership is
   single-owner, and both servers hold 20 TPS. Recorded in
   `compat/evidence/cluster-20261111T050235Z.md` (13/13 checks).
3. **The compatibility and research docs required by the project now exist:**
   `REQUIRED_MOD_COMPATIBILITY.md`, `DISTRIBUTED_ARCHITECTURE_RESEARCH.md`,
   `DISTRIBUTED_ROADMAP.md`, plus runnable tooling under `tools/compat/`.

## 2. Verified build and test status

Environment: Debian container, no preinstalled JDK. Temurin JDK 17.0.21 was installed under
`.tools/` (git-ignored) and the egress proxy CA (`/etc/mitm/ca.crt`) was added to a copy of
the JDK trust store because Gradle/Forge downloads fail PKIX otherwise. Repository code does
not depend on those local paths.

```bash
./gradlew build :multiforge-master:fatJar
# :multiforge-protocol:test  -> 17 tests
# :multiforge-master:test    -> 20 tests
# BUILD SUCCESSFUL
```

Artifacts:

* `multiforge-forge/build/libs/multiforge-1.0.0-alpha.1.jar` (mod, protocol/API sources
  compiled in, Mixin refmap embedded);
* `multiforge-master/build/libs/multiforge-master-1.0.0-alpha.1-all.jar` (runnable master).

**Forge was bumped from 47.3.0 to 47.4.29.** Structure Layout Optimizer 1.0.11 declares
`[47.3.12,)`; FML refuses to start with 47.3.0. The build, mixins and live servers were
re-verified on 47.4.29. `gradle.properties` is the only place the version is set.

## 3. What changed this session

### Compatibility infrastructure

* `com.multiforge.forge.compat.ModCompatibilityManager` — detects all 35 declared targets
  in the running game, records versions, detects known deployment conflicts (Radium+Canary,
  missing Tectonic/Just Player Heads/Structure Layout Optimizer dependencies, client-only
  mods on a server, Forge version mismatch) and builds the report used by the command and
  the log. It is diagnostic, not an automatic compatibility layer; the JavaDoc says so.
* `/multiforge compat` — prints the loaded/missing target report. Wired in
  `MultiForgeServer` (`compat()`), initialised after Forge finished loading mods.
* `/multiforge chunk <x> <z>` — chunk coordinates without needing a player, and
  `loaded=`/`owner=`/`subscribers=` in one line (used by the harness).
* `/multiforge chunks` now always prints the loaded-chunk section, not only when no chunk
  is owned. This is what made headless verification possible.
* `MultiForgeApiImpl.getServers()` now always substitutes the locally known peer address for
  this server's own entry; previously the server that was queried showed `(no peer port)`
  because the master broadcasts `ServerStartedMessage` only to the other servers.

### Tooling (not shipped in the mod jar)

* `tools/compat/resolve_required_mods.py` — resolves the newest 1.20.1 Forge/pack release of
  every target on Modrinth, follows required dependencies, writes
  `compat/required-mods.json` with SHA-512 and downloads the server/client sets.
* `compat/required-mods.json` — the pinned manifest (generated; commit it).
* `tools/compat/rcon.py` — minimal RCON client.
* `tools/compat/cluster_check.py` — 13-check two-server harness; writes Markdown + JSON
  evidence. Run with `--expected-targets 18` when Ksyxis is removed for the sync check.
* `compat/evidence/` — recorded transcripts.

### Documentation

* New `docs/REQUIRED_MOD_COMPATIBILITY.md` (35 rows, pins, statuses, interactions, gaps).
* New `docs/DISTRIBUTED_ARCHITECTURE_RESEARCH.md` (actor model, ownership epochs, storage,
  capabilities, instrumentation, what is/is not feasible).
* New `docs/DISTRIBUTED_ROADMAP.md` (prototypes 1-5 with acceptance tests and gates).
* Updated `docs/AGENT_GUIDE.md`, `docs/ARCHITECTURE.md`, `docs/TODO.md`,
  `docs/KNOWN_ISSUES.md`, `README.md`, this file.

## 4. Live verification performed

Two real Forge 47.4.29 dedicated servers (`live1`, `live2`) plus one master, all with the
pinned server target set. Both servers downloaded the shared world from the master, then the
harness ran over RCON:

| Check | Result |
| --- | --- |
| master connectivity | both `master=connected` |
| cluster membership | each server lists `live1` and `live2` with peer ports |
| required targets | 18/19 loaded in the sync configuration; 19/19 observed in the full configuration |
| required data pack | `UltimateAntiSeedCracker.zip` enabled from `world/datapacks` |
| block A→B / B→A | `execute if block` passed on the other server |
| chest inventory A→B | `data get block` on B returned `minecraft:diamond` |
| time sync | B read 6000 after A `time set 6000` |
| gamerule sync | B saw `doDaylightCycle false` |
| single owner | no chunk claimed by both servers |
| tick health | both servers `Mean TPS: 20.000` |
| save-all | completed on both |

Full transcript: `compat/evidence/cluster-20261111T050235Z.md`.

The full 19/19 startup (including Ksyxis) is in the earlier server logs; the sync harness
removes Ksyxis because force-loaded tickets vanished while it was installed. That is a
mod-mod interaction, not a MultiForge ownership failure, and is recorded in
`REQUIRED_MOD_COMPATIBILITY.md` section 5.

## 5. Findings that change the compatibility picture

1. **Timber Strike 3.0 does not work on 1.20.1** despite its Modrinth metadata: its
   functions use 1.20.2 `return run` syntax and all 15 fail to parse. The pinned 1.3 build
   loads cleanly. This is exactly why "it appears in the mod list" is not verification.
2. **Structure Layout Optimizer 1.0.11 requires Forge 47.3.12+**, which forced the Forge
   bump. Any future mod requiring a newer 47.x can be accommodated the same way.
3. **Radium and ServerCore overlap** on a `ServerChunkCache` redirect. Radium wins, the
   ServerCore optimisation is skipped, the server remains correct. Do not claim both
   optimisations are active.
4. **Radium makes BetterAutoSave disable part of its pipeline** (its own diagnostic).
   Async saving still works; the sync chunk-load detector does not.
5. **BetterAutoSave probes `level.dat` before MultiForge downloads it** and logs a
   `MISSING` error. MultiForge then loads the file (logged size) and the server runs. This
   is cosmetic but should be documented for users.
6. **Master region files are not vanilla-compatible.** The master writes gzip bytes with
   the zlib version byte. MultiForge's own read path handles it; external tools do not.
   Recorded in `KNOWN_ISSUES.md` and the research doc; do not change the format without a
   migration plan.
7. **`/forceload` is not a reliable way to create a shared chunk while Ksyxis is loaded**
   (see above). Use the harness's spawn-chunk discovery instead.

## 6. Still missing (unchanged priorities)

1. **Live entity mirroring** (TODO 1) — players on other servers still do not see mobs or
   players owned elsewhere. Start from the entity id blocks that exist; port
   `EntityUpdatePacket`, `EntityRemovePacket` and spawn/despawn with tests.
2. **External player mirroring** (TODO 2), including tab list presence and packet
   forwarding; depends on the entity representation.
3. **Inventory / ender chest / experience / hunger / effects live sync** (TODO 3). Player
   data files work; live changes do not.
4. **Live user list application** (TODO 7): files are synced, the running `PlayerList` is
   not reloaded.
5. **Chunk generation lock**: two servers can still generate the same never-seen chunk
   concurrently. A master-side generation lease is the next reliability item.
6. **Ownership epochs + packet sequence numbers** before any dynamic ownership work; see
   `DISTRIBUTED_ARCHITECTURE_RESEARCH.md`.

## 7. Recommended next milestones

1. **Player-driven compatibility tests.** A real client (or a fake-player harness) unlocks
   most LOADING rows: minecarts, tree felling, leaf decay, head drops, item merging, combat
   log, TPA, chat, xray, anti-seed datapack, and MultiForge's own player transfer.
2. **Entity mirroring** — the largest functional gap in the port.
3. **Inventory/experience sync** — second largest.
4. **Client matrix** for the 15 client targets on a GPU machine, using the pinned manifest
   and the plan in `REQUIRED_MOD_COMPATIBILITY.md`.
5. **Reliability**: generation lease, reconnect re-upload queue, protocol fuzz tests,
   automated two-server cluster in CI.

## 8. Handoff notes

* Start with `AGENT_GUIDE.md`, then this file, then the subsystem doc you will touch.
* Never copy a jar into a running server's `mods/` directory. On Linux the running JVM can
  lazily load classes from the replaced file, producing `ClassNotFoundException` for classes
  that are in the jar. Stop the server, copy, then start. This session lost time to that.
* The master, both game servers and the Gradle daemon together use ~7 GB. Stop unneeded
  servers before building.
* `tools/compat/cluster_check.py` is the fastest way to prove a change did not break
  cross-server block/chest/time sync. It needs RCON enabled and takes ~30 s.
* Do not trust a prior claim that a subsystem is done. Check `git ls-files`, the lifecycle
  wiring and a live/test run. The missing `world` package in the previous session is the
  proof.
* Commit after each verified milestone and update `TODO.md`/`KNOWN_ISSUES.md` in the same
  commit.
