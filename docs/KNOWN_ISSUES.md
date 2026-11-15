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
* **Ownership boundary crossings:** an entity that walks from a chunk owned by A into one
  owned by B can freeze for a moment. A stops broadcasting when its chunk is no longer the
  entity's chunk, and B starts once it ticks and owns the new chunk; there is no full NBT
  resync on the crossing yet.
* **Players use the player stream.** A mirrored player is an `ExternalPlayer` registered in
  the vanilla `PlayerList` and level; `EntitySyncManager` forwards its movement, metadata and
  equipment but never treats it as an ordinary NBT entity. The copy is not saved and is not
  ticked, and a hit on it is forwarded to the origin server before local invulnerability
  checks. A logout packet removes the copy; a peer disconnect removes everything that came
  from that server.
* **Live player state has the same eventual-consistency window.** Health and movement sync,
  and inventory, ender chest, hotbar, hunger, experience and effects are diffed once per tick,
  but a few details are not mirrored: potion *particles* on other players (vanilla derives them
  from synched metadata, which does sync), a changed tab-list display name or game-mode icon,
  the death animation (`tick death` is not simulated on the copy), the cursor stack, and the
  countdown of an effect duration (the mirror holds the duration from the last add/extension
  because the copy never ticks effects). An open container window is forwarded to the owner
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
* Effect duration is sent when an effect is applied or extended, not while it counts down.
  The mirror's `ActiveEffects` therefore shows the duration of the last update and not the
  decreasing owner value.
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
ownership keeps working locally. When the master returns, servers reconnect and re-subscribe,
but chunks written locally while disconnected are not automatically re-uploaded unless they
are saved again (the local files are the backup, not a queue).

**Mitigation:** monitor the master; restart servers after a long master outage if you need
those local changes uploaded.

### 6. World state conflicts

Time, weather, gamerules, difficulty, spawn and world border are peer-to-peer and use a
last-writer-wins policy sampled once per second. Time is broadcast unconditionally every second
(upstream behaviour), so it can jitter by a tick; weather is only broadcast on a state change
or a timer reset, which avoids a feedback loop between servers that all decrement the timer.
A visible flicker is possible if two admins change the same value within one second.

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

`/reload` re-bakes the config snapshot, but components that cache values at construction (peer
compression threshold, master address) keep the old values until restart.

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

### 14. `debug.chunkBorders` is not implemented

The option exists in the config and is documented as reserved. Either implement it or remove
it (see TODO.md item 16).

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
* A read could be redirected to a peer that was already unloading the chunk; the master now
  records chunk status and serves its own copy in that case
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
* A mirrored player's main hand could land in the wrong inventory slot: `Player.setItemSlot`
  writes `Inventory.items[selected]`, and the owner's `ClientboundSetEquipmentPacket` is sent
  before the live-selected-slot packet in the same tick. `EntitySyncManager.applyEquipment`
  now keeps the mirror's own main hand and only applies absolute slots (armor, off hand) from
  the packet. Found by `tools/compat/player_state_check.py`, which showed the golden sword in
  both hotbar slots 0 and 8 on the mirror before the fix
  (`compat/evidence/player-state-20261115T054356Z.md`).
