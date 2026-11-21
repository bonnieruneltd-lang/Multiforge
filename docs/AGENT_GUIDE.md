# Agent guide

This file is for the next AI agent (or human) continuing MultiForge. Read it fully before
touching code, then read the architecture document of the subsystem you change.

## 1. Orientation (10 minutes)

1. `README.md` - what the project is, status table, quick start.
2. `docs/ARCHITECTURE.md` - modules, lifecycle, threading, mixins, packaging decision.
3. `docs/PORTING_NOTES.md` - what MultiPaper is, what was ported, every deliberate deviation.
4. `docs/NETWORKING.md` - message/packet catalogues and framing.
5. `docs/CHUNK_MANAGEMENT.md` and `docs/SYNCHRONIZATION.md` - the distributed invariants.
6. `docs/TODO.md` and `docs/KNOWN_ISSUES.md` - what is missing and what is fragile.
7. `docs/PERFORMANCE.md` - hot paths, measured optimizations, remaining opportunities.

## 2. Golden rules

1. **Never claim a feature works without evidence.** Build, run the tests, and for distributed
   behaviour run a real master plus two servers (DEVELOPMENT.md).
2. **Do not break the invariants** in SYNCHRONIZATION.md. If a change affects ownership,
   player claims or chunk data, add or extend an integration test in
   `multiforge-master/src/test/java`.
3. **Never touch world state off the server thread.** Use `MultiForgeServer.execute`. IO
   threads may block on futures; Netty threads may not touch levels.
4. **No placeholders.** If something is not implemented, put it in TODO.md and do not expose a
   config flag that pretends it works (see `debug.chunkBorders`, which still needs fixing).
5. **Document every public class and method** (JavaDoc: what/why/callers/params/returns/side
   effects/networking/threading/assumptions). Existing code is a good template.
6. **Append-only protocols.** Peer packet ids are positional; master message ids come from the
   registration order. Never reorder; add at the end and update docs/NETWORKING.md.
7. **Keep Git clean.** Commit after each verified milestone with a conventional message
   (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`) and update docs in the same commit.
8. **Never commit secrets.** Tavily/API keys belong in environment variables (`TAVILY_API_KEY`),
   never in the repository.
9. **Check `.gitignore` before adding a directory.** Runtime rules must be anchored
   (`/world/`, not `world/`); an unanchored rule silently excluded the entire
   `com.multiforge.forge.world` package from git for a whole session. After adding
   files, run `git ls-files | grep <package>` to confirm they are tracked.
10. **Use the committed wrapper.** Gradle 8.8 + ForgeGradle 6.0.54 fails with
    "Project services has been closed"; the wrapper is 8.10.2 for that reason.

## 3. Where to change what

| Task | Files |
| --- | --- |
| New master message | `multiforge-protocol/.../messages/{masterbound,serverbound}`, register in the protocol, handle in `MasterConnection` (mod) and/or `ServerConnection` (master) |
| New peer packet | `multiforge-forge/.../peer/packet`, register at the end of `PeerProtocol`, handle in `PeerConnection` or the owning manager |
| Chunk IO behaviour | `ChunkSyncIo`, `ChunkStorageMixin`, `ChunkStorageRegistry` |
| Ownership / ticking | `ChunkManager`, `ServerLevelMixin`, `LevelTicksMixin` |
| Block/block entity sync | `LevelChunkMixin`, `BlockEntityMixin`, `ChunkManager` |
| Player data / login | `PlayerSyncManager`, `PlayerListMixin`, `ServerStatsCounterMixin`, `PlayerAdvancementsMixin` |
| Player mirroring | `player/PlayerMirrorManager`, `player/ExternalPlayer`, `PlayerListAccessor`, `ServerPlayerHurtMixin`, `EntitySyncManager` |
| Main scoreboard sync | `scoreboard/ScoreboardSyncManager`, `ServerScoreboardObjectiveMixin`, `ScoreboardUpdatePacket` |
| Live stats/advancements | `player/PlayerProgressSyncManager`, `StatsCounterIncrementMixin`, `PlayerAdvancementsAwardMixin`, packets 31-32 |
| Chunk border debug view | `chunk/ChunkBorderVisualizer`, `/multiforge borders` in `MultiForgeCommands` |
| Master metrics | `master/util/MetricsServer`, `MultiForgeMaster.snapshotMetrics`, `MasterConfig` metrics keys |
| Master leader election | `master/LeaderElection`, `MultiForgeMaster.acquireLeadership`, `leader-election` config |
| Download progress | `DownloadProgressMessage`, `OutboundDataStream.copyFromAsync(InputStream, LongConsumer)`, `DownloadFileHandler`, `FileSyncer.onDownloadProgress` |
| BungeeCord/Velocity forwarding | `protocol/proxy/BungeeCordForwarding`, `forge/connect/BungeeCordSupport` + `VelocityForwardingSupport`, `BungeeHandshakeMixin`/`BungeeLoginMixin`/`VelocityLoginMixin`, `ConnectionAddressAccessor`, `server.bungeeCord` / `server.velocityModern` |
| Container window forwarding | `container/ContainerSyncManager`, `ServerPlayerMenuMixin`, `AbstractContainerMenuClickMixin`, `OpenContainerPacket`/`ContainerClickPacket`/`ContainerClosePacket` |
| Live user lists | `StoredUserListMixin`, `FileSyncer.onUserListChanged/onDataUpdate`, `PlayerSyncManager.reloadUserList`, `WriteJsonHandler` |
| Ownership epochs | `ChunkSubscriptionManager`, `SetChunkOwnerMessage`, `ChunkSubscribersSyncMessage`, `ChunkState`, `ChunkManager`, `BlockChangePacket`, `BlockEntityUpdatePacket` |
| Required-target detection / `/multiforge compat` | `compat/ModCompatibilityManager`, `MultiForgeServer.compat()` |
| Pinned compatibility targets | `tools/compat/resolve_required_mods.py`, `compat/required-mods.json`, `docs/REQUIRED_MOD_COMPATIBILITY.md` |
| Two-server verification | `tools/compat/cluster_check.py` (+ `rcon.py`), evidence in `compat/evidence/` |
| World state / files | `WorldSyncManager`, `FileSyncer` |
| Master storage | `MasterStorage`, `RegionFileCache`, `RegionFile`, handlers |
| Polling intervals / feature switches | `MultiForgeConfig`, then the consuming manager |

## 4. Verification checklist before committing

```bash
./gradlew build                        # compiles protocol, api, master, mod
./gradlew :multiforge-protocol:test    # 21 tests
./gradlew :multiforge-master:test      # 28 integration tests
```

For changes to chunk IO, ownership or player handling also run a live cluster (DEVELOPMENT.md)
and confirm:

* both servers say `MultiForge ready` and `Master connection established`;
* `/multiforge status` shows owned chunks and a peer;
* the master storage grows (`du -sh <storage>/world`);
* no exceptions in either server log or the master log;
* a graceful stop logs `Stopping MultiForge (server stopped)` and pending writes are flushed.

For player mirroring, join with a client (or `tools`-free headless bot) and confirm that
`/list` on the other server shows the player, `/data get entity <name> Pos` matches on both,
a `/damage` on the mirror reduces the owner's health, and logout removes the copy.

## 5. Current state and next best steps

* Verified this session: clean build on Forge 47.4.29, protocol tests and master
  integration/region tests pass.
* Verified live: master + two Forge 47.4.29 servers with the pinned compatibility set.
  `tools/compat/cluster_check.py` passed 13/13 checks (blocks both directions, chest
  inventory, time, gamerule, single owner, 20 TPS, save-all) with 18/19 server mods loaded.
  The full set (19/19 incl. Ksyxis) also starts; Ksyxis is removed for the shared-chunk
  check because force-loaded tickets disappear while it is installed.
* Compatibility score: no target is FULLY COMPATIBLE yet. 2 server targets FUNCTIONAL, 8
  PARTIAL, 10 LOADING, 15 client-only targets NOT TESTED. The deployment is a headless
  server cluster: client rendering targets (shaders, Embeddium/Oculus, ...) do not run on
  the server and are out of scope for server functionality, but they are not claimed as
  compatible either. Matrix and evidence: `docs/REQUIRED_MOD_COMPATIBILITY.md`.
* Entity mirroring (TODO 1) and player mirroring (TODO 2) are implemented and verified on a
  two-server cluster; player evidence is in
  `compat/evidence/player-mirroring-20261115T030505Z.md`. Live inventory, ender chest,
  hotbar, hunger, experience and effect sync (TODO 3) is implemented by
  `PlayerStateSyncManager` and verified 21/21 on a two-server cluster with a headless bot
  (`compat/evidence/player-state-20261115T054356Z.md`; harness:
  `tools/compat/player_state_check.py` + `tools/compat/mcbot.js`). Then scoreboards/boss
  bars, advancement/stat live deltas, entity boundary resync (`onRemoteEntityChunkChanged`
  is still a log-only stub), and `debug.chunkBorders` (still a no-op).
* This session added: cross-server container window forwarding
  (`ContainerSyncManager`, peer packets 27-29), master ownership epochs on
  block/block-entity packets, attacker attribution in `EntityHurtPacket`, live
  whitelist/ops/ban reload, and true-zlib master region files with a round-trip test.
  Container open/click/close, live op/whitelist/ban reload and the region bytes were
  verified on a master + two-server cluster; see
  `compat/evidence/containers-epochs-userlists-20261115T074200Z.md`.
* Performance pass done and verified on a live master + two servers (20 TPS, entity,
  block and block-entity sync, graceful stop): see `docs/PERFORMANCE.md`. Per-tick paths
  no longer allocate a world string/`ChunkPos` per call, entity forwarding has an O(1)
  "has subscribers" guard, peer writes flush once per event-loop burst, file scans skip
  unchanged files, and master region lookups no longer call `getCanonicalPath()`
  (7.2 us -> 0.12 us in `tools/bench/HotPathBench.java`).
* Chunk generation leases are implemented and integration tested; the lease is demand
  driven (the server whose player needs the chunk generates it). Choosing an idle server
  to generate for a busy one is not implemented.
* This session (6) also added the master leader lock (`leader-election`, opt-in), download
  progress reporting, `/multiforge map`, BungeeCord/Velocity legacy IP+UUID+profile
  forwarding (`server.bungeeCord`, opt-in with a firewall requirement), Velocity modern
  forwarding (`server.velocityModern` + HMAC login plugin, verified with a simulated proxy)
  and single-encode entity resyncs. **Verified live** on two headless Forge dev servers plus a master: objectives,
  scores, teams and sidebar slots, block/time sync, player mirroring, `/multiforge map`,
  forwarded BungeeCord login (IP+UUID) and two-process leader takeover. Evidence:
  `compat/evidence/server-features-20261121T065500Z.md`. Protocol/master/api suites pass
  (protocol 36, master 44, api 4, example 3). Live testing found six bugs that compile checks missed:
  the config was a Forge SERVER config (wrong directory), Forge strips the forwarded host
  before the handshake handler, vanilla does not broadcast objective add/remove, and
  `ClientboundSetScorePacket` takes `(method, objectiveName, owner, score)`.
* The config is `COMMON` at `config/multiforge-server.toml`; Forge 47.4.29 `/reload` does not
  fire config reload events, so config changes still need a restart in practice.
* It also finished the earlier non-GPU items: main-scoreboard sync (peer packet 30),
  live statistic/advancement deltas (31-32), `/multiforge borders`, the master Prometheus
  endpoint, entity ownership-boundary resyncs and protocol input hardening/fuzzing. All
  compile and the protocol/master suites pass (`ProtocolFuzzTest`, `MetricsServerTest`,
  36 protocol + 36 master tests). Forge-side features are compile-verified only; no live
  two-server run happened here, so do not describe them as live-verified. Details in
  TODO.md items 9, 11, 16, 17, 19 and KNOWN_ISSUES.md.
* Highest-value next work: (1) live-verify the session 6 forge features on a master + two
  servers, (2) extend the player-driven harness (`tools/compat/mcbot.js` +
  `player_state_check.py`) with gameplay interactions, because most server rows are waiting
  on interaction, not code. Remaining
  design/code items are config hot reload, per-chunk sequence numbers/acks, master leader
  election and the performance leftovers in PERFORMANCE.md.
* Do not restart or rewrite working subsystems. Read `DEVELOPMENT_HANDOFF.md` for the
  session audit and `KNOWN_ISSUES.md` for the interaction findings (Timber 3.0, Radium ×
  ServerCore, Ksyxis × forceload, master region compression label).

## 6. Research resources

* Upstream source: <https://github.com/MultiPaper/MultiPaper> (patches/server contains the
  server implementation; the master and protocol modules are plain Java).
* Tavily is available for research; use the `TAVILY_API_KEY` environment variable (never
  hardcode it).
* Forge 1.20.1 mappings: official (Mojang). Use `javap` against the FG `minecraft_user_repo`
  jar to confirm signatures before writing a mixin.
* Mixin 0.8.5 restrictions that already bit this project: no constructor HEAD injections, no
  reliable `@ModifyVariable` on method arguments; use `@Redirect` on the actual call site.
