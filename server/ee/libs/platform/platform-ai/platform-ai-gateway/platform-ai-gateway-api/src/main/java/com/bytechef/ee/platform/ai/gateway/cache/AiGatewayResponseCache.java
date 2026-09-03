/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.cache;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionResponse;
import org.jspecify.annotations.Nullable;

/**
 * @version ee
 */
public interface AiGatewayResponseCache {

    String AI_GATEWAY_RESPONSE_CACHE = "ai-gateway-response";

    AiGatewayChatCompletionResponse get(String cacheKey);

    AiGatewayChatCompletionResponse put(String cacheKey, AiGatewayChatCompletionResponse response);

    /**
     * Computes the cache key for {@code request}, scoped to {@code connectedUserId}. The key MUST include
     * {@code connectedUserId} (rather than request content alone): with BYOK (bring-your-own-key), which provider — and
     * whose credentials, and whose bill — serves an otherwise-identical request depends on which connected user is
     * asking. A request-content-only key was sound only while every request of a given shape resolved to the same
     * provider; it stopped being sound the moment provider resolution became connected-user-dependent. Without this,
     * connected user B's identical prompt would be served content generated on connected user A's key — A's account,
     * A's region, A's bill, with no audit trail for B — voiding a BYOK customer's data-residency guarantee.
     *
     * @param connectedUserId the connected user the request resolved to, or {@code null} for automation traffic
     */
    String computeCacheKey(AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId);

    boolean shouldCache(AiGatewayChatCompletionRequest request);
}
