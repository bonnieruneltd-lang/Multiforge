# Chunk management

MultiPaper's defining feature is that each server caches the chunks its players need and
the servers cooperate so every chunk is ticked exactly once. This document explains how
that works in MultiForge.

## 1. Storage model

| Storage | Vanilla location | Master path | Class |
| --- | --- | --- | --- |
| blocks | `<world>/region/r.x.z.mca` | `<storage>/<world>/region/...` | `ChunkSyncIo` (`region`) |
| entities | `<world>/entities/r.x.z.mca` | `<storage>/<world>/entities/...` | `ChunkSyncIo` (`entities`) |
| POI | `<world>/poi/r.x.z.mca` | `<storage>/<world>/poi/...` | `ChunkSyncIo` (`poi`) |

The master stores the *deflated* chunk payload exactly as it appears inside the region
file. Servers compress/decompress with `NbtIo.writeCompressed/readCompressed`, so the
format is identical to vanilla and existing worlds can be imported by copying the folders
into the master's storage root.

### Path detection

Vanilla 1.20.1's `ChunkStorage` does not expose its directory. `ChunkStorageMixin` captures
the constructor argument, and `ChunkStorageRegistry` maps it to a `ChunkStore(world, type)`:

* `LevelEvent.Load` registers `region`, `entities` and `poi` for every `ServerLevel`;
* storages created before that (the POI manager is constructed inside `ServerLevel`) are
  resolved by `WorldDirectories.inferStore`, which walks the path and reconstructs the
  logical world name (`DIM-1` -> `_nether`, `DIM1` -> `_the_end`, custom dimensions ->
  `_<namespace>_<path>`).

## 2. Read path

```
ChunkStorage.read(pos)
  -> ChunkSyncIo.read(store, pos)                      [IO thread]
       -> another server generating? wait (bounded) -> retry the read
       -> master: ReadChunkMessage(world, type, x, z)
            DataMessageReply(data)                 -> inflate -> CompoundTag
            ChunkLoadedOnAnotherServer(peer)       -> PeerManager.requestChunk(...)
                 data non-empty                    -> use it (newest in-memory copy)
                 data empty                        -> ForceReadChunkMessage (master copy, lease-aware)
            no data and path=region               -> generation lease
                 owner                             -> empty payload (generate)
                 waiter                            -> reply deferred until the owner is ready
       on failure -> LocalChunkStorage.read (never regenerate over existing terrain)
```

Details:

* Concurrent reads of the same chunk share one future (`inFlightReads`).
* A missing chunk returns `Optional.empty()`, which is the vanilla signal for "generate".
* A read is not redirected to a peer that reported the chunk as unloading; the master's stored
  copy is served instead (`ChunkStatusTracker`).
* The read is asynchronous (`CompletableFuture`), so the IO worker is never blocked on the
  server thread.

## 3. Write path

```
ChunkStorage.write(pos, tag)
  -> ChunkSyncIo.write(store, pos, tag)
       -> optional local backup (keepLocalChunkBackup)
       -> coalesce per chunk (PendingWrite)
       -> WillSaveChunkLaterMessage            (master holds reads)
       -> WriteChunkMessage(world, type, x, z, deflated)
       -> BooleanMessageReply
            dirty?  -> upload the newer payload
            failure -> local backup + error log
```

* While an upload is in flight, further saves only replace the pending payload; the newest
  data is always the last one uploaded.
* `flush(timeout)` waits for all uploads and is called during shutdown after vanilla saved
  the world.
* Entity uploads additionally broadcast `EntityChunkChangedPacket` so peers can invalidate
  cached entity data.

## 4. Ownership and ticking

State per chunk (`ChunkState`): owner, local loaded, subscribed, entity subscribed, remote
status, subscribers, entity subscribers.

```
ServerLevel.tickChunk(chunk, randomTickSpeed)  -> shouldTick(level, chunk)
ServerLevel.tickNonPassenger(entity)           -> shouldTick(level, chunk)
LevelTicks.runCollectedTicks (block/fluid)     -> shouldTick(level, chunk)
```

`shouldTick` returns true only when `owner == this server`. Otherwise it requests ownership
(at most once per `chunkOwnershipRequestIntervalTicks` per chunk) and returns false, so the
chunk stays loaded and synchronized but is not simulated.

The master grants ownership on `LockChunkMessage` and answers with `SetChunkOwnerMessage`
(also broadcast to all subscribers). `onOwnerChanged` flips the local set; ticking starts on
the next tick.

### Releasing ownership

* `ChunkEvent.Unload` releases ownership immediately and schedules the unsubscribe after
  `chunkUnloadGraceTicks` (avoids churn when a player crosses a chunk border).
* Disconnect: the master's `ChunkSubscriptionManager.unsubscribeAndUnlockAll` releases all
  locks of that connection and broadcasts the new owner.
* `forceTakeOwnership(keys)` ports MultiPaper's "take control of neighbouring chunks before
  running redstone" behaviour (`RequestChunkOwnershipMessage`).

## 5. Subscriptions and block updates

* On load: `SubscribeChunkMessage` + `SubscribeEntitiesMessage`.
* On unload (after grace): `UnsubscribeChunkMessage` + `UnsubscribeEntitiesMessage`.
* Master notifications: `Add/RemoveChunkSubscriberMessage`, `ChunkSubscribersSyncMessage`,
  `EntitySubscribersSyncMessage`, `SetChunkOwnerMessage`.
* Block changes: `LevelChunk.setBlockState` -> `BlockChangePacket` to all peers. Receivers
  apply the state if the chunk is loaded; the `applyingRemote` thread local prevents echo.
* Block entities: queued once per tick, sent with full metadata, applied and re-sent to local
  clients.

## 6. Peer responses

`ChunkManager.respondToChunkRequest` serializes the *loaded* chunk on the server thread with
`ChunkSerializer.write(level, chunk)` and compresses it. This is why peers can serve data
newer than the master's copy: it is the in-memory state including unsaved changes.

If the peer no longer has the chunk loaded it answers with an empty payload and the
requester reads the master copy instead of generating terrain.

## 7. Chunk generation and the generation lease

World generation still happens on a game server, never on the master. Two servers can
both need a never-seen chunk at the same time (two players exploring, or two players
joining a fresh world at once). Generation is the most CPU-expensive part of loading a
chunk, so doing it twice is pure waste, and the two uploads race: the last write wins and
the terrain of one server is discarded.

The master therefore grants a **generation lease** (`ChunkGenerationManager`):

```
server A reads unknown chunk  -> master grants the lease -> A replies "generate"
server B reads the same chunk -> master holds the reply   -> B waits
A loads the chunk (Subscribe/ChunkChangedStatus "loaded")
                              -> master wakes B         -> B reads A's copy via the peer path
```

* Only the lease owner receives the empty payload that makes vanilla generate. Every
  other reader is answered later, through the normal peer redirect, so the chunk is
  generated exactly once and both servers use the same terrain.
* The lease is completed by the first of: the owner reporting the chunk `loaded`, the
  owner subscribing the chunk (its in-memory copy is servable), a region upload of the
  chunk, or the owner disconnecting. An `unloaded` status also releases waiters.
* A waiter stops waiting after `generation-wait-millis` (default 2000 ms) and generates
  locally. A stalled or very slow generator therefore cannot hold a chunk load until the
  client-side IO timeout; the trade-off is a possible duplicate generation after the
  budget, which is what happened always before this feature.
* An incomplete lease expires after `generation-lease-millis` (default 15000 ms), so a
  generator that never loads or saves the chunk does not block it forever.
* Both values live in `multiforge-master.properties`; `generation-lease-millis=0`
  disables the feature and restores the old first-come-first-served behaviour.
* The mod needs no change: a deferred reply simply keeps the `ChunkStorage.read` future
  pending, and the normal peer/stored read is used when it completes.

### What scales and what does not

Chunk **ticking**, block/fluid ticks, entity simulation and chunk **generation** are
distributed: each chunk is simulated by one server and generated by one server. With
players spread over the cluster, the work therefore spreads with them, which is what lets
a world serve more players than one machine could simulate.

A single player's view is different: that player's server loads (and usually generates)
every chunk in their view distance itself, because a client can only be served by the
server it is connected to. Adding servers does not make one player's chunk loading faster
than local generation; it makes the *total* work of many players run in parallel. Two
players in the same chunk also share one owner, so they do not add a second machine to
that chunk. This is the same model as MultiPaper, and it is why the generation lease (one
machine per chunk) matters: it is what keeps a many-player startup from doing the same
generation several times.

## 8. Observability

* `/multiforge chunk` prints owner, locality and subscribers of the chunk you stand in.
* `/multiforge chunks` lists locally owned chunks (first 50).
* `/multiforge status` prints owned/loaded counts, peer count and chunk IO counters
  (reads, writes, failures).
* `debug.logging = true` logs every ownership request, acquisition and load.
