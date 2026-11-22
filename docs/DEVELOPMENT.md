# Development

## Toolchain

* JDK 17 (the build uses a Java 17 toolchain; Gradle can provision one via the foojay
  resolver)
* Gradle 8.10.2 (wrapper included) - required; ForgeGradle 6.0.54 fails on Gradle 8.8 with
  "Project services has been closed"
* ForgeGradle 6.0.x, Forge 47.4.29, MixinGradle 0.7.x, Mixin 0.8.5

## Build

```bash
./gradlew build                      # all modules + tests
./gradlew :multiforge-master:fatJar  # self contained master jar
./gradlew :multiforge-forge:build     # reobfuscated mod jar
```

Artifacts:

* `multiforge-forge/build/libs/multiforge-<version>.jar` is the mod jar. It contains the
  protocol and API classes and the generated `multiforge.refmap.json`.
* `multiforge-master/build/libs/multiforge-master-<version>-all.jar` is the standalone
  master (protocol + Netty + log4j + NBT + YAML).

## Tests

```bash
./gradlew :multiforge-protocol:test   # wire format, message round-trips, data streams
./gradlew :multiforge-master:test     # real TCP integration against a master
```

The master tests start a master on an ephemeral port with a temporary storage root and a
`TestServerClient` that speaks the production protocol. They cover the distributed
invariants: single owner per chunk, ownership handover, concurrent lock races, disconnect
cleanup, subscriber sync, duplicate logins, file round-trips, data streams, chunk unload status
and entity id block allocation.

## Running a cluster locally

1. Build the master and start it (storage root is configurable):

   ```bash
   java -Dmultiforge.master.storage=/tmp/mf-master -jar \
        multiforge-master/build/libs/multiforge-master-<version>-all.jar 35353
   ```

2. First server:

   ```bash
   ./gradlew :multiforge-forge:runServer
   ```

   The config is `run/config/multiforge-server.toml`; set
   `[server] name = "server1"`.

3. Second server. The `server2` run inherits the main class, module path, `MOD_CLASSES` and
   arguments from the `server` run (ForgeGradle only fills those in for inherited runs).
   For two servers in a small container, cap each dev JVM with
   `-PmultiforgeRunHeap=1200M` (the default is 2 GB per server):

   ```bash
   mkdir -p multiforge-forge/run2
   printf 'eula=true\n' > multiforge-forge/run2/eula.txt
   cp multiforge-forge/run/server.properties multiforge-forge/run2/server.properties
   # change server-port in run2/server.properties, then:
   ./gradlew :multiforge-forge:runServer2
   ```

   `run2/config/multiforge-server.toml` is created by Forge on the first start; set a unique
   `[server] name`. Run the two Gradle commands in separate terminals (each needs its own
   daemon/console).

   The durable way for a real cluster is to drop the built jar into a normal Forge server
   installation and run two copies with different `server-port` and `[server] name`.

## Required-mod compatibility environment

The pinned target manifest is generated and downloaded with:

```bash
python3 tools/compat/resolve_required_mods.py --write
python3 tools/compat/resolve_required_mods.py --write --download .runtime/server-mods
python3 tools/compat/resolve_required_mods.py --write --download-client .runtime/client-mods
```

Install the Forge 47.4.29 dedicated server, copy
`multiforge-forge/build/libs/multiforge-1.0.0-alpha.1.jar` and every
`.runtime/server-mods/*.jar` into `mods/`, put `UltimateAntiSeedCracker.zip` in
`world/datapacks/`, and enable RCON (`enable-rcon`, `rcon.port`, `rcon.password`).
Two servers plus one master, then:

```bash
python3 tools/compat/cluster_check.py --a-port 25575 --b-port 25576 \
    --password <rcon-password> --expected-targets 19 --out compat/evidence
```

Use `--expected-targets 18` when Ksyxis is removed. The harness needs both servers to hold
one common chunk; it reads `/multiforge chunks` to find it. It also verifies scoreboard
objectives, scores and teams, plus entity mirroring, forwarded damage and removal; add
`--skip-mod-checks` to run it on a dev cluster without the pinned server mods.
`tools/compat/proxy_transfer_check.py` logs a headless client in through the master's
built-in proxy, gives it an item, moves it to the other server and asserts the item follows;
it also found and covers the game-port/peer-port split. The transcript it writes is the
evidence for `docs/REQUIRED_MOD_COMPATIBILITY.md`.

**Never copy a new jar into `mods/` while the server is running.** Stop it, copy, start it;
otherwise lazy class loading can throw `ClassNotFoundException` from the replaced jar.

## Live player-state check

Player mirroring and the live player-state sync (inventory, ender chest, hotbar, hunger,
experience, effects) are verified with a headless bot and RCON, without a graphical client:

```bash
# Node 18+; the bot is development tooling and is not committed with node_modules.
npm install minecraft-protocol@1.68.0
node tools/compat/mcbot.js 127.0.0.1 25565 MirrorBot 25599 &

python3 tools/compat/player_state_check.py --password <rcon-password>
```

The bot connects to the first server; the second server creates the mirror when it loads the
player's chunk (the spawn chunks are loaded on both). The script changes the owner's state with
vanilla commands and with a serverbound `held_item_slot` packet, then compares `/data get
entity` output on both servers. It exits non-zero when a check fails and prints the transcript
used as evidence in `compat/evidence/`. See
`compat/evidence/player-state-20261115T054356Z.md` for a recorded run.

The same bot now also sends `dig`, `place`, `drop`, `command`, `attackplayer` and logs
`game_state_change`/chat packets, which is what the player-driven compatibility checks use
(e.g. Timber/FastLeafDecay, TPA and Beautified Chat in
`compat/evidence/gameplay-mods-20261122T043800Z.md`).

## Live fight-state check

Raid and dragon-fight replication and handover are verified with two headless bots and RCON:

```bash
node tools/compat/mcbot.js 127.0.0.1 25565 SyncBotA 25599 &
node tools/compat/mcbot.js 127.0.0.1 25566 SyncBotB 25600 &
python3 tools/compat/fight_sync_check.py --a-port 25575 --b-port 25576 \
    --password <rcon-password> --out compat/evidence
```

The script teleports the bots to the raid first (when a raid is active) and then to the end,
asserts that both servers report the same raid id/wave and dragon UUID/phase with exactly one
`local=true`, then moves the owner's bot away and the other bot in to trigger two fenced
handovers per fight, checking the epoch advances and the state is preserved. It exits non-zero
on failure. Evidence: `compat/evidence/fight-sync-20261122T074549Z.md` (14/14).

## Forced packet-loss check

`tools/compat/packet_loss_check.py` sets `/multiforge drop 50` on one server, places blocks and
a chest in a chunk that server owns, then verifies on the peer that the receiver observed
sequence gaps and that every block plus the chest NBT arrived through the retransmit path:

```bash
python3 tools/compat/packet_loss_check.py --a-port 25575 --b-port 25576 \
    --password <rcon-password> --out compat/evidence
```

The injector drops only the first transmission of block/block-entity updates; retransmissions
are never dropped, so a run always converges. It is a debug tool: the default is 0 and
`/multiforge status` reports the active percentage as `drop=`.

## Hardware client matrix

The 15 client targets can only be judged by a real client. `tools/compat/run_client_matrix.sh`
is the one-command wrapper: it probes the host (refusing a machine without a usable OpenGL
renderer), pins and SHA-512-verifies the client artifacts, assembles the instance, installs
the production Forge client, runs one client per shader pack and writes
`compat/evidence/client-matrix-<utc>.{md,json}`. A software-only machine can still run the
launch/log checks with `--allow-software` (labelled SOFTWARE; shader rows stay open). See
[docs/CLIENT_MATRIX.md](CLIENT_MATRIX.md) for the machine specification and the verdict rules.

## One-command live cluster

`tools/ci/live_cluster.sh` boots the master, two Forge dev servers and the headless bots, runs
the compatibility, forced-loss and fight-state harnesses, and shuts everything down:

```bash
FRESH=1 tools/ci/live_cluster.sh                 # clean world, ~3 minutes
MODS_DIR=.runtime/server-mods tools/ci/live_cluster.sh   # optional mods copied to run/mods
HEAP=1200M RCON_PASSWORD=mfcompat tools/ci/live_cluster.sh
```

It writes evidence to `compat/evidence/` and logs to `.runtime/live-cluster/`, and exits
non-zero unless every harness passes. `tools/ci/live-cluster-workflow.yml` is the GitHub
Actions template (copy to `.github/workflows/live-cluster.yml`). The script replaces the manual
steps above for CI; use the manual procedure when debugging a single step.

## Running behind BungeeCord or Velocity

MultiForge servers can sit behind an external proxy. Two forwarding modes are supported:

* **Velocity modern forwarding** (recommended): set `player-info-forwarding-mode = "modern"`
in `velocity.toml`, copy `forwarding.secret` into `server.velocitySecret`, and set
`server.velocityModern = true`. The backend sends the `velocity:player_info` login plugin
request and verifies the HMAC-SHA256 answer (address, UUID, name, properties) before
accepting the login. Forwarding version 1 is requested; the key-bearing versions 2-4 are
not negotiated.
* **Legacy IP forwarding**: BungeeCord `ip_forward` or Velocity `bungeecord: true`, with
`server.bungeeCord = true` (see below). The two options are mutually exclusive.

1. Set `online-mode=false` in each backend `server.properties`. The proxy
authenticates the player; a backend in online mode would overwrite the forwarded
UUID during its own authentication. This applies to legacy forwarding too.
2. Set `server.bungeeCord = true` in each `config/multiforge-server.toml`.
3. Firewall the backend ports so only the proxy can reach them. Legacy forwarding
   is not authenticated: anyone who can connect directly can claim any IP/UUID.
4. Add the backends to the proxy with their names matching `server.name`, so the
   master's server list and the proxy's server list agree.

With the option enabled the handshake host (`host\0ip[\0uuid\0properties]`) is
parsed by `BungeeCordForwarding`, the connection address becomes the player's real
IP (bans, whitelist logs, `/multiforge players`) and the login profile is replaced
with the forwarded UUID and texture properties. All three shapes are accepted:
IP-only (MultiForge's built-in proxy sends this), IP+UUID and IP+UUID+properties.
A malformed or non-literal address is rejected and logged; the player then falls
back to the proxy address/offline UUID rather than getting an arbitrary one.

The master's built-in proxy (`proxy-port`) already sends IP-only forwarding, so a
cluster can use either the built-in proxy or an external BungeeCord/Velocity, not
both for the same player.

Master leader election is unrelated but often used with a proxy: if two masters
share one storage root, set `leader-election=true`; only the lock holder binds, the
standby waits and takes over when the leader exits.

## Continuous integration

`tools/ci/build-workflow.yml` is the GitHub Actions workflow (copy it to
`.github/workflows/build.yml` when adopting it; this repository's automation token cannot
create workflow files). It runs on every push and pull request: it provisions JDK 17 and
Gradle, runs `:multiforge-protocol:test :multiforge-master:test :multiforge-api:test`, then
`./gradlew build` (which includes the Forge reobfuscated jar and mixin refmap) and uploads
the jars. The live master + two-server check is still manual because it needs a Minecraft
server download, RCON and a disposable world.

## Configuration files

* Master: `multiforge-master.properties` next to the process (created on first start).
  `-Dmultiforge.master.storage=<dir>` sets the world/file root.
  `generation-lease-millis` / `generation-wait-millis` control the chunk generation
  lease (see [CHUNK_MANAGEMENT.md](CHUNK_MANAGEMENT.md)); `0` disables it.
* Server: `config/multiforge-server.toml` (Forge `COMMON` config, so the path is the same on
  every world; earlier builds registered it as `SERVER`, which Forge stored under
  `<world>/serverconfig/` and is migrated once on start-up). Values are read at start-up via a
  small TOML reader because MultiForge connects before Forge loads the config. On Forge
  47.4.29 `/reload` does not fire `ModConfigEvent.Reloading` for mod configs, so a config
  change needs a restart in practice; `MultiForgeServer.onConfigReloaded` applies it if the
  event does fire.

## Debugging

* `debug.logging = true` logs ownership requests/acquisitions, chunk loads and file syncs.
* `debug.chunkBorders = true` enables `/multiforge borders`; the command toggles particle
  lines on the current chunk border for the invoking player (green = owned here, red =
  another server, white = unowned). It is off by default because it sends particles twice a
  second.
* `/multiforge status` shows IO counters and peer count.
* `synchronization.scoreboards = true` (default) mirrors the main scoreboard;
  `statistics`/`advancements` also push live deltas, not only the save-time files.
* Master: set `metrics-port` and optionally `metrics-address` in
  `multiforge-master.properties` to expose Prometheus counters on `/metrics` (default
  disabled). Scrape it from a private interface; it has no authentication.
* The master logs every connection, start announcement, chunk lock request and error.
* If a Mixin fails to apply the server aborts during class transformation; the log names the
  mixin and the injection. Re-run `:multiforge-forge:build` to regenerate the refmap after
  changing a mixin target.

## Code conventions

* Java 17, 4 spaces, 120 column soft limit.
* Every public class and method has JavaDoc explaining what/why/callers/threading/side effects.
* No placeholder implementations: if something is not implemented, document it in TODO.md and
  do not expose a config option that pretends otherwise.
* New network fields are append-only; peer packet ids are append-only.
* Update the relevant `docs/*.md` in the same commit as the code change.
