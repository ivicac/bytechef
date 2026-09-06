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

package com.bytechef.platform.ai.guardrails;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * Decides whether a call's conversation id may key a token store that outlives the request.
 *
 * <p>
 * Every chat surface already publishes {@link ChatMemory#CONVERSATION_ID} as an advisor param, and Spring AI turns
 * advisor params into the request context every advisor reads — so an id is always visible. Visibility is not trust:
 * the canvas AI Agent's id is a workflow-author expression, and two end users sharing one such id would share one token
 * store, turning the privacy feature into a cross-user disclosure.
 * </p>
 *
 * <p>
 * The marker is what separates them. Only platform code that ISSUED the id sets {@link #PLATFORM_ISSUED_KEY} alongside
 * it; a workflow author cannot reach that code. An id without the marker is treated as author-supplied and keys
 * nothing.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class ConversationScope {

    /**
     * Set by the caller that issued the conversation id, asserting the platform can prove it identifies one
     * conversation of one user.
     */
    public static final String PLATFORM_ISSUED_KEY = "bytechef.guardrails.conversationPlatformIssued";

    /** The verified end user the conversation belongs to, published by the same caller as a {@code Long}. */
    public static final String USER_ID_KEY = "bytechef.guardrails.conversationUserId";

    private ConversationScope() {
    }

    /**
     * Returns the store key for this call, or empty when anything required is missing or untrusted.
     *
     * @param context     the advisor context
     * @param workspaceId the workspace the call resolved, or {@code null}
     * @return the key, or empty for a request-scoped call
     */
    public static Optional<Key> trustedKey(Map<String, ?> context, @Nullable Long workspaceId) {
        if (workspaceId == null || !Boolean.TRUE.equals(context.get(PLATFORM_ISSUED_KEY))) {
            return Optional.empty();
        }

        if (!(context.get(ChatMemory.CONVERSATION_ID) instanceof String conversationId) || conversationId.isBlank()) {
            return Optional.empty();
        }

        if (!(context.get(USER_ID_KEY) instanceof Number userId)) {
            return Optional.empty();
        }

        return Optional.of(new Key(workspaceId, userId.longValue(), conversationId));
    }

    /**
     * @param workspaceId    the workspace
     * @param userId         the verified end user
     * @param conversationId the platform-issued conversation id
     */
    public record Key(long workspaceId, long userId, String conversationId) {
    }
}
