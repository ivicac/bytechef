/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.sampleoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class SampleOutputCopilotGeneratorImplTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static ChatResponse buildChatResponse(String text) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();
    }

    @SuppressWarnings("unchecked")
    private SampleOutputCopilotGeneratorImpl generatorReturning(String... llmTexts) {
        ChatModel chatModel = mock(ChatModel.class);

        if (llmTexts.length == 1) {
            when(chatModel.call(any(Prompt.class))).thenReturn(buildChatResponse(llmTexts[0]));
        } else {
            when(chatModel.call(any(Prompt.class))).thenReturn(
                buildChatResponse(llmTexts[0]), buildChatResponse(llmTexts[1]));
        }

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        return new SampleOutputCopilotGeneratorImpl(
            chatModel, new SampleOutputPromptBuilder(), meterRegistryProvider);
    }

    @Test
    void testGenerateObjectStripsFencesAndValidates() {
        SampleOutputCopilotGeneratorImpl generator = generatorReturning(
            "```json\n{\"id\":1,\"items\":[]}\n```");

        SampleOutputCopilotResult result = generator.generate(
            new SampleOutputCopilotRequest("wf1", "order", 0));

        assertThat(result.value()).isEqualTo("{\"id\":1,\"items\":[]}");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    void testGenerateArrayIsValid() {
        SampleOutputCopilotGeneratorImpl generator = generatorReturning("[{\"id\":1},{\"id\":2}]");

        SampleOutputCopilotResult result = generator.generate(
            new SampleOutputCopilotRequest("wf1", "list of orders", 0));

        assertThat(result.value()).isEqualTo("[{\"id\":1},{\"id\":2}]");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testGenerateInvalidThenRepaired() {
        SampleOutputCopilotGeneratorImpl generator = generatorReturning(
            "not json", "{\"id\":1}");

        SampleOutputCopilotResult result = generator.generate(
            new SampleOutputCopilotRequest("wf1", "order", 0));

        assertThat(result.value()).isEqualTo("{\"id\":1}");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void testGenerateStillInvalidAfterRepairReturnsInvalid() {
        SampleOutputCopilotGeneratorImpl generator = generatorReturning("nope", "still not json");

        SampleOutputCopilotResult result = generator.generate(
            new SampleOutputCopilotRequest("wf1", "order", 0));

        assertThat(result.value()).isEqualTo("still not json");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
    }
}
