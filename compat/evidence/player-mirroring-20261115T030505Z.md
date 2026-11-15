# Player mirroring evidence 20261115T030505Z

Two Forge 47.4.29 dev servers (`server72789`, `server59221`) plus one MultiForge master on
`127.0.0.1:35353`, built from this working tree. The clients are headless
`minecraft-protocol` 1.68.0 bots (offline mode), not a graphical client, so the checks are
server state, `/list`, tab-list packets and entity packets rather than screenshots. The bot
tooling lives in `.tools/mcbot` and is not committed.

## Startup and cluster state

```
[server1] Master connection established, re-subscribing chunks
[server1] Starting MultiForge as 'server72789' (master localhost:35353), sync: chunks=true, entities=true, players=true, world=true
[server1] MultiForge ready (3 worlds, port 39513)
[server2] Starting MultiForge as 'server59221' (master localhost:35353), sync: chunks=true, entities=true, players=true, world=true
[server2] MultiForge ready (3 worlds, port 33089)
```

```
/multiforge status
server72789 | master=connected | tps=20.0 | chunks owned=440 loaded=529 | peers=1 | mirrored=0 | io reads=2025 writes=0 failures=0
server59221 | master=connected | tps=20.0 | chunks owned=371 loaded=529 | peers=1 | mirrored=0 | io reads=2025 writes=0 failures=0
```

## Bot joins server 1, appears on server 2

```
[bot] LOGIN MirrorBot                  (127.0.0.1:25565)
[server1] MirrorBot[/127.0.0.1:...] logged in with entity id 303 at (424.5, 77.0, 70.5)

[server2] /forceload add 416 64 431 79
Marked chunk [26, 4] in minecraft:overworld to be force loaded
[server2] Mirroring player MirrorBot (8875a618-34fa-3484-b53e-b4db81f2e35c) from server72789

[server2] /list
There are 1 of a max of 20 players online: MirrorBot
```

The player chunk was not loaded on server 2 initially; force-loading it triggered the entity
subscription, and the owner sent the create then. A normal player walking into view does the
same through `onEntitySubscriberAdded`.

## Position and rotation follow the owner

```
[server1] /tp MirrorBot 429 77 70
Teleported MirrorBot to 429.500000, 77.000000, 70.500000
[server1] /data get entity MirrorBot Pos
MirrorBot has the following entity data: [429.5d, 77.0d, 70.5d]
[server2] /data get entity MirrorBot Pos
MirrorBot has the following entity data: [429.5d, 77.0d, 70.5d]
```

## Tab list entry and client-side spawn on the mirror

A second bot (`Observer`) connected to server 2. It received the initializing player-info
packet for the mirrored player before spawning:

```
[observer] PLAYER_INFO {"add_player":true,"update_listed":true,...}
           [{"uuid":"8875a618-34fa-3484-b53e-b4db81f2e35c",
             "player":{"name":"MirrorBot","properties":[]},"gamemode":0,"listed":1,"latency":0}]

[observer] NAMED_ENTITY_SPAWN 37003 8875a618-34fa-3484-b53e-b4db81f2e35c 430.5 77 70.5
[observer] MOVE rel_entity_move {"entityId":37003,"dX":-4096,"dY":0,"dZ":0,"onGround":true}
```

`dX=-4096` is the vanilla 1/4096-block delta encoding of the teleport above. The observer
keeps receiving periodic movement packets for the mirrored entity id.

## Damage on the mirror is forwarded to the owner

```
[server2] /damage MirrorBot 3 minecraft:generic
Applied 3.0 damage to MirrorBot

[server1] /data get entity MirrorBot Health -> 16.499998f   (was 19.499998f)
[server2] /data get entity MirrorBot Health -> 16.499998f
```

The hit was applied on server 2 (where no connection exists), forwarded with
`EntityHurtPacket`, applied by the owner, and the owner's metadata update reached the mirror.
Before `ServerPlayerHurtMixin`, `ServerPlayer.hurt` rejected the hit locally as invulnerable
and never forwarded it.

## Both directions

`Observer` connected to server 2 and the same path mirrored it on server 1:

```
[server1] Mirroring player Observer (11f4f517-853c-3cf3-a01b-95809db70061) from server59221
[server1] /list -> There are 2 of a max of 20 players online: MirrorBot, Observer
[server2] /list -> There are 2 of a max of 20 players online: MirrorBot, Observer
```

## Logout removes the copy but keeps the owner's chunk

```
[kill MirrorBot]
[server1] MirrorBot lost connection: Disconnected
[server1] MirrorBot left the game
[master]  8875a618-34fa-3484-b53e-b4db81f2e35c left server72789
[server2] /list -> There are 0 of a max of 20 players online:
```

The master line proves the previously unused `releasePlayer` path is now wired to
`PlayerLoggedOutEvent`; the empty `/list` proves the mirror copy and its tab entry were
removed. Rejoining immediately afterwards succeeded, so the claim was released.

## Final build smoke test

The packet layout was reduced to the fields that are actually applied (the unused optional
tab-list display-name JSON was removed) and the server was rebuilt. A final master + two
server run repeated the core checks on that build:

```
[server1] Mirroring player MirrorBot (8875a618-34fa-3484-b53e-b4db81f2e35c) from server80849
[server2] /list -> There are 1 of a max of 20 players online: MirrorBot
[server2] /damage MirrorBot 3 minecraft:generic
Applied 3.0 damage to MirrorBot
[server1] Health -> 13.499998f  (was 16.499998f)
[server2] Health -> 13.499998f
```

`./gradlew build :multiforge-protocol:test :multiforge-master:test` on the final tree:
`BUILD SUCCESSFUL`, 49 tests passed (21 protocol, 28 master).

## Shutdown

Both servers stopped cleanly after the checks:

```
[server1] Stopping MultiForge (server stopped)
[server2] Stopping MultiForge (server stopped)
```
