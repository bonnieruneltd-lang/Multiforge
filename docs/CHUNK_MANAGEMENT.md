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
       -> master: ReadChunkMessage(world, type, x, z)
            DataMessageReply(data)                 -> inflate -> CompoundTag
            ChunkLoadedOnAnotherServer(peer)       -> PeerManager.requestChunk(...)
                 data non-empty                    -> use it (newest in-memory copy)
                 data empty                        -> ForceReadChunkMessage (master copy)
       on failure -> LocalChunkStorage.read (never regenerate over existing terrain)
```

Details:

* Concurrent reads of the same chunk share one future (`inFlightReads`).
* A missing chunk returns `Optional.empty()`, which is the vanilla signal for "generate".
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

## 7. Chunk Generation and world seeds

World generation still happens on the server that needs the chunk first. Two servers can
generate the same chunk concurrently only if the master has no data for it and both load it
before either saves; the master serializes the writes (`WillSaveChunkLater` lock), so the
last upload wins and both servers converge after re-reading. To avoid this entirely, let one
server generate the spawn area before starting the others (MultiPaper has the same caveat).

## 8. Observability

* `/multiforge chunk` prints owner, locality and subscribers of the chunk you stand in.
* `/multiforge chunks` lists locally owned chunks (first 50).
* `/multiforge status` prints owned/loaded counts, peer count and chunk IO counters
  (reads, writes, failures).
* `debug.logging = true` logs every ownership request, acquisition and load.
