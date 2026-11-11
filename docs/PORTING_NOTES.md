# Porting notes: MultiPaper -> MultiForge

This document records the study of the original project, the porting roadmap and every
architectural decision future agents need to understand. It is the reference for "why is it
done this way?".

## 1. What the original MultiPaper consists of

The upstream repository (`MultiPaper/MultiPaper`, `1.20.1` branch) is not a plugin. It is a
Purpur fork whose functionality lives in three places:

| Part | Location upstream | Size |
| --- | --- | --- |
| Master process | `MultiPaper-Master` (Java, Netty, no Minecraft) | 61 classes: 41 message handlers, `ServerConnection`, chunk/entity lock and subscription managers, region file cache/writer, file locker, proxy, CLI |
| Master protocol | `MultiPaper-MasterMessagingProtocol` | 86 classes: message base, length/compression codecs, data streams, ~70 messages in two directions |
| Server patches | `patches/server/0001..0150` (applied to Purpur) | ~150 patches, 126 new files under `puregero/multipaper`, plus hundreds of hunks inside Paper/Minecraft classes |
| API | `patches/api` | Bukkit-facing API (`isLocalPlayer`, `isLocalChunk`, `getAllOnlinePlayers`, notifications) |

### How it works

* Every game server connects to the **master** and announces a **peer port**.
* `MultiPaperIO` replaces Paper's `RegionFileIOThread`: chunk reads/writes go to the master
  (`ReadChunkMessage`/`WriteChunkMessage`) which stores region files.
* `MultiPaperChunkHandler` subscribes loaded chunks, requests ownership and gates ticking
  (`MultiPaper.isChunkLocal`).
* `ExternalServerConnection` + `externalserverprotocol` (81 packet classes) implement
  peer-to-peer transfer, block/entity/player updates and cross-server interactions.
* `MultiPaperEntitiesHandler`, `MultiPaperPlayerHandler`, `MultiPaperInventoryHandler`,
  `MultiPaperFileSyncer`, `MultiPaperWorldBorderHandler`, ... implement the feature list.
* The master arbitrates chunk locks (`ChunkSubscriptionManager`), entity subscribers
  (`EntitiesSubscriptionManager`), in-flight chunk writes (`ChunkLockManager`), player logins
  (`PlayerConnectHandler`) and stores level/player/stat/advancement/JSON/shared files.

## 2. Porting roadmap (with status)

| Phase | Work | Status |
| --- | --- | --- |
| 1 | Study MultiPaper, extract patch sources, inventory Paper dependencies | done |
| 2 | Gradle multi-module foundation, Java 17, Forge 47.3.0, config, logging | done |
| 3 | Port master protocol; port master process (storage, locks, subscriptions, proxy) | done |
| 4a | Networking in the mod: master connection, reconnect, heartbeats, cluster view | done |
| 4b | Peer protocol, peer server/client, compression, chunk transfer | done |
| 5a | Chunk data plane (region/entities/poi) through master + peer + local fallback | done |
| 5b | Ownership arbitration and tick gating (chunk, entity, block/fluid ticks) | done |
| 5c | Block and block entity broadcasts | done |
| 5d | Player data, duplicate login, stats, advancements, cluster player list, chat | done |
| 5e | World state (level.dat, uid, time, weather, gamerules, border), file sync | done |
| 6 | Commands, public API, documentation | done |
| 7 | Tests: protocol, master integration over real sockets, live two-server cluster | done |
| 8 | External entity/player mirroring, inventory/ender chest/experience, dragon/raids | **not ported** (TODO.md) |

## 3. Paper-specific code replaced by Forge equivalents

| MultiPaper / Paper | MultiForge / Forge | Why |
| --- | --- | --- |
| `RegionFileIOThread` patches (`MultiPaperIO`) | `ChunkStorageMixin` on `ChunkStorage.read/write` | In 1.20.1 all chunk IO (blocks, entities, POI) funnels through `ChunkStorage`; `ChunkMap` extends it, so two injections cover the whole IO path. |
| `NewChunkHolder` / `ChunkEntitySlices` chunk system | `LevelChunk`, `ChunkMap`, `ServerChunkCache` | Forge has no Paper chunk system; vanilla `LevelChunk` is the unit of subscription and ticking. |
| `MultiPaperChunkHandler.shouldTick` + tick patches | `ServerLevelMixin.tickChunk` + `ChunkManager.shouldTick` | Same decision point: only the owner ticks the chunk. |
| Paper entity tick patches | `ServerLevelMixin.tickNonPassenger` | Entities are gated by their chunk's ownership. |
| Paper block tick patches | `LevelTicksMixin` (redirect of the scheduled-tick consumer) | Redstone/hoppers execute from `LevelTicks`; filtering there prevents duplicate simulation. |
| `Level.setBlock` / `LevelChunk.setBlockEntity` patches | `LevelChunkMixin`, `BlockEntityMixin` | Closest stable hook; the "chunk is registered" check filters worldgen/deserialization. |
| Starlight light nibble patches | not ported: light travels inside the chunk NBT | Starlight is Paper-specific; vanilla light is synchronized with the chunk payload. |
| `CraftServer` / `Bukkit` world and player APIs | `MinecraftServer`, `ServerLevel`, `PlayerList` | Direct server-level equivalents. |
| `Bukkit.broadcastMessage` across servers | `ServerChatEvent` -> `ChatMessagePacket` -> `PlayerList.broadcastSystemMessage` | Forge has no cluster broadcast. |
| `PlayerJoinEvent`/`QuitEvent` handling | `PlayerList.placeNewPlayer` claim + player registry + logout manager | The claim must happen before the player is added. |
| `multipaper.yml` (`ConfigurationPart`) | `ForgeConfigSpec` (`SERVER`) + startup TOML reader | Forge owns config loading, but MultiForge starts before it. |
| BungeeCord/Velocity embedding of the master | standalone master only | Forge has no proxy plugin API; the master ships the same TCP proxy. |
| `MultiPaperConnection` (`@Sharable`) | `MasterConnection` (`@Sharable`, required for reconnect) | Reattaching a channel handler requires it. |
| Paper `RegionFile` | ported unchanged into `com.multiforge.master.util` | No Minecraft dependency, battle tested. |
| `jo-nbt` transient entity merge | same dependency in the master | Needed server-side. |
| Bukkit commands (`/servers`, `/slist`, `/mpdebug`) | `/multiforge ...` Brigadier commands | Forge command API. |
| Bukkit API module | `multiforge-api` + `MultiForgeApiImpl` | Stable API for mods, no Minecraft types. |

## 4. Architectural differences that matter

1. **No Paper chunk system.** Ticking, entity tracking and chunk status are vanilla concepts.
   MultiForge gates three explicit points (`tickChunk`, `tickNonPassenger`, `LevelTicks`)
   instead of patching Paper's chunk holder.
2. **Server startup order.** Forge fires `ServerAboutToStartEvent` after `initServer()` created
   the levels, so `level.dat` must already be on disk. MultiForge hooks
   `MinecraftServer.loadLevel` (HEAD) instead of an event.
3. **Config timing.** Forge loads the `SERVER` config after `loadLevel` starts. Startup code
   reads `config/multiforge-server.toml` with a small parser, then Forge loads the same file
   and `bake()` replaces the snapshot.
4. **Module layer.** Forge loads mods in isolated modules; plain `implementation project(...)`
   dependencies are not visible at runtime in dev (`NoClassDefFoundError`). The protocol and
   API sources are compiled into the mod jar (see ARCHITECTURE.md).
5. **Mixin instead of patches.** Mixin 0.8.5 cannot inject at constructor HEAD; statistics and
   advancement prefetch therefore happens in `PlayerList.getPlayerForLogin`, before the
   `ServerPlayer` constructor.
6. **Peer protocol scope.** MultiPaper's 81 peer packets encode Paper-specific object graphs
   (raids, dragon fight, mob navigation). MultiForge implements the 15 packets needed by the
   ported systems; the rest is additive (ids are append-only).
7. **Explicit fallbacks.** MultiPaper assumes the master is always available. MultiForge adds
   local-file fallbacks so a cluster degrades instead of corrupting data.

## 5. Deliberate deviations and why

* `ServerInfoUpdateMessage` carries a player count, `ServerStartedMessage` carries the server
  name and `PlayerUpdateMessage` is new. Forge has no Bukkit API exposing cluster state, so
  the master pushes it.
* `MasterStorage` resolves every master path against a configurable root
  (`-Dmultiforge.master.storage`). MultiPaper used the process working directory, which made
  tests and containers awkward.
* `DataStreamManager` buffers frames for unknown streams and replays them under the stream
  monitor instead of retrying on a timer. Ordering is guaranteed; a close marker cannot
  overtake data (this caused truncated downloads before it was fixed).
* `OutboundDataStream.send` copies the slice it sends. The original reused the caller's buffer,
  which is racy when Netty encodes on another thread.
* `MessageHandler` ignores reply-only messages without a callback (duplicate replies after a
  reconnect).
* The master's shutdown wait is bounded to 10 seconds instead of one hour so a stuck file task
  cannot keep the port occupied.
* The master replays `ServerStartedMessage` to newly connected servers, otherwise a server that
  starts later never learns the addresses of existing peers.
* `WorldDirectories.inferStore` reconstructs the world name from a storage path because POI
  storages exist before `LevelEvent.Load` registers the dimensions.

## 6. Verification performed

* 21 protocol unit tests: framing, varints, representative message round-trips, transaction
  ids, large payloads, unknown id rejection, truncated frames, oversized frame rejection and
  data streams (300 KB multi-frame transfer, buffering of frames that arrive before the stream
  is registered).
* 25 master integration tests against a real master over TCP: ownership arbitration,
  handover, generation leases,
  concurrent locks, disconnect recovery, subscriber sync, chunk read/write, read redirection to
  a peer, forced ownership, duplicate login, player broadcasts, player data, file
  upload/download (128 KiB), JSON/level/uid round-trip, data storage, the secret handshake,
  chunk unload status, entity id blocks and immutable subscriber snapshots.
* Live cluster (previous session): one master + two Forge 1.20.1 servers loading the same
  world, acquiring ownership of different chunks, exchanging peer handshakes and chunk data,
  and flushing 11 MB of chunk data into master storage on a graceful shutdown.
* Live cluster (this session, after restoring `world/`): master + two Forge servers from
  `runServer` and `runServer2`. `live2` downloaded `level.dat` and a shared file from the
  master, fetched chunks from `live1` over the peer protocol, received a real-time file push
  within a second of the edit, and both servers uploaded their final `level.dat` during a
  graceful shutdown. No errors in either server or the master log. See DEVELOPMENT_HANDOFF.md.
* Master restart while both servers ran (previous session): both detected the disconnect,
  reconnected, the master replayed peer addresses and the servers resumed.
* Clean-checkout build after this session's fixes: `./gradlew build` with Gradle 8.10.2, 17
  protocol + 25 master tests green. See DEVELOPMENT_HANDOFF.md.
* Session 3: Forge bumped to 47.4.29 for Structure Layout Optimizer 1.0.11. Two real Forge
  dedicated servers with the pinned compatibility set; `tools/compat/cluster_check.py`
  recorded 13/13 checks (bidirectional blocks, chest inventory, time, gamerule, single owner,
  20 TPS, save-all). 20/20 required targets installed; 19/19 Forge mods load in the full
  configuration. `docs/REQUIRED_MOD_COMPATIBILITY.md` has the per-target evidence and the
  honest 0/35 fully-compatible score.
