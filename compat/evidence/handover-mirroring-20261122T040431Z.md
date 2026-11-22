# Handover, mirroring and backoff live evidence 20261122T040431Z

Follow-up run on the final build of this session: the fenced handover plus the live player
list/game-mode sync, the periodic player re-announce and the refusal backoff. Same two Forge
47.4.29 dev servers (`live1` 25565/RCON 25575, `live2` 25566/RCON 25576) and standalone
master 35353; Java 17.0.21.1. Two headless bots (`minecraft-protocol` 1.68.0): `InfoBot` on
`live1`, `ViewBot` on `live2` (placed two chunks away so it loads the target chunk without
standing in it).

## 1. Cluster regression on the final build

`python3 tools/compat/cluster_check.py --a-port 25575 --b-port 25576 --password mfcompat
--skip-mod-checks` recorded **18/18 PASS** in
`compat/evidence/cluster-20261122T040520Z.md` (a first run on the handover build,
`cluster-20261122T035136Z`, also passed 18/18):

```
master-connected, cluster-membership, shared-loaded-chunk, block-a-to-b, block-b-to-a,
block-entity-a-to-b, scoreboard-objective-a-to-b, scoreboard-score-a-to-b,
scoreboard-team-a-to-b, entity-mirror-A-to-B, entity-damage-A-to-B,
entity-remove-A-to-B, sign-text-a-to-b, time-sync, gamerule-sync,
single-authoritative-owner, tick-health, save-all
```

Both servers held 20.0 TPS, one peer and no IO failures; `seq gaps=0` and
`local-backlog=0` on both. The mirrored counts prove the player and entity streams were
active during the run (live1 mirrored=15, live2 mirrored=162).

## 2. Fenced handover still succeeds

`chunk world,5,-6` was owned by `live2`; `tp InfoBot 88 100 -88` on `live1` moved a real
player into it. The master completed the transfer and the query shows the new owner:

```
[04:04:20 INFO] ... Handover 4 of world,5,-6 from live2 to live1 started
[04:04:20 INFO] ... Handover 4 of world,5,-6 completed to live1 (epoch 4)
live1: Chunk world,5,-6 owner=live1 local=true loaded=true subscribers=[live2]
```

This ran after the start message gained the `newOwner` field and after the old owner began
sending its live entity snapshots before the fence.

## 3. Live game-mode / tab-list mirroring

`InfoBot` (real on `live1`) had a mirror on `live2`. Before the change both read the same
value and after `gamemode creative InfoBot` on `live1` both sides agreed:

```
live1: InfoBot has the following entity data: 0   (survival)
live2: InfoBot has the following entity data: 0   (mirror before)
live1: Set InfoBot's game mode to Creative Mode
live1: InfoBot has the following entity data: 1
live2: InfoBot has the following entity data: 1   (after PlayerListInfoPacket)
```

The run also exposed a pre-existing mirroring hole: a player who teleports into a chunk that
another server already has entity-subscribed was never announced there, so no mirror was
created (the earlier attempt showed `No entity was found` on `live2`). `PlayerMirrorManager`
now re-announces every local player every 100 ticks; with that in place the mirror appeared
within five seconds and the game-mode update above was applied. The periodic create is
idempotent (it refreshes an existing mirror).

## 4. Refusal keeps the old owner and the backoff suppresses retries

`ViewBot` (real on `live2`) stood in `world,2,0`, owned by `live2`. `InfoBot` was teleported
into the same chunk. The owner refused and kept the chunk; over the following six seconds
only **one** request reached the master:

```
[04:04:31 INFO] ... Handover 5 of world,2,0 from live2 to live1 started
[04:04:31 INFO] ... Handover 5 of world,2,0 declined by live2 (local player inside the chunk)
live1: Chunk world,2,0 owner=live2 local=false loaded=true subscribers=[live2]
live2: Chunk world,2,0 owner=live2 local=true  loaded=true subscribers=[live1]
```

The previous build repeated this every second; the new `HANDOVER_RETRY_BACKOFF_TICKS`
(200 ticks) is therefore verified live.

## 5. Not covered here

The 5 s timeout path and the old-owner-disconnect path still come from the eight master
integration tests, not a forced live failure. The pinned 20-mod server set was not installed
in this run (`--skip-mod-checks`), so the compatibility matrix is unchanged. Client-only
targets still require a GPU.
