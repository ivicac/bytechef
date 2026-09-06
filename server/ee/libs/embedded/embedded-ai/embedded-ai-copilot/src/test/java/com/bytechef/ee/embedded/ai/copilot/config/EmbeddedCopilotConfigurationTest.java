/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.copilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agui.core.exception.AGUIException;
import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.ai.copilot.agent.OverrideChatClientResolver;
import com.bytechef.ai.copilot.agent.WorkflowEditorSpringAIAgent;
import com.bytechef.ai.copilot.agent.WorkflowExecutionSpringAIAgent;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolChatClientFactory;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.ee.embedded.ai.tool.IntegrationTools;
import com.bytechef.ee.embedded.ai.tool.IntegrationWorkflowExecutionTools;
import com.bytechef.ee.embedded.ai.tool.IntegrationWorkflowTools;
import com.bytechef.ee.embedded.ai.tool.ReadIntegrationTools;
import com.bytechef.ee.embedded.ai.tool.ReadIntegrationWorkflowTools;
import com.bytechef.ee.embedded.configuration.facade.IntegrationFacade;
import com.bytechef.ee.embedded.configuration.facade.IntegrationWorkflowFacade;
import com.bytechef.ee.embedded.workflow.execution.facade.IntegrationWorkflowExecutionFacade;
import com.bytechef.platform.ai.tool.ComponentTools;
import com.bytechef.platform.ai.tool.TaskTools;
import com.bytechef.platform.ai.tool.WorkflowInstructionTools;
import com.bytechef.platform.ai.tool.WorkflowValidatorTools;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class EmbeddedCopilotConfigurationTest {

    private static final Resource PROMPT_RESOURCE = new ByteArrayResource("prompt".getBytes(StandardCharsets.UTF_8));

    private final EmbeddedCopilotConfiguration embeddedCopilotConfiguration = new EmbeddedCopilotConfiguration(
        PROMPT_RESOURCE, PROMPT_RESOURCE, PROMPT_RESOURCE, PROMPT_RESOURCE, PROMPT_RESOURCE, PROMPT_RESOURCE,
        mock(WorkflowInstructionTools.class), mock(WorkflowValidatorTools.class),
        mock(CopilotGuardrailsAdvisorFactory.class));
    private final IntegrationTools integrationTools = new IntegrationTools(mock(IntegrationFacade.class));
    private final IntegrationWorkflowTools integrationWorkflowTools =
        new IntegrationWorkflowTools(mock(IntegrationWorkflowFacade.class));
    private final IntegrationWorkflowExecutionTools integrationWorkflowExecutionTools =
        new IntegrationWorkflowExecutionTools(mock(IntegrationWorkflowExecutionFacade.class));

    @Test
    void testWorkflowEditorEmbeddedAskAgentIsRegisteredUnderTheAskAgentId() throws AGUIException {
        WorkflowEditorSpringAIAgent workflowEditorSpringAIAgent =
            embeddedCopilotConfiguration.workflowEditorEmbeddedAskSpringAIAgent(
                mock(ChatMemory.class), mock(ChatModel.class), new ReadIntegrationTools(integrationTools),
                new ReadIntegrationWorkflowTools(integrationWorkflowTools), mock(ComponentTools.class),
                mock(TaskTools.class), Optional.empty(), Optional.empty(), mock(WorkflowService.class),
                mock(WorkflowNodeOutputFacade.class), mock(Advisor.class), mock(PermissionService.class),
                mock(SecurityContextRehydrator.class), emptyProvider());

        assertThat(workflowEditorSpringAIAgent.getAgentId()).isEqualTo("workflow_editor_embedded_ask");
    }

    @Test
    void testWorkflowEditorEmbeddedBuildAgentIsRegisteredUnderTheBuildAgentId() throws AGUIException {
        WorkflowEditorSpringAIAgent workflowEditorSpringAIAgent =
            embeddedCopilotConfiguration.workflowEditorEmbeddedBuildSpringAIAgent(
                mock(ChatMemory.class), mock(ChatModel.class), integrationTools, integrationWorkflowTools,
                mock(ComponentTools.class), mock(TaskTools.class), mock(WorkflowService.class),
                mock(WorkflowNodeOutputFacade.class), mock(PermissionService.class),
                mock(SecurityContextRehydrator.class), emptyProvider());

        assertThat(workflowEditorSpringAIAgent.getAgentId()).isEqualTo("workflow_editor_embedded_build");
    }

    @Test
    void testWorkflowExecutionEmbeddedAskAgentIsRegisteredUnderTheAskAgentId() throws AGUIException {
        WorkflowExecutionSpringAIAgent workflowExecutionSpringAIAgent =
            embeddedCopilotConfiguration.workflowExecutionEmbeddedAskSpringAIAgent(
                mock(ChatMemory.class), mock(ChatModel.class), integrationWorkflowExecutionTools,
                new ReadIntegrationWorkflowTools(integrationWorkflowTools), mock(ComponentTools.class),
                Optional.empty(), Optional.empty(), mock(SecurityContextRehydrator.class), emptyProvider());

        assertThat(workflowExecutionSpringAIAgent.getAgentId()).isEqualTo("workflow_execution_embedded_ask");
    }

    @Test
    void testWorkflowExecutionEmbeddedBuildAgentIsRegisteredUnderTheBuildAgentId() throws AGUIException {
        WorkflowExecutionSpringAIAgent workflowExecutionSpringAIAgent =
            embeddedCopilotConfiguration.workflowExecutionEmbeddedBuildSpringAIAgent(
                mock(ChatMemory.class), mock(ChatModel.class), integrationWorkflowExecutionTools,
                integrationWorkflowTools, mock(TaskTools.class), mock(SecurityContextRehydrator.class),
                emptyProvider());

        assertThat(workflowExecutionSpringAIAgent.getAgentId()).isEqualTo("workflow_execution_embedded_build");
    }

    @Test
    void testBuildSubAgentChatClientFactoryReturnsTheDefaultChatClientWithoutACandidateModel() {
        ChatClient defaultChatClient = mock(ChatClient.class);

        IntelligentToolChatClientFactory chatClientFactory =
            embeddedCopilotConfiguration.workflowEditorEmbeddedBuildSubAgentChatClientFactory(
                defaultChatClient, integrationTools, integrationWorkflowTools, mock(ComponentTools.class),
                mock(TaskTools.class));

        assertThat(chatClientFactory.get(null)).isSameAs(defaultChatClient);
    }

    @Test
    void testBuildSubAgentChatClientFactoryRebuildsTheChatClientForACandidateModel() {
        ChatClient defaultChatClient = mock(ChatClient.class);

        IntelligentToolChatClientFactory chatClientFactory =
            embeddedCopilotConfiguration.workflowEditorEmbeddedBuildSubAgentChatClientFactory(
                defaultChatClient, integrationTools, integrationWorkflowTools, mock(ComponentTools.class),
                mock(TaskTools.class));

        ChatClient candidateChatClient = chatClientFactory.get(mock(ChatModel.class));

        assertThat(candidateChatClient).isNotNull()
            .isNotSameAs(defaultChatClient);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OverrideChatClientResolver> emptyProvider() {
        return mock(ObjectProvider.class);
    }
}
