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

package com.bytechef.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.config.ProjectIntTestConfiguration;
import com.bytechef.automation.configuration.config.ProjectIntTestConfigurationSharedMocks;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.SystemProjects;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.DeployedConnection;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.DeployedWorkflow;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.MissingInput;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.RemovedWorkflow;
import com.bytechef.automation.configuration.dto.DeploymentConnectionChoiceDTO;
import com.bytechef.automation.configuration.dto.ProjectDTO;
import com.bytechef.automation.configuration.dto.ProjectDeploymentDTO;
import com.bytechef.automation.configuration.dto.ProjectDeploymentWorkflowDTO;
import com.bytechef.automation.configuration.dto.ProjectWorkflowDTO;
import com.bytechef.automation.configuration.repository.ProjectDeploymentRepository;
import com.bytechef.automation.configuration.repository.ProjectDeploymentWorkflowRepository;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.repository.ProjectWorkflowRepository;
import com.bytechef.automation.configuration.repository.WorkspaceRepository;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.configuration.util.ProjectDeploymentFacadeHelper;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableOutput;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.category.repository.CategoryRepository;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.service.ConnectionDefinitionService;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.connection.domain.AiProviderConnectionId;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.domain.Connection.CredentialStatus;
import com.bytechef.platform.connection.domain.ConnectionStatus;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.tag.repository.TagRepository;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = ProjectIntTestConfiguration.class,
    properties = {
        "bytechef.workflow.repository.jdbc.enabled=true"
    })
@Import(PostgreSQLContainerConfiguration.class)
@ProjectIntTestConfigurationSharedMocks
public class ProjectDeploymentFacadeIntTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProjectFacade projectFacade;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectDeploymentRepository projectDeploymentRepository;

    @Autowired
    private ProjectDeploymentWorkflowRepository projectDeploymentWorkflowRepository;

    @Autowired
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Autowired
    private ProjectWorkflowFacade projectWorkflowFacade;

    @Autowired
    private ProjectWorkflowRepository projectWorkflowRepository;

    @Autowired
    TagRepository tagRepository;

    private Workspace workspace;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private ProjectDeploymentFacadeHelper projectDeploymentFacadeHelper;

    @Autowired
    private JobFacade jobFacade;

    @Autowired
    private JobService jobService;

    @Autowired
    private PrincipalJobService principalJobService;

    @Autowired
    private TriggerDefinitionService triggerDefinitionService;

    @Autowired
    private TriggerLifecycleFacade triggerLifecycleFacade;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @Autowired
    private TriggerStateService triggerStateService;

    @AfterEach
    public void afterEach() {
        projectDeploymentWorkflowRepository.deleteAll();
        projectWorkflowRepository.deleteAll();
        projectDeploymentRepository.deleteAll();
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();

        categoryRepository.deleteAll();
        tagRepository.deleteAll();

    }

    @BeforeEach
    void beforeEach() {
        workspace = workspaceRepository.save(new Workspace("test"));

        projectDeploymentFacadeHelper = new ProjectDeploymentFacadeHelper(
            categoryRepository, projectFacade, projectRepository, projectDeploymentFacade, projectWorkflowFacade,
            projectWorkflowRepository);

        // Default stub: no running jobs unless explicitly mocked by a test
        when(principalJobService.getJobIds(any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt()))
            .thenReturn(Page.empty());

        // Mock trigger definition service to throw for unknown triggers (prevents NPE in getStaticWebhookUrl)
        when(triggerDefinitionService.getTriggerDefinition(anyString(), anyInt(), anyString()))
            .thenThrow(new IllegalArgumentException("Trigger definition not found"));
    }

    @Disabled
    @Test
    public void testCreateProjectDeployment() {
        // TODO
    }

    @Test
    public void testCreateProjectDeploymentWithRequiredIntegerInputs() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        projectDeploymentFacadeHelper.addTestWorkflow(
            projectDTO,
            """
                {
                    "label": "BUG - RunDaily - Scheduler",
                    "inputs": [
                        {"name": "destinationFolderName", "label": "Destination folder name", "type": "string"},
                        {"name": "serviceProviderEmail", "label": "Email", "type": "string", "required": true},
                        {"name": "hourToRun", "label": "Hour", "type": "integer", "required": true},
                        {"name": "minutesToRun", "label": "Minute", "type": "integer", "required": true}
                    ],
                    "triggers": [
                        {
                            "label": "Every Work Day",
                            "name": "trigger_1",
                            "type": "schedule/v1/everyDay",
                            "parameters": {
                                "hour": "${hourToRun}",
                                "minute": "${minutesToRun}",
                                "timezone": "Europe/Zagreb",
                                "dayOfWeek": [2, 3, 4, 5, 6]
                            }
                        }
                    ],
                    "tasks": [
                        {
                            "label": "Logger",
                            "name": "logger_1",
                            "type": "logger/v1/info",
                            "parameters": {"text": "Run every day at ${hourToRun}:${minutesToRun}"}
                        }
                    ]
                }""");

        projectFacade.publishProject(projectDTO.id(), "Published for test", false);

        ProjectWorkflow publishedProjectWorkflow = projectWorkflowRepository.findAllByProjectIdAndProjectVersion(
            projectDTO.id(), 1)
            .getFirst();

        Map<String, Object> inputs = Map.of(
            "serviceProviderEmail", "racuni@example.com",
            "hourToRun", 11,
            "minutesToRun", 50);

        ProjectDeploymentWorkflowDTO projectDeploymentWorkflowDTO = new ProjectDeploymentWorkflowDTO(
            List.of(), null, null, inputs, true, null, null, null, null, null, null, null, 0,
            publishedProjectWorkflow.getWorkflowId(), publishedProjectWorkflow.getUuidAsString());

        ProjectDeploymentDTO projectDeploymentDTO = ProjectDeploymentDTO.builder()
            .projectId(projectDTO.id())
            .name("GH-5404")
            .environment(Environment.DEVELOPMENT)
            .projectVersion(1)
            .projectDeploymentWorkflows(List.of(projectDeploymentWorkflowDTO))
            .build();

        long projectDeploymentId = projectDeploymentFacade.createProjectDeployment(projectDeploymentDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);
        projectDeploymentFacade.enableProjectDeploymentWorkflow(
            projectDeploymentId, publishedProjectWorkflow.getWorkflowId(), true);

        ProjectDeploymentDTO createdProjectDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentId);

        assertThat(createdProjectDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(projectDeploymentWorkflow -> {
                assertThat(projectDeploymentWorkflow.enabled()).isTrue();

                Map<String, ?> savedInputs = projectDeploymentWorkflow.inputs();

                assertThat(savedInputs.get("hourToRun")).isEqualTo(11);
                assertThat(savedInputs.get("minutesToRun")).isEqualTo(50);
                assertThat(savedInputs.get("serviceProviderEmail")).isEqualTo("racuni@example.com");
            });
    }

    @Test
    public void testCreateProjectDeploymentMapsDraftWorkflowIdToDeployedVersion() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        projectDeploymentFacadeHelper.addTestWorkflow(projectDTO);

        projectFacade.publishProject(projectDTO.id(), "Published for test", false);

        ProjectWorkflow publishedProjectWorkflow = projectWorkflowRepository.findAllByProjectIdAndProjectVersion(
            projectDTO.id(), 1)
            .getFirst();
        ProjectWorkflow draftProjectWorkflow = projectWorkflowRepository.findAllByProjectIdAndProjectVersion(
            projectDTO.id(), 2)
            .getFirst();

        assertThat(draftProjectWorkflow.getWorkflowId()).isNotEqualTo(publishedProjectWorkflow.getWorkflowId());

        ProjectDeploymentWorkflowDTO projectDeploymentWorkflowDTO = new ProjectDeploymentWorkflowDTO(
            List.of(), null, null, Map.of(), true, null, null, null, null, null, null, null, 0,
            draftProjectWorkflow.getWorkflowId(), draftProjectWorkflow.getUuidAsString());

        ProjectDeploymentDTO projectDeploymentDTO = ProjectDeploymentDTO.builder()
            .projectId(projectDTO.id())
            .name("GH-6353")
            .environment(Environment.DEVELOPMENT)
            .projectVersion(1)
            .projectDeploymentWorkflows(List.of(projectDeploymentWorkflowDTO))
            .build();

        long projectDeploymentId = projectDeploymentFacade.createProjectDeployment(projectDeploymentDTO);

        ProjectDeploymentDTO createdProjectDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentId);

        assertThat(createdProjectDeployment.projectDeploymentWorkflows())
            .singleElement()
            .satisfies(projectDeploymentWorkflow -> assertThat(projectDeploymentWorkflow.workflowId())
                .isEqualTo(publishedProjectWorkflow.getWorkflowId()));
    }

    @Test
    public void testCreateProjectDeploymentRejectsWorkflowMissingFromDeployedVersion() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        projectDeploymentFacadeHelper.addTestWorkflow(projectDTO);

        projectFacade.publishProject(projectDTO.id(), "Published for test", false);

        ProjectWorkflowDTO draftOnlyProjectWorkflowDTO = projectDeploymentFacadeHelper.addTestWorkflow(projectDTO);

        ProjectDeploymentWorkflowDTO projectDeploymentWorkflowDTO = new ProjectDeploymentWorkflowDTO(
            List.of(), null, null, Map.of(), true, null, null, null, null, null, null, null, 0,
            draftOnlyProjectWorkflowDTO.getId(), draftOnlyProjectWorkflowDTO.getWorkflowUuid());

        ProjectDeploymentDTO projectDeploymentDTO = ProjectDeploymentDTO.builder()
            .projectId(projectDTO.id())
            .name("GH-6353")
            .environment(Environment.DEVELOPMENT)
            .projectVersion(1)
            .projectDeploymentWorkflows(List.of(projectDeploymentWorkflowDTO))
            .build();

        assertThatThrownBy(() -> projectDeploymentFacade.createProjectDeployment(projectDeploymentDTO))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("does not exist in project version v=1");
    }

    @Disabled
    @Test
    public void testCreateProjectDeploymentJob() {
        // TODO
    }

    @Test
    public void testDeleteProjectDeployment() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());
        ProjectDeploymentDTO projectDeploymentDTO =
            projectDeploymentFacadeHelper.createProjectDeployment(workspace.getId(), projectDTO);

        ProjectDeploymentDTO projectDeploymentToDelete =
            projectDeploymentFacade.getProjectDeployment(projectDeploymentDTO.id());

        projectDeploymentFacade.deleteProjectDeployment(projectDeploymentToDelete.id());

        List<ProjectDeploymentDTO> workspaceProjectDeployments = projectDeploymentFacade.getWorkspaceProjectDeployments(
            workspace.getId(), (long) Environment.DEVELOPMENT.ordinal(), projectDeploymentToDelete.projectId(), null,
            true);

        assertThat(workspaceProjectDeployments).hasSize(0);
    }

    /**
     * Needs a principal because {@code filterOutSystemProjectDeployments} runs the project visibility filter as well as
     * the system-name filter, and the resolver reads the current user's login. Scoped to this method rather than the
     * class so the other tests here keep running without one.
     */
    @Test
    @WithMockUser(username = "admin@localhost.com", authorities = AuthorityConstants.ADMIN)
    public void testGetWorkspaceProjectDeploymentsExcludesSyntheticDeployments() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());
        ProjectDeploymentDTO visibleProjectDeploymentDTO =
            projectDeploymentFacadeHelper.createProjectDeployment(workspace.getId(), projectDTO);

        saveSyntheticProjectDeployment(
            SystemProjects.API_COLLECTION_DEPLOYMENT_NAME_PREFIX, projectDTO.id(),
            visibleProjectDeploymentDTO.projectVersion());
        saveSyntheticProjectDeployment(
            SystemProjects.MCP_SERVER_DEPLOYMENT_NAME_PREFIX, projectDTO.id(),
            visibleProjectDeploymentDTO.projectVersion());
        saveSyntheticProjectDeployment(
            SystemProjects.A2A_SERVER_DEPLOYMENT_NAME_PREFIX, projectDTO.id(),
            visibleProjectDeploymentDTO.projectVersion());

        List<ProjectDeploymentDTO> workspaceProjectDeployments = projectDeploymentFacade.getWorkspaceProjectDeployments(
            workspace.getId(), (long) Environment.DEVELOPMENT.ordinal(), projectDTO.id(), null, true);

        assertThat(workspaceProjectDeployments)
            .hasSize(1)
            .first()
            .satisfies(
                projectDeployment -> assertThat(projectDeployment.id()).isEqualTo(visibleProjectDeploymentDTO.id()));
    }

    private void saveSyntheticProjectDeployment(String namePrefix, long projectId, int projectVersion) {
        ProjectDeployment syntheticProjectDeployment = new ProjectDeployment();

        syntheticProjectDeployment.setName(namePrefix + projectId + "_v" + projectVersion);
        syntheticProjectDeployment.setProjectId(projectId);
        syntheticProjectDeployment.setProjectVersion(projectVersion);
        syntheticProjectDeployment.setEnvironment(Environment.DEVELOPMENT);
        syntheticProjectDeployment.setEnabled(true);
        syntheticProjectDeployment.setUuid(UUID.randomUUID());

        projectDeploymentRepository.save(syntheticProjectDeployment);
    }

    @Test
    public void testDeleteProjectDeploymentRemovesAllPrincipalJobsBeforeAnyJob() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());
        ProjectDeploymentDTO projectDeploymentDTO =
            projectDeploymentFacadeHelper.createProjectDeployment(workspace.getId(), projectDTO);

        long deploymentId = projectDeploymentDTO.id();
        long parentJobId = 501L;
        long childJobId = 502L;

        when(principalJobService.getJobIds(deploymentId, PlatformType.AUTOMATION))
            .thenReturn(List.of(parentJobId, childJobId));

        projectDeploymentFacade.deleteProjectDeployment(deploymentId);

        InOrder inOrder = inOrder(principalJobService, jobFacade);

        inOrder.verify(principalJobService)
            .deletePrincipalJobs(parentJobId, PlatformType.AUTOMATION);
        inOrder.verify(principalJobService)
            .deletePrincipalJobs(childJobId, PlatformType.AUTOMATION);
        inOrder.verify(jobFacade, calls(1))
            .deleteJob(anyLong());
        inOrder.verify(jobFacade, calls(1))
            .deleteJob(anyLong());

        verify(jobFacade).deleteJob(parentJobId);
        verify(jobFacade).deleteJob(childJobId);
    }

    @Disabled
    @Test
    public void testGetProjectDeployment() {
        // TODO
    }

    @Disabled
    @Test
    public void testGetProjectDeploymentTags() {
        // TODO
    }

    @Disabled
    @Test
    public void testSearchProjectDeployments() {
        // TODO
    }

    @Test
    public void testUpdateProjectDeploymentWorkflowEnabledToEnabledShouldDisableAndReEnable() {
        // Given - Create a project with a workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), true);

        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, true);

        // When - Update the same workflow (enabled to enabled transition)
        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, true);

        // Then - Verify the workflow is still enabled
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> {
                assertThat(workflow.enabled()).isTrue();
                assertThat(workflow.workflowId()).isEqualTo(workflowId);
            });
    }

    @Test
    public void testUpdateProjectDeploymentWorkflowDisabledToEnabledShouldOnlyEnable() {
        // Given - Create a project with a workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), true);

        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, false);

        // When - Enable the workflow (disabled to enabled transition)
        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, true);

        // Then - Verify the workflow is enabled
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> {
                assertThat(workflow.enabled()).isTrue();
                assertThat(workflow.workflowId()).isEqualTo(workflowId);
            });
    }

    @Test
    public void testDisablingWorkflowStopsRunningJobs() {
        // Given - Create a project with a workflow and enable deployment + workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        long deploymentId = projectDeploymentDTO.id();

        projectDeploymentFacade.enableProjectDeployment(deploymentId, true);

        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(deploymentId, workflowId, true);

        // And mock PrincipalJobService to return STARTED job IDs for this deployment/workflow
        List<Long> runningJobIds = List.of(101L, 202L);

        when(
            principalJobService.getJobIds(
                eq(Job.Status.STARTED), eq(null), eq(null), eq(List.of(deploymentId)), eq(PlatformType.AUTOMATION),
                eq(List.of(workflowId)), eq(false), eq(0)))
                    .thenReturn(new PageImpl<>(runningJobIds, PageRequest.of(0, 20), runningJobIds.size()));

        // When - Disable the workflow
        projectDeploymentFacade.enableProjectDeploymentWorkflow(deploymentId, workflowId, false);

        // Then - verify each running job was stopped
        verify(jobFacade).stopJob(101L);
        verify(jobFacade).stopJob(202L);
    }

    @Test
    public void testEnableRolledBackByTheSurroundingTransactionDisarmsTheTriggersItArmed() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeploymentWithTriggers(
            workspace.getId(), projectDTO);

        long projectDeploymentId = projectDeploymentDTO.id();
        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        reset(triggerLifecycleFacade);

        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);
        IllegalStateException callerFailure = new IllegalStateException("a later write of the caller failed");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(transactionStatus -> {
            projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

            verify(triggerLifecycleFacade, never()).executeTriggerDisable(any(), any(), any(), any(), any());

            throw callerFailure;
        })).isSameAs(callerFailure);

        ProjectDeploymentDTO rolledBackProjectDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentId);

        assertThat(rolledBackProjectDeployment.enabled()).isFalse();

        ArgumentCaptor<WorkflowExecutionId> armedWorkflowExecutionIdCaptor = ArgumentCaptor.captor();
        InOrder inOrder = inOrder(triggerLifecycleFacade);

        inOrder.verify(triggerLifecycleFacade)
            .executeTriggerEnable(
                eq(workflowId), armedWorkflowExecutionIdCaptor.capture(), any(), any(), any(), any(), anyLong());
        inOrder.verify(triggerLifecycleFacade)
            .executeTriggerDisable(eq(workflowId), eq(armedWorkflowExecutionIdCaptor.getValue()), any(), any(), any());
        verifyNoMoreInteractions(triggerLifecycleFacade);
        verify(jobFacade, never()).stopJob(anyLong());
    }

    @Test
    public void testEnableRolledBackByTheSurroundingTransactionRestoresTheArmedTriggerStateInANewTransaction() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeploymentWithTriggers(
            workspace.getId(), projectDTO);

        long projectDeploymentId = projectDeploymentDTO.id();
        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        reset(triggerLifecycleFacade);

        WebhookEnableOutput armedWebhookEnableOutput = new WebhookEnableOutput(
            Map.of("id", "subscription-armed-in-the-rolled-back-transaction"), null);

        when(triggerStateService.fetchValue(any()))
            .thenReturn(Optional.of(armedWebhookEnableOutput), Optional.empty());

        List<Boolean> saveSynchronizationActive = new ArrayList<>();

        doAnswer(invocation -> {
            saveSynchronizationActive.add(TransactionSynchronizationManager.isSynchronizationActive());

            return null;
        }).when(triggerStateService)
            .save(any(), any());

        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);
        IllegalStateException callerFailure = new IllegalStateException("a later write of the caller failed");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(transactionStatus -> {
            projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

            verify(triggerStateService, never()).save(any(), any());

            throw callerFailure;
        })).isSameAs(callerFailure);

        ArgumentCaptor<WorkflowExecutionId> armedWorkflowExecutionIdCaptor = ArgumentCaptor.captor();
        InOrder inOrder = inOrder(triggerLifecycleFacade, triggerStateService);

        inOrder.verify(triggerLifecycleFacade)
            .executeTriggerEnable(
                eq(workflowId), armedWorkflowExecutionIdCaptor.capture(), any(), any(), any(), any(), anyLong());
        inOrder.verify(triggerStateService)
            .save(eq(armedWorkflowExecutionIdCaptor.getValue()), eq(armedWebhookEnableOutput));
        inOrder.verify(triggerLifecycleFacade)
            .executeTriggerDisable(eq(workflowId), eq(armedWorkflowExecutionIdCaptor.getValue()), any(), any(), any());
        verifyNoMoreInteractions(triggerLifecycleFacade);

        assertThat(saveSynchronizationActive).containsExactly(true);
    }

    @Test
    public void testDisableWorkflowCommittedByTheSurroundingTransactionStopsItsJobsAfterCommitAndUndoesNothing() {
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeploymentWithTriggers(
            workspace.getId(), projectDTO);

        long projectDeploymentId = projectDeploymentDTO.id();
        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

        when(
            principalJobService.getJobIds(
                eq(Job.Status.STARTED), eq(null), eq(null), eq(List.of(projectDeploymentId)),
                eq(PlatformType.AUTOMATION), eq(List.of(workflowId)), eq(false), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(101L), PageRequest.of(0, 20), 1));

        reset(triggerLifecycleFacade);

        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);

        transactionTemplate.executeWithoutResult(transactionStatus -> {
            projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentId, workflowId, false);

            verify(jobFacade, never()).stopJob(anyLong());
        });

        verify(jobFacade).stopJob(101L);
        verify(triggerLifecycleFacade).executeTriggerDisable(eq(workflowId), any(), any(), any(), any());
        verifyNoMoreInteractions(triggerLifecycleFacade);

        ProjectDeploymentDTO committedProjectDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentId);

        assertThat(committedProjectDeployment.projectDeploymentWorkflows())
            .singleElement()
            .satisfies(projectDeploymentWorkflow -> assertThat(projectDeploymentWorkflow.enabled()).isFalse());
    }

    @Test
    public void testUpdateProjectDeploymentWorkflowEnabledToDisabledShouldDisable() {
        // Given - Create a project with a workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), true);

        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, true);

        // When - Disable the workflow (enabled to disabled transition)
        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, false);

        // Then - Verify the workflow is disabled
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> {
                assertThat(workflow.enabled()).isFalse();
                assertThat(workflow.workflowId()).isEqualTo(workflowId);
            });
    }

    @Test
    public void testUpdateProjectDeploymentWorkflowProjectDeploymentDisabledShouldNotAffectTriggers() {
        // Given - Create a project with a workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), false);

        String workflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        // When - Enable the workflow while project deployment is disabled
        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), workflowId, true);

        // Then - Verify the workflow is enabled but triggers should not be affected
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.enabled()).isFalse();
        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> {
                assertThat(workflow.enabled()).isTrue();
                assertThat(workflow.workflowId()).isEqualTo(workflowId);
            });
    }

    @Disabled
    @Test
    public void testUpdateProjectDeploymentTags() {
        // TODO
    }

    @Test
    public void testWorkflowLastExecutionDateFiltersJobsByDeploymentId() {
        // Given - Create a project with a workflow and deploy it
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        long deploymentId = projectDeploymentDTO.id();

        // And mock job execution - simulate a job that ran in this deployment
        long jobIdForThisDeployment = 12345L;
        Instant executionTime = Instant.parse("2024-06-15T10:30:00Z");

        Job mockJob = new Job();

        mockJob.setEndDate(executionTime);

        when(principalJobService.fetchLastWorkflowJobId(eq(deploymentId), anyList(), eq(PlatformType.AUTOMATION)))
            .thenReturn(Optional.of(jobIdForThisDeployment));
        when(jobService.getJob(jobIdForThisDeployment))
            .thenReturn(mockJob);

        // When - Get the project deployment (which fetches workflow execution dates)
        ProjectDeploymentDTO result = projectDeploymentFacade.getProjectDeployment(deploymentId);

        // Then - Verify that fetchLastWorkflowJobId was called with THIS deployment's ID
        // This ensures execution dates are filtered by deployment, not fetched globally
        verify(principalJobService, atLeastOnce()).fetchLastWorkflowJobId(
            eq(deploymentId), anyList(), eq(PlatformType.AUTOMATION));

        // And the workflow should have the execution date from the mocked job
        assertThat(result.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> assertThat(workflow.lastExecutionDate()).isEqualTo(executionTime));
    }

    @Test
    public void testWorkflowLastExecutionDateIsolatedBetweenDeployments() {
        // Given - Create a project with a workflow
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        // And create TWO deployments (simulating different environments)
        ProjectDeploymentDTO deployment1 = projectDeploymentFacadeHelper.createProjectDeployment(
            workspace.getId(), projectDTO);

        ProjectDeploymentDTO deployment2 = projectDeploymentFacadeHelper.createProjectDeploymentForEnvironment(
            workspace.getId(), projectDTO, Environment.STAGING);

        long deployment1Id = deployment1.id();
        long deployment2Id = deployment2.id();

        // And mock different job executions for each deployment
        long jobIdForDeployment1 = 1001L;
        long jobIdForDeployment2 = 2002L;
        Instant executionTime1 = Instant.parse("2024-06-01T10:00:00Z");
        Instant executionTime2 = Instant.parse("2024-06-15T15:00:00Z");

        Job mockJob1 = new Job();

        mockJob1.setEndDate(executionTime1);

        Job mockJob2 = new Job();

        mockJob2.setEndDate(executionTime2);

        // Reset mocks to clear default stub
        reset(principalJobService);

        when(principalJobService.fetchLastWorkflowJobId(eq(deployment1Id), anyList(), eq(PlatformType.AUTOMATION)))
            .thenReturn(Optional.of(jobIdForDeployment1));
        when(principalJobService.fetchLastWorkflowJobId(eq(deployment2Id), anyList(), eq(PlatformType.AUTOMATION)))
            .thenReturn(Optional.of(jobIdForDeployment2));

        when(jobService.getJob(jobIdForDeployment1))
            .thenReturn(mockJob1);
        when(jobService.getJob(jobIdForDeployment2))
            .thenReturn(mockJob2);

        // Re-stub getJobIds for other tests
        when(principalJobService.getJobIds(any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt()))
            .thenReturn(Page.empty());

        // When - Get each deployment
        ProjectDeploymentDTO result1 = projectDeploymentFacade.getProjectDeployment(deployment1Id);
        ProjectDeploymentDTO result2 = projectDeploymentFacade.getProjectDeployment(deployment2Id);

        // Then - Each deployment should have its OWN execution date, not mixed up
        assertThat(result1.projectDeploymentWorkflows())
            .first()
            .satisfies(workflow -> assertThat(workflow.lastExecutionDate()).isEqualTo(executionTime1));

        assertThat(result2.projectDeploymentWorkflows())
            .first()
            .satisfies(workflow -> assertThat(workflow.lastExecutionDate()).isEqualTo(executionTime2));

        // Verify the correct deployment IDs were used in the queries
        verify(principalJobService, atLeastOnce()).fetchLastWorkflowJobId(
            eq(deployment1Id), anyList(), eq(PlatformType.AUTOMATION));
        verify(principalJobService, atLeastOnce()).fetchLastWorkflowJobId(
            eq(deployment2Id), anyList(), eq(PlatformType.AUTOMATION));
    }

    @Test
    public void testUpdateProjectVersionWithWorkflowDisabledShouldDisableTriggers() {
        // Given - Create a project with a trigger-enabled workflow and publish v1
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeploymentWithTriggers(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), true);

        String v1WorkflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), v1WorkflowId, true);

        // And publish v2
        projectFacade.publishProject(projectDTO.id(), "Published v2 for test", false);

        // And get the v2 workflow ID and the workflow UUID
        List<ProjectWorkflow> v2ProjectWorkflows = projectWorkflowRepository.findAllByProjectIdAndProjectVersion(
            projectDTO.id(), 2);

        ProjectWorkflow v2ProjectWorkflow = v2ProjectWorkflows.getFirst();

        String v2WorkflowId = v2ProjectWorkflow.getWorkflowId();
        String workflowUuid = v2ProjectWorkflow.getUuidAsString();

        // When - Reset mock to clear calls from the enable step, then update to v2 with workflow disabled
        reset(triggerLifecycleFacade);

        ProjectDeploymentWorkflowDTO disabledWorkflowDTO = new ProjectDeploymentWorkflowDTO(
            List.of(), null, null, Map.of(), false, null, null, null, null, null, null, null, 0, v2WorkflowId,
            workflowUuid);

        ProjectDeploymentDTO currentDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        ProjectDeploymentDTO updateDTO = ProjectDeploymentDTO.builder()
            .id(projectDeploymentDTO.id())
            .projectId(projectDTO.id())
            .name(projectDeploymentDTO.name())
            .enabled(true)
            .environment(projectDeploymentDTO.environment())
            .projectVersion(2)
            .projectDeploymentWorkflows(List.of(disabledWorkflowDTO))
            .version(currentDeployment.version())
            .build();

        projectDeploymentFacade.updateProjectDeployment(updateDTO);

        // Then - Verify the deployment workflow is now disabled
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> assertThat(workflow.enabled()).isFalse());

        // And verify that trigger disable was called for the previously-enabled workflow
        verify(triggerLifecycleFacade, atLeastOnce()).executeTriggerDisable(any(), any(), any(), any(), any());
    }

    @Test
    public void testUpdateProjectVersionWithWorkflowStillEnabledShouldReenableTriggers() {
        // Given - Create a project with a trigger-enabled workflow and publish v1
        ProjectDTO projectDTO = projectDeploymentFacadeHelper.createProject(workspace.getId());

        ProjectDeploymentDTO projectDeploymentDTO = projectDeploymentFacadeHelper.createProjectDeploymentWithTriggers(
            workspace.getId(), projectDTO);

        projectDeploymentFacade.enableProjectDeployment(projectDeploymentDTO.id(), true);

        String v1WorkflowId = projectDeploymentDTO.projectDeploymentWorkflows()
            .getFirst()
            .workflowId();

        projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentDTO.id(), v1WorkflowId, true);

        // And publish v2
        projectFacade.publishProject(projectDTO.id(), "Published v2 for test", false);

        // And get the v2 workflow ID and the workflow UUID
        List<ProjectWorkflow> v2ProjectWorkflows = projectWorkflowRepository.findAllByProjectIdAndProjectVersion(
            projectDTO.id(), 2);

        ProjectWorkflow v2ProjectWorkflow = v2ProjectWorkflows.getFirst();

        String v2WorkflowId = v2ProjectWorkflow.getWorkflowId();
        String workflowUuid = v2ProjectWorkflow.getUuidAsString();

        // When - Reset mock to clear calls from the enable step, then update to v2 with workflow still enabled
        reset(triggerLifecycleFacade);

        ProjectDeploymentWorkflowDTO enabledWorkflowDTO = new ProjectDeploymentWorkflowDTO(
            List.of(), null, null, Map.of(), true, null, null, null, null, null, null, null, 0, v2WorkflowId,
            workflowUuid);

        ProjectDeploymentDTO currentDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        ProjectDeploymentDTO updateDTO = ProjectDeploymentDTO.builder()
            .id(projectDeploymentDTO.id())
            .projectId(projectDTO.id())
            .name(projectDeploymentDTO.name())
            .enabled(true)
            .environment(projectDeploymentDTO.environment())
            .projectVersion(2)
            .projectDeploymentWorkflows(List.of(enabledWorkflowDTO))
            .version(currentDeployment.version())
            .build();

        projectDeploymentFacade.updateProjectDeployment(updateDTO);

        // Then - Verify the workflow is still enabled with the new v2 workflow ID
        ProjectDeploymentDTO updatedDeployment = projectDeploymentFacade.getProjectDeployment(
            projectDeploymentDTO.id());

        assertThat(updatedDeployment.projectDeploymentWorkflows())
            .hasSize(1)
            .first()
            .satisfies(workflow -> {
                assertThat(workflow.enabled()).isTrue();
                assertThat(workflow.workflowId()).isEqualTo(v2WorkflowId);
            });

        // And verify that old triggers were disabled and new triggers were enabled
        verify(triggerLifecycleFacade, atLeastOnce()).executeTriggerDisable(any(), any(), any(), any(), any());
        verify(triggerLifecycleFacade, atLeastOnce()).executeTriggerEnable(
            any(), any(), any(), any(), any(), any(), anyLong());
    }

    /**
     * The deploy rules of the Apps agent loop against real project, version and deployment rows. Connection candidates
     * come from a mocked {@link WorkspaceConnectionFacade}: what it returns stands for what the caller can see, so a
     * PRIVATE connection of someone else is simply absent from its answer. Method security is not active in this
     * context; the guard is pinned by {@code ProjectDeploymentFacadeAuthorizationTest}.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
        classes = ProjectIntTestConfiguration.class,
        properties = {
            "bytechef.workflow.repository.jdbc.enabled=true"
        })
    @Import(PostgreSQLContainerConfiguration.class)
    @ProjectIntTestConfigurationSharedMocks
    class DeployProject {

        private static final String SLACK = "slack";
        private static final String SLACK_NODE = "sendMessage_1";
        private static final ComponentConnection SLACK_SLOT =
            new ComponentConnection(SLACK, 1, SLACK_NODE, SLACK, true);
        private static final String WORKFLOW_A = """
            {"label": "Sync A", "triggers": [], "tasks": []}""";
        private static final String WORKFLOW_B = """
            {"label": "Sync B", "triggers": [], "tasks": []}""";
        private static final String WORKFLOW_WITH_TRIGGER = """
            {"label": "Sync A",
             "triggers": [{"label": "Schedule", "name": "trigger_1", "type": "schedule/v1/schedule"}],
             "tasks": []}""";
        private static final String WORKFLOW_B_WITH_TRIGGER = """
            {"label": "Sync B",
             "triggers": [{"label": "Schedule", "name": "trigger_1", "type": "schedule/v1/schedule"}],
             "tasks": []}""";
        private static final String WORKFLOW_WITH_INPUT = """
            {"label": "Sync A",
             "inputs": [{"name": "region", "label": "Region", "type": "string", "required": true}],
             "triggers": [], "tasks": []}""";

        @Autowired
        private ComponentConnectionFacade componentConnectionFacade;

        @MockitoBean
        private ConnectionDefinitionService connectionDefinitionService;

        @Autowired
        private ConnectionService connectionService;

        @MockitoBean
        private PermissionService permissionService;

        @Autowired
        private ProjectService projectService;

        @Autowired
        private ProjectWorkflowService projectWorkflowService;

        @MockitoBean
        private WorkspaceConnectionFacade workspaceConnectionFacade;

        @Autowired
        private WorkflowService workflowService;

        @BeforeEach
        void beforeEachDeployProject() {
            ConnectionDefinition connectionDefinition = mock(ConnectionDefinition.class);

            when(connectionDefinition.getVersion()).thenReturn(1);
            when(connectionDefinitionService.getConnectionDefinition(SLACK, 1)).thenReturn(connectionDefinition);
            when(componentConnectionFacade.getWorkflowComponentConnections(anyString()))
                .thenReturn(List.of(SLACK_SLOT));
            when(componentConnectionFacade.getComponentConnection(anyString(), eq(SLACK_NODE), eq(SLACK)))
                .thenReturn(SLACK_SLOT);
            when(connectionService.getConnection(anyLong()))
                .thenAnswer(invocation -> connection(invocation.getArgument(0)));
            when(permissionService.hasResourceScope(any(), eq("Connection"), eq("CONNECTION_USE"))).thenReturn(true);

            stubCandidates(connectionDTO(101L, "Slack A"));
        }

        @Test
        public void testCreatesTheDeploymentWithARowForEveryWorkflowAndPicksTheOnlyCandidate() {
            long projectId = createPublishedProject(WORKFLOW_A, WORKFLOW_B);
            String workflowAUuid = uuidOf(projectId, "Sync A");
            String workflowBUuid = uuidOf(projectId, "Sync B");

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowAUuid), List.of(), Map.of());

            assertThat(result.written()).isTrue();

            long projectDeploymentId = Objects.requireNonNull(result.projectDeploymentId());

            ProjectDeployment projectDeployment = projectDeploymentRepository.findById(projectDeploymentId)
                .orElseThrow();

            assertThat(projectDeployment.getEnvironment()).isEqualTo(Environment.DEVELOPMENT);
            assertThat(projectDeployment.isEnabled()).isFalse();
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId))
                .extracting(ProjectDeploymentWorkflow::isEnabled, ProjectDeploymentWorkflow::getConnectionsCount)
                .containsExactlyInAnyOrder(tuple(true, 1), tuple(false, 1));
            assertThat(result.workflows())
                .extracting(DeployedWorkflow::workflowUuid, DeployedWorkflow::enabled)
                .containsExactlyInAnyOrder(tuple(workflowAUuid, true), tuple(workflowBUuid, false));
            assertThat(result.workflows())
                .allSatisfy(deployedWorkflow -> {
                    assertThat(deployedWorkflow.draftWorkflowId()).isNotEqualTo(deployedWorkflow.workflowId());
                    assertThat(deployedWorkflow.connections())
                        .extracting(DeployedConnection::connectionId, DeployedConnection::connectionName)
                        .containsExactly(tuple(101L, "Slack A"));
                });
        }

        @Test
        public void testRedeployAfterPublishUpdatesTheSameDeploymentSoItsBindingsStayValid() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowUuid));

            updateDraft(workflowUuid, """
                {"label": "Sync A", "description": "changed", "triggers": [], "tasks": []}""");

            projectFacade.publishProject(projectId, "Second", false);

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(), List.of(), Map.of());

            String publishedWorkflowId = publishedWorkflowId(projectId, workflowUuid);

            assertThat(result.projectDeploymentId()).isEqualTo(projectDeploymentId);
            assertThat(result.removedWorkflows()).isEmpty();
            assertThat(projectWorkflowService.fetchProjectWorkflowWorkflowId(projectDeploymentId, workflowUuid))
                .contains(publishedWorkflowId);
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId))
                .singleElement()
                .satisfies(projectDeploymentWorkflow -> {
                    assertThat(projectDeploymentWorkflow.getWorkflowId()).isEqualTo(publishedWorkflowId);
                    assertThat(projectDeploymentWorkflow.isEnabled()).isTrue();
                });
        }

        @Test
        public void testSubsetOfEnabledWorkflowsKeepsEveryOtherRowAndItsEnabledState() {
            long projectId = createPublishedProject(WORKFLOW_A, WORKFLOW_B);
            String workflowAUuid = uuidOf(projectId, "Sync A");
            String workflowBUuid = uuidOf(projectId, "Sync B");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowAUuid, workflowBUuid));

            deploy(projectId, projectDeploymentId, List.of(workflowAUuid));

            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId))
                .extracting(ProjectDeploymentWorkflow::isEnabled)
                .containsExactly(true, true);
        }

        @Test
        public void testExplicitConnectionWithoutConnectionUseIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            stubCandidates(connectionDTO(101L, "Slack A"), connectionDTO(102L, "Slack B"));

            when(permissionService.hasResourceScope(eq(102L), eq("Connection"), eq("CONNECTION_USE")))
                .thenReturn(false);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, 102L)), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Connection 102 cannot be used");

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testPrivateConnectionInvisibleToTheCallerIsNeitherACandidateNorAccepted() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, 103L)), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Connection 103 cannot be used");

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of());

            assertThat(result.workflows()
                .getFirst()
                .connections())
                    .extracting(DeployedConnection::connectionId)
                    .containsExactly(101L);
        }

        @Test
        public void testDeploymentOfProductionIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            long productionDeploymentId = saveDeployment(projectId, "Orders", Environment.PRODUCTION);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, productionDeploymentId, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("is not a DEVELOPMENT deployment of project");
        }

        @Test
        public void testDeploymentOfAnotherProjectIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            long otherProjectId = createPublishedProject(WORKFLOW_B);
            long otherDeploymentId = saveDeployment(otherProjectId, "Other", Environment.DEVELOPMENT);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, otherDeploymentId, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("is not a DEVELOPMENT deployment of project");
        }

        @Test
        public void testCreateWhenTheProjectAlreadyHasADevelopmentDeploymentIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);

            saveDeployment(projectId, "Orders", Environment.DEVELOPMENT);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("pass its projectDeploymentId");
        }

        @Test
        public void testSystemDeploymentOfTheProjectIsIgnored() {
            long projectId = createPublishedProject(WORKFLOW_A);
            long mcpServerDeploymentId = saveDeployment(
                projectId, SystemProjects.MCP_SERVER_DEPLOYMENT_NAME_PREFIX + "orders", Environment.DEVELOPMENT);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, mcpServerDeploymentId, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class);

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(), List.of(), Map.of());

            assertThat(result.projectDeploymentId()).isNotEqualTo(mcpServerDeploymentId);
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(mcpServerDeploymentId))
                .isEmpty();
        }

        @Test
        public void testRemovedWorkflowsAreReported() {
            long projectId = createPublishedProject(WORKFLOW_A, WORKFLOW_B);
            String workflowAUuid = uuidOf(projectId, "Sync A");
            String workflowBUuid = uuidOf(projectId, "Sync B");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowAUuid, workflowBUuid));

            projectWorkflowFacade.deleteWorkflow(projectWorkflowService.getLastWorkflowId(workflowBUuid));
            projectFacade.publishProject(projectId, "Without B", false);

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(), List.of(), Map.of());

            assertThat(result.removedWorkflows()).containsExactly(new RemovedWorkflow(workflowBUuid, "Sync B"));
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId))
                .hasSize(1);
        }

        @Test
        public void testAmbiguousRequiredSlotOfAnEnabledWorkflowWritesNothing() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            stubCandidates(connectionDTO(101L, "Slack A"), connectionDTO(102L, "Slack B"));

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of());

            assertThat(result.written()).isFalse();
            assertThat(result.projectDeploymentId()).isNull();
            assertThat(result.unresolvedConnections())
                .singleElement()
                .satisfies(unresolvedConnection -> {
                    assertThat(unresolvedConnection.workflowUuid()).isEqualTo(workflowUuid);
                    assertThat(unresolvedConnection.workflowNodeName()).isEqualTo(SLACK_NODE);
                    assertThat(unresolvedConnection.required()).isTrue();
                    assertThat(unresolvedConnection.candidates())
                        .extracting(DeployProjectResultDTO.Candidate::id)
                        .containsExactly(101L, 102L);
                });
            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testDisabledWorkflowWithAnAmbiguousSlotDoesNotBlock() {
            long projectId = createPublishedProject(WORKFLOW_A);

            stubCandidates(connectionDTO(101L, "Slack A"), connectionDTO(102L, "Slack B"));

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(), List.of(), Map.of());

            assertThat(result.written()).isTrue();
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(
                Objects.requireNonNull(result.projectDeploymentId())))
                    .singleElement()
                    .satisfies(projectDeploymentWorkflow -> {
                        assertThat(projectDeploymentWorkflow.isEnabled()).isFalse();
                        assertThat(projectDeploymentWorkflow.getConnectionsCount()).isZero();
                    });
        }

        @Test
        public void testMissingRequiredInputWritesNothingAndAGivenOneIsStored() {
            long projectId = createPublishedProject(WORKFLOW_WITH_INPUT);
            String workflowUuid = uuidOf(projectId, "Sync A");

            DeployProjectResultDTO missing = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of());

            assertThat(missing.written()).isFalse();
            assertThat(missing.missingInputs()).containsExactly(new MissingInput(workflowUuid, "region"));
            assertThat(projectDeploymentRepository.count()).isZero();

            DeployProjectResultDTO written = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of(workflowUuid, Map.of("region", "eu")));

            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(
                Objects.requireNonNull(written.projectDeploymentId())))
                    .singleElement()
                    .satisfies(projectDeploymentWorkflow -> assertThat(projectDeploymentWorkflow.getInputs())
                        .extractingByKey("region")
                        .isEqualTo("eu"));
        }

        @Test
        public void testNeverPublishedProjectIsRefused() {
            ProjectDTO projectDTO =
                projectDeploymentFacadeHelper.createProject(Objects.requireNonNull(workspace.getId()));

            projectDeploymentFacadeHelper.addTestWorkflow(projectDTO, WORKFLOW_A);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                Objects.requireNonNull(projectDTO.id()), null, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("call publishProject first");
        }

        @Test
        public void testChangedDefinitionIsAnUnpublishedChange() {
            long projectId = createPublishedProject(WORKFLOW_A);

            updateDraft(uuidOf(projectId, "Sync A"), """
                {"label": "Sync A", "description": "changed", "triggers": [], "tasks": []}""");

            assertUnpublishedChanges(projectId);
        }

        @Test
        public void testAddedWorkflowIsAnUnpublishedChange() {
            long projectId = createPublishedProject(WORKFLOW_A);

            projectWorkflowFacade.addWorkflow(projectId, WORKFLOW_B);

            assertUnpublishedChanges(projectId);
        }

        @Test
        public void testRemovedWorkflowIsAnUnpublishedChange() {
            long projectId = createPublishedProject(WORKFLOW_A, WORKFLOW_B);

            projectWorkflowFacade.deleteWorkflow(projectWorkflowService.getLastWorkflowId(uuidOf(projectId, "Sync B")));

            assertUnpublishedChanges(projectId);
        }

        @Test
        public void testRedeployOfTheSameVersionUpdatesRowsInPlace() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowUuid));

            stubCandidates(connectionDTO(101L, "Slack A"), connectionDTO(102L, "Slack B"));

            deploy(projectId, projectDeploymentId, List.of());

            assertThat(connectionIdsOf(projectDeploymentId)).containsExactly(101L);

            projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, 102L)), Map.of());

            assertThat(connectionIdsOf(projectDeploymentId)).containsExactly(102L);
        }

        @Test
        public void testUnknownWorkflowUuidIsRefusedNamingTheVersionsUuids() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");
            String publishedWorkflowId = publishedWorkflowId(projectId, workflowUuid);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(publishedWorkflowId), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(workflowUuid);

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testConnectionForAnUnknownSlotIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, "missing_1", SLACK, 101L)), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("has no connection slot missing_1/slack");

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testConnectionChoiceWithANullSlotFieldIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, null, SLACK, 101L)), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("has no connection slot null/slack");

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testNullWorkflowUuidIsRefusedNamingTheVersionsUuids() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, Arrays.asList(workflowUuid, null), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("[null]")
                    .hasMessageContaining(workflowUuid);

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testSystemProjectIsRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);

            renameProject(projectId, SystemProjects.KNOWLEDGE_BASE_NAME_PREFIX + "orders");

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(), List.of(), Map.of()))
                    .isInstanceOf(NoSuchElementException.class);

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testRedeployOfAnEnabledDeploymentLeavesItDisabledWithItsRowsIntact() {
            long projectId = createPublishedProject(WORKFLOW_WITH_TRIGGER);
            String workflowUuid = uuidOf(projectId, "Sync A");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowUuid));

            projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(), List.of(), Map.of());

            assertThat(result.written()).isTrue();
            assertThat(result.projectDeploymentId()).isEqualTo(projectDeploymentId);
            assertThat(projectDeploymentRepository.findById(projectDeploymentId)
                .orElseThrow()
                .isEnabled()).isFalse();
            assertThat(projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId))
                .singleElement()
                .satisfies(projectDeploymentWorkflow -> assertThat(projectDeploymentWorkflow.isEnabled()).isTrue());
            assertThat(connectionIdsOf(projectDeploymentId)).containsExactly(101L);

            verify(triggerLifecycleFacade).executeTriggerDisable(any(), any(), any(), any(), any());
        }

        @Test
        public void testFailedRedeployWriteLeavesTheEnabledDeploymentArmed() {
            long projectId = createPublishedProject(WORKFLOW_WITH_TRIGGER);
            String workflowUuid = uuidOf(projectId, "Sync A");

            long projectDeploymentId = deploy(projectId, null, List.of(workflowUuid));

            projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

            Connection productionConnection = connection(101L);

            productionConnection.setEnvironmentId(Environment.PRODUCTION.ordinal());

            doReturn(productionConnection).when(connectionService)
                .getConnection(101L);

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class);

            assertThat(projectDeploymentRepository.findById(projectDeploymentId)
                .orElseThrow()
                .isEnabled()).isTrue();
            assertThat(connectionIdsOf(projectDeploymentId)).containsExactly(101L);

            verify(triggerLifecycleFacade, never()).executeTriggerDisable(any(), any(), any(), any(), any());
        }

        @Test
        public void testFailedDeferredDisarmReArmsTheTriggersAlreadyDisarmed() {
            long projectId = createPublishedProject(WORKFLOW_WITH_TRIGGER, WORKFLOW_B_WITH_TRIGGER);

            long projectDeploymentId = deploy(
                projectId, null, List.of(uuidOf(projectId, "Sync A"), uuidOf(projectId, "Sync B")));

            projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

            List<String> disarmedWorkflowExecutionIds = new ArrayList<>();
            IllegalStateException disarmingFailure = new IllegalStateException("schedule deregistration refused");

            doAnswer(invocation -> {
                disarmedWorkflowExecutionIds.add(String.valueOf(invocation.getArgument(1, WorkflowExecutionId.class)));

                if (disarmedWorkflowExecutionIds.size() == 2) {
                    throw disarmingFailure;
                }

                return null;
            }).when(triggerLifecycleFacade)
                .executeTriggerDisable(any(), any(), any(), any(), any());

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, List.of(), List.of(), Map.of()))
                    .isSameAs(disarmingFailure);

            assertThat(disarmingFailure.getSuppressed()).isEmpty();
            assertThat(projectDeploymentRepository.findById(projectDeploymentId)
                .orElseThrow()
                .isEnabled()).isTrue();

            InOrder inOrder = inOrder(triggerLifecycleFacade);

            inOrder.verify(triggerLifecycleFacade, times(2))
                .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
            inOrder.verify(triggerLifecycleFacade, times(2))
                .executeTriggerDisable(any(), any(), any(), any(), any());
            inOrder.verify(triggerLifecycleFacade)
                .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
            verifyNoMoreInteractions(triggerLifecycleFacade);

            ArgumentCaptor<String> workflowIdArgumentCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<WorkflowExecutionId> workflowExecutionIdArgumentCaptor =
                ArgumentCaptor.forClass(WorkflowExecutionId.class);
            ArgumentCaptor<String> webhookUrlArgumentCaptor = ArgumentCaptor.forClass(String.class);

            verify(triggerLifecycleFacade, times(3)).executeTriggerEnable(
                workflowIdArgumentCaptor.capture(), workflowExecutionIdArgumentCaptor.capture(), any(), any(), any(),
                webhookUrlArgumentCaptor.capture(), eq((long) Environment.DEVELOPMENT.ordinal()));

            List<String> armedWorkflowExecutionIds = workflowExecutionIdArgumentCaptor.getAllValues()
                .stream()
                .map(String::valueOf)
                .toList();
            int originalArmingIndex = armedWorkflowExecutionIds.subList(0, 2)
                .indexOf(disarmedWorkflowExecutionIds.getFirst());

            assertThat(originalArmingIndex).isNotNegative();
            assertThat(armedWorkflowExecutionIds.get(2)).isEqualTo(disarmedWorkflowExecutionIds.getFirst());
            assertThat(workflowIdArgumentCaptor.getAllValues()
                .get(2)).isEqualTo(workflowIdArgumentCaptor.getAllValues()
                    .get(originalArmingIndex));
            assertThat(webhookUrlArgumentCaptor.getAllValues()
                .get(2)).isEqualTo(webhookUrlArgumentCaptor.getAllValues()
                    .get(originalArmingIndex));
        }

        @Test
        public void testSeveralConnectionChoicesForOneSlotAreRefused() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            stubCandidates(connectionDTO(101L, "Slack A"), connectionDTO(102L, "Slack B"));

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(
                    new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, 101L),
                    new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, 102L)),
                Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("has several connection choices for slot sendMessage_1/slack");

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        @Test
        public void testAiProviderConnectionIsNeverACandidate() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");
            long aiProviderConnectionId = AiProviderConnectionId.encode(1, Environment.DEVELOPMENT.ordinal());

            stubCandidates(connectionDTO(aiProviderConnectionId, "OpenAI"), connectionDTO(101L, "Slack A"));

            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid),
                List.of(new DeploymentConnectionChoiceDTO(workflowUuid, SLACK_NODE, SLACK, aiProviderConnectionId)),
                Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Connection %d cannot be used".formatted(aiProviderConnectionId));

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of());

            assertThat(connectionIdsOf(Objects.requireNonNull(result.projectDeploymentId()))).containsExactly(101L);
        }

        @Test
        public void testConnectionWithInvalidCredentialsIsNeverACandidate() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            ConnectionDTO invalidConnectionDTO = ConnectionDTO.builder(connectionDTO(102L, "Slack B"))
                .credentialStatus(CredentialStatus.INVALID)
                .build();

            stubCandidates(invalidConnectionDTO, connectionDTO(101L, "Slack A"));

            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, null, List.of(workflowUuid), List.of(), Map.of());

            assertThat(connectionIdsOf(Objects.requireNonNull(result.projectDeploymentId()))).containsExactly(101L);
        }

        @Test
        public void testOptionalSlotGetsItsOnlyCandidateAndIsSkippedWithoutOne() {
            long projectId = createPublishedProject(WORKFLOW_A);
            String workflowUuid = uuidOf(projectId, "Sync A");

            when(componentConnectionFacade.getWorkflowComponentConnections(anyString()))
                .thenReturn(List.of(new ComponentConnection(SLACK, 1, SLACK_NODE, SLACK, false)));

            stubCandidates();

            long projectDeploymentId = deploy(projectId, null, List.of(workflowUuid));

            assertThat(connectionIdsOf(projectDeploymentId)).isEmpty();

            stubCandidates(connectionDTO(101L, "Slack A"));

            deploy(projectId, projectDeploymentId, List.of(workflowUuid));

            assertThat(connectionIdsOf(projectDeploymentId)).containsExactly(101L);
        }

        private void assertUnpublishedChanges(long projectId) {
            assertThatThrownBy(() -> projectDeploymentFacade.deployProject(
                projectId, null, List.of(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("has unpublished changes; call publishProject first");

            assertThat(projectDeploymentRepository.count()).isZero();
        }

        private List<Long> connectionIdsOf(long projectDeploymentId) {
            return projectDeploymentWorkflowRepository.findAllByProjectDeploymentId(projectDeploymentId)
                .stream()
                .flatMap(projectDeploymentWorkflow -> projectDeploymentWorkflow.getConnections()
                    .stream())
                .map(ProjectDeploymentWorkflowConnection::getConnectionId)
                .toList();
        }

        private long createPublishedProject(String... workflowDefinitions) {
            ProjectDTO projectDTO =
                projectDeploymentFacadeHelper.createProject(Objects.requireNonNull(workspace.getId()));

            for (String workflowDefinition : workflowDefinitions) {
                projectDeploymentFacadeHelper.addTestWorkflow(projectDTO, workflowDefinition);
            }

            long projectId = Objects.requireNonNull(projectDTO.id());

            projectFacade.publishProject(projectId, "Published for test", false);

            return projectId;
        }

        private long deploy(long projectId, Long projectDeploymentId, List<String> enabledWorkflowUuids) {
            DeployProjectResultDTO result = projectDeploymentFacade.deployProject(
                projectId, projectDeploymentId, enabledWorkflowUuids, List.of(), Map.of());

            assertThat(result.written()).isTrue();

            return Objects.requireNonNull(result.projectDeploymentId());
        }

        private List<ProjectWorkflow> publishedProjectWorkflows(long projectId) {
            Project project = projectService.getProject(projectId);

            ProjectVersion projectVersion = Objects.requireNonNull(project.getLastPublishedProjectVersion());

            return projectWorkflowService.getProjectWorkflows(projectId, projectVersion.getVersion());
        }

        private String publishedWorkflowId(long projectId, String workflowUuid) {
            return publishedProjectWorkflows(projectId).stream()
                .filter(projectWorkflow -> Objects.equals(projectWorkflow.getUuidAsString(), workflowUuid))
                .map(ProjectWorkflow::getWorkflowId)
                .findFirst()
                .orElseThrow();
        }

        private void renameProject(long projectId, String name) {
            Project project = projectRepository.findById(projectId)
                .orElseThrow();

            project.setName(name);

            projectRepository.save(project);
        }

        private long saveDeployment(long projectId, String name, Environment environment) {
            ProjectDeployment projectDeployment = new ProjectDeployment();

            projectDeployment.setEnabled(false);
            projectDeployment.setEnvironment(environment);
            projectDeployment.setName(name);
            projectDeployment.setProjectId(projectId);
            projectDeployment.setProjectVersion(
                Objects.requireNonNull(projectService.getProject(projectId)
                    .getLastPublishedProjectVersion())
                    .getVersion());
            projectDeployment.setUuid(UUID.randomUUID());

            ProjectDeployment savedProjectDeployment = projectDeploymentRepository.save(projectDeployment);

            return Objects.requireNonNull(savedProjectDeployment.getId());
        }

        private void stubCandidates(ConnectionDTO... connectionDTOs) {
            when(
                workspaceConnectionFacade.getConnections(
                    eq(Objects.requireNonNull(workspace.getId())), eq(SLACK), eq(1), eq(0L), isNull()))
                        .thenReturn(List.of(connectionDTOs));
        }

        private void updateDraft(String workflowUuid, String definition) {
            String draftWorkflowId = projectWorkflowService.getLastWorkflowId(workflowUuid);

            ProjectWorkflowDTO projectWorkflowDTO = projectWorkflowFacade.getProjectWorkflow(draftWorkflowId);

            projectWorkflowFacade.updateWorkflow(draftWorkflowId, definition, projectWorkflowDTO.getVersion());
        }

        private String uuidOf(long projectId, String label) {
            return publishedProjectWorkflows(projectId).stream()
                .filter(projectWorkflow -> Objects.equals(
                    workflowService.getWorkflow(projectWorkflow.getWorkflowId())
                        .getLabel(),
                    label))
                .map(ProjectWorkflow::getUuidAsString)
                .findFirst()
                .orElseThrow();
        }

        private static Connection connection(long id) {
            Connection connection = new Connection();

            connection.setComponentName(SLACK);
            connection.setEnvironmentId(Environment.DEVELOPMENT.ordinal());
            connection.setId(id);
            connection.setName("Slack " + id);

            return connection;
        }

        private static ConnectionDTO connectionDTO(long id, String name) {
            return ConnectionDTO.builder()
                .componentName(SLACK)
                .connectionVersion(1)
                .credentialStatus(CredentialStatus.VALID)
                .environmentId(Environment.DEVELOPMENT.ordinal())
                .id(id)
                .name(name)
                .status(ConnectionStatus.ACTIVE)
                .visibility(ResourceVisibility.WORKSPACE)
                .build();
        }
    }

    @Nested
    @ContextConfiguration(classes = PreAuthorizeEnforcement.MethodSecurityConfiguration.class)
    class PreAuthorizeEnforcement {

        private static final long PROJECT_DEPLOYMENT_ID = 21L;
        private static final long PROJECT_ID = 11L;
        private static final String WORKFLOW_ID = "workflow-41";

        @MockitoBean
        private PermissionService permissionService;

        @BeforeEach
        void beforeEach() {
            reset(permissionService);

            authenticate(AuthorityConstants.USER);
        }

        @AfterEach
        void afterEach() {
            SecurityContextHolder.clearContext();
        }

        @Test
        void testEveryOverloadChecksDeploymentPush() {
            ProjectDeploymentDTO projectDeploymentDTO = ProjectDeploymentDTO.builder()
                .environment(Environment.PRODUCTION)
                .id(PROJECT_DEPLOYMENT_ID)
                .projectId(PROJECT_ID)
                .projectVersion(1)
                .build();

            ProjectDeployment projectDeployment = new ProjectDeployment();

            projectDeployment.setProjectId(PROJECT_ID);

            // The DTO overloads check the role held in the TARGET environment (read off the DTO), so they route
            // through hasWorkspaceScopeForProject(projectId, scope, environment) rather than hasResourceScope.
            assertRequiresTargetEnvironmentScopes(
                () -> projectDeploymentFacade.createProjectDeployment(projectDeploymentDTO),
                () -> when(permissionService.hasWorkspaceScopeForProject(
                    PROJECT_ID, "WORKFLOW_EDIT", Environment.PRODUCTION)).thenReturn(true),
                () -> when(permissionService.hasWorkspaceScopeForProject(
                    PROJECT_ID, "DEPLOYMENT_PUSH", Environment.PRODUCTION)).thenReturn(true));
            assertRequiresScope(
                PROJECT_ID, "Project", "DEPLOYMENT_PUSH",
                () -> projectDeploymentFacade.createProjectDeployment(projectDeployment, WORKFLOW_ID, List.of()));
            assertRequiresScope(
                PROJECT_ID, "Project", "DEPLOYMENT_PUSH",
                () -> projectDeploymentFacade.createProjectDeployment(projectDeployment, List.of(), List.of()));
            assertRequiresTargetEnvironmentScopes(
                () -> projectDeploymentFacade.updateProjectDeployment(projectDeploymentDTO),
                () -> when(permissionService.hasResourceScope(
                    PROJECT_DEPLOYMENT_ID, "ProjectDeployment", "DEPLOYMENT_EDIT")).thenReturn(true),
                () -> when(permissionService.hasWorkspaceScopeForProject(
                    PROJECT_ID, "DEPLOYMENT_PUSH", Environment.PRODUCTION)).thenReturn(true));
            assertRequiresScope(
                PROJECT_ID, "Project", "DEPLOYMENT_PUSH",
                () -> projectDeploymentFacade.updateProjectDeployment(PROJECT_ID, 1, "uuid", List.of(), null));
            assertRequiresScope(
                PROJECT_ID, "Project", "DEPLOYMENT_PUSH",
                () -> projectDeploymentFacade.updateProjectDeployment(projectDeployment, List.of(), List.of()));

            assertThat(countOverloads(ProjectDeploymentFacadeImpl.class, "createProjectDeployment")).isEqualTo(3);
            assertThat(countOverloads(ProjectDeploymentFacadeImpl.class, "updateProjectDeployment")).isEqualTo(3);
        }

        /**
         * Each grant alone is denied; only both together let the call through.
         */
        private void assertRequiresTargetEnvironmentScopes(
            ThrowingCallable call, Runnable firstGrant, Runnable secondGrant) {

            reset(permissionService);

            assertDenied(call);

            firstGrant.run();

            assertDenied(call);

            reset(permissionService);

            secondGrant.run();

            assertDenied(call);

            firstGrant.run();

            assertNotDenied(call);

            reset(permissionService);
        }

        private void
            assertRequiresScope(Serializable targetId, String targetType, String scope, ThrowingCallable call) {
            reset(permissionService);

            assertDenied(call);

            when(permissionService.hasResourceScope(targetId, targetType, scope)).thenReturn(true);

            assertNotDenied(call);

            reset(permissionService);
        }

        private static void assertDenied(ThrowingCallable call) {
            assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
        }

        private static void assertNotDenied(ThrowingCallable call) {
            Throwable throwable = catchThrowable(call);

            assertThat(throwable)
                .as("Expected the gate to let the call through, but it was denied: %s", throwable)
                .satisfiesAnyOf(
                    actual -> assertThat(actual).isNull(),
                    actual -> assertThat(actual).isNotInstanceOf(AccessDeniedException.class));
        }

        private static void authenticate(String authority) {
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        "user", "n/a", List.of(new SimpleGrantedAuthority(authority))));
        }

        private static long countOverloads(Class<?> clazz, String methodName) {
            Method[] methods = clazz.getDeclaredMethods();

            return Arrays.stream(methods)
                .filter(method -> !method.isSynthetic())
                .filter(method -> methodName.equals(method.getName()))
                .count();
        }

        @EnableMethodSecurity
        static class MethodSecurityConfiguration {
        }
    }
}
