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
| Master protocol (86 message types, data streams, compression) | implemented, unit tested |
| Chunk data plane through the master (region, entities, poi) | implemented, verified live |
| Chunk ownership and tick gating | implemented, verified live |
| Block and block entity broadcasts | implemented |
| Peer-to-peer chunk transfer with compression | implemented, verified live |
| Server registry, heartbeats, TPS/player counts, global player list | implemented |
| Player data, statistics, advancements through the master | implemented |
| level.dat / uid.dat / whitelist / ops / file sync | implemented |
| Time, weather, gamerules, world border sync | implemented |
| Cluster-wide chat | implemented |
| Recovery after master or server disconnects | implemented (see KNOWN_ISSUES) |
| Live entity/player packet mirroring between servers | partial (see [docs/TODO.md](docs/TODO.md)) |
| Inventory/ender chest/experience sync | not yet ported |

Read [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) before running a production world.

## Requirements

* Java 17
* Forge 1.20.1 (47.x) dedicated server
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
`/multiforge chunks`, `/multiforge download <path>` (aliases `/mfservers`, `/mfslist`).

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

## License

MIT. MultiForge is derived from MultiPaper (also MIT); see `LICENSE`.
