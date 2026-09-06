/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WorkflowDescriptionCopilotGeneratorImplTest {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static ChatResponse buildChatResponse(String text) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();
    }

    @SuppressWarnings("unchecked")
    private WorkflowDescriptionCopilotGeneratorImpl generatorReturning(String definition, String llmText) {
        ChatModel chatModel = chatModelMock();

        when(chatModel.call(any(Prompt.class))).thenReturn(buildChatResponse(llmText));

        Workflow workflow = mock(Workflow.class);

        when(workflow.getDefinition()).thenReturn(definition);
        when(workflowService.getWorkflow(any())).thenReturn(workflow);

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        return new WorkflowDescriptionCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), workflowService, new WorkflowDescriptionPromptBuilder(),
            meterRegistryProvider);
    }

    @Test
    void testGenerateWholeWorkflowDescriptionStripsFences() {
        WorkflowDescriptionCopilotGeneratorImpl generator = generatorReturning(
            "{\"label\":\"Sync\"}", "```\nSyncs records nightly.\n```");

        WorkflowDescriptionCopilotResult result = generator.generate(
            new WorkflowDescriptionCopilotRequest("wf1", null, 0));

        assertThat(result.value()).isEqualTo("Syncs records nightly.");
    }

    @Test
    void testGenerateNodeDescription() {
        WorkflowDescriptionCopilotGeneratorImpl generator = generatorReturning(
            "{\"tasks\":[{\"name\":\"node1\"}]}", "Sends a Slack message.");

        WorkflowDescriptionCopilotResult result = generator.generate(
            new WorkflowDescriptionCopilotRequest("wf1", "node1", 0));

        assertThat(result.value()).isEqualTo("Sends a Slack message.");
    }

    @Test
    void testGenerateStripsLanguageTaggedFences() {
        WorkflowDescriptionCopilotGeneratorImpl generator = generatorReturning(
            "{\"label\":\"Sync\"}", "```markdown\nSyncs records nightly.\n```");

        WorkflowDescriptionCopilotResult result = generator.generate(
            new WorkflowDescriptionCopilotRequest("wf1", null, 0));

        assertThat(result.value()).isEqualTo("Syncs records nightly.");
    }

    /**
     * A real {@link CopilotGuardrailsAdvisorFactory} with no {@code AiGuardrailsAdvisorProvider} available - the CE
     * shape. It attaches only the inert tool-boundary advisor, so these tests drive the real guarded-{@code ChatClient}
     * path the generator now takes while the model underneath is still reached through {@code chatModel.call(Prompt)} -
     * which is what they stub - and no EE guardrails module is needed on this module's test classpath.
     */
    @SuppressWarnings("unchecked")
    private static CopilotGuardrailsAdvisorFactory guardrailsAdvisorFactory() {
        return new CopilotGuardrailsAdvisorFactory(
            mock(ObjectProvider.class), new SensitiveDataRedactor(List.of()));
    }

    /**
     * A {@link ChatModel} mock with default options stubbed. {@code ChatClient.builder(chatModel)} - which the
     * generator now goes through so its prompt passes the guardrails advisors - reads the model's default options to
     * seed every request, so a bare mock ({@code getOptions()} returning null) fails before a prompt is ever built.
     * Real implementations return {@link ToolCallingChatOptions}.
     */
    private static ChatModel chatModelMock() {
        ChatModel chatModel = mock(ChatModel.class);

        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder()
            .build());

        return chatModel;
    }

}
