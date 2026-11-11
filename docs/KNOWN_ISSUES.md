# Known issues and limitations

Read this before running a world that matters. Each entry states the symptom, the cause and
the mitigation.

## Functional gaps

### 1. Players cannot see players/entities on other servers

**Symptom:** a player on server A does not see a player or a mob that is standing in a chunk
owned by server B, even though both servers have the chunk loaded and block changes are in
sync.

**Cause:** MultiForge does not yet mirror entities and players between servers (MultiPaper's
`ExternalPlayer` and external entity system). The owning server simulates them; other servers
keep the chunk loaded but do not tick the entities (`ServerLevel.tickNonPassenger` is gated),
so nothing moves and no packets are forwarded.

**Mitigation:** keep players of one area on the same server (the master gives ownership to the
server that needs to tick the chunk, so the area a player is in is normally owned by their
server). Tracked in TODO.md items 1 and 2.

### 2. Live inventory/experience changes are not synchronized

Player data files, statistics and advancements are synchronized on save/load. Changes made
while a player is online on another server are not pushed as packets (MultiPaper's inventory,
ender chest, effects and hunger handlers are not ported).

### 3. Entity ids can collide (fixed master-side, not used by the mod yet)

The master now hands out disjoint, persisted entity id blocks
(`RequestEntityIdBlockHandler`, `lastblock.txt` in the master storage root), tested by
`entityIdBlocksAreDisjointAndPersisted`. The mod does not request a block yet because live
entity mirroring is not implemented; do it when entity packets are ported.

### 4. Chunk generation races

Two servers can generate the same chunk at the same time when the master has no data for it.
The last write wins and both converge after re-reading, but terrain can differ for a moment.
Warm the world with one server before starting the others.

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

### 7. User list changes apply on restart

`FileSyncer` uploads `whitelist.json`, `ops.json` and the ban lists to the master and adopts the
master's copies at startup, but a running server does not reload its in-memory `PlayerList`.
A newly connected server picks the cluster lists up on its next start. Tracked in TODO item 7.

### 8. Config hot reload is partial

`/reload` re-bakes the config snapshot, but components that cache values at construction (peer
compression threshold, master address) keep the old values until restart.

## Operational notes

### 9. ForgeGradle second dev server

`:multiforge-forge:runServer2` exists as a run configuration but ForgeGradle 6 does not assign a
main class to custom run names, so it fails with "No main class specified". Use a real Forge
installation or the `MOD_CLASSES` workaround in DEVELOPMENT.md.

### 10. Master must be started in (or pointed at) its storage root

The master stores worlds relative to `-Dmultiforge.master.storage` (default: working
directory). Changing the root after a world exists looks like an empty cluster and the first
server will upload a fresh world on top. Keep the root stable; back it up.

### 11. Logging volume

`debug.logging = true` logs every chunk load and ownership change. On a busy server this is
several MB per minute. Use it only while diagnosing.

### 12. Refmap when editing mixins

After changing a mixin target or adding a new mixin, rebuild before running, otherwise the
refmap and the mixin config are out of date and Mixin will fail at class transformation.

### 13. `debug.chunkBorders` is not implemented

The option exists in the config and is documented as reserved. Either implement it or remove
it (see TODO.md item 16).

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
