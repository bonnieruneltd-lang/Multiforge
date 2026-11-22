# Velocity plugin evidence 2026-11-22

The port of MultiPaper's proxy-hosted master (`MultiPaperVelocity`) was built and exercised on
this tree. The standalone master remains the default; the plugin is optional.

## Build

```
./gradlew :multiforge-velocity:build
multiforge-velocity/build/libs/multiforge-velocity-1.0.0-alpha.1.jar  (7.1 MB, self-contained)
```

## Live run

Environment: Velocity **3.4.0** (Java 17), plugin in `plugins/`, two Forge **47.4.29** dev
servers `live1` (game 25565, RCON 25575) and `live2` (25566/25576), both configured with
`[master] address = "127.0.0.1:35353"` and **no standalone master process**. Velocity's
`try = ["live1"]`, plugin `balance-nodes=true`, forwarding mode `none`.

```
[Velocity] Loaded plugin multiforge-velocity 1.0.0 by MultiForge contributors
[MultiForgeMaster] Embedded master starting: port 35353, storage plugins/multiforge-velocity/storage
[Velocity] Listening on /0.0.0.0:25577
[MultiForgeMaster] MultiForge master listening on 0.0.0.0:35353
[MultiForgeMaster] Connection from /127.0.0.1:58086 (live1)
[MultiForgeMaster] live1 (game port 25565, peer port 44651) has started ...
[MultiForgeMaster] Connection from /127.0.0.1:53024 (live2)
[MultiForgeMaster] live2 (game port 25566, peer port 44373) has started ...
```

A headless client connected to Velocity on 25577:

```
[bot] LOGIN ProxyBot
```

**Balancing:** `try` names `live1`, but after the connect `live2` reported
`chunks owned=204, mirrored=0` and `live1` reported `chunks owned=0, mirrored=83`, so the
plugin moved the player to the backend with the lower average tick time. Both servers stayed at
20 TPS and saw one player (the other has the mirror copy).

The full RCON harness passed **18/18** against this proxy-hosted cluster: master connected,
membership, blocks both directions, chest inventory, scoreboard, entity mirror/damage/removal,
sign, time, gamerule, single owner, 20 TPS and save-all
(`compat/evidence/cluster-20261122T080834Z.md`).

## Limits

* Only the **Velocity** plugin is ported; MultiPaper's BungeeCord plugin is not. The standalone
  master and the built-in proxy remain the recommended production deployments.
* Embedded mode does not start the built-in Minecraft proxy (Velocity is the proxy), the
  Prometheus endpoint or leader election; use the standalone master for those.
* Velocity's `[servers]` names must equal the Forge servers' `server.name` values (the plugin
  matches them to master connections). Player-info forwarding is left to the proxy; this run
  used `none` with `online-mode=false`, exactly like the test cluster.
* The plugin is built as a self-contained jar (master + protocol + Netty + NBT/YAML/logging);
  rebuild it when those modules change.
