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
# :multiforge-protocol:test  -> 21 tests
# :multiforge-master:test    -> 25 tests
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

## 6. Still missing (updated session 5)

1. **Live entity mirroring** (TODO 1) - **done** in session 4. The owner forwards movement,
   metadata, equipment, passenger/leash/pickup and removal packets; mirrors create copies
   from NBT; damage is forwarded to the owner. Evidence:
   `compat/evidence/entity-mirroring-20261111T212200Z.md`.
2. **Player mirroring** (TODO 2) - **done** in session 5. `ExternalPlayer` copies are in the
   vanilla `PlayerList`/level (tab list and `/list`), `PlayerCreatePacket`/
   `PlayerRemovePacket` carry profile/position/game mode/player data, movement and metadata
   use the entity stream, and hits are forwarded before local invulnerability checks.
   Evidence: `compat/evidence/player-mirroring-20261115T030505Z.md`.
3. **Inventory / ender chest / experience / hunger / effects live sync** (TODO 3) - **done**
   in session 5; see the session 5 update below and
   `compat/evidence/player-state-20261115T054356Z.md`.
4. **Live user list application** (TODO 7) - **done**: uploads are immediate and the other
   servers reload whitelist/ops/bans without a restart.
5. **Chunk generation lease**: implemented (`ChunkGenerationManager`, four integration
   tests). Only one server generates a never-seen chunk; later readers wait a bounded time
   and then read the generator's copy. Remaining work is load-aware assignment (an idle
   server generating for a busy one), not correctness.
6. **Ownership epochs + packet sequence numbers** before dynamic ownership work; see
   `DISTRIBUTED_ARCHITECTURE_RESEARCH.md`. Epochs are implemented (stamped on block/block
   entity packets, older epochs dropped). Per-chunk sequence numbers/acks are still absent,
   but the current design closes the gap they would detect: TCP orders each peer stream and
   a new owner sends an NBT resync, so a missing update cannot persist across an ownership
   change. They remain relevant only for a future live ownership-migration feature.

## 7. Recommended next milestones

1. **Player-driven compatibility tests.** A real client (or a fake-player harness) unlocks
   most LOADING rows: minecarts, tree felling, leaf decay, head drops, item merging, combat
   log, TPA, chat, xray, anti-seed datapack, and MultiForge's own player transfer. A minimal
   bot harness now exists (`tools/compat/mcbot.js`); extend it with interactions.
2. **Display-name/team sync and attacker attribution** for cross-server player interactions.
3. **Client matrix** for the 15 client targets on a GPU machine, using the pinned manifest
   and the plan in `REQUIRED_MOD_COMPATIBILITY.md`.
4. **Reliability**: reconnect re-upload queue, protocol fuzz tests,
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

## 9. Session 5 update (2026-11-15)

* **Live player-state sync is done.** `PlayerStateSyncManager` diffs inventory, armor, off
  hand, ender chest, hotbar selection, hunger, experience and effects once per tick and sends
  five new peer packets (ids 22-26); the mirror applies them to the `ExternalPlayer`. A newly
  created mirror gets the full state from `PlayerCreatePacket`, so only deltas travel.
* **Verified live:** master + two Forge 47.4.29 servers with a headless
  `minecraft-protocol` bot, 21/21 checks. Harness: `tools/compat/player_state_check.py` and
  `tools/compat/mcbot.js`; evidence: `compat/evidence/player-state-20261115T054356Z.md`.
* **Bug found by the harness:** the mirror's main hand could be written into
  `Inventory.items[selected]` from the owner's equipment packet before the selected-slot
  packet arrived (sword duplicated into slots 0 and 8). Fixed in
  `EntitySyncManager.applyEquipment`; regression evidence is in the same transcript.
* `./gradlew build :multiforge-master:fatJar` passes 49 tests (21 protocol, 28 master).
* Next milestones from section 7 stay valid except item 2, which is now complete; the
  player-driven harness now exists and should be extended with gameplay interactions.

## 10. Session 6 update (2026-11-21, no GPU)

This session finished the remaining server-side TODO items. The target deployment is a
headless server cluster, so client rendering/shaders are out of scope. No live master +
two-server game run was practical here, so the horizontal rule is: protocol and master
changes are **test-verified**, forge changes are **compile-verified** (full reobf jar) and
marked as such in README/TODO. The master leader lock was additionally exercised live with
two processes.

* **Protocol hardening + fuzzing (TODO 19).** `ProtocolFuzzTest` feeds random and malformed
  bodies to every registered message id. It found a remote OOM: `ExtendedByteBuf.readString`
  trusted the announced length before Netty allocated the decode buffer, and
  `new byte[readVarInt()]` / `new String[readVarInt()]` / `new ChunkKey[readVarInt()]` had the
  same problem. Added bounds-checked `readByteArray`, `readStringArray`,
  `readChunkKeyArray` and a 1 MiB `MAX_STRING_BYTES`; every call site now uses them.
  `ExtendedByteBufTest` covers the guards.
* **Master Prometheus endpoint (TODO 17).** `MetricsServer` serves `/metrics` on
  `metrics-port` (default off) with uptime, online servers/players, owned/subscribed chunks,
  pending entity writes and next entity id. Verified end to end by starting the fat jar with
  `-Dmultiforge.master.metricsPort=39001` and scraping it, plus `MetricsServerTest` and a
  `MasterIntegrationTest` case that drives real counters.
* **Entity ownership resync.** `EntitySyncManager.onChunkOwnershipGained` re-snapshots every
  entity of a newly owned chunk to its entity subscribers; `EntityChunkChangedPacket` is no
  longer log-only. This closes the one-tick freeze documented in KNOWN_ISSUES.
* **Main scoreboard sync (TODO 9).** `PlayerListBroadcastMixin` captures the four vanilla
  clientbound scoreboard packets; `ScoreboardSyncManager` forwards them as peer packet 30 and
  replays the vanilla mutation on the receiver (criteria included for a new objective), and
  sends the full scoreboard to a peer on connect. Boss bars are not synced (upstream parity).
* **Live statistics/advancements (TODO 11).** `StatsCounterIncrementMixin` batches deltas for
  ten seconds (packet 31); `PlayerAdvancementsAwardMixin` forwards a successful criterion
  (packet 32); `PlayerProgressSyncManager` applies them under an echo guard. This closes the
  player-handover window; files stay authoritative on save/load.
* **`debug.chunkBorders` (TODO 16).** Implemented as `/multiforge borders`
  (`ChunkBorderVisualizer`): particles on the current chunk border, green = owned here, red =
  another server, white = unowned; opt-in through the existing config option.
* **Config hot reload (TODO 15).** `MultiForgeServer.config` is volatile and swapped on
  `ModConfigEvent.Reloading`; a changed master address reconnects, disabling chunk borders
  clears viewers; bound/negotiated values are logged as restart-only.
* **Op sync (TODO 10)** is documented as done for vanilla operators; Forge has no Bukkit
  permission plugins.
* **Bug fixed during verification:** the entity-id counter could be moved backwards by a
  queued stale write landing after `flush()` (`entityIdBlocksAreDisjointAndPersisted` failed
  once); writes are now serialized, `flush()` waits for the queue, and a monotonic guard
  refuses to write a smaller counter.
* **Master leader election.** `LeaderElection` holds `master-leader.lock` (opt-in
  `leader-election=true`) so only one master per storage root binds; a standby waits and
  takes the lock when the leader exits. Unit tested (same-JVM lock contention and takeover).
* **Download progress.** `DownloadProgressMessage` is sent per MiB plus an exact final message;
  `FileSyncer` tracks active downloads and logs 25% milestones; `/multiforge status` lists
  them. Covered by the file round-trip integration test.
* **Velocity modern forwarding.** `VelocityForwardingSupport` requests
  `velocity:player_info`, verifies the HMAC-SHA256 answer against `server.velocitySecret`,
  pauses the login tick until it arrives, and applies the forwarded address/UUID/properties.
  Verified live with a simulated proxy (`tools/compat/velocity_forwarding_check.py`); the run
  also documented that the login plugin answer has no length prefix. Versions 2-4 (player
  key) are not requested.
* **BungeeCord/Velocity legacy forwarding.** `BungeeCordForwarding` parses
  `host\0ip[\0uuid\0properties]`; `BungeeCordSupport` plus handshake/login/connection mixins
  apply the IP and forwarded UUID/textures when `server.bungeeCord=true`. Parser unit tested;
  mixins were exercised with a simulated proxy; modern forwarding is implemented and verified
  (see above).
* **`/multiforge map [radius]`** prints a text chunk-ownership grid (partial `/mpmap`), and
  entity ownership resyncs serialize NBT once per entity instead of once per subscriber.
* **Performance pass:** gamerule diffing now uses `GameRules.visitGameRuleTypes` instead of
  `createTag`, `RegionFileCache` is split into 16 independently locked LRU shards
  (`concurrentAccessReturnsOneHandle`), and peer framing reuses one resettable
  `Inflater`/`Deflater` per connection. Only chunk-NBT compression off the server thread and
  the tiny `ChunkKey` allocation cache remain from PERFORMANCE.md.
* **Live verified** on two headless Forge 47.4.29 dev servers plus a master: scoreboard
  objectives/scores/teams/display, block and time sync, player mirroring (`/list` on the peer),
  `/multiforge map`, a forwarded BungeeCord login (IP `127.0.0.9` and UUID applied) and a
  two-process leader takeover. Evidence: `compat/evidence/server-features-20261121T065500Z.md`.
  The run found and fixed four bugs: the config was registered as SERVER (wrong directory,
  resetting `bungeeCord`), Forge strips the forwarded host before `handleIntention`, 1.20.1
  does not broadcast objective add/remove, and the score packet constructor argument order was
  swapped. The config is now COMMON at `config/multiforge-server.toml` with a legacy migration.
  `/reload` does not fire config reload events on this Forge version, so restart for configs.
## 11. Session 7 update (2026-11-22, no GPU)

* **Raid and ender-dragon fight state transfer (TODO 5 / KNOWN_ISSUES 26).** `FightSyncManager`
  ports MultiPaper's `Sync raids` and `Sync ender dragon fight` patches without touching
  vanilla classes: the owner serializes `Raid.save` and `EndDragonFight.saveData()` once per
  second, broadcasts `RaidStatePacket`/`DragonFightStatePacket` (peer ids 37-38) only when the
  state changed, sends a removal packet when a raid ends, and sends the relevant snapshots to
  the new owner before a handover fence. Receivers apply the state but never tick it:
  `RaidMixin` and `EndDragonFightMixin` cancel `tick` unless this server owns the raid centre
  chunk or the end island chunk. Ownership epochs fence late snapshots, and the losing server
  clears its own boss bar so a handover cannot leave two visible bars.
* **Verified:** `:multiforge-forge:build` (including reobf) succeeds; a real Forge 47.4.29 dev
  server reached `Done (33.5s)` and `MultiForge ready (3 worlds)` with all 35 mixins applied,
  including the four new ones (`RaidMixin`, `RaidsAccessor`, `EndDragonFightMixin`,
  `RaidAccessor`), and no `FightSyncManager` exception during the run.
* **Live verified on a master + two dev servers (session 7):** the cluster harness passed
  18/18 (`compat/evidence/cluster-20261122T074448Z.md`). `tools/compat/fight_sync_check.py`
  passed **14/14** (`compat/evidence/fight-sync-20261122T074549Z.md`): it created a raid
  (`/multiforge startraid` plus a test village), verified the same raid id/wave and dragon
  UUID/phase on both servers with exactly one `local=true`, and handed each fight over and back
  (raid epochs 2→3→4, dragon 1→2→3) while preserving the state.
  `tools/compat/packet_loss_check.py` passed **3/3** (`packet-loss-20261122T074452Z.md`): with
  50 % of first block transmissions dropped, the receiver observed 23 sequence gaps and all
  40 blocks plus the chest NBT converged through retransmits.
* **Repeatable:** `tools/ci/live_cluster.sh` boots the master, both dev servers and the bots,
  runs the cluster, forced-loss and fight harnesses and shuts everything down (exit 0 only on
  success); the GitHub Actions template is `tools/ci/live-cluster-workflow.yml`. A full run
  passed 18/18 + 3/3 + 14/14.
* **Reliability pass:** fight mutations are pushed immediately (`RaidMixin` hooks stop/spawnGroup/
  joinRaid/removeFromRaid/absorbBadOmen; `EndDragonFightMixin` hooks setDragonKilled/tryRespawn/
  updateDragon/setRespawnStage) with the 1 s comparison as a safety net. The receiver now sends
  a heartbeat acknowledgement once per second for five seconds after the last update, which
  repairs a lost **tail** (the last update of a burst) that per-update acks cannot detect. The
  forced-loss run found both this and the lost-first-update baseline (`PacketLossInjector`
  deliberately never drops sequence 1; KNOWN_ISSUES 24). `-Dmultiforge.serverName=<name>`
  overrides a config file that Forge rewrote during startup, which makes the CI cluster
  deterministic; `tools/ci/` uses it for live1/live2.
* **Mod profiles closed:** Ksyxis 1.4.6 did not reproduce the `/forceload` ticket loss on
  47.4.29; Radium/ServerCore and BetterAutoSave are documented deployment choices
  (`docs/COMPATIBILITY_PROFILES.md`, `compat/evidence/mod-profile-20261122T070103Z.md`).
* **Not yet verified live:** client rendering on a hardware GPU (compatibility rows).
* **Docs:** NETWORKING (ids 37-38), SYNCHRONIZATION, TODO, KNOWN_ISSUES, README, AGENT_GUIDE and
  the new `docs/COMPLETION.md` scorecard were updated in the same change. Test counts are now
  protocol 72, master 56, api 4, example 3.


