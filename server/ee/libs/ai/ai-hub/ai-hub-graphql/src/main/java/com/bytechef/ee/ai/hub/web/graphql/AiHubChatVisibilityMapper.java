/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import com.bytechef.platform.security.domain.ResourceVisibility;

/**
 * Converts between the platform-wide {@link ResourceVisibility} the facade and service layers speak and the narrower
 * {@link AiHubChatVisibility} the GraphQL schema exposes. Kept at the controller boundary rather than the facade: the
 * facade and {@code ResourceVisibilityPolicyRegistry} remain the authority on what a chat supports, so this class is a
 * client-facing convenience, not a second enforcement point.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class AiHubChatVisibilityMapper {

    private AiHubChatVisibilityMapper() {
    }

    /**
     * Total: every {@link AiHubChatVisibility} value has a corresponding {@link ResourceVisibility} value.
     */
    static ResourceVisibility toResourceVisibility(AiHubChatVisibility visibility) {
        return switch (visibility) {
            case PRIVATE -> ResourceVisibility.PRIVATE;
            case WORKSPACE -> ResourceVisibility.WORKSPACE;
        };
    }

    /**
     * @throws IllegalStateException when {@code visibility} is {@code ORGANIZATION} — {@code AiHubChatVisibilityPolicy}
     *                               never supports that rung for a chat, so seeing it here means a row was written with
     *                               a visibility the service layer should have rejected.
     */
    static AiHubChatVisibility toAiHubChatVisibility(ResourceVisibility visibility) {
        return switch (visibility) {
            case PRIVATE -> AiHubChatVisibility.PRIVATE;
            case WORKSPACE -> AiHubChatVisibility.WORKSPACE;
            case ORGANIZATION -> throw new IllegalStateException(
                "AiHubChat visibility ORGANIZATION is not supported");
        };
    }
}
