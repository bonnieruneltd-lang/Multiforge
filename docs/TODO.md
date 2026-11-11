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
4. **Entity id allocation** - done: `RequestEntityIdBlockHandler` hands out disjoint,
   persisted blocks (`lastblock.txt` in the master storage root) and rejects exhaustion.
   The mod does not request blocks yet; do that when entity mirroring is implemented.
5. **Peer packet coverage**: port the remaining MultiPaper packets (teleport, hurt, equipment,
   mob navigation handover, raids, dragon fight, projectile hits, container interactions,
   sign editing, pistons, statistics/advancement live updates).
6. **Chunk status protocol** - done: the master records load/unload status
   (`ChunkStatusTracker`) and does not redirect a read to a peer that is unloading; covered by
   `unloadingPeerIsNotChosenForChunkRead`. The client-side status view is still informational.
7. **Live user list application**: `FileSyncer` syncs whitelist/ops/bans through the master,
   but a running server keeps its in-memory `PlayerList` until restart. Add
   `StoredUserList`/`PlayerList` hooks or reload the lists after a `DataUpdateMessage`.

## Medium priority

8. **Run configuration for a second dev server** - done: the `server2` ForgeGradle run inherits
   the resolved launch configuration from `server` (`parent runs.server`) and runs from
   `run2/`; verified with two live servers in this environment. See DEVELOPMENT.md.
9. **Scoreboards and boss bars** (`MultiPaperConfig.syncScoreboards` in the original).
10. **Permissions/op synchronization** (`MultiPaperPermissionSyncer`). Ops JSON is synced; live
    op changes are not broadcast as packets (see item 7).
11. **Advancement/stat live updates**: files are uploaded on save; MultiPaper also sends
    incremental updates.
12. **Weather/time conflict resolution**: currently last-writer-wins per broadcast. A logical
    clock owned by the master would remove the one-tick jitter described in KNOWN_ISSUES.
13. **Master high availability**: multiple masters sharing storage are supported by the file
    locks, but there is no leader election; the built-in proxy would also need it.
14. **Built-in proxy player balancing**: the proxy currently picks a live server by load; the
    original can also use BungeeCord/Velocity. Verify reconnects and protocol edge cases.
15. **Config hot reload**: `bake()` updates the snapshot but managers cache values (e.g. peer
    compression). Recreate affected components on reload.

## Low priority / polish

16. `debug.chunkBorders` (in-game ownership visualisation) is declared but not implemented.
    Implement it or remove the option (do not leave it as a silent no-op).
17. Metrics/observability endpoint (Prometheus) for the master.
18. Structured progress reporting for large file downloads (the download itself is streamed).
19. More protocol fuzz tests (truncated frames, invalid ids, oversized arrays).
20. A `multiforge-api` example mod and integration test.
21. Port MultiPaper's `/mpmap` and `/mpdebug` visualisations.
22. Automate the live two-server check (container recipe) so it can run in CI.

## Done (for reference)

* Master protocol, master process, proxy, CLI.
* Master storage refactor (`MasterStorage`) and startup TOML reader.
* Chunk/entity/POI data plane with peer transfer and local fallback.
* Ownership gating for chunks, entities, block and fluid ticks.
* Chunk unload status used to avoid peer redirects (`ChunkStatusTracker`).
* Entity id block allocation persisted in the master storage root.
* Block and block entity broadcasts.
* Player data, duplicate login prevention, stats, advancements, cluster player list, chat.
* level.dat, uid.dat, time, weather, gamerules, world border, shared/real-time file sync.
* Second development server run (`runServer2`, inherited ForgeGradle run).
* Commands, public API, documentation, protocol and master integration tests.
