/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ToggleProjectDeploymentToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void testEnableCallsFacadeWithTrue() throws Exception {
        ProjectDeploymentFacade facade = mock(ProjectDeploymentFacade.class);

        ToggleProjectDeploymentToolCallback callback = new ToggleProjectDeploymentToolCallback(facade);

        String result = callback.call("{\"projectDeploymentId\":\"7\",\"enabled\":true}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("projectDeploymentId")
            .asLong()).isEqualTo(7L);
        assertThat(node.get("enabled")
            .asBoolean()).isTrue();

        verify(facade).enableProjectDeployment(7L, true);
    }

    @Test
    void testDisableCallsFacadeWithFalse() throws Exception {
        ProjectDeploymentFacade facade = mock(ProjectDeploymentFacade.class);

        ToggleProjectDeploymentToolCallback callback = new ToggleProjectDeploymentToolCallback(facade);

        String result = callback.call("{\"projectDeploymentId\":\"7\",\"enabled\":false}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("enabled")
            .asBoolean()).isFalse();

        verify(facade).enableProjectDeployment(7L, false);
    }

    @Test
    void testRejectsMissingEnabled() throws Exception {
        ToggleProjectDeploymentToolCallback callback = new ToggleProjectDeploymentToolCallback(
            mock(ProjectDeploymentFacade.class));

        String result = callback.call("{\"projectDeploymentId\":\"7\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
        assertThat(node.get("error")
            .asText()).contains("enabled");
    }
}
