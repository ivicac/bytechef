/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.ai.copilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agui.core.agent.RunAgentInput;
import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * @author Ivica Cardic
 */
class CopilotSpringAIAgentChatClientTest {

    @Test
    void testUnavailableSelectedModelFailsTheRun() throws AGUIException {
        OverrideChatClientResolver overrideChatClientResolver = mock(OverrideChatClientResolver.class);

        when(overrideChatClientResolver.resolve(any()))
            .thenThrow(new SelectedChatModelUnavailableException("openAi", "gpt-4o", 1));

        CopilotSpringAIAgent agent = newAgent(overrideChatClientResolver);

        assertThatThrownBy(() -> agent.resolveChatClient(newInput()))
            .isInstanceOf(AGUIException.class)
            .hasMessageContaining("gpt-4o");
    }

    @Test
    void testOtherResolverFailuresFallBackToTheDefaultChatClient() throws AGUIException {
        OverrideChatClientResolver overrideChatClientResolver = mock(OverrideChatClientResolver.class);

        when(overrideChatClientResolver.resolve(any())).thenThrow(new IllegalStateException("catalog down"));

        CopilotSpringAIAgent agent = newAgent(overrideChatClientResolver);

        ChatClient chatClient = agent.resolveChatClient(newInput());

        assertThat(chatClient).isNotNull();
    }

    private static RunAgentInput newInput() {
        return new RunAgentInput("thread", "run", new State(), List.of(), List.of(), List.of(), null);
    }

    private static CopilotSpringAIAgent newAgent(OverrideChatClientResolver overrideChatClientResolver)
        throws AGUIException {

        return ConverterSpringAIAgent.builder()
            .agentId("test")
            .chatModel(mock(ChatModel.class))
            .systemMessage("system")
            .state(new State())
            .overrideChatClientResolver(overrideChatClientResolver)
            .build();
    }
}
