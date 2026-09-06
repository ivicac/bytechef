/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsTarget;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.guardrails.GuardrailSurface;
import com.bytechef.platform.ai.guardrails.RestorationDestination;
import com.bytechef.platform.ai.sensitivedata.tokenization.SensitiveDataPolicy;
import com.bytechef.platform.ai.sensitivedata.tokenization.SensitiveDataPolicyToolContext;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Pins {@link AiGuardrailsAdvisor#applyResponseGuardrails} gating a response restore on {@link RestorationDestination}
 * plus {@link AiGuardrails#isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget)}: a conversation always restores, a
 * workflow output restores only while the workspace setting stays on, and {@code restore_suppressed} is recorded once
 * per call, only when withholding actually changed something.
 *
 * <p>
 * Every test runs over a workspace with PII tokenization on ({@code redactPii}) and response scanning off, so the
 * request-direction pipeline mints a real token for {@code ada@example.com} into the call's own session and the mocked
 * chain echoes the (now tokenized) prompt straight back — the same technique
 * {@code AiGuardrailsAdvisorConversationScopeTest} uses, reused here so the token under test is the product of the real
 * tokenizing pipeline rather than a hand-built string that could drift from the real token shape.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class AiGuardrailsAdvisorRestorationDestinationTest {

    private static final Long WORKSPACE_ID = 42L;
    private static final String EMAIL = "ada@example.com";
    private static final String TOKEN_PREFIX = "[PII_EMAIL_ADDRESS_1_";

    private final AiGuardrailsWorkspaceSettingsService settingsService =
        mock(AiGuardrailsWorkspaceSettingsService.class);
    private final AiGuardrailMetrics metrics = mock(AiGuardrailMetrics.class);

    @Test
    void testAWorkflowOutputRestoresWhenTheSettingIsOn() {
        String responseText = echoedResponseText(RestorationDestination.WORKFLOW_OUTPUT, true);

        assertThat(responseText).contains(EMAIL);
    }

    @Test
    void testAWorkflowOutputKeepsTokensWhenTheSettingIsOff() {
        String responseText = echoedResponseText(RestorationDestination.WORKFLOW_OUTPUT, false);

        assertThat(responseText).doesNotContain(EMAIL);
        assertThat(responseText).contains(TOKEN_PREFIX);
    }

    @Test
    void testAConversationRestoresEvenWhenTheSettingIsOff() {
        String responseText = echoedResponseText(RestorationDestination.CONVERSATION, false);

        assertThat(responseText).contains(EMAIL);
    }

    @Test
    void testWithholdingRestorationIsRecorded() {
        echoedResponseText(RestorationDestination.WORKFLOW_OUTPUT, false);

        verify(metrics).recordRestoreSuppressed();
    }

    /**
     * The other half of D5: a call whose response carries no resolvable token must not tick the same counter a withheld
     * restoration does, or an admin reading it could never tell "nothing to restore" from "restoration withheld" -- the
     * one distinction the metric exists to preserve.
     */
    @Test
    void testASuppressedRestoreIsNotRecordedWhenThereWasNothingToRestore() {
        adviseCall(RestorationDestination.WORKFLOW_OUTPUT, false, staticChain("nothing sensitive here"));

        verify(metrics, never()).recordRestoreSuppressed();
    }

    /**
     * The pairing D8 turns on: destination {@code CONVERSATION} means this call's own RESPONSE always restores (it goes
     * back to whoever just supplied the value), but its OUTBOUND tool-call arguments are gated on the SURFACE, not on
     * {@link #destination} -- {@code AiGuardrailsAdvisor#withSessionInToolContext} resolves {@code workflowSurface}
     * from {@code GuardrailSurface.AI_AGENT.equals(surface)}, never from {@code destination} and never by reading
     * {@code metrics.getSurface()}. A tool call leaves the agent for a system the workflow author chose no matter how
     * the agent's own reply reaches the caller, so a streaming canvas AI Agent call still withholds its tool-call
     * arguments while the workspace setting is off, even though its response half always restores. Nothing else in the
     * suite pins this combination, and it is the one a future simplification -- keying tool-argument restoration off
     * {@code destination} instead of the surface -- would collapse.
     */
    @Test
    void testAStreamingAgentStillGatesItsToolArguments() {
        assertThat(toolContextPolicy().restoreOutboundArguments()).isFalse();
    }

    /**
     * Spec §7's streaming invariant: {@code adviseStream}'s flushed tail restores through
     * {@link AiGuardrails#newStreamingResponseRedactor(AiGuardrailsSettingsTarget, AiGuardrailMetrics, PiiTokenSession)},
     * which never consults {@link RestorationDestination} or {@code restoreIntoWorkflowOutput} at all -- unlike
     * {@code adviseCall}'s response path, gated in {@code AiGuardrailsAdvisor#applyResponseGuardrails}. Destination is
     * deliberately set to {@code WORKFLOW_OUTPUT} here, the combination that would withhold restoration on the
     * {@code adviseCall} path, so a regression that started routing the streaming tail through that same gate would
     * fail this test rather than passing by coincidence.
     */
    @Test
    void testAStreamingTailRestoresEvenWhenTheSettingIsOff() {
        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, WORKSPACE_ID, null, null, null, null, null, null, null, null, null,
            false)));

        AiGuardrails aiGuardrails = new AiGuardrails(
            settingsService, null, null, null, true, false, "", false, false, false, false);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(
            aiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID), metrics, GuardrailSurface.AI_AGENT,
            RestorationDestination.WORKFLOW_OUTPUT);

        ChatClientRequest request = requestWithUserMessage("mail " + EMAIL);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest forwardedRequest = invocation.getArgument(0);

            return Flux.just(responseChunk(userTextOf(forwardedRequest)));
        });

        List<ChatClientResponse> emitted = Objects.requireNonNull(
            advisor.adviseStream(request, chain)
                .collectList()
                .block(),
            "emitted");

        String combinedText = emitted.stream()
            .map(response -> {
                ChatResponse chatResponse = Objects.requireNonNull(response.chatResponse(), "chatResponse");
                Generation generation = Objects.requireNonNull(chatResponse.getResult(), "generation");

                return generation.getOutput()
                    .getText();
            })
            .collect(Collectors.joining());

        assertThat(combinedText).contains(EMAIL);
    }

    /**
     * The regression Task 3's review flagged: every other test in this suite builds a single-{@link Generation}
     * {@link ChatResponse}, so a mutation that moved {@code recordRestoreSuppressed()} back inside the per-generation
     * loop -- recording once per generation instead of once per call -- would pass the whole suite. Two generations,
     * both carrying a resolvable token, with restoration withheld: the event must still fire exactly once.
     */
    @Test
    void testRestoreSuppressedIsRecordedOnceEvenAcrossMultipleGenerations() {
        adviseCall(RestorationDestination.WORKFLOW_OUTPUT, false, twoGenerationEchoingChain());

        verify(metrics, times(1)).recordRestoreSuppressed();
    }

    /**
     * Finding I5: {@code withSessionInToolContext} and {@code applyResponseGuardrails} used to each call
     * {@link AiGuardrails#isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget)} independently, against an uncached
     * settings row -- two reads per call for a canvas AI Agent that makes a tool call, since that combination (surface
     * {@link GuardrailSurface#AI_AGENT}, destination {@link RestorationDestination#WORKFLOW_OUTPUT}, a forwarded
     * request carrying {@code ToolCallingChatOptions}) is exactly the one where both halves consult the setting. Spies
     * the real {@link AiGuardrails} built from the mocked {@code settingsService} -- rather than mocking
     * {@code AiGuardrails} outright -- so the rest of the call (tokenization, scanning, restoration) still runs for
     * real and only the call count on this one method is observed.
     */
    @Test
    void testTheRestorationSettingIsReadOncePerCall() {
        AiGuardrails spiedAiGuardrails = spiedAiGuardrails(true);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(
            spiedAiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID), metrics, GuardrailSurface.AI_AGENT,
            RestorationDestination.WORKFLOW_OUTPUT);

        advisor.adviseCall(requestWithToolCallingOptions("mail " + EMAIL), echoingChain());

        verify(spiedAiGuardrails, times(1)).isRestoreIntoWorkflowOutput(any());
    }

    /**
     * The subtle failure two independent reads produce: a toggle flipped between the request half and the response half
     * would let the tool arguments restore while the response is withheld (or vice versa). Stubbing the spy to answer
     * {@code false} then {@code true} models exactly that flip. This test also passes trivially if the fix resolves
     * once but resolves it in the WRONG half -- it is only meaningful paired with
     * {@link #testTheRestorationSettingIsReadOncePerCall}, which pins that exactly one read happens at all.
     */
    @Test
    void testTheRequestAndResponseHalvesCannotDisagree() {
        AiGuardrails spiedAiGuardrails = spiedAiGuardrails(false);

        doReturn(false, true).when(spiedAiGuardrails)
            .isRestoreIntoWorkflowOutput(any());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(
            spiedAiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID), metrics, GuardrailSurface.AI_AGENT,
            RestorationDestination.WORKFLOW_OUTPUT);

        ChatClientResponse response =
            advisor.adviseCall(requestWithToolCallingOptions("mail " + EMAIL), echoingChain());

        assertThat(textOf(response)).doesNotContain(EMAIL);
    }

    /**
     * Builds a real {@link AiGuardrails} over the mocked {@code settingsService} -- so tokenization, scanning and
     * restoration all run for real -- then wraps it in a Mockito spy so {@code isRestoreIntoWorkflowOutput} call counts
     * and return values can be observed/overridden without hand-mocking the rest of the engine's surface.
     */
    private AiGuardrails spiedAiGuardrails(boolean restoreIntoWorkflowOutput) {
        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, WORKSPACE_ID, null, null, null, null, null, null, null, null, null,
            restoreIntoWorkflowOutput)));

        AiGuardrails aiGuardrails = new AiGuardrails(
            settingsService, null, null, null, true, false, "", false, false, false, false);

        return spy(aiGuardrails);
    }

    /**
     * Resolves the {@link SensitiveDataPolicy} {@code AiGuardrailsAdvisor#withSessionInToolContext} carries onto the
     * forwarded request's {@code ToolContext} for a canvas AI Agent call (constructed with {@code surface} set to
     * {@link GuardrailSurface#AI_AGENT}) whose workspace has {@code restoreIntoWorkflowOutput} off -- mirrors
     * {@code AiGuardrailsAdvisorTest#testAdviseCallPutsTheResolvedToolBoundaryPolicyIntoTheForwardedToolContext}'s
     * capture technique.
     */
    private SensitiveDataPolicy toolContextPolicy() {
        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, WORKSPACE_ID, null, null, null, null, null, null, null, null, null,
            false)));

        AiGuardrails aiGuardrails = new AiGuardrails(
            settingsService, null, null, null, true, false, "", false, false, false, false);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(
            aiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID), metrics, GuardrailSurface.AI_AGENT,
            RestorationDestination.CONVERSATION);

        ChatClientRequest request = requestWithToolCallingOptions("mail " + EMAIL);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedRequestCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        when(chain.nextCall(forwardedRequestCaptor.capture())).thenReturn(ChatClientResponse.builder()
            .context(Map.of())
            .build());

        advisor.adviseCall(request, chain);

        return Objects.requireNonNull(
            SensitiveDataPolicyToolContext.from(capturedToolContext(forwardedRequestCaptor)));
    }

    private static ChatClientRequest requestWithToolCallingOptions(String text) {
        ChatOptions chatOptions = ToolCallingChatOptions.builder()
            .toolContext(Map.of())
            .build();

        return new ChatClientRequest(new Prompt(List.of(new UserMessage(text)), chatOptions), Map.of());
    }

    /**
     * Extracts the {@link ToolContext} the advisor forwarded, from the {@link ToolCallingChatOptions} carried on the
     * captured request's {@link Prompt} -- mirrors {@code AiGuardrailsAdvisorTest#capturedToolContext}.
     */
    private static @Nullable ToolContext capturedToolContext(ArgumentCaptor<ChatClientRequest> forwardedRequestCaptor) {
        ChatOptions chatOptions = forwardedRequestCaptor.getValue()
            .prompt()
            .getOptions();

        if (!(chatOptions instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return null;
        }

        Map<String, Object> toolContext = toolCallingChatOptions.getToolContext();

        return toolContext == null ? null : new ToolContext(toolContext);
    }

    /**
     * A chain answering with TWO generations, both echoing the request's own (post-tokenization) user text -- the
     * multi-generation counterpart of {@link #echoingChain()}, used to pin that {@code recordRestoreSuppressed()} fires
     * once per call rather than once per generation.
     */
    private static CallAdvisorChain twoGenerationEchoingChain() {
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);

        when(callAdvisorChain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest chatClientRequest = invocation.getArgument(0);
            String text = userTextOf(chatClientRequest);

            return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(
                    new Generation(new AssistantMessage(text)),
                    new Generation(new AssistantMessage(text)))))
                .context(chatClientRequest.context())
                .build();
        });

        return callAdvisorChain;
    }

    private String echoedResponseText(RestorationDestination destination, boolean restoreIntoWorkflowOutput) {
        ChatClientResponse response = adviseCall(destination, restoreIntoWorkflowOutput, echoingChain());

        return textOf(response);
    }

    private ChatClientResponse adviseCall(
        RestorationDestination destination, boolean restoreIntoWorkflowOutput, CallAdvisorChain chain) {

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.of(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, WORKSPACE_ID, null, null, null, null, null, null, null, null, null,
            restoreIntoWorkflowOutput)));

        AiGuardrails aiGuardrails = new AiGuardrails(
            settingsService, null, null, null, true, false, "", false, false, false, false);

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(
            aiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID), metrics, GuardrailSurface.AI_AGENT,
            destination);

        return advisor.adviseCall(requestWithUserMessage("mail " + EMAIL), chain);
    }

    /**
     * A chain answering with the request's own (post-tokenization) user text, so the response direction is exercised on
     * exactly the token the request direction just minted -- mirrors
     * {@code AiGuardrailsAdvisorConversationScopeTest#echoingChain}.
     */
    private static CallAdvisorChain echoingChain() {
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);

        when(callAdvisorChain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest chatClientRequest = invocation.getArgument(0);

            return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(
                    userTextOf(chatClientRequest))))))
                .context(chatClientRequest.context())
                .build();
        });

        return callAdvisorChain;
    }

    /**
     * A chain answering with fixed text carrying no token at all, regardless of what the request minted -- models a
     * response direction with nothing to restore.
     */
    private static CallAdvisorChain staticChain(String responseText) {
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);

        when(callAdvisorChain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest chatClientRequest = invocation.getArgument(0);

            return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(responseText)))))
                .context(chatClientRequest.context())
                .build();
        });

        return callAdvisorChain;
    }

    private static ChatClientRequest requestWithUserMessage(String text) {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage(text))), Map.of());
    }

    private static String textOf(ChatClientResponse chatClientResponse) {
        ChatResponse chatResponse = Objects.requireNonNull(chatClientResponse.chatResponse());
        Generation generation = Objects.requireNonNull(chatResponse.getResult());
        AssistantMessage assistantMessage = Objects.requireNonNull(generation.getOutput());

        return Objects.requireNonNull(assistantMessage.getText());
    }

    private static ChatClientResponse responseChunk(String text) {
        ChatResponse chatResponse = ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();

        return ChatClientResponse.builder()
            .chatResponse(chatResponse)
            .context(Map.of())
            .build();
    }

    private static String userTextOf(ChatClientRequest chatClientRequest) {
        Prompt prompt = chatClientRequest.prompt();

        return prompt.getInstructions()
            .stream()
            .filter(message -> message.getMessageType() == MessageType.USER)
            .map(Message::getText)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow();
    }
}
