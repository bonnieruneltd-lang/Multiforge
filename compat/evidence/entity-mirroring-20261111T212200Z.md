# M1 entity mirroring evidence 20261111T212200Z

Two Forge 47.4.29 dedicated servers (`live1`, `live2`) plus one MultiForge master on
`127.0.0.1:35353`, built from this branch with the pinned server target set (18/19 installed;
Ksyxis removed for the shared-chunk check as documented). Both servers use the production
mod jar, not a dev run.

## Startup and cluster state

```
[live1] Master connection established, re-subscribing chunks
[live1] MultiForge ready (3 worlds, port 35401)
[live2] Master connection established, re-subscribing chunks
[live2] MultiForge ready (3 worlds, port 35402)
```

```
/multiforge status
live1 | master=connected | tps=20.0 | chunks owned=93 loaded=529 | peers=1 | mirrored=79 | io reads=2025 writes=0 failures=0
live2 | master=connected | tps=20.0 | chunks owned=0 loaded=529 | peers=1 | mirrored=341 | io reads=2025 writes=0 failures=0
```

```
[25575] /multiforge chunk 6 -2
Chunk world,6,-2 owner=live1 local=true loaded=true subscribers=[live2]
[25576] /multiforge chunk 6 -2
Chunk world,6,-2 owner=live1 local=false loaded=true subscribers=[live1]
```

The chunk is owned and ticked only by `live1`; `live2` has it loaded and subscribed. Both
servers were force-loaded (`/forceload add 96 -32 112 -16`) so no player was needed.

## Entity spawned on live1, visible on live2

```
[live1] /summon minecraft:cow 110 200 -24 {NoGravity:1b,Health:10.0f,Tags:["m1final2"],PersistenceRequired:1b,NoAI:1b}
Summoned new Cow

[live1] /data get entity @e[tag=m1final2,limit=1] Pos
Cow has the following entity data: [110.5d, 200.0d, -23.5d]
[live2] /data get entity @e[tag=m1final2,limit=1] Pos
Cow has the following entity data: [110.5d, 200.0d, -23.5d]

[live1] /data get entity @e[tag=m1final2,limit=1] Health
Cow has the following entity data: 10.0f
[live2] /data get entity @e[tag=m1final2,limit=1] Health
Cow has the following entity data: 10.0f
```

## Movement (position update) from the owner

```
[live1] /tp @e[tag=m1final2,limit=1] 108 210 -26
Teleported Cow to 108.500000, 210.000000, -25.500000

[live1] Pos -> [108.5d, 210.0d, -25.5d]
[live2] Pos -> [108.5d, 210.0d, -25.5d]
```

An earlier run of the same code path also showed delta movement (`ClientboundMoveEntityPacket`)
on a moving item entity: the owner reported x=116.13213792706979 and the mirror x=116.152099609375
(one tick of motion), confirming that the non-teleport movement path is applied. In the final
recipe runs no player was online, so the force-loaded chunk was not entity-ticking and the
item stayed at its spawn position on both servers; teleport (also a movement packet) is the
reproducible check above.

## Damage from the mirror reaches the owner

```
[live2] /damage @e[tag=m1final2,limit=1] 4 minecraft:generic
Applied 4.0 damage to Cow

[live1] Health -> 6.0f
[live2] Health -> 6.0f
```

## Damage from the owner reaches the mirror

Fresh cow `m1final4`, so the previous hit's invulnerability window cannot interfere:

```
[live1] /damage @e[tag=m1final4,limit=1] 4 minecraft:generic
Applied 4.0 damage to Cow

[live1] Health -> 6.0f
[live2] Health -> 6.0f
```

## Removal on the owner removes the mirror

```
[live1] /kill @e[tag=m1final2,limit=1]
Killed Cow

[live1] /data get entity @e[tag=m1final2,limit=1] Health
No entity was found
[live2] /data get entity @e[tag=m1final2,limit=1] Health
No entity was found
```

## Cluster regression harness

`tools/compat/cluster_check.py --expected-targets 18` passed **13/13** checks on the same
run (master connectivity, membership, 18/19 targets, anti-seed datapack, shared chunk,
blocks both ways, chest block entity, time, gamerule, single owner, 20 TPS, save-all):

```
compat/evidence/cluster-20261111T212137Z.md
compat/evidence/cluster-20261111T212137Z.json
```

## Storage, logs and shutdown

```
du -sh .runtime/master-storage
14M
```

No MultiForge exceptions in either server or the master log. The only ERROR lines are
Antixray's own `Mixin config ... does not specify "minVersion"` startup messages (a known
target-mod warning, not MultiForge).

```
[live1] Stopping MultiForge (server stopped)
[live2] Stopping MultiForge (server stopped)
```

Both servers stopped cleanly; no pending-upload warning was logged, so `ChunkSyncIo.flush`
completed.

## Final jar re-run

After the last code changes (invalidate in-flight entity id prefetches on shutdown; open
missing peer connections on a dedicated connector thread instead of the server thread), the
final jar was deployed again and the key path repeated twice, with fresh cows `m1verify` and
`m1peer`: position `[110.5, 200.0, -23.5]` and health `10.0f` on both servers, damage of 4
on the mirror produced `6.0f` on both, and `/kill` on the owner made the entity disappear on
both servers. Both servers logged `Stopping MultiForge (server stopped)` again.

## Remaining limitations (recorded in KNOWN_ISSUES.md)

* Player entities are not mirrored yet (TODO 2).
* A cross-server hit carries damage type and amount, not the attacker; kill credit and death
  messages are not attributed to a player from another server.
* An entity crossing an ownership boundary can pause until the new owner ticks it; there is
  no full NBT resync on the crossing yet.
