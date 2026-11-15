# Container forwarding, live user lists and master region format - live evidence

Date: 2026-11-15 (07:38-07:42 UTC)

Environment: one `multiforge-master` (storage `/workspace/.runtime/master`) and two
Forge 47.4.29 dedicated servers started from the development runs (`server1` on
25565/RCON 25575, `server2` on 25566/RCON 25576). No gameplay mods; headless
`mineflayer` 4.32.0 client on `server2`. `spawn-protection=0` and
`multiforge-server.toml` `[debug] logging=true` were set for the test.

## Container window forwarding

A chest was placed on `server1` at `(-34, 65, -185)`, inside chunk `(-3, -12)`,
which was owned by `server1` while the bot was connected to `server2`.

* `server2` log: `Forwarded container open at world BlockPos{x=-34, y=65, z=-185} to owner server1`
* `server1` log: `Player 931ffc2d-... opened remote container world BlockPos{x=-34, y=65, z=-185}`
* The headless client received `OPENED` (window open) and shift-clicked its diamond stack.
* `server1` log: `Applied 1 remote slot change(s) at BlockPos{x=-34, y=65, z=-185} from 931ffc2d-...`
* `data get block -34 65 -185` on **both** servers:
  `{Items: [{Slot: 0b, id: "minecraft:diamond", Count: 4b}], id: "minecraft:chest", ...}`
* `data get entity BotB Inventory` on `server2`: `[]` (the stack moved from the player
  inventory into the owner-authoritative chest).
* The client sent `close_window`; the menu closed without an error.

This covers `OpenContainerPacket`, `ContainerClickPacket`, `ContainerClosePacket`,
`ServerPlayerMenuMixin` and `AbstractContainerMenuClickMixin` end to end.

## Live whitelist / ops / bans

Commands ran on `server1`; the changes were observed on the running `server2`
without restart:

| Command on server1 | server2 log |
| --- | --- |
| `/op BotB` | `Reloaded ops.json (1 entries)` |
| `/whitelist add BotB` | `Reloaded whitelist.json (1 entries)` |
| `/ban Nobody` | `Reloaded banned-players.json` |
| `/pardon Nobody` | `Reloaded banned-players.json` |

This covers `StoredUserListMixin` -> `WriteJsonHandler` broadcast ->
`FileSyncer.onDataUpdate` -> `PlayerSyncManager.reloadUserList`.

## Ownership epochs

Ownership announcements and applied changes logged the epoch:

* `Acquired ownership of world,5,16 (epoch 1, was null)`
* `Acquired ownership of world,-3,-12 (epoch 1, was null)` / later epochs observed
  as ownership moved during the restarts.
* Block and block-entity changes made under those epochs were applied (chest block
  and its NBT appeared on both servers; the remote click reached the owner), i.e.
  the epoch filter did not reject current traffic.

Stale-packet rejection itself is covered by the master integration tests
(`ownershipEpochAdvancesOnEveryOwnerChange`, `ownershipEpochIsAssignedWithoutSubscribers`)
and `ChunkState.observeEpoch`; no live previous-owner packet was injected here.

## Master region format

The first two chunks found in the live master region files were inspected with a
plain Python zlib reader:

```
r.-1.-1.mca chunk 6 0 version 2 len 413 magic 789c
  zlib ok bytes 3547 first 0a00000800065374
r.-1.-1.mca chunk 7 0 version 2 len 413 magic 789c
  zlib ok bytes 3547 first 0a00000800065374
```

Version byte is 2 (deflate), the payload starts with the zlib header `78 9c`, and
it inflates to raw NBT (0a 00 00 = TAG_Compound). `RegionFileTest` covers the same
round-trip plus the legacy gzip-mislabelled-as-deflate read path.

## Notes

The verification ran in a headless sandbox; no graphical client was available.
The container test used a vanilla-protocol bot, not a Forge client, so client-side
screen rendering is not covered. Death-message attribution from a cross-server
hit was not exercised live (the packet construction and owner-side `DamageSource`
rebuild are covered by compilation and code review).
