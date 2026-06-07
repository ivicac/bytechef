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

package com.bytechef.component.ai.agent.chat.memory.builtin.session;

import static com.bytechef.component.ai.agent.chat.memory.builtin.session.constant.BuiltInSessionChatMemoryConstants.BUILT_IN_SESSION_CHAT_MEMORY;
import static com.bytechef.component.definition.ComponentDsl.component;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.ai.agent.chat.memory.builtin.session.cluster.BuiltInSessionChatMemory;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component(BUILT_IN_SESSION_CHAT_MEMORY + "_v1_ComponentHandler")
public class BuiltInSessionChatMemoryComponentHandler implements ComponentHandler {

    private final ComponentDefinition componentDefinition;

    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public BuiltInSessionChatMemoryComponentHandler(@Autowired(required = false) @Nullable JdbcTemplate jdbcTemplate) {
        this.componentDefinition = component(BUILT_IN_SESSION_CHAT_MEMORY)
            .title("Built-in Session Repository")
            .description("Built-in storage backend for Session Chat Memory.")
            .icon("path:assets/built-in-session-chat-memory.svg")
            .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
            .clusterElements(BuiltInSessionChatMemory.of(jdbcTemplate));
    }

    @Override
    public ComponentDefinition getDefinition() {
        return componentDefinition;
    }
}
