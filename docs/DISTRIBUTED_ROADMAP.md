# Distributed roadmap

This is the experimental track. It must never destabilise the MultiPaper port or the
required-mod compatibility work; the priorities in `TODO.md` win over anything here.

Each prototype is sized to produce **evidence**, not a finished feature. A prototype is
done when the listed acceptance test runs reproducibly and the result is written down.

## Prototype 1 — Basic distributed world

**Goal:** two Forge 1.20.1 servers share one world through the master, with one owner per
chunk and correct block/block-entity propagation.

**Current state:** implemented and exercised live (master + `live1` + `live2`; chunk
transfer, block broadcast, time/gamerule sync, ownership requests). `tools/compat/cluster_check.py`
turns the manual checks into a repeatable transcript.

**Remaining acceptance:**

* run the harness in CI with two servers and assert all checks pass;
* add a player-driven walk across a chunk boundary and verify ownership moves without a
  tick gap;
* verify a graceful stop flushes every pending chunk write (master storage grows, no
  `pending uploads` warning).

**Risks:** environment cost (two 2 GB JVMs), flaky startup times, port collisions. Keep
this prototype on a dedicated CI runner, not in the unit-test suite.

## Prototype 2 — Modded functionality

**Goal:** representative Forge systems survive the cluster: worldgen mods, a block entity
with items, a Forge Energy capability, scheduled ticks and a custom entity.

**Steps:**

1. Use the pinned 20-mod server set as the baseline (`REQUIRED_MOD_COMPATIBILITY.md`).
2. Add a small test mod that registers an energy storage block entity and a machine that
   moves items/energy between neighbours.
3. Place the machine across a chunk boundary; confirm the owning server ticks it and the
   mirror server receives NBT updates.
4. Move ownership while the machine is active; check for duplicated items/energy.
5. Record capability object invalidation behaviour on both sides.

**Acceptance:** no item/energy duplication over a 10-minute run with ownership migration;
block entity contents converge after migration; no stale capability exception.

**Risks:** this is where the current NBT-only sync is most likely to fail. Expect to need
capability invalidation hooks and possibly a "do not split" rule.

## Prototype 3 — Message-based operations

**Goal:** measure whether any world interaction can safely become asynchronous.

**Steps:**

1. Add a request/reply peer message for a read-only query (e.g. "what is the block state at
   this position on the owner?") with a timeout.
2. Instrument one known operation (a comparator/redstone query or a mod adapter) to use it.
3. Measure latency p50/p99 and compare with local reads and with ownership transfer.
4. Deliberately delay/drop the reply and verify the caller falls back safely.

**Acceptance:** a written comparison of asynchronous query vs ownership transfer, including
failure behaviour. Do **not** wire it into a general block access path.

**Risks:** blocking the tick thread on a network round trip; deadlock when two servers query
each other. A timeout that aborts the operation is mandatory.

## Prototype 4 — Dynamic ownership

**Goal:** transfer chunk/region ownership based on load, latency or player movement.

**Steps:**

1. Add an ownership epoch to the master and to block/block-entity packets; drop packets
   from older epochs.
2. Implement freeze → drain → snapshot → fence → resume in the master and `ChunkManager`.
3. Trigger migration on player movement first (deterministic), then on tick-time load.
4. Kill the old owner mid-migration and verify rollback or completion with no unowned chunk.
5. Benchmark lock traffic with per-chunk ownership vs an 8×8 region prototype.

**Acceptance:** no duplicated block change and no lost block entity update across 100
migrations; no chunk left unowned after an owner crash; measurable reduction in lock
messages with regions.

**Risks:** this is the highest-risk item. Without epochs and acknowledgements it can corrupt
state. Do not merge it behind a config flag until the failure test passes.

## Prototype 5 — Instrumentation

**Goal:** learn how often existing mods touch state owned by another server, without
changing semantics.

**Steps:**

1. Add a Mixin that counts `Level.getBlockEntity` calls whose chunk is owned elsewhere and
   logs a histogram (mod id from the caller class loader if available).
2. Run it with the required 20-mod set for a normal play session.
3. Repeat with a modded machine pack; identify the top cross-ownership callers.
4. Decide, from data, whether adapters or migration are worth building.

**Acceptance:** a report of call counts and hot paths, with zero behaviour change. The
transformer must be removable by config and must not run on the client.

**Risks:** Mixin conflicts with performance mods; caller attribution is imprecise; the
measurement itself can slow the server. Keep it behind `debug` and never ship it enabled.

## Sequencing and gates

| Order | Prototype | Gate before starting |
| --- | --- | --- |
| 1 | Basic distributed world | harness green twice in a row |
| 2 | Modded functionality | prototype 1 green + pinned mod set stable |
| 3 | Message-based operations | prototype 2 shows a real need for remote reads |
| 4 | Dynamic ownership | epochs + sequence numbers implemented and tested |
| 5 | Instrumentation | prototype 2/3 data shows where to look |

Anything that changes ownership or packet ordering must update `SYNCHRONIZATION.md`,
`NETWORKING.md` and the peer protocol version in the same commit.

## Explicit non-goals for now

* Parallel chunk ticking inside one server process.
* Transparent asynchronous world APIs for unmodified mods.
* Replacing master region storage with Redis or a message queue.
* Distributing a single block entity's tick across machines.

These are not impossible in principle, but the current evidence does not justify the risk.
