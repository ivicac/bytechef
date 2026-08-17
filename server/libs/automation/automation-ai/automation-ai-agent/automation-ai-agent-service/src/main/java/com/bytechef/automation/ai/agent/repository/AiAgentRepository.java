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

package com.bytechef.automation.ai.agent.repository;

import com.bytechef.automation.ai.agent.domain.AiAgent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing {@link AiAgent} entities.
 *
 * @author Ivica Cardic
 */
@Repository
public interface AiAgentRepository extends ListCrudRepository<AiAgent, Long> {

    /**
     * Finds all agents that belong to the specified project — the agents whose generated workflow
     * ({@code project_workflow_uuid}) is one of the project's workflows. {@code ai_agent} keeps no project column of
     * its own; the project is read from {@code project_workflow}.
     *
     * @param projectId the ID of the project to filter by
     * @return the agents of the specified project
     */
    @Query("""
        SELECT ai_agent.* FROM ai_agent
        WHERE ai_agent.project_workflow_uuid IN (
            SELECT project_workflow.uuid FROM project_workflow WHERE project_workflow.project_id = :projectId
        )
        ORDER BY ai_agent.id ASC
        """)
    List<AiAgent> findAllByProjectId(@Param("projectId") long projectId);

    /**
     * Finds the project that owns the given generated workflow. Every version of a project workflow shares its uuid and
     * its project, so the distinct result is a single row.
     *
     * @param projectWorkflowUuid the agent's {@code project_workflow_uuid}
     * @return the owning project's ID, or empty when no project workflow carries the uuid
     */
    @Query("SELECT DISTINCT project_workflow.project_id FROM project_workflow WHERE project_workflow.uuid = :uuid")
    Optional<Long> findProjectIdByProjectWorkflowUuid(@Param("uuid") UUID projectWorkflowUuid);

    /**
     * Batch variant of {@link #findProjectIdByProjectWorkflowUuid(UUID)}: one row per distinct uuid found.
     *
     * @param projectWorkflowUuids the agents' {@code project_workflow_uuid} values; must not be empty
     * @return the uuid → project pairs found
     */
    @Query(
        value = """
            SELECT DISTINCT project_workflow.uuid, project_workflow.project_id FROM project_workflow
            WHERE project_workflow.uuid IN (:uuids)
            """,
        rowMapperClass = ProjectWorkflowProjectRowMapper.class)
    List<ProjectWorkflowProject> findProjectIdsByProjectWorkflowUuids(
        @Param("uuids") Collection<UUID> projectWorkflowUuids);

    /**
     * Finds all agents that belong to the specified workspace.
     *
     * @param workspaceId the ID of the workspace to filter by
     * @return a list of agents with the specified workspace ID
     */
    List<AiAgent> findByWorkspaceId(Long workspaceId);

    /**
     * Finds all agents that reference the given agent as a sub-agent, i.e. agents that have an {@code ai_agent_element}
     * row with {@code kind = SUB_AGENT} and {@code reference_id = referencedAgentId}.
     *
     * @param referencedAgentId the ID of the agent being referenced as a sub-agent
     * @return the referencing agents
     */
    @Query("""
        SELECT ai_agent.* FROM ai_agent
        JOIN ai_agent_element ON ai_agent_element.agent_id = ai_agent.id
        WHERE ai_agent_element.kind = 'SUB_AGENT' AND ai_agent_element.reference_id = :referencedAgentId
        ORDER BY ai_agent.id ASC
        """)
    List<AiAgent> findSubAgentReferencingAgents(@Param("referencedAgentId") long referencedAgentId);

    /**
     * A project workflow uuid paired with the project that owns it.
     */
    record ProjectWorkflowProject(UUID projectWorkflowUuid, long projectId) {
    }

    /**
     * Maps a {@code (uuid, project_id)} row of {@code project_workflow} to a {@link ProjectWorkflowProject}.
     */
    class ProjectWorkflowProjectRowMapper implements RowMapper<ProjectWorkflowProject> {

        @Override
        public ProjectWorkflowProject mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new ProjectWorkflowProject(
                resultSet.getObject("uuid", UUID.class), resultSet.getLong("project_id"));
        }
    }
}
