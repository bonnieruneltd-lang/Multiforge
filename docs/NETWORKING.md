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

* `MessageLengthEncoder/Decoder` add/remove the length prefix.
* `MessageEncoder/Decoder` write/read the two ids and call `Message#write`/`Message(ExtendedByteBuf)`.
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
| `StartMessage(host, port)` | announce the peer listener |
| `SetPortMessage(port)` | announce the peer port only |
| `WriteTickTimeMessage(tickTime, tps)` | report server health |
| `PlayerConnectMessage(uuid, name)` | claim a player (reply boolean: allowed) |
| `PlayerDisconnectMessage(uuid)` | release a player |
| `LockChunkMessage(world, cx, cz)` | request ownership (first come, first served) |
| `UnlockChunkMessage` | release ownership |
| `RequestChunkOwnershipMessage(world, keys[])` | force-take a batch of chunks (redstone control) |
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
| `ReadJsonMessage` / `WriteJsonMessage` | whitelist/ops/ban lists |
| `ReadDataMessage` / `WriteDataMessage` | arbitrary shared file |
| `UploadFileMessage` / `DownloadFileMessage` / `RequestFilesToSyncMessage` | shared file store |
| `CallDataStorageMessage` | key/value storage used by integrations |
| `RequestEntityIdBlock` | reserved for entity id allocation |

### Master -> server

| Message | Purpose |
| --- | --- |
| `SetSecretMessage` | cluster secret (used by the built-in proxy) |
| `SetChunkOwnerMessage(world, cx, cz, owner)` | ownership changed; empty owner = unowned |
| `AddChunkSubscriberMessage` / `RemoveChunkSubscriberMessage` | subscription changes |
| `AddEntitySubscriberMessage` / `RemoveEntitySubscriberMessage` | entity subscription changes |
| `ChunkSubscribersSyncMessage(owner, subscribers[])` | full state of a chunk |
| `EntitySubscribersSyncMessage(subscribers[])` | full entity subscriber state |
| `ServerChangedChunkStatusMessage` | another server reported a chunk status |
| `ServerInfoUpdateMessage(name, tickTime, tps, playerCount)` | health of a server |
| `ServerStartedMessage(name, host, port)` | a peer is reachable |
| `PlayerUpdateMessage(uuid, name, server, online)` | cluster player list |
| `DataMessageReply`, `BooleanMessageReply`, `NullableStringMessageReply`, `KeyValueStringMapMessageReply`, `IntegerPairMessageReply` | replies |
| `ChunkLoadedOnAnotherServerMessage(server)` | chunk read must go to a peer |
| `FileContentMessage(path, lastModified, streamId)` | file download follows on a data stream |
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
| 5 | `BlockChangePacket(world, pos, stateId)` | block update broadcast |
| 6 | `BlockEntityUpdatePacket(world, pos, nbt)` | block entity update |
| 7 | `ChatMessagePacket(json, sender, origin)` | cluster-wide chat |
| 8 | `TimeUpdatePacket` | level time |
| 9 | `WeatherUpdatePacket` | rain/thunder + timers |
| 10 | `GameRuleUpdatePacket` | single gamerule |
| 11 | `WorldBorderUpdatePacket` | border state |
| 12 | `EntityChunkChangedPacket` | entity data of a chunk changed |
| 13 | `LevelDataUpdatePacket` | level data changes |

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
`allow-multiple-connections`, `ping-timeout-millis`) plus `-Dmultiforge.master.storage` for
the storage root.

Server: `config/multiforge-server.toml` (see `MultiForgeConfig`):

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
filesToSyncOnStartup = []
filesToSyncInRealTime = []
filesToNotSync = ["logs", "crash-reports"]

[debug]
logging = false
chunkBorders = false
```
