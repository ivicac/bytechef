/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agui.core.state.State;
import com.bytechef.ee.automation.ai.gateway.domain.WorkspaceAiGatewayProvider;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.aihub.util.AiHubStateKeys;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Pins the precedence rules for {@link AiHubChatClientResolver#resolve(State)}:
 *
 * <pre>
 *   user-selected (USER_SELECTED_LLM_*_KEY)        ← highest
 *     ↓ absent
 *   personal-agent override (PERSONAL_AGENT_LLM_*_KEY)
 *     ↓ absent
 *   null (caller falls back to workspace @Primary ChatModel)   ← lowest
 * </pre>
 *
 * Half-set states at any layer fall through to the next; unknown providers fall through too. The resolver never throws
 * — every defensive branch returns null so the conversation continues on the workspace default rather than 500-ing.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatClientResolverTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long OPENAI_PROVIDER_ID = 100L;
    private static final long ANTHROPIC_PROVIDER_ID = 200L;
    private static final String OPENAI_MODEL = "gpt-4o";
    private static final String ANTHROPIC_MODEL = "claude-opus-4-7";

    private WorkspaceAiGatewayProviderService workspaceAiGatewayProviderService;
    private AiGatewayProviderService aiGatewayProviderService;
    private AiGatewayChatModelFactory aiGatewayChatModelFactory;

    private AiGatewayProvider openAiProvider;
    private AiGatewayProvider anthropicProvider;
    private ChatModel openAiChatModel;
    private ChatModel anthropicChatModel;

    private AiHubChatClientResolver resolver;

    @BeforeEach
    void setUp() {
        workspaceAiGatewayProviderService = mock(WorkspaceAiGatewayProviderService.class);
        aiGatewayProviderService = mock(AiGatewayProviderService.class);
        aiGatewayChatModelFactory = mock(AiGatewayChatModelFactory.class);

        // Two enabled providers in the workspace: OPENAI (id=100) and ANTHROPIC (id=200). Each test below picks
        // which one the resolver should produce a ChatClient for via state keys, and we verify by checking which
        // ChatModel the factory was asked for.
        openAiProvider = mock(AiGatewayProvider.class);
        anthropicProvider = mock(AiGatewayProvider.class);

        when(openAiProvider.getId()).thenReturn(OPENAI_PROVIDER_ID);
        when(openAiProvider.getType()).thenReturn(AiGatewayProviderType.OPENAI);
        when(openAiProvider.isEnabled()).thenReturn(true);

        when(anthropicProvider.getId()).thenReturn(ANTHROPIC_PROVIDER_ID);
        when(anthropicProvider.getType()).thenReturn(AiGatewayProviderType.ANTHROPIC);
        when(anthropicProvider.isEnabled()).thenReturn(true);

        WorkspaceAiGatewayProvider workspaceOpenAi = mock(WorkspaceAiGatewayProvider.class);
        WorkspaceAiGatewayProvider workspaceAnthropic = mock(WorkspaceAiGatewayProvider.class);

        when(workspaceOpenAi.getProviderId()).thenReturn(OPENAI_PROVIDER_ID);
        when(workspaceAnthropic.getProviderId()).thenReturn(ANTHROPIC_PROVIDER_ID);

        when(workspaceAiGatewayProviderService.getWorkspaceProviders(WORKSPACE_ID))
            .thenReturn(List.of(workspaceOpenAi, workspaceAnthropic));

        when(aiGatewayProviderService.getProvider(OPENAI_PROVIDER_ID)).thenReturn(openAiProvider);
        when(aiGatewayProviderService.getProvider(ANTHROPIC_PROVIDER_ID)).thenReturn(anthropicProvider);

        openAiChatModel = mock(ChatModel.class);
        anthropicChatModel = mock(ChatModel.class);

        when(aiGatewayChatModelFactory.getChatModel(openAiProvider)).thenReturn(openAiChatModel);
        when(aiGatewayChatModelFactory.getChatModel(anthropicProvider)).thenReturn(anthropicChatModel);

        resolver = new AiHubChatClientResolver(
            workspaceAiGatewayProviderService, aiGatewayProviderService, aiGatewayChatModelFactory);
    }

    @Test
    void testUserSelectedTakesPrecedenceOverPersonalAgent() {
        // Both layers set; user-selected (OPENAI/gpt-4o) MUST win over personal-agent (ANTHROPIC/opus).
        State state = newState();

        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "OPENAI");
        state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, OPENAI_MODEL);
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_PROVIDER_KEY, "ANTHROPIC");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_MODEL_KEY, ANTHROPIC_MODEL);

        ChatClient client = resolver.resolve(state);

        assertNotNull(client);
        verify(aiGatewayChatModelFactory).getChatModel(openAiProvider);
        verify(aiGatewayChatModelFactory, never()).getChatModel(anthropicProvider);
    }

    @Test
    void testUserSelectedOnlyResolvesUserSelected() {
        State state = newState();

        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "OPENAI");
        state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, OPENAI_MODEL);

        ChatClient client = resolver.resolve(state);

        assertNotNull(client);
        verify(aiGatewayChatModelFactory).getChatModel(openAiProvider);
    }

    @Test
    void testPersonalAgentOnlyResolvesPersonalAgent() {
        State state = newState();

        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_PROVIDER_KEY, "ANTHROPIC");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_MODEL_KEY, ANTHROPIC_MODEL);

        ChatClient client = resolver.resolve(state);

        assertNotNull(client);
        verify(aiGatewayChatModelFactory).getChatModel(anthropicProvider);
        verify(aiGatewayChatModelFactory, never()).getChatModel(openAiProvider);
    }

    @Test
    void testNoOverridesReturnsNull() {
        // No state keys set → resolver returns null so the caller falls back to the workspace @Primary ChatModel.
        State state = newState();

        assertNull(resolver.resolve(state));
        verify(aiGatewayChatModelFactory, never()).getChatModel(openAiProvider);
        verify(aiGatewayChatModelFactory, never()).getChatModel(anthropicProvider);
    }

    @Test
    void testHalfSetUserStateFallsThroughToPersonalAgent() {
        // Client sent provider but not model (transient state during user picking) → don't 400; fall through to the
        // personal-agent override which IS fully set. The resolver should log a warning and produce the agent's
        // ChatClient.
        State state = newState();

        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "OPENAI");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_PROVIDER_KEY, "ANTHROPIC");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_MODEL_KEY, ANTHROPIC_MODEL);

        ChatClient client = resolver.resolve(state);

        assertNotNull(client);
        verify(aiGatewayChatModelFactory).getChatModel(anthropicProvider);
        verify(aiGatewayChatModelFactory, never()).getChatModel(openAiProvider);
    }

    @Test
    void testUnknownUserProviderReturnsNull() {
        // User picked a provider the workspace doesn't have enabled. Resolver returns null + warn-logs; caller falls
        // back to workspace default. (NOT to the personal-agent override — once user explicitly picked, we honor
        // their intent or revert to the workspace floor; we don't silently re-fall-through to the agent's choice.)
        State state = newState();

        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "GROQ");
        state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, "llama-3");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_PROVIDER_KEY, "ANTHROPIC");
        state.set(AiHubStateKeys.PERSONAL_AGENT_LLM_MODEL_KEY, ANTHROPIC_MODEL);

        assertNull(resolver.resolve(state));
        verify(aiGatewayChatModelFactory, never()).getChatModel(openAiProvider);
        verify(aiGatewayChatModelFactory, never()).getChatModel(anthropicProvider);
    }

    @Test
    void testMissingWorkspaceIdReturnsNull() {
        // Defensive: the controller normally injects VERIFIED_WORKSPACE_ID, but if it didn't, the resolver returns
        // null instead of NPE-ing or hitting the DB with a null workspace.
        State state = new State();

        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "OPENAI");
        state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, OPENAI_MODEL);

        assertNull(resolver.resolve(state));
    }

    @Test
    void testNullStateReturnsNull() {
        assertNull(resolver.resolve(null));
    }

    private static State newState() {
        State state = new State();

        state.set(AiHubStateKeys.VERIFIED_WORKSPACE_ID, WORKSPACE_ID);

        return state;
    }
}
