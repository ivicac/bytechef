/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
    private PropertyCopilotGeneratorImpl generatorReturning(String llmText) {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse chatResponse = buildChatResponse(llmText);

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong()))
            .thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(any(), any(), anyLong()))
            .thenReturn(Map.of());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        return new PropertyCopilotGeneratorImpl(
            chatModel, evaluator, new PropertyCopilotPromptBuilder(), List.of(), workflowNodeOutputFacade,
            meterRegistryProvider);
    }

    @Test
    void testTextModeReturnsValueVerbatim() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("Hello ${trigger_1.firstName}");

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "greet", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", 0));

        assertThat(result.value()).isEqualTo("Hello ${trigger_1.firstName}");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    void testFormulaModeStripsFencesAndValidates() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("```\n=upperCase(${trigger_1.city})\n```");
        // evaluator does not throw -> valid
        when(evaluator.evaluate(any(), any(), eq(false))).thenReturn(Map.of("value", "PARIS"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "uppercase city", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", 0));

        assertThat(result.value()).isEqualTo("=upperCase(${trigger_1.city})");
        assertThat(result.valid()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFormulaModeInvalidThenRepaired() {
        ChatModel chatModel = mock(ChatModel.class);
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
            chatModel, evaluator, new PropertyCopilotPromptBuilder(), List.of(), workflowNodeOutputFacade,
            meterRegistryProvider);

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "concat a", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", 0));

        assertThat(result.value()).isEqualTo("=concat(${a})");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testFormulaModeStillInvalidAfterRepairReturnsInvalid() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("=bogus(");

        when(evaluator.evaluate(any(), any(), eq(false))).thenThrow(new RuntimeException("parse error"));

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "x", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", 0));

        assertThat(result.value()).isEqualTo("=bogus(");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void testJsonSchemaModeStripsFencesAndValidates() {
        PropertyCopilotGeneratorImpl generator = generatorReturning(
            "```json\n{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}\n```");

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "order schema", PropertyCopilotMode.JSON_SCHEMA, "wf1", "node2", "responseSchema", "STRING", 0));

        assertThat(result.value()).isEqualTo("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJsonSchemaModeInvalidThenRepaired() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse bad = buildChatResponse("not json at all");
        ChatResponse good = buildChatResponse("{\"type\":\"object\",\"properties\":{}}");

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(bad, good);
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(any(), any(), anyLong())).thenReturn(List.of());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        PropertyCopilotGeneratorImpl generator = new PropertyCopilotGeneratorImpl(
            chatModel, evaluator, new PropertyCopilotPromptBuilder(), List.of(), workflowNodeOutputFacade,
            meterRegistryProvider);

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "order schema", PropertyCopilotMode.JSON_SCHEMA, "wf1", "node2", "responseSchema", "STRING", 0));

        assertThat(result.value()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testJsonSchemaModeStillInvalidAfterRepairReturnsInvalid() {
        PropertyCopilotGeneratorImpl generator = generatorReturning("definitely not json");

        PropertyCopilotResult result = generator.generate(new PropertyCopilotRequest(
            "order schema", PropertyCopilotMode.JSON_SCHEMA, "wf1", "node2", "responseSchema", "STRING", 0));

        assertThat(result.value()).isEqualTo("definitely not json");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
    }
}
