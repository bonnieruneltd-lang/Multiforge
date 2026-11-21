package com.multiforge.example;

import com.multiforge.api.ChunkLocation;
import com.multiforge.api.MultiForgeApi;
import com.multiforge.api.ServerInfo;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles and tests the example API consumer against a fake implementation.
 *
 * <p>This is the third-party integration test for {@code multiforge-api}: if a method is
 * removed, renamed or its contract changes, this module stops compiling even though the
 * Forge module's own implementation may have been updated.</p>
 */
class ExampleApiConsumerTest {

    private final MultiForgeApi api = new FakeApi();

    @Test
    void serverListIsSortedAndFormatted() {
        assertEquals(List.of(
                "live1 10.0.0.1:25565 2 players",
                "live2 10.0.0.2:25566 0 players (offline)"), ExampleApiConsumer.describeServers(api));
    }

    @Test
    void chunkAndPlayerHelpersDelegateToTheApi() {
        assertTrue(ExampleApiConsumer.isLocalChunk(api, "world", 1, 2));
        assertEquals(Optional.of("live1"), ExampleApiConsumer.chunkOwner(api, "world", 1, 2));
        assertEquals(2, ExampleApiConsumer.clusterPlayerCount(api));
        assertEquals(Optional.of("live1"),
                ExampleApiConsumer.playerServer(api, UUID.fromString(
                        "00000000-0000-0000-0000-000000000001")));
        assertTrue(ExampleApiConsumer.playerServer(api, UUID.randomUUID()).isEmpty());
    }

    @Test
    void unownedChunksAreReportedAsEmpty() {
        assertEquals(Optional.empty(), ExampleApiConsumer.chunkOwner(api, "world", 9, 9));
        assertFalse(ExampleApiConsumer.isLocalChunk(api, "world", 9, 9));
    }

    /** Minimal fake cluster used by the example tests. */
    private static final class FakeApi implements MultiForgeApi {

        @Override
        public String getLocalServerName() {
            return "live1";
        }

        @Override
        public boolean isConnectedToMaster() {
            return true;
        }

        @Override
        public Collection<ServerInfo> getServers() {
            return List.of(
                    new ServerInfo("live2", "10.0.0.2", 25566, 0.0, 0, false),
                    new ServerInfo("live1", "10.0.0.1", 25565, 20.0, 2, true));
        }

        @Override
        public Optional<ServerInfo> getServer(String name) {
            return getServers().stream().filter(info -> info.name().equals(name)).findFirst();
        }

        @Override
        public boolean isChunkLocal(ChunkLocation chunk) {
            return chunk.x() == 1 && chunk.z() == 2;
        }

        @Override
        public Optional<String> getChunkOwner(ChunkLocation chunk) {
            return isChunkLocal(chunk) ? Optional.of("live1") : Optional.empty();
        }

        @Override
        public Collection<String> getChunkSubscribers(ChunkLocation chunk) {
            return isChunkLocal(chunk) ? List.of("live1", "live2") : List.of();
        }

        @Override
        public Optional<String> getPlayerServer(UUID player) {
            return player.equals(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    ? Optional.of("live1") : Optional.empty();
        }

        @Override
        public Collection<UUID> getAllOnlinePlayers() {
            return List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"));
        }
    }
}
