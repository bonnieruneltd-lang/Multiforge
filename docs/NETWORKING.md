# Networking

MultiForge has two networks: the **control plane** between servers and the master, and the
**data plane** between servers (peers). Both run on Netty and use the same Netty version
that Minecraft 1.20.1 ships (4.1.82.Final), so no extra runtime dependency is required.

## 1. Control plane framing

`multiforge-protocol` implements the framing exactly like MultiPaper:

```
frame   := varint messageLength, message
message := varint transactionId, varint messageId, payload
```

* `MessageLengthEncoder/Decoder` add/remove the length prefix. Frames larger than 64 MiB
  (`-Dmultiforge.protocol.maxFrameBytes`) are rejected before they are buffered, and a
  partial frame is kept until the rest arrives rather than being consumed.
* `MessageEncoder/Decoder` write/read the two ids and call `Message#write`/`Message(ExtendedByteBuf)`.
* Every announced length is validated against the unread frame before allocating:
  `ExtendedByteBuf.readByteArray`, `readStringArray`, `readChunkKeyArray` and `readString`
  reject negative or impossibly large values (a malformed frame used to be able to request a
  multi-gigabyte array and kill the process). `ProtocolFuzzTest` fuzzes every message id.
* `MessageHandler` keeps a map of transaction id -> callback. A reply is delivered to the
  callback when the ids match. Reply-only messages received without a callback are ignored
  (they happen when a request is retransmitted after a reconnect).
* Large payloads (files, chunk data) use **data streams**: `streamId` is announced by a
  normal message, data frames follow as `*DataStreamMessage`, an empty frame closes the
  stream. `DataStreamManager` buffers frames that arrive before the receiver registered the
  stream and replays them in order, so a close marker can never overtake data.

### Requests and retries

`MasterConnection.request(message)` assigns a transaction id, remembers the message in
`pendingRequests` and completes a future with the reply. On reconnect, `channelActive`
re-sends every pending request; replies are idempotent at the master (chunk writes, reads,
lock requests). File uploads are fire-and-forget.

## 2. Master message catalogue

Direction names: **S->M** = server to master (`MasterBoundProtocol`), **M->S** = master to
server (`ServerBoundProtocol`).

### Server -> master

| Message | Purpose |
| --- | --- |
| `HelloMessage(name, uuid)` | register the connection |
| `PingMessage` | heartbeat; master answers implicitly by updating liveness |
| `StartMessage(host, port)` | announce the game server host/port (used by the built-in proxy) |
| `SetPortMessage(port)` | announce the peer-to-peer listener port (used by `ServerStartedMessage`) |
| `WriteTickTimeMessage(tickTime, tps)` | report server health |
| `PlayerConnectMessage(uuid, name)` | claim a player (reply boolean: allowed) |
| `PlayerDisconnectMessage(uuid)` | release a player |
| `LockChunkMessage(world, cx, cz)` | request ownership (first come, first served) |
| `UnlockChunkMessage` | release ownership |
| `RequestChunkOwnershipMessage(world, keys[])` | force-take a batch of chunks (redstone control) |
| `RequestChunkHandoverMessage(world, cx, cz)` | fenced transfer of a loaded chunk the requester subscribes to; the current owner drains first |
| `ChunkHandoverReadyMessage(world, cx, cz, id, ready)` | current owner finished saving (`ready=true`) or keeps the chunk (`ready=false`) |
| `ReportWeatherMessage(world, rain, rainTime, thunder, thunderTime)` | local weather transition for the master to sequence |
| `SyncChunkOwnerToAllMessage`, `SyncChunkSubscribersMessage`, `SyncEntitiesSubscribersMessage` | ask for authoritative state |
| `SubscribeChunkMessage`, `UnsubscribeChunkMessage` | block change subscription |
| `SubscribeEntitiesMessage`, `UnsubscribeEntitiesMessage` | entity subscription |
| `ChunkChangedStatusMessage(world, cx, cz, status)` | inform the master about load/unload (also recorded by `ChunkStatusTracker`) |
| `RequestEntityIdBlock` | reserve a block of cluster-unique entity ids (reply `IntegerPairMessageReply(start, end)`) |
| `WillSaveChunkLaterMessage`, `WillSaveEntitiesLaterMessage` | hold reads until the write arrives |
| `ReadChunkMessage(world, path, cx, cz)` | read chunk data (region/entities/poi) |
| `ForceReadChunkMessage` | read without peer redirection |
| `WriteChunkMessage(world, path, cx, cz, data, transientEntities)` | store chunk data |
| `ReadLevelMessage` / `WriteLevelMessage` | level.dat |
| `ReadUidMessage` / `WriteUidMessage` | uid.dat |
| `ReadPlayerMessage` / `WritePlayerMessage` | player .dat |
| `ReadStatsMessage` / `WriteStatsMessage` | statistics JSON |
| `ReadAdvancementMessage` / `WriteAdvancementsMessage` | advancements JSON |
| `ReadJsonMessage` / `WriteJsonMessage` | whitelist/ops/ban lists; a write is broadcast to the other servers as `DataUpdateMessage` so their running `PlayerList` reloads |
| `ReadDataMessage` / `WriteDataMessage` | arbitrary shared file |
| `UploadFileMessage` / `DownloadFileMessage` / `RequestFilesToSyncMessage` | shared file store |
| `CallDataStorageMessage` | key/value storage used by integrations |

### Master -> server

| Message | Purpose |
| --- | --- |
| `SetSecretMessage` | cluster secret (used by the built-in proxy) |
| `SetChunkOwnerMessage(world, cx, cz, owner, epoch)` | ownership changed; empty owner = unowned; epoch fences stale peer packets |
| `ChunkHandoverStartMessage(world, cx, cz, id)` | current owner must freeze, save and answer ready |
| `ChunkHandoverCompleteMessage(world, cx, cz, id, success, owner, epoch)` | final handover result for both participants |
| `WeatherStateMessage(world, version, rain, rainTime, thunder, thunderTime)` | master-ordered weather; a receiver drops an older version |
| `ChunkSubscribersSyncMessage(owner, subscribers[], epoch)` | full state of a chunk |
| `AddChunkSubscriberMessage` / `RemoveChunkSubscriberMessage` | subscription changes |
| `AddEntitySubscriberMessage` / `RemoveEntitySubscriberMessage` | entity subscription changes |
| `EntitySubscribersSyncMessage(subscribers[])` | full entity subscriber state |
| `ServerChangedChunkStatusMessage` | another server reported a chunk status |
| `ServerInfoUpdateMessage(name, tickTime, tps, playerCount)` | health of a server |
| `ServerStartedMessage(name, host, port)` | a peer is reachable at its peer port |
| `PlayerUpdateMessage(uuid, name, server, online)` | cluster player list |
| `DataUpdateMessage(path, data)` | another server wrote a shared/user-list file; receivers reload it |
| `DataMessageReply`, `BooleanMessageReply`, `NullableStringMessageReply`, `KeyValueStringMapMessageReply`, `IntegerPairMessageReply` | replies |
| `ChunkLoadedOnAnotherServerMessage(server)` | chunk read must go to a peer |
| `FileContentMessage(path, lastModified, streamId)` | file download follows on a data stream |
| `DownloadProgressMessage(path, streamId, transferred, total)` | advisory progress for that download, at least once per MiB and once with `transferred == total` |
| `FilesToSyncMessage(files[])` | shared file inventory |
| `DataUpdateMessage(path, data)` | real-time file update |
| `ShutdownMessage` | master asks the server to stop |

`ServerInfoUpdateMessage`, `ServerStartedMessage` and `PlayerUpdateMessage` were extended
compared to MultiPaper (player count, server name, player registry) because Forge has no
Bukkit API to query them locally.

## 3. Data plane (peer protocol)

Frame layout:

```
varint frameLength, varint uncompressedLength, varint packetId, payload
```

* `uncompressedLength == 0` means the payload is stored verbatim; otherwise it is zlib
  compressed and the field holds the inflated size. The decoder is stateless with respect
  to compression, so handshake timing cannot corrupt the stream.
* The encoder starts compressing only after the accepting side sent
  `SetCompressionPacket(ZLIB)` and flushed it.
* Compression is negotiated only for non-loopback peers when
  `server.compressionThreshold > 0`.

### Handshake

1. The connecting side sends `HelloPacket(name, nanoTime, compressionFlags)`.
2. The accepting side answers with its own hello, registers the connection and, when both
   sides support zlib and the peer is remote, sends `SetCompressionPacket`.
3. Duplicate connections between two servers are resolved by the higher `nanoTime`; the
   older connection is closed.

### Packets (ids are positional, append only)

| Id | Packet | Purpose |
| --- | --- | --- |
| 0 | `HelloPacket` | identity, connection age, compression flags |
| 1 | `SetCompressionPacket` | enable/disable zlib |
| 2 | `KeepAlivePacket` | heartbeat |
| 3 | `RequestChunkPacket(requestId, world, x, z)` | ask for the newest chunk copy |
| 4 | `SendChunkPacket(requestId, world, x, z, data)` | compressed chunk NBT, empty = not available |
| 5 | `BlockChangePacket(world, pos, stateId, epoch)` | block update broadcast |
| 6 | `BlockEntityUpdatePacket(world, pos, nbt, epoch)` | block entity update |
| 7 | `ChatMessagePacket(json, sender, origin)` | cluster-wide chat |
| 8 | `TimeUpdatePacket` | legacy level time (no longer broadcast) |
| 9 | `WeatherUpdatePacket` | rain/thunder + timers |
| 10 | `GameRuleUpdatePacket` | single gamerule |
| 11 | `WorldBorderUpdatePacket` | border state |
| 12 | `EntityChunkChangedPacket` | entity data of a chunk changed |
| 13 | `LevelDataUpdatePacket` | level data changes |
| 14 | `TimeSyncPacket` | time with game time + force flag (see below) |
| 15 | `EntityUpdateNBTPacket` | spawn/refresh a mirror copy from entity NBT |
| 16 | `EntityUpdatePacket` | one serialized clientbound entity packet (movement, metadata, ...) |
| 17 | `EntityRemovePacket` | a mirrored entity was removed |
| 18 | `EntityUpdateWithDependenciesPacket` | an entity packet that references other entities (passengers, leash, pickups) |
| 19 | `EntityHurtPacket` | damage on a mirror with attacker/direct UUIDs and source position |
| 20 | `PlayerCreatePacket` | profile, position, game mode and player data for a mirror copy |
| 21 | `PlayerRemovePacket` | a mirrored player left the cluster |
| 22 | `PlayerInventoryUpdatePacket` | one changed inventory/armor/off-hand/ender-chest slot |
| 23 | `PlayerSelectedSlotPacket` | selected hotbar slot of a mirrored player |
| 24 | `PlayerFoodUpdatePacket` | food, saturation and exhaustion of a mirrored player |
| 25 | `PlayerExperiencePacket` | experience progress, level and total of a mirrored player |
| 26 | `PlayerEffectsUpdatePacket` | one status effect added, refreshed or removed |
| 27 | `OpenContainerPacket(world, pos, player)` | a player opened a container owned by the receiver |
| 28 | `ContainerClickPacket(world, pos, player, changes[])` | changed container slots after a menu click |
| 29 | `ContainerClosePacket(world, pos, player)` | the forwarded container window closed |
| 30 | `ScoreboardUpdatePacket(criteria, packetBytes)` | one clientbound scoreboard packet (`SetObjective`, `SetScore`, `SetDisplayObjective`, `SetPlayerTeam`) |
| 31 | `PlayerStatsIncreasePacket(uuid, entries[])` | batched statistic deltas (type id, value id, delta) |
| 32 | `AdvancementProgressPacket(uuid, advancement, criterion)` | one advancement criterion was granted |
| 33 | `ChunkSequenceAckPacket(world, x, z, epoch, contiguous)` | highest contiguous block/block-entity sequence a peer applied |
| 34 | `PlayerListInfoPacket(uuid, displayName, gameMode)` | live tab-list display name / game-mode change of a mirrored player |
| 35 | `MobNavigationPacket(world, uuid, path, speed, target)` | path destination and combat target of a mob crossing an ownership boundary |
| 36 | `BossEventPacket(world, data)` | one encoded vanilla boss-bar packet (dragon/raid/plugin bars) |

The entity family (15-19) is the M1 mirroring protocol. Ids 20-21 extend it to players and
ids 22-26 carry live player state; they are described in
[SYNCHRONIZATION.md](SYNCHRONIZATION.md#players). Ids 27-29 forward container windows
([SYNCHRONIZATION.md](SYNCHRONIZATION.md#containers)), id 30 synchronizes the main scoreboard,
and ids 31-32 carry live statistics/advancement progress
([SYNCHRONIZATION.md](SYNCHRONIZATION.md#scoreboards-statistics-and-advancements)). Id 33 is
the sequencing acknowledgement described below, id 34 carries live tab-list name/game-mode
changes of a mirrored player, id 35 carries the navigation/combat target of a mob across
an ownership handover, and id 36 forwards vanilla boss-bar packets. Ids were appended, so
older peers keep their ids.

### Ownership epochs

`BlockChangePacket` and `BlockEntityUpdatePacket` append a `long epoch`. The master
increments it on every ownership change and announces it in `SetChunkOwnerMessage` and
`ChunkSubscribersSyncMessage`; the mod stamps outgoing packets with the chunk's current
epoch and drops a received packet whose epoch is older than the newest one it has seen for
that chunk (from the master or from an earlier peer packet). This is a fence against a
delayed update produced by the previous owner. Because the field is appended, a peer
running an older build omits it and decoding yields epoch 0 ("no information", always
applied).

### Chunk sequences and acknowledgements

`BlockChangePacket` and `BlockEntityUpdatePacket` append a second `long sequence` after the
epoch. Every sender numbers its own updates per chunk monotonically, restarting at one when
the epoch changes. The receiver keeps one `SequenceTracker` per sender
(`SenderSequenceTable`), because more than one server can change a block in a chunk while
ownership settles; sharing a counter would make independent streams look like duplicates.
Each tracker orders its stream:

* the next expected number is applied (`ACCEPTED`);
* a number above the next expected one is applied but reported as a gap from that point on
  (`GAP`). A gap wider than the receiver window (128, equal to the sender history) or the first
  packet of a sender the tracker has never seen adopts the newest value as the baseline, so an
  unrepairable hole cannot stall the acknowledgement stream forever;
* an already applied or older number is dropped (`DUPLICATE`);
* anything from an older epoch is dropped (`STALE_EPOCH`).

The receiver batches one `ChunkSequenceAckPacket` per changed chunk and sending peer per tick
with that sender's highest *contiguous* sequence. The sender keeps the last 128 updates of
each chunk **per peer** in a `RetransmitBuffer`; an acknowledgement below what it sent makes it
resend the missing entries on that connection. The history is per peer because one server's
acknowledgement must not discard entries another server still needs, and it survives a
reconnect with the same cluster name. Entries acknowledged by a newer epoch are discarded,
because a new owner starts its own numbering. Acknowledgements are optional for correctness on
a single TCP connection (TCP preserves order); they matter after a reconnect, when a peer was
disconnected while the owner kept sending, and they turn a silent inconsistency into a
detected gap.

Both fields are appended, so a peer running an older build omits them and decoding yields 0
("no sequencing information", applied as before). Updates without a positive epoch *or*
sequence are never acknowledged, because an ack with contiguous 0 would make the sender
retransmit them forever; the sender also does not keep retransmit history for them. The new
packet id (33) requires both sides to run a build that knows it; peer protocols are upgraded
together with the cluster.

`EntityHurtPacket` also appends optional fields: the causing entity UUID, the direct entity
UUID and the source position. The owner resolves them and builds a real `DamageSource`, so
death messages and kill credit name a player who hit from another server. Field order is
`damageType`, attacker flag+UUID, direct flag+UUID, position flag+xyz; absent fields decode
as `null`.

### Time synchronization

`TimeSyncPacket` carries `gameTime`, `dayTime` and a `force` flag. Periodic updates are
applied only when the receiver's game time is more than one second behind; a forced update
(command, sleep skip, clock change) is always applied. This is the upstream MultiPaper
semantics and prevents another server's periodic broadcast from immediately overwriting a
`/time set`. The older `TimeUpdatePacket` (id 8) remains registered for rolling upgrades but
is no longer broadcast.

MultiPaper's peer protocol has ~80 packet types (inventory, ender chest, dragon fight,
raids, mob navigation, ...). MultiForge implements the subset needed by the ported systems;
the rest is tracked in [TODO.md](TODO.md). Ids are appended, so adding packets is safe.

## 4. Master built-in proxy

The master can expose a TCP load-balancing proxy (`proxy-port`). It accepts Minecraft
connections, picks the least loaded live server from the master's registry and splices the
stream (`ProxyServer`, pure NIO). MultiPaper's proxy is the same design; MultiForge ported
it unchanged except for package names and logging.

## 5. Configuration reference

Master: `multiforge-master.properties` (`address`, `port`, `proxy-port`,
`allow-multiple-connections`, `ping-timeout-millis`, `generation-lease-millis`,
`generation-wait-millis`, `metrics-port`, `metrics-address`, `leader-election`,
`leader-retry-millis`) plus
`-Dmultiforge.master.storage` for the storage root. `metrics-port=-1` disables the
Prometheus endpoint; `/metrics` then reports the counters listed in
[TODO.md](TODO.md#low-priority--polish).

Server: `config/multiforge-server.toml` (a Forge `COMMON` config, so it is not world-specific;
see `MultiForgeConfig`):

```toml
[master]
address = "localhost:35353"
pingIntervalSeconds = 5
reconnectDelaySeconds = 2
ioTimeoutSeconds = 30
tpsSyncIntervalTicks = 20

[server]
name = "server1"       # empty = auto generated
peerPort = 0           # 0 = ephemeral
advertiseHost = ""
bungeeCord = false     # accept legacy BungeeCord/Velocity forwarding (trusted proxy only)
velocityModern = false # accept Velocity modern forwarding (HMAC login plugin)
velocitySecret = ""    # Velocity forwarding.secret; required for modern forwarding
compressionThreshold = 0

[synchronization]
chunks = true
entities = true
players = true
world = true
keepLocalChunkBackup = false
chunkOwnershipRequestIntervalTicks = 10
chunkUnloadGraceTicks = 100
levelDat = true
gameRules = true
time = true
weather = true
worldBorder = true
jsonFiles = true
playerData = true
statistics = true
advancements = true
scoreboards = true
filesToSyncOnStartup = []
filesToSyncInRealTime = []
filesToNotSync = ["logs", "crash-reports"]

[debug]
logging = false
chunkBorders = false
```
