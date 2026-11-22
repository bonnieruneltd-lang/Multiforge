# Offline chunk backlog and master-outage evidence 20261122T041340Z

Two Forge 1.20.1 dev servers (`live1` 25565/RCON 25575, `live2` 25566/RCON 25576), one
standalone master 35353, current handover build, Java 17.0.21.1. `BacklogBot`
(`minecraft-protocol` 1.68.0) was connected to `live2` so it owned and simulated its chunk.

## 1. Baseline

```
live1 | master=connected | tps=20.0 | chunks owned=110 | peers=1 | io writes=586 failures=0 | seq gaps=0 dup=3 | local-backlog=0
live2 | master=connected | tps=20.0 | chunks owned=118 | peers=1 | io writes=586 failures=0 | seq gaps=0 dup=0 | local-backlog=0
```

## 2. Master killed while both servers keep running

`kill -9` on the master process. Both servers logged the drop and reconnected loops:

```
[04:11:47 WARN] [MultiForge/Master/]: Lost connection to master, scheduling reconnect
[04:11:47 WARN] [MultiForge/]: Master connection lost; chunk synchronization paused until reconnect
...[04:11:50 DEBUG] [MultiForge/Master/]: Dropping LockChunkMessage because the master connection is down
```

`/multiforge status` reported `master=disconnected` while both servers stayed at 20 TPS. A
player-driven ownership request continued to work after the master returned, and peer links
stayed up:

* Block at live2-owned `-13,70,19` set to `gold_block` on live2.
* Block at `-13,71,19` set to `emerald_block` on **live1** (not the owner) and applied on live2
  through the peer link; `execute if block` on live2 found the emerald.
* `save-all flush` on both servers wrote the dirty chunks locally and queued them:
  `local-backlog` grew from 4/5 to **47 on each server** with no master connection.

## 3. Master restarted; the backlog drained

Fresh master on the same storage root. Both servers reconnected within two seconds:

```
[04:12:09 INFO] MultiForgeMaster - Connection from /127.0.0.1:33272 (live2)
[04:12:09 INFO] MultiForgeMaster - Connection from /127.0.0.1:33274 (live1)
```

Eight seconds later:

```
live1 | master=connected | tps=20.0 | io writes=795 failures=0 | seq gaps=0 dup=84 | local-backlog=0
live2 | master=connected | tps=20.0 | io writes=792 failures=0 | seq gaps=0 dup=7  | local-backlog=0
```

The 47 queued chunks per server were uploaded (writes increased by >80 each, zero failures).

## 4. Proof that the master has the offline data

`live2`'s local region file `world/region/r.-1.0.mca` (containing chunk `-1,1`) was moved
aside and deleted while `live2` was stopped, then `live2` was restarted and a bot loaded the
chunk again. If the upload had been lost, the chunk would regenerate or be missing. Instead:

```
execute if block -13 70 19 minecraft:gold_block       -> GOLD (block set offline on live2)
execute if block -13 71 19 minecraft:emerald_block    -> EMERALD (set on live1, peer-forwarded offline)
```

Both conditions fired in `server2e.log` and a conditional `setblock` succeeded, so the
master's region file was the source after the local file was deleted. This closes the
"re-upload after an outage" item in TODO/PERFORMANCE.

## 5. What this does not cover

The backlog is still an in-memory, bounded set (KNOWN_ISSUES 5): a process crash while the
master is down loses the queued entries. A packet-loss / gap test (dropping the peer TCP
connection during a block storm and observing `seq gaps=`) was not forced; the normal-load
runs keep `seq gaps=0` and the sequence tracker has property/recovery unit tests.
