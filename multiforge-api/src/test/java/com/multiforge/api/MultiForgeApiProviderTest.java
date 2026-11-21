package com.multiforge.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests and a minimal example implementation of the public API.
 *
 * <p>This is the "example mod" for the API module (TODO 20) in test form: a mod
 * only needs to implement {@link MultiForgeApi}, register it once with
 * {@link MultiForgeApiProvider#setApi(MultiForgeApi)} and call
 * {@link MultiForgeApiProvider#api()} from anywhere. No Minecraft classes are
 * involved, which is the point of the separate module.</p>
 *
 * <p>The tests also pin the holder behaviour: registration is visible to every
 * thread, {@code peek()} never throws, {@code reset()} isolates tests, and
 * calling {@code api()} before registration fails with an explanatory message
 * instead of a {@code NullPointerException}.</p>
 */
class MultiForgeApiProviderTest {

    private final MultiForgeApi example = new ExampleApi();

    @BeforeEach
    @AfterEach
    void resetHolder() {
        MultiForgeApiProvider.reset();
    }

    @Test
    void registeredImplementationIsReturnedToEveryCaller() {
        MultiForgeApiProvider.setApi(example);
        assertSame(example, MultiForgeApiProvider.api());
        assertEquals(Optional.of(example), MultiForgeApiProvider.peek());
        assertEquals("example", MultiForgeApiProvider.api().getLocalServerName());
    }

    @Test
    void resetMakesTheHolderEmptyAgain() {
        MultiForgeApiProvider.setApi(example);
        MultiForgeApiProvider.reset();
        assertTrue(MultiForgeApiProvider.peek().isEmpty());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                MultiForgeApiProvider::api);
        assertTrue(failure.getMessage().contains("not initialised"), failure.getMessage());
    }

    @Test
    void nullImplementationIsRejected() {
        assertThrows(NullPointerException.class, () -> MultiForgeApiProvider.setApi(null));
    }

    @Test
    void valueObjectsExposeStableKeysAndAddresses() {
        assertEquals("world_nether,-3,7", new ChunkLocation("world_nether", -3, 7).asKey());
        ServerInfo info = new ServerInfo("survival2", "10.0.0.5", 25566, 20.0, 3, true);
        assertEquals("10.0.0.5:25566", info.address());
        assertFalse(new ServerInfo("survival2", "10.0.0.5", -1, 0.0, 0, false).address().contains(":"));
    }

    /** Minimal API implementation used by the tests; a mod would replace the bodies. */
    private static final class ExampleApi implements MultiForgeApi {

        @Override
        public String getLocalServerName() {
            return "example";
        }

        @Override
        public boolean isConnectedToMaster() {
            return true;
        }

        @Override
        public Collection<ServerInfo> getServers() {
            return List.of(new ServerInfo("example", "127.0.0.1", 25565, 20.0, 0, true));
        }

        @Override
        public Optional<ServerInfo> getServer(String name) {
            return getServers().stream().filter(info -> info.name().equals(name)).findFirst();
        }

        @Override
        public boolean isChunkLocal(ChunkLocation chunk) {
            return true;
        }

        @Override
        public Optional<String> getChunkOwner(ChunkLocation chunk) {
            return Optional.of("example");
        }

        @Override
        public Collection<String> getChunkSubscribers(ChunkLocation chunk) {
            return List.of("example");
        }

        @Override
        public Optional<String> getPlayerServer(UUID player) {
            return Optional.empty();
        }

        @Override
        public Collection<UUID> getAllOnlinePlayers() {
            return List.of();
        }
    }
}
