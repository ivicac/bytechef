/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.facade.WorkspaceConnectionFacade;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.ee.embedded.codeworkflowbridge.AutomationCodeWorkflowBridgeIntTestConfiguration;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectCodeWorkflowFacade;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserConnectionFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceRolloutManager;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.listener.AutomationWorkflowProjectPublishedEventListener;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.configuration.security.EmbeddedPermissionEvaluator;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserProjectService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer.Language;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.facade.ActionDefinitionFacade;
import com.bytechef.platform.component.facade.TriggerDefinitionFacade;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.component.service.ConnectionDefinitionService;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.cache.WorkflowCacheManager;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.facade.OAuth2ParametersFacade;
import com.bytechef.platform.configuration.facade.WorkflowNodeParameterFacade;
import com.bytechef.platform.configuration.facade.WorkflowTestConfigurationFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.configuration.service.WorkflowNodeTestOutputService;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.githubproxy.client.GitHubProxyClient;
import com.bytechef.platform.mcp.service.McpComponentService;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.mcp.service.McpToolService;
import com.bytechef.platform.oauth2.service.OAuth2Service;
import com.bytechef.platform.security.facade.ApiKeyFacade;
import com.bytechef.platform.security.service.ApiKeyService;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.ConnectionLifecycleFacade;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end integration test for the embedded automation code-workflow bridge, exercising Tasks 1-6 together against a
 * real Postgres (Testcontainers): deploy an artifact through {@link AutomationWorkflowProjectCodeWorkflowFacade}, wire
 * connected-user references through {@link ConnectedUserWorkflowReferenceFacade}, and confirm the seams that only show
 * up when both meet -- uuid carry-forward across a redeploy, the dangling sweep it triggers, automation
 * workflow-project isolation, connection auto-wiring, and per-environment deployment isolation.
 *
 * <p>
 * Mock boundary: {@link ComponentConnectionFacade}, {@link ConnectionService}, {@link ConnectedUserConnectionFacade},
 * {@link EnvironmentService}, and the job/trigger/MCP/API-key/OAuth2 collaborators of the real
 * {@code ProjectDeploymentFacadeImpl} are mocked -- they are execution-time concerns (trigger enable/disable, job
 * dispatch) that the deploy/redeploy/dangling/reference seam under test never touches (our fixture workflows declare no
 * triggers). {@link ComponentConnectionFacade} drives which slots {@code ConnectedUserWorkflowConnectionResolver} (via
 * {@code WorkflowConnectionSlots}) sees for the fixture's single task, and {@link ConnectedUserConnectionFacade} drives
 * which connections the connected user is entitled to for that slot's component -- together they are what let the
 * connection round-trip test (priority 3) and the D6 cross-user test exercise {@link MissingConnectionException}
 * without a real component registry. {@link ComponentDefinitionService} is mocked mainly because other beans in this
 * Spring context need one to exist -- the current resolver never consults it -- but the connection round-trip and D6
 * tests still override it to {@code null} so the SAME fixture also exercises the pre-fix resolver's "unknown component,
 * might require a connection" branch when its production files are temporarily reverted to prove a regression.
 * Everything else -- {@code ProjectService}/{@code ProjectWorkflowService}/{@code ProjectDeploymentService}/
 * {@code ProjectDeploymentWorkflowService}, {@code ProjectCodeWorkflowService}, {@code CodeWorkflowContainerService}/
 * {@code CodeWorkflowContainerFacade}, {@code ConnectedUserService}, and the whole embedded-configuration facade layer
 * -- is real, backed by the Testcontainers Postgres instance. See
 * {@link AutomationCodeWorkflowBridgeIntTestConfiguration} for why its Spring config lives outside this package.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = AutomationCodeWorkflowBridgeIntTestConfiguration.class,
    properties = {
        "bytechef.edition=EE",
        "bytechef.workflow.repository.jdbc.enabled=true",
        "bytechef.webhook-url=/webhooks/{id}",
        "spring.liquibase.contexts=configuration,user",
        "spring.main.allow-bean-definition-overriding=true"
    })
@Import(PostgreSQLContainerConfiguration.class)
@MockitoBean(types = {
    ActionDefinitionFacade.class, ApiKeyFacade.class, ApiKeyService.class, AuthorityService.class,
    ClusterElementDefinitionService.class, ComponentConnectionFacade.class, ComponentDefinitionService.class,
    ConnectionDefinitionService.class, ConnectionFacade.class, ConnectionLifecycleFacade.class,
    ConnectionService.class, EmbeddedPermissionEvaluator.class, EnvironmentService.class, GitHubProxyClient.class,
    JobFacade.class, JobService.class, McpComponentService.class, McpIntegrationInstanceConfigurationService.class,
    McpIntegrationInstanceConfigurationWorkflowService.class, McpIntegrationInstanceToolService.class,
    McpServerService.class, McpToolService.class, OAuth2ParametersFacade.class, OAuth2Service.class,
    PrincipalJobFacade.class, PrincipalJobService.class, ProjectFacade.class,
    TaskDispatcherDefinitionService.class, TaskExecutionService.class,
    TriggerDefinitionFacade.class, TriggerDefinitionService.class, TriggerExecutionService.class,
    TriggerLifecycleFacade.class, TriggerStateService.class, UserService.class, WorkflowCacheManager.class,
    WorkflowNodeParameterFacade.class,
    WorkflowNodeTestOutputService.class, WorkflowTestConfigurationFacade.class, WorkflowTestConfigurationService.class,
    WorkspaceConnectionFacade.class, WorkspaceFacade.class
})
class AutomationCodeWorkflowBridgeIntTest {

    private static final String CODE_WORKFLOW_COMPONENT_NAME = "codeWorkflow";

    @Autowired
    private AutomationWorkflowProjectCodeWorkflowFacade automationWorkflowProjectCodeWorkflowFacade;

    @Autowired
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    // Publishing would otherwise run the reference rollout from the after-commit listener; rollout has its own tests.
    @MockitoBean
    private AutomationWorkflowProjectPublishedEventListener automationWorkflowProjectPublishedEventListener;

    @Autowired
    private ComponentConnectionFacade componentConnectionFacade;

    @Autowired
    private ConnectedUserWorkflowReferenceFacade connectedUserWorkflowReferenceFacade;

    @MockitoBean
    private ConnectedUserConnectionFacade connectedUserConnectionFacade;

    @Autowired
    private ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;

    @Autowired
    private ConnectedUserReferenceRolloutManager connectedUserReferenceRolloutManager;

    @Autowired
    private ConnectedUserProjectService connectedUserProjectService;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Autowired
    private ComponentDefinitionService componentDefinitionService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private EmbeddedPermissionEvaluator embeddedPermissionEvaluator;

    @Autowired
    private PrincipalJobService principalJobService;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @Autowired
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        // ComponentDefinitionService is not consulted by the current resolver; this default keeps it harmless for
        // any other bean that might call it, and priorities 1, 2 and 4 never touch connection wiring at all (the
        // mocked ComponentConnectionFacade returns an empty slot list by default). The connection round-trip test
        // (priority 3) and the D6 cross-user test both override this to null purely so a temporary revert of the
        // resolver/facade production files back to the pre-fix behavior can be exercised against the SAME fixture --
        // see their Javadoc.
        when(componentDefinitionService.getComponentDefinition(anyString(), anyInt()))
            .thenReturn(new ComponentDefinition(CODE_WORKFLOW_COMPONENT_NAME));
        when(connectionService.getConnections(PlatformType.EMBEDDED))
            .thenReturn(List.of());

        // getOrCreateReference validates the automation workflows uuid against the permission-FILTERED automation
        // workflow, which consults this
        // (mocked) evaluator; the default mock answer of false would hide every template from every connected user.
        when(embeddedPermissionEvaluator.evaluate(any(), any()))
            .thenReturn(true);
    }

    /**
     * Priority 1: deploy v1 (workflows A+B) -> a user provisions a reference to A -> a second user provisions a
     * reference to B -> redeploy v2 (A unchanged, B removed, C added). A's reference must survive with the SAME
     * automation_workflow_uuid, B's reference must be flagged dangling by the rollout that follows the publish, and C
     * must be listed in the new published version.
     */
    @Test
    void testRedeployCarriesUuidForwardAndMarksRemovedWorkflowDangling() {
        connectedUserService.createConnectedUser("userLifecycleA", Environment.PRODUCTION);
        connectedUserService.createConnectedUser("userLifecycleB", Environment.PRODUCTION);

        givenNoRunningJobs();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("lifecycle-project", "workflowA", "workflowB"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("lifecycle-project")
            .orElseThrow();

        int publishedV1 = publishedVersion(projectId);

        ProjectWorkflow workflowARowV1 = findProjectWorkflowByLabel(projectId, publishedV1, "workflowA");
        ProjectWorkflow workflowBRowV1 = findProjectWorkflowByLabel(projectId, publishedV1, "workflowB");

        String workflowAUuid = workflowARowV1.getUuidAsString();
        String workflowBUuid = workflowBRowV1.getUuidAsString();

        ConnectedUserProjectWorkflow referenceA = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userLifecycleA", workflowAUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow referenceB = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userLifecycleB", workflowBUuid, Environment.PRODUCTION);

        assertThat(referenceA.isDangling()).isFalse();
        assertThat(referenceB.isDangling()).isFalse();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("lifecycle-project", "workflowA", "workflowC"), Language.JAVASCRIPT);

        connectedUserReferenceRolloutManager.rollOut(projectId);

        int publishedV2 = publishedVersion(projectId);

        ProjectWorkflow workflowARowV2 = findProjectWorkflowByLabel(projectId, publishedV2, "workflowA");
        ProjectWorkflow workflowCRowV2 = findProjectWorkflowByLabel(projectId, publishedV2, "workflowC");

        assertThat(workflowARowV2.getUuidAsString())
            .as("uuid carry-forward: workflowA must keep the same uuid across the redeploy")
            .isEqualTo(workflowAUuid);
        assertThat(workflowCRowV2)
            .as("workflowC must be listable in the redeployed automation workflow project")
            .isNotNull();

        ConnectedUserProjectWorkflow reloadedReferenceA = connectedUserProjectWorkflowRepository
            .findById(referenceA.getId())
            .orElseThrow();
        ConnectedUserProjectWorkflow reloadedReferenceB = connectedUserProjectWorkflowRepository
            .findById(referenceB.getId())
            .orElseThrow();

        assertThat(reloadedReferenceA.isDangling())
            .as("A's reference must survive the redeploy since its uuid was carried forward")
            .isFalse();
        assertThat(reloadedReferenceB.isDangling())
            .as("B's reference must be flagged dangling since workflowB was removed from the automation workflows")
            .isTrue();
        assertThat(reloadedReferenceB.getDanglingReason())
            .isEqualTo("Removed from the automation workflow project on publish");
    }

    /**
     * Priority 2: a second automation workflow project's references must be untouched by the first project's redeploy
     * -- the dangling sweep is scoped per automation workflow project, never a repository-wide scan.
     */
    @Test
    void testRedeployOfOneAutomationWorkflowProjectNeverDanglesAnotherAutomationWorkflowProjectsReferences() {
        connectedUserService.createConnectedUser("userCrossA", Environment.PRODUCTION);
        connectedUserService.createConnectedUser("userCrossB", Environment.PRODUCTION);

        givenNoRunningJobs();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("cross-project-a", "onlyWorkflow"), Language.JAVASCRIPT);
        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("cross-project-b", "onlyWorkflow"), Language.JAVASCRIPT);

        long projectAId = automationWorkflowProjectFacade.fetchProjectIdByName("cross-project-a")
            .orElseThrow();
        long projectBId = automationWorkflowProjectFacade.fetchProjectIdByName("cross-project-b")
            .orElseThrow();

        String workflowAUuid = findProjectWorkflowByLabel(projectAId, publishedVersion(projectAId), "onlyWorkflow")
            .getUuidAsString();
        String workflowBUuid = findProjectWorkflowByLabel(projectBId, publishedVersion(projectBId), "onlyWorkflow")
            .getUuidAsString();

        ConnectedUserProjectWorkflow referenceToA = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userCrossA", workflowAUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow referenceToB = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userCrossB", workflowBUuid, Environment.PRODUCTION);

        // Redeploy project A only, with its workflow renamed away -- this dangles referenceToA, but must never touch
        // referenceToB, which belongs to an entirely different automation workflow project.
        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("cross-project-a", "renamedWorkflow"), Language.JAVASCRIPT);

        connectedUserReferenceRolloutManager.rollOut(projectAId);

        ConnectedUserProjectWorkflow reloadedReferenceToA = connectedUserProjectWorkflowRepository
            .findById(referenceToA.getId())
            .orElseThrow();
        ConnectedUserProjectWorkflow reloadedReferenceToB = connectedUserProjectWorkflowRepository
            .findById(referenceToB.getId())
            .orElseThrow();

        assertThat(reloadedReferenceToA.isDangling()).isTrue();
        assertThat(reloadedReferenceToB.isDangling())
            .as("cross-project-b's reference must be untouched by cross-project-a's redeploy")
            .isFalse();
    }

    /**
     * Priority 3: provisioning a reference to a workflow whose component has no matching connection leaves the
     * reference disabled and rethrows {@link MissingConnectionException} naming the component. Once the connected user
     * creates the matching connection, enabling the reference re-resolves it, wires it into the real
     * {@link ProjectDeploymentWorkflow} connections, and succeeds -- keyed by the platform connection key (the
     * fixture's single slot's {@code componentConnectionFacade}-declared key, {@code "codeWorkflow"}), not by the
     * workflow node name ({@code "task1"}). The fixture forces the pre-fix resolver's "unknown component, might require
     * a connection" branch too (via the {@code componentDefinitionService} override below) and stubs a real
     * {@code Connection} through {@code connectionService} in addition to the {@code connectedUserConnectionFacade}
     * stub, so this same test body can be run unmodified against a temporarily reverted resolver/facade to prove the D7
     * key-shape regression: the pre-fix code resolves successfully (using {@code connectionService}) but stamps the
     * node name into the key, so the final {@code containsExactly} assertion fails there.
     */
    @Test
    void testProvisionThenEnableRoundTripWithAConnection() {
        // Forces the pre-fix resolver's "unknown component, might require a connection" branch -- see the class
        // Javadoc and this method's Javadoc for why this fixture must also be wireable by the reverted code.
        when(componentDefinitionService.getComponentDefinition(anyString(), anyInt()))
            .thenReturn(null);

        connectedUserService.createConnectedUser("userConnection", Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("connection-project", "connectedWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("connection-project")
            .orElseThrow();
        String workflowUuid = findProjectWorkflowByLabel(projectId, publishedVersion(projectId), "connectedWorkflow")
            .getUuidAsString();

        when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class)))
            .thenReturn(List.of(
                new ComponentConnection(CODE_WORKFLOW_COMPONENT_NAME, 1, "task1", CODE_WORKFLOW_COMPONENT_NAME, true)));
        when(connectedUserConnectionFacade.getConnections(anyLong(), eq(CODE_WORKFLOW_COMPONENT_NAME), eq(List.of())))
            .thenReturn(List.of());

        assertThatThrownBy(
            () -> connectedUserWorkflowReferenceFacade.getOrCreateReference(
                "userConnection", workflowUuid, Environment.PRODUCTION))
                    .isInstanceOf(MissingConnectionException.class)
                    .satisfies(exception -> assertThat(((MissingConnectionException) exception).getComponentName())
                        .isEqualTo(CODE_WORKFLOW_COMPONENT_NAME));

        ConnectedUserProjectWorkflow disabledReference = connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndAutomationWorkflowUuid(
                connectedUserProjectId("userConnection", Environment.PRODUCTION), workflowUuid)
            .orElseThrow();

        assertThat(disabledReference.isEnabled()).isFalse();

        // The connected user creates the missing connection. Both connectionService (consulted by the pre-fix
        // resolver) and connectedUserConnectionFacade (consulted by the current one) are stubbed with the SAME
        // connection id, so this fixture resolves successfully under either implementation.
        Connection connection = new Connection();

        connection.setId(777L);
        connection.setComponentName(CODE_WORKFLOW_COMPONENT_NAME);
        connection.setEnvironmentId(Environment.PRODUCTION.ordinal());

        when(connectionService.getConnections(PlatformType.EMBEDDED))
            .thenReturn(List.of(connection));

        // Enabling writes the row through ProjectDeploymentFacadeImpl, which validates an enabled row's connection
        // against its declared slot (component name) and the deployment's environment.
        when(connectionService.getConnection(777L))
            .thenReturn(connection);
        when(componentConnectionFacade.getComponentConnection(anyString(), eq("task1"),
            eq(CODE_WORKFLOW_COMPONENT_NAME)))
                .thenReturn(
                    new ComponentConnection(CODE_WORKFLOW_COMPONENT_NAME, 1, "task1", CODE_WORKFLOW_COMPONENT_NAME,
                        true));

        ConnectionDTO connectionDTO = connectionDTO(777L, CODE_WORKFLOW_COMPONENT_NAME);

        when(connectedUserConnectionFacade.getConnections(anyLong(), eq(CODE_WORKFLOW_COMPONENT_NAME), eq(List.of())))
            .thenReturn(List.of(connectionDTO));

        connectedUserWorkflowReferenceFacade.enableReference(
            "userConnection", workflowUuid, true, Environment.PRODUCTION);

        ConnectedUserProjectWorkflow enabledReference = connectedUserProjectWorkflowRepository
            .findById(disabledReference.getId())
            .orElseThrow();

        assertThat(enabledReference.isEnabled()).isTrue();

        String automationWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(workflowUuid);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = projectDeploymentWorkflowService
            .getProjectDeploymentWorkflow(enabledReference.getProjectDeploymentId(), automationWorkflowId);

        assertThat(projectDeploymentWorkflow.getConnections())
            .as("enabling must wire the real ProjectDeploymentWorkflow connections keyed by the platform connection "
                + "key (\"" + CODE_WORKFLOW_COMPONENT_NAME + "\"), not the workflow node name (\"task1\")")
            .containsExactly(new ProjectDeploymentWorkflowConnection(777L, CODE_WORKFLOW_COMPONENT_NAME, "task1"));
    }

    /**
     * D6 regression test: a connected user's reference must be wired ONLY to that connected user's own entitled
     * connections, never to any embedded connection in the tenant. The fixture uses the SAME component name
     * ({@code "codeWorkflow"}) for both the workflow's only slot and the other connected user's connection (id 555),
     * and forces the pre-fix resolver's "unknown component, might require a connection" branch (via the
     * {@code componentDefinitionService} override below) so that reverting only the resolver/facade production files to
     * their pre-fix content actually exercises the bug: the pre-fix resolver matches by component name against the
     * tenant-wide {@code connectionService} list, finds connection 555, and wires it -- silently succeeding where
     * {@link MissingConnectionException} must be thrown instead. The fixed
     * {@link ConnectedUserWorkflowReferenceFacadeImpl#getOrCreateReference} resolves connections exclusively through
     * {@code ConnectedUserConnectionFacade}, which this connected user has none registered with, so
     * {@code connectionService}'s connection 555 is never even consulted.
     */
    @Test
    void testReferenceIsNeverWiredToAnotherConnectedUsersConnection() {
        // Forces the pre-fix resolver's "unknown component, might require a connection" branch -- see this method's
        // Javadoc for why this fixture must also be wireable (albeit wrongly) by the reverted code.
        when(componentDefinitionService.getComponentDefinition(anyString(), anyInt()))
            .thenReturn(null);

        connectedUserService.createConnectedUser("user-b", Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("cross-user-project", "crossUserWorkflow"), Language.JAVASCRIPT);

        long automationWorkflowProjectId = automationWorkflowProjectFacade.fetchProjectIdByName("cross-user-project")
            .orElseThrow();
        String automationWorkflowUuid = findProjectWorkflowByLabel(
            automationWorkflowProjectId, publishedVersion(automationWorkflowProjectId), "crossUserWorkflow")
                .getUuidAsString();

        when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class)))
            .thenReturn(List.of(
                new ComponentConnection(CODE_WORKFLOW_COMPONENT_NAME, 1, "task1", CODE_WORKFLOW_COMPONENT_NAME, true)));
        Connection anotherUsersConnection = connectionOwnedByAnotherUser(555L, CODE_WORKFLOW_COMPONENT_NAME);

        when(connectionService.getConnections(PlatformType.EMBEDDED))
            .thenReturn(List.of(anotherUsersConnection));

        // Stubbed so that, if the pre-fix resolver wires connection 555 anyway, ProjectDeploymentFacadeImpl's
        // environment validation (which looks the connection up by id, not from the list above) does not itself blow
        // up with an unrelated NullPointerException and mask the real assertion this test cares about.
        when(connectionService.getConnection(555L))
            .thenReturn(anotherUsersConnection);
        when(connectedUserConnectionFacade.getConnections(anyLong(), eq(CODE_WORKFLOW_COMPONENT_NAME), eq(List.of())))
            .thenReturn(List.of());

        // catchThrowable (not assertThatThrownBy) so that, on the pre-fix resolver -- which does not throw here at
        // all, because it wires connection 555 instead -- the wiring assertions below still run and show exactly
        // what got wired, rather than the run stopping at "expected a throwable but none was thrown".
        Throwable thrown = catchThrowable(() -> connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "user-b", automationWorkflowUuid, Environment.PRODUCTION));

        assertThat(
            projectDeploymentWorkflowService
                .getProjectDeploymentWorkflows(deploymentIdFor(automationWorkflowProjectId, "user-b")))
                    .as("the real ProjectDeploymentWorkflow connections must never hold connection 555, which belongs to "
                        + "another connected user")
                    .allSatisfy(row -> assertThat(row.getConnections()).isEmpty());

        assertThat(thrown).isInstanceOf(MissingConnectionException.class);
    }

    /**
     * Priority 4: the same connected user provisioning the same automation workflow in two different environments must
     * get two isolated {@code ProjectDeployment}s.
     */
    @Test
    void testSameUserInTwoEnvironmentsGetsIsolatedDeployments() {
        connectedUserService.createConnectedUser("userMultiEnv", Environment.PRODUCTION);
        connectedUserService.createConnectedUser("userMultiEnv", Environment.DEVELOPMENT);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("multi-env-project", "envWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("multi-env-project")
            .orElseThrow();
        String workflowUuid = findProjectWorkflowByLabel(projectId, publishedVersion(projectId), "envWorkflow")
            .getUuidAsString();

        ConnectedUserProjectWorkflow productionReference = connectedUserWorkflowReferenceFacade
            .getOrCreateReference("userMultiEnv", workflowUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow developmentReference = connectedUserWorkflowReferenceFacade
            .getOrCreateReference("userMultiEnv", workflowUuid, Environment.DEVELOPMENT);

        assertThat(productionReference.getProjectDeploymentId())
            .isNotEqualTo(developmentReference.getProjectDeploymentId());
    }

    /**
     * The rollout after a code-workflow redeploy marks a removed template's reference dangling and deletes the
     * deployment once its last row is gone. Deleting that dangling reference afterwards removes the reference itself
     * and leaves no deployment behind.
     */
    @Test
    void testDeletingADanglingReferenceLeavesNoDeploymentBehind() {
        connectedUserService.createConnectedUser("userDanglingDelete", Environment.PRODUCTION);

        givenNoRunningJobs();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("dangling-delete-project", "keptWorkflow", "removedWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("dangling-delete-project")
            .orElseThrow();
        String removedWorkflowUuid =
            findProjectWorkflowByLabel(projectId, publishedVersion(projectId), "removedWorkflow")
                .getUuidAsString();

        ConnectedUserProjectWorkflow reference = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userDanglingDelete", removedWorkflowUuid, Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("dangling-delete-project", "keptWorkflow"), Language.JAVASCRIPT);

        connectedUserReferenceRolloutManager.rollOut(projectId);

        assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId()))
            .get()
            .extracting(ConnectedUserProjectWorkflow::isDangling)
            .isEqualTo(true);

        connectedUserWorkflowReferenceFacade.deleteReference(
            "userDanglingDelete", removedWorkflowUuid, Environment.PRODUCTION);

        assertThat(projectDeploymentService.fetchProjectDeploymentByName(
            projectId, "__EMBEDDED__userDanglingDelete__PRODUCTION")).isEmpty();
        assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId())).isEmpty();
    }

    /**
     * Disabling a reference the rollout left dangling keeps it dangling and disabled (a dangling template can never be
     * enabled again), and leaves the row of the connected user's other template in place.
     */
    @Test
    void testDisablingADanglingReferenceKeepsTheOtherTemplatesRow() {
        connectedUserService.createConnectedUser("userDanglingDisable", Environment.PRODUCTION);

        givenNoRunningJobs();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("dangling-disable-project", "keptWorkflow", "removedWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("dangling-disable-project")
            .orElseThrow();
        int publishedVersion = publishedVersion(projectId);

        ProjectWorkflow keptProjectWorkflow = findProjectWorkflowByLabel(projectId, publishedVersion, "keptWorkflow");
        String removedWorkflowUuid = findProjectWorkflowByLabel(projectId, publishedVersion, "removedWorkflow")
            .getUuidAsString();

        connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userDanglingDisable", keptProjectWorkflow.getUuidAsString(), Environment.PRODUCTION);

        ConnectedUserProjectWorkflow removedReference = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userDanglingDisable", removedWorkflowUuid, Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("dangling-disable-project", "keptWorkflow"), Language.JAVASCRIPT);

        connectedUserReferenceRolloutManager.rollOut(projectId);

        connectedUserWorkflowReferenceFacade.enableReference(
            "userDanglingDisable", removedWorkflowUuid, false, Environment.PRODUCTION);

        ProjectWorkflow keptProjectWorkflowAfterRollout = findProjectWorkflowByLabel(
            projectId, publishedVersion(projectId), "keptWorkflow");

        assertThat(
            projectDeploymentWorkflowService.getProjectDeploymentWorkflows(removedReference.getProjectDeploymentId()))
                .extracting(ProjectDeploymentWorkflow::getWorkflowId)
                .containsExactly(keptProjectWorkflowAfterRollout.getWorkflowId());
        assertThat(connectedUserProjectWorkflowRepository.findById(removedReference.getId()))
            .get()
            .satisfies(reference -> {
                assertThat(reference.isDangling()).isTrue();
                assertThat(reference.isEnabled()).isFalse();
            });
    }

    /**
     * The rollout after a code-workflow redeploy moves the deployment to the new version: the kept template's row is
     * carried to its new workflow id, and the removed template's reference is marked dangling with its row dropped
     * rather than left running.
     */
    @Test
    void testRolloutAfterARedeployDropsTheDanglingReferencesRowAndCarriesTheKeptOne() {
        connectedUserService.createConnectedUser("userRedeployRollout", Environment.PRODUCTION);

        givenNoRunningJobs();

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("redeploy-rollout-project", "keptWorkflow", "removedWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("redeploy-rollout-project")
            .orElseThrow();
        int firstVersion = publishedVersion(projectId);

        String keptWorkflowUuid = findProjectWorkflowByLabel(projectId, firstVersion, "keptWorkflow")
            .getUuidAsString();
        String removedWorkflowUuid = findProjectWorkflowByLabel(projectId, firstVersion, "removedWorkflow")
            .getUuidAsString();

        ConnectedUserProjectWorkflow keptReference = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userRedeployRollout", keptWorkflowUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow removedReference = connectedUserWorkflowReferenceFacade.getOrCreateReference(
            "userRedeployRollout", removedWorkflowUuid, Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("redeploy-rollout-project", "keptWorkflow"), Language.JAVASCRIPT);

        int secondVersion = publishedVersion(projectId);

        connectedUserReferenceRolloutManager.rollOut(projectId);

        long projectDeploymentId = keptReference.getProjectDeploymentId();

        assertThat(projectDeploymentService.getProjectDeployment(projectDeploymentId)
            .getProjectVersion()).isEqualTo(secondVersion);
        assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeploymentId))
            .extracting(ProjectDeploymentWorkflow::getWorkflowId)
            .containsExactly(
                findProjectWorkflowByLabel(projectId, secondVersion, "keptWorkflow").getWorkflowId());
        assertThat(connectedUserProjectWorkflowRepository.findById(removedReference.getId()))
            .get()
            .satisfies(reference -> {
                assertThat(reference.isDangling()).isTrue();
                assertThat(reference.isEnabled()).isFalse();
            });
    }

    /**
     * Two first provisions of one connected user for two templates of the same automation workflow project, racing:
     * both find no deployment and, unserialized, both create one -- after which every lookup by (project, name) fails.
     * The connected user's project row is created before the race; only the reference writes that follow are under
     * test.
     */
    @Test
    void testConcurrentFirstProvisionsOfOneConnectedUserShareOneDeployment() throws Exception {
        connectedUserService.createConnectedUser("userConcurrent", Environment.PRODUCTION);

        automationWorkflowProjectCodeWorkflowFacade.save(
            projectSource("concurrent-project", "firstWorkflow", "secondWorkflow"), Language.JAVASCRIPT);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName("concurrent-project")
            .orElseThrow();
        int publishedVersion = publishedVersion(projectId);

        String firstWorkflowUuid = findProjectWorkflowByLabel(projectId, publishedVersion, "firstWorkflow")
            .getUuidAsString();
        String secondWorkflowUuid = findProjectWorkflowByLabel(projectId, publishedVersion, "secondWorkflow")
            .getUuidAsString();

        createConnectedUserProject("userConcurrent", Environment.PRODUCTION);

        CountDownLatch startLatch = new CountDownLatch(1);

        ConnectedUserProjectWorkflow firstReference;
        ConnectedUserProjectWorkflow secondReference;

        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            Future<ConnectedUserProjectWorkflow> firstFuture = executorService.submit(
                () -> provisionAfterStart(startLatch, "userConcurrent", firstWorkflowUuid));
            Future<ConnectedUserProjectWorkflow> secondFuture = executorService.submit(
                () -> provisionAfterStart(startLatch, "userConcurrent", secondWorkflowUuid));

            startLatch.countDown();

            firstReference = firstFuture.get(2, TimeUnit.MINUTES);
            secondReference = secondFuture.get(2, TimeUnit.MINUTES);
        }

        List<ProjectDeployment> projectDeployments = projectDeploymentService.getAllProjectDeployments(projectId);

        assertThat(projectDeployments).hasSize(1);

        long projectDeploymentId = projectDeployments.getFirst()
            .getId();

        assertThat(firstReference.getProjectDeploymentId()).isEqualTo(projectDeploymentId);
        assertThat(secondReference.getProjectDeploymentId()).isEqualTo(projectDeploymentId);
    }

    /**
     * Removing an enabled row stops its running jobs first; the mocked job service answers "none running", as an empty
     * job table would.
     */
    private void givenNoRunningJobs() {
        when(principalJobService.getJobIds(any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(Page.empty());
    }

    private ConnectedUserProjectWorkflow provisionAfterStart(
        CountDownLatch startLatch, String externalUserId, String automationWorkflowUuid) throws InterruptedException {

        startLatch.await();

        return connectedUserWorkflowReferenceFacade.getOrCreateReference(
            externalUserId, automationWorkflowUuid, Environment.PRODUCTION);
    }

    private long connectedUserProjectId(String externalUserId, Environment environment) {
        return connectedUserProjectService.getConnectUserProject(externalUserId, environment)
            .getId();
    }

    private long deploymentIdFor(long automationWorkflowProjectId, String externalUserId) {
        return projectDeploymentService
            .fetchProjectDeploymentByName(automationWorkflowProjectId, "__EMBEDDED__" + externalUserId + "__PRODUCTION")
            .orElseThrow()
            .getId();
    }

    private static Connection connectionOwnedByAnotherUser(long id, String componentName) {
        Connection connection = new Connection();

        connection.setId(id);
        connection.setComponentName(componentName);
        connection.setType(PlatformType.EMBEDDED);
        connection.setEnvironmentId(Environment.PRODUCTION.ordinal());

        return connection;
    }

    private static ConnectionDTO connectionDTO(long id, String componentName) {
        ConnectionDTO connectionDTO = mock(ConnectionDTO.class);

        when(connectionDTO.id()).thenReturn(id);
        when(connectionDTO.componentName()).thenReturn(componentName);

        return connectionDTO;
    }

    private int publishedVersion(long projectId) {
        Project project = projectService.getProject(projectId);

        return project.getLastPublishedProjectVersion()
            .getVersion();
    }

    private ProjectWorkflow findProjectWorkflowByLabel(long projectId, int projectVersion, String label) {
        List<ProjectWorkflow> workflows = projectWorkflowService.getProjectWorkflows(projectId, projectVersion);

        for (ProjectWorkflow projectWorkflow : workflows) {
            Workflow workflow = workflowService.getWorkflow(projectWorkflow.getWorkflowId());

            if (workflow.getDefinition()
                .contains("\"label\":\"" + label + "\"")) {

                return projectWorkflow;
            }
        }

        throw new AssertionError(
            "No project workflow found with label '" + label + "' in project=" + projectId + " version="
                + projectVersion);
    }

    // The newlines are embedded JavaScript source content, not console formatting; %n would corrupt the script.
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static byte[] projectSource(String projectName, String... workflowNames) {
        String workflowsJs = Arrays.stream(workflowNames)
            .map(AutomationCodeWorkflowBridgeIntTest::workflowJs)
            .collect(Collectors.joining(","));

        String source = """
            ({
                name: "%s",
                version: "1",
                description: "A code workflow.",
                workflows: [%s]
            })
            """.formatted(projectName, workflowsJs);

        return source.getBytes(StandardCharsets.UTF_8);
    }

    // The newlines are embedded JavaScript source content, not console formatting; %n would corrupt the script.
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static String workflowJs(String workflowName) {
        return """
            {
                name: "%s",
                label: "%s",
                tasks: [
                    {
                        name: "task1",
                        label: "Task 1",
                        perform: function () {
                            return "hello";
                        }
                    }
                ]
            }
            """.formatted(workflowName, workflowName);
    }

    private void createConnectedUserProject(String externalUserId, Environment environment) {
        Project project = new Project();

        project.setName("__EMBEDDED__" + externalUserId);
        project.setWorkspaceId(Workspace.DEFAULT_WORKSPACE_ID);

        project = projectService.create(project);

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        connectedUserProjectService.create(connectedUser.getId(), project.getId());
    }
}
