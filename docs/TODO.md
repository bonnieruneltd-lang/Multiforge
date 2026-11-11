# TODO

Ordered by value for a production cluster. Items marked **blocked** need design work before
coding.

## High priority

1. **Live entity mirroring between servers** (blocked on design). Servers that do not own a
   chunk should spawn lightweight copies of entities owned elsewhere and apply position /
   rotation / metadata updates received from the owner. This is MultiPaper's
   `EntityUpdatePacket`, `EntityRemovePacket`, `EntityUpdateWithDependenciesPacket` family.
   Without it players only see stale entities while another server owns their chunk.
2. **External player mirroring** (`ExternalPlayer` in MultiPaper). Needed so that players see
   each other across servers (tab list is already cluster-wide, world presence is not).
   Requires a fake `ServerPlayer` per remote player with packet forwarding.
3. **Inventory / ender chest / experience / hunger / effects syncing**
   (`MultiPaperInventoryHandler`, `MultiPaperEnderChestHandler`, `MultiPaperEffectsHandler`).
   Player data files are already synchronized; live changes while a player is online on
   another server are not.
4. **Entity id allocation** (`RequestEntityIdBlock` exists in the protocol but the master
   handler is a stub). Needed to avoid id collisions when entities move between servers.
5. **Peer packet coverage**: port the remaining MultiPaper packets (teleport, hurt, equipment,
   mob navigation handover, raids, dragon fight, projectile hits, container interactions,
   sign editing, pistons, statistics/advancement live updates).
6. **Chunk status protocol**: MultiPaper uses `ServerChangedChunkStatusMessage` to know when a
   chunk is unloading; MultiForge sends load/unload but does not yet use the information to
   avoid requesting a chunk that is mid-unload.

## Medium priority

7. **Run configuration for a second dev server.** FG6 does not create a usable `runServer2`
   (no main class). Either add a Gradle task that launches a second JVM with `MOD_CLASSES` or
   document a proper distribution-based workflow.
8. **Scoreboards and boss bars** (`MultiPaperConfig.syncScoreboards` in the original).
9. **Permissions/op synchronization** (`MultiPaperPermissionSyncer`). Ops JSON is synced; live
   op changes are not broadcast as packets.
10. **Advancement/stat live updates**: files are uploaded on save; MultiPaper also sends
    incremental updates.
11. **Weather/time conflict resolution**: currently last-writer-wins per second. A logical
    clock owned by the master would avoid drift.
12. **Master high availability**: multiple masters sharing storage are supported by the file
    locks, but there is no leader election; the built-in proxy would also need it.
13. **Built-in proxy player balancing**: the proxy currently picks a live server by load; the
    original can also use BungeeCord/Velocity. Verify reconnects and protocol edge cases.
14. **Config hot reload**: `bake()` updates the snapshot but managers cache values (e.g. peer
    compression). Recreate affected components on reload.

## Low priority / polish

15. `debug.chunkBorders` (in-game ownership visualisation) is declared but not implemented.
    Implement it or remove the option (do not leave it as a silent no-op).
16. Metrics/observability endpoint (Prometheus) for the master.
17. Structured progress reporting for large file downloads.
18. More protocol fuzz tests (truncated frames, invalid ids, oversized arrays).
19. A `multiforge-api` example mod and integration test.
20. Port `MultiPaper's` `/mpmap` and `/mpdebug` visualisations.

## Done (for reference)

* Master protocol, master process, proxy, CLI.
* Master storage refactor (`MasterStorage`) and startup TOML reader.
* Chunk/entity/POI data plane with peer transfer and local fallback.
* Ownership gating for chunks, entities, block and fluid ticks.
* Block and block entity broadcasts.
* Player data, duplicate login prevention, stats, advancements, cluster player list, chat.
* level.dat, uid.dat, time, weather, gamerules, world border, JSON/shared file sync.
* Commands, public API, documentation, protocol and master integration tests.
