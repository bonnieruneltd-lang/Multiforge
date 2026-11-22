# Known issues and limitations

Read this before running a world that matters. Each entry states the symptom, the cause and
the mitigation.

## Functional gaps

### 1. Entity and player mirroring edge cases

* **Cross-server damage attribution:** `EntityHurtPacket` carries the damage type, amount,
  the causing and direct entity UUIDs and the source position. The owner resolves them and
  rebuilds a real `DamageSource`, so death messages and kill credit name a player who hit
  from another server. Limitation: if the causing/direct entity does not exist on the owner
  (not even as a player mirror), the hit degrades to damage-type-only attribution. Projectile
  ownership still relies on the owner having a mirror of the shooter; there is no
  entity-handover protocol for a projectile that outlives its shooter's chunk.
* **Ownership boundary crossings:** a player-driven crossing now uses a fenced handover
  (`ChunkHandoverManager`): the old owner freezes, saves the chunk and acknowledges before the
  epoch advances, so the new owner does not read a stale copy. An entity that walks from a
  chunk owned by A into one owned by B can still miss a packet in the handover tick. The new
  owner re-sends an NBT snapshot of every entity in the chunk to all entity subscribers when it
  gains ownership (`EntitySyncManager.onChunkOwnershipGained`), and a peer's
  `EntityChunkChangedPacket` triggers the same resync on the owning server, so the window is
  one tick instead of "until the next spawn/save". A mirror that was already removed is
  recreated by the snapshot.
* **Players use the player stream.** A mirrored player is an `ExternalPlayer` registered in
  the vanilla `PlayerList` and level; `EntitySyncManager` forwards its movement, metadata and
  equipment but never treats it as an ordinary NBT entity. The copy is not saved and is not
  ticked, and a hit on it is forwarded to the origin server before local invulnerability
  checks. A logout packet removes the copy; a peer disconnect removes everything that came
  from that server.
* **Live player state has the same eventual-consistency window.** Health and movement sync,
  and inventory, ender chest, hotbar, hunger, experience and effects are diffed once per tick,
  but a few details are not mirrored: potion *particles* on other players (vanilla derives them
  from synched metadata, which does sync), the death animation (`tick death` is not simulated
  on the copy), the cursor stack, and the effect countdown (the mirror never ticks effects;
  the owner refreshes the whole effect map once per second, so the mirror is at most one
  second behind). The tab-list display name and game-mode icon now follow the owner through
  `PlayerListInfoPacket` (diffed once per tick), so only the cursor stack and the death
  animation remain unmirrored. An open container window is forwarded to the owner
  (see section 2 and the Containers section of SYNCHRONIZATION.md), including clicks, but the
  cursor stack itself still lives only on the player's server. Player data files are still
  authoritative on the master at save/load time, and the mirror uses the profile properties
  sent with `PlayerCreatePacket`, so skins work when the origin has them.

### 2. Live player state sync latency and scope

Player data files, statistics and advancements are synchronized on save/load. Live changes
while the player is online are now pushed as peer packets by `PlayerStateSyncManager`
(inventory, ender chest, hotbar selection, hunger, experience, effects).

Remaining limitations:

* Detection is a once-per-tick snapshot diff, so a change can take up to one tick (50 ms) to
  reach a mirror. MultiPaper reacts inside the mutating method instead.
* Effect duration is refreshed once per second while an effect is active, so the mirror is at
  most one second behind the owner; it still never ticks the effect itself, and effects
  applied to the player by another hidden mechanism between refreshes appear within that
  second.
* Container windows are forwarded to the owning server (`ContainerSyncManager`): opening a
  chest/furnace in a foreign chunk sends `OpenContainerPacket`, clicks send only the changed
  container slots, and closing sends `ContainerClosePacket`. The menu itself runs on the
  server the client is connected to, so the cursor stack, drag distribution and shift-click
  stay local and responsive; the owner applies the resulting container slots and broadcasts
  the authoritative block entity NBT. The trade-off is last-writer-wins for the container
  slots: a mod that mutates the same container directly on both servers during one click can
  produce a transient mismatch, repaired by the next block entity broadcast.
* Duplication repair is not implemented. Only the owning server mutates the player's
  inventory, and TCP keeps the deltas ordered, so MultiPaper's "replacing item mismatch"
  search for dupes has no trigger here; if a future feature lets another server mutate the
  inventory directly, this must be revisited.

### 3. Entity ids during a master outage

The master hands out disjoint, persisted entity id blocks
(`RequestEntityIdBlockHandler`, `lastblock.txt` in the master storage root), tested by
`entityIdBlocksAreDisjointAndPersisted`. The mod requests a block at startup/reconnect and
prefetches one ahead (`EntityIdAllocator`), and every entity that joins a level gets an id
from the block, so two servers never hand the same id to different entities.

**Remaining risk:** while the master is unreachable no block can be reserved. Blocking
entity spawns until the master returns would freeze the server, so the allocator falls back
to a descending high range (`Integer.MAX_VALUE` downwards) and logs once. Ids from that range
cannot collide with master blocks in practice (the master counter starts at zero), but they
are not persisted. See `EntityIdAllocatorTest.unavailableMasterFallsBackAndReportsIt`.

### 4. Chunk generation is single-owner but still local to one server

A generation lease (`ChunkGenerationManager`) makes sure only one server generates a
never-seen chunk; second readers wait for the first server's copy. Residual risks: a
generator slower than `generation-wait-millis` (default 2 s) makes a waiter generate the
chunk too, and a lease that is never completed expires after `generation-lease-millis`
(default 15 s). Both windows are far smaller than the previous unconditional race, but a
fresh-world startup is still cheapest when one server warms the spawn area first.

### 5. Master outage while running

If the master disappears while servers run, chunk IO falls back to local region files and
ownership keeps working locally. Chunks vanilla writes while the connection is down are now
remembered (`ChunkSyncIo.recordOfflineWrite`) and uploaded after the reconnect
(`flushBacklog`); the same happens for an upload that failed. This was verified live on
2026-11-22: with the master killed, both servers queued 47 chunks each, the backlog drained to
0 after the restart with zero IO failures, and a deleted local region file was reloaded from
the master with the offline and peer-forwarded blocks intact
(`compat/evidence/offline-backlog-20261122T041340Z.md`). The backlog is bounded at
65,536 chunks per server (further offline writes stay local and log once) and lives in
memory, so a server that stops while the master is down still requires a restart before its
changes reach the master. If the master drops again during the replay, the chunk stays in the
backlog and is retried on the next reconnect. `/multiforge status` shows `local-backlog=<n>`.

### 6. World state conflicts

Time, gamerules, difficulty, spawn and world border are peer-to-peer and use a
last-writer-wins policy sampled once per second. Time is broadcast unconditionally every second
(upstream behaviour), so it can jitter by a tick. Weather is master-sequenced while the master
is connected: every transition gets a version and a receiver drops an older one, so two
simultaneous changes converge; while the master is down the peer `WeatherUpdatePacket` fallback
is still last-writer-wins. A visible flicker is possible if two admins change the same non-weather
value within one second. Weather convergence was verified live with two bots
(`compat/evidence/weather-ordering-20261122T042413Z.md`): the master advanced only the two
semantic versions for a rain→clear sequence and both clients saw both transitions.

### 7. User list reload conflicts

`StoredUserListMixin` uploads a whitelist/ops/ban change immediately, and `WriteJsonHandler`
broadcasts the content to the other servers, which reload their in-memory lists without a
restart (`PlayerSyncManager#reloadUserList`). A whitelist reload disconnects players that are
no longer allowed; a ban reload disconnects matching users/IPs; an op reload re-sends
permission levels. Two admins editing the same list on two servers within the round-trip
window still produce last-writer-wins (the master serializes the writes, but the later write
replaces the earlier one rather than merging entries).

### 8. Region files written by older masters

New chunks are zlib-compressed and readable by vanilla and external tools. A master world
written before this change contains gzip bytes stored under the deflate version byte; the
read path detects the gzip magic and transcodes it, so servers and peers can still load
those chunks, but an external tool that does its own region parsing will see the old files
as corrupt until they are rewritten (re-save the chunk through the master).

### 9. Config hot reload is partial

On Forge 47.4.29 `/reload` does not fire `ModConfigEvent.Reloading` for mod configs, so
`/multiforge reload` re-reads the TOML file directly and applies it. If the event ever fires,
`MultiForgeServer.onConfigReloaded` also swaps the snapshot, so tick,
file, world and player paths use the new values immediately; a changed `master.address`
reconnects the control channel. A changed `master.pingIntervalSeconds` reschedules the
keep-alive loop, and a changed peer compression threshold is read by existing connections on
their next frame. Only `server.peerPort` and `server.name` are bound/announced once and still
need a restart (logged). Switching peer compression on or off (threshold 0 &lt;-&gt; positive) is
negotiated in the hello and therefore applies to new connections; the server says so in the
log.

## Operational notes

### 10. ForgeGradle second dev server (fixed)

`:multiforge-forge:runServer2` used to fail with "No main class specified" because the
`server2` run did not inherit ForgeGradle's resolved launch configuration. It now declares
`parent runs.server` and runs from `run2/`; see DEVELOPMENT.md.

### 11. Master must be started in (or pointed at) its storage root

The master stores worlds relative to `-Dmultiforge.master.storage` (default: working
directory). Changing the root after a world exists looks like an empty cluster and the first
server will upload a fresh world on top. Keep the root stable; back it up.

### 12. Logging volume

`debug.logging = true` logs every chunk load and ownership change. On a busy server this is
several MB per minute. Use it only while diagnosing.

### 13. Refmap when editing mixins

After changing a mixin target or adding a new mixin, rebuild before running, otherwise the
refmap and the mixin config are out of date and Mixin will fail at class transformation.

### 14. `debug.chunkBorders` draws only the current chunk

`/multiforge borders` is implemented (session 6): it draws the border of the chunk the player
stands in with particles, coloured by owner (green = this server, red = another, white =
unowned). It intentionally does not draw the whole view distance: that would be hundreds of
particles per player per refresh. Clients that hide particles (or have them disabled) will not
see it even though the server sends the packets.

## Interoperability and compatibility findings (session 3)

### 15. `/forceload` tickets disappear while Ksyxis is installed

With Ksyxis 1.4.6 loaded, `forceload add` reported success but `forceload query` immediately
showed no ticket and the chunk did not stay loaded. Player-driven chunk loading is not
affected. The compatibility harness removes Ksyxis from both servers so it can find a common
spawn chunk; the full 19/19 startup including Ksyxis was verified separately.

### 16. Radium wins a mixin redirect over ServerCore

`servercore.common.mixins.json:optimizations.ticking.chunk.cache.ServerChunkCacheMixin`
logs `@Redirect conflict` and is skipped because Radium's `lithium.mixins.json` redirect has
priority 1000. The server is correct; one ServerCore optimisation is inactive. BetterAutoSave
also disables its synchronous chunk-load detector because Radium overwrites
`ServerChunkCache.getChunk`. There is no config profile yet that restores both.

### 17. BetterAutoSave probes `level.dat` before MultiForge downloads it

During startup BetterAutoSave logs `level.dat ... MISSING` because MultiForge fetches
`level.dat` from the master in `loadLevel`, after the first BetterAutoSave probe. The file
is present afterwards (`Loaded level.dat (... bytes) from the master`) and the server runs.
The message is cosmetic but confusing; a future adapter could delay the probe or silence it.

### 18. Timber Strike 3.0 is mislabelled for 1.20.1

Its Modrinth version lists 1.20.1 but its data-pack functions use 1.20.2 `return run`
syntax; every tree function fails to parse. Pin `timber-strike-1.3.jar`, which loads cleanly.
This is a target-mod defect, recorded so nobody re-pins 3.0.

### 19. Scoreboards, statistics and advancements are cluster state with a local tail

* The **main scoreboard** is synchronized by replaying vanilla clientbound packets on every
  server (`ScoreboardSyncManager`, peer packet 30). Per-player sidebars, boss bars and
  scoreboards created by mods are not synchronized; they are not world state and upstream
  does not synchronize them either. Two admins changing the same objective on two servers in
  the same round-trip still last-writer-wins.
* **Statistics** are sent as batched deltas every ten seconds and **advancements** when a
  criterion is awarded. They are applied only by the server currently hosting the player, so
  they close the handover window; they are not mirrored onto other servers' copies of the
  player. The player data file on the master is still authoritative on save/load, and a
  statistic earned while no peer connection exists waits for the next save.

### 20. BungeeCord forwarding trusts the proxy

With `server.bungeeCord=true` the backend accepts IP, UUID and profile properties from
the handshake. The format is not authenticated, so anyone who can reach the backend port
can impersonate any player. This is inherent to legacy forwarding (the same is true of
Paper's `bungeecord: true`); the mitigation is a firewall or private network that only
admits the proxy, plus `online-mode=false` on the backend because the proxy authenticates.
Velocity **modern** forwarding is now supported separately (`server.velocityModern` +
`server.velocitySecret`): the backend sends the `velocity:player_info` login plugin request,
verifies the HMAC-SHA256 answer and pauses the login until it arrives. Only forwarding
version 1 is requested; versions 2-4 (player key/respawn data) are not negotiated. The
two forwarding modes are mutually exclusive. Malformed legacy forwarding data is rejected
and logged rather than used, and the connection falls back to the proxy address and an
offline UUID.

### 21. Leader election is a failover lock, not live migration

`leader-election=true` guarantees only one master serves a shared storage root. The
standby process waits for the lock and starts listening when the leader exits (including a
crash, because the OS releases the lock). It does not transfer open connections, in-flight
uploads or the proxy socket: game servers reconnect to the master on their normal retry
interval and replay their pending requests, so expect a short reconnect window. The old
leader releases the lock only after its listener and event loop are shut down, so the
standby never binds while the previous term still owns a listening socket. Leave the
option off unless two masters really share one storage directory.

### 22. Download progress is advisory

`DownloadProgressMessage` reports bytes handed to the channel, not bytes the peer has
written to disk. A network buffer can therefore make the reported value jump ahead of the
actual file; the data-stream close marker remains the only completion signal, and a
missing or duplicated progress message cannot corrupt a download. Very large files on a
slow link are throttled to one message per MiB so progress does not add meaningful
traffic.

### 23. Config location and reload on Forge

The config is registered as a Forge `COMMON` config and lives at
`config/multiforge-server.toml`, the path the documentation and the early start-up reader use.
Earlier builds registered it as `SERVER`, which Forge stores in
`<world>/serverconfig/multiforge-server.toml`; those settings are migrated once on start-up
(`MultiForgeConfig.migrateLegacyConfig`). Forge 47.4.29 does not fire config reload events on
`/reload`, so `/multiforge reload` re-reads the TOML file directly and applies it (verified
live). Values bound or negotiated once still need a restart: `server.peerPort`,
`server.name`, existing peer compression and scheduler periods; the server logs which value
was not applied. A changed `master.address` reconnects the control channel.

### 24. Chunk sequence history is bounded

Every block and block-entity update carries a per-chunk sequence number, and the receiver
acknowledges the highest contiguous value per chunk and sender per tick. Ordering state is
kept per sender (`SenderSequenceTable`) because more than one server can change a block in the
same chunk while ownership settles; a shared counter would treat the other sender's numbers as
duplicates and drop updates. The sender keeps the last 128 updates of each chunk **per peer**
(`ChunkState.retransmitBuffer`), so one peer's acknowledgement cannot discard entries another
peer still needs; a gap that is more than 128 updates old, or wider than the receiver window
(also 128), cannot be filled from memory. Instead of stalling, the receiver adopts the newest
value as the baseline (logged as a gap) and the next full chunk load repairs the state. The
first packet of a sender the tracker has never seen is used as the baseline too, because earlier
numbers were never sent to that peer. On a single TCP connection packets are ordered and
complete, so a gap indicates a reconnect during which the owner kept sending, or a software
defect; the counter is visible as `seq gaps=` in `/multiforge status`. Pending acknowledgements
are dropped at shutdown, which is harmless because a restarting peer reloads chunks from the
cluster.

### 25. Chunk handover limits

`ChunkHandoverManager` moves a chunk only after the old owner saved it and the master
acknowledged the upload. Remaining limits:

* The 5 s drain budget (`multiforge.master.handoverTimeoutMillis`) is fixed per master. A
  very large modded chunk that takes longer to serialize keeps the old owner; the next walk
  over the border retries.
* The owner refuses while a **real** local player is inside the chunk. If players from two
  servers stand in the same chunk, the current owner keeps it and the other server retries at
  the ownership-request interval, so blocks keep syncing but only one side ticks.
* `ChunkMap.save` writes region, entity and POI data of that chunk; mob navigation state is not
  persisted by vanilla, so `MobNavigationPacket` carries the destination/speed/combat target
  before the fence and the new owner recomputes the path. Raids and the dragon fight remain
  separate gaps (TODO 5).
* The mod-side freeze/save/ready path, the refusal path and the ten-second refusal backoff are
  verified live on two dev servers with headless bots
  (`compat/evidence/chunk-handover-20261122T035205Z.md`,
  `compat/evidence/handover-mirroring-20261122T040431Z.md`); the timeout and
  old-owner-disconnect paths are covered by master integration tests.

### 26. Boss bars are replicated, fight state is not

Dragon, raid and plugin boss bars are forwarded as vanilla `ClientboundBossEventPacket`s
(`BossEventPacket`, peer id 36), so every server shows the same bar. The underlying fight is
still simulated only by the owner: raid wave NBT, dragon-fight phase state and the raid/dragon
objects are not transferred on a handover. A raid or dragon that moves between servers can
therefore lose its in-progress phases even though the bar remains correct. See TODO 5 and
`compat/evidence/boss-bar-20261122T044950Z.md`.

## Fixed issues worth knowing about (regression tests cover them)

* Data stream downloads were truncated at 64 KiB because the outbound stream reused the
  caller's buffer (`OutboundDataStream.send` now copies the slice).
* Frames arriving before the receiver registered a stream could be reordered or lost (a close
  marker could complete a download early); the
  stream manager now buffers unknown streams and replays them in order.
* Reconnects failed because `MasterConnection` was not `@Sharable`.
* Duplicate replies after a reconnect threw `UnsupportedOperationException`; orphan replies are
  now ignored.
* The master could not be stopped within an hour if a file task lingered; the wait is bounded.
* New servers did not learn existing peers' addresses; the master replays start messages on
  hello.
* POI storages created before level registration were attributed to the world `unknown`; the
  path is now reconstructed.
* The whole `com.multiforge.forge.world` package was missing from git because `.gitignore`
  contained an unanchored `world/` rule; the build failed on a clean checkout. The rules are
  anchored now and the package is restored (no regression test can catch a missing file, so
  check `git ls-files` after adding sources).
* A `FilesToSyncMessage` decoder allocated `new FileToSync[readVarInt()]` before looking at
  the frame, so a small hostile frame could announce two billion entries and kill the process
  with an `OutOfMemoryError` (found by `ProtocolFuzzTest`, reproduced with
  `fileToSyncCountLargerThanTheFrameIsRejectedBeforeAllocation`). The count is now bounded by
  the remaining frame bytes.
* The peer frame decoder trusted the announced uncompressed length and allocated it before
  inflating. A tiny frame could therefore request a multi-gigabyte buffer on a Netty thread.
  Both the frame length and the uncompressed length are capped by
  `-Dmultiforge.peer.maxFrameBytes` (16 MiB default; the inflater holds the compressed input,
  the uncompressed output and the target buffer at the same time).
* The peer packet decoders had two more unguarded counts: an
  `EntityUpdateWithDependenciesPacket` created `new UUID[readVarInt()]` and a
  `ContainerClickPacket` created `new ArrayList<>(readVarInt())` before looking at the frame.
  Both are bounded by the remaining bytes (and a 4096-entry cap) now, like the master protocol
  arrays.
* The data-stream manager buffered frames for every unknown stream id without a global limit, so
  a peer could create millions of one-frame streams. Unknown streams are capped at 1024 and
  their total buffered bytes at 64 MiB (`DataStreamManager.MAX_PENDING_STREAMS`,
  `MAX_TOTAL_PENDING_BYTES`); `DataStreamTest` covers both.
* A fallback `ChunkStorage` created by `LocalChunkStorage` was registered by the constructor
  mixin, so a local backup write (or a read fallback while the master was up) re-entered
  `ChunkSyncIo` and would recurse until the stack overflowed. The instance is unregistered
  right after construction (`ChunkStorageRegistry.unregister`), and the local write is no
  longer duplicated when `keepLocalChunkBackup` is already on.
* Sequence numbers were first tracked per chunk only. A second server can change a block in a
  chunk while ownership settles, and its independent numbering then looked like duplicates
  (updates dropped). Ordering state is per sender now (`SenderSequenceTable`), and
  acknowledgements are tracked per chunk and peer.
  (`unloadingPeerIsNotChosenForChunkRead`).
* Entity id blocks were persisted in the process working directory and could wrap negative;
  they now live in the master storage root with serialized, overflow-checked allocation
  (`entityIdBlocksAreDisjointAndPersisted`).
* `/time set` was overwritten by the other server's periodic time broadcast; `TimeSyncPacket`
  now carries game time and a force flag (`onRemoteTimeSync`).
* Block propagation stopped when the master was down even though peer links were up; the
  master-connection gates were removed from `LevelChunkMixin` and `ChunkManager`.
* A two-server reconnect threw `ConcurrentModificationException` in the chunk status handler;
  `getSubscribers` returns an immutable snapshot (`subscriberSnapshotsAreImmutableAcrossChanges`).
* The built-in proxy connected to the peer listener because servers sent their peer port in
  `StartMessage`; the game port now travels in `StartMessage` and the peer port in
  `SetPortMessage`/`ServerStartedMessage` (`startAndPeerPortsAreSeparate`).
* IP-only BungeeCord forwarding (the built-in proxy) rebuilt the login profile with the
  null UUID that vanilla holds before `handleAcceptedLogin`, breaking player data; the
  profile is now left untouched for IP-only forwarding and uses the offline UUID only
  when properties are forwarded. Player data then followed a live transfer between
  servers (5/5 proxy/transfer checks).
* A download request for a missing shared file made the master log a `FileNotFoundException`
  and never reply, so the server waited for its IO timeout; it now replies with
  `FileContentMessage(..., streamId=-1)` immediately. Shared paths are also normalized and
  rejected when they escape `synced-server-files` (`MasterStorage.sharedFile`).
* The documented `config/multiforge-server.toml` was ignored after start-up because the config
  was registered as a Forge `SERVER` config (which lives in `world/serverconfig/`); Forge then
  reset `bungeeCord` to its default. The config is now `COMMON` with a legacy migration.
* BungeeCord/Velocity forwarding data was invisible because Forge's
  `ClientIntentionPacket` strips everything after the first NUL; the raw host is now captured
  at the `readUtf` call site (`HandshakeAddressMixin`).
* The scoreboard sync used `PlayerList.broadcastAll`, but 1.20.1 does not broadcast objective
  add/remove at all and only broadcasts score/display/team packets to players that track the
  main scoreboard; it now hooks the `ServerScoreboard` callbacks and sends a full
  objective/score snapshot to a connecting peer. `ClientboundSetScorePacket` takes
  `(method, objectiveName, owner, score)`; the swapped arguments silently dropped scores.
* A malformed frame could announce a multi-gigabyte string or array length and make the
  decoder allocate it before the bounds check (`readString`, `new byte[readVarInt()]`,
  `new String[readVarInt()]`, `new ChunkKey[readVarInt()]`). Every reader now validates the
  announced length against the frame first (`ExtendedByteBuf.readByteArray`,
  `readStringArray`, `readChunkKeyArray`); `ProtocolFuzzTest` and
  `ExtendedByteBufTest` cover it.
* A mirrored player's main hand could land in the wrong inventory slot: `Player.setItemSlot`
  writes `Inventory.items[selected]`, and the owner's `ClientboundSetEquipmentPacket` is sent
  before the live-selected-slot packet in the same tick. `EntitySyncManager.applyEquipment`
  now keeps the mirror's own main hand and only applies absolute slots (armor, off hand) from
  the packet. Found by `tools/compat/player_state_check.py`, which showed the golden sword in
  both hotbar slots 0 and 8 on the mirror before the fix
  (`compat/evidence/player-state-20261115T054356Z.md`).
* A read could be redirected to a peer that was already unloading the chunk; the master now
  records chunk status and serves its own copy in that case.
