# Development handoff

**Written:** 2026-11-11 (session 2) · **Branch:** `main` · **Base commit:** `c50fafc`

This document is the audit and handoff for the next agent. It records what the
previous sessions built, what was verified now, what is still missing, and the
next milestones in priority order. Read it before `TODO.md` and `KNOWN_ISSUES.md`;
those two files are kept in sync with this one.

## 1. Executive summary

MultiForge is no longer "compiles but unverified": after this session the
committed tree builds from a clean checkout with the wrapper, all protocol and
master integration tests pass, and the two Forge-side subsystems that had been
silently lost (world state and file synchronization) are restored and wired into
the lifecycle.

Two defects explain why the previous handoff overstated readiness:

1. **`.gitignore` contained an unanchored `world/` rule.** Git ignores that
   pattern at any depth, so the entire Java package
   `multiforge-forge/src/main/java/com/multiforge/forge/world/` was never
   committed. `WorldSyncManager` and `FileSyncer` therefore did not exist in the
   repository even though `MultiForgeServer` imported them and the docs described
   them as complete. A clean checkout could not compile. The rule is now anchored
   (`/world/`), and both classes have been re-implemented against the 1.20.1 API
   (they are not recovered from history; no version of them was ever committed).
2. **The build could not run with the committed Gradle 8.8.** ForgeGradle
   6.0.54 calls `JavaToolchainService.launcherFor` from an MCP step and fails with
   `IllegalStateException: Project services has been closed` on 8.8. The wrapper
   is now Gradle 8.10.2, which runs the same build cleanly.

The other claims in the previous handoff were re-checked rather than trusted; the
status table below reflects the code that exists now.

## 2. Verified build and test status

Environment used for the audit: fresh container, no JDK preinstalled. Temurin
JDK 17.0.21 was installed, and the egress proxy CA was added to a copy of the
JDK trust store (`-Djavax.net.ssl.trustStore`). This is an environment detail;
the repository itself needs only JDK 17 and network access to Maven/Forge.

```bash
./gradlew build
# :multiforge-protocol:test  -> 15 tests
# :multiforge-master:test    -> 19 tests (17 inherited + 2 added this session)
# BUILD SUCCESSFUL
```

Artifacts produced by `build`:

* `multiforge-forge/build/libs/multiforge-1.0.0-alpha.1.jar` (mod, includes the
  protocol/API sources and the Mixin refmap);
* `multiforge-master/build/libs/multiforge-master-1.0.0-alpha.1.jar`;
* `:multiforge-master:fatJar` produces the runnable `-all.jar`.

The new tests are `unloadingPeerIsNotChosenForChunkRead` and
`entityIdBlocksAreDisjointAndPersisted`; both fail without the fixes described in
section 4 and pass with them.

**Live verification performed in this session:** a master plus two real Forge 1.20.1
dedicated servers (`runServer` and `runServer2`) were started against one storage root:

* both bootstrapped MultiForge and connected (`Master connection established`);
* `live1` generated the world, uploaded `level.dat`, the four user lists and its shared file;
  `live2` downloaded `level.dat` (36879 bytes) and the missing shared file before the world
  was usable;
* `live2` fetched chunks from `live1` over the peer protocol (`Chunk world 2,5 received from
  peer live2` in the first server's log) and ownership moved between them;
* editing `config/multiforge-shared.txt` on `live1` produced an immediate upload and a
  `FileContentMessage` push that `live2` wrote to disk within a second;
* both servers shut down gracefully, each logged `Stopping MultiForge (server stopped)` and
  uploaded its final `level.dat` (36884 and 36880 bytes) before closing the master channel;
* no `ERROR` entries appeared in either server log or the master log.

The live run also exposed a bug in the *new* download path: attaching `copyToAsync` after
`createInboundDataStream` lost frames that were buffered before handlers were installed, so a
downloaded file stayed at 0 bytes. The code now uses the handler-accepting factory; the live
run above is the regression evidence. A unit test for this needs a Forge runtime and is tracked
in TODO item 22 (automated live check).

## 3. What is implemented and wired

| Area | State | Where |
| --- | --- | --- |
| Master process, storage, locks, subscriptions, CLI, proxy | implemented | `multiforge-master` |
| Master protocol (86 message types, framing, data streams, compression) | implemented, unit tested | `multiforge-protocol` |
| Chunk data plane (region/entities/POI) through master + peer + local fallback | implemented, tested | `ChunkSyncIo`, master handlers |
| Chunk ownership and tick gating (chunk, entity, block/fluid ticks) | implemented, tested | `ChunkManager`, `ServerLevelMixin`, `LevelTicksMixin` |
| Block and block entity broadcasts | implemented | `LevelChunkMixin`, `BlockEntityMixin`, `ChunkManager` |
| Peer transfer of in-memory chunks | implemented | `PeerServer`, `PeerConnection`, `ChunkManager` |
| Server registry, heartbeat, TPS/player counts, cluster player list, chat | implemented | `MasterConnection`, `PlayerSyncManager` |
| Player data, stats, advancements, duplicate login | implemented | `PlayerSyncManager`, mixins |
| `level.dat` / `uid.dat` download at bootstrap, upload on change/shutdown | implemented this session | `MultiForgeServer`, `FileSyncer` |
| Shared files (`filesToSyncOnStartup`, `filesToSyncInRealTime`) | implemented this session | `FileSyncer` |
| User lists (`ops`, `whitelist`, bans) | uploaded/downloaded at start and on change; **not injected into a running `PlayerList`** | `FileSyncer` |
| Time / weather / gamerules / border / difficulty+spawn | implemented this session | `WorldSyncManager` |
| Chunk unload status used to avoid peer redirects | implemented this session | `ChunkStatusTracker`, `ReadChunkHandler` |
| Entity id block allocation, persisted in master storage | implemented this session | `RequestEntityIdBlockHandler` |
| Live entity/player mirroring between servers | **not implemented** | TODO 1-2 |
| Inventory / ender chest / experience / hunger / effects sync | **not implemented** | TODO 3 |
| Remaining ~65 peer packets (dragon, raids, mob navigation, ...) | **not implemented** | TODO 5 |

## 4. Changes made in this session

### 4.1 Repository integrity

* `.gitignore`: runtime directories are now anchored (`/world/`, `/run/`,
  `/logs/`, `/out/`). **Never add an unanchored directory pattern that can match a
  Java package.** `world/` silently dropped a whole subsystem; the same would
  happen to `player/`, `chunk/` or `network/` if such a rule were added.
* `gradle/wrapper/gradle-wrapper.properties`: Gradle 8.8 -> 8.10.2. The Forge
  module cannot be configured with 8.8 + ForgeGradle 6.0.54.

### 4.2 Restored `com.multiforge.forge.world.FileSyncer`

Mirrors upstream `MultiPaperFileSyncer` with a polling scanner instead of a
`DirectoryWatcher` dependency:

* startup: `RequestFilesToSyncMessage` -> download missing/older shared files,
  upload `filesToSyncOnStartup`/`filesToSyncInRealTime` trees, adopt or upload the
  user lists, upload `level.dat`/`uid.dat` if the master has none;
* once per second: scan real-time trees, upload changed files with immediate peer
  propagation, upload changed user lists and content-changed world files;
* inbound: `FileContentMessage` streams to a temp file and renames on the close
  marker; `DataUpdateMessage` writes atomically and suppresses echo;
* shutdown: awaited uploads of world files and changed real-time files before the
  master channel closes;
* all paths are resolved against the server directory with escape rejection,
  `filesToNotSync` prefixes are honoured, `.tmp`/`.hprof` files are skipped.

### 4.3 Restored `com.multiforge.forge.world.WorldSyncManager`

* day time: broadcast every 20 ticks, applied on receipt (upstream behaviour);
* weather: broadcast on rain/thunder transition or timer reset, timers applied
  separately (not through `setWeatherParameters`, which merges them);
* gamerules: diff of `GameRules.createTag()`, applied through a validated
  `gamerule` command with suppressed output;
* world border: diff with epsilon, applied through the `WorldBorder` setters;
* level data: difficulty and spawn broadcast as a compressed NBT subset and
  applied with `MinecraftServer.setDifficulty` / `ServerLevel.setDefaultSpawnPos`.

### 4.4 Chunk unload status

`ChunkChangedStatusHandler` now records the reporting server's status in the new
`ChunkStatusTracker`. `ReadChunkHandler` refuses to redirect a read (blocks or
entities) to a peer that reported `unloaded`/`unloading`, and serves the master's
stored copy instead. Entries are dropped on `loaded`, on last unsubscribe and on
disconnect. Covered by `unloadingPeerIsNotChosenForChunkRead`.

### 4.5 Entity id allocation

`RequestEntityIdBlockHandler` was present but wrote `lastblock.txt` to the process
working directory, could wrap around to negative ids and had no serialized
allocation. It now uses `MasterStorage`, serializes allocation, rejects exhaustion
and persists asynchronously with a shutdown flush. Covered by
`entityIdBlocksAreDisjointAndPersisted`.

### 4.6 Second development server

The `server2` ForgeGradle run did not inherit a main class, so `runServer2` failed.
It now uses `parent runs.server` and runs from `run2/`; verified live with two
servers. See DEVELOPMENT.md.

### 4.7 Data stream download ordering

`FileSyncer` originally registered an inbound stream and attached `copyToAsync`
afterwards. Frames that the master sent before the handler was attached were
replayed into no handler, leaving a zero-byte file (observed live). Downloads now
use the four-argument `createInboundDataStream(channel, id, onData, onClose)`,
which installs the handlers before replaying buffered frames.


## 5. Known bugs and limitations (see KNOWN_ISSUES.md for symptoms)

1. No live entity or player mirroring: players do not see entities/players whose
   chunk is owned by another server.
2. Live inventory, ender chest, experience, hunger and effects are not synced;
   only the player `.dat` file is.
3. User list changes are uploaded to the master but a running server keeps its
   in-memory `PlayerList` until restart.
4. Two servers can generate the same never-seen chunk concurrently; last write
   wins (no generation lock).
5. Chunks written while the master is unreachable are not re-uploaded after
   reconnect unless saved again.
6. Weather/gamerule/border conflicts are last-writer-wins with a one-second
   window; time is authoritative-per-broadcast and can jitter by a tick.
7. `debug.chunkBorders` is still a silent no-op (declared in config, unimplemented).
8. Config reload only re-bakes the snapshot; components caching values at
   construction (compression threshold, master address) need a restart.
9. `runServer2` works now; the durable way to run a production cluster is still a normal
   Forge installation with two server directories.

## 6. Architectural concerns for the next agent

* **Protocol ids are append-only.** Peer packet ids are positional in
  `PeerProtocol`; master message ids come from registration order. Add at the end
  and update NETWORKING.md.
* **Server thread only.** Mixins and managers must not touch levels from Netty or
  file threads; use `MultiForgeServer.execute`. `FileSyncer` intentionally does
  file IO off-thread and only touches concurrent maps from Netty callbacks.
* **World state is peer-to-peer, not master-owned.** Do not move it into the
  master without a migration plan; it must keep working when the master is down.
* **Level data packets are a MultiForge subset.** They are not the full
  `level.dat`; the master owns that file. Keep the two channels separate.
* **ForgeGradle version.** If you bump the wrapper again, run the full build; the
  toolchain failure described above is version-specific and easy to miss because
  it appears as a dependency-resolution error.
* **Mixin refmap.** Rebuild the Forge module after touching a mixin target;
  running an old refmap fails at class transformation.

## 7. Recommended next milestones

1. **Live verification** - done this session (two servers, level.dat/shared-file sync, peer
   chunk transfer, real-time file push, graceful shutdown). Repeat it after changing chunk,
   ownership, file or world-state code; the automated version is TODO item 22.
2. **External entity mirroring** (TODO 1). Start with the entity id blocks that
   now exist, then port `EntityUpdatePacket`, `EntityRemovePacket` and the
   spawn/despawn path one packet at a time with tests.
3. **External player mirroring** (TODO 2), including tab list presence and packet
   forwarding. It depends on the entity representation from milestone 2.
4. **Inventory/ender chest/experience sync** (TODO 3) and live user-list reload.
5. **Reliability**: chunk generation lock, reconnect re-upload queue, protocol fuzz
   tests, and a second live server in CI if the environment allows it.

## 8. Handoff notes

* Start with `docs/AGENT_GUIDE.md` (rules and where to change what), then this
  document, then the subsystem doc.
* Build commands in `docs/DEVELOPMENT.md` are current; the wrapper needs no flags
  beyond JDK 17.
* Do not trust a prior claim that a subsystem is "done"; check that the file is in
  `git ls-files`, that it is referenced from the lifecycle, and that a test or a
  live run exercises it. This session's missing package is the proof.
* Commit after each verified milestone and update `TODO.md`/`KNOWN_ISSUES.md` in
  the same commit.

