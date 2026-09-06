/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.sensitivedata;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists a conversation's minted tokens between turns.
 *
 * <p>
 * Implementations hold sensitive data — the map's values ARE the caller's real PII — and must encrypt it at rest.
 * Secrets never reach here: the engine redacts a {@code SECRET} span irreversibly and only ever mints a token for a
 * {@code PII} one, so a secret has no token to store.
 * </p>
 *
 * <p>
 * An implementation that fails must fail SOFT — return {@link LoadResult#unavailable()} from {@link #load}, swallow a
 * {@link #save} error after logging, and swallow an {@link #evict} error after logging — because a store outage should
 * cost cross-turn coherence, never the request itself. The caller then behaves exactly as a request-scoped call.
 * </p>
 *
 * <p>
 * A failed load must be DISTINGUISHABLE from a conversation that simply has no stored tokens, which is what
 * {@link LoadResult#available()} carries: the two look identical in the tokens they yield, but writing back over a
 * failed load replaces every token earlier turns accumulated with the one turn that could not read them, and those
 * values are then gone for good. A caller must therefore not call {@link #save} for a key whose load was unavailable.
 * </p>
 */
public interface PiiTokenSessionStore {

    /**
     * @param key the conversation
     * @return the stored token-to-value map, empty when the conversation has none, and {@link LoadResult#unavailable()}
     *         when the store could not answer at all
     */
    LoadResult load(SessionKey key);

    /**
     * Replaces the conversation's stored map. An empty map is stored as an absent session rather than an empty row.
     *
     * @param key    the conversation
     * @param tokens token text to value
     */
    void save(SessionKey key, Map<String, String> tokens);

    /**
     * Removes a conversation's tokens, on deletion of the conversation itself.
     *
     * @param workspaceId    the workspace
     * @param conversationId the conversation
     */
    void evict(long workspaceId, String conversationId);

    /**
     * @param workspaceId    the workspace
     * @param userId         the verified end user
     * @param conversationId the platform-issued conversation id
     */
    record SessionKey(long workspaceId, long userId, String conversationId) {
    }

    /**
     * What {@link #load} found, kept apart from what it could not read: {@code available} is false only when the store
     * itself failed, never when the conversation genuinely has no tokens.
     *
     * @param available whether the store answered
     * @param tokens    the stored token-to-value map, empty when the conversation has none and always empty when
     *                  {@code available} is false
     */
    record LoadResult(boolean available, Map<String, String> tokens) {

        /**
         * Defensively copies {@code tokens} so neither a caller-held map can be smuggled into this result nor the
         * result's own map handed back out for mutation — these values are the caller's real PII.
         */
        public LoadResult {
            tokens = Collections.unmodifiableMap(new HashMap<>(tokens));
        }

        /**
         * @param tokens the stored token-to-value map, possibly empty
         * @return a result carrying {@code tokens}
         */
        public static LoadResult of(Map<String, String> tokens) {
            return new LoadResult(true, tokens);
        }

        /**
         * @return the result of a load the store could not answer
         */
        public static LoadResult unavailable() {
            return new LoadResult(false, Map.of());
        }
    }
}
