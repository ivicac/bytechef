/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.security.domain.ResourceVisibilityPolicyRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AiHubChatVisibilityPolicy} declares a default that {@link ResourceVisibilityPolicyRegistry} accepts,
 * and that {@code ORGANIZATION} is out of reach for a chat.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatVisibilityPolicyTest {

    private final ResourceVisibilityPolicyRegistry registry =
        new ResourceVisibilityPolicyRegistry(List.of(new AiHubChatVisibilityPolicy()));

    @Test
    void testDefaultIsPrivate() {
        assertThat(registry.defaultVisibility(AiHubChatVisibilityPolicy.RESOURCE_TYPE))
            .isEqualTo(ResourceVisibility.PRIVATE);
    }

    @Test
    void testOrganizationIsNotSupported() {
        assertThat(registry.supports(AiHubChatVisibilityPolicy.RESOURCE_TYPE, ResourceVisibility.PRIVATE)).isTrue();
        assertThat(registry.supports(AiHubChatVisibilityPolicy.RESOURCE_TYPE, ResourceVisibility.WORKSPACE)).isTrue();
        assertThat(registry.supports(AiHubChatVisibilityPolicy.RESOURCE_TYPE, ResourceVisibility.ORGANIZATION))
            .as("a chat belongs to at most one workspace; there is no representation for it outside that workspace")
            .isFalse();
    }
}
