# Proxy plugins (Velocity and BungeeCord)

MultiForge normally runs the master as a **separate process** and optionally its built-in
Minecraft proxy. These optional modules are the port of MultiPaper's proxy-hosted master: they
run the master **inside Velocity or BungeeCord** and balance connecting players across the
MultiForge backends by average tick time. The standalone master remains the default and the
recommended production deployment.

## Velocity

```bash
./gradlew :multiforge-velocity:build
cp multiforge-velocity/build/libs/multiforge-velocity-<version>.jar <velocity>/plugins/
```

The self-contained jar contains the master, protocol, Netty, NBT/YAML and logging. On first
start it creates `plugins/multiforge-velocity/config.properties`:

```properties
port=35353          # control port the Forge servers connect to
balance-nodes=true  # send connects to the live backend with the lowest average tick time
```

The world lives under `plugins/multiforge-velocity/storage`. Backends use:

```toml
[master]
    address = "proxy-host:35353"

[server]
    name = "live1"    # must equal the Velocity [servers] key
```

and `velocity.toml`:

```toml
[servers]
live1 = "backend1:25565"
live2 = "backend2:25566"
try = ["live1"]
```

## BungeeCord

```bash
./gradlew :multiforge-bungee:build
cp multiforge-bungee/build/libs/multiforge-bungee-<version>.jar <bungee>/plugins/
```

`plugins/MultiForgeBungee/config.yml`:

```yaml
port: 35353
balanceNodes: true
```

The world lives under `plugins/MultiForgeBungee/storage`. Backend names in the proxy's
`config.yml` must equal the Forge servers' `server.name`; the control address is the same
`proxy-host:35353` as for Velocity. `/server <name>` with the
`multiforge.directserverconnect` permission bypasses balancing once.

**Version note:** BungeeCord's current build targets a newer Minecraft protocol and rejects a
1.20.1 client. Use **Waterfall 1.20** (the BungeeCord fork for 1.20) for 1.20.1 Forge backends;
the plugin uses only the stable BungeeCord API and loads unchanged.

## Shared behaviour and limits

* Connects to non-MultiForge servers (hubs, lobbies) are left to the proxy.
* Embedded mode does not start the built-in Minecraft proxy, Prometheus endpoint or leader
  election; use the standalone master for those.
* Player-info forwarding is the proxy's responsibility. The test clusters used
  `online-mode=false` with forwarding disabled.
* Rebuild the plugin jar when the master/protocol modules change.

## Verification

Both plugins were exercised with two Forge 47.4.29 backends and **no standalone master**:

* Velocity 3.4.0: embedded master accepted both backends, a headless client connected through
  the proxy and was balanced from `try=live1` to `live2`, cluster 18/18
  (`compat/evidence/velocity-plugin-20261122T080834Z.md`).
* Waterfall 1.20 build 578: plugin loaded unchanged, embedded master accepted both backends, a
  headless client joined through the proxy, cluster 18/18
  (`compat/evidence/bungee-plugin-20261122T082425Z.md`).

The cluster harness now selects the player's own chunk on either backend (and waits for
ownership), so it works behind a proxy; this is a test-harness change only.

