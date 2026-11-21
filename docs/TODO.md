# TODO

Ordered by value for a production cluster. Items marked **blocked** need design work before
coding.

## High priority

3. **Inventory / ender chest / experience / hunger / effects syncing** - done.
   `PlayerStateSyncManager` diffs every local player against the last transmitted snapshot
   once per tick and broadcasts `PlayerInventoryUpdatePacket`, `PlayerSelectedSlotPacket`,
   `PlayerFoodUpdatePacket`, `PlayerExperiencePacket` and `PlayerEffectsUpdatePacket`; the
   receiver applies them to the `ExternalPlayer`. It covers `MultiPaperInventoryHandler`,
   `MultiPaperEnderChestHandler` and `MultiPaperEffectsHandler` in one manager (see
   [PORTING_NOTES.md](PORTING_NOTES.md) for the polling deviation). A newly created mirror
   gets the full state from `PlayerCreatePacket` (`saveWithoutId`). Container windows are
   handled separately by `ContainerSyncManager` (see item 5, packets 27-29).
4. **Entity id allocation** - done end to end. `RequestEntityIdBlockHandler` hands out
   disjoint, persisted blocks (`lastblock.txt` in the master storage root) and rejects
   exhaustion; the mod now receives a block at startup/reconnect, prefetches one block ahead
   and assigns every entity that joins a level an id from it (`EntityIdAllocator`,
   `EntityJoinLevelEvent`). `twoServersAllocateDisjointEntityIdsThroughTheirAllocators`
   proves two live server connections never overlap. During a master outage the allocator
   falls back to a high local range and logs once (see KNOWN_ISSUES).
5. **Peer packet coverage**: entity movement/teleport/rotation/metadata/equipment/attributes
   and hurt forwarding are ported (M1). Container windows are forwarded through
   `OpenContainerPacket`/`ContainerClickPacket`/`ContainerClosePacket` and the
   `ServerPlayerMenuMixin`/`AbstractContainerMenuClickMixin` hooks (menu opens where the
   client is connected; the owner applies slot deltas). Cross-server damage now carries the
   causing/direct entity UUIDs and source position, and the owner rebuilds a real
   `DamageSource`. Still missing: mob navigation handover, raids, dragon fight, projectile
   ownership beyond the attacker/direct UUIDs. Sign editing and pistons are already covered
   by the generic paths: a sign calls `BlockEntity.setChanged()` and a piston move is a
   block/block-entity change, so `BlockEntityUpdatePacket`/`BlockChangePacket` carry them
   (not separately live-tested).
6. **Chunk status protocol** - done: the master records load/unload status
   (`ChunkStatusTracker`) and does not redirect a read to a peer that is unloading; covered by
   `unloadingPeerIsNotChosenForChunkRead`. The client-side status view is still informational.
7. **Live user list application** - done. `FileSyncer` still syncs whitelist/ops/bans
   through the master, and `StoredUserListMixin` uploads a list immediately after
   `add`/`remove` (so `/op`, `/deop`, `/whitelist`, `/ban` and `/pardon` do not wait for the
   one-second scan). `WriteJsonHandler` now broadcasts the new content as
   `DataUpdateMessage`; receivers call `PlayerSyncManager.reloadUserList`, which reloads the
   in-memory list, re-sends op levels and disconnects players that became unwhitelisted or
   banned. No restart is needed.

## Compatibility and reliability (session 3 additions)

* **Chunk generation lease** (done): the master grants exactly one server the right to
  generate a never-seen chunk; other readers wait (bounded) and then read the generator's
  copy. `ChunkGenerationManager`, tested by
  `secondReaderWaitsForGenerationInsteadOfDuplicatingIt`,
  `generationLeaseTimeoutLetsTheWaiterGenerateItself` and
  `generatorDisconnectReleasesWaitingReader`. Remaining: the lease is still demand driven
  (the server whose player needs the chunk generates it); choosing an idle server to
  generate on behalf of a busy one is a separate feature.
* **Ownership epochs + packet sequence numbers** - epochs done. The master keeps a
  per-chunk epoch (`ChunkSubscriptionManager`) and appends it to
  `SetChunkOwnerMessage`/`ChunkSubscribersSyncMessage`; `BlockChangePacket` and
  `BlockEntityUpdatePacket` are stamped and receivers drop older epochs
  (`ChunkState.observeEpoch`). Per-chunk sequence numbers and acknowledgements are still
  missing; they are only needed for gap detection and safe dynamic ownership migration
  (see `DISTRIBUTED_ARCHITECTURE_RESEARCH.md`).
* **Master region format compatibility** - done. `ChunkSyncIo.encode` now writes true zlib
  (deflate with a zlib header) and `RegionFile` detects legacy gzip bytes mislabelled as
  version 2 and transcodes them on read. `RegionFileTest` proves a standard JDK inflater
  reads the stored chunk and that the legacy path still round-trips.
* **Ksyxis `/forceload` interaction** (low/medium): while Ksyxis is loaded, force-loaded
  tickets disappeared in the test environment. Reproduce with Ksyxis alone, determine
  whether it matters for players, and document the workaround.
* **Radium + ServerCore profile** (low): Radium wins a chunk-cache redirect and one
  ServerCore optimisation is skipped. Provide a documented config profile instead of
  claiming both are active.
* **Player-driven compatibility tests** (highest for the 35 targets): a client or fake-player
  harness is required for minecarts, tree felling, leaf decay, head drops, item merging,
  combat log, TPA, chat, xray and the anti-seed data pack. A minimal headless bot now exists
  (`tools/compat/mcbot.js`, used by `player_state_check.py`) and can be extended for those
  interactions; until then those rows stay LOADING in `REQUIRED_MOD_COMPATIBILITY.md`.
* **Client matrix** (highest for the 15 client targets): run the pinned client set on a GPU
  machine, one shader pack at a time, and append the results to the matrix.

## Medium priority

8. **Run configuration for a second dev server** - done: the `server2` ForgeGradle run inherits
   the resolved launch configuration from `server` (`parent runs.server`) and runs from
   `run2/`; verified with two live servers in this environment. See DEVELOPMENT.md.
9. **Scoreboards** - done and verified live (session 6): every `ServerScoreboard` mutation
   (`onObjectiveAdded/Changed/Removed`, `onScoreChanged`, `onPlayerScoreRemoved`,
   `setDisplayObjective`, team add/change/remove/membership) is forwarded as peer packet 30
   and replayed through the vanilla scoreboard API on the other servers
   (`ScoreboardSyncManager`); a connecting peer receives every objective, its scores, the
   display slots and all teams. Live evidence: objectives, scores, teams and display slots
   created on `live1` were read back on `live2` (`compat/evidence/server-features-20261121T065500Z.md`). Only the main scoreboard is synchronized, matching upstream;
   boss bars are per-player vanilla state and are **not** synchronized (upstream has no
   equivalent either).
10. **Permissions/op synchronization** - done for vanilla operators: `/op` and `/deop` upload
    `ops.json` immediately and the other servers reload it and re-send permission levels (item
    7), so there is no restart and no stale op. Bukkit permission plugins (what MultiPaper's
    `MultiPaperPermissionSyncer` targets) do not exist on Forge; a permission mod would need
    its own peer packet.
11. **Advancement/stat live updates** - done (session 6): `PlayerProgressSyncManager`
    batches `StatsCounter.increment` deltas for ten seconds (peer packet 31) and forwards a
    successful `PlayerAdvancements.award` immediately (peer packet 32); the receiving server
    applies them to a locally hosted player under an echo guard. This closes the handover
    window where a player transfers before the files are saved. Files remain authoritative on
    the master at save/load time.
12. **Weather conflict resolution**: weather is last-writer-wins on a state transition; time already
    follows upstream's forced/periodic semantics. A master-owned logical clock would remove the
    remaining one-tick jitter after a lag spike.
13. **Master high availability** - single-leader part done (session 6): `LeaderElection`
    takes an exclusive OS lock on `master-leader.lock` when `leader-election=true` (default
    off); a standby waits and takes over when the leader exits, and the built-in proxy and
    metrics endpoint only start on the leader. This is a failover lock, not live migration:
    servers reconnect and replay pending requests. Unit tested; live two-process takeover is
    exercised manually (start two masters on one storage).
14. **Built-in proxy player balancing** - verified live (session 6): the proxy picks the
    least loaded live server by tick time, forwards the handshake with the client IP, and
    accepted a raw 1.20.1 login end to end. Verification also found that servers advertised
    their peer port in `StartMessage` (the proxy dialled the peer protocol); it now advertises
    the game port and keeps the peer port in `SetPortMessage`/`ServerStartedMessage`.
    Reconnects use the normal TCP path; deeper protocol edge cases remain untested.
15. **Config hot reload** - done and verified live (session 6): `/multiforge reload` re-reads
    `config/multiforge-server.toml` directly (because Forge 47.4.29 does not fire config
    reload events), applies the snapshot and logs restart-only values. A changed
    `master.address` reconnects; disabling `debug.chunkBorders` clears viewers. Details: `MultiForgeServer.config` is volatile and
    `onConfigReloaded` swaps the snapshot on `ModConfigEvent.Reloading`, so every manager that
    reads `config()` sees the new values immediately; a changed `master.address` closes the
    control channel and reconnects, and disabling `debug.chunkBorders` clears the viewers.
    `server.peerPort`, `server.name`, the ping/reconnect schedulers and the peer compression
    threshold are fixed per process or per connection and are logged as restart/next
    connection only.

## Low priority / polish

16. `debug.chunkBorders` - done (session 6). `/multiforge borders` toggles particle lines on
    the current chunk border (green = owned by this server, red = another server, white =
    unowned). `ChunkBorderVisualizer` refreshes twice per second and sends only to the player
    who enabled it; the config option is mandatory.
17. Metrics/observability endpoint (Prometheus) - done (session 6). `MetricsServer` exposes
    `/metrics` on `metrics-port` (disabled with -1, default) with uptime, online
    servers/players, owned/subscribed chunks, pending entity writes and the next entity id.
    Bind it to a private interface with `metrics-address` in production.
18. Structured progress reporting for large file downloads - done and verified live (session
    6): the master sends `DownloadProgressMessage` (path, stream id, transferred, total) at
    least once per MiB and once with `transferred == total`; a 6 MiB live download logged
    16/33/50/66/83/100% and produced the exact file. `FileSyncer` tracks active downloads and
    `/multiforge status` lists them. A missing or out-of-root file now gets a `-1`
    "unavailable" reply instead of a master stack trace and a requester timeout; the shared
    root rejects paths escaping it.
19. Protocol fuzz tests - done (session 6). `ProtocolFuzzTest` feeds malformed and random
    bodies to every registered message id and checks announced byte/string/array lengths are
    rejected before allocation. It found a remote OOM in `readString` and the array readers;
    `ExtendedByteBuf` now has bounds-checked `readByteArray`, `readStringArray` and
    `readChunkKeyArray`.
20. A `multiforge-api` example mod and integration test - partially done (session 6):
    `MultiForgeApiProviderTest` contains a complete example implementation plus contract
    tests for registration, reset, the not-initialised error and the value objects. A
    separate example mod project is still missing.
21. Port MultiPaper's `/mpmap` and `/mpdebug` visualisations - partially done (session 6):
    `/multiforge map [radius]` prints a text ownership grid around the player (`@` = this
    server, first letter = another server, `.` = unowned) and `/multiforge borders` covers
    the debug particle view. A graphical map is still missing.
22. Automate the live two-server check (container recipe) so it can run in CI - partially
    done (session 6): `.github/workflows/build.yml` builds every module and runs the
    protocol/master/API suites on a clean runner and uploads the jars.
    `tools/compat/cluster_check.py` now also verifies scoreboard objectives/scores/teams
    and entity mirror/damage/removal, and `--skip-mod-checks` lets it run on a dev
    cluster; a live dev run recorded 17/17 checks
    (`compat/evidence/cluster-20261121T070338Z.md`). The live check still needs a
    Minecraft server download, RCON and a disposable world, so it remains a manual step
    rather than a CI job.

23. **BungeeCord / Velocity proxy compatibility** - legacy forwarding done and verified live
    (session 6):
    `server.bungeeCord=true` parses `host\0ip[\0uuid\0properties]`, applies the player IP
    to the connection and replaces the login profile with the forwarded UUID/textures
    (`BungeeCordSupport`, handshake/login/connection mixins). Parser is unit tested; the
    mixins were exercised with a raw forwarded login (UUID applied, IP logged) and through the
    built-in proxy (IP-only forwarding preserved the offline UUID). Velocity **modern**
    forwarding (login plugin + HMAC) is implemented and verified with a simulated proxy;
    versions 2-4 (player key) are not requested. Remaining: MultiPaper's BungeeCord/Velocity
    plugin that runs the master inside the proxy and balances players itself - MultiForge
    relies on the proxy's own balancing or the built-in proxy.

## Done (for reference)

* **Player mirroring (TODO 2)** - done and verified on a two-server cluster with headless
  clients. `ExternalPlayer` copies are registered in the vanilla `PlayerList` and level, so
  `/list`, the tab list and the entity tracker see them; `PlayerCreatePacket`/
  `PlayerRemovePacket` carry the profile, position, game mode and player data; movement,
  metadata and equipment use the existing entity packet stream; hits on a copy are forwarded
  from `ServerPlayer.hurt` to the server that hosts the connection; logout and peer loss
  remove the copies. Evidence: `compat/evidence/player-mirroring-20261115T030505Z.md`.
  Live inventory/ender chest/hotbar/hunger/experience/effects sync is done (item 3);
  remaining: display-name/team sync and full attacker attribution for cross-server hits.

* **Live entity mirroring (M1)** - done and verified on a two-server cluster. The owner
  forwards `ChunkMap.TrackedEntity.broadcast` packets (`EntityUpdatePacket`), spawns are
  mirrored from NBT (`EntityUpdateNBTPacket`), removals use `EntityRemovePacket`,
  passenger/leash/pickup links use `EntityUpdateWithDependenciesPacket`, and damage on a
  mirror is forwarded with `EntityHurtPacket`. Evidence: a cow spawned in a chunk owned by
  live1 had the same position and health on live2, followed teleports and delta movement,
  lost health when damaged from either server, and disappeared on both when killed.
  Players are mirrored by the player stream (TODO 2, done). Remaining (tracked above or in
  KNOWN_ISSUES): attacker attribution for cross-server hits, and a full resync when an entity
  crosses an ownership boundary (session 6: `EntitySyncManager.onChunkOwnershipGained`
  re-snapshots every entity of a newly owned chunk to its entity subscribers, and
  `EntityChunkChangedPacket` is no longer log-only).

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
connection `Inflater` for compressed peer frames, and a segmented master region cache.
Gamerule diffing without `createTag` and single-encode entity snapshots for many subscribers
are done (session 6).

* Forge 47.4.29 build/run verification and the full 20-target server startup.
