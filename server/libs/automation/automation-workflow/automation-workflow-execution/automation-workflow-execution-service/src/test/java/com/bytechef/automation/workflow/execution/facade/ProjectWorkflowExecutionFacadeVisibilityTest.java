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

package com.bytechef.automation.workflow.execution.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.service.ContextService;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.file.storage.TriggerFileStorage;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;

/**
 * An executions query narrowed by an explicit project id gets by-id semantics: a project the caller may not see is
 * denied outright rather than answered with an empty page.
 *
 * @author Ivica Cardic
 */
class ProjectWorkflowExecutionFacadeVisibilityTest {

    private static final long DEVELOPMENT_DEPLOYMENT_ID = 800L;
    private static final long PRODUCTION_DEPLOYMENT_ID = 900L;
    private static final long PROJECT_ID = 7L;
    private static final String WORKFLOW_ID = "workflow-1";
    private static final long WORKSPACE_ID = 1L;

    private final PermissionService permissionService = mock(PermissionService.class);
    private final PrincipalJobService principalJobService = mock(PrincipalJobService.class);
    private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);

    private ProjectWorkflowExecutionFacadeImpl projectWorkflowExecutionFacade;

    @BeforeEach
    void setUp() {
        projectWorkflowExecutionFacade = new ProjectWorkflowExecutionFacadeImpl(
            mock(ComponentDefinitionService.class), mock(ContextService.class), mock(Evaluator.class),
            environmentScopeFilterHoldingEveryEnvironment(), mock(EnvironmentService.class), mock(JobService.class),
            permissionService, principalJobService,
            mock(ProjectFacade.class), projectDeploymentService, projectService,
            projectWorkflowService, mock(TaskDispatcherDefinitionService.class), mock(TaskExecutionService.class),
            mock(TaskFileStorage.class), mock(TriggerExecutionService.class), mock(TriggerFileStorage.class),
            mock(WorkflowService.class));
    }

    @Test
    void testExecutionsOfAHiddenProjectAreDeniedRatherThanEmptied() {
        when(permissionService.hasResourceScope(PROJECT_ID, "Project", "EXECUTION_VIEW")).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class)
            .isThrownBy(
                () -> projectWorkflowExecutionFacade.getWorkflowExecutions(
                    null, null, null, null, null, PROJECT_ID, null, null, WORKSPACE_ID, 0))
            .withMessageContaining("Project id=7");
    }

    @Test
    void testExecutionsOfAVisibleProjectAreServed() {
        when(permissionService.hasResourceScope(PROJECT_ID, "Project", "EXECUTION_VIEW")).thenReturn(true);
        when(projectWorkflowService.getProjectWorkflowIds(PROJECT_ID)).thenReturn(List.of());

        Page<?> workflowExecutionPage = projectWorkflowExecutionFacade.getWorkflowExecutions(
            null, null, null, null, null, PROJECT_ID, null, null, WORKSPACE_ID, 0);

        assertThat(workflowExecutionPage).isEmpty();
    }

    /**
     * A real {@link EnvironmentScopeFilter} over a caller holding the scope in every environment, so the production
     * filtering code genuinely runs here but narrows nothing. That is the ordinary case rather than a convenience: a
     * member in implicit mode resolves in every environment, so this keeps the test about what it was already about
     * while still exercising the filter.
     */
    private static EnvironmentScopeFilter environmentScopeFilterHoldingEveryEnvironment() {
        PermissionService permissionService = mock(PermissionService.class);

        // lenient: most tests in this class never reach a filtered listing, and MockitoExtension's strict stubs
        // would fail every one of them for a stub this shared helper always sets up.
        lenient()
            .when(permissionService.getMyWorkspaceScopeEnvironments(anyLong(), anyString()))
            .thenReturn(EnumSet.allOf(Environment.class));

        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> permissionServiceProvider = mock(ObjectProvider.class);

        lenient()
            .when(permissionServiceProvider.getIfAvailable())
            .thenReturn(permissionService);

        return new EnvironmentScopeFilter(permissionServiceProvider);
    }

    /**
     * D4 of {@code docs/superpowers/specs/2026-09-06-environment-scoped-authorization-remaining-families-design.md},
     * for the one site of the three whose result is a {@link Page}.
     *
     * <p>
     * Filtering a page after the fact would be wrong here, not merely wasteful: the page is cut by the query, so
     * dropping rows from it yields short pages and, once every row on a page is dropped, an empty page that looks like
     * the end of the results. The narrowing therefore happens BEFORE the query, on the deployments the environment
     * actually hangs off, and the assertion has to match -- it pins the deployment ids the paged query is asked for,
     * not the rows that come back.
     */
    @Test
    void testExecutionsAreQueriedOnlyForDeploymentsInEnvironmentsTheCallerHolds() {
        Project project = new Project();

        project.setId(PROJECT_ID);
        project.setName("Customer Sync");

        when(permissionService.hasResourceScope(PROJECT_ID, "Project", "EXECUTION_VIEW")).thenReturn(true);
        when(projectWorkflowService.getProjectWorkflowIds(PROJECT_ID)).thenReturn(List.of(WORKFLOW_ID));
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
        when(projectDeploymentService.getProjectDeployments(null, null, null, null, null))
            .thenReturn(
                List.of(
                    projectDeployment(DEVELOPMENT_DEPLOYMENT_ID, Environment.DEVELOPMENT),
                    projectDeployment(PRODUCTION_DEPLOYMENT_ID, Environment.PRODUCTION)));
        when(
            principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(Page.empty());

        ProjectWorkflowExecutionFacadeImpl developmentOnlyFacade = facadeHolding(EnumSet.of(Environment.DEVELOPMENT));

        developmentOnlyFacade.getWorkflowExecutions(
            null, null, null, null, null, PROJECT_ID, null, null, WORKSPACE_ID, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> projectDeploymentIdsCaptor = ArgumentCaptor.forClass(List.class);

        verify(principalJobService).getJobIds(
            any(), any(), any(), projectDeploymentIdsCaptor.capture(), any(), anyList(), anyBoolean(), anyInt());

        // Both halves matter: the Production deployment must be absent, and the Development one present. A narrowing
        // that asked for nothing at all would also omit Production, and would empty the executions page for everyone.
        assertThat(projectDeploymentIdsCaptor.getValue()).containsExactly(DEVELOPMENT_DEPLOYMENT_ID);
    }

    private static ProjectDeployment projectDeployment(long id, Environment environment) {
        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(id);
        projectDeployment.setProjectId(PROJECT_ID);
        projectDeployment.setEnvironment(environment);

        return projectDeployment;
    }

    /**
     * A facade whose {@link EnvironmentScopeFilter} answers with exactly {@code environments}, so a test can put the
     * caller in one environment rather than in all of them.
     */
    private ProjectWorkflowExecutionFacadeImpl facadeHolding(Set<Environment> environments) {
        PermissionService scopedPermissionService = mock(PermissionService.class);

        when(scopedPermissionService.getMyWorkspaceScopeEnvironments(anyLong(), anyString()))
            .thenReturn(environments);

        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> permissionServiceProvider = mock(ObjectProvider.class);

        when(permissionServiceProvider.getIfAvailable()).thenReturn(scopedPermissionService);

        return new ProjectWorkflowExecutionFacadeImpl(
            mock(ComponentDefinitionService.class), mock(ContextService.class), mock(Evaluator.class),
            new EnvironmentScopeFilter(permissionServiceProvider), mock(EnvironmentService.class),
            mock(JobService.class), permissionService, principalJobService, mock(ProjectFacade.class),
            projectDeploymentService, projectService, projectWorkflowService,
            mock(TaskDispatcherDefinitionService.class), mock(TaskExecutionService.class), mock(TaskFileStorage.class),
            mock(TriggerExecutionService.class), mock(TriggerFileStorage.class), mock(WorkflowService.class));
    }

}
