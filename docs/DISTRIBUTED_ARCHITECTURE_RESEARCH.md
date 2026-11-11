# Distributed architecture research

**Status:** research note, not a description of shipped behaviour. MultiForge today is a
MultiPaper-style shared-world cluster: one master owns storage and arbitration, and every
game server simulates the chunks it owns. This document asks which parts of that design can
be extended to heavily modded Forge worlds, which cannot, and what evidence would be needed
before changing the core.

Read `ARCHITECTURE.md`, `SYNCHRONIZATION.md` and `CHUNK_MANAGEMENT.md` first. Everything
below is grounded in the code that exists in this repository.

## 1. What the current design guarantees

| Property | Mechanism | Where |
| --- | --- | --- |
| One authoritative ticker per chunk | `LockChunkMessage` → master `ChunkSubscriptionManager.lock`, local `ownedLocally`, `ServerLevelMixin.tickChunk` | `ChunkManager`, master handlers |
| Single owner per entity tick | `ServerLevelMixin.tickNonPassenger` consults the chunk owner | `ServerLevelMixin` |
| Block/block-entity fan-out | `LevelChunkMixin` / `BlockEntityMixin` → peer `BlockChangePacket` / `BlockEntityUpdatePacket` | `ChunkManager`, peer packet layer |
| Durable shared world | Master region files + `WillSaveChunkLater` / `WriteChunkMessage` | `ChunkSyncIo`, `RegionFile` |
| Crash recovery | Master releases locks/subscriptions on channel close; reconnect re-subscribes | `ServerConnection`, `MasterConnection` |
| Cluster identity | `HelloMessage` + `PlayerConnectMessage` duplicate-login guard | master, `PlayerSyncManager` |

The design is deliberately synchronous on the game thread: a block change is applied
locally and broadcast; a chunk read blocks the requesting thread on a master/peer future
with a timeout. That is what makes vanilla and most Forge code keep working.

## 2. Actor / message-passing model

### 2.1 What can become an actor

Chunk **ownership** is already an actor-like lease: the master is the arbiter, the owner
is the only one allowed to mutate, and everyone else receives events. The missing pieces
for a real actor model are:

1. **Ordered event log per chunk.** Today peer block packets have no per-chunk sequence
   number. TCP preserves order on one connection, but reconnects and multi-hop paths can
   reorder relative to chunk data. An epoch + sequence per chunk would let receivers detect
   gaps and request a snapshot.
2. **Acknowledgements.** There is no ack for block broadcasts. A sender cannot know that a
   subscriber applied an update, so ownership migration has no safe cut-over point.
3. **Requests with replies.** Chunk reads already have request/reply semantics. Block
   *interactions* do not: a mod calling `level.getBlockEntity(remotePos)` gets the local
   copy, which may be stale. There is no request path today.

### 2.2 The synchronous API problem

Minecraft and Forge expose synchronous world access everywhere: `Level.getBlockState`,
`BlockEntity` methods, capabilities, `IItemHandler`, redstone queries. An actor system
cannot replace these with futures without breaking every caller. The practical options are:

* **Ownership transfer on demand** (what MultiPaper does): if a mod needs to simulate a
  remote block, take ownership of the chunk first, wait for the handoff, then run vanilla
  code locally. This preserves synchronous semantics at the cost of a pause.
* **Local optimistic copy + reconciliation**: read the local mirror, perform the action
  locally, and reconcile when the owner reports state. Fast, but can duplicate effects
  (two machines crafting the same item). Only safe for read-only or idempotent operations.
* **Remote procedure call with blocking wait**: marshal the operation to the owner and
  block the calling thread on the result. Correct but can deadlock if the owner is also
  waiting on the caller; needs a strict lock ordering or a timeout that aborts the action.

MultiForge should not pretend an asynchronous API can transparently replace synchronous
world access. The safe default remains ownership transfer.

### 2.3 Feasibility verdict

| Idea | Feasibility | Why |
| --- | --- | --- |
| Per-chunk event sequence + gap detection | High | Local change to peer packets and `ChunkState`; no API change. |
| Broadcast acknowledgements | Medium | Adds traffic and backpressure; needed before safe migration. |
| Async block interaction RPC | Low for general mods | API is synchronous; only adapter-known operations can be marshalled safely. |
| Chunk actors ticking independently | Low | Minecraft's tick loop is single-threaded per level; parallel chunk ticking breaks mods that assume thread affinity. |

## 3. Dynamic chunk and region ownership

Ownership is currently demand-driven: a server requests a chunk when it is about to tick
it. That is good for load balancing (the server with the player gets the chunk) but has no
migration protocol beyond "lock, then the old owner stops ticking when the master
broadcasts the new owner".

### 3.1 Safe migration requirements

A correct handoff needs, in order:

1. **Freeze**: master marks the chunk `migrating`, stops granting ticks to the old owner.
2. **Drain**: old owner finishes the current tick, flushes scheduled block/fluid ticks and
   block-entity changes, and reports a state digest (tick count, pending ticks, entity ids).
3. **Snapshot**: new owner reads the chunk from the master (or directly from the old owner)
   and confirms the digest.
4. **Fence**: master increments an ownership epoch; packets from older epochs are dropped.
5. **Resume**: new owner starts ticking; old owner keeps a read-only mirror.

Items 1, 3 and 5 partly exist. There is **no epoch** in any packet today, so a delayed
block change from the old owner can overwrite a newer change from the new owner. Adding an
epoch to `BlockChangePacket` and `BlockEntityUpdatePacket` is the smallest useful next step
and does not require a protocol redesign (append a field, bump the peer protocol version).

### 3.2 Region ownership

Grouping chunks into regions (e.g. 8×8 or 16×16 chunks) reduces lock traffic and keeps
neighbours together, which matters for redstone, multiblocks and modded machines. The cost
is coarser balancing: one busy chunk pins a whole region to a server. A hybrid is
reasonable: regions for ownership decisions, per-chunk subscriptions for data. This can be
prototyped entirely in the master without touching the mod's tick gate, by making
`LockChunkMessage` lock the surrounding region and return the region id.

### 3.3 What must not be split

Do not split a block entity from its neighbours while it has pending scheduled ticks or an
active capability network. A modded machine often caches references to adjacent
`IItemHandler`/`IEnergyStorage` objects. Moving one block entity without invalidating those
caches produces stale capability objects. Ownership migration must therefore either move a
whole connected component (hard to compute generically) or force capability invalidation on
both sides and accept a rebuild cost.

## 4. Distributed state and storage

| Option | Fit | Notes |
| --- | --- | --- |
| Master region files (today) | Good | Simple, crash-safe enough, no extra service. Latency is one network round trip per read/write. |
| Redis for coordination | Medium | Good for leases, player routing, pub/sub and ephemeral metadata. Not a replacement for chunk data: serializing a 100 KB chunk per tick is not viable. |
| Redis as chunk cache | Low/medium | A read-through cache of recently written chunks can help, but invalidation and memory cost must be measured. |
| Embedded KV (RocksDB) on shared NVMe | Medium | Useful for a future "storage node" role; requires a single writer or a consensus layer. |
| Message queue (NATS/Kafka) | Low for gameplay | Adds latency and operations; useful only for analytics/telemetry. |
| Shared filesystem | Not recommended | Region files assume one writer; NFS locking is not reliable enough for chunk data. |

The master's region format writes raw gzip payloads with the zlib version byte. MultiForge
reads them through its own path, but the files are **not** vanilla-compatible. That is a
latent interoperability bug: any tool or server reading master storage with a standard
region reader will fail. The fix is to store actual zlib-deflated bytes (or change the
version byte to gzip and teach the reader), and to add a round-trip test that opens master
storage with a vanilla-format reader.

## 5. Bytecode instrumentation

Forge's supported transformation path is Mixin (plus ModLauncher's built-in transformations).
A custom Java agent would fight the module layer and is not justified.

Useful interception points and their limits:

| Operation | Can intercept? | Limit |
| --- | --- | --- |
| `Level.getBlockState` / `getBlockEntity` | Yes | Callers expect the local mirror; redirecting to a remote owner requires a synchronous RPC or ownership transfer. |
| Capability lookups (`getCapability`) | Yes, per call site | Cached capability objects bypass the hook; must invalidate on ownership change. |
| `BlockEntity.setChanged` | Already hooked | Only sees NBT-persistent state, not in-memory caches. |
| `Entity.setPos` / tick | Yes | Server-thread affinity and client tracking packets make remote simulation unsafe. |
| Custom mod packets | No generic hook | A mod's packet may encode assumptions about which server owns the block; adapters are required. |

Instrumentation is therefore best used for **detection and routing of known operations**,
not as a universal distributed runtime. Every transformer must be tested against the actual
mod versions pinned in `compat/required-mods.json`; a transformer that works on one version
can silently change behaviour on the next.

A realistic first transformer is read-only: intercept `Level.getBlockEntity` for a block
entity whose chunk is owned elsewhere and return the local mirror, while recording a
counter. That produces evidence about how often mods actually touch remote state without
changing semantics. It is a measurement tool, not a compatibility fix.

## 6. Capabilities and modded machines

Forge capabilities are the hardest part of automatic compatibility:

* **Energy / fluid / item handlers** are often rebuilt lazily and cached by neighbours.
  Syncing the block entity's NBT is not enough: the live `IItemHandler` object holds
  references and per-tick state.
* **Capability invalidation** (`invalidateCaps`) must run on both the old and new server
  when ownership changes, otherwise one side keeps a handler pointing at a detached block
  entity.
* **Capability serialization** is mod-defined. There is no generic `serialize()`; a mod may
  keep energy in a field and only write it on save. MultiForge's NBT broadcast covers the
  persisted subset, not the live subset.
* **Multiblocks** couple several block positions. A chunk-level ownership model can split
  a multiblock; a region model helps but does not guarantee the component fits in a region.

The general solution is therefore: keep capability-bearing blocks on the same owner, and
only migrate whole connected components. Detecting components generically requires either
mod adapters or a conservative radius-based rule (e.g. never migrate a chunk whose
neighbours contain capability block entities with recent activity). Both are research
items; neither should be assumed to work.

## 7. Recommended sequence

1. Add ownership epochs and a peer-protocol version bump (small, testable).
2. Add per-chunk sequence numbers and gap detection on block/block-entity packets.
3. Fix the master region file compression label and add a vanilla-reader round-trip test.
4. Prototype region ownership in the master only; measure lock traffic and TPS.
5. Instrument read-only remote block-entity access to learn how often mods cross ownership
   boundaries; use the result to decide whether adapters or migration are worth building.
6. Only then attempt capability-aware migration with a real modded machine.
