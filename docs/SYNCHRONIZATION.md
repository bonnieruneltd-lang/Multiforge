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
* **Ownership epochs.** Every block/block-entity packet carries the chunk's ownership epoch.
  The master increments the epoch on each owner change and announces it with
  `SetChunkOwnerMessage`/`ChunkSubscribersSyncMessage`; receivers drop a packet older than
  the newest epoch they know for that chunk. A delayed packet from the previous owner can
  therefore no longer overwrite the new owner's state.

## Block entities

* `LevelChunk.setBlockEntity` and `BlockEntity.setChanged` queue the block entity.
* `ChunkManager.tick()` drains the queue once per tick and broadcasts `saveWithFullMetadata()`.
* Receivers load the NBT into their instance and resend the block update to their clients.
* A forwarded container click suppresses the generic broadcast for that chunk on the
  player's server; the owner applies the slot delta and broadcasts the canonical NBT.

## Containers

A player can open a chest, furnace or other block-entity container whose chunk is owned by
another server. The window is opened where the client is connected (vanilla behaviour), and
the owner is kept authoritative through three peer packets:

* `OpenContainerPacket` — sent after `ServerPlayer.openMenu` created a menu for a block
  entity in a foreign chunk. The owner validates that it still owns the chunk, records the
  open window and replies with a fresh `BlockEntityUpdatePacket`, so a stale local copy is
  refreshed before the first click.
* `ContainerClickPacket` — `AbstractContainerMenu.clicked` is wrapped with a before/after
  snapshot. After vanilla handled the click (cursor, shift-click, drag, quick-move) the
  changed container slots are sent as `(container slot, item stack)` pairs. The owner writes
  them into its container, calls `setChanged()` and broadcasts the new NBT to every
  subscriber. Player-inventory slots are not forwarded; they are authoritative on the
  player's server and travel through `PlayerStateSyncManager`.
* `ContainerClosePacket` — sent from `ServerPlayer.doCloseContainer`; the owner drops the
  open-window record so a late click is ignored.

While a forwarded click runs, the player's server skips the generic block-entity broadcast
for that chunk. Only the owner's resulting broadcast is applied by peers, which avoids two
servers racing to write the same NBT. Double chests are reconstructed on the owner from the
block state (`ChestBlock.getContainer`), so slot indices above 26 address the correct half.
The design is last-writer-wins for the container slots; the player's inventory remains
single-owner.

## Entities

Implemented:

* Entity storage files (`<world>/entities/r.x.z.mca`) are synchronized through the same
  chunk data plane, so entity data is durable cluster-wide.
* Entity subscriptions (`SubscribeEntitiesMessage`) track which servers hold a chunk's
  entities; the master keeps the subscriber sets consistent on join/leave/crash.
* Entity simulation is gated by chunk ownership (`ServerLevel.tickNonPassenger`), so only
  one server runs AI/physics for a given entity.
* **Live mirroring (M1).** Every entity that joins a level gets an id from the block the
  master reserved for this server, so ids are unique cluster-wide. The owner forwards the
  clientbound packets it broadcasts for an entity (`ChunkMap.TrackedEntity.broadcast`) to
  the servers subscribed to that chunk's entities: movement/teleport/head rotation/velocity,
  metadata, attributes, equipment, animations, entity events and damage events
  (`EntityUpdatePacket`), passenger/leash/pickup links
  (`EntityUpdateWithDependenciesPacket`) and removals (`EntityRemovePacket`). A server that
  has the chunk but not the entity gets an NBT snapshot (`EntityUpdateNBTPacket`) when the
  entity spawns, when a new server subscribes, and when a server gains ownership of the chunk
  (the ownership-boundary resync); the copy is added to the level but is not ticked because
  the chunk belongs to another server. A peer's `EntityChunkChangedPacket` makes the owning
  server re-send the chunk's snapshots, so the previous log-only stub is gone. Position and synched data are applied
  on the mirror, so its tracker sends the changes to its own players.
* Damage on a mirrored entity is forwarded to the owner (`EntityHurtPacket`), so a mob can
  be hit from any server and its health stays authoritative on the owner. The packet carries
  the damage type, amount, causing/direct entity UUIDs and source position; the owner
  resolves them and builds a real `DamageSource`, so death messages and kill credit name an
  attacker who is connected to a different server (the mirror copy is a real `ServerPlayer`
  there). Unknown entities degrade to damage-type-only attribution.
* **Player mirroring.** A player is represented on the other servers by an
  `ExternalPlayer` (a `ServerPlayer` with a sink connection and empty `tick`/`doTick`). The
  owning server sends `PlayerCreatePacket` (profile, world, position, rotation, game mode and
  `saveWithoutId` data) when the player joins, respawns, changes dimension, or when a peer
  subscribes to their chunk; the same `EntityUpdatePacket` stream then carries movement,
  metadata and equipment. The mirror copy is added to the vanilla `PlayerList` and level, so
  `/list` and the tab list include it and the tracker sends `ClientboundAddPlayerPacket` and
  movement packets to local clients. `PlayerRemovePacket` removes the copy on logout; a peer
  disconnect removes everything that came from that server, and a reconnect replays the
  local player list to the peer. A hit on the copy is forwarded from `ServerPlayer.hurt`
  before local invulnerability checks.

* **Live state.** The owning server keeps the mirror's inventory, armor, off hand, ender
  chest, selected hotbar slot, hunger, experience and status effects in sync. `PlayerStateSyncManager`
  diffs every local player against its last transmitted snapshot once per tick and broadcasts
  `PlayerInventoryUpdatePacket` (one slot), `PlayerSelectedSlotPacket`, `PlayerFoodUpdatePacket`,
  `PlayerExperiencePacket` and `PlayerEffectsUpdatePacket`. A newly created mirror already has
  the full state, because `PlayerCreatePacket` carries `saveWithoutId` (inventory, ender chest,
  hunger, experience, effects); only the deltas after that are sent. Effect duration is sent
  when an effect appears or is extended, not while it counts down, because the mirror never
  ticks the effect. When no peer connection is live the snapshots are dropped; the next
  handshake re-announces every local player with a full create packet. The receiving side
  applies changes only to an `ExternalPlayer`; a packet that names a locally hosted player is
  ignored (the local player is authoritative). Inventory changes also re-send the mirror's
  equipment to local clients.

Not implemented yet: a full NBT resync when an entity crosses an ownership boundary,
mob navigation handover, raids, the dragon fight and projectile ownership beyond the
attacker/direct UUIDs above. See [TODO.md](TODO.md) and [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

## Players

* **Mirroring**: every server with the player's chunk loaded keeps an `ExternalPlayer` copy
  (see the entity section). The copy is never ticked; its state comes from the owner's
  packets. `PlayerListMixin.save` refuses to write a mirror copy, so player files and
  statistics can only be uploaded by the server the player is connected to.
* **Login**: `PlayerList.placeNewPlayer` claims the player at the master; a rejection
  disconnects the duplicate connection. The claim is released on logout and automatically
  on disconnect (master side).
* **Data**: `PlayerList.load` is replaced by the master's `playerdata/<uuid>.dat` when it
  exists; `PlayerList.save` first uploads the current data (vanilla still writes the local
  file as a backup).
* **Statistics/advancements**: fetched into the local files before vanilla constructs
  `ServerStatsCounter`/`PlayerAdvancements` (`PlayerList.getPlayerForLogin` hook), uploaded
  after each `save()`.
* **Live state**: inventory, ender chest, hotbar selection, hunger, experience and effects
  are diffed once per tick by `PlayerStateSyncManager` and applied to the mirror (see the
  "Live state" bullet in the entity section for the packets and the resync policy).
* **Registry**: the master broadcasts `PlayerUpdateMessage` on login/logout; servers keep a
  UUID -> (name, server) map used by the API, commands and chat.
* **User lists (whitelist/ops/bans)**: `FileSyncer` uploads a changed list immediately when
  `StoredUserList.add/remove` runs (`StoredUserListMixin`) and also on its one-second scan.
  `WriteJsonHandler` broadcasts the new content to the other servers as
  `DataUpdateMessage`; `FileSyncer.onDataUpdate` writes the file and calls
  `PlayerSyncManager.reloadUserList`, which reloads the affected `StoredUserList`, re-sends
  op permission levels, and disconnects players that are no longer whitelisted or are now
  banned (user or IP). No restart is needed.
* **Chat**: `ServerChatEvent` is forwarded to peers as JSON and shown locally on every
  server (MultiPaper's cluster-wide `broadcastMessage`).

## Scoreboards, statistics and advancements

* **Main scoreboard.** `PlayerList.broadcastAll` is hooked by
  `PlayerListBroadcastMixin`. When vanilla broadcasts one of the four clientbound scoreboard
  packets (`SetObjective`, `SetScore`, `SetDisplayObjective`, `SetPlayerTeam`),
  `ScoreboardSyncManager` serializes it (wire id plus body) into
  `ScoreboardUpdatePacket` (peer id 30) and the receiving server replays the mutation through
  the vanilla `ServerScoreboard` API. The packet also carries the objective criteria for a
  new objective, because the clientbound packet does not. Only the main scoreboard is
  synchronized; per-player sidebars, boss bars and modded scoreboards are not world state.
  A thread-local `APPLYING` flag suppresses echo, and a replayed mutation still broadcasts to
  the local clients.
* **Statistics.** `StatsCounterIncrementMixin` records every increase at
  `StatsCounter.increment`; `PlayerProgressSyncManager` accumulates deltas per player and
  sends `PlayerStatsIncreasePacket` (id 31) every ten seconds. The receiver adds the delta to
  its own `StatsCounter` with saturation, using registry ids for the type and value so modded
  statistics transfer when both servers know them.
* **Advancements.** `PlayerAdvancementsAwardMixin` reports a criterion only when
  `award` returned `true`; the owning server sends `AdvancementProgressPacket` (id 32)
  immediately. The receiver awards the same criterion on a locally hosted player under an
  echo guard. Both features close the handover window (a player transferring before the next
  player-data save); the files on the master remain authoritative at save/load time.

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
