/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.chat.AiHubChatService.AiHubChatMessage;
import com.bytechef.ee.ai.hub.exception.TitleGenerationFailedException;
import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import com.bytechef.platform.ai.guardrails.GuardrailSurface;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link TitleGenerationService}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class TitleGenerationServiceTest {

    private static final long WORKSPACE_ID = 7L;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderProvider;

    @InjectMocks
    private TitleGenerationService titleGenerationService;

    @Test
    void testGenerateTitleReturnsTrimmedResponse() {
        stubChatModel("  Create Workflow Automation  ");

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "How do I create a workflow?", Instant.now()),
            new AiHubChatMessage("ASSISTANT", "You can create a workflow by...", Instant.now()));

        String title = titleGenerationService.generateTitle(messages, WORKSPACE_ID);

        assertThat(title).isEqualTo("Create Workflow Automation");
    }

    @Test
    void testGenerateTitleReturnsEmptyWhenResponseIsBlank() {
        stubChatModel("   ");

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "Hello", Instant.now()));

        String title = titleGenerationService.generateTitle(messages, WORKSPACE_ID);

        assertThat(title).isEmpty();
    }

    @Test
    void testGenerateTitleTruncatesOverlyLongResponses() {
        String longResponse = "This is a very long title that exceeds the sixty character limit set by the service";

        assertThat(longResponse.length()).isGreaterThan(60);

        stubChatModel(longResponse);

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "Tell me something", Instant.now()),
            new AiHubChatMessage("ASSISTANT", "Sure, here is something", Instant.now()));

        String title = titleGenerationService.generateTitle(messages, WORKSPACE_ID);

        assertThat(title).isEmpty();
    }

    @Test
    void testGenerateTitleReturnsEmptyForEmptyMessageList() {
        String title = titleGenerationService.generateTitle(List.of(), WORKSPACE_ID);

        assertThat(title).isEmpty();
    }

    @Test
    void testGenerateTitleThrowsWhenChatModelFails() {
        RuntimeException upstream = new RuntimeException("upstream model 503");

        stubDefaultChatOptions();

        when(chatModel.call(any(Prompt.class))).thenThrow(upstream);

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "Hello", Instant.now()));

        // Pin: ChatModel failure must propagate as TitleGenerationFailedException so the controller can return
        // 503 instead of silently leaving the chat labelled "Untitled".
        assertThatThrownBy(() -> titleGenerationService.generateTitle(messages, WORKSPACE_ID))
            .isInstanceOf(TitleGenerationFailedException.class)
            .hasCauseReference(upstream);
    }

    /**
     * The point of routing title generation through a {@code ChatClient}: the workspace's guardrails advisor sits in
     * the chain and sees the prompt. This is the assertion the direct {@code chatModel.call(new Prompt(...))} could not
     * satisfy at all -- an advisor cannot intercept a call that never enters an advisor chain -- so reverting that one
     * line turns this test red.
     */
    @Test
    void testTheModelCallPassesThroughTheWorkspaceGuardrailsAdvisor() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = mock(AiGuardrailsAdvisorProvider.class);
        RecordingAdvisor recordingAdvisor = new RecordingAdvisor();

        when(aiGuardrailsAdvisorProviderProvider.getIfAvailable()).thenReturn(aiGuardrailsAdvisorProvider);
        when(aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(WORKSPACE_ID, GuardrailSurface.AI_HUB))
            .thenReturn(Optional.of(recordingAdvisor));

        stubChatModel("Guarded Title");

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "My card is 4111 1111 1111 1111", Instant.now()));

        String title = titleGenerationService.generateTitle(messages, WORKSPACE_ID);

        assertThat(title).isEqualTo("Guarded Title");
        assertThat(recordingAdvisor.advisedCall).isTrue();
    }

    /**
     * A null workspace still has to reach the provider -- it resolves the tenant-default settings row rather than
     * meaning "unguarded". Pinning it stops a future null-guard from turning the default case into a bypass.
     */
    @Test
    void testANullWorkspaceStillConsultsTheGuardrailsProvider() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = mock(AiGuardrailsAdvisorProvider.class);
        RecordingAdvisor recordingAdvisor = new RecordingAdvisor();

        when(aiGuardrailsAdvisorProviderProvider.getIfAvailable()).thenReturn(aiGuardrailsAdvisorProvider);
        when(aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(null, GuardrailSurface.AI_HUB))
            .thenReturn(Optional.of(recordingAdvisor));

        stubChatModel("Default Title");

        List<AiHubChatMessage> messages = List.of(
            new AiHubChatMessage("USER", "Hello", Instant.now()));

        String title = titleGenerationService.generateTitle(messages, null);

        assertThat(title).isEqualTo("Default Title");
        assertThat(recordingAdvisor.advisedCall).isTrue();
    }

    /**
     * A {@link org.springframework.ai.chat.client.ChatClient} asks the model for its default options while building the
     * request, before any call reaches the model. A bare mock answers null there and the client NPEs, so every test
     * that gets as far as the model has to supply them.
     */
    private void stubDefaultChatOptions() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder()
            .build());
    }

    private void stubChatModel(String responseText) {
        stubDefaultChatOptions();

        AssistantMessage assistantMessage = new AssistantMessage(responseText);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    /**
     * Records that the advisor chain actually ran, then delegates unchanged. A mocked {@code CallAdvisor} would not do:
     * the chain would have nothing to delegate to and the call would never reach the model, so the test could pass
     * without the model call happening at all.
     */
    private static final class RecordingAdvisor implements CallAdvisor {

        private boolean advisedCall;

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
            advisedCall = true;

            return callAdvisorChain.nextCall(chatClientRequest);
        }

        @Override
        public String getName() {
            return "RecordingAdvisor";
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }
}
