package com.multiforge.master;

import com.multiforge.master.util.MasterStorage;
import com.multiforge.master.handlers.RequestEntityIdBlockHandler;
import com.multiforge.protocol.ChunkKey;
import com.multiforge.protocol.MessageBootstrap;
import com.multiforge.protocol.messages.masterbound.CallDataStorageMessage;
import com.multiforge.protocol.messages.masterbound.ChunkChangedStatusMessage;
import com.multiforge.protocol.messages.masterbound.DownloadFileMessage;
import com.multiforge.protocol.messages.masterbound.ForceReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.LockChunkMessage;
import com.multiforge.protocol.messages.masterbound.PlayerConnectMessage;
import com.multiforge.protocol.messages.masterbound.PlayerDisconnectMessage;
import com.multiforge.protocol.messages.masterbound.ReadChunkMessage;
import com.multiforge.protocol.messages.masterbound.ReadJsonMessage;
import com.multiforge.protocol.messages.masterbound.ReadLevelMessage;
import com.multiforge.protocol.messages.masterbound.ReadPlayerMessage;
import com.multiforge.protocol.messages.masterbound.ReadUidMessage;
import com.multiforge.protocol.messages.masterbound.RequestChunkOwnershipMessage;
import com.multiforge.protocol.messages.masterbound.RequestEntityIdBlock;
import com.multiforge.protocol.messages.masterbound.RequestFilesToSyncMessage;
import com.multiforge.protocol.messages.masterbound.SubscribeChunkMessage;
import com.multiforge.protocol.messages.masterbound.UnlockChunkMessage;
import com.multiforge.protocol.messages.masterbound.UploadFileMessage;
import com.multiforge.protocol.messages.masterbound.WriteChunkMessage;
import com.multiforge.protocol.messages.masterbound.WriteJsonMessage;
import com.multiforge.protocol.messages.masterbound.WriteLevelMessage;
import com.multiforge.protocol.messages.masterbound.WritePlayerMessage;
import com.multiforge.protocol.messages.masterbound.WriteUidMessage;
import com.multiforge.protocol.messages.serverbound.BooleanMessageReply;
import com.multiforge.protocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import com.multiforge.protocol.messages.serverbound.ChunkSubscribersSyncMessage;
import com.multiforge.protocol.messages.serverbound.DataMessageReply;
import com.multiforge.protocol.messages.serverbound.FilesToSyncMessage;
import com.multiforge.protocol.messages.serverbound.IntegerPairMessageReply;
import com.multiforge.protocol.messages.serverbound.NullableStringMessageReply;
import com.multiforge.protocol.messages.serverbound.PlayerUpdateMessage;
import com.multiforge.protocol.messages.serverbound.RemoveChunkSubscriberMessage;
import com.multiforge.protocol.messages.serverbound.ServerBoundMessage;
import com.multiforge.protocol.messages.serverbound.SetChunkOwnerMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the master against real TCP clients speaking the production
 * protocol.
 *
 * <p>These tests are the safety net for the distributed invariants MultiForge has
 * to guarantee: a chunk has at most one owner, ownership survives disconnects, a
 * player can only be online once, files round-trip byte for byte, and concurrent
 * requests never grant the same chunk twice.</p>
 *
 * <p>Each test uses its own world name (and often its own chunk coordinates) so the
 * shared master state does not leak between tests.</p>
 */
class MasterIntegrationTest {

    @TempDir
    static Path storage;

    private static MultiForgeMaster master;
    private static int port;
    private final List<TestServerClient> clients = new ArrayList<>();

    @BeforeAll
    static void startMaster() throws Exception {
        MasterStorage.setRoot(storage);
        master = new MultiForgeMaster("127.0.0.1", 0);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (master.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        port = master.getBoundPort();
        assertTrue(port > 0, "master should listen on an ephemeral port");
    }

    @AfterEach
    void closeClients() {
        for (TestServerClient client : clients) {
            client.close();
        }
        clients.clear();
    }

    @AfterAll
    static void stopMaster() {
        if (master != null) {
            MessageBootstrap.getEventLoopGroup().shutdownGracefully().awaitUninterruptibly();
        }
    }

    private TestServerClient client(String name) throws Exception {
        TestServerClient client = new TestServerClient(name).connect(port);
        clients.add(client);
        return client;
    }

    // ------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------

    @Test
    void firstLockWinsAndOtherServerSeesOwner() throws Exception {
        TestServerClient a = client("lock-a");
        TestServerClient b = client("lock-b");
        String world = "lock_world";

        a.send(new SubscribeChunkMessage(world, 0, 0));
        a.send(new LockChunkMessage(world, 0, 0));
        a.await(SetChunkOwnerMessage.class, message -> message.owner.equals("lock-a"), 5000L);

        b.send(new SubscribeChunkMessage(world, 0, 0));
        SetChunkOwnerMessage ownerSeenByB = b.await(SetChunkOwnerMessage.class,
                message -> message.owner.equals("lock-a"), 5000L);
        assertEquals(world, ownerSeenByB.world);
        assertEquals(0, ownerSeenByB.cx);

        // The master also tells the existing subscriber about the new subscriber.
        a.await(com.multiforge.protocol.messages.serverbound.AddChunkSubscriberMessage.class,
                message -> message.server.equals("lock-b"), 5000L);
    }

    @Test
    void unlockHandsOwnershipToWaitingServer() throws Exception {
        TestServerClient a = client("handover-a");
        TestServerClient b = client("handover-b");
        String world = "handover_world";

        a.send(new SubscribeChunkMessage(world, 1, 1));
        a.send(new LockChunkMessage(world, 1, 1));
        a.await(SetChunkOwnerMessage.class, message -> message.owner.equals("handover-a"), 5000L);

        b.send(new SubscribeChunkMessage(world, 1, 1));
        b.await(SetChunkOwnerMessage.class, message -> message.owner.equals("handover-a"), 5000L);

        a.send(new UnlockChunkMessage(world, 1, 1));

        // After the unlock the chunk is unowned; the second server can lock it.
        b.await(SetChunkOwnerMessage.class, message -> message.owner.isEmpty(), 5000L);
        b.send(new LockChunkMessage(world, 1, 1));
        b.await(SetChunkOwnerMessage.class, message -> message.owner.equals("handover-b"), 5000L);
    }

    @Test
    void concurrentLocksGrantExactlyOneOwner() throws Exception {
        String world = "concurrent_world";
        int clientCount = 4;
        List<TestServerClient> contenders = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            TestServerClient contender = client("contender-" + i);
            contender.send(new SubscribeChunkMessage(world, 5, 5));
            // Confirm the subscription was processed before racing for the lock;
            // ownership announcements only go to current subscribers.
            contender.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 5, 5));
            contender.await(ChunkSubscribersSyncMessage.class, message -> message.world.equals(world), 5000L);
            contenders.add(contender);
        }

        CountDownLatch start = new CountDownLatch(1);
        List<TestServerClient.Reply> replies = new ArrayList<>();
        for (TestServerClient contender : contenders) {
            replies.add(contender.request(new LockChunkMessage(world, 5, 5)));
        }
        start.countDown();

        // Lock requests are fire-and-forget in the MultiPaper protocol: ownership is
        // announced to every subscriber via SetChunkOwnerMessage, so the assertions
        // below count those announcements instead of waiting for a reply.
        int owners = 0;
        for (TestServerClient contender : contenders) {
            if (contender.seen(SetChunkOwnerMessage.class,
                    message -> message.world.equals(world) && message.cx == 5 && message.cz == 5
                            && message.owner.equals(contender.name()), 1000L)) {
                owners++;
            }
        }
        assertEquals(1, owners, "exactly one server must win the chunk");
    }

    @Test
    void ownershipIsReleasedWhenOwnerDisconnects() throws Exception {
        TestServerClient a = client("crash-a");
        TestServerClient b = client("crash-b");
        String world = "crash_world";

        a.send(new SubscribeChunkMessage(world, 2, 2));
        a.send(new LockChunkMessage(world, 2, 2));
        a.await(SetChunkOwnerMessage.class, message -> message.owner.equals("crash-a"), 5000L);
        b.send(new SubscribeChunkMessage(world, 2, 2));
        b.await(SetChunkOwnerMessage.class, message -> message.owner.equals("crash-a"), 5000L);

        a.close();

        // The master drops the subscription and the lock when the connection dies.
        b.await(RemoveChunkSubscriberMessage.class, message -> message.server.equals("crash-a"), 5000L);
        b.await(SetChunkOwnerMessage.class, message -> message.owner.isEmpty(), 5000L);
        b.send(new LockChunkMessage(world, 2, 2));
        b.await(SetChunkOwnerMessage.class, message -> message.owner.equals("crash-b"), 5000L);
    }

    @Test
    void subscribersSyncContainsEveryServer() throws Exception {
        TestServerClient a = client("sync-a");
        TestServerClient b = client("sync-b");
        String world = "sync_world";

        a.send(new SubscribeChunkMessage(world, 3, 3));
        b.send(new SubscribeChunkMessage(world, 3, 3));
        // Wait until the master saw b's subscription (a is notified as an existing
        // subscriber), then ask for the authoritative snapshot.
        a.await(com.multiforge.protocol.messages.serverbound.AddChunkSubscriberMessage.class,
                message -> message.server.equals("sync-b"), 5000L);
        a.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 3, 3));

        ChunkSubscribersSyncMessage sync = a.await(ChunkSubscribersSyncMessage.class,
                message -> message.world.equals(world), 5000L);
        assertTrue(sync.subscribers.length == 1);
        assertEquals("sync-b", sync.subscribers[0]);
    }

    // ------------------------------------------------------------------
    // Chunk data
    // ------------------------------------------------------------------

    @Test
    void chunkDataRoundTripsThroughMasterStorage() throws Exception {
        TestServerClient a = client("io-a");
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }

        BooleanMessageReply write = a.request(new WriteChunkMessage("io_world", "region", 4, 4, payload)).get();
        assertTrue(write.result);

        DataMessageReply read = a.request(new ForceReadChunkMessage("io_world", "region", 4, 4)).get();
        assertArrayEquals(payload, read.data);
    }

    @Test
    void missingChunkReadsAsEmptyData() throws Exception {
        TestServerClient a = client("missing-a");
        DataMessageReply read = a.request(new ForceReadChunkMessage("missing_world", "region", 30, 30)).get();
        assertEquals(0, read.data.length);
    }

    @Test
    void readIsRedirectedToServerHoldingTheChunk() throws Exception {
        TestServerClient a = client("holder-a");
        TestServerClient b = client("holder-b");
        String world = "holder_world";

        a.send(new SubscribeChunkMessage(world, 6, 6));
        // SyncChunkSubscribers is answered directly, which confirms the subscription
        // was processed before the read below.
        a.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 6, 6));
        a.await(ChunkSubscribersSyncMessage.class, message -> message.world.equals(world), 5000L);

        ServerBoundMessage reply = b.request(new ReadChunkMessage(world, "region", 6, 6)).get();
        assertTrue(reply instanceof ChunkLoadedOnAnotherServerMessage);
        assertEquals("holder-a", ((ChunkLoadedOnAnotherServerMessage) reply).server);
    }
    @Test
    void unloadingPeerIsNotChosenForChunkRead() throws Exception {
        TestServerClient a = client("unload-a");
        TestServerClient b = client("unload-b");
        String world = "unload_world";

        a.send(new SubscribeChunkMessage(world, 10, 10));
        // The sync reply proves the subscription was processed before the read below.
        a.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 10, 10));
        a.await(ChunkSubscribersSyncMessage.class, message -> message.world.equals(world), 5000L);

        // While a still reports the chunk as loaded the read is redirected to it.
        ServerBoundMessage redirected = b.request(new ReadChunkMessage(world, "region", 10, 10)).get();
        assertTrue(redirected instanceof ChunkLoadedOnAnotherServerMessage);

        // a is now unloading: a read must be served by the master instead of asking
        // a peer whose in-memory copy is about to disappear.
        a.clear();
        a.send(new ChunkChangedStatusMessage(world, 10, 10, "unloaded"));
        a.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 10, 10));
        a.await(ChunkSubscribersSyncMessage.class, message -> message.world.equals(world), 5000L);
        ServerBoundMessage fromMaster = b.request(new ReadChunkMessage(world, "region", 10, 10)).get();
        assertTrue(fromMaster instanceof DataMessageReply,
                "expected the master copy, got " + fromMaster.getClass().getSimpleName());

        // Once a loaded the chunk again the peer redirection is used again.
        a.clear();
        a.send(new ChunkChangedStatusMessage(world, 10, 10, "loaded"));
        a.send(new com.multiforge.protocol.messages.masterbound.SyncChunkSubscribersMessage(world, 10, 10));
        a.await(ChunkSubscribersSyncMessage.class, message -> message.world.equals(world), 5000L);
        ServerBoundMessage redirectedAgain = b.request(new ReadChunkMessage(world, "region", 10, 10)).get();
        assertTrue(redirectedAgain instanceof ChunkLoadedOnAnotherServerMessage,
                "expected a peer redirect, got " + redirectedAgain.getClass().getSimpleName());
    }

    @Test
    void entityIdBlocksAreDisjointAndPersisted() throws Exception {
        TestServerClient a = client("entityid-a");
        TestServerClient b = client("entityid-b");

        IntegerPairMessageReply first = a.request(new RequestEntityIdBlock()).get();
        IntegerPairMessageReply second = b.request(new RequestEntityIdBlock()).get();

        assertEquals(RequestEntityIdBlockHandler.blockSize(), first.y - first.x);
        assertEquals(first.y, second.x, "blocks must be adjacent and disjoint");
        assertTrue(first.x >= 0 && second.x >= 0, "entity ids must stay non-negative");

        RequestEntityIdBlockHandler.flush();
        Path persisted = MasterStorage.file("lastblock.txt").toPath();
        assertTrue(java.nio.file.Files.isRegularFile(persisted), "counter must be stored in the master root");
        assertEquals(Integer.toString(second.y),
                java.nio.file.Files.readString(persisted).trim(),
                "persisted counter must be the next unallocated id");

        // Simulate a master restart: the next block must continue from the persisted
        // counter instead of starting at zero (which would re-issue live entity ids).
        RequestEntityIdBlockHandler.resetForTests();
        IntegerPairMessageReply afterRestart = a.request(new RequestEntityIdBlock()).get();
        assertEquals(second.y, afterRestart.x, "allocation must resume from the persisted counter");
    }



    @Test
    void entityChunkWriteIsStored() throws Exception {
        TestServerClient a = client("entity-a");
        byte[] payload = new byte[] { 1, 2, 3, 4, 5 };
        BooleanMessageReply entityWrite = a.request(new WriteChunkMessage("entity_world", "entities", 7, 7, payload)).get();
        assertTrue(entityWrite.result);
        DataMessageReply read = a.request(new ForceReadChunkMessage("entity_world", "entities", 7, 7)).get();
        assertArrayEquals(payload, read.data);
    }

    @Test
    void forcedOwnershipLocksNeighbouringChunks() throws Exception {
        TestServerClient a = client("force-a");
        TestServerClient b = client("force-b");
        String world = "force_world";

        a.send(new SubscribeChunkMessage(world, 8, 8));
        a.send(new SubscribeChunkMessage(world, 9, 8));
        b.send(new SubscribeChunkMessage(world, 9, 8));
        a.send(new LockChunkMessage(world, 8, 8));
        a.await(SetChunkOwnerMessage.class, message -> message.owner.equals("force-a"), 5000L);

        // MultiPaper's "take control" path: a server that already owns a chunk may
        // re-assert ownership for a batch that includes neighbouring chunks it needs
        // for a redstone update.
        BooleanMessageReply forced = a.request(new RequestChunkOwnershipMessage(world,
                new ChunkKey[] { new ChunkKey(world, 8, 8), new ChunkKey(world, 9, 8) })).get();
        assertTrue(forced.result);

        // b subscribed to the neighbour, so it learns that a took control of it.
        b.await(SetChunkOwnerMessage.class,
                message -> message.world.equals(world) && message.cx == 9 && message.cz == 8
                        && message.owner.equals("force-a"), 5000L);
    }

    // ------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------

    @Test
    void duplicateLoginIsRejectedUntilOwnerReleases() throws Exception {
        TestServerClient a = client("player-a");
        TestServerClient b = client("player-b");
        UUID uuid = UUID.randomUUID();

        BooleanMessageReply first = a.request(new PlayerConnectMessage(uuid, "Alice")).get();
        assertTrue(first.result);
        BooleanMessageReply second = b.request(new PlayerConnectMessage(uuid, "Alice")).get();
        assertFalse(second.result);

        a.send(new PlayerDisconnectMessage(uuid));
        BooleanMessageReply retry = waitForLoginAllowed(b, uuid);
        assertTrue(retry.result);
    }

    private BooleanMessageReply waitForLoginAllowed(TestServerClient client, UUID uuid) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            BooleanMessageReply reply = client.request(new PlayerConnectMessage(uuid, "Alice")).get();
            if (reply.result) {
                return reply;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Login was never allowed");
    }

    @Test
    void playerJoinAndLeaveAreBroadcast() throws Exception {
        TestServerClient a = client("broadcast-a");
        TestServerClient b = client("broadcast-b");
        UUID uuid = UUID.randomUUID();

        BooleanMessageReply bob = a.request(new PlayerConnectMessage(uuid, "Bob")).get();
        assertTrue(bob.result);
        PlayerUpdateMessage join = b.await(PlayerUpdateMessage.class, message -> message.uuid.equals(uuid), 5000L);
        assertTrue(join.online);
        assertEquals("broadcast-a", join.server);
        assertEquals("Bob", join.playerName);

        a.send(new PlayerDisconnectMessage(uuid));
        PlayerUpdateMessage leave = b.await(PlayerUpdateMessage.class,
                message -> message.uuid.equals(uuid) && !message.online, 5000L);
        assertFalse(leave.online);
    }

    @Test
    void playerDataRoundTrips() throws Exception {
        TestServerClient a = client("pdata-a");
        byte[] data = "player-nbt-payload".getBytes(StandardCharsets.UTF_8);
        UUID uuid = UUID.randomUUID();

        BooleanMessageReply written = a.request(new WritePlayerMessage("pdata_world", uuid.toString(), data)).get();
        assertTrue(written.result);
        DataMessageReply read = a.request(new ReadPlayerMessage("pdata_world", uuid.toString())).get();
        assertArrayEquals(data, read.data);
    }

    // ------------------------------------------------------------------
    // Files and world data
    // ------------------------------------------------------------------

    @Test
    void sharedFileUploadDownloadRoundTrips() throws Exception {
        TestServerClient a = client("files-a");
        TestServerClient b = client("files-b");
        byte[] data = new byte[128 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }

        BooleanMessageReply upload = a.request(new UploadFileMessage(false, "plugins/demo/data.bin",
                System.currentTimeMillis(), data)).get();
        assertTrue(upload.result);

        FilesToSyncMessage files = a.request(new RequestFilesToSyncMessage()).get();
        assertTrue(files.filesToSync.length >= 1);
        assertEquals("plugins/demo/data.bin", files.filesToSync[0].path());

        byte[] downloaded = b.download("plugins/demo/data.bin").get(10, TimeUnit.SECONDS);
        assertArrayEquals(data, downloaded);
    }

    @Test
    void jsonLevelAndUidRoundTrip() throws Exception {
        TestServerClient a = client("json-a");
        byte[] json = "[{\"uuid\":\"x\"}]".getBytes(StandardCharsets.UTF_8);
        BooleanMessageReply jsonWritten = a.request(new WriteJsonMessage("ops.json", json)).get();
        assertTrue(jsonWritten.result);
        DataMessageReply jsonReply = a.request(new ReadJsonMessage("ops.json")).get();
        assertEquals(new String(json, StandardCharsets.UTF_8),
                new String(jsonReply.data, StandardCharsets.UTF_8));

        byte[] level = "level-dat".getBytes(StandardCharsets.UTF_8);
        BooleanMessageReply levelWritten = a.request(new WriteLevelMessage("level_world", level)).get();
        assertTrue(levelWritten.result);
        DataMessageReply levelReply = a.request(new ReadLevelMessage("level_world")).get();
        assertArrayEquals(level, levelReply.data);

        byte[] uid = "uid-dat".getBytes(StandardCharsets.UTF_8);
        BooleanMessageReply uidWritten = a.request(new WriteUidMessage("uid_world", uid)).get();
        assertTrue(uidWritten.result);
        DataMessageReply uidReply = a.request(new ReadUidMessage("uid_world")).get();
        assertArrayEquals(uid, uidReply.data);
    }

    @Test
    void dataStorageStoresValues() throws Exception {
        TestServerClient a = client("storage-a");
        a.request(new CallDataStorageMessage("demo.key", CallDataStorageMessage.Action.SET, "value-1")).get();
        NullableStringMessageReply reply = a.request(
                new CallDataStorageMessage("demo.key", CallDataStorageMessage.Action.GET, null)).get();
        assertEquals("value-1", reply.result);
    }

    @Test
    void secretIsSentOnHello() throws Exception {
        TestServerClient a = client("secret-a");
        assertFalse(a.name().isEmpty());
        // connect() already waited for the secret; reading it again proves the
        // message is recorded and non-empty.
        com.multiforge.protocol.messages.serverbound.SetSecretMessage secret =
                a.await(com.multiforge.protocol.messages.serverbound.SetSecretMessage.class, message -> true, 1000L);
        assertFalse(secret.secret.isEmpty());
    }
}
