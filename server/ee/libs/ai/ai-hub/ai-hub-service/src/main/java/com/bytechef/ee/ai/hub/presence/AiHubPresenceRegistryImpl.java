/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.presence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * {@link CacheManager}-backed implementation of {@link AiHubPresenceRegistry}. Stores one {@code HashMap<Long,
 * PresenceEntry>} per thread id in the cache, keyed by userId, so multiple concurrently-present users share a single
 * cache entry per thread.
 *
 * <p>
 * The stored map is read, mutated, and written back on every {@link #heartbeat} / {@link #leave} call. This
 * read-modify-write races between two users' heartbeats landing on the same thread at the same time — the loser's write
 * is silently overwritten by the winner's read-then-write. That is accepted rather than guarded with locking: the
 * loser's entry reappears on its own next heartbeat, well within the presence TTL, so the worst case is a momentary gap
 * in the roster rather than a lost or corrupted entry.
 * </p>
 *
 * <p>
 * Presence entries are filtered by {@link PresenceEntry#lastSeen} on every {@link #presence} read rather than relying
 * on the cache's own TTL to expire individual entries. The cache's TTL applies to the whole per-thread map, not to
 * individual users within it, so a map that still has one active user would otherwise keep every other user's stale
 * entry alive indefinitely.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubPresenceRegistryImpl implements AiHubPresenceRegistry {

    /**
     * Cache name for the per-thread presence map. Duplicated as a literal in {@code CacheConfiguration} because
     * {@code cache-config} cannot depend on {@code ai-hub} — the same split
     * {@code WorkflowChatGuard.IN_FLIGHT_CACHE_NAME} follows.
     */
    public static final String CACHE_NAME = "com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.presence";

    /**
     * How long a presence entry remains in {@link #presence} without a fresh heartbeat. Comfortably wider than the
     * client's 20-second heartbeat interval so a single missed heartbeat (a slow tick, a brief network hiccup) does not
     * flicker a still-present user off the roster.
     */
    static final long PRESENCE_TTL_SECONDS = 45L;

    private final CacheManager cacheManager;
    private final Clock clock;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiHubPresenceRegistryImpl(CacheManager cacheManager) {
        this(cacheManager, Clock.systemUTC());
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    AiHubPresenceRegistryImpl(CacheManager cacheManager, Clock clock) {
        this.cacheManager = cacheManager;
        this.clock = clock;
    }

    @Override
    public void heartbeat(String threadId, long userId, String userName, PresenceState state) {
        Cache cache = getCache();

        Map<Long, PresenceEntry> presenceByUserId = readPresenceMap(cache, threadId);

        presenceByUserId.put(userId, new PresenceEntry(userId, userName, state, Instant.now(clock)));

        cache.put(threadId, presenceByUserId);
    }

    @Override
    public void leave(String threadId, long userId) {
        Cache cache = getCache();

        Map<Long, PresenceEntry> presenceByUserId = readPresenceMap(cache, threadId);

        presenceByUserId.remove(userId);

        cache.put(threadId, presenceByUserId);
    }

    @Override
    public List<PresenceEntry> presence(String threadId) {
        Cache cache = getCache();

        Map<Long, PresenceEntry> presenceByUserId = readPresenceMap(cache, threadId);

        Instant staleBefore = Instant.now(clock)
            .minusSeconds(PRESENCE_TTL_SECONDS);

        return presenceByUserId.values()
            .stream()
            .filter(presenceEntry -> presenceEntry.lastSeen()
                .isAfter(staleBefore))
            .sorted(Comparator.comparingLong(PresenceEntry::userId))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, PresenceEntry> readPresenceMap(Cache cache, String threadId) {
        HashMap<Long, PresenceEntry> presenceByUserId = cache.get(threadId, HashMap.class);

        if (presenceByUserId == null) {
            return new HashMap<>();
        }

        return presenceByUserId;
    }

    private Cache getCache() {
        Cache cache = cacheManager.getCache(CACHE_NAME);

        if (cache == null) {
            throw new IllegalStateException(
                "AiHubPresenceRegistry cache '" + CACHE_NAME + "' is not configured. Add it to the CacheManager "
                    + "(Caffeine via registerCustomCache, Redis via withCacheConfiguration).");
        }

        return cache;
    }
}
