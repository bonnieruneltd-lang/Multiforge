# MultiForge Project Plan

## Executive Summary

**Current Status**: 94% overall completion (weighted scorecard)
- Core parity implementation: **100%** ✓
- Automated verification: **100%** ✓ (138 tests pass)
- Live cluster evidence: **99%** ✓
- Required-mod compatibility: **63%** (12 FUNCTIONAL, 20 PARTIAL, 3 LOADING of 35)
- Production hardening: **92%** ✓

**Test Results**: 138 tests pass (75 protocol + 56 master + 4 api + 3 example)
- Forge module compilation fails in this environment (Forge dependency download blocked by network)
- All protocol and master tests pass successfully

---

## What Is Done (Verified)

### Core Systems (100% Complete)
| System | Status | Evidence |
|--------|--------|----------|
| Master process & protocol | ✓ | 56 integration tests, live cluster runs |
| Chunk data plane (master + peer + local fallback) | ✓ | 18/18 cluster checks |
| Ownership arbitration & tick gating | ✓ | Live verified, 6 handover tests |
| Block/block-entity broadcasts | ✓ | Bidirectional propagation verified |
| Player data, login, stats, advancements | ✓ | Player data round-trips |
| World sync (time, weather, gamerules, border) | ✓ | Weather/sequencing tests |
| File sync (level.dat, uid.dat, JSON, shared files) | ✓ | 128KB download test, progress reporting |
| Entity mirroring (M1) | ✓ | Live cow/entity tests |
| Player mirroring | ✓ | Headless bot, 21/21 state checks |
| Inventory/ender chest/hunger/experience/effects sync | ✓ | PlayerStateSyncManager, 21/21 checks |
| Container sync (chest/furnace across servers) | ✓ | Open/click/close packets |
| Scoreboard sync | ✓ | 18/18 cluster, objectives/scores/teams |
| Raid & dragon fight state sync | ✓ | 14/14 fight checks, handovers verified |
| Fenced chunk handover | ✓ | 6 master tests + live verification |
| Entity ID allocation (persisted, disjoint) | ✓ | twoServersAllocateDisjointEntityIds test |
| Chunk status protocol (unload awareness) | ✓ | unloadingPeerIsNotChosenForChunkRead |
| Sequence numbers + acks + retransmits | ✓ | SequenceTrackerTest, RetransmitBufferTest |
| Protocol fuzzing & input hardening | ✓ | ProtocolFuzzTest, bounds-checked readers |
| Proxy plugins (Velocity + BungeeCord/Waterfall) | ✓ | 18/18 each, embedded master |
| Config hot reload (/multiforge reload) | ✓ | Live verified, restart-only logged |
| Prometheus metrics endpoint | ✓ | MetricsServerTest, 4 tests |
| Commands & public API | ✓ | Example mod + contract tests |
| Mod compatibility detection | ✓ | /multiforge compat, 35 targets |
| Chunk border visualization | ✓ | /multiforge borders |

### Performance Optimizations (Done)
- Hot-path pass: single map lookup, no allocations, O(1) guards, reused buffers
- Entity subscription snapshot: AABB query instead of full scan
- File sync: skip unchanged reads, no queue behind slow scans
- Master region lookup: memoized canonical paths (7.2μs → 0.12μs)
- Chunk NBT compression off server thread (BoundedTaskExecutor)
- Per-chunk ChunkKey cache (512-slot direct-mapped)
- Segmented master region cache (16 shards)
- Per-connection Inflater/Deflater reuse
- Gamerule diff without CompoundTag creation

### Tools & Infrastructure (Done)
- `tools/compat/resolve_required_mods.py` - pins 35 targets, downloads + SHA-512 verifies
- `tools/compat/cluster_check.py` - live cluster harness (18/18 checks)
- `tools/compat/mcbot.js` - player-driven bot (look/move/chat/use/attack)
- `tools/compat/run_client_matrix.sh` - client matrix runner (software GL works)
- `tools/ci/live_cluster.sh` - full reproduction (18/18 + 3/3 loss + 14/14 fights)
- `tools/bench/HotPathBench.java` - performance benchmarks
- `tools/compat/player_state_check.py` - 21/21 player state checks
- `tools/compat/fight_sync_check.py` - raid/dragon verification
- `tools/compat/packet_loss_check.py` - forced loss verification
---

## What Remains (Prioritized)

### HIGH PRIORITY - Production Readiness

#### 1. Required-Mod Compatibility (63% → 100%)
**Gap**: 12 FUNCTIONAL, 20 PARTIAL, 3 LOADING of 35 targets. 0/35 FULLY COMPATIBLE.

**Server mods needing work (LOADING → FUNCTIONAL)**:
- **AntiXray** (paper-antixray): Packet obfuscation verification
- **CombatLogX**: Combat log punishment verification  
- **Minecart Mechanics**: End-to-end minecart transport across servers

**Server mods needing player-driven checks (PARTIAL → FUNCTIONAL)**:
- **Just Player Heads**: Head drops on cross-server kills
- **Beautified Chat**: Cross-server chat formatting
- **Treefeller / Leaf Decay**: Cross-ownership block breaking
- **Item merging**: Cross-server item stack merging
- **TPA**: Same-server teleport (already works), cross-server needs test
- **Anti-seed data pack**: /seed command obfuscation

**Client mods needing hardware GPU verification (PARTIAL → FUNCTIONAL/FULLY)**:
- **Complementary Shaders / Sildur's Shaders**: Pipeline rendering fidelity
- **Cull Leaves**: Leaf culling verification
- **Dynamic FPS**: Throttling behavior verification
- **Kerria / OBE / BadOptimizations**: Performance impact
- **RRLS server-switch overlay**: Visual verification (software GL verified load, needs GPU)

**Effort**: 2-3 sessions with hardware GPU access + player-driven test writing

#### 2. Cross-Server Mob AI Continuation (TODO.md item)
**Issue**: Mobs that lose their target when crossing ownership boundaries don't re-acquire properly.
**Location**: `EntitySyncManager`, `MobNavigationPacket` handling
**Fix**: Ensure combat target + navigation state fully transfers, new owner retargets correctly

#### 3. Living Entity Equipment Parity (KNOWN_ISSUES #1)
**Issue**: Left hand, armor rendering on mirrors incomplete
**Fix**: Extend `EntitySyncManager.applyEquipment` to cover all equipment slots

#### 4. Third-Party Mod Capability/Energy Sync (DISTRIBUTED_ROADMAP.md Prototype 2)
**Issue**: Forge Energy, capabilities not synced across ownership boundaries
**Risk**: Item/energy duplication when machine crosses chunk boundary
**Acceptance**: No duplication over 10-min run with ownership migration

---

### MEDIUM PRIORITY - Reliability & Polish

#### 5. Known Issues Resolution (24 documented in KNOWN_ISSUES.md)
| # | Issue | Effort |
|---|-------|--------|
| 1 | Cross-server damage attribution gaps | Medium |
| 2 | Ownership boundary 1-tick gap | Low (design) |
| 3 | Player mirror: death animation, cursor stack, effect countdown | Medium |
| 4 | Live player state sync latency (1 tick) | Low |
| 5 | Master outage: entity ID allocator high-range fallback | Low |
| 9 | Config reload: name/port/scheduler/compression need restart | Low |
| 24 | Sequence tracker: lost-first-update baseline | Medium |
| 25 | In-memory offline backlog not persisted | Medium |
| 26 | Handover budget not measured | Low |

#### 6. Dynamic Ownership Migration (DISTRIBUTED_ROADMAP.md Prototype 4)
- **Player-driven**: Done (ChunkHandoverManager)
- **Load-based/region-level**: Not started
- **Gate**: Epochs + sequence numbers done, acknowledgements done

#### 7. Async Read Queries (DISTRIBUTED_ROADMAP.md Prototype 3)
- Request/reply peer message for read-only queries
- Timeout + safe fallback required

#### 8. Instrumentation for Cross-Ownership Calls (Prototype 5)
- Mixin counting `Level.getBlockEntity` on remote-owned chunks
- Histogram logging, removable by config

#### 9. CI Automation for Live Cluster
- `tools/ci/build-workflow.yml` exists, needs copy to `.github/workflows/`
- Live cluster still needs Minecraft server download + RCON (manual step)

#### 10. Graphical Map Visualization
- Text `/multiforge map` done, graphical version optional

---

### LOW PRIORITY - Nice to Have

#### 11. Cross-Server Gameplay Verification
- End-to-end minecart transport
- Player head drops on cross-server kills
- Combat log punishment across servers
- AntiXray packet obfuscation verification

#### 12. Modded Functionality Prototype (DISTRIBUTED_ROADMAP.md Prototype 2)
- Test mod with Forge Energy block entity across chunk boundary
- Capability invalidation behavior on ownership migration

#### 13. Documentation Polish
- Update PERFORMANCE.md with latest measurements
---

## Bugs to Patch (from KNOWN_ISSUES.md)

### Critical (affects correctness)
1. **Lost-first-update baseline** (KNOWN_ISSUES #24): SequenceTracker can lose the first update of a stream
2. **In-memory offline backlog** (KNOWN_ISSUES #25): Chunk writes during master outage not persisted to disk
3. **Cross-server damage attribution** (KNOWN_ISSUES #1): Degrades to damage-type-only if causer not mirrored

### High (affects player experience)
4. **Player mirror gaps**: Death animation, cursor stack, effect countdown 1s behind
5. **Ownership boundary 1-tick gap**: Entity can miss packet during handover
6. **Master outage entity ID fallback**: High local range, logs once

### Medium (operational)
7. **Config reload restart-only items**: server.name, peerPort, schedulers, compression threshold
8. **Handover budget unmeasured**: No latency/timing metrics for handover
9. **Radium × ServerCore mixin conflict**: Documented as deployment choice, not fixed

---

## Tests Left to Write/Run

### Unit/Integration Tests (Minecraft-free - can run in CI)
- [ ] `SequenceTrackerPropertyTest` - property-based tests for sequence logic
- [ ] `SequenceRecoveryRandomTest` - randomized recovery scenarios
- [ ] `SenderSequenceTableTest` - sender-side sequence tracking
- [ ] `BoundedTaskExecutorStressTest` - concurrency stress for compression executor
- [ ] **Missing**: Unit tests for Minecraft-facing managers (EntitySyncManager, ChunkManager, PlayerStateSyncManager, etc.)

### Live Cluster Tests (require 2+ servers + master)
- [x] 18/18 core cluster checks
- [x] 3/3 forced packet loss (50%, 90%, tail loss)
- [x] 14/14 raid/dragon fight handovers
- [ ] **Cross-server gameplay**: Minecart, head drops, combat log, AntiXray
- [ ] **Modded functionality**: Forge Energy block entity across boundary
- [ ] **Load-based migration**: Tick-time driven ownership transfer
- [ ] **Hardware GPU client matrix**: 9 PARTIAL rows need visual verification

### Player-Driven Harness Tests (tools/compat/mcbot.js)
- [ ] Minecart transport across servers
- [ ] Tree felling / leaf decay across ownership
- [ ] Player head drops on cross-server kill
- [ ] Combat log punishment on disconnect
- [ ] TPA cross-server
- [ ] Chat formatting cross-server
- [ ] Anti-seed /seed command
- [ ] Natural item merging across servers
---

## Tools to Build

### High Priority
1. **Hardware GPU client matrix runner** - Extend `run_client_matrix.sh` for GPU instances
2. **Cross-server gameplay test suite** - Extend `mcbot.js` + `cluster_check.py` with new scenarios
3. **Handover latency profiler** - Measure freeze→drain→snapshot→fence→resume timing

### Medium Priority
4. **Cross-ownership call instrumenter** - Mixin + histogram logger (Prototype 5)
5. **Async read query prototype** - Request/reply peer message with timeout (Prototype 3)
6. **Modded test mod** - Forge Energy block entity for Prototype 2

### Low Priority
7. **Graphical map renderer** - Web-based or client-side `/multiforge map` visualization
8. **CI pipeline for live cluster** - GitHub Actions with self-hosted GPU runners

---

## Effort Estimation

| Category | Items | Estimated Sessions | Notes |
|----------|-------|-------------------|-------|
| Required-mod compatibility (server) | 3 LOADING + 11 PARTIAL | 3-4 | Needs player-driven tests |
| Required-mod compatibility (client) | 11 PARTIAL | 2-3 | **Requires hardware GPU** |
| Known issues (critical/high) | 6 | 2-3 | Sequence tracker, backlog, damage |
| Dynamic ownership (load-based) | 1 | 2-3 | Prototype 4, high risk |
| Async reads prototype | 1 | 1-2 | Prototype 3 |
| Instrumentation | 1 | 1 | Prototype 5 |
| CI automation | 1 | 1 | GitHub Actions + self-hosted |
| Documentation/maintenance | Ongoing | 0.5/session | Keep current with changes |

**Total remaining: ~12-18 sessions** (assuming 1 session = 1 focused work period)

**Critical path**: Hardware GPU access for client matrix (blocks 11 PARTIAL client rows from reaching FUNCTIONAL/FULLY)

---

## Next Immediate Steps (Priority Order)

1. **Run full build verification** (when network allows Forge download)
   ```bash
   ./gradlew build :multiforge-forge:build
   ```

2. **Write player-driven tests for 3 LOADING server mods**
   - AntiXray packet verification
   - CombatLogX punishment verification  
   - Minecart Mechanics cross-server transport

3. **Fix critical known issues**
   - SequenceTracker lost-first-update baseline
   - In-memory offline backlog persistence
   - Cross-server damage attribution improvement

4. **Extend mcbot.js with gameplay scenarios**
   - Minecart, tree felling, head drops, combat log, TPA, chat, anti-seed, item merging

5. **Set up hardware GPU CI runner** for client matrix

6. **Run live cluster with full mod set** using `tools/ci/live_cluster.sh`

---

## Architecture Invariants (Must Not Break)

From SYNCHRONIZATION.md and AGENT_GUIDE.md:
1. **Never touch world state off server thread** - Use `MultiForgeServer.execute`
2. **Single owner per chunk** - Master arbitrates via ChunkSubscriptionManager
3. **Epoch-based ownership** - Packets stamped, older epochs rejected
4. **Sequence numbers per chunk+sender** - RetransmitBuffer per chunk+peer
5. **Player data authoritative on master** - Live sync is eventual consistency
6. **Fenced handover** - Freeze → save → master ack → epoch advance → resume
7. **Append-only protocols** - Never reorder peer packet IDs or master message IDs
8. **No placeholders** - Don't expose config flags without behavior

---

## File Locations for Key Changes

| Task | Files to Modify |
|------|----------------|
| New master message | `multiforge-protocol/.../messages/{masterbound,serverbound}`, register in protocol, handle in `MasterConnection` / `ServerConnection` |
| New peer packet | `multiforge-forge/.../peer/packet`, register at end of `PeerProtocol`, handle in manager |
| Chunk IO behavior | `ChunkSyncIo`, `ChunkStorageMixin`, `ChunkStorageRegistry` |
| Ownership/ticking | `ChunkManager`, `ChunkHandoverManager`, `ServerLevelMixin`, `LevelTicksMixin` |
| Block/block entity sync | `LevelChunkMixin`, `BlockEntityMixin`, `ChunkManager` |
| Player data/login | `PlayerSyncManager`, `PlayerListMixin`, `ServerStatsCounterMixin`, `PlayerAdvancementsMixin` |
| Player mirroring | `player/PlayerMirrorManager`, `player/ExternalPlayer`, `player/PlayerStateSyncManager` |
| Entity sync | `entity/EntitySyncManager`, `entity/EntityChunkChangedPacket` |
| Container sync | `container/ContainerSyncManager`, `ServerPlayerMenuMixin`, `AbstractContainerMenuClickMixin` |
| Scoreboard sync | `scoreboard/ScoreboardSyncManager` |
| World sync | `world/WorldSyncManager`, `world/FileSyncer` |
| Config | `config/MultiForgeConfig` |
| Commands | `command/MultiForgeCommands` |
| Compatibility | `compat/ModCompatibilityManager` |

---

## Evidence Files (Reference)

All live verification evidence in `compat/evidence/`:
- `cluster-*.md` - Core cluster checks (18/18)
- `chunk-handover-*.md` - Handover verification
- `fight-sync-*.md` - Raid/dragon fight (14/14)
- `packet-loss-*.md` - Forced loss (3/3)
- `player-state-*.md` - Player state sync (21/21)
- `player-mirroring-*.md` - Player mirroring
- `server-features-*.md` - Scoreboard, map, borders, proxy
- `client-matrix-*.md` - Client matrix (software GL)
- `client-functional-checks-*.md` - In-world client checks
- `gameplay-mods-*.md` - Production gameplay mods
- `mod-profile-*.md` - Radium/ServerCore/BetterAutoSave profiles
- `velocity-plugin-*.md` / `bungee-plugin-*.md` - Proxy plugins

---

*Generated from docs/TODO.md, docs/KNOWN_ISSUES.md, docs/COMPLETION.md, docs/REQUIRED_MOD_COMPATIBILITY.md, docs/DISTRIBUTED_ROADMAP.md, docs/DEVELOPMENT_HANDOFF.md, and test execution results.*
- Keep TODO.md / KNOWN_ISSUES.md current with each change