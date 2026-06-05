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

package com.bytechef.component.ai.agent.chat.memory.session;

import static com.bytechef.component.ai.agent.chat.memory.session.constant.SessionChatMemoryConstants.SESSION_CHAT_MEMORY;
import static com.bytechef.component.definition.ComponentDsl.component;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.ai.agent.chat.memory.session.cluster.SessionChatMemory;
import com.bytechef.component.ai.agent.chat.memory.session.util.SessionChatMemoryUtils;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * @author Ivica Cardic
 */
@Component(SESSION_CHAT_MEMORY + "_v1_ComponentHandler")
public class SessionChatMemoryComponentHandler implements ComponentHandler {

    private final ComponentDefinition componentDefinition;

    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public SessionChatMemoryComponentHandler(@Autowired(required = false) @Nullable JdbcTemplate jdbcTemplate) {
        SessionService sessionService = createSessionService(jdbcTemplate);

        this.componentDefinition = component(SESSION_CHAT_MEMORY)
            .title("Session Chat Memory")
            .description("Event-sourced session-based chat memory.")
            .icon("path:assets/session-chat-memory.svg")
            .categories(ComponentCategory.ARTIFICIAL_INTELLIGENCE)
            .clusterElements(SessionChatMemory.of(sessionService));
    }

    @Override
    public ComponentDefinition getDefinition() {
        return componentDefinition;
    }

    private static SessionService createSessionService(@Nullable JdbcTemplate jdbcTemplate) {
        SessionRepository sessionRepository;

        DataSource dataSource = jdbcTemplate == null ? null : jdbcTemplate.getDataSource();

        if (dataSource == null) {
            sessionRepository = InMemorySessionRepository.builder()
                .build();
        } else {
            SessionChatMemoryUtils.initializeSchema(dataSource);

            sessionRepository = JdbcSessionRepository.builder()
                .dataSource(dataSource)
                .jsonMapper(JsonMapper.builder()
                    .build())
                .build();
        }

        return DefaultSessionService.builder()
            .sessionRepository(sessionRepository)
            .build();
    }
}
