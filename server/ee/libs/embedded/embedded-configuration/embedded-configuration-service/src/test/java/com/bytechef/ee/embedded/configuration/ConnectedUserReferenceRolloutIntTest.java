/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.facade.WorkspaceConnectionFacade;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.configuration.service.ProjectCodeWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.ee.embedded.codeworkflowbridge.AutomationCodeWorkflowBridgeIntTestConfiguration;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserConnectionFacade;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.configuration.security.EmbeddedPermissionEvaluator;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
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
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A connected user's references to templates of one catalog project -- visual or code workflow alike -- share ONE
 * per-(connected user, catalog project, environment) {@link ProjectDeployment}, created at the catalog project's last
 * published version, holding one row per referenced template, and removed together with its last row.
 *
 * <p>
 * Uses the {@link AutomationCodeWorkflowBridgeIntTest} Spring configuration and mock boundary, against a real Postgres.
 * {@link ComponentConnectionFacade}, {@link ConnectionService} and {@link ConnectedUserConnectionFacade} are stubbed so
 * the fixture's single {@code slack} slot resolves to connection 777 and {@code ProjectDeploymentFacadeImpl}'s
 * validation of an enabled row (connection key, component name and environment) passes.
 * {@link ProjectCodeWorkflowService} is mocked so a catalog project can be marked a code workflow project without a
 * code workflow artifact.
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
    PrincipalJobFacade.class, PrincipalJobService.class, ProjectCodeWorkflowService.class, ProjectFacade.class,
    TaskDispatcherDefinitionService.class, TaskExecutionService.class,
    TriggerDefinitionFacade.class, TriggerDefinitionService.class, TriggerExecutionService.class,
    TriggerLifecycleFacade.class, UserService.class, WorkflowCacheManager.class, WorkflowNodeParameterFacade.class,
    WorkflowNodeTestOutputService.class, WorkflowTestConfigurationFacade.class, WorkflowTestConfigurationService.class,
    WorkspaceConnectionFacade.class, WorkspaceFacade.class
})
class ConnectedUserReferenceRolloutIntTest {

    private static final String EXTERNAL_USER_ID = "rollout-user-1";
    private static final long RUNNING_FIRST_TEMPLATE_JOB_ID = 4242L;

    private static final String SLACK_WORKFLOW_DEFINITION = """
        {"label":"Post","triggers":[],"tasks":[{"name":"postMessage1","type":"slack/v1/postMessage","parameters":{}}]}
        """;

    @Autowired
    private AutomationWorkflowProjectFacade automationWorkflowProjectFacade;

    @Autowired
    private ComponentConnectionFacade componentConnectionFacade;

    @Autowired
    private ComponentDefinitionService componentDefinitionService;

    @Autowired
    private ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;

    @MockitoBean
    private ConnectedUserConnectionFacade connectedUserConnectionFacade;

    @Autowired
    private ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private EmbeddedPermissionEvaluator embeddedPermissionEvaluator;

    @Autowired
    private JobFacade jobFacade;

    @Autowired
    private PrincipalJobService principalJobService;

    @Autowired
    private ProjectCodeWorkflowService projectCodeWorkflowService;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @BeforeEach
    void setUp() {
        if (connectedUserService.fetchConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION)
            .isEmpty()) {

            connectedUserService.createConnectedUser(EXTERNAL_USER_ID, Environment.PRODUCTION);
        }

        when(componentDefinitionService.getComponentDefinition(anyString(), anyInt()))
            .thenReturn(new ComponentDefinition("slack"));
        when(connectionService.getConnections(PlatformType.EMBEDDED))
            .thenReturn(List.of());

        // The per-user deployment is enabled, so rewriting or removing an enabled row stops that row's running jobs;
        // the mocked job service answers "none running", as an empty job table would.
        when(principalJobService.getJobIds(
            any(), any(), any(), anyList(), any(), anyList(), anyBoolean(), anyInt()))
                .thenReturn(Page.empty());

        // getOrCreateReference validates the catalog uuid against the permission-FILTERED catalog, which consults this
        // (mocked) evaluator; the default mock answer of false would hide every template from every connected user.
        when(embeddedPermissionEvaluator.evaluate(any(), any()))
            .thenReturn(true);
    }

    @Test
    void testTwoTemplatesFromOneVisualProjectShareOneDeploymentAtThePublishedVersion() {
        long catalogProjectId = createCatalogProject("Two Templates");

        String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
        String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);
        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow second = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

        assertThat(second.getProjectDeploymentId()).isEqualTo(first.getProjectDeploymentId());

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(
            first.getProjectDeploymentId());

        assertThat(projectDeployment.getProjectVersion()).isEqualTo(2);
        assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(first.getProjectDeploymentId()))
            .hasSize(2)
            .allSatisfy(row -> assertThat(row.getConnections())
                .containsExactly(new ProjectDeploymentWorkflowConnection(777L, "slack", "postMessage1")));
    }

    @Test
    void testEnableReferenceAfterRepublishDoesNotFail() {
        long catalogProjectId = createCatalogProject("Republish Enable");

        String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        String versionOneWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(workflowUuid);

        ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

        connectedUserCodeWorkflowReferenceFacade.enableReference(
            EXTERNAL_USER_ID, workflowUuid, false, Environment.PRODUCTION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        assertThatCode(() -> connectedUserCodeWorkflowReferenceFacade.enableReference(
            EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION))
                .doesNotThrowAnyException();

        ConnectedUserProjectWorkflow enabledReference = connectedUserProjectWorkflowRepository
            .findById(reference.getId())
            .orElseThrow();

        assertThat(enabledReference.isEnabled()).isTrue();
        assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(reference.getProjectDeploymentId()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getWorkflowId()).isEqualTo(versionOneWorkflowId);
                assertThat(row.isEnabled()).isTrue();
            });
    }

    @Test
    void testDeleteLastReferenceRemovesItsRowAndTheDeployment() {
        long catalogProjectId = createCatalogProject("Delete Row");

        String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

        connectedUserCodeWorkflowReferenceFacade.deleteReference(EXTERNAL_USER_ID, workflowUuid,
            Environment.PRODUCTION);

        assertThat(projectDeploymentService.fetchProjectDeploymentByName(
            catalogProjectId, "__EMBEDDED__" + EXTERNAL_USER_ID + "__PRODUCTION")).isEmpty();
        assertThat(connectedUserProjectWorkflowRepository.findById(reference.getId())).isEmpty();
    }

    /**
     * Changing one template's reference must write only that template's row: disabling a row stops its running jobs, so
     * re-sending a sibling row would stop the sibling template's in-flight runs and re-register its triggers.
     */
    @Test
    void testChangingOneReferenceNeverStopsAnotherTemplatesRunningJobs() {
        long catalogProjectId = createCatalogProject("Sibling Rows");

        String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
        String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);

        assertThat(first.isEnabled()).isTrue();

        String firstWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(firstUuid);

        when(principalJobService.getJobIds(
            any(), any(), any(), anyList(), any(), argThat(workflowIds -> workflowIds.contains(firstWorkflowId)),
            anyBoolean(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(RUNNING_FIRST_TEMPLATE_JOB_ID)));

        ConnectedUserProjectWorkflow second = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

        connectedUserCodeWorkflowReferenceFacade.enableReference(
            EXTERNAL_USER_ID, secondUuid, false, Environment.PRODUCTION);
        connectedUserCodeWorkflowReferenceFacade.enableReference(
            EXTERNAL_USER_ID, secondUuid, true, Environment.PRODUCTION);

        assertThat(second.getProjectDeploymentId()).isEqualTo(first.getProjectDeploymentId());

        verify(jobFacade, never()).stopJob(RUNNING_FIRST_TEMPLATE_JOB_ID);
    }

    /**
     * Provisioning through the three-argument overload is atomic: a failure after the deployment was created and the
     * reference saved -- here the entitlement lookup failing with a non-409 exception -- leaves neither behind, so the
     * next call cannot return an orphan.
     */
    @Test
    void testFailedProvisioningLeavesNoReferenceAndNoDeployment() {
        long catalogProjectId = createCatalogProject("Atomic Provisioning");

        String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        when(connectedUserConnectionFacade.getConnections(anyLong(), eq("slack"), eq(List.of())))
            .thenThrow(new IllegalStateException("Connection lookup failed"));

        assertThatThrownBy(() -> connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION))
                .isInstanceOf(IllegalStateException.class);

        assertThat(connectedUserProjectWorkflowRepository.findAll())
            .noneMatch(reference -> workflowUuid.equals(reference.getCatalogWorkflowUuid()));
        assertThat(projectDeploymentService.fetchProjectDeploymentByName(
            catalogProjectId, "__EMBEDDED__" + EXTERNAL_USER_ID + "__PRODUCTION")).isEmpty();
    }

    /**
     * Enabling refused with a 409 keeps what it wrote: the reference, enabled before, is saved disabled in the real
     * transaction rather than rolled back to its enabled state.
     */
    @Test
    void testEnableReferenceWithAMissingConnectionThrowsAndKeepsTheReferenceDisabled() {
        long catalogProjectId = createCatalogProject("Missing Connection Enable");

        String workflowUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, workflowUuid, Environment.PRODUCTION);

        assertThat(reference.isEnabled()).isTrue();

        when(connectedUserConnectionFacade.getConnections(anyLong(), eq("slack"), eq(List.of())))
            .thenReturn(List.of());

        assertThatThrownBy(() -> connectedUserCodeWorkflowReferenceFacade.enableReference(
            EXTERNAL_USER_ID, workflowUuid, true, Environment.PRODUCTION))
                .isInstanceOf(MissingConnectionException.class);

        ConnectedUserProjectWorkflow reloadedReference = connectedUserProjectWorkflowRepository
            .findById(reference.getId())
            .orElseThrow();

        assertThat(reloadedReference.isEnabled()).isFalse();
    }

    @Test
    void testCodeWorkflowProjectTwoTemplatesShareOneDeployment() {
        long catalogProjectId = createCatalogProject("Code Two Templates");

        when(projectCodeWorkflowService.getCodeWorkflowProjectIds()).thenReturn(List.of(catalogProjectId));

        String firstUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);
        String secondUuid = addWorkflow(catalogProjectId, SLACK_WORKFLOW_DEFINITION);

        automationWorkflowProjectFacade.publishProject(catalogProjectId);
        automationWorkflowProjectFacade.publishProject(catalogProjectId);

        stubEntitledSlackConnection(777L);

        ConnectedUserProjectWorkflow first = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, firstUuid, Environment.PRODUCTION);
        ConnectedUserProjectWorkflow second = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
            EXTERNAL_USER_ID, secondUuid, Environment.PRODUCTION);

        assertThat(second.getProjectDeploymentId()).isEqualTo(first.getProjectDeploymentId());
        assertThat(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(first.getProjectDeploymentId()))
            .hasSize(2);
    }

    private long createCatalogProject(String name) {
        return automationWorkflowProjectFacade.createProject(name + " " + UUID.randomUUID(), "", null, List.of(), null);
    }

    private String addWorkflow(long catalogProjectId, String definition) {
        String workflowId = automationWorkflowProjectFacade.createProjectWorkflow(catalogProjectId, definition, null);

        ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(workflowId);

        return projectWorkflow.getUuidAsString();
    }

    private void stubEntitledSlackConnection(long connectionId) {
        ConnectionDTO connectionDTO = mock(ConnectionDTO.class);
        Connection connection = new Connection();

        connection.setComponentName("slack");
        connection.setEnvironmentId(Environment.PRODUCTION.ordinal());
        connection.setId(connectionId);

        ComponentConnection slot = new ComponentConnection("slack", 1, "postMessage1", "slack", true);

        when(connectionDTO.id()).thenReturn(connectionId);
        when(connectionDTO.componentName()).thenReturn("slack");
        when(connectedUserConnectionFacade.getConnections(anyLong(), eq("slack"), eq(List.of())))
            .thenReturn(List.of(connectionDTO));
        when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class))).thenReturn(List.of(slot));
        when(componentConnectionFacade.getComponentConnection(anyString(), eq("postMessage1"), eq("slack")))
            .thenReturn(slot);
        when(connectionService.getConnection(connectionId)).thenReturn(connection);
    }
}
