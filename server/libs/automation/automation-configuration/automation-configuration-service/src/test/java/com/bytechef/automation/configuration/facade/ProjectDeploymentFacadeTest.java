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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.SystemProjects;
import com.bytechef.automation.configuration.dto.ProjectDeploymentDTO;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableOutput;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.constant.WorkflowExtConstants;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
@SuppressFBWarnings(
    value = {
        "CT_CONSTRUCTOR_THROW", "URF_UNREAD_FIELD"
    },
    justification = "Both are false positives of the @Spy field below. It IS read — by Mockito, reflectively, to "
        + "inject it into @InjectMocks — which SpotBugs cannot see; and its inline initializer is what puts the "
        + "field's construction inside this class's constructor. Nothing but the JUnit runner instantiates a test "
        + "class, so there is no finalizer-attack surface.")
class ProjectDeploymentFacadeTest {

    private static final long VISIBLE_PROJECT_ID = 1L;
    private static final long HIDDEN_PROJECT_ID = 2L;
    private static final long WORKSPACE_ID = 3L;
    private static final long SYSTEM_PROJECT_ID = 4L;
    private static final long ARMED_PROJECT_DEPLOYMENT_ID = 5L;
    private static final WorkflowNodeType WEBHOOK_TRIGGER_TYPE = new WorkflowNodeType("webhook", 1, "newEvent");

    @Mock
    private ApplicationProperties applicationProperties;

    /**
     * A real filter over a caller holding the scope in every environment, so the listing's environment narrowing runs
     * through the production seam and narrows nothing -- leaving these cases about project visibility, which is what
     * they pin. A spy for the same reason {@link #projectVisibilityFilter} is one: {@code @InjectMocks} injects only
     * mock and spy fields, and a plain {@code @Mock} here would return no rows at all and hide every deployment for a
     * reason none of these tests is about.
     */
    @Spy
    private EnvironmentScopeFilter environmentScopeFilter = new EnvironmentScopeFilter(
        objectProvider(permissionServiceHoldingEveryEnvironment()));

    @Mock
    private ConnectionService connectionService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Mock
    private ProjectDeploymentService projectDeploymentService;

    @Mock
    private ProjectService projectService;

    /**
     * A real filter over a resolver that hides {@link #HIDDEN_PROJECT_ID} and nothing else, so the deployment listing
     * is exercised through the same seam production uses rather than a stubbed answer. Hiding only that one project
     * rather than revealing only {@link #VISIBLE_PROJECT_ID} is what keeps the system-project filter separable: a
     * system project this resolver also hid would be dropped for two reasons at once, and neither case below would pin
     * either. Declared as a spy because {@code @InjectMocks} only injects mock/spy fields.
     */
    @Spy
    private ProjectVisibilityFilter projectVisibilityFilter = new ProjectVisibilityFilter(
        objectProvider(
            (resourceType, workspaceId, candidates) -> candidates.stream()
                .map(VisibilityRecord::id)
                .filter(id -> id != HIDDEN_PROJECT_ID)
                .collect(Collectors.toSet())));

    @Mock
    private TagService tagService;

    @InjectMocks
    private ProjectDeploymentFacadeImpl projectDeploymentFacade;

    @AfterEach
    void afterEach() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void testUpdateProjectDeploymentWorkflowRefusesConnectionFromAnotherEnvironment() {
        stubProjectDeploymentWorkflowUpdate(Environment.DEVELOPMENT, Environment.PRODUCTION);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = submittedProjectDeploymentWorkflow();

        assertThatThrownBy(() -> projectDeploymentFacade.updateProjectDeploymentWorkflow(projectDeploymentWorkflow))
            .isInstanceOf(ConfigurationException.class);

        verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
    }

    @Test
    void testUpdateProjectDeploymentWorkflowAcceptsConnectionFromTheDeploymentEnvironment() {
        stubProjectDeploymentWorkflowUpdate(Environment.DEVELOPMENT, Environment.DEVELOPMENT);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = submittedProjectDeploymentWorkflow();

        projectDeploymentFacade.updateProjectDeploymentWorkflow(projectDeploymentWorkflow);

        verify(projectDeploymentWorkflowService).update(projectDeploymentWorkflow);
    }

    private void
        stubProjectDeploymentWorkflowUpdate(Environment deploymentEnvironment, Environment connectionEnvironment) {
        ProjectDeploymentWorkflow persistedProjectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        persistedProjectDeploymentWorkflow.setId(30L);
        persistedProjectDeploymentWorkflow.setProjectDeploymentId(ARMED_PROJECT_DEPLOYMENT_ID);

        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(30L))
            .thenReturn(persistedProjectDeploymentWorkflow);
        when(projectDeploymentService.getProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(projectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, VISIBLE_PROJECT_ID, deploymentEnvironment));

        Connection connection = new Connection();

        connection.setEnvironmentId(connectionEnvironment.ordinal());
        connection.setName("connection");

        when(connectionService.getConnection(61L)).thenReturn(connection);
    }

    private static ProjectDeploymentWorkflow submittedProjectDeploymentWorkflow() {
        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setConnections(List.of(new ProjectDeploymentWorkflowConnection(61L, "key", "node")));
        projectDeploymentWorkflow.setEnabled(false);
        projectDeploymentWorkflow.setId(30L);
        projectDeploymentWorkflow.setProjectDeploymentId(ARMED_PROJECT_DEPLOYMENT_ID);

        return projectDeploymentWorkflow;
    }

    @Test
    void testWorkspaceDeploymentsHideDeploymentsOfHiddenProjects() {
        when(projectDeploymentService.getProjectDeployments(false, null, null, null, WORKSPACE_ID))
            .thenReturn(
                List.of(
                    projectDeployment(10L, VISIBLE_PROJECT_ID, Environment.DEVELOPMENT),
                    projectDeployment(11L, HIDDEN_PROJECT_ID, Environment.DEVELOPMENT)));
        when(projectService.getProjects(List.of(VISIBLE_PROJECT_ID, HIDDEN_PROJECT_ID)))
            .thenReturn(List.of(project(VISIBLE_PROJECT_ID), project(HIDDEN_PROJECT_ID)));

        List<ProjectDeploymentDTO> projectDeploymentDTOs =
            projectDeploymentFacade.getWorkspaceProjectDeployments(WORKSPACE_ID, null, null, null, false);

        assertThat(projectDeploymentDTOs).extracting(ProjectDeploymentDTO::projectId)
            .containsExactly(VISIBLE_PROJECT_ID);
    }

    @Test
    void testEveryDeploymentOfAHiddenProjectIsHidden() {
        // There is no per-deployment or per-environment opt-out: a PRODUCTION deployment of a private project is as
        // hidden as its DEVELOPMENT sibling. The project itself is returned by the project service and is not a
        // system project, so the only thing that can exclude these three rows is the visibility filter.
        when(projectDeploymentService.getProjectDeployments(false, null, null, null, WORKSPACE_ID))
            .thenReturn(
                List.of(
                    projectDeployment(10L, HIDDEN_PROJECT_ID, Environment.DEVELOPMENT),
                    projectDeployment(11L, HIDDEN_PROJECT_ID, Environment.STAGING),
                    projectDeployment(12L, HIDDEN_PROJECT_ID, Environment.PRODUCTION)));
        when(projectService.getProjects(anyList())).thenReturn(List.of(project(HIDDEN_PROJECT_ID)));

        assertThat(projectDeploymentFacade.getWorkspaceProjectDeployments(WORKSPACE_ID, null, null, null, false))
            .isEmpty();

        // The three deployments share one project, so the project lookup must ask for that id once. Deployments are
        // per-environment, so undeduplicated the id list grows with the number of environments a project is deployed
        // to rather than with the number of projects it has to load.
        ArgumentCaptor<List<Long>> projectIdsArgumentCaptor = ArgumentCaptor.captor();

        verify(projectService).getProjects(projectIdsArgumentCaptor.capture());

        assertThat(projectIdsArgumentCaptor.getValue()).containsExactly(HIDDEN_PROJECT_ID);
    }

    /**
     * The entity-returning overload, which the GraphQL {@code workspaceProjectDeployments} query delegates to. Its two
     * filters used to live in {@code ProjectDeploymentGraphQlController}, which read the rows off
     * {@code ProjectDeploymentService} directly and so had neither them nor a gate.
     *
     * <p>
     * The system-project half of this is a second line of defence, and the stub is what makes that visible: the row for
     * {@code SYSTEM_PROJECT_ID} is one the real query never returns, because
     * {@code CustomProjectDeploymentRepositoryImpl} appends
     * {@code SystemProjects.projectNameNotLikePredicates("project.name")} to every listing unconditionally, and
     * {@code ProjectDeploymentServiceSystemProjectIntTest} pins that against real rows. So this asserts that the facade
     * filter would still hold if the SQL one were removed or narrowed — not that a system-project deployment reaches
     * the facade today. The hidden-project half is the opposite: nothing below the facade applies visibility, so there
     * the filter under test is the only one there is.
     */
    @Test
    void testWorkspaceProjectDeploymentRowsHideSystemProjectsAndHiddenProjects() {
        when(environmentService.getEnvironment(0L)).thenReturn(Environment.DEVELOPMENT);
        when(
            projectDeploymentService.getProjectDeployments(
                false, Environment.DEVELOPMENT, null, null, WORKSPACE_ID))
                    .thenReturn(
                        List.of(
                            projectDeployment(10L, VISIBLE_PROJECT_ID, Environment.DEVELOPMENT),
                            projectDeployment(11L, HIDDEN_PROJECT_ID, Environment.DEVELOPMENT),
                            projectDeployment(12L, SYSTEM_PROJECT_ID, Environment.DEVELOPMENT)));
        when(projectService.getProjects(anyList()))
            .thenReturn(List.of(project(VISIBLE_PROJECT_ID), project(HIDDEN_PROJECT_ID), systemProject()));

        assertThat(projectDeploymentFacade.getWorkspaceProjectDeployments(WORKSPACE_ID, 0L, null, null))
            .extracting(ProjectDeployment::getId)
            .containsExactly(10L);
    }

    @Test
    void testGetProjectDeploymentTags() {
        stubOneTaggedDeploymentPerProject();

        when(tagService.getTags(List.of(20L, 21L))).thenReturn(List.of(new Tag("x"), new Tag("y")));

        List<Tag> tags = projectDeploymentFacade.getProjectDeploymentTags(WORKSPACE_ID);

        assertThat(tags).hasSize(2);
    }

    /**
     * This listing feeds the tag dropdown over {@link ProjectDeploymentFacadeImpl#getWorkspaceProjectDeployments}, and
     * until it shared that method's {@code filterOutSystemProjectDeployments} the two disagreed: the dropdown offered
     * names aggregated off withheld and system projects, each of them selecting nothing in the list beside it.
     *
     * <p>
     * The three deployments carry DIFFERENT tag ids and the assertion is on the ids handed to {@code TagService}, so a
     * facade that stopped filtering asks for the other two and fails here — where asserting the returned tags would
     * only pin what the stub was told to return.
     */
    @Test
    void testProjectDeploymentTagsDropTheTagsOfHiddenAndSystemProjects() {
        stubOneTaggedDeploymentPerProject();

        projectDeploymentFacade.getProjectDeploymentTags(WORKSPACE_ID);

        verify(tagService).getTags(List.of(20L, 21L));
    }

    /**
     * One tagged deployment per project — visible, hidden, system — differing only in which project owns them and which
     * tag ids they carry, so each exclusion above is attributable to exactly one filter.
     */
    private void stubOneTaggedDeploymentPerProject() {
        when(projectDeploymentService.getProjectDeployments(null, null, null, null, WORKSPACE_ID))
            .thenReturn(
                List.of(
                    taggedProjectDeployment(10L, VISIBLE_PROJECT_ID, List.of(20L, 21L)),
                    taggedProjectDeployment(11L, HIDDEN_PROJECT_ID, List.of(22L)),
                    taggedProjectDeployment(12L, SYSTEM_PROJECT_ID, List.of(23L))));
        when(projectService.getProjects(anyList()))
            .thenReturn(List.of(project(VISIBLE_PROJECT_ID), project(HIDDEN_PROJECT_ID), systemProject()));
    }

    private static ProjectDeployment taggedProjectDeployment(long id, long projectId, List<Long> tagIds) {
        ProjectDeployment projectDeployment = projectDeployment(id, projectId, Environment.DEVELOPMENT);

        projectDeployment.setTagIds(tagIds);

        return projectDeployment;
    }

    @Test
    void testValidateInputsAcceptsNonStringValuesForRequiredInputs() {
        Workflow workflow = mock(Workflow.class);

        when(workflow.getInputs()).thenReturn(List.of(
            new Workflow.Input("hourToRun", "Hour", "integer", true),
            new Workflow.Input("minutesToRun", "Minute", "integer", true),
            new Workflow.Input("serviceProviderEmail", "Email", "string", true)));

        Map<String, Object> inputs = Map.of("hourToRun", 11, "minutesToRun", 50, "serviceProviderEmail", "a@b.c");

        assertThatCode(() -> ProjectDeploymentFacadeImpl.validateProjectDeploymentWorkflowInputs(inputs, workflow))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateInputsRejectsMissingBlankAndNullRequiredValues() {
        Workflow workflow = mock(Workflow.class);

        when(workflow.getInputs()).thenReturn(List.of(new Workflow.Input("name", "Name", "string", true)));

        assertThatIllegalArgumentException()
            .isThrownBy(() -> ProjectDeploymentFacadeImpl.validateProjectDeploymentWorkflowInputs(Map.of(), workflow))
            .withMessageContaining("Missing required param: name");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> ProjectDeploymentFacadeImpl.validateProjectDeploymentWorkflowInputs(
                Map.of("name", "   "), workflow))
            .withMessageContaining("Missing required param: name");

        Map<String, Object> nullValueInputs = new HashMap<>();

        nullValueInputs.put("name", null);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> ProjectDeploymentFacadeImpl.validateProjectDeploymentWorkflowInputs(
                nullValueInputs, workflow))
            .withMessageContaining("Missing required param: name");
    }

    @Test
    void testValidateInputsIgnoresAbsentOptionalInputs() {
        Workflow workflow = mock(Workflow.class);

        when(workflow.getInputs()).thenReturn(List.of(
            new Workflow.Input("destinationFolderName", "Folder", "string", false)));

        assertThatCode(() -> ProjectDeploymentFacadeImpl.validateProjectDeploymentWorkflowInputs(Map.of(), workflow))
            .doesNotThrowAnyException();
    }

    /**
     * A system project row can carry no workspace, which the real schema behind {@code ProjectDeploymentFacadeIntTest}
     * does not allow, so that case is pinned here against a mocked {@link ProjectService}. Every other collaborator is
     * null: the refusal must come before anything else is consulted.
     */
    @Test
    void testDeployProjectRefusesASystemProjectWithoutAWorkspaceBeforeAnythingElse() {
        Project project = new Project();

        project.setId(7L);
        project.setName(SystemProjects.EMBEDDED_AUTOMATION_NAME_PREFIX + "catalog");
        project.setWorkspaceId(null);

        when(projectService.getProject(7L)).thenReturn(project);

        ProjectDeploymentFacadeImpl bareProjectDeploymentFacade = new ProjectDeploymentFacadeImpl(
            null, null, null, null, null, null, null, null, null, List.of(), null, null, projectService, null, null,
            null, null, null, null, applicationProperties, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> bareProjectDeploymentFacade.deployProject(7L, null, List.of(), List.of(), Map.of()))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testEnableDisarmsEarlierWorkflowsWhenALaterWorkflowFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        IllegalStateException armingFailure = new IllegalStateException("webhook registration refused");

        doNothing().doThrow(armingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true))
                .isSameAs(armingFailure);

        verify(armingFixture.triggerLifecycleFacade).executeTriggerDisable(
            eq("workflow-1"), argThat(workflowExecutionIdOf(firstWorkflowUuid, "trigger_1")),
            eq(WEBHOOK_TRIGGER_TYPE), eq(Map.of("event", "trigger_1")), isNull());
        verify(armingFixture.triggerLifecycleFacade, times(1))
            .executeTriggerDisable(any(), any(), any(), any(), any());
        verify(armingFixture.projectDeploymentService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testEnableDisarmsEarlierTriggersOfTheSameWorkflowWhenALaterTriggerFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        IllegalStateException armingFailure = new IllegalStateException("schedule registration refused");

        doNothing().doThrow(armingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true))
                .isSameAs(armingFailure);

        verify(armingFixture.triggerLifecycleFacade).executeTriggerDisable(
            eq("workflow-1"), argThat(workflowExecutionIdOf(workflowUuid, "trigger_1")),
            eq(WEBHOOK_TRIGGER_TYPE), eq(Map.of("event", "trigger_1")), isNull());
        verify(armingFixture.triggerLifecycleFacade, times(1))
            .executeTriggerDisable(any(), any(), any(), any(), any());
        verify(armingFixture.projectDeploymentService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testEnableKeepsDisarmingWhenADisarmFailsAndSuppressesItsFailure() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2", "trigger_3");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        IllegalStateException armingFailure = new IllegalStateException("polling registration refused");
        IllegalStateException disarmingFailure = new IllegalStateException("webhook deregistration refused");

        doNothing().doNothing()
            .doThrow(armingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
        doThrow(disarmingFailure).doNothing()
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerDisable(any(), any(), any(), any(), any());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true))
                .isSameAs(armingFailure);

        assertThat(armingFailure.getSuppressed()).containsExactly(disarmingFailure);

        verify(armingFixture.triggerLifecycleFacade).executeTriggerDisable(
            eq("workflow-1"), argThat(workflowExecutionIdOf(workflowUuid, "trigger_2")),
            eq(WEBHOOK_TRIGGER_TYPE), eq(Map.of("event", "trigger_2")), isNull());
        verify(armingFixture.triggerLifecycleFacade).executeTriggerDisable(
            eq("workflow-1"), argThat(workflowExecutionIdOf(workflowUuid, "trigger_1")),
            eq(WEBHOOK_TRIGGER_TYPE), eq(Map.of("event", "trigger_1")), isNull());
        verify(armingFixture.projectDeploymentService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testEnableWorkflowDisarmsEarlierTriggersWhenALaterTriggerFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        ProjectDeploymentWorkflow projectDeploymentWorkflow = armingFixture.projectDeploymentWorkflow("workflow-1");

        projectDeploymentWorkflow.setId(30L);

        when(
            armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1"))
                    .thenReturn(projectDeploymentWorkflow);

        IllegalStateException armingFailure = new IllegalStateException("webhook registration refused");

        doNothing().doThrow(armingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", true))
                    .isSameAs(armingFailure);

        verify(armingFixture.triggerLifecycleFacade).executeTriggerDisable(
            eq("workflow-1"), argThat(workflowExecutionIdOf(workflowUuid, "trigger_1")),
            eq(WEBHOOK_TRIGGER_TYPE), eq(Map.of("event", "trigger_1")), isNull());
        verify(armingFixture.triggerLifecycleFacade, times(1))
            .executeTriggerDisable(any(), any(), any(), any(), any());
        verify(armingFixture.projectDeploymentWorkflowService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testUpdateRestoresTheTriggerRegistrationsWhenALaterWorkflowFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UpgradedWorkflows upgradedWorkflows = armingFixture.stubUpgradedWorkflows();

        IllegalStateException armingFailure = new IllegalStateException("webhook registration refused");

        doNothing().doThrow(armingFailure)
            .doNothing()
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.updateProjectDeployment(
                armingFixture.upgradedProjectDeployment(), upgradedWorkflows.projectDeploymentWorkflows(), List.of()))
                    .isSameAs(armingFailure);

        assertThat(armingFailure.getSuppressed()).isEmpty();

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-2", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testUpdateDisarmsANewlyCreatedWorkflowWhenALaterWorkflowFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = UUID.randomUUID();
        UUID secondWorkflowUuid = UUID.randomUUID();

        List<ProjectWorkflow> projectWorkflows = List.of(
            armingFixture.stubWorkflow("workflow-c", firstWorkflowUuid, 1, "trigger_1"),
            armingFixture.stubWorkflow("workflow-d", secondWorkflowUuid, 1, "trigger_1"));

        when(armingFixture.projectWorkflowService.getProjectWorkflows(VISIBLE_PROJECT_ID)).thenReturn(projectWorkflows);
        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of());

        armingFixture.stubProjectDeploymentWorkflow(20L, "workflow-c");
        armingFixture.stubProjectDeploymentWorkflow(21L, "workflow-d");

        IllegalStateException armingFailure = new IllegalStateException("schedule registration refused");

        doNothing().doThrow(armingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.updateProjectDeployment(
                armingFixture.upgradedProjectDeployment(),
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-c"),
                    armingFixture.projectDeploymentWorkflow("workflow-d")),
                List.of()))
                    .isSameAs(armingFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-c", firstWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-d", secondWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-c", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.projectDeploymentWorkflowService).updateEnabled(20L, true);
        verify(armingFixture.projectDeploymentWorkflowService, never()).updateEnabled(21L, true);
    }

    @Test
    void testUpdateKeepsRestoringWhenAnUndoStepFailsAndSuppressesItsFailure() {
        ArmingFixture armingFixture = new ArmingFixture();

        UpgradedWorkflows upgradedWorkflows = armingFixture.stubUpgradedWorkflows();

        IllegalStateException armingFailure = new IllegalStateException("polling registration refused");
        IllegalStateException undoFailure = new IllegalStateException("webhook deregistration refused");

        doNothing().doThrow(armingFailure)
            .doNothing()
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
        doNothing().doNothing()
            .doThrow(undoFailure)
            .doNothing()
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerDisable(any(), any(), any(), any(), any());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.updateProjectDeployment(
                armingFixture.upgradedProjectDeployment(), upgradedWorkflows.projectDeploymentWorkflows(), List.of()))
                    .isSameAs(armingFailure);

        assertThat(armingFailure.getSuppressed()).containsExactly(undoFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-2", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
    }

    @Test
    void testUpdateMakesNoUndoCallsWhenEveryWorkflowArms() {
        ArmingFixture armingFixture = new ArmingFixture();

        UpgradedWorkflows upgradedWorkflows = armingFixture.stubUpgradedWorkflows();

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        armingProjectDeploymentFacade.updateProjectDeployment(
            armingFixture.upgradedProjectDeployment(), upgradedWorkflows.projectDeploymentWorkflows(), List.of());

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-2", upgradedWorkflows.secondWorkflowUuid());
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testUpdateStopsNoRunningJobsWhenALaterWorkflowFailsToArm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UpgradedWorkflows upgradedWorkflows = armingFixture.stubUpgradedWorkflows();

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        IllegalStateException armingFailure = new IllegalStateException("webhook registration refused");

        doNothing().doThrow(armingFailure)
            .doNothing()
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.updateProjectDeployment(
                armingFixture.upgradedProjectDeployment(), upgradedWorkflows.projectDeploymentWorkflows(), List.of()))
                    .isSameAs(armingFailure);

        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testUpdateStopsTheRunningJobsOfDisabledAndRemovedWorkflowsAfterEveryTriggerChange() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID upgradedWorkflowUuid = UUID.randomUUID();
        UUID removedWorkflowUuid = UUID.randomUUID();

        List<ProjectWorkflow> projectWorkflows = List.of(
            armingFixture.stubWorkflow("workflow-a-1", upgradedWorkflowUuid, 0, "trigger_1"),
            armingFixture.stubWorkflow("workflow-a-2", upgradedWorkflowUuid, 1, "trigger_1"),
            armingFixture.stubWorkflow("workflow-c-1", removedWorkflowUuid, 0, "trigger_1"));

        when(armingFixture.projectWorkflowService.getProjectWorkflows(VISIBLE_PROJECT_ID)).thenReturn(projectWorkflows);
        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow(10L, "workflow-a-1"),
                    armingFixture.projectDeploymentWorkflow(12L, "workflow-c-1")));

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), eq(List.of("workflow-a-1")), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));
        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), eq(List.of("workflow-c-1")), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(102L)));

        armingFixture.stubProjectDeploymentWorkflow(10L, "workflow-a-1");
        armingFixture.stubProjectDeploymentWorkflow(10L, "workflow-a-2");
        armingFixture.stubProjectDeploymentWorkflow(12L, "workflow-c-1");

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        armingProjectDeploymentFacade.updateProjectDeployment(
            armingFixture.upgradedProjectDeployment(), List.of(armingFixture.projectDeploymentWorkflow("workflow-a-2")),
            List.of());

        InOrder inOrder = inOrder(
            armingFixture.triggerLifecycleFacade, armingFixture.principalJobService, armingFixture.jobFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflowUuid);
        verifyRunningJobsQueried(inOrder, armingFixture, "workflow-a-1");
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-c-1", removedWorkflowUuid);
        verifyRunningJobsQueried(inOrder, armingFixture, "workflow-c-1");
        inOrder.verify(armingFixture.jobFacade)
            .stopJob(100L);
        inOrder.verify(armingFixture.jobFacade)
            .stopJob(102L);
        verifyNoMoreInteractions(
            armingFixture.triggerLifecycleFacade, armingFixture.principalJobService, armingFixture.jobFacade);
    }

    @Test
    void testDisableWorkflowReArmsEarlierTriggersWhenALaterTriggerFailsToDisarm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        armingFixture.stubNoRunningJobs();
        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        IllegalStateException disarmingFailure = new IllegalStateException("webhook deregistration refused");

        doNothing().doThrow(disarmingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerDisable(any(), any(), any(), any(), any());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false))
                    .isSameAs(disarmingFailure);

        assertThat(disarmingFailure.getSuppressed()).isEmpty();

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.principalJobService, never())
            .getJobIds(any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt());
        verify(armingFixture.projectDeploymentWorkflowService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testDisableWorkflowStopsItsRunningJobsAfterDisarmingEveryTrigger() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false);

        InOrder inOrder = inOrder(
            armingFixture.triggerLifecycleFacade, armingFixture.principalJobService,
            armingFixture.projectDeploymentWorkflowService, armingFixture.jobFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyRunningJobsQueried(inOrder, armingFixture, "workflow-1");
        inOrder.verify(armingFixture.projectDeploymentWorkflowService)
            .updateEnabled(30L, false);
        inOrder.verify(armingFixture.jobFacade)
            .stopJob(100L);
        verifyNoMoreInteractions(
            armingFixture.triggerLifecycleFacade, armingFixture.principalJobService, armingFixture.jobFacade);
    }

    @Test
    void testDisableReArmsEarlierWorkflowsWhenALaterWorkflowFailsToDisarm() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        IllegalStateException disarmingFailure = new IllegalStateException("schedule deregistration refused");

        doNothing().doThrow(disarmingFailure)
            .when(armingFixture.triggerLifecycleFacade)
            .executeTriggerDisable(any(), any(), any(), any(), any());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, false))
                .isSameAs(disarmingFailure);

        assertThat(disarmingFailure.getSuppressed()).isEmpty();

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.projectDeploymentService, never()).updateEnabled(anyLong(), anyBoolean());
    }

    @Test
    void testUpdateKeepingTheWorkflowIdStopsOnlyTheJobsRunningWhenItWasDisarmed() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = UUID.randomUUID();

        ProjectWorkflow projectWorkflow = armingFixture.stubWorkflow("workflow-1", workflowUuid, 0, "trigger_1");

        when(armingFixture.projectWorkflowService.getProjectWorkflows(VISIBLE_PROJECT_ID))
            .thenReturn(List.of(projectWorkflow));
        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow(10L, "workflow-1")));

        armingFixture.stubProjectDeploymentWorkflow(10L, "workflow-1");

        AtomicBoolean reArmed = new AtomicBoolean();

        doAnswer(invocation -> {
            reArmed.set(true);

            return null;
        }).when(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenAnswer(invocation -> new PageImpl<>(reArmed.get() ? List.of(100L, 101L) : List.of(100L)));

        ProjectDeployment projectDeployment = projectDeployment(
            ARMED_PROJECT_DEPLOYMENT_ID, VISIBLE_PROJECT_ID, Environment.DEVELOPMENT);

        projectDeployment.setEnabled(true);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        armingProjectDeploymentFacade.updateProjectDeployment(
            projectDeployment, List.of(armingFixture.projectDeploymentWorkflow("workflow-1")), List.of());

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade, armingFixture.jobFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        inOrder.verify(armingFixture.jobFacade)
            .stopJob(100L);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade, armingFixture.jobFacade);
    }

    @Test
    void testDisableWorkflowKeepsStoppingItsRunningJobsWhenAStopFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        armingFixture.stubWorkflow("workflow-1", "trigger_1");
        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L, 101L)));
        doThrow(new IllegalStateException("job already finished"))
            .when(armingFixture.jobFacade)
            .stopJob(100L);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatCode(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false))
                    .doesNotThrowAnyException();

        verify(armingFixture.jobFacade).stopJob(101L);
        verify(armingFixture.projectDeploymentWorkflowService).updateEnabled(30L, false);
        verify(armingFixture.triggerLifecycleFacade, times(1))
            .executeTriggerDisable(any(), any(), any(), any(), any());
        verify(armingFixture.triggerLifecycleFacade, never())
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void testUpdateKeepsStoppingRunningJobsWhenAStopFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        armingFixture.stubUpgradedWorkflows();

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), eq(List.of("workflow-a-1")), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));
        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), eq(List.of("workflow-b-1")), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(101L)));
        doThrow(new IllegalStateException("job already finished"))
            .when(armingFixture.jobFacade)
            .stopJob(100L);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatCode(
            () -> armingProjectDeploymentFacade.updateProjectDeployment(
                armingFixture.upgradedProjectDeployment(),
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-a-2"),
                    armingFixture.projectDeploymentWorkflow("workflow-b-2")),
                List.of()))
                    .doesNotThrowAnyException();

        verify(armingFixture.jobFacade).stopJob(101L);
        verify(armingFixture.triggerLifecycleFacade, times(2))
            .executeTriggerDisable(any(), any(), any(), any(), any());
        verify(armingFixture.triggerLifecycleFacade, times(2))
            .executeTriggerEnable(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void testEnableDisarmsTheArmedTriggersWhenUpdateEnabledFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentService)
            .updateEnabled(ARMED_PROJECT_DEPLOYMENT_ID, true);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true))
                .isSameAs(updateFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testDisableReArmsTheDisarmedTriggersWhenUpdateEnabledFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentService)
            .updateEnabled(ARMED_PROJECT_DEPLOYMENT_ID, false);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, false))
                .isSameAs(updateFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testEnableWorkflowDisarmsItsTriggersWhenUpdateEnabledFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentWorkflowService)
            .updateEnabled(30L, true);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", true))
                    .isSameAs(updateFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testDisableWorkflowReArmsItsTriggersAndStopsNoJobWhenUpdateEnabledFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentWorkflowService)
            .updateEnabled(30L, false);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false))
                    .isSameAs(updateFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testDeleteReArmsTheDisarmedTriggersWhenALaterDeleteFails() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow(40L, "workflow-1"),
                    armingFixture.projectDeploymentWorkflow(41L, "workflow-2")));

        IllegalStateException deleteFailure = new IllegalStateException("row delete refused");

        doThrow(deleteFailure).when(armingFixture.projectDeploymentService)
            .delete(ARMED_PROJECT_DEPLOYMENT_ID);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        assertThatThrownBy(() -> armingProjectDeploymentFacade.deleteProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID))
            .isSameAs(deleteFailure);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testEnableInATransactionDisarmsTheArmedTriggersWhenTheTransactionRollsBack() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.platformTransactionManager).getTransaction(
            argThat(transactionDefinition -> transactionDefinition
                .getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testDisableInATransactionReArmsTheDisarmedTriggersWhenTheTransactionRollsBack() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");
        UUID secondWorkflowUuid = armingFixture.stubWorkflow("workflow-2", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(
                List.of(
                    armingFixture.projectDeploymentWorkflow("workflow-1"),
                    armingFixture.projectDeploymentWorkflow("workflow-2")));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, false);

        verify(armingFixture.jobFacade, never()).stopJob(anyLong());

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-2", secondWorkflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testDisableWorkflowInATransactionStopsItsRunningJobsOnlyAfterCommit() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1", "trigger_2");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false);

        verify(armingFixture.jobFacade, never()).stopJob(anyLong());

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCommit();
        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(armingFixture.jobFacade).stopJob(100L);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_1");
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid, "trigger_2");
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade, armingFixture.jobFacade);
        verifyNoMoreInteractions(armingFixture.platformTransactionManager);
    }

    @Test
    void testUpdateInATransactionRestoresTheTriggerRegistrationsWhenTheTransactionRollsBack() {
        ArmingFixture armingFixture = new ArmingFixture();

        UpgradedWorkflows upgradedWorkflows = armingFixture.stubUpgradedWorkflows();

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.updateProjectDeployment(
            armingFixture.upgradedProjectDeployment(), upgradedWorkflows.projectDeploymentWorkflows(), List.of());

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-2", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-b-2", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-b-1", upgradedWorkflows.secondWorkflowUuid());
        verifyTriggerDisable(inOrder, armingFixture, "workflow-a-2", upgradedWorkflows.firstWorkflowUuid());
        verifyTriggerEnable(inOrder, armingFixture, "workflow-a-1", upgradedWorkflows.firstWorkflowUuid());
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testUnknownTransactionOutcomeNeitherUndoesTheTriggerChangesNorStopsJobs() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false);

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade, armingFixture.platformTransactionManager);
        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testFailedEnableInATransactionLeavesTheUndoToTheRollback() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID firstWorkflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentService)
            .updateEnabled(ARMED_PROJECT_DEPLOYMENT_ID, true);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true))
                .isSameAs(updateFailure);

        verify(armingFixture.triggerLifecycleFacade, never()).executeTriggerDisable(any(), any(), any(), any(), any());

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(1);

        TransactionSynchronization transactionSynchronization = transactionSynchronizations.getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", firstWorkflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
        verify(armingFixture.platformTransactionManager).getTransaction(
            argThat(transactionDefinition -> transactionDefinition
                .getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void testFailedDisableWorkflowInATransactionStopsNoJobsWhenTheTransactionCommits() {
        ArmingFixture armingFixture = new ArmingFixture();

        armingFixture.stubWorkflow("workflow-1", "trigger_1");
        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        IllegalStateException updateFailure = new IllegalStateException("row update refused");

        doThrow(updateFailure).when(armingFixture.projectDeploymentWorkflowService)
            .updateEnabled(30L, false);

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(
            () -> armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(
                ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false))
                    .isSameAs(updateFailure);

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        transactionSynchronization.afterCommit();
        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(armingFixture.jobFacade, never()).stopJob(anyLong());
    }

    @Test
    void testRollbackUndoOfAnArmedTriggerRestoresTheTriggerStateCapturedWhenItWasArmed() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        WebhookEnableOutput armedWebhookEnableOutput = new WebhookEnableOutput(
            Map.of("id", "subscription-armed-in-the-rolled-back-transaction"), null);
        WebhookEnableOutput staleWebhookEnableOutput = new WebhookEnableOutput(
            Map.of("id", "subscription-already-removed"), null);

        when(armingFixture.triggerStateService.fetchValue(argThat(workflowExecutionIdOf(workflowUuid, "trigger_1"))))
            .thenReturn(Optional.of(armedWebhookEnableOutput), Optional.of(staleWebhookEnableOutput));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        verify(armingFixture.triggerStateService, never()).save(any(), any());

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade, armingFixture.triggerStateService);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        inOrder.verify(armingFixture.triggerStateService)
            .save(argThat(workflowExecutionIdOf(workflowUuid, "trigger_1")), eq(armedWebhookEnableOutput));
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testRollbackUndoOfAnArmedTriggerWritesNoTriggerStateWhenTheStoredOneAlreadyMatches() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        WebhookEnableOutput armedWebhookEnableOutput = new WebhookEnableOutput(Map.of("id", "subscription-1"), null);

        when(armingFixture.triggerStateService.fetchValue(argThat(workflowExecutionIdOf(workflowUuid, "trigger_1"))))
            .thenReturn(
                Optional.of(armedWebhookEnableOutput),
                Optional.of(new WebhookEnableOutput(Map.of("id", "subscription-1"), null)));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verify(armingFixture.triggerStateService, never()).save(any(), any());
    }

    @Test
    void testRollbackUndoRestoresOnlyWebhookTriggerState() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        Map<String, Object> pollingTriggerState = Map.of("lastPolledId", 7);

        when(armingFixture.triggerStateService.fetchValue(argThat(workflowExecutionIdOf(workflowUuid, "trigger_1"))))
            .thenReturn(Optional.of(pollingTriggerState));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verify(armingFixture.triggerStateService).fetchValue(any());
        verify(armingFixture.triggerStateService, never()).save(any(), any());
    }

    @Test
    void testRollbackUndoStillDisarmsATriggerWhoseStateCannotBeRestored() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));

        WebhookEnableOutput armedWebhookEnableOutput = new WebhookEnableOutput(Map.of("id", "subscription-1"), null);

        when(armingFixture.triggerStateService.fetchValue(argThat(workflowExecutionIdOf(workflowUuid, "trigger_1"))))
            .thenReturn(Optional.of(armedWebhookEnableOutput))
            .thenThrow(new IllegalStateException("trigger state store unavailable"));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verify(armingFixture.triggerStateService, never()).save(any(), any());
    }

    @Test
    void testChangeThatJournalsNothingRegistersNoSynchronization() {
        ArmingFixture armingFixture = new ArmingFixture();

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of());

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void testTwoChangesInOneTransactionAreUndoneLatestFirstWhenTheTransactionRollsBack() {
        ArmingFixture armingFixture = new ArmingFixture();

        UUID workflowUuid = armingFixture.stubWorkflow("workflow-1", "trigger_1");

        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");
        armingFixture.stubNoRunningJobs();

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false);
        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", true);

        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        assertThat(transactionSynchronizations).hasSize(2);

        TransactionSynchronizationUtils.invokeAfterCompletion(
            transactionSynchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);

        InOrder inOrder = inOrder(armingFixture.triggerLifecycleFacade);

        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerDisable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyTriggerEnable(inOrder, armingFixture, "workflow-1", workflowUuid);
        verifyNoMoreInteractions(armingFixture.triggerLifecycleFacade);
    }

    @Test
    void testRollbackUndoWhoseTransactionFailsIsLoggedAndNotThrown() {
        ArmingFixture armingFixture = new ArmingFixture();

        armingFixture.stubWorkflow("workflow-1", "trigger_1");

        when(armingFixture.projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
            .thenReturn(List.of(armingFixture.projectDeploymentWorkflow("workflow-1")));
        when(armingFixture.platformTransactionManager.getTransaction(any()))
            .thenThrow(new IllegalStateException("no connection"));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        TransactionSynchronizationManager.initSynchronization();

        armingProjectDeploymentFacade.enableProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID, true);

        TransactionSynchronization transactionSynchronization = TransactionSynchronizationManager.getSynchronizations()
            .getFirst();

        assertThatCode(
            () -> transactionSynchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                .doesNotThrowAnyException();
    }

    @Test
    void testDisableWorkflowWithoutATransactionStopsItsRunningJobsAtOnce() {
        ArmingFixture armingFixture = new ArmingFixture();

        armingFixture.stubWorkflow("workflow-1", "trigger_1");
        armingFixture.stubProjectDeploymentWorkflow(30L, "workflow-1");

        when(
            armingFixture.principalJobService.getJobIds(
                any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(100L)));

        ProjectDeploymentFacadeImpl armingProjectDeploymentFacade = armingFixture.projectDeploymentFacade();

        armingProjectDeploymentFacade.enableProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, "workflow-1", false);

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        verify(armingFixture.jobFacade).stopJob(100L);
        verifyNoMoreInteractions(armingFixture.platformTransactionManager);
    }

    private static void verifyRunningJobsQueried(InOrder inOrder, ArmingFixture armingFixture, String workflowId) {
        inOrder.verify(armingFixture.principalJobService)
            .getJobIds(
                eq(Job.Status.STARTED), isNull(), isNull(), eq(List.of(ARMED_PROJECT_DEPLOYMENT_ID)),
                eq(PlatformType.AUTOMATION), eq(List.of(workflowId)), eq(false), eq(0));
    }

    private static void verifyTriggerDisable(
        InOrder inOrder, ArmingFixture armingFixture, String workflowId, UUID workflowUuid) {

        verifyTriggerDisable(inOrder, armingFixture, workflowId, workflowUuid, "trigger_1");
    }

    private static void verifyTriggerDisable(
        InOrder inOrder, ArmingFixture armingFixture, String workflowId, UUID workflowUuid, String triggerName) {

        inOrder.verify(armingFixture.triggerLifecycleFacade)
            .executeTriggerDisable(
                eq(workflowId), argThat(workflowExecutionIdOf(workflowUuid, triggerName)), eq(WEBHOOK_TRIGGER_TYPE),
                eq(Map.of("event", triggerName)), isNull());
    }

    private static void verifyTriggerEnable(
        InOrder inOrder, ArmingFixture armingFixture, String workflowId, UUID workflowUuid) {

        verifyTriggerEnable(inOrder, armingFixture, workflowId, workflowUuid, "trigger_1");
    }

    private static void verifyTriggerEnable(
        InOrder inOrder, ArmingFixture armingFixture, String workflowId, UUID workflowUuid, String triggerName) {

        String webhookUrl = "http://localhost/webhooks/" + WorkflowExecutionId.of(
            PlatformType.AUTOMATION, ARMED_PROJECT_DEPLOYMENT_ID, workflowUuid.toString(), triggerName);

        inOrder.verify(armingFixture.triggerLifecycleFacade)
            .executeTriggerEnable(
                eq(workflowId), argThat(workflowExecutionIdOf(workflowUuid, triggerName)), eq(WEBHOOK_TRIGGER_TYPE),
                eq(Map.of("event", triggerName)), isNull(), eq(webhookUrl),
                eq((long) Environment.DEVELOPMENT.ordinal()));
    }

    private record UpgradedWorkflows(
        UUID firstWorkflowUuid, UUID secondWorkflowUuid, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows) {
    }

    private static ArgumentMatcher<WorkflowExecutionId> workflowExecutionIdOf(
        UUID workflowUuid, String triggerName) {

        String expectedWorkflowExecutionId = String.valueOf(
            WorkflowExecutionId.of(
                PlatformType.AUTOMATION, ARMED_PROJECT_DEPLOYMENT_ID, workflowUuid.toString(), triggerName));

        return workflowExecutionId -> workflowExecutionId != null
            && expectedWorkflowExecutionId.equals(workflowExecutionId.toString());
    }

    private final class ArmingFixture {

        private final ComponentConnectionFacade componentConnectionFacade = mock(ComponentConnectionFacade.class);
        private final Evaluator evaluator = mock(Evaluator.class);
        private final JobFacade jobFacade = mock(JobFacade.class);
        private final PlatformTransactionManager platformTransactionManager = mock(PlatformTransactionManager.class);
        private final PrincipalJobService principalJobService = mock(PrincipalJobService.class);
        private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
        private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService =
            mock(ProjectDeploymentWorkflowService.class);
        private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
        private final TriggerLifecycleFacade triggerLifecycleFacade = mock(TriggerLifecycleFacade.class);
        private final TriggerStateService triggerStateService = mock(TriggerStateService.class);
        private final WorkflowService workflowService = mock(WorkflowService.class);

        private ArmingFixture() {
            ProjectDeployment projectDeployment = projectDeployment(
                ARMED_PROJECT_DEPLOYMENT_ID, VISIBLE_PROJECT_ID, Environment.DEVELOPMENT);

            projectDeployment.setEnabled(true);

            lenient()
                .when(projectDeploymentService.getProjectDeployment(ARMED_PROJECT_DEPLOYMENT_ID))
                .thenReturn(projectDeployment);
            lenient()
                .when(evaluator.evaluate(anyMap(), anyMap(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private ProjectDeploymentFacadeImpl projectDeploymentFacade() {
            when(applicationProperties.getWebhookUrl()).thenReturn("http://localhost/webhooks/{id}");

            return new ProjectDeploymentFacadeImpl(
                null, null, evaluator, null, null, null, principalJobService, jobFacade, null, List.of(),
                projectDeploymentService,
                projectDeploymentWorkflowService, null, null, projectWorkflowService, null, null, null,
                triggerLifecycleFacade, applicationProperties, componentConnectionFacade, workflowService, null, null,
                null, platformTransactionManager, triggerStateService);
        }

        private ProjectDeploymentWorkflow projectDeploymentWorkflow(String workflowId) {
            ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

            projectDeploymentWorkflow.setEnabled(true);
            projectDeploymentWorkflow.setInputs(Map.of());
            projectDeploymentWorkflow.setProjectDeploymentId(ARMED_PROJECT_DEPLOYMENT_ID);
            projectDeploymentWorkflow.setWorkflowId(workflowId);

            return projectDeploymentWorkflow;
        }

        private ProjectDeployment upgradedProjectDeployment() {
            ProjectDeployment projectDeployment = projectDeployment(
                ARMED_PROJECT_DEPLOYMENT_ID, VISIBLE_PROJECT_ID, Environment.DEVELOPMENT);

            projectDeployment.setEnabled(true);
            projectDeployment.setProjectVersion(1);

            return projectDeployment;
        }

        private UpgradedWorkflows stubUpgradedWorkflows() {
            UUID firstWorkflowUuid = UUID.randomUUID();
            UUID secondWorkflowUuid = UUID.randomUUID();

            List<ProjectWorkflow> projectWorkflows = List.of(
                stubWorkflow("workflow-a-1", firstWorkflowUuid, 0, "trigger_1"),
                stubWorkflow("workflow-a-2", firstWorkflowUuid, 1, "trigger_1"),
                stubWorkflow("workflow-b-1", secondWorkflowUuid, 0, "trigger_1"),
                stubWorkflow("workflow-b-2", secondWorkflowUuid, 1, "trigger_1"));

            when(projectWorkflowService.getProjectWorkflows(VISIBLE_PROJECT_ID)).thenReturn(projectWorkflows);
            when(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(ARMED_PROJECT_DEPLOYMENT_ID))
                .thenReturn(
                    List.of(
                        projectDeploymentWorkflow(10L, "workflow-a-1"),
                        projectDeploymentWorkflow(11L, "workflow-b-1")));
            stubNoRunningJobs();

            stubProjectDeploymentWorkflow(10L, "workflow-a-1");
            stubProjectDeploymentWorkflow(10L, "workflow-a-2");
            stubProjectDeploymentWorkflow(11L, "workflow-b-1");
            stubProjectDeploymentWorkflow(11L, "workflow-b-2");

            return new UpgradedWorkflows(
                firstWorkflowUuid, secondWorkflowUuid,
                List.of(projectDeploymentWorkflow("workflow-a-2"), projectDeploymentWorkflow("workflow-b-2")));
        }

        private void stubNoRunningJobs() {
            lenient()
                .when(
                    principalJobService.getJobIds(
                        any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                .thenReturn(Page.empty());
        }

        private void stubProjectDeploymentWorkflow(long id, String workflowId) {
            when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(ARMED_PROJECT_DEPLOYMENT_ID, workflowId))
                .thenReturn(projectDeploymentWorkflow(id, workflowId));
        }

        private ProjectDeploymentWorkflow projectDeploymentWorkflow(long id, String workflowId) {
            ProjectDeploymentWorkflow projectDeploymentWorkflow = projectDeploymentWorkflow(workflowId);

            projectDeploymentWorkflow.setId(id);

            return projectDeploymentWorkflow;
        }

        private UUID stubWorkflow(String workflowId, String... triggerNames) {
            UUID workflowUuid = UUID.randomUUID();

            stubWorkflow(workflowId, workflowUuid, 1, triggerNames);

            return workflowUuid;
        }

        private ProjectWorkflow stubWorkflow(
            String workflowId, UUID workflowUuid, int projectVersion, String... triggerNames) {

            Workflow workflow = mock(Workflow.class);

            List<WorkflowTrigger> workflowTriggers = Arrays.stream(triggerNames)
                .map(triggerName -> new WorkflowTrigger(
                    Map.of(
                        "name", triggerName, "type", "webhook/v1/newEvent", "parameters",
                        Map.of("event", triggerName))))
                .toList();

            when(workflow.getId()).thenReturn(workflowId);
            when(workflow.getExtensions(WorkflowExtConstants.TRIGGERS, WorkflowTrigger.class, List.of()))
                .thenReturn(workflowTriggers);
            when(workflowService.getWorkflow(workflowId)).thenReturn(workflow);

            ProjectWorkflow projectWorkflow = new ProjectWorkflow(
                VISIBLE_PROJECT_ID, projectVersion, workflowId, workflowUuid);

            when(projectWorkflowService.getWorkflowProjectWorkflow(workflowId)).thenReturn(projectWorkflow);

            return projectWorkflow;
        }
    }

    /**
     * Generic since the environment filter needs one over {@link PermissionService} as well as the visibility filter's
     * over {@code ResourceVisibilityResolver}; the body was already type-agnostic.
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> objectProvider(T instance) {
        ObjectProvider<T> objectProvider = mock(ObjectProvider.class);

        lenient()
            .when(objectProvider.getIfAvailable())
            .thenReturn(instance);

        return objectProvider;
    }

    private static Project systemProject() {
        Project systemProject = project(SYSTEM_PROJECT_ID);

        systemProject.setName(SystemProjects.KNOWLEDGE_BASE_NAME_PREFIX + "1");

        return systemProject;
    }

    private static Project project(long id) {
        Project project = new Project();

        project.setId(id);
        project.setName("project-" + id);
        project.setVisibility(ResourceVisibility.WORKSPACE);

        return project;
    }

    private static ProjectDeployment projectDeployment(long id, long projectId, Environment environment) {
        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(id);
        projectDeployment.setEnvironment(environment);
        projectDeployment.setProjectId(projectId);

        return projectDeployment;
    }

    /**
     * A {@link PermissionService} that answers "every environment" -- the ordinary case, since a member in implicit
     * mode holds their scopes in all of them.
     */
    private static PermissionService permissionServiceHoldingEveryEnvironment() {
        PermissionService permissionService = mock(PermissionService.class);

        lenient()
            .when(permissionService.getMyWorkspaceScopeEnvironments(anyLong(), anyString()))
            .thenReturn(EnumSet.allOf(Environment.class));

        return permissionService;
    }

}
