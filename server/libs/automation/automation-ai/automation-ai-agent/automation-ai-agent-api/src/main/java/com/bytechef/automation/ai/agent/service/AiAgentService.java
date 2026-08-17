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

package com.bytechef.automation.ai.agent.service;

import com.bytechef.automation.ai.agent.domain.AiAgent;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Service interface for managing {@link AiAgent} entities.
 *
 * @author Ivica Cardic
 */
public interface AiAgentService {

    AiAgent create(AiAgent agent);

    void delete(long id);

    Optional<AiAgent> fetchAgent(long id);

    AiAgent getAgent(long id);

    List<AiAgent> getAgents(@Nullable Long workspaceId);

    /**
     * Returns the ID of the project the agent belongs to — the project of its generated workflow
     * ({@code project_workflow.project_id} for {@link AiAgent#getProjectWorkflowUuid()}). The agent row keeps no
     * project column of its own.
     *
     * @param agent the agent
     * @return the owning project's ID
     * @throws IllegalStateException when no project workflow carries the agent's workflow uuid
     */
    long getProjectId(AiAgent agent);

    /**
     * Batch variant of {@link #getProjectId(AiAgent)} for list paths: resolves every agent's project in one query.
     * Agents whose generated workflow no longer exists are absent from the result.
     *
     * @param agents the agents
     * @return agent ID → owning project ID
     */
    Map<Long, Long> getProjectIds(Collection<AiAgent> agents);

    /**
     * Returns every agent that belongs to the given project (whose generated workflow is one of the project's
     * workflows). A project may hold any number of agents.
     *
     * @param projectId the ID of the project to filter by
     * @return the agents with the specified project ID
     */
    List<AiAgent> getProjectAgents(long projectId);

    /**
     * Returns the agents that reference the given agent as a sub-agent, i.e. agents that have an {@code agent_element}
     * row with {@code kind = SUB_AGENT} and {@code reference_id = referencedAgentId}.
     *
     * @param referencedAgentId the id of the agent being referenced as a sub-agent
     * @return the referencing agents
     */
    List<AiAgent> getSubAgentReferencingAgents(long referencedAgentId);

    AiAgent update(AiAgent agent);
}
