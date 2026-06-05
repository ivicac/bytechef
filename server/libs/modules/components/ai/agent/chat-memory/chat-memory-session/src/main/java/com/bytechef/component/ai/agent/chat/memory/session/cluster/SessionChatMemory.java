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

package com.bytechef.component.ai.agent.chat.memory.session.cluster;

import static com.bytechef.component.ai.agent.chat.memory.session.constant.SessionChatMemoryConstants.CONVERSATION_ID;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction.CHAT_MEMORY;

import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

/**
 * @author Ivica Cardic
 */
public class SessionChatMemory {

    public static ClusterElementDefinition<ChatMemoryFunction> of(SessionService sessionService) {
        return ComponentDsl.<ChatMemoryFunction>clusterElement("chatMemory")
            .title("Session Chat Memory")
            .description("Event-sourced session memory; prior messages are recalled per conversation session.")
            .properties(
                string(CONVERSATION_ID)
                    .label("Conversation ID")
                    .description("The unique identifier for the conversation session.")
                    .required(true))
            .type(CHAT_MEMORY)
            .object(() -> (inputParameters, connectionParameters, extensions, componentConnections) -> apply(
                sessionService));
    }

    private SessionChatMemory() {
    }

    protected static BaseAdvisor apply(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
            .defaultUserId("bytechef")
            .build();
    }
}
