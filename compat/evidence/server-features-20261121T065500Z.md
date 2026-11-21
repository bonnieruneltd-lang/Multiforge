# Live server-feature evidence 20261121T065500Z

Two Forge 47.4.29 **dev** servers (`live1`, `live2`) and one MultiForge master on
`127.0.0.1:35353`, built from this working tree, run headless (no display, no GPU, no
shader stack). The deployment target is a dedicated server, so client-only rendering
targets are out of scope; every check below is server-side.

* Master: `multiforge-master-1.0.0-alpha.1-all.jar`, storage `/workspace/.tools/live-master`,
  metrics on `127.0.0.1:35354`.
* Servers: `./gradlew :multiforge-forge:runServer` and `:runServer2` with
  `-PmultiforgeRunHeap=1200M`, `online-mode=false`, RCON on 25575/25576, `server.bungeeCord=true`,
  `debug.logging=true`.
* `live1`/`live2` configs are `${serverDir}/config/multiforge-server.toml` (the config is now
  `COMMON`; see "Config path bug" below).
* Commands use `tools/compat/rcon.py` (copied to `.tools/rcon.py` for this run); the raw login
  client is a small Python implementation of protocol 763 (handshake + login start).

## Startup

```
[live1] Starting MultiForge as 'live1' (master 127.0.0.1:35353), sync: chunks=true, entities=true, players=true, world=true, bungeeCord=true
[live1] MultiForge ready (3 worlds, port 37269)
[live2] Starting MultiForge as 'live2' (master 127.0.0.1:35353), sync: chunks=true, entities=true, players=true, world=true, bungeeCord=true
[live2] MultiForge ready (3 worlds, port ...)
[live1] live1 | master=connected | tps=20.0 | chunks owned=38 loaded=529 | peers=1 | mirrored=0
[live2] live2 | master=connected | tps=20.0 | chunks owned=0 loaded=529 | peers=1 | mirrored=33
```

## BungeeCord / Velocity legacy forwarding

Raw handshake host `localhost\0 127.0.0.9 \0 12345678-1234-1234-1234-123456789abc \0 []`,
login name `BungeeTest`, `online-mode=false`:

```
LOGIN_SUCCESS uuid=12345678-1234-1234-1234-123456789abc name=BungeeTest
[live1] Forwarded connection from 127.0.0.9 (uuid 12345678-1234-1234-1234-123456789abc)
[live1] Applied forwarded profile for BungeeTest (uuid 12345678-1234-1234-1234-123456789abc)
```

The same connection was then kept open and `live2` reported the mirror copy:

```
[live2] There are 1 of a max of 20 players online: BungeeTest
```

## Main scoreboard

| Action on `live1` | Read-back on `live2` |
| --- | --- |
| `scoreboard objectives add mfv2 dummy` | objective list contains `[mfv2]` |
| `scoreboard players set Tester mfv2 41` (before connect) | snapshot on connect: `Tester has 41 [mfv2]` |
| `scoreboard players set Tester mfv2 52` (live) | `Tester has 52 [mfv2]` |
| `scoreboard players set Tester mfv2 63` (live) | `Tester has 63 [mfv2]` |
| `team add finalteam` / `team join finalteam Tester` | `Team [finalteam] has 1 member(s): Tester` |
| `scoreboard objectives setdisplay sidebar mfnew` | display slot forwarded |

Live-testing found three real bugs that compile-only checks could not:

1. Forge registers `SERVER` configs under `world/serverconfig/`, so the documented
   `config/multiforge-server.toml` was ignored after startup (`bungeeCord` reverted to false).
   The config is now `COMMON`, with a one-time migration from the legacy world path.
2. Forge's `ClientIntentionPacket` strips everything after the first NUL, so the handshake
   hook could not see forwarding data. `HandshakeAddressMixin` captures the raw host at the
   `readUtf` call site instead.
3. Vanilla 1.20.1 does not broadcast objective add/remove, and only broadcasts
   score/display/team packets to players that already track the main scoreboard. The sync now
   hooks the `ServerScoreboard` callbacks directly, and `ClientboundSetScorePacket`'s
   constructor is `(method, objectiveName, owner, score)` (the initial swapped arguments
   silently dropped scores).

## Other live checks

```
[live1] setblock 12 101 12 minecraft:gold_block
[live2] execute if block 12 101 12 minecraft:gold_block -> Test passed

[live1] time set 12000
[live2] time query daytime -> The time is 12021

[live2] execute as BungeeTest run multiforge map 2
Chunk ownership around 0,-1 (radius 2) - @ = this server, letters = other servers, . = unowned
LLLLL
LLLL@
LLLLL
LLLLL
LLLLL

[live1] execute as BungeeTest run multiforge borders  (config false)
Chunk border visualization is disabled (set debug.chunkBorders=true).
```

## Master leader election (two processes, one storage)

Two masters with `leader-election=true`, `leader-retry-millis=1000`, ports 39050/39051:

```
A: This master is the storage leader
B: Another master holds the storage lock; standing by
ports with both running: 39050 open, 39051 closed
(kill A)
t+1s: A process still running, 39050 closed
t+3s: A exited, B: This master is the storage leader, 39051 open
```

The old leader releases the lock only after its listener/event loop are shut down, so the
standby never binds while the previous term still owns a socket.

## Metrics

`GET http://127.0.0.1:35354/metrics` -> 200 with
`multiforge_uptime_seconds`, `multiforge_servers_online`, `multiforge_players_online`,
`multiforge_chunks_owned`, `multiforge_chunks_subscribed`,
`multiforge_entity_writes_pending`, `multiforge_next_entity_id`.

## Automated suites

`./gradlew build :multiforge-protocol:test :multiforge-master:test :multiforge-api:test`
passes 72 tests (protocol 30, master 38, api 4). Re-run after the live-test fixes:
protocol 36 (new Bungee parser/fuzz/progress tests), master 41 (leader election and download
progress), api 4.

## Known caveat from this run

`/reload` on Forge 47.4.29 did not fire `ModConfigEvent.Reloading` for the mod config, so
`onConfigReloaded` was not exercised live. The code path is wired to that event and covered by
compilation; in practice a config change still needs a restart on this Forge version.
