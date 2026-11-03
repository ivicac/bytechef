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

package com.bytechef.automation.configuration.instance.accessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.instance.config.ProjectDeploymentJobPrincipalAccessorIntTestConfiguration;
import com.bytechef.automation.configuration.repository.WorkspaceRepository;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.domain.ConnectionStatus;
import com.bytechef.platform.connection.event.ConnectionWorkflowPausedEvent;
import com.bytechef.platform.connection.exception.ConnectionErrorType;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalSummary;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = ProjectDeploymentJobPrincipalAccessorIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@RecordApplicationEvents
class ProjectDeploymentJobPrincipalAccessorIntTest {

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectDeploymentJobPrincipalAccessor projectDeploymentJobPrincipalAccessor;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void testGetJobPrincipalSummaryDescribesTheDeployedProject() {
        Project project = createProject("CRM " + UUID.randomUUID());

        ProjectDeployment projectDeployment = createProjectDeployment(project);

        assertThat(
            projectDeploymentJobPrincipalAccessor.getJobPrincipalSummary(projectDeployment.getId(), "missing-workflow"))
                .isEqualTo(
                    new JobPrincipalSummary(Environment.PRODUCTION.ordinal(), project.getId(), project.getName(), 3));
    }

    @Test
    void testGetJobPrincipalSummaryReportsTheProjectVersionTheJobRan() {
        Project project = createProject("CRM " + UUID.randomUUID());

        ProjectDeployment projectDeployment = createProjectDeployment(project);

        String workflowId = "workflow-" + UUID.randomUUID();

        projectWorkflowService.addWorkflow(project.getId(), 2, workflowId, UUID.randomUUID());

        assertThat(projectDeploymentJobPrincipalAccessor.getJobPrincipalSummary(projectDeployment.getId(), workflowId))
            .isEqualTo(
                new JobPrincipalSummary(Environment.PRODUCTION.ordinal(), project.getId(), project.getName(), 2));
    }

    @Test
    void testGetJobPrincipalSummaryOmitsTheNameOfAMissingProject() {
        Project project = createProject("CRM " + UUID.randomUUID());

        ProjectDeployment projectDeployment = createProjectDeployment(project);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");

            jdbcTemplate.update("DELETE FROM project WHERE id = ?", project.getId());
        });

        assertThat(
            projectDeploymentJobPrincipalAccessor.getJobPrincipalSummary(projectDeployment.getId(), "missing-workflow"))
                .isEqualTo(new JobPrincipalSummary(Environment.PRODUCTION.ordinal(), project.getId(), null, 3));
    }

    @Test
    void testValidateConnectionsForJobWhenAllActiveDoesNothing() {
        Project project = createProject("CRM " + UUID.randomUUID());

        ProjectDeployment projectDeployment = createProjectDeployment(project);

        String workflowUuid = createDeploymentWorkflowWithConnections(project, projectDeployment);

        when(connectionService.getInactiveConnections(anyList())).thenReturn(List.of());

        projectDeploymentJobPrincipalAccessor.validateConnectionsForJob(projectDeployment.getId(), workflowUuid);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> connectionIdsArgumentCaptor = ArgumentCaptor.forClass(List.class);

        verify(connectionService).getInactiveConnections(connectionIdsArgumentCaptor.capture());

        assertThat(connectionIdsArgumentCaptor.getValue()).containsExactlyInAnyOrder(100L, 101L);
        assertThat(applicationEvents.stream(ConnectionWorkflowPausedEvent.class)).isEmpty();

        verify(connectionService, never()).validateConnectionsActive(anyList());
    }

    @Test
    void testValidateConnectionsForJobWhenInactivePublishesAuditAndThrows() {
        Project project = createProject("CRM " + UUID.randomUUID());

        ProjectDeployment projectDeployment = createProjectDeployment(project);

        String workflowUuid = createDeploymentWorkflowWithConnections(project, projectDeployment);

        Connection inactiveConnection = new Connection();

        inactiveConnection.setId(100L);
        inactiveConnection.setStatus(ConnectionStatus.PENDING_REASSIGNMENT);

        when(connectionService.getInactiveConnections(anyList())).thenReturn(List.of(inactiveConnection));

        doThrow(new ConfigurationException("blocked", ConnectionErrorType.CONNECTION_NOT_ACTIVE))
            .when(connectionService)
            .validateConnectionsActive(anyList());

        assertThatThrownBy(
            () -> projectDeploymentJobPrincipalAccessor.validateConnectionsForJob(
                projectDeployment.getId(), workflowUuid))
                    .isInstanceOf(ConfigurationException.class);

        String workflowId = projectWorkflowService.getProjectWorkflowWorkflowId(
            projectDeployment.getId(), workflowUuid);

        assertThat(applicationEvents.stream(ConnectionWorkflowPausedEvent.class)).containsExactly(
            new ConnectionWorkflowPausedEvent(
                100L,
                Map.of(
                    "projectDeploymentId", projectDeployment.getId(),
                    "workflowId", workflowId,
                    "connectionStatus", "PENDING_REASSIGNMENT")));
    }

    /**
     * A webhook URL carries {@code jobPrincipalId} as a plain, unsigned field (see
     * {@code WorkflowExecutionId#toString()}, which is base64 of a colon-joined string, not a signed token), so anyone
     * holding a webhook URL can edit that field to a deployment that does not exist — including {@code -1}, the
     * test-mode sentinel whose branch in {@code WebhookWorkflowExecutorImpl#getWorkflowId} resolves a workflow by uuid
     * alone and would therefore bypass the deployment's pinned workflow version.
     *
     * <p>
     * That branch is unreachable from the public {@code /webhooks/{id}} path only because this method reports "not
     * enabled" for an unknown principal, which makes the controller answer 410 GONE before it ever resolves a workflow.
     * Two implementation details carry that guarantee, and neither is stated anywhere else: the deployment lookup
     * returns {@code false} for a missing row rather than throwing, and {@code &&} short-circuits so the throwing
     * uuid-resolution is never evaluated. Reordering the operands or making the lookup throw would turn a clean deny
     * into a 500 that has already reached workflow resolution.
     */
    @Test
    void testIsWorkflowEnabledFailsClosedForUnknownJobPrincipal() {
        UUID workflowUuid = UUID.randomUUID();

        assertThat(projectDeploymentJobPrincipalAccessor.isWorkflowEnabled(-1L, workflowUuid.toString())).isFalse();
    }

    private Project createProject(String name) {
        Workspace workspace = workspaceRepository.save(new Workspace("workspace " + UUID.randomUUID()));

        Project project = new Project();

        project.setName(name);
        project.setUuid(UUID.randomUUID());
        project.setWorkspaceId(workspace.getId());

        return projectService.create(project);
    }

    private ProjectDeployment createProjectDeployment(Project project) {
        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setName("deployment " + UUID.randomUUID());
        projectDeployment.setProjectId(project.getId());
        projectDeployment.setProjectVersion(3);

        return projectDeploymentService.create(projectDeployment);
    }

    private String createDeploymentWorkflowWithConnections(Project project, ProjectDeployment projectDeployment) {
        String workflowId = "workflow-" + UUID.randomUUID();
        UUID workflowUuid = UUID.randomUUID();

        projectWorkflowService.addWorkflow(project.getId(), 3, workflowId, workflowUuid);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setConnections(
            List.of(
                new ProjectDeploymentWorkflowConnection(100L, "main", "nodeA"),
                new ProjectDeploymentWorkflowConnection(101L, "aux", "nodeB")));
        projectDeploymentWorkflow.setProjectDeploymentId(projectDeployment.getId());
        projectDeploymentWorkflow.setWorkflowId(workflowId);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");

            projectDeploymentWorkflowService.create(projectDeploymentWorkflow);
        });

        return workflowUuid.toString();
    }
}
