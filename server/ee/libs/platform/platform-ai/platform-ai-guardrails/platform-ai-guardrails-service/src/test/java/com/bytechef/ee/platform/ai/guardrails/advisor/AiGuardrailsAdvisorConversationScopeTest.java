/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrailMetrics;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsTarget;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.platform.ai.guardrails.ConversationScope;
import com.bytechef.platform.ai.guardrails.GuardrailSurface;
import com.bytechef.platform.ai.guardrails.RestorationDestination;
import com.bytechef.platform.ai.sensitivedata.PiiTokenSessionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Pins the three behaviours conversation-scoped token sessions must hold together: a trusted conversation carries its
 * tokens across turns, an untrusted one stores nothing at all, and a store outage costs cross-turn coherence rather
 * than the request.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailsAdvisorConversationScopeTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final long USER_ID = 7L;
    private static final long WORKSPACE_ID = 42L;

    private static final PiiTokenSessionStore.SessionKey KEY =
        new PiiTokenSessionStore.SessionKey(WORKSPACE_ID, USER_ID, CONVERSATION_ID);

    private final AiGuardrailsWorkspaceSettingsService settingsService =
        mock(AiGuardrailsWorkspaceSettingsService.class);

    @Test
    void testATrustedConversationRestoresATokenMintedInAnEarlierTurn() {
        RecordingStore store = new RecordingStore();

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));

        AiGuardrailsAdvisor advisor = advisorOver(store);

        ChatClientResponse response = advisor.adviseCall(
            requestWithTrustedConversation("write to [PII_EMAIL_ADDRESS_1_abcd]"), echoingChain());

        assertThat(textOf(response))
            .as("turn 1's token arrives in turn 2's prompt out of retained chat history, where a request-scoped "
                + "session would leave a dead token")
            .isEqualTo("write to bob@acme.io");
    }

    @Test
    void testATrustedConversationSavesTheTokensItMinted() {
        RecordingStore store = new RecordingStore();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(store.storedTokens(KEY)).containsValue("bob@acme.io");
    }

    @Test
    void testASecondTurnKeepsTheSameTokenForTheSameValue() {
        RecordingStore store = new RecordingStore();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());
        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io again"), echoingChain());

        assertThat(store.storedTokens(KEY)).hasSize(1);
    }

    @Test
    void testAnUntrustedConversationStoresNothing() {
        RecordingStore store = new RecordingStore();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        ChatClientResponse response =
            advisor.adviseCall(requestWithUntrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(store.saveCount())
            .as("the canvas AI Agent's id is a workflow author's expression, so the same request shape without the "
                + "platform-issued marker must key nothing")
            .isZero();
        assertThat(textOf(response)).isEqualTo("mail bob@acme.io");
    }

    @Test
    void testAStoreFailureLeavesTheRequestWorkingRequestScoped() {
        RecordingStore store = new RecordingStore();

        store.failLoads();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        ChatClientResponse response =
            advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(textOf(response)).isEqualTo("mail bob@acme.io");
    }

    @Test
    void testAFailedLoadNeverWritesAnEmptySessionOverTheStoredOne() {
        RecordingStore store = new RecordingStore();

        store.failLoads();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        advisor.adviseCall(requestWithTrustedConversation("hello there"), echoingChain());

        assertThat(store.saveCount())
            .as("a turn that could not load and minted nothing must not write an empty map, which the store reads as "
                + "'this conversation has no session' and deletes the row for")
            .isZero();
    }

    /**
     * The other half of the failed-load case: the turn DID mint something, so the empty-skip guard does not fire.
     * Writing that session back would replace four turns' worth of tokens with the one turn that could not read them,
     * leaving every earlier token replayed out of retained chat history unresolvable.
     */
    @Test
    void testAFailedLoadNeverOverwritesTheStoredTokensWithAFreshSession() {
        RecordingStore store = new RecordingStore();

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));

        int savesBeforeTheBlip = store.saveCount();

        store.failLoads();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        advisor.adviseCall(requestWithTrustedConversation("mail carol@acme.io"), echoingChain());

        assertThat(store.saveCount())
            .as("a turn that could not load must write nothing at all, however much it minted -- the write replaces "
                + "the conversation's whole map")
            .isEqualTo(savesBeforeTheBlip);
        assertThat(store.storedTokens(KEY)).containsExactlyEntriesOf(
            Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));
    }

    /**
     * The store contract is fail-soft, but a store that ignores it must not cost an already-successful model response:
     * the save runs from {@code adviseCall}'s own {@code finally}, where a thrown exception would replace the response
     * with a failure and skip the session close that clears the decrypted mapping.
     */
    @Test
    void testAThrowingStoreSaveStillReturnsTheModelResponse() {
        RecordingStore store = new RecordingStore();

        store.throwOnSaves();

        AiGuardrailsAdvisor advisor = advisorOver(store);

        ChatClientResponse response =
            advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(textOf(response)).isEqualTo("mail bob@acme.io");
        assertThat(store.saveCount())
            .as("the save was attempted -- otherwise the test proves nothing about surviving it")
            .isEqualTo(1);
    }

    private AiGuardrailsAdvisor advisorOver(PiiTokenSessionStore piiTokenSessionStore) {
        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrails aiGuardrails = new AiGuardrails(
            settingsService, null, null, new AiGuardrailMetrics(new SimpleMeterRegistry(), "gateway"), true, false, "",
            false, false, false, false, piiTokenSessionStore);

        return new AiGuardrailsAdvisor(
            aiGuardrails, AiGuardrailsSettingsTarget.workspace(WORKSPACE_ID),
            new AiGuardrailMetrics(new SimpleMeterRegistry(), "ai_hub"),
            GuardrailSurface.AI_HUB, RestorationDestination.CONVERSATION);
    }

    /**
     * A chain answering with the request's own user text, so the response path — scan, then restore this session's
     * tokens — is exercised on exactly what the request direction produced.
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

    private static ChatClientRequest requestWithTrustedConversation(String text) {
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(text))),
            Map.<String, Object>of(
                ChatMemory.CONVERSATION_ID, CONVERSATION_ID, ConversationScope.PLATFORM_ISSUED_KEY, true,
                ConversationScope.USER_ID_KEY, USER_ID));
    }

    private static ChatClientRequest requestWithUntrustedConversation(String text) {
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(text))),
            Map.<String, Object>of(
                ChatMemory.CONVERSATION_ID, CONVERSATION_ID, ConversationScope.USER_ID_KEY, USER_ID));
    }

    private static String textOf(ChatClientResponse chatClientResponse) {
        ChatResponse chatResponse = Objects.requireNonNull(chatClientResponse.chatResponse());
        Generation generation = Objects.requireNonNull(chatResponse.getResult());
        AssistantMessage assistantMessage = Objects.requireNonNull(generation.getOutput());

        return Objects.requireNonNull(assistantMessage.getText());
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

    /**
     * An in-memory store standing in for the encrypted one, counting writes so a test can assert that nothing was
     * written at all.
     *
     * <p>
     * {@link #failLoads()} makes it read the way the interface requires an implementation to fail — empty rather than
     * thrown. A throwing store would violate that contract, and catching it in the engine would hide a real bug, so the
     * degradation under test is the one a real outage produces.
     * </p>
     */
    private static final class RecordingStore implements PiiTokenSessionStore {

        private final Map<SessionKey, Map<String, String>> sessions = new HashMap<>();
        private boolean loadsFail;
        private boolean savesThrow;
        private int saveCount;

        @Override
        public LoadResult load(SessionKey key) {
            if (loadsFail) {
                return LoadResult.unavailable();
            }

            Map<String, String> tokens = sessions.get(key);

            return LoadResult.of(tokens == null ? Map.of() : tokens);
        }

        @Override
        public void save(SessionKey key, Map<String, String> tokens) {
            saveCount++;

            if (savesThrow) {
                throw new IllegalStateException("connection refused");
            }

            if (tokens.isEmpty()) {
                sessions.remove(key);

                return;
            }

            sessions.put(key, Map.copyOf(tokens));
        }

        @Override
        public void evict(long workspaceId, String conversationId) {
            sessions.keySet()
                .removeIf(key -> key.workspaceId() == workspaceId &&
                    Objects.equals(key.conversationId(), conversationId));
        }

        void failLoads() {
            loadsFail = true;
        }

        /**
         * Breaks the fail-soft contract on purpose, which is exactly what the advisor must survive: the contract is
         * documentation, and an implementation that ignores it must not cost an already-successful response.
         */
        void throwOnSaves() {
            savesThrow = true;
        }

        Map<String, String> storedTokens(SessionKey key) {
            return sessions.getOrDefault(key, Map.of());
        }

        int saveCount() {
            return saveCount;
        }
    }
}
