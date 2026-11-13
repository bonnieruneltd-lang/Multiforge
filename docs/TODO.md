# TODO

Ordered by value for a production cluster. Items marked **blocked** need design work before
coding.

## High priority

2. **External player mirroring** (`ExternalPlayer` in MultiPaper). Needed so that players see
   each other across servers (tab list is already cluster-wide, world presence is not).
   Requires a fake `ServerPlayer` per remote player with packet forwarding. Entity
   mirroring (item 1, done) already excludes players for this reason.
3. **Inventory / ender chest / experience / hunger / effects syncing**
   (`MultiPaperInventoryHandler`, `MultiPaperEnderChestHandler`, `MultiPaperEffectsHandler`).
   Player data files are already synchronized; live changes while a player is online on
   another server are not.
4. **Entity id allocation** - done end to end. `RequestEntityIdBlockHandler` hands out
   disjoint, persisted blocks (`lastblock.txt` in the master storage root) and rejects
   exhaustion; the mod now receives a block at startup/reconnect, prefetches one block ahead
   and assigns every entity that joins a level an id from it (`EntityIdAllocator`,
   `EntityJoinLevelEvent`). `twoServersAllocateDisjointEntityIdsThroughTheirAllocators`
   proves two live server connections never overlap. During a master outage the allocator
   falls back to a high local range and logs once (see KNOWN_ISSUES).
5. **Peer packet coverage**: entity movement/teleport/rotation/metadata/equipment/attributes
   and hurt forwarding are ported (M1). Still missing: full `DamageSource`/attacker
   attribution, mob navigation handover, raids, dragon fight, projectile hits, container
   interactions, sign editing, pistons, statistics/advancement live updates.
6. **Chunk status protocol** - done: the master records load/unload status
   (`ChunkStatusTracker`) and does not redirect a read to a peer that is unloading; covered by
   `unloadingPeerIsNotChosenForChunkRead`. The client-side status view is still informational.
7. **Live user list application**: `FileSyncer` syncs whitelist/ops/bans through the master,
   but a running server keeps its in-memory `PlayerList` until restart. Add
   `StoredUserList`/`PlayerList` hooks or reload the lists after a `DataUpdateMessage`.

## Compatibility and reliability (session 3 additions)

* **Chunk generation lease** (done): the master grants exactly one server the right to
  generate a never-seen chunk; other readers wait (bounded) and then read the generator's
  copy. `ChunkGenerationManager`, tested by
  `secondReaderWaitsForGenerationInsteadOfDuplicatingIt`,
  `generationLeaseTimeoutLetsTheWaiterGenerateItself` and
  `generatorDisconnectReleasesWaitingReader`. Remaining: the lease is still demand driven
  (the server whose player needs the chunk generates it); choosing an idle server to
  generate on behalf of a busy one is a separate feature.
* **Ownership epochs + packet sequence numbers** (high, before dynamic ownership): delayed
  block/block-entity packets from a previous owner can overwrite newer state. Append an
  epoch to `BlockChangePacket`/`BlockEntityUpdatePacket` and drop stale epochs. See
  `DISTRIBUTED_ARCHITECTURE_RESEARCH.md`.
* **Master region format compatibility** (medium): master region files contain gzip bytes
  with the zlib version byte, so vanilla region readers cannot open them. Decide between
  writing true zlib and converting on read, or labelling gzip correctly; add a round-trip
  test with a standard reader before changing existing storage.
* **Ksyxis `/forceload` interaction** (low/medium): while Ksyxis is loaded, force-loaded
  tickets disappeared in the test environment. Reproduce with Ksyxis alone, determine
  whether it matters for players, and document the workaround.
* **Radium + ServerCore profile** (low): Radium wins a chunk-cache redirect and one
  ServerCore optimisation is skipped. Provide a documented config profile instead of
  claiming both are active.
* **Player-driven compatibility tests** (highest for the 35 targets): a client or fake-player
  harness is required for minecarts, tree felling, leaf decay, head drops, item merging,
  combat log, TPA, chat, xray and the anti-seed data pack. Until then those rows stay
  LOADING in `REQUIRED_MOD_COMPATIBILITY.md`.
* **Client matrix** (highest for the 15 client targets): run the pinned client set on a GPU
  machine, one shader pack at a time, and append the results to the matrix.

## Medium priority

8. **Run configuration for a second dev server** - done: the `server2` ForgeGradle run inherits
   the resolved launch configuration from `server` (`parent runs.server`) and runs from
   `run2/`; verified with two live servers in this environment. See DEVELOPMENT.md.
9. **Scoreboards and boss bars** (`MultiPaperConfig.syncScoreboards` in the original).
10. **Permissions/op synchronization** (`MultiPaperPermissionSyncer`). Ops JSON is synced; live
    op changes are not broadcast as packets (see item 7).
11. **Advancement/stat live updates**: files are uploaded on save; MultiPaper also sends
    incremental updates.
12. **Weather conflict resolution**: weather is last-writer-wins on a state transition; time already
    follows upstream's forced/periodic semantics. A master-owned logical clock would remove the
    remaining one-tick jitter after a lag spike.
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
19. More protocol fuzz tests (invalid message ids are covered, as are truncated and oversized
    frames; add malformed string/array lengths and random payload fuzzing).
20. A `multiforge-api` example mod and integration test.
21. Port MultiPaper's `/mpmap` and `/mpdebug` visualisations.
22. Automate the live two-server check (container recipe) so it can run in CI.

## Done (for reference)

* **Live entity mirroring (M1)** - done and verified on a two-server cluster. The owner
  forwards `ChunkMap.TrackedEntity.broadcast` packets (`EntityUpdatePacket`), spawns are
  mirrored from NBT (`EntityUpdateNBTPacket`), removals use `EntityRemovePacket`,
  passenger/leash/pickup links use `EntityUpdateWithDependenciesPacket`, and damage on a
  mirror is forwarded with `EntityHurtPacket`. Evidence: a cow spawned in a chunk owned by
  live1 had the same position and health on live2, followed teleports and delta movement,
  lost health when damaged from either server, and disappeared on both when killed.
  Remaining (tracked above or in KNOWN_ISSUES): players (item 2), attacker attribution for
  cross-server hits, and a full resync when an entity crosses an ownership boundary.

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
* `ModCompatibilityManager` + `/multiforge compat` (target detection, version report,
  deployment conflicts).
* `/multiforge chunk <x> <z>`, loaded-chunk listing, and the server-list self-address fix.
* Required-mod resolver, pinned manifest (`compat/required-mods.json`), RCON client and the
  two-server `cluster_check.py` harness with recorded evidence.
* `docs/REQUIRED_MOD_COMPATIBILITY.md`, `docs/DISTRIBUTED_ARCHITECTURE_RESEARCH.md`,
  `docs/DISTRIBUTED_ROADMAP.md`.

## Performance (session 4)

Done and verified:

* **Hot-path pass** on the per-chunk tick, per-entity tick, entity packet forwarding and
  block/block-entity broadcast paths: single map lookup for ownership, no string or
  `ChunkPos` allocation per call, O(1) "has entity subscribers" guard, empty subscription
  lists no longer copied, reused encode buffer, peer writes coalesced into one flush per
  event-loop burst, `PeerProtocol.idOf` hash lookup. Details and measurements in
  [PERFORMANCE.md](PERFORMANCE.md).
* **Entity subscription snapshot** now queries the chunk's entities (AABB) instead of
  scanning every entity in the level; the removal cache expires once per second.
* **File sync** skips reading `level.dat`/`uid.dat`/user lists that did not change and no
  longer queues scans behind a slow one.
* **Master region lookup** no longer calls `File.getCanonicalPath()` (a filesystem syscall
  under the global cache lock) for every chunk access; canonical directories are memoized.
  `tools/bench/HotPathBench.java` records 7.2 us -> 0.12 us per lookup.
* **Shutdown noise**: late master/peer messages no longer log a `RejectedExecutionException`
  stack trace when the server is already stopping.

Still open (moved from the analysis, in value order): chunk NBT compression off the server
thread (`respondToChunkRequest`), a `ChunkKey` cache for the entity tick path, a per
connection `Inflater` for compressed peer frames, gamerule diffing without `createTag`,
single-encode entity snapshots for many subscribers, and a segmented master region cache.

* Forge 47.4.29 build/run verification and the full 20-target server startup.
