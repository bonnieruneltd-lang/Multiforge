package com.multiforge.forge.common;

import net.minecraft.server.level.ServerLevel;

/**
 * Tracks which level is currently being ticked on the calling thread.
 *
 * <p>{@code LevelTicks} (scheduled block and fluid ticks) has no reference to its
 * level, so the block-tick ownership filter needs to know which level the call
 * belongs to. {@code ServerLevelMixin} sets this thread local around
 * {@code ServerLevel.tick}. It is only read on the server thread.</p>
 */
public final class CurrentTickLevel {

    private static final ThreadLocal<ServerLevel> CURRENT = new ThreadLocal<>();

    private CurrentTickLevel() {
    }

    /**
     * @param level level that starts ticking
     */
    public static void set(ServerLevel level) {
        CURRENT.set(level);
    }

    /** @return the level currently ticking on this thread, or {@code null} */
    public static ServerLevel get() {
        return CURRENT.get();
    }

    /** Clears the thread local after the tick finished. */
    public static void clear() {
        CURRENT.remove();
    }
}
