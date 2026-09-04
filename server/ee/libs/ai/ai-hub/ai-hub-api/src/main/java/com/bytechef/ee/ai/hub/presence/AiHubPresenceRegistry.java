/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.presence;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Tracks which users are currently viewing or typing in a shared AI Hub chat, for the status poll's presence roster.
 * Backed by the same Caffeine/Redis dual cache backend {@code WorkflowChatGuard} uses (configured by
 * {@code com.bytechef.cache.config.CacheConfiguration}), so multi-instance deployments share presence state.
 *
 * <p>
 * A {@link PresenceEntry} expires from {@link #presence} after 45 seconds without a fresh {@link #heartbeat} — the
 * client sends a heartbeat every 20 seconds while a chat is open, so a viewer who closes the tab or loses connectivity
 * disappears from the roster within one missed cycle plus margin, without requiring an explicit {@link #leave} call.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubPresenceRegistry {

    /**
     * What a present user is currently doing on the chat. {@code TYPING} is a stronger signal than {@code VIEWING} —
     * the client sends {@code TYPING} while the composer has focus and reverts to {@code VIEWING} once it clears.
     */
    enum PresenceState {
        VIEWING, TYPING
    }

    /**
     * One user's presence on a thread as of {@code lastSeen}. Implements {@link Serializable} because the backing cache
     * may be Redis-backed, which requires every cached value to serialize.
     */
    record PresenceEntry(long userId, String userName, PresenceState state, Instant lastSeen)
        implements Serializable {
    }

    /**
     * Records that {@code userId} is present on {@code threadId} as of now, in the given {@code state}. Called by the
     * client's presence heartbeat while the chat is open, and again with {@code TYPING} on composer focus.
     */
    void heartbeat(String threadId, long userId, String userName, PresenceState state);

    /**
     * Removes {@code userId}'s presence entry from {@code threadId} immediately, rather than waiting for it to expire.
     * Called when the client explicitly signals it is leaving the chat (navigating away, closing the tab).
     */
    void leave(String threadId, long userId);

    /**
     * Returns the users currently present on {@code threadId} — every entry whose {@link PresenceEntry#lastSeen} is
     * recent enough to still be considered present, sorted by {@link PresenceEntry#userId}. Empty when nobody is
     * present or the thread has no cache entry.
     */
    List<PresenceEntry> presence(String threadId);
}
