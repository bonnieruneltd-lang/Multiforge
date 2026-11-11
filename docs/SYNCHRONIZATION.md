# Synchronization

This document describes every value MultiForge keeps consistent across servers, the
invariants behind it, and the failure behaviour. Read it together with
[CHUNK_MANAGEMENT.md](CHUNK_MANAGEMENT.md).

## Invariants

1. **A chunk has at most one owner.** Ownership is granted by the master on
   `LockChunkMessage` (first come, first served) and released on `UnlockChunkMessage`,
   unload or disconnect. Only the owner ticks the chunk.
2. **Every player is online on at most one server.** The master rejects a second
   `PlayerConnectMessage` for the same UUID while the first server still holds it.
3. **Chunk data is never silently lost.** If the master is unreachable, writes fall back to
   the local region file; reads fall back to the local file instead of reporting "no
   chunk" (which would regenerate terrain).
4. **The master is the only writer of world data.** Servers upload; only the master writes
   its region files (protected by `FileLocker`/`RegionFile` locks, safe for multiple
   masters sharing a volume).
5. **Remote updates never echo.** Applying a network update sets a thread-local flag that
   suppresses re-broadcasting.
6. **Ids are stable.** Peer packet ids and master message ids are append-only.
7. **Reads never target a peer that is unloading.** The master records the status each server
   reports per chunk and serves its own stored copy when the only candidate peer reported
   `unloaded`/`unloading`; a peer that reported `loaded` is redirected to as before.
8. **At most one server generates a never-seen chunk.** The master grants a generation lease
   on the first empty read (`ChunkGenerationManager`); other readers are answered from the
   generator's copy after it reports the chunk loaded, saved or subscribed. The lease is
   bounded (`generation-lease-millis`) and a waiter stops waiting after
   `generation-wait-millis`, so no chunk load can hang on a dead generator.

## Chunk data

See [CHUNK_MANAGEMENT.md](CHUNK_MANAGEMENT.md) for the full flow. Summary:

* `ChunkStorage.read/write` are intercepted by `ChunkStorageMixin`.
* Reads ask the master; if another server holds the chunk, the read is served by that peer
  from memory (`SendChunkPacket`), falling back to the master copy.
* Writes are coalesced per chunk (only the newest payload is uploaded while a write is in
  flight) and acknowledged (`BooleanMessageReply`); failures write a local backup.
* `WillSaveChunkLaterMessage` tells the master to hold reads until the write lands, so
  another server cannot read a half-updated chunk.

## Block changes

* `LevelChunk.setBlockState` broadcasts the new global block state id to all peers.
* Receivers apply `Block.stateById(id)` with `Level.setBlock(..., UPDATE_ALL)` when they
  have the chunk loaded; otherwise the change is ignored (the chunk will be read from the
  cluster when it loads).
* Only changes made after a chunk is registered with the level are broadcast, so chunk
  deserialization and world generation do not spam the network.
* Peer broadcasts do not depend on the master connection. If the master goes down, servers
  with a live peer link keep exchanging block and block entity changes; only ownership and
  subscription changes wait for the reconnect.

## Block entities

* `LevelChunk.setBlockEntity` and `BlockEntity.setChanged` queue the block entity.
* `ChunkManager.tick()` drains the queue once per tick and broadcasts `saveWithFullMetadata()`.
* Receivers load the NBT into their instance and resend the block update to their clients.

## Entities

Implemented:

* Entity storage files (`<world>/entities/r.x.z.mca`) are synchronized through the same
  chunk data plane, so entity data is durable cluster-wide.
* Entity subscriptions (`SubscribeEntitiesMessage`) track which servers hold a chunk's
  entities; the master keeps the subscriber sets consistent on join/leave/crash.
* Entity simulation is gated by chunk ownership (`ServerLevel.tickNonPassenger`), so only
  one server runs AI/physics for a given entity.

Not implemented yet: live entity packet mirroring (spawning "external" copies of entities
on servers that do not own the chunk) and the entity-specific peer packets of MultiPaper
(teleport, hurt, equipment, mob navigation, raids, ...). See [TODO.md](TODO.md).

## Players

* **Login**: `PlayerList.placeNewPlayer` claims the player at the master; a rejection
  disconnects the duplicate connection. The claim is released on logout and automatically
  on disconnect (master side).
* **Data**: `PlayerList.load` is replaced by the master's `playerdata/<uuid>.dat` when it
  exists; `PlayerList.save` first uploads the current data (vanilla still writes the local
  file as a backup).
* **Statistics/advancements**: fetched into the local files before vanilla constructs
  `ServerStatsCounter`/`PlayerAdvancements` (`PlayerList.getPlayerForLogin` hook), uploaded
  after each `save()`.
* **Registry**: the master broadcasts `PlayerUpdateMessage` on login/logout; servers keep a
  UUID -> (name, server) map used by the API, commands and chat.
* **Chat**: `ServerChatEvent` is forwarded to peers as JSON and shown locally on every
  server (MultiPaper's cluster-wide `broadcastMessage`).

## World state

`WorldSyncManager` polls once per second and broadcasts only changes:

| Value | Packet | Conflict policy |
| --- | --- | --- |
| day time | `TimeSyncPacket` | forced changes always win; periodic updates only fix a server >1s behind |
| rain/thunder + timers | `WeatherUpdatePacket` | last writer wins |
| world border | `WorldBorderUpdatePacket` | last writer wins |
| gamerules | `GameRuleUpdatePacket` | applied with a validated `/gamerule` command |
| level data (difficulty, spawn) | `LevelDataUpdatePacket` | compact NBT subset, applied through the level API |

`level.dat` itself is authoritative on the master: it is downloaded before the world is
loaded and uploaded when its content changes and on shutdown. `uid.dat` is handled the same way
so all servers share one world UUID (scoreboard/stats identity). The peer level-data packet is
deliberately a subset (difficulty and spawn); it is not a second `level.dat` channel.

Time is broadcast every second. A packet carries game time and a force flag: a periodic
update is only applied by a server that is more than a second behind, while a forced update
(command, sleep) is always applied. This mirrors upstream and prevents another server's
periodic broadcast from overwriting a `/time set`. Weather is broadcast only when the
rain/thunder state flips or a timer jumps upwards; comparing decrementing timers every second
would make every server rebroadcast forever.

## Files

`FileSyncer` mirrors MultiPaper's `MultiPaperFileSyncer` with a one-second polling scan instead
of a directory watcher:

* `level.dat` and `uid.dat`: downloaded at startup when the master has a copy, otherwise the
  local copy is uploaded; later content changes and the shutdown state are uploaded with an
  awaited acknowledgement.
* `whitelist.json`, `ops.json`, `banned-players.json`, `banned-ips.json`: uploaded to the
  master on change and adopted at startup. A running server applies them on its next start
  (no `StoredUserList` hook yet).
* `filesToSyncOnStartup`: uploaded once at startup (configs, plugin/mod jars, ...).
* `filesToSyncInRealTime`: directory trees scanned once per second and uploaded when the
  modification time changes; the master immediately pushes the new content to the other
  servers (`FileContentMessage` + data stream, written atomically).
* `filesToNotSync`: never uploaded (defaults to logs and crash reports).

Downloads use the data-stream protocol so a 100 MB file does not block a Netty thread, and the
final rename only happens on the close marker.

## Recovery

| Failure | Behaviour |
| --- | --- |
| Master connection drops | `MasterConnection.channelInactive` schedules a reconnect; chunk IO switches to local files; on reconnect every loaded chunk is re-subscribed and ownership is re-requested on the next tick. |
| Master restarts | Servers reconnect, re-subscribe and re-announce their peer port; the master rebuilds locks/subscriptions from scratch. Chunk ownership is re-acquired lazily on the next tick. |
| Server crashes | Master removes its locks/subscriptions (`unsubscribeAndUnlockAll`), broadcasts the owner change; the next requester takes over. Player claims are released so the player can rejoin. |
| Peer disappears | Chunk request future completes empty; the read falls back to the master copy. The peer entry is reaped after 30s of silence. |
| Peer connection churn | Simultaneous connections are resolved by `nanoTime`; the newest wins, the older is closed. |
| Server stops | Ownership released on unload, all subscriptions dropped, pending writes flushed before the connection closes. |

## Race conditions that are handled explicitly

* Two servers requesting ownership at the same time: the master serializes per-chunk locks
  (`ChunkLock` monitor) and only one list head exists. Covered by an integration test.
* Chunk read while a write is in flight: the master's `ChunkLockManager` holds the read until
  `WriteChunkMessage` completes.
* Concurrent reads for the same chunk on one server: `ChunkSyncIo` keeps one in-flight future
  per chunk and shares it.
* Repeated saves while an upload is in flight: the pending write is marked dirty and
  re-uploaded after the acknowledgement, so the newest data always wins.
* Data stream frames arriving before the receiver knows the stream id: buffered and replayed
  in order by `DataStreamManager`.
* Re-sent requests after reconnect produce duplicate replies: `MessageHandler` ignores replies
  without a matching callback.
