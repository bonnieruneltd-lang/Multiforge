# Velocity plugin

MultiForge normally runs the master as a **separate process** and optionally its built-in
Minecraft proxy. This module is the port of MultiPaper's `MultiPaperVelocity`: it runs the
master **inside Velocity** and balances connecting players across the MultiForge backends by
average tick time. It is optional; the standalone master remains the default and the
recommended production deployment.

## Build and install

```bash
./gradlew :multiforge-velocity:build
# copy the self-contained jar (master + protocol + Netty + NBT/YAML/logging)
cp multiforge-velocity/build/libs/multiforge-velocity-<version>.jar \
   <velocity>/plugins/
```

Start Velocity once; it creates `plugins/multiforge-velocity/config.properties`:

```properties
port=35353          # control port the Forge servers connect to
balance-nodes=true  # send connects to the live backend with the lowest average tick time
```

The embedded master stores the shared world under
`plugins/multiforge-velocity/storage`. On every backend set:

```toml
[master]
    address = "proxy-host:35353"

[server]
    name = "live1"    # must equal the Velocity [servers] key
```

and in `velocity.toml`:

```toml
[servers]
live1 = "backend1:25565"
live2 = "backend2:25566"
try = ["live1"]
```

The plugin leaves connects to non-MultiForge servers (hubs, lobbies) alone. It does not start the
built-in Minecraft proxy, Prometheus endpoint or leader election; use the standalone master for
those. Player-info forwarding is the proxy's responsibility (tested with `none` and
`online-mode=false`).

## Verification

The plugin was loaded in Velocity 3.4.0 with two Forge 47.4.29 backends and no standalone
master: the embedded master accepted both servers, a headless client connected through the
proxy and was balanced from `try=live1` to `live2`, and the RCON cluster harness passed 18/18.
Details and log excerpts: `compat/evidence/velocity-plugin-20261122T080834Z.md`.

## Not ported

MultiPaper's **BungeeCord** plugin. The same design applies, but it is a separate proxy API and
was not part of this port. Use the standalone master or the built-in proxy with BungeeCord.
