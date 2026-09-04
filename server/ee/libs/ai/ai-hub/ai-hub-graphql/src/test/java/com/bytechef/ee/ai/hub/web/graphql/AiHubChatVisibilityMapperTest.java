/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.security.domain.ResourceVisibility;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatVisibilityMapperTest {

    @Test
    void testToResourceVisibilityMapsPrivate() {
        assertThat(AiHubChatVisibilityMapper.toResourceVisibility(AiHubChatVisibility.PRIVATE))
            .isEqualTo(ResourceVisibility.PRIVATE);
    }

    @Test
    void testToResourceVisibilityMapsWorkspace() {
        assertThat(AiHubChatVisibilityMapper.toResourceVisibility(AiHubChatVisibility.WORKSPACE))
            .isEqualTo(ResourceVisibility.WORKSPACE);
    }

    @Test
    void testToAiHubChatVisibilityMapsPrivate() {
        assertThat(AiHubChatVisibilityMapper.toAiHubChatVisibility(ResourceVisibility.PRIVATE))
            .isEqualTo(AiHubChatVisibility.PRIVATE);
    }

    @Test
    void testToAiHubChatVisibilityMapsWorkspace() {
        assertThat(AiHubChatVisibilityMapper.toAiHubChatVisibility(ResourceVisibility.WORKSPACE))
            .isEqualTo(AiHubChatVisibility.WORKSPACE);
    }

    /**
     * A chat can never legally carry ORGANIZATION ({@code AiHubChatVisibilityPolicy} does not support it), so this pins
     * the defensive failure mode rather than a silent, wrong mapping if a row were ever written with it anyway.
     */
    @Test
    void testToAiHubChatVisibilityRejectsOrganization() {
        assertThatThrownBy(() -> AiHubChatVisibilityMapper.toAiHubChatVisibility(ResourceVisibility.ORGANIZATION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ORGANIZATION");
    }
}
