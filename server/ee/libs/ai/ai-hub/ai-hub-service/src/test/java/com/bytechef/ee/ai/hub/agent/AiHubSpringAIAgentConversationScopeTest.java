/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agui.core.agent.RunAgentInput;
import com.agui.core.exception.AGUIException;
import com.agui.core.message.BaseMessage;
import com.agui.core.message.UserMessage;
import com.agui.core.state.State;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.platform.ai.guardrails.ConversationScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Pins {@link AiHubSpringAIAgent#advisorParams} as the single caller that turns on conversation-scoped PII
 * tokenization: the {@link ConversationScope#PLATFORM_ISSUED_KEY} marker and {@link ConversationScope#USER_ID_KEY} may
 * be published only alongside a thread id AND the controller-verified {@link AiHubStateKeys#AUTHENTICATED_USER_ID} —
 * never derived from the thread id itself, which would make the trust check circular.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubSpringAIAgentConversationScopeTest {

    private static final Long VERIFIED_USER_ID = 42L;

    @Test
    void testAdvisorParamsPublishesMarkerAndUserIdWithVerifiedUserAndThreadId() throws AGUIException {
        AiHubSpringAIAgent agent = newAgent();

        State state = new State();

        state.set(AiHubStateKeys.AUTHENTICATED_USER_ID, VERIFIED_USER_ID);

        Map<String, Object> advisorParams = agent.advisorParams(runInput("thread-1", state));

        assertThat(advisorParams)
            .containsEntry(ChatMemory.CONVERSATION_ID, "thread-1")
            .containsEntry(ConversationScope.PLATFORM_ISSUED_KEY, Boolean.TRUE)
            .containsEntry(ConversationScope.USER_ID_KEY, VERIFIED_USER_ID);
    }

    @Test
    void testAdvisorParamsOmitsMarkerAndUserIdWhenVerifiedUserIdMissing() throws AGUIException {
        AiHubSpringAIAgent agent = newAgent();

        State state = new State();

        Map<String, Object> advisorParams = agent.advisorParams(runInput("thread-1", state));

        assertThat(advisorParams).containsEntry(ChatMemory.CONVERSATION_ID, "thread-1");
        assertThat(advisorParams).doesNotContainKey(ConversationScope.PLATFORM_ISSUED_KEY);
        assertThat(advisorParams).doesNotContainKey(ConversationScope.USER_ID_KEY);
    }

    @Test
    void testAdvisorParamsOmitsAllThreeWhenThreadIdMissing() throws AGUIException {
        AiHubSpringAIAgent agent = newAgent();

        State state = new State();

        state.set(AiHubStateKeys.AUTHENTICATED_USER_ID, VERIFIED_USER_ID);

        Map<String, Object> advisorParams = agent.advisorParams(runInput(null, state));

        assertThat(advisorParams).doesNotContainKey(ChatMemory.CONVERSATION_ID);
        assertThat(advisorParams).doesNotContainKey(ConversationScope.PLATFORM_ISSUED_KEY);
        assertThat(advisorParams).doesNotContainKey(ConversationScope.USER_ID_KEY);
    }

    private static RunAgentInput runInput(String threadId, State state) {
        UserMessage userMessage = new UserMessage();

        userMessage.setContent("Tell me about the project");

        return new RunAgentInput(
            threadId, "run", state, List.of((BaseMessage) userMessage), List.of(), List.of(), null);
    }

    private static AiHubSpringAIAgent newAgent() throws AGUIException {
        return AiHubSpringAIAgent.builder()
            .agentId("ai_hub")
            .chatModel((ChatModel) (prompt -> null))
            .systemMessage("test")
            .state(new State())
            .build();
    }
}
