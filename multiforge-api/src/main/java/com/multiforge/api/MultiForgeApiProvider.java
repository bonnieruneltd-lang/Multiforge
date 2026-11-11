package com.multiforge.api;

import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Process-wide access point for the {@link MultiForgeApi} implementation.
 *
 * <p>The master, the Forge mod and test fixtures all need to reach cluster
 * state from contexts where dependency injection is unavailable (mixin hooks,
 * message handlers). This holder centralises that lookup.</p>
 *
 * <p>Thread-safety: {@link #setApi(MultiForgeApi)} is called once during mod
 * construction, {@link #api()} may be called from any thread afterwards. The
 * field is volatile; no locking is required.</p>
 */
public final class MultiForgeApiProvider {

    private static volatile MultiForgeApi api;

    private MultiForgeApiProvider() {
    }

    /**
     * Registers the live implementation. Called by the Forge mod during startup
     * and by test harnesses.
     *
     * @param implementation live API implementation
     */
    public static void setApi(MultiForgeApi implementation) {
        api = Objects.requireNonNull(implementation, "implementation");
    }

    /**
     * @return the live implementation
     * @throws IllegalStateException if called before the mod finished starting
     */
    public static MultiForgeApi api() {
        MultiForgeApi current = api;
        if (current == null) {
            current = loadFromServiceLoader();
            api = current;
        }
        return current;
    }

    /**
     * @return the implementation if one has been registered, without throwing
     */
    public static Optional<MultiForgeApi> peek() {
        return Optional.ofNullable(api);
    }

    /**
     * Removes the registered implementation. Only used by tests to isolate
     * cases; production code never calls this.
     */
    public static void reset() {
        api = null;
    }

    private static MultiForgeApi loadFromServiceLoader() {
        return ServiceLoader.load(MultiForgeApi.class).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MultiForge is not initialised yet; no MultiForgeApi implementation is registered"));
    }
}
