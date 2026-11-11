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
./gradlew :multiforge-master:test      # 25 integration tests
```

For changes to chunk IO, ownership or player handling also run a live cluster (DEVELOPMENT.md)
and confirm:

* both servers say `MultiForge ready` and `Master connection established`;
* `/multiforge status` shows owned chunks and a peer;
* the master storage grows (`du -sh <storage>/world`);
* no exceptions in either server log or the master log;
* a graceful stop logs `Stopping MultiForge (server stopped)` and pending writes are flushed.

## 5. Current state and next best steps

* Verified this session: clean build on Forge 47.4.29 (bumped for Structure Layout
  Optimizer 1.0.11), 21 protocol tests and 25 master integration tests pass.
* Verified live: master + two Forge 47.4.29 servers with the pinned compatibility set.
  `tools/compat/cluster_check.py` passed 13/13 checks (blocks both directions, chest
  inventory, time, gamerule, single owner, 20 TPS, save-all) with 18/19 server mods loaded.
  The full set (19/19 incl. Ksyxis) also starts; Ksyxis is removed for the shared-chunk
  check because force-loaded tickets disappear while it is installed.
* Compatibility score: 0/35 FULLY COMPATIBLE. 2 server targets FUNCTIONAL, 8 PARTIAL,
  10 LOADING, 15 client targets NOT TESTED (no GPU). Do not inflate this; the matrix and
  evidence are in `docs/REQUIRED_MOD_COMPATIBILITY.md`.
* Biggest functional gap: entity/player mirroring (TODO 1-2). Then inventory/experience
  sync (TODO 3), live user-list reload, ownership epochs.
* Chunk generation leases are implemented and integration tested; the lease is demand
  driven (the server whose player needs the chunk generates it). Choosing an idle server
  to generate for a busy one is not implemented.
* Highest-value next work for compatibility: a player-driven harness/client, because most
  server rows are waiting on gameplay interaction, not code.
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
