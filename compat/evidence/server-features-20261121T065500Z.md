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
passes 83 tests (protocol 36, master 43, api 4) after the live-test fixes: Bungee parser,
protocol fuzzing and bounds guards, leader election, download progress, missing-file reply
and the scoreboard checks.

## Second pass: reload, entity sync, download progress

`/reload` on Forge 47.4.29 does not fire config reload events, so MultiForge now has its own
command. Live result (player connected to `live1`):

```
[live1] execute as BungeeTest run multiforge borders  -> Chunk border visualization on.
[live1] execute as BungeeTest run multiforge borders  -> Chunk border visualization off.
(local file: debug.chunkBorders=false)
[live1] multiforge reload -> MultiForge configuration reloaded from disk (restart-only values are logged).
[live1] execute as BungeeTest run multiforge borders  -> Chunk border visualization is disabled (set debug.chunkBorders=true).
[live1] MultiForge configuration reloaded
```

Entity mirroring, damage forwarding and removal across the cluster:

```
[live1] summon minecraft:cow 5 -59 5            -> Summoned new Cow
[live2] execute if entity @e[type=minecraft:cow] -> Test passed, count: 1
[live2] data get entity @e[type=minecraft:cow,limit=1] Pos
        -> [5.205078125d, -60.0d, 5.794921875d]
[live1] damage @e[type=minecraft:cow,limit=1] 2 -> Applied 2.0 damage to Cow
[live2] ... Health -> 8.0f (from 10.0f)
[live1] kill @e[type=minecraft:cow]              -> Killed Cow
[live2] execute if entity @e[type=minecraft:cow] -> Test failed
```

Download progress (6 MiB shared file, `/multiforge download progress-test.bin`):

```
Downloading 'progress-test.bin': 16% (1048576 of 6291456 bytes)
Downloading 'progress-test.bin': 33% (2097152 of 6291456 bytes)
Downloading 'progress-test.bin': 50% (3145728 of 6291456 bytes)
Downloading 'progress-test.bin': 66% (4194304 of 6291456 bytes)
Downloading 'progress-test.bin': 83% (5242880 of 6291456 bytes)
Downloading 'progress-test.bin': 100% (6291456 of 6291456 bytes)
```

The downloaded file was exactly 6,291,456 bytes. A missing file now returns
`FileContentMessage(..., streamId=-1)` immediately instead of a master stack trace and a
requester timeout; a `../../etc/passwd` request is rejected by the master's normalized shared
root (`Shared path escapes the sync root`). Covered by
`MasterIntegrationTest.missingSharedFileIsReportedWithoutHanging` and the master log.

## Known caveat from this run

The Forge `/reload` command does not reload mod configs on 47.4.29; use
`/multiforge reload` (verified live). Values bound/negotiated once (peer port, server name,
existing peer compression, scheduler periods) still need a restart and are logged as such.

## Automated harness

`tools/compat/cluster_check.py --skip-mod-checks` ran against this dev cluster and passed
**18/18** checks, including scoreboard, entity and sign checks: `compat/evidence/cluster-20261121T072841Z.md`.

## Proxy and player-data transfer

`tools/compat/proxy_transfer_check.py` logged in a raw 1.20.1 client through the built-in
proxy, gave it diamonds, disconnected, reconnected directly to the other server and read the
inventory back:

```
[PASS] proxy-login: cluster owner=live1
[PASS] inventory-on-first-server: [{Slot: 0b, id: "minecraft:diamond", Count: 3b}]
[PASS] logout-releases-claim: cluster registry no longer lists the player
[PASS] reconnect-other-server: cluster owner=live2 (port 25566)
[PASS] player-data-follows-transfer: [{Slot: 0b, id: "minecraft:diamond", Count: 3b}]
RESULT 5/5 checks passed
```

This found two more bugs: servers advertised their **peer** listener port in `StartMessage`, so
the proxy connected to the peer protocol (`Unknown peer packet id 763`); `StartMessage` now
carries the game port and `SetPortMessage`/`ServerStartedMessage` carry the peer port. IP-only
forwarding also rebuilt the login profile with the null UUID that vanilla has before
`handleAcceptedLogin`; it now leaves the profile alone (or uses vanilla's offline UUID when
properties are forwarded), which fixed player-data transfer.

## Velocity modern forwarding

`tools/compat/velocity_forwarding_check.py` simulated Velocity's modern forwarding:

```
login plugin request channel=velocity:player_info transaction=2
requested forwarding version=1
sent signed answer (72 bytes, uuid 730c6503-3592-4391-8efd-53ee997988e4)
LOGIN_SUCCESS uuid=730c6503-3592-4391-8efd-53ee997988e4 name=VelocityBot
PASS Velocity modern forwarding applied
```

The run found that `ServerboundCustomQueryPacket` carries the answer as **all remaining
bytes** after the presence flag, without a length prefix; the simulated proxy initially sent
one and the HMAC shifted by a byte. The backend pauses `ServerLoginPacketListenerImpl.tick`
until the signed answer is verified, then applies the address, UUID and properties.
