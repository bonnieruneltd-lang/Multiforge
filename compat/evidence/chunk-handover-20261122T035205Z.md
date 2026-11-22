# Fenced chunk handover live evidence 20261122T035205Z

Two Forge 1.20.1 / Forge 47.4.29 development servers (`live1` game port 25565, `live2`
25566, RCON 25575/25576) and one standalone master (35353), all on the tree that contains the
`ChunkHandoverManager` protocol additions. Java 17.0.21.1 (Temurin). Storage was a fresh
master directory. Both servers had `online-mode=false`.

## 1. Regression: the existing 18-check cluster harness passes

`python3 tools/compat/cluster_check.py --a-port 25575 --b-port 25576 --password mfcompat
--skip-mod-checks` recorded **18/18 PASS** in
`.runtime/evidence/cluster-20261122T035136Z.md`:

```
master-connected, cluster-membership, shared-loaded-chunk, block-a-to-b, block-b-to-a,
block-entity-a-to-b, scoreboard-objective-a-to-b, scoreboard-score-a-to-b,
scoreboard-team-a-to-b, entity-mirror-B-to-A, entity-damage-B-to-A,
entity-remove-B-to-A, sign-text-a-to-b, time-sync, gamerule-sync,
single-authoritative-owner, tick-health, save-all
```

Both servers reported 20.0 TPS, one peer each and no IO failures. This run therefore also
covers the chunk sequencing counters (`seq gaps=0`) and the handover code path being loaded
without breaking ownership.

## 2. Player-driven handover succeeds

A headless bot (`tools/compat/mcbot.js HandoverBot`, `minecraft-protocol` 1.68.0) joined
`live1`. Chunk `world,0,0` was force-loaded on both servers and owned by `live2`:

```
live1: Chunk world,0,0 owner=live2 local=false loaded=true subscribers=[live2]
live2: Chunk world,0,0 owner=live2 local=true  loaded=true subscribers=[live1]
```

`tp HandoverBot 8 100 8` on `live1` moved the real player into that chunk. Within the next
second the owner changed and the master logged the full sequence:

```
[03:51:51 INFO] [MultiForge-Netty-2] MultiForgeMaster - Handover 1 of world,0,0 from live2 to live1 started
[03:51:51 INFO] [MultiForge-Netty-3] MultiForgeMaster - Handover 1 of world,0,0 completed to live1 (epoch 2)
```

`live2` logged `Lost ownership of world,0,0 to live1 (epoch 2)`; the query became:

```
live1: Chunk world,0,0 owner=live1 local=true  loaded=true subscribers=[live2]
live2: Chunk world,0,0 owner=live1 local=false loaded=true subscribers=[live1]
```

A second, deeper crossing reused a chunk still owned by `live2` (`world,5,6`), teleported the
bot there and completed the same way:

```
[03:52:05 INFO] ... Handover 2 of world,5,6 from live2 to live1 started
[03:52:05 INFO] ... Handover 2 of world,5,6 completed to live1 (epoch 2)
live1: Chunk world,5,6 owner=live1 local=true loaded=true subscribers=[live2]
```

The transfer is acknowledgment-fenced: the master only logged `completed` after `live2`
answered ready, which it does after `ChunkMap.save` and the master's write acknowledgement.
`live2` kept the chunk loaded as a subscriber and no TPS drop was observed.

## 3. Refusal when the old owner has a real player

A second bot (`StayBot`) joined `live2` and was teleported into chunk `world,-4,-7`, which
`live2` owned. `HandoverBot` was then teleported into the same chunk. The owner refused and
the chunk stayed with `live2`:

```
[03:52:40 INFO] ... Handover 16 of world,-4,-7 from live2 to live1 started
[03:52:40 INFO] ... Handover 16 of world,-4,-7 declined by live2 (local player inside the chunk)
live1: Chunk world,-4,-7 owner=live2 local=false loaded=true subscribers=[live2]
```

The retry initially ran at the ownership-request interval; the run exposed that noise, so
`ChunkManager` now applies a ten-second per-chunk backoff after a refusal or timeout
(`HANDOVER_RETRY_BACKOFF_TICKS`). The backoff was added after this run and is compile-checked
only; the success and refusal paths above are the recorded evidence.

## 4. Limits still open

The timeout path and the old-owner-disconnect path are covered by master integration tests
(`aTimedOutHandoverLeavesTheOldOwnerInPlaceAndIgnoresALateAnswer`,
`handoverCompletesWhenTheOldOwnerDisconnects`) but were not forced live. Mob navigation,
raids, the dragon fight and projectile handover beyond the chunk resync remain TODO 5. The
two dev servers were started from the Gradle development runs, not from a normal Forge
installation with the pinned mod set; server-mod compatibility was not re-run in this session.
