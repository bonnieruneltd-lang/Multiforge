package com.multiforge.forge.chunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable cluster view of a single chunk.
 *
 * <p>One instance exists per chunk this server knows about, whether it is loaded
 * locally or only observed through master notifications. Fields are mutated on the
 * server thread (ownership, subscriptions) and read from the API/commands, so the
 * mutable collections are concurrent and the scalar fields are volatile.</p>
 */
public final class ChunkState {

    /** Name of the server that ticks the chunk, {@code null} when unowned. */
    private volatile String owner;

    /**
     * Derived fast-path flag: {@code true} when {@link #owner} is this server.
     *
     * <p>{@code shouldTick} runs once per chunk per tick and once per entity per
     * tick, so it reads this single flag instead of comparing owner strings or
     * probing a second concurrent set.</p>
     */
    private volatile boolean localOwner;

    /** Whether the chunk is currently loaded on this server. */
    private volatile boolean loaded;

    /** Whether this server subscribed to block changes of the chunk. */
    private volatile boolean subscribed;

    /** Whether this server subscribed to entity changes of the chunk. */
    private volatile boolean entitySubscribed;

    /** Last status reported by any server (loaded, unloading, unloaded). */
    private volatile String remoteStatus = "unknown";

    /** Server names that have the chunk loaded. */
    private final Set<String> subscribers = ConcurrentHashMap.newKeySet();

    /** Server names that have the chunk's entities loaded. */
    private final Set<String> entitySubscribers = ConcurrentHashMap.newKeySet();

    /** @return owning server name, or {@code null} */
    public String owner() {
        return owner;
    }

    /**
     * @param owner new owner, {@code null} when the chunk became unowned
     */
    public void owner(String owner) {
        this.owner = owner;
    }

    /** @return whether this server is the current owner */
    public boolean localOwner() {
        return localOwner;
    }

    /** @param localOwner whether this server is the current owner */
    public void localOwner(boolean localOwner) {
        this.localOwner = localOwner;
    }

    /** @return whether the chunk is loaded here */
    public boolean loaded() {
        return loaded;
    }

    /** @param loaded new local load state */
    public void loaded(boolean loaded) {
        this.loaded = loaded;
    }

    /** @return whether block changes are subscribed */
    public boolean subscribed() {
        return subscribed;
    }

    /** @param subscribed new subscription state */
    public void subscribed(boolean subscribed) {
        this.subscribed = subscribed;
    }

    /** @return whether entity changes are subscribed */
    public boolean entitySubscribed() {
        return entitySubscribed;
    }

    /** @param entitySubscribed new entity subscription state */
    public void entitySubscribed(boolean entitySubscribed) {
        this.entitySubscribed = entitySubscribed;
    }

    /** @return last reported remote status */
    public String remoteStatus() {
        return remoteStatus;
    }

    /** @param remoteStatus new remote status */
    public void remoteStatus(String remoteStatus) {
        this.remoteStatus = remoteStatus;
    }

    /** @return live set of servers that have the chunk loaded */
    public Set<String> subscribers() {
        return subscribers;
    }

    /** @return live set of servers that have the chunk's entities loaded */
    public Set<String> entitySubscribers() {
        return entitySubscribers;
    }

    /** @return whether this state carries no information worth keeping */
    public boolean isIdle() {
        return !loaded && !subscribed && !entitySubscribed && owner == null
                && subscribers.isEmpty() && entitySubscribers.isEmpty();
    }
}
