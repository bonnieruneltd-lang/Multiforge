# Performance

This document records where MultiForge spends time, what was optimized and how to
reproduce the measurements. Read it together with
[ARCHITECTURE.md](ARCHITECTURE.md#4-threading-model) (threading) and
[KNOWN_ISSUES.md](KNOWN_ISSUES.md) (features that are intentionally incomplete).

## 1. Hot paths

MultiForge adds work to four places that run very often. Everything else (login,
file sync, world state) runs at most once per second.

| Path | Frequency | Entry point |
| --- | --- | --- |
| Chunk tick decision | once per ticking chunk per tick | `ServerLevelMixin.tickChunk` -> `ChunkManager.shouldTick` |
| Entity tick decision | once per non-player entity per tick | `ServerLevelMixin.tickNonPassenger` -> `ChunkManager.shouldTick` |
| Entity packet forwarding | once per tracked entity packet per tick | `ChunkMapTrackedEntityMixin.broadcast` -> `EntitySyncManager.onEntityPacket` |
| Block / block entity change | once per changed block / block entity per tick | `LevelChunkMixin.setBlockState`, `BlockEntityMixin.setChanged` |

Nothing on these paths may allocate per call if it can be avoided, and none of them
may touch disk. The master has one comparable hot path: `RegionFileCache` runs on
every chunk read and write request.

## 2. Optimizations in this change set

### 2.1 Chunk tick decision (server thread)

* **One map lookup instead of two.** `ChunkState` gained a `localOwner` flag that
  `onOwnerChanged` maintains, so `shouldTick` reads the state once instead of
  probing `ownedLocally` (a second concurrent set) and then `states`.
* **No more string allocation per call.** `ChunkManager.worldName` cached the
  logical world name under `dimension().location().toString()`, which allocated a
  `String` on every chunk tick and every entity tick. The cache is now keyed by
  the level's immutable `ResourceKey<Level>`.
* **Unknown chunks are no longer materialized.** `shouldTick` never created a
  `ChunkState` for a chunk that has no state yet (the state is created when the
  master answers the ownership request), and the per-chunk ownership-request
  bookkeeping is dropped when the chunk is granted or unloaded, so it cannot grow
  without bound.
* **Entity overload.** `ServerLevelMixin.tickEntity` no longer calls
  `entity.chunkPosition()` (allocates a `ChunkPos`) and
  `ChunkSource.getChunkNow` (chunk-map lookup) per entity per tick. An entity that
  ticks is by definition in a loaded chunk, so `shouldTick(ServerLevel, Entity)`
  derives the chunk coordinates from the position directly.

### 2.2 Entity packet forwarding (server thread)

* **O(1) "is anybody listening?" guard.** `ChunkManager` maintains a counter of
  chunks with at least one entity subscriber (`hasEntitySubscribers`). Without a
  subscriber `onEntityPacket`, `onEntityJoined`, `onEntityRemoved` and
  `drainSpawns` return before building a chunk key, copying the subscriber list or
  encoding the packet. A later subscriber receives the full chunk snapshot from
  `onEntitySubscriberAdded`, so no state is lost.
* **No `Optional` on the ownership check.** `ChunkManager.ownerName` returns the
  owner directly; `isReplicationAuthority` and `onEntityHurt` use it instead of
  `ownerOf(...).orElse(null)`.
* **No `ChunkPos` allocations.** `keyOf(level, x, z)` is used with coordinates
  derived from the entity position.
* **Empty subscriber lists do not copy.** `entitySubscribersOf` /
  `subscribersOf` return `List.of()` when the set is empty instead of copying it.
* **Reused encode buffer.** `EntitySyncManager.encode` uses a thread-local
  `FriendlyByteBuf` (256 bytes, discarded if a packet grows it beyond 64 KiB)
  instead of allocating a Netty buffer per forwarded packet.
* **Subscriptions are O(entities in the chunk).** `onEntitySubscriberAdded` used
  to iterate *every* entity in the level and filter by chunk; it now queries the
  chunk's area through `Level.getEntities(Entity, AABB, Predicate)`. This runs on
  every entity subscription, i.e. potentially hundreds of times while a player
  walks.
* **Removal cache expires once per second.** `removedUntil` was scanned with
  `removeIf` on every tick; entries live 600 ticks, so scanning every 20 ticks is
  equivalent and cheaper on busy servers.


### 2.3 Block and block entity changes (server thread)

* **No packet construction without a peer.** `broadcastBlockChange` and
  `drainBlockEntityUpdates` return early when `PeerManager.hasConnections()` is
  false. An explosion used to build one `BlockChangePacket` (with a world name)
  per block even on single-server clusters, and `drainBlockEntityUpdates`
  serialized full NBT for nothing. The chunk data plane remains the fallback: a
  peer that connects later reads the newest copy.

### 2.4 Peer connection writes (Netty event loop)

* **Coalesced flushes.** `PeerConnection.send` used `writeAndFlush` per packet.
  Entity mirroring produces one packet per tracked entity per tick, so a busy
  world caused thousands of socket flushes per second. Writes are now queued and a
  single flush is scheduled on the event loop (`scheduleFlush`), which keeps
  latency within one event-loop turn and reduces syscalls to one per burst.
* **Hash lookup instead of a linear scan.** `PeerProtocol.idOf` used
  `List.indexOf` over the registered classes for every packet; it now uses a
  `Map<Class, Integer>`. Packet ids stay append-only.

### 2.5 File synchronization (IO thread)

* **Unchanged files are not read.** The one-second scan hashed the content of
  `level.dat`, `uid.dat` and the four user lists even when nothing had written
  them. A modification-time-plus-size stamp is checked first; the hash is still
  compared whenever the stamp changes.
* **No scan pile-up.** A slow disk or a large `filesToSyncInRealTime` tree could
  queue one scan per second behind the previous one on the single IO thread. An
  in-flight flag now skips the tick instead.

### 2.6 Master storage (master, all server connections)

* **Region file lookups no longer call the filesystem.** `RegionFileCache`
  resolved `File.getCanonicalPath()` for every chunk access while holding the
  class lock. Canonicalisation is memoized per region directory, so the hot path
  is a hash-map get plus string concatenation. This also shortens the critical
  section that serialized chunk storage access across servers.

### 2.7 Shutdown robustness (no performance cost)

* `MultiForgeServer.execute` swallows `RejectedExecutionException` thrown by
  `MinecraftServer.executeIfPossible` during shutdown. Previously every late
  master/peer message logged a full stack trace (about 19k lines in a two-server
  test), which obscured real errors and made stops slow.

## 3. Measurements

### 3.1 Microbenchmark

`tools/bench/HotPathBench.java` compares the old and new implementations of the
two lookups that run per chunk access or per packet:

```
region file lookup: old 7205.2 ns/op, new 121.5 ns/op (x59.3)
packet id lookup:   old   78.6 ns/op, new   9.3 ns/op (x8.5)
```

The region file number is dominated by the `getCanonicalPath()` syscall that the
old code performed on every lookup, under the global cache lock. Reproduce with
the command in the class comment (the file is not part of the Gradle build because
it needs no Minecraft classes).

### 3.2 Live two-server run

Verified with a master plus two Forge 47.4.29 servers (`runServer` and
`runServer2`, no compatibility mods, 529 loaded chunks per server):

| Observation | Result |
| --- | --- |
| `/multiforge status` on both | `master=connected`, `peers=1`, `tps=20.0`, `mirrored=73` on the mirror |
| Summon a pig in a chunk owned by live1 | `mirrored` on live2 increased by one |
| `setblock` chain live1 -> live2 -> live1 -> live2 | all four blocks propagated |
| Chest filled on live1 | `data get block` on live2 shows the item |
| Graceful `stop` | `Stopping MultiForge (server stopped)`, no exception in either server log |

Both servers stayed at 20 TPS while the checks ran.


## 4. Remaining opportunities (not implemented)

Ordered by expected value. None of these are required for correctness.

1. **Chunk serialization off the server thread.** `ChunkManager.respondToChunkRequest`
   runs `ChunkSerializer.write` *and* gzip compression (`NbtIo.writeCompressed`) on
   the server thread. Only the NBT snapshot must be taken there; compression can
   move to an IO thread, which shortens the worst-case tick stall when a peer
   fetches a large chunk. Deferred because it needs a bounded executor and a test
   that a chunk reply is never dropped at shutdown.
2. **Per-chunk key cache.** `shouldTick` still allocates one `ChunkKey` per entity
   per tick. A small direct-mapped cache (or a `ChunkState` field on
   `LevelChunk` through a mixin) would remove it. Deferred: the allocation is
   ~32 bytes and the `ChunkKey` fields are final, so safety is easy but the gain
   is small compared to the changes above.
3. **Ownership epochs + packet sequence numbers** (already in TODO.md): delayed
   packets from a previous owner can still overwrite newer state. This is a
   correctness feature, not a speed one.
4. **Inline `Inflater` per peer connection.** `PeerFrameCodec.Decoder` allocates a
   new `Inflater` for every compressed frame (chunk transfers). Chunk transfers
   are rare compared to entity traffic, so this is a low priority.
5. **Gamerule diff without `createTag`.** `WorldSyncManager.pollGameRules` builds a
   `CompoundTag` of every gamerule once per second per level. Small, but it could
   be removed with a direct `GameRules` visitor if the API exposes one.
6. **Entity snapshots per subscriber.** `onEntitySubscriberAdded` serializes the
   chunk's entities once per subscriber; with more than two servers that is N-1
   copies of the same NBT. Encoding once and sending it to all new subscribers
   would be cheaper on large clusters.
7. **Master region cache granularity.** `RegionFileCache` is a single
   `synchronized` `LinkedHashMap`. It only serializes cache lookups (the IO itself
   happens under the per-file lock), but a segmented cache would remove that
   contention at high chunk rates.

## 5. Verifying a performance change

1. `./gradlew build :multiforge-protocol:test :multiforge-master:test`
2. `tools/bench/HotPathBench.java` for master-side lookups.
3. A live master plus two servers (DEVELOPMENT.md) with `/multiforge status` on
   both, an entity and a block change in a shared chunk, then a graceful stop; the
   server logs must not contain new exceptions.
4. Update this document with the new measurement and keep the "Remaining
   opportunities" list honest: do not claim a change is free if it trades one
   cost for another.
