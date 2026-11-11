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
./gradlew :multiforge-protocol:test   # 17 tests: wire format, message round-trips, data streams
./gradlew :multiforge-master:test     # 24 tests: real TCP integration against a master
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
   arguments from the `server` run (ForgeGradle only fills those in for inherited runs):

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
one common chunk; it reads `/multiforge chunks` to find it. The transcript it writes is the
evidence for `docs/REQUIRED_MOD_COMPATIBILITY.md`.

**Never copy a new jar into `mods/` while the server is running.** Stop it, copy, start it;
otherwise lazy class loading can throw `ClassNotFoundException` from the replaced jar.

## Configuration files

* Master: `multiforge-master.properties` next to the process (created on first start).
  `-Dmultiforge.master.storage=<dir>` sets the world/file root.
  `generation-lease-millis` / `generation-wait-millis` control the chunk generation
  lease (see [CHUNK_MANAGEMENT.md](CHUNK_MANAGEMENT.md)); `0` disables it.
* Server: `config/multiforge-server.toml` (Forge `SERVER` config). Values are read at
  start-up and re-baked on `/reload` (`ModConfigEvent`).

## Debugging

* `debug.logging = true` logs ownership requests/acquisitions, chunk loads and file syncs.
* `/multiforge status` shows IO counters and peer count.
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
