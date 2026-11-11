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

### 3. Entity ids can collide

Entity ids are per-server; the protocol has `RequestEntityIdBlock` but the master handler is a
stub. With entity mirroring not implemented this is currently harmless, but it must be solved
before cross-server entity packets are added.

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

Time, weather, gamerules and world border use last-writer-wins with one-second polling. Two
servers changing the same value within a second can oscillate once. No data corruption, but a
visible flicker is possible.

### 7. Config hot reload is partial

`/reload` re-bakes the config snapshot, but components that cache values at construction (peer
compression threshold, master address) keep the old values until restart.

## Operational notes

### 8. ForgeGradle second dev server

`:multiforge-forge:runServer2` exists as a run configuration but ForgeGradle 6 does not assign a
main class to custom run names, so it fails with "No main class specified". Use a real Forge
installation or the `MOD_CLASSES` workaround in DEVELOPMENT.md.

### 9. Master must be started in (or pointed at) its storage root

The master stores worlds relative to `-Dmultiforge.master.storage` (default: working
directory). Changing the root after a world exists looks like an empty cluster and the first
server will upload a fresh world on top. Keep the root stable; back it up.

### 10. Logging volume

`debug.logging = true` logs every chunk load and ownership change. On a busy server this is
several MB per minute. Use it only while diagnosing.

### 11. Refmap when editing mixins

After changing a mixin target or adding a new mixin, rebuild before running, otherwise the
refmap and the mixin config are out of date and Mixin will fail at class transformation.

### 12. `debug.chunkBorders` is not implemented

The option exists in the config and is documented as reserved. Either implement it or remove
it (see TODO.md item 15).

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
