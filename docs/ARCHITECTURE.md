# MultiForge architecture

This document describes the modules, the runtime layout and the threading model. It is
the first file to read before changing a subsystem.

## 1. Modules

| Gradle module | Purpose | Minecraft dependency |
| --- | --- | --- |
| `multiforge-protocol` | Netty framing, message registry, data streams, complete master <-> server message set, `ChunkKey` | none |
| `multiforge-api` | Stable, Minecraft-free API (`MultiForgeApi`, `ServerInfo`, `ChunkLocation`) | none |
| `multiforge-master` | Standalone coordination process: storage, locks, subscriptions, proxy, CLI | none |
| `multiforge-forge` | The Forge 1.20.1 mod: Mixins, networking, chunk/entity/player/world managers | Forge 47.3.0 |

The protocol and API modules are compiled **into the mod jar** (see
"Why the protocol sources are compiled into the mod" below). They remain separate
Gradle modules so the master and the tests can use them without a Minecraft runtime.

### Package map (`multiforge-forge`)

```
com.multiforge.forge
├── MultiForgeMod              mod entry point (config + event registration)
├── MultiForgeServer           runtime facade and lifecycle (bootstrap/tick/shutdown)
├── ForgeEventHandlers         Forge event bridge (chunks, ticks, chat, commands)
├── chunk/
│   ├── ChunkManager           ownership, subscriptions, block/entity updates, peer replies
│   ├── ChunkState             per-chunk cluster view
│   ├── ChunkSyncIo            chunk data plane (master + peer + local fallback)
│   ├── ChunkStorageRegistry   maps vanilla ChunkStorage instances to logical stores
│   └── LocalChunkStorage      fallback/backup access to vanilla region files
├── network/MasterConnection   control plane connection, replies, reconnect, cluster view
├── peer/                      peer-to-peer protocol, connections, server and manager
├── player/PlayerSyncManager   data files, duplicate login prevention, chat, registry
├── world/
│   ├── WorldSyncManager       time, weather, gamerules, world border, level data
│   └── FileSyncer             level.dat, uid.dat, JSON files, arbitrary files
├── config/MultiForgeConfig    ForgeConfigSpec + startup TOML reader + immutable Snapshot
├── command/MultiForgeCommands /multiforge commands
├── api/MultiForgeApiImpl      public API implementation
├── common/                    small value types and path helpers
└── mixin/                     nine Mixins (see below)
```

## 2. Processes and connections

* **Master** (one per cluster): listens on a TCP port (default 35353). Every game server
  keeps exactly one control connection. The master also owns the world files and can
  optionally expose a TCP load-balancing proxy.
* **Game servers** (N): one control connection to the master plus lazy peer connections to
  other servers. A peer connection is opened only when a server needs data from a peer
  (chunk hit) or when another server connects to it.

### Control plane (master <-> server)

All messages use `MessageBootstrap`: varint length framing, varint transaction id, varint
message id, payload. Request/reply pairs correlate through the transaction id. The
message catalogue is in [NETWORKING.md](NETWORKING.md).

Responsibilities of the control plane:

1. server registration (`HelloMessage`, `StartMessage`, `SetPortMessage`) and heartbeats;
2. chunk/entity ownership arbitration (`LockChunkMessage`, `UnlockChunkMessage`,
   `RequestChunkOwnershipMessage`);
3. subscription bookkeeping (`Subscribe*`, `Unsubscribe*`, `Sync*`);
4. authoritative storage access (`Read*`/`Write*` for chunks, level data, player data,
   statistics, advancements, JSON files, shared files);
5. cluster metadata (`ServerInfoUpdateMessage`, `PlayerUpdateMessage`).

### Data plane (peer <-> peer)

Chunk data is transferred directly between servers when the master reports that another
server holds a newer copy (`ChunkLoadedOnAnotherServerMessage`). The peer protocol has
its own framing with optional zlib compression, see [NETWORKING.md](NETWORKING.md).

## 3. Lifecycle

```
MinecraftServer.loadLevel (Mixin HEAD)
  └─ MultiForgeServer.bootstrap
       ├─ read config (startup TOML reader, before Forge's config event)
       ├─ start peer listener, start master connection
       ├─ wait for master, download level.dat / uid.dat
       └─ announce peer port
Forge LevelEvent.Load            -> register chunk stores, track level
Forge ChunkEvent.Load/Unload     -> subscribe/unsubscribe, ownership release
Server tick (Forge, END phase)    -> ChunkManager.tick, WorldSyncManager.tick, FileSyncer.tick
ServerStartedEvent               -> upload file set, mark ready
ServerStoppingEvent              -> beginStop: no new ownership, keep uploading
MinecraftServer.stopServer (Mixin RETURN) -> flush uploads, close sockets
```

`beginStop` deliberately keeps chunk uploads alive: the final world save happens after
`ServerStoppingEvent`, and those chunks must still reach the master.

## 4. Threading model

| Thread | Work |
| --- | --- |
| Server thread | world mutations, ownership decisions, chunk manager state, block/entity broadcasts |
| `MultiForge-Netty-*` | master and peer frame decoding, `MasterConnection` handlers |
| Netty worker (peer server/client) | peer frame decoding, chunk request/reply completion |
| `Util.backgroundExecutor` (vanilla IO) | `ChunkStorage.read/write` hooks, NBT (de)serialization |
| `MultiForge-MasterConnection` | pings, reconnect scheduling |
| `MultiForge-PeerKeepAlive` | peer heartbeat and dead connection reaping |
| Master: `MultiPaper-Netty-*` -> `MultiForge-Netty-*` | message handling, ownership arbitration |
| Master: `ForkJoinPool.commonPool` | region file IO, file sync |

Rules:

1. Never touch level, chunk or entity state from a Netty thread. Use
   `MultiForgeServer.execute(Runnable)`.
2. IO hooks (`ChunkSyncIo`) run on IO threads and may block on futures; they never block the
   server thread for more than a map lookup.
3. Login-time reads (`PlayerList.load`, `claimPlayer`, file prefetch) block the server
   thread with the configured IO timeout; that is the same trade-off MultiPaper makes.
4. Chunk ownership state is read on the server thread and written from Netty handlers
   through `execute`; the containers are concurrent for the remaining cross-thread reads
   (API, commands).

## 5. Mixins and why they exist

MultiPaper changed Paper with patches; the equivalent Forge hooks are Mixins:

| Mixin | Target | Purpose |
| --- | --- | --- |
| `ChunkStorageMixin` | `ChunkStorage.<init>/read/write` | capture storage paths, redirect chunk IO to the cluster |
| `ServerLevelMixin` | `ServerLevel.tick/tickChunk/tickNonPassenger` | publish ticking level, gate chunk and entity simulation by ownership |
| `LevelTicksMixin` | `LevelTicks.runCollectedTicks` | run scheduled block/fluid ticks only on the owner |
| `LevelChunkMixin` | `LevelChunk.setBlockState/setBlockEntity` | broadcast block and block entity changes |
| `BlockEntityMixin` | `BlockEntity.setChanged` | queue content changes (chests, signs, hoppers) |
| `MinecraftServerMixin` | `MinecraftServer.loadLevel/stopServer` | bootstrap before world data is read, flush after the final save |
| `PlayerListMixin` | `PlayerList.placeNewPlayer/load/save/getPlayerForLogin` | duplicate login prevention, player data and file prefetch |
| `ServerStatsCounterMixin` | `ServerStatsCounter.save` | upload statistics |
| `PlayerAdvancementsMixin` | `PlayerAdvancements.save` | upload advancements |

All Mixin members live in `multiforge.mixins.json`; the annotation processor generates
`multiforge.refmap.json` (official -> SRG) which is embedded in the jar.

### Why the protocol sources are compiled into the mod

Forge loads mods in a separate module layer. In development, `implementation project(...)`
dependencies end up on the JVM classpath but not in the mod's module, which caused
`NoClassDefFoundError` at runtime. Compiling the two pure-Java modules into the mod's
source set produces one self-contained jar that works identically in dev and production.

## 6. Failure behaviour

* **Master unavailable at startup**: servers fall back to local world files, keep trying to
  reconnect, and chunk IO stays local (`shouldIntercept` is false). No data is silently
  dropped.
* **Master lost while running**: chunk IO falls back to local region files (writes) and
  local reads; ownership/ticking continues locally until the master returns and
  re-subscribes everything (documented limitation).
* **Peer lost**: the chunk request returns empty and `ChunkSyncIo` falls back to the
  master's stored copy.
* **Server crash**: the master releases all its locks and subscriptions and hands
  ownership to the next requester.
* **Master crash**: the master's region storage is crash safe (region file format, write
  locks); servers reconnect automatically.

## 7. Extension points

* New master messages: add a class in `multiforge-protocol`, register it in the protocol,
  handle it in `MasterConnection` (mod) or `ServerConnection` (master).
* New peer packets: add a class in `forge/peer/packet`, register it in `PeerProtocol`
  (append only, ids are positional), handle it in the manager it belongs to.
* New synchronization areas: implement a manager with a `tick()` and register it in
  `MultiForgeServer.tick()`; document it in SYNCHRONIZATION.md.
* Third-party mods: use `MultiForgeApiProvider.api()` / `Services` or the
  `com.multiforge.api` classes bundled in the mod jar.
