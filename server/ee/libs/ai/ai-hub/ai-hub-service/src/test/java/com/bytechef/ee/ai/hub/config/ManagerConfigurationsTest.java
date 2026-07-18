/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.ee.ai.hub.personalagent.AiHubPersonalAgentService;
import com.bytechef.ee.ai.hub.task.AiHubTaskService;
import com.bytechef.ee.automation.apiplatform.configuration.facade.ApiCollectionFacade;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Covers the personal-agent, deployment, and API-collection manager subagent configurations: ChatClient construction
 * succeeds against mocks and the delegate ToolCallbacks carry the agent-type keys the ai_hub BUILD prompt references.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ManagerConfigurationsTest {

    @Test
    void testPersonalAgentManagerChatClientIsBuilt() {
        Resource promptResource = toPromptResource("You are the personal_agent_manager subagent.");

        PersonalAgentManagerConfiguration configuration = new PersonalAgentManagerConfiguration();

        assertThatNoException().isThrownBy(
            () -> configuration.personalAgentManagerChatClient(
                mock(AiHubPersonalAgentService.class), mock(AiHubTaskService.class), mock(ChatModel.class),
                promptResource));
    }

    @Test
    void testPersonalAgentManagerToolCallbackIsNamedCorrectly() {
        ToolCallback toolCallback = PersonalAgentManagerConfiguration.createPersonalAgentManagerToolCallback(
            mock(ChatClient.class));

        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("personal_agent_manager");
    }

    @Test
    void testDeploymentManagerChatClientIsBuilt() {
        Resource promptResource = toPromptResource("You are the deployment_manager subagent.");

        DeploymentManagerConfiguration configuration = new DeploymentManagerConfiguration();

        assertThatNoException().isThrownBy(
            () -> configuration.deploymentManagerChatClient(
                mock(ChatModel.class), mock(ProjectDeploymentFacade.class), promptResource));
    }

    @Test
    void testDeploymentManagerToolCallbackIsNamedCorrectly() {
        ToolCallback toolCallback = DeploymentManagerConfiguration.createDeploymentManagerToolCallback(
            mock(ChatClient.class));

        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("deployment_manager");
    }

    @Test
    void testApiCollectionManagerChatClientIsBuilt() {
        Resource promptResource = toPromptResource("You are the api_collection_manager subagent.");

        ApiCollectionManagerConfiguration configuration = new ApiCollectionManagerConfiguration();

        assertThatNoException().isThrownBy(
            () -> configuration.apiCollectionManagerChatClient(
                mock(ApiCollectionFacade.class), mock(ChatModel.class), promptResource));
    }

    @Test
    void testApiCollectionManagerToolCallbackIsNamedCorrectly() {
        ToolCallback toolCallback = ApiCollectionManagerConfiguration.createApiCollectionManagerToolCallback(
            mock(ChatClient.class));

        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("api_collection_manager");
    }

    private Resource toPromptResource(String prompt) {
        return new ByteArrayResource(prompt.getBytes(StandardCharsets.UTF_8), "test prompt resource");
    }
}
