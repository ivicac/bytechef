/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.ee.platform.ai.agent.catalog.CatalogChatClientResolver;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.configuration.context.EnvironmentContext;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class PropertyCopilotGeneratorImplTest {

    private final WorkflowNodeOutputFacade workflowNodeOutputFacade = mock(WorkflowNodeOutputFacade.class);
    private final Evaluator evaluator = mock(Evaluator.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static ChatResponse buildChatResponse(String text) {
        Generation generation = new Generation(new AssistantMessage(text));

        return ChatResponse.builder()
            .generations(List.of(generation))
            .build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<CatalogChatClientResolver> emptyCatalogChatClientResolverProvider() {
        ObjectProvider<CatalogChatClientResolver> catalogChatClientResolverProvider = mock(ObjectProvider.class);

        when(catalogChatClientResolverProvider.getIfAvailable()).thenReturn(null);

        return catalogChatClientResolverProvider;
    }

    @SuppressWarnings("unchecked")
    private PropertyCopilotGeneratorImpl generatorReturning(String llmText) {
        ChatModel chatModel = chatModelMock();
        ChatResponse chatResponse = buildChatResponse(llmText);

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong()))
            .thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        return new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(), List.of(),
            workflowNodeOutputFacade, meterRegistryProvider, "", emptyCatalogChatClientResolverProvider());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGenerateUsesCatalogResolvedChatClientWhenAvailable() {
        EnvironmentContext.clear();

        ChatModel chatModel = chatModelMock();
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CatalogChatClientResolver catalogChatClientResolver = mock(CatalogChatClientResolver.class);

        when(catalogChatClientResolver.resolveDefault(Environment.STAGING.ordinal())).thenReturn(chatClient);
        // The generator re-guards the catalog-resolved client before using it, so the deep-stub chain has to survive
        // a mutate()/defaultAdvisors()/build() round trip; returning the same mock keeps the stubs below reachable and
        // makes an unguarded catalog branch (one that skipped mutate()) fail here rather than pass silently.
        when(chatClient.mutate()
            .defaultAdvisors(anyList())
            .build()).thenReturn(chatClient);
        when(chatClient.prompt(any(String.class))
            .call()
            .content()).thenReturn("a constant value");
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong())).thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        ObjectProvider<CatalogChatClientResolver> catalogChatClientResolverProvider = mock(ObjectProvider.class);

        when(catalogChatClientResolverProvider.getIfAvailable()).thenReturn(catalogChatClientResolver);

        PropertyCopilotGeneratorImpl generator = new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(), List.of(),
            workflowNodeOutputFacade, meterRegistryProvider, "", catalogChatClientResolverProvider);

        try {
            PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
                "value", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true,
                Environment.STAGING.ordinal()));

            assertThat(result.value()).isEqualTo("a constant value");

            verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        } finally {
            EnvironmentContext.clear();
        }
    }

    @Test
    void testGenerateFallsBackToChatModelWhenNoCatalogResolver() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("a constant value");

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "value", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("a constant value");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGenerateBindsRequestEnvironmentDuringChatModelCall() {
        EnvironmentContext.clear();

        ChatModel chatModel = chatModelMock();
        AtomicReference<Environment> observedEnvironment = new AtomicReference<>();

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(invocation -> {
            observedEnvironment.set(EnvironmentContext.getCurrentEnvironment());

            return buildChatResponse("a constant value");
        });
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong())).thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        PropertyCopilotGeneratorImpl generator = new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(), List.of(),
            workflowNodeOutputFacade, meterRegistryProvider, "", emptyCatalogChatClientResolverProvider());

        try {
            generator.generate(new PropertyCopilotRequest(
                "value", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true,
                Environment.STAGING.ordinal()));

            assertThat(observedEnvironment.get()).isEqualTo(Environment.STAGING);
            assertThat(EnvironmentContext.fetchCurrentEnvironment()).isNull();
        } finally {
            EnvironmentContext.clear();
        }
    }

    @Test
    void testTextModeReturnsValueVerbatim() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("Hello ${trigger_1.firstName}");

        // pill resolves -> evaluator returns the substituted value (no surviving ${...})
        when(evaluator.evaluate(any(), any(), eq(true))).thenReturn(Map.of("value", "Hello Ada"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "greet", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("Hello ${trigger_1.firstName}");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    void testTextModeReturnsConstantWithoutPillsAsValid() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("a constant value");

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "value", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("a constant value");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTextModeUnresolvedPillThenRepaired() {
        ChatModel chatModel = chatModelMock();
        ChatResponse bad = buildChatResponse("Hi ${missing.name}");
        ChatResponse good = buildChatResponse("Hi ${trigger_1.firstName}");

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(bad, good);
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong())).thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        // first pill stays unresolved (${...} survives), second resolves
        when(evaluator.evaluate(any(), any(), eq(true)))
            .thenReturn(Map.of("value", "${missing.name}"))
            .thenReturn(Map.of("value", "Ada"));

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        PropertyCopilotGeneratorImpl generator = new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(), List.of(),
            workflowNodeOutputFacade, meterRegistryProvider, "", emptyCatalogChatClientResolverProvider());

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "greet", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("Hi ${trigger_1.firstName}");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testTextModeStillUnresolvedAfterRepairReturnsInvalid() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("Hi ${missing.name}");

        when(evaluator.evaluate(any(), any(), eq(true))).thenReturn(Map.of("value", "${missing.name}"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "greet", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("Hi ${missing.name}");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void testFormulaModeStripsFencesAndValidates() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("```\n=upperCase(${trigger_1.city})\n```");
        // evaluator does not throw -> valid
        when(evaluator.evaluate(any(), any(), eq(false))).thenReturn(Map.of("value", "PARIS"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "uppercase city", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("=upperCase(${trigger_1.city})");
        assertThat(result.valid()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFormulaModeInvalidThenRepaired() {
        ChatModel chatModel = chatModelMock();
        ChatResponse bad = buildChatResponse("=bogus(");
        ChatResponse good = buildChatResponse("=concat(${a})");

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(bad, good);
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong())).thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        // first validate throws, second succeeds
        when(evaluator.evaluate(any(), any(), eq(false)))
            .thenThrow(new RuntimeException("parse error"))
            .thenReturn(Map.of("value", "x"));

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        PropertyCopilotGeneratorImpl generator = new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(), List.of(),
            workflowNodeOutputFacade, meterRegistryProvider, "", emptyCatalogChatClientResolverProvider());

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "concat a", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("=concat(${a})");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testFormulaModeStillInvalidAfterRepairReturnsInvalid() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("=bogus(");

        when(evaluator.evaluate(any(), any(), eq(false))).thenThrow(new RuntimeException("parse error"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "x", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", true, 0));

        assertThat(result.value()).isEqualTo("=bogus(");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
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
