# Development

## Toolchain

* JDK 17 (the build uses a Java 17 toolchain; Gradle can provision one via the foojay
  resolver)
* Gradle 8.8 (wrapper included)
* ForgeGradle 6.0.x, Forge 47.3.0, MixinGradle 0.7.x, Mixin 0.8.5

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
./gradlew :multiforge-master:test     # real TCP integration tests against a master
```

The master tests start a master on an ephemeral port with a temporary storage root and a
`TestServerClient` that speaks the production protocol. They cover the distributed
invariants: single owner per chunk, ownership handover, concurrent lock races, disconnect
cleanup, subscriber sync, duplicate logins, file round-trips and data streams.

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

3. Second server. ForgeGradle does not create a working second server run configuration
   (custom runs need a main class FG only sets for `client`/`server`), so either:

   * copy the project and change `runs.server.workingDirectory`, or
   * start a second dev server with the same JVM command line as the first, from another
     directory:

     ```bash
     python3 - <<'PY'
     import json, os, subprocess
     args = json.load(open('/tmp/mf-dev-args.json'))   # saved from /proc/<pid>/cmdline
     env = dict(os.environ, MOD_CLASSES=
         '<project>/multiforge-forge/build/resources/main:<project>/multiforge-forge/build/classes/java/main')
     subprocess.Popen(args, cwd='<project>/multiforge-forge/run2', env=env)
     PY
     ```

     `MOD_CLASSES` is mandatory: without it FML does not see the mod and the mixin config
     fails to load.

   The durable way for a real cluster is to drop the built jar into a normal Forge server
   installation and run two copies with different `server-port` and `[server] name`.

## Configuration files

* Master: `multiforge-master.properties` next to the process (created on first start).
  `-Dmultiforge.master.storage=<dir>` sets the world/file root.
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
