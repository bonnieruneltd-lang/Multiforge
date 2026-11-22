# MultiForge

**MultiForge is a direct Forge 1.20.1 port of [MultiPaper](https://github.com/MultiPaper/MultiPaper):
run one Minecraft world across many Forge servers.**

MultiPaper lets a network of Paper servers share a single world. Each server loads the
chunks its players need, the servers keep each other's copies in sync, and a central
*Master* process stores the world and decides who ticks which chunk. MultiForge brings
the same architecture to **Minecraft Forge 1.20.1 / Forge 47.x on Java 17**, replacing
Paper's patches with Forge APIs and Mixins.

```
            +------------------------+
            |   MultiForge Master    |  world storage + ownership arbitration
            |  (standalone process)  |
            +-----------+------------+
                        |  control connection (Netty, binary protocol)
        +---------------+----------------+
        |                                |
+-------v--------+               +-------v--------+
| Forge server 1 |<-- peer ----->| Forge server 2 |   chunk data, block/entity
|  (MultiForge)  |     TCP       |  (MultiForge)  |   updates, chat, world state
+----------------+               +----------------+
```

## Status

This is an **alpha** port. The core distributed systems are implemented and verified by
integration tests and by running two real Forge servers against a master:

| Area | Status |
| --- | --- |
| Master process (storage, ownership, subscriptions, proxy, CLI) | implemented |
| Master protocol (90 message types, data streams, compression) | implemented, unit tested |
| Chunk data plane through the master (region, entities, poi) | implemented, verified live (previous session) |
| Chunk generation lease (one generator per never-seen chunk) | implemented, integration tested |
| Chunk ownership and tick gating | implemented, verified live (previous session) |
| Fenced chunk handover / player-follow ownership | implemented; 8 master integration tests and verified live on two dev servers with bots walking across the border ([evidence 1](compat/evidence/chunk-handover-20261122T035205Z.md), [evidence 2](compat/evidence/handover-mirroring-20261122T040431Z.md)) |
| Chunk unload status respected when redirecting reads | implemented, integration tested |
| Block and block entity broadcasts | implemented |
| Peer-to-peer chunk transfer with compression | implemented, verified live (previous session) |
| Server registry, heartbeats, TPS/player counts, global player list | implemented |
| Player data, statistics, advancements through the master | implemented |
| level.dat / uid.dat / shared files / real-time file sync | implemented (restored this session) |
| User lists (whitelist/ops/bans) | file sync + live reload (no restart) |
| Time, weather, gamerules, world border, difficulty/spawn sync | implemented; weather is master-sequenced with a per-world version and live-verified ([evidence](compat/evidence/weather-ordering-20261122T042413Z.md)); peer fallback while the master is down |
| Cluster-wide chat | implemented |
| Entity id block allocation | implemented, integration tested |
| Recovery after master or server disconnects | implemented; master-outage backlog live-verified (47 chunks each, drained to 0, local region deleted and reloaded from the master) ([evidence](compat/evidence/offline-backlog-20261122T041340Z.md)) |
| Live entity mirroring (mobs, items, vehicles; movement/metadata/removals/damage) | implemented, verified on a two-server cluster (see [compat/evidence/entity-mirroring-20261111T212200Z.md](compat/evidence/entity-mirroring-20261111T212200Z.md)) |
| Player mirroring across servers (tab list, movement, metadata, damage, logout) | implemented, verified on a two-server cluster with headless clients; mirrors are re-announced every 5 s so a teleport into an already-subscribed chunk cannot leave a server without a copy (see [compat/evidence/player-mirroring-20261115T030505Z.md](compat/evidence/player-mirroring-20261115T030505Z.md)) |
| Live player state (inventory, ender chest, hotbar, hunger, experience, effects, tab-list name/game mode) | implemented, verified on a two-server cluster (see [compat/evidence/player-state-20261115T054356Z.md](compat/evidence/player-state-20261115T054356Z.md) and [game-mode live run](compat/evidence/handover-mirroring-20261122T040431Z.md)) |
| Cross-server container windows (chests, furnaces, ...) | implemented; open/click/close verified on a two-server cluster (see [compat/evidence/containers-epochs-userlists-20261115T074200Z.md](compat/evidence/containers-epochs-userlists-20261115T074200Z.md)) |
| Ownership epochs on block/block-entity packets | implemented, master + protocol + master integration test |
| Cross-server attacker attribution (death messages, kill credit) | implemented: causing/direct UUIDs + source position |
| Main scoreboard (objectives, scores, teams, display slots) | implemented and verified live on two dev servers (peer packet 30; snapshot + incremental) |
| Live statistics and advancement progress | implemented: 10 s statistic deltas + immediate criterion awards (peer packets 31-32); compile-verified, live check pending |
| Master region file format | true zlib, readable by vanilla/tooling; legacy gzip transcoded on read (round-trip tested + live master bytes checked) |
| Boss bars (dragon, raids, plugin bars) | implemented: owner forwards vanilla boss-event packets; verified live with two clients receiving the same add/progress/remove ([evidence](compat/evidence/boss-bar-20261122T044950Z.md)) |
| Required-target detection and `/multiforge compat` | implemented |
| Chunk-border debug visualization (`/multiforge borders`) | implemented (config `debug.chunkBorders`); compile-verified |
| Prometheus `/metrics` endpoint for the master | implemented (`metrics-port`, default off); unit tested |
| Protocol input hardening + fuzzing | implemented: bounds-checked lengths, random/malformed-payload tests |
| BungeeCord / Velocity forwarding | legacy IP+UUID+profile forwarding verified live (`server.bungeeCord`); Velocity **modern** forwarding (HMAC login plugin) implemented and verified with a simulated proxy (`server.velocityModern`) |
| External proxy / built-in proxy load balancing | built-in proxy verified live (raw 1.20.1 login + IP forwarding); external BungeeCord/Velocity documented |
| Master leader election for shared storage | implemented (`leader-election`, default off); unit tested |
| Download progress reporting | implemented: `DownloadProgressMessage` every MiB, shown in `/multiforge status`; integration tested |
| Chunk-ownership text map (`/multiforge map`) | implemented; compile-verified |
| Pinned compatibility manifest and two-server RCON harness | implemented; 35 targets pinned, server set tested together |
| Required mod compatibility (35 targets) | All 35 exercised: server 20/20 (5 functional, 11 partial, 4 loading) and client 15/15 launched in a production Forge client under Xvfb/llvmpipe (4 functional, 11 partial) - see [docs/REQUIRED_MOD_COMPATIBILITY.md](docs/REQUIRED_MOD_COMPATIBILITY.md) |

Read [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) before running a production world, and
[docs/DEVELOPMENT_HANDOFF.md](docs/DEVELOPMENT_HANDOFF.md) for the current audit.

## Requirements

* Java 17
* Forge 1.20.1 (47.x; built and tested against 47.4.29)
* One MultiForge master process (Java 17, no Minecraft)

## Quick start

1. Build everything:

   ```bash
   ./gradlew build :multiforge-master:fatJar
   ```

2. Start the master in the directory that will hold the world:

   ```bash
   java -Dmultiforge.master.storage=/srv/multiforge -jar \
        multiforge-master/build/libs/multiforge-master-1.0.0-alpha.1-all.jar 35353
   ```

3. Install `multiforge-forge/build/libs/multiforge-1.0.0-alpha.1.jar` into each Forge
   server's `mods/` directory, start the server once, then edit
   `config/multiforge-server.toml`:

   ```toml
   [master]
   address = "your-master-host:35353"

   [server]
   name = "survival1"          # unique per server
   ```

4. Point your load balancer (or the master's built-in proxy) at the servers. Players can
   join any server; their data follows them.

## Commands

`/multiforge status`, `/multiforge servers`, `/multiforge players`, `/multiforge chunk`,
`/multiforge chunks`, `/multiforge map [radius]`, `/multiforge borders`, `/multiforge reload`,
`/multiforge compat`, `/multiforge download <path>` (aliases `/mfservers`, `/mfslist`).
`/multiforge borders` requires `debug.chunkBorders = true`; `/multiforge reload` re-reads
`config/multiforge-server.toml` without a restart.

## Documentation

* [Architecture](docs/ARCHITECTURE.md)
* [Networking](docs/NETWORKING.md)
* [Synchronization](docs/SYNCHRONIZATION.md)
* [Chunk management](docs/CHUNK_MANAGEMENT.md)
* [Porting notes](docs/PORTING_NOTES.md)
* [Development](docs/DEVELOPMENT.md)
* [TODO](docs/TODO.md)
* [Known issues](docs/KNOWN_ISSUES.md)
* [Agent guide](docs/AGENT_GUIDE.md)
* [Development handoff](docs/DEVELOPMENT_HANDOFF.md)
* [Required mod compatibility](docs/REQUIRED_MOD_COMPATIBILITY.md)
* [Distributed architecture research](docs/DISTRIBUTED_ARCHITECTURE_RESEARCH.md)
* [Distributed roadmap](docs/DISTRIBUTED_ROADMAP.md)

## License

MIT. MultiForge is derived from MultiPaper (also MIT); see `LICENSE`.
