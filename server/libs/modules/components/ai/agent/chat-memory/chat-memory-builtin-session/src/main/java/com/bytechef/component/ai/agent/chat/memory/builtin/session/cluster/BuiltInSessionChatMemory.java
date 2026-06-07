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

package com.bytechef.component.ai.agent.chat.memory.builtin.session.cluster;

import static com.bytechef.platform.component.definition.ai.agent.SessionRepositoryFunction.SESSION_REPOSITORY;

import com.bytechef.component.ai.agent.chat.memory.jdbc.session.util.SessionChatMemoryUtils;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.platform.component.definition.ai.agent.SessionRepositoryFunction;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Ivica Cardic
 */
public class BuiltInSessionChatMemory {

    public static ClusterElementDefinition<SessionRepositoryFunction> of(@Nullable JdbcTemplate jdbcTemplate) {
        DataSource dataSource = jdbcTemplate == null ? null : jdbcTemplate.getDataSource();

        SessionRepository sessionRepository = dataSource == null
            ? InMemorySessionRepository.builder()
                .build()
            : SessionChatMemoryUtils.getSessionRepository(dataSource);

        return ComponentDsl.<SessionRepositoryFunction>clusterElement("sessionRepository")
            .title("Built-in Session Repository")
            .description("Stores session events in the application database.")
            .type(SESSION_REPOSITORY)
            .object(
                () -> (inputParameters, connectionParameters, extensions, componentConnections) -> sessionRepository);
    }

    private BuiltInSessionChatMemory() {
    }
}
