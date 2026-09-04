/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.PresenceEntry;
import com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.PresenceState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * Unit tests for {@link AiHubPresenceRegistryImpl}. Exercises the contract surface from {@code heartbeat} /
 * {@code leave} / {@code presence}: adding and updating a user's entry, removing it on leave, dropping stale entries on
 * read, and keeping threads isolated from one another.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubPresenceRegistryTest {

    private static final String THREAD_ID = "thread-1";
    private static final String OTHER_THREAD_ID = "thread-2";
    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Test
    void testHeartbeatAddsAndUpdates() {
        AiHubPresenceRegistryImpl registry = newRegistry(Instant.parse("2026-09-02T10:00:00Z"));

        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.VIEWING);
        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.TYPING);

        List<PresenceEntry> presence = registry.presence(THREAD_ID);

        assertThat(presence).hasSize(1);
        assertThat(presence.get(0)
            .userId()).isEqualTo(USER_ID);
        assertThat(presence.get(0)
            .state()).isEqualTo(PresenceState.TYPING);
    }

    @Test
    void testLeaveRemoves() {
        AiHubPresenceRegistryImpl registry = newRegistry(Instant.parse("2026-09-02T10:00:00Z"));

        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.VIEWING);
        registry.heartbeat(THREAD_ID, OTHER_USER_ID, "bob", PresenceState.VIEWING);

        registry.leave(THREAD_ID, USER_ID);

        List<PresenceEntry> presence = registry.presence(THREAD_ID);

        assertThat(presence).hasSize(1);
        assertThat(presence.get(0)
            .userId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void testStaleEntriesAreDroppedOnRead() {
        Instant heartbeatAt = Instant.parse("2026-09-02T10:00:00Z");

        ConcurrentMapCacheManager cacheManager =
            new ConcurrentMapCacheManager(AiHubPresenceRegistryImpl.CACHE_NAME);

        AiHubPresenceRegistryImpl registry = new AiHubPresenceRegistryImpl(
            cacheManager, Clock.fixed(heartbeatAt, ZoneOffset.UTC));

        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.VIEWING);

        AiHubPresenceRegistryImpl laterRegistry = new AiHubPresenceRegistryImpl(
            cacheManager, Clock.fixed(heartbeatAt.plusSeconds(46), ZoneOffset.UTC));

        List<PresenceEntry> presence = laterRegistry.presence(THREAD_ID);

        assertThat(presence).isEmpty();
    }

    @Test
    void testThreadsAreIsolated() {
        AiHubPresenceRegistryImpl registry = newRegistry(Instant.parse("2026-09-02T10:00:00Z"));

        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.VIEWING);
        registry.heartbeat(OTHER_THREAD_ID, OTHER_USER_ID, "bob", PresenceState.VIEWING);

        assertThat(registry.presence(THREAD_ID)).extracting(PresenceEntry::userId)
            .containsExactly(USER_ID);
        assertThat(registry.presence(OTHER_THREAD_ID)).extracting(PresenceEntry::userId)
            .containsExactly(OTHER_USER_ID);
    }

    /**
     * Both the default Caffeine backend and the {@link ConcurrentMapCacheManager} used here store the object reference
     * rather than a serialized copy, so a read that handed back the cached instance would let {@code heartbeat} and
     * {@code leave} mutate the very map {@code presence} is streaming — a {@code ConcurrentModificationException} out
     * of the {@code /status} poll, on a key several clients heartbeat and read concurrently. Reading the cached map and
     * then mutating through the registry is the deterministic form of that race.
     */
    @Test
    void testTheCachedMapIsNeverHandedOutForMutation() {
        ConcurrentMapCacheManager cacheManager =
            new ConcurrentMapCacheManager(AiHubPresenceRegistryImpl.CACHE_NAME);

        AiHubPresenceRegistryImpl registry = new AiHubPresenceRegistryImpl(
            cacheManager, Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC));

        registry.heartbeat(THREAD_ID, USER_ID, "alice", PresenceState.VIEWING);

        HashMap<?, ?> mapReadBeforeTheSecondHeartbeat = cacheManager.getCache(AiHubPresenceRegistryImpl.CACHE_NAME)
            .get(THREAD_ID, HashMap.class);

        registry.heartbeat(THREAD_ID, OTHER_USER_ID, "bob", PresenceState.VIEWING);
        registry.leave(THREAD_ID, USER_ID);

        assertThat(mapReadBeforeTheSecondHeartbeat).hasSize(1);
        assertThat(mapReadBeforeTheSecondHeartbeat.containsKey(USER_ID)).isTrue();
        assertThat(registry.presence(THREAD_ID)).extracting(PresenceEntry::userId)
            .containsExactly(OTHER_USER_ID);
    }

    private static AiHubPresenceRegistryImpl newRegistry(Instant now) {
        ConcurrentMapCacheManager cacheManager =
            new ConcurrentMapCacheManager(AiHubPresenceRegistryImpl.CACHE_NAME);

        return new AiHubPresenceRegistryImpl(cacheManager, Clock.fixed(now, ZoneOffset.UTC));
    }
}
