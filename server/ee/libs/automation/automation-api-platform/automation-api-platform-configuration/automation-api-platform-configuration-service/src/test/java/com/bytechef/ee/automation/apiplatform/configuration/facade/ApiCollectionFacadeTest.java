/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.apiplatform.configuration.audit.ApiCollectionAuditEvent;
import com.bytechef.ee.automation.apiplatform.configuration.audit.ApiCollectionAuditPublisher;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollection;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint.HttpMethod;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionDTO;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionEndpointDTO;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionVersionUpdateDTO;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionVersionUpdateDTO.MissingConnection;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionVersionUpdateDTO.MissingInput;
import com.bytechef.ee.automation.apiplatform.configuration.exception.ApiCollectionErrorType;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.tag.service.TagService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ApiCollectionFacadeTest {

    @Mock
    private ApiCollectionAuditPublisher apiCollectionAuditPublisher;

    @Mock
    private ApiCollectionEndpointService apiCollectionEndpointService;

    @Mock
    private ApiCollectionService apiCollectionService;

    @Mock
    private ComponentConnectionFacade componentConnectionFacade;

    @Mock
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Mock
    private ProjectDeploymentService projectDeploymentService;

    @Mock
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    @Mock
    private TagService tagService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private ApiCollectionFacadeImpl apiCollectionFacade;

    @Test
    void testCreateApiCollectionThrowsWhenNameAlreadyExists() {
        Project project = new Project();

        project.setWorkspaceId(5L);

        when(projectService.getProject(42L)).thenReturn(project);
        when(apiCollectionService.existsByNameAndEnvironment("billing", 5L, Environment.STAGING, null))
            .thenReturn(true);

        ApiCollectionDTO apiCollectionDTO = createApiCollectionDTO();

        assertThatThrownBy(() -> apiCollectionFacade.createApiCollection(apiCollectionDTO))
            .isInstanceOf(ConfigurationException.class)
            .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
            .isEqualTo(ApiCollectionErrorType.NAME_ALREADY_EXISTS.getErrorKey());
    }

    @Test
    void testCreateApiCollectionDoesNotThrowWhenNameAvailable() {
        Project project = new Project();

        project.setWorkspaceId(5L);

        when(projectService.getProject(42L)).thenReturn(project);
        when(apiCollectionService.existsByNameAndEnvironment("billing", 5L, Environment.STAGING, null))
            .thenReturn(false);
        when(projectDeploymentService.create(any())).thenAnswer(invocation -> {
            ProjectDeployment projectDeployment = invocation.getArgument(0);

            projectDeployment.setId(200L);

            return projectDeployment;
        });
        when(apiCollectionService.create(any())).thenAnswer(invocation -> {
            ApiCollection apiCollection = invocation.getArgument(0);

            apiCollection.setId(100L);

            return apiCollection;
        });
        when(projectService.getProjectDeploymentProject(200L)).thenReturn(project);

        ProjectDeployment savedProjectDeployment = new ProjectDeployment();

        savedProjectDeployment.setId(200L);
        savedProjectDeployment.setProjectId(42L);
        savedProjectDeployment.setProjectVersion(3);
        savedProjectDeployment.setEnvironment(Environment.STAGING);

        when(projectDeploymentService.getProjectDeployment(200L)).thenReturn(savedProjectDeployment);

        ApiCollectionDTO apiCollectionDTO = createApiCollectionDTO();

        ApiCollectionDTO created = apiCollectionFacade.createApiCollection(apiCollectionDTO);

        assertThat(created.name()).isEqualTo("billing");
        assertThat(created.id()).isEqualTo(100L);
    }

    @Test
    void testUpdateApiCollectionThrowsWhenAnotherCollectionOwnsName() {
        stubPersistedApiCollectionAndDeployment();

        when(apiCollectionService.existsByNameAndEnvironment("billing", 5L, Environment.STAGING, 100L))
            .thenReturn(true);

        ApiCollectionDTO apiCollectionDTO = updateApiCollectionDTO("billing");

        assertThatThrownBy(() -> apiCollectionFacade.updateApiCollection(apiCollectionDTO))
            .isInstanceOf(ConfigurationException.class)
            .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
            .isEqualTo(ApiCollectionErrorType.NAME_ALREADY_EXISTS.getErrorKey());
    }

    @Test
    void testUpdateApiCollectionDoesNotThrowWhenRenamingToOwnName() {
        stubPersistedApiCollectionAndDeployment();

        // excludeId (the collection's own persisted id) genuinely excludes the row being updated, so a name only
        // this collection owns must not be reported as a collision.
        when(apiCollectionService.existsByNameAndEnvironment("billing", 5L, Environment.STAGING, 100L))
            .thenReturn(false);
        when(apiCollectionService.update(any())).thenAnswer(invocation -> {
            ApiCollection updatedApiCollection = invocation.getArgument(0);

            // Mirrors ApiCollectionServiceImpl.update(), which loads the persisted row and never copies
            // projectDeploymentId off the incoming argument, so the returned entity keeps its own deployment id.
            updatedApiCollection.setProjectDeploymentId(200L);

            return updatedApiCollection;
        });

        ApiCollectionDTO apiCollectionDTO = updateApiCollectionDTO("billing");

        ApiCollectionDTO updated = apiCollectionFacade.updateApiCollection(apiCollectionDTO);

        assertThat(updated.name()).isEqualTo("billing");
    }

    @Test
    void testUpdateApiCollectionResolvesDeploymentFromPersistedEntityNotFromDto() {
        stubPersistedApiCollectionAndDeployment();

        when(apiCollectionService.existsByNameAndEnvironment("billing", 5L, Environment.STAGING, 100L))
            .thenReturn(false);
        when(apiCollectionService.update(any())).thenAnswer(invocation -> {
            ApiCollection updatedApiCollection = invocation.getArgument(0);

            updatedApiCollection.setProjectDeploymentId(200L);

            return updatedApiCollection;
        });

        ApiCollectionDTO apiCollectionDTO = updateApiCollectionDTO("billing");

        assertThat(apiCollectionDTO.projectDeploymentId()).isZero();

        ApiCollectionDTO updated = apiCollectionFacade.updateApiCollection(apiCollectionDTO);

        assertThat(updated.name()).isEqualTo("billing");

        // The regression: the guard must resolve the deployment (200L) from the persisted entity, never from the
        // client-supplied DTO's readOnly projectDeploymentId (0L, per toApiCollection()'s primitive default).
        verify(projectDeploymentService, never()).getProjectDeployment(0L);
        verify(projectService, never()).getProjectDeploymentProject(0L);
    }

    /**
     * The pointer is what ties an endpoint to the workflow it serves, and every {@code api_collection_endpoint} row FKs
     * to it. Because {@link ApiCollectionEndpointDTO#toApiCollectionEndpoint()} does not map it, an update whose DTO
     * carries no pointer must leave the persisted one alone rather than blanking it.
     */
    @Test
    void testUpdateApiCollectionEndpointKeepsProjectDeploymentWorkflowWhenTheWorkflowIsUnchanged() {
        stubEndpointUpdate(901L);

        apiCollectionFacade.updateApiCollectionEndpoint(endpointDTO("wf-uuid"));

        assertThat(capturedUpdatedEndpoint().getProjectDeploymentWorkflowId()).isEqualTo(901L);
    }

    @Test
    void testUpdateApiCollectionEndpointRepointsProjectDeploymentWorkflowWhenTheWorkflowChanged() {
        stubEndpointUpdate(902L);

        apiCollectionFacade.updateApiCollectionEndpoint(endpointDTO("other-wf-uuid"));

        assertThat(capturedUpdatedEndpoint().getProjectDeploymentWorkflowId()).isEqualTo(902L);
    }

    /**
     * A uuid naming no workflow of this collection is bad caller input, not a server fault: before the endpoint update
     * learned to re-resolve the pointer it was silently ignored, and the naive re-resolution reported it as an untyped
     * {@code IllegalArgumentException} — a 500.
     */
    @Test
    void testUpdateApiCollectionEndpointRejectsAWorkflowUuidOutsideTheCollection() {
        ApiCollectionEndpoint persistedApiCollectionEndpoint = new ApiCollectionEndpoint();

        persistedApiCollectionEndpoint.setId(501L);
        persistedApiCollectionEndpoint.setApiCollectionId(100L);
        persistedApiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        when(apiCollectionEndpointService.getOpenApiEndpoint(501L)).thenReturn(persistedApiCollectionEndpoint);

        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setId(100L);
        apiCollection.setProjectDeploymentId(200L);

        when(apiCollectionService.getApiCollection(100L)).thenReturn(apiCollection);
        when(projectWorkflowService.fetchProjectWorkflowWorkflowId(200L, "foreign-wf-uuid"))
            .thenReturn(Optional.empty());

        ApiCollectionEndpointDTO apiCollectionEndpointDTO = endpointDTO("foreign-wf-uuid");

        assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionEndpoint(apiCollectionEndpointDTO))
            .isInstanceOf(ConfigurationException.class)
            .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
            .isEqualTo(ApiCollectionErrorType.WORKFLOW_NOT_FOUND.getErrorKey());

        verify(apiCollectionEndpointService, never()).update(any());
    }

    @Test
    void testUpdateApiCollectionEndpointRejectsAMalformedWorkflowUuidTheSameWay() {
        ApiCollectionEndpoint persistedApiCollectionEndpoint = new ApiCollectionEndpoint();

        persistedApiCollectionEndpoint.setId(501L);
        persistedApiCollectionEndpoint.setApiCollectionId(100L);
        persistedApiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        when(apiCollectionEndpointService.getOpenApiEndpoint(501L)).thenReturn(persistedApiCollectionEndpoint);

        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setId(100L);
        apiCollection.setProjectDeploymentId(200L);

        when(apiCollectionService.getApiCollection(100L)).thenReturn(apiCollection);

        // UUID.fromString throws before the repository is reached; the caller must not be able to tell that apart
        // from a well-formed uuid naming nothing.
        when(projectWorkflowService.fetchProjectWorkflowWorkflowId(200L, "not-a-uuid"))
            .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

        ApiCollectionEndpointDTO apiCollectionEndpointDTO = endpointDTO("not-a-uuid");

        assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionEndpoint(apiCollectionEndpointDTO))
            .isInstanceOf(ConfigurationException.class)
            .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
            .isEqualTo(ApiCollectionErrorType.WORKFLOW_NOT_FOUND.getErrorKey());
    }

    @Nested
    class UpdateApiCollectionProjectVersion {

        private static final UUID WORKFLOW_A_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        private static final UUID WORKFLOW_B_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        private final ProjectDeployment projectDeployment = new ProjectDeployment();
        private final ProjectDeploymentWorkflowConnection workflowAConnection =
            new ProjectDeploymentWorkflowConnection(61L, "slack", "postMessage");

        private ApiCollectionEndpoint listOrdersEndpoint;
        private ApiCollectionEndpoint createOrderEndpoint;

        @BeforeEach
        void beforeEach() {
            ApiCollection apiCollection = new ApiCollection();

            apiCollection.setCollectionVersion(1);
            apiCollection.setContextPath("orders");
            apiCollection.setId(100L);
            apiCollection.setName("orders");
            apiCollection.setProjectDeploymentId(200L);

            lenient().when(apiCollectionService.getApiCollection(100L))
                .thenReturn(apiCollection);

            projectDeployment.setEnvironment(Environment.STAGING);
            projectDeployment.setId(200L);
            projectDeployment.setProjectId(42L);
            projectDeployment.setProjectVersion(2);

            lenient().when(projectDeploymentService.getProjectDeployment(200L))
                .thenReturn(projectDeployment);

            Project project = new Project();

            project.setId(42L);
            project.setProjectVersions(
                List.of(
                    publishedProjectVersion(1), publishedProjectVersion(2), publishedProjectVersion(3),
                    new ProjectVersion(4)));
            project.setWorkspaceId(5L);

            lenient().when(projectService.getProject(42L))
                .thenReturn(project);

            lenient().when(projectWorkflowService.getProjectWorkflows(42L, 2))
                .thenReturn(
                    List.of(
                        new ProjectWorkflow(42L, 2, "wf-a-2", WORKFLOW_A_UUID),
                        new ProjectWorkflow(42L, 2, "wf-b-2", WORKFLOW_B_UUID)));

            ProjectDeploymentWorkflow workflowARow = createProjectDeploymentWorkflow(
                901L, "wf-a-2", true, List.of(workflowAConnection), Map.of("region", "eu"));
            ProjectDeploymentWorkflow workflowBRow = createProjectDeploymentWorkflow(
                902L, "wf-b-2", false, List.of(), Map.of("limit", 10));

            lenient().when(projectDeploymentWorkflowService.getProjectDeploymentWorkflows(200L))
                .thenReturn(List.of(workflowARow, workflowBRow));

            listOrdersEndpoint = createEndpoint(501L, "orders", HttpMethod.GET, 901L);

            listOrdersEndpoint.setName("listOrders");

            createOrderEndpoint = createEndpoint(502L, "orders", HttpMethod.POST, 902L);

            createOrderEndpoint.setName("createOrder");

            lenient().when(apiCollectionEndpointService.getApiEndpoints(100L))
                .thenReturn(List.of(listOrdersEndpoint, createOrderEndpoint));
            lenient().when(apiCollectionEndpointService.getOpenApiEndpoint(501L))
                .thenReturn(listOrdersEndpoint);
            lenient().when(apiCollectionEndpointService.getOpenApiEndpoint(502L))
                .thenReturn(createOrderEndpoint);
            lenient().when(apiCollectionEndpointService.update(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

            lenient().when(projectWorkflowService.fetchProjectWorkflowWorkflowId(200L, WORKFLOW_A_UUID.toString()))
                .thenReturn(Optional.of("wf-a-3"));
            lenient().when(projectWorkflowService.fetchProjectWorkflowWorkflowId(200L, WORKFLOW_B_UUID.toString()))
                .thenReturn(Optional.of("wf-b-3"));
            ProjectDeploymentWorkflow workflowAVersionThreeRow = createProjectDeploymentWorkflow(
                901L, "wf-a-3", true, List.of(), Map.of());
            ProjectDeploymentWorkflow workflowBVersionThreeRow = createProjectDeploymentWorkflow(
                902L, "wf-b-3", false, List.of(), Map.of());

            lenient().when(projectDeploymentWorkflowService.fetchProjectDeploymentWorkflow(200L, "wf-a-3"))
                .thenReturn(Optional.of(workflowAVersionThreeRow));
            lenient().when(projectDeploymentWorkflowService.fetchProjectDeploymentWorkflow(200L, "wf-b-3"))
                .thenReturn(Optional.of(workflowBVersionThreeRow));
            lenient().when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(901L))
                .thenReturn(workflowAVersionThreeRow);
            lenient().when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(902L))
                .thenReturn(workflowBVersionThreeRow);

            lenient().when(componentConnectionFacade.getWorkflowComponentConnections("wf-a-2"))
                .thenReturn(List.of(slackSlot("slack")));
            lenient().when(componentConnectionFacade.getWorkflowComponentConnections("wf-a-3"))
                .thenReturn(List.of(slackSlot("slack")));

            Workflow workflowWithoutInputs = mock(Workflow.class);

            lenient().when(workflowWithoutInputs.getInputs())
                .thenReturn(List.of());
            lenient().when(workflowService.getWorkflow(any()))
                .thenReturn(workflowWithoutInputs);
            lenient().when(projectWorkflowService.getProjectWorkflowUuid(eq(200L), any()))
                .thenReturn(WORKFLOW_A_UUID.toString());
        }

        @Test
        void testUpdateProjectVersionRepointsEndpointsByWorkflowUuid() {
            stubVersionThreeWorkflows(true);

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false);

            List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = capturedProjectDeploymentWorkflows();

            assertThat(projectDeployment.getProjectVersion()).isEqualTo(3);
            assertThat(projectDeploymentWorkflows).extracting(ProjectDeploymentWorkflow::getWorkflowId)
                .containsExactly("wf-a-3", "wf-b-3");

            ProjectDeploymentWorkflow workflowARow = projectDeploymentWorkflows.get(0);

            assertThat(workflowARow.getConnections()).containsExactly(workflowAConnection);
            assertThat(workflowARow.getInputs()).isEqualTo(Map.of("region", "eu"));
            assertThat(workflowARow.isEnabled()).isTrue();
            assertThat(workflowARow.getProjectDeploymentId()).isEqualTo(200L);

            ProjectDeploymentWorkflow workflowBRow = projectDeploymentWorkflows.get(1);

            assertThat(workflowBRow.getConnections()).isEmpty();
            assertThat(workflowBRow.getInputs()).isEqualTo(Map.of("limit", 10));
            assertThat(workflowBRow.isEnabled()).isFalse();

            ArgumentCaptor<ApiCollectionEndpoint> apiCollectionEndpointCaptor =
                ArgumentCaptor.forClass(ApiCollectionEndpoint.class);

            verify(apiCollectionEndpointService, times(2))
                .update(apiCollectionEndpointCaptor.capture());

            assertThat(apiCollectionEndpointCaptor.getAllValues())
                .extracting(ApiCollectionEndpoint::getId, ApiCollectionEndpoint::getProjectDeploymentWorkflowId)
                .containsExactly(
                    tuple(501L, 901L), tuple(502L, 902L));

            verify(apiCollectionService, never()).update(any(ApiCollection.class));
            verify(apiCollectionAuditPublisher).publish(
                ApiCollectionAuditEvent.API_COLLECTION_PROJECT_VERSION_UPDATED, 100L, Map.of("from", 2, "to", 3));

            assertThat(apiCollectionVersionUpdateDTO.apiCollection()
                .collectionVersion()).isEqualTo(1);
            assertThat(apiCollectionVersionUpdateDTO.removedEndpointNames()).isEmpty();
            assertThat(apiCollectionVersionUpdateDTO.missingConnections()).isEmpty();
            assertThat(apiCollectionVersionUpdateDTO.disabledEndpointNames()).isEmpty();
        }

        @Test
        void testUpdateProjectVersionRefusesWhenWorkflowRemoved() {
            stubVersionThreeWorkflows(false);

            assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("createOrder")
                .satisfies(exception -> {
                    ConfigurationException configurationException = (ConfigurationException) exception;

                    assertThat(configurationException.getErrorKey())
                        .isEqualTo(ApiCollectionErrorType.ENDPOINT_WORKFLOW_REMOVED.getErrorKey());
                    Map<String, ?> inputParameters = configurationException.getInputParameters();

                    assertThat(inputParameters.get("endpointNames")).isEqualTo(List.of("createOrder"));
                });

            verify(projectDeploymentFacade, never()).updateProjectDeployment(any(), any(), any());
            verify(apiCollectionEndpointService, never()).delete(any(Long.class));
            verify(apiCollectionEndpointService, never()).update(any());
        }

        @Test
        void testUpdateProjectVersionRemovesEndpointWhenConfirmed() {
            stubVersionThreeWorkflows(false);

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, true);

            InOrder deleteBeforeMove = inOrder(apiCollectionEndpointService, projectDeploymentFacade);

            deleteBeforeMove.verify(apiCollectionEndpointService)
                .delete(502L);
            deleteBeforeMove.verify(projectDeploymentFacade)
                .updateProjectDeployment(any(), any(), any());

            verify(apiCollectionEndpointService, never()).delete(501L);

            assertThat(capturedProjectDeploymentWorkflows()).extracting(ProjectDeploymentWorkflow::getWorkflowId)
                .containsExactly("wf-a-3");
            assertThat(apiCollectionVersionUpdateDTO.removedEndpointNames()).containsExactly("createOrder");
        }

        @Test
        void testUpdateProjectVersionRefusesSameOrOlderVersion() {
            for (int projectVersion : List.of(1, 2)) {
                assertThatThrownBy(
                    () -> apiCollectionFacade.updateApiCollectionProjectVersion(100L, projectVersion, false))
                        .isInstanceOf(ConfigurationException.class)
                        .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
                        .isEqualTo(ApiCollectionErrorType.PROJECT_VERSION_INVALID.getErrorKey());
            }

            verify(projectDeploymentFacade, never()).updateProjectDeployment(any(), any(), any());
            verify(apiCollectionEndpointService, never()).delete(any(Long.class));
            verify(apiCollectionEndpointService, never()).update(any());
        }

        @Test
        void testUpdateProjectVersionRefusesUnpublishedVersion() {
            for (int projectVersion : List.of(4, 9)) {
                assertThatThrownBy(
                    () -> apiCollectionFacade.updateApiCollectionProjectVersion(100L, projectVersion, false))
                        .isInstanceOf(ConfigurationException.class)
                        .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
                        .isEqualTo(ApiCollectionErrorType.PROJECT_VERSION_INVALID.getErrorKey());
            }

            verify(projectDeploymentFacade, never()).updateProjectDeployment(any(), any(), any());
            verify(apiCollectionEndpointService, never()).delete(any(Long.class));
            verify(apiCollectionEndpointService, never()).update(any());
        }

        @Test
        void testUpdateProjectVersionReportsMissingConnections() {
            stubVersionThreeWorkflows(true);

            when(componentConnectionFacade.getWorkflowComponentConnections("wf-a-3"))
                .thenReturn(
                    List.of(
                        slackSlot("slack"), new ComponentConnection("gmail", 1, "sendEmail", "gmail", true),
                        new ComponentConnection("jira", 1, "createIssue", "jira", false)));

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false);

            assertThat(apiCollectionVersionUpdateDTO.missingConnections())
                .containsExactly(new MissingConnection("listOrders", "sendEmail", "gmail", "gmail"));
            assertThat(apiCollectionVersionUpdateDTO.disabledEndpointNames()).containsExactly("listOrders");
            assertThat(capturedProjectDeploymentWorkflows().get(0)
                .isEnabled()).isFalse();
        }

        @Test
        void testUpdateProjectVersionDropsConnectionWhoseSlotWasRemoved() {
            stubVersionThreeWorkflows(true);

            when(componentConnectionFacade.getWorkflowComponentConnections("wf-a-3")).thenReturn(List.of());

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false);

            ProjectDeploymentWorkflow workflowARow = capturedProjectDeploymentWorkflows().get(0);

            assertThat(workflowARow.getConnections()).isEmpty();
            assertThat(workflowARow.isEnabled()).isTrue();
            assertThat(apiCollectionVersionUpdateDTO.missingConnections()).isEmpty();
            assertThat(apiCollectionVersionUpdateDTO.disabledEndpointNames()).isEmpty();
        }

        @Test
        void testUpdateProjectVersionDropsConnectionWhoseSlotChangedComponent() {
            stubVersionThreeWorkflows(true);

            when(componentConnectionFacade.getWorkflowComponentConnections("wf-a-3"))
                .thenReturn(List.of(slackSlot("discord")));

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false);

            ProjectDeploymentWorkflow workflowARow = capturedProjectDeploymentWorkflows().get(0);

            assertThat(workflowARow.getConnections()).isEmpty();
            assertThat(workflowARow.isEnabled()).isFalse();
            assertThat(apiCollectionVersionUpdateDTO.missingConnections())
                .containsExactly(new MissingConnection("listOrders", "postMessage", "slack", "discord"));
            assertThat(apiCollectionVersionUpdateDTO.disabledEndpointNames()).containsExactly("listOrders");
        }

        @Test
        void testUpdateProjectVersionDisablesWorkflowMissingARequiredInput() {
            stubVersionThreeWorkflows(true);

            Workflow workflowWithRequiredInput = mock(Workflow.class);

            when(workflowWithRequiredInput.getInputs())
                .thenReturn(
                    List.of(
                        new Workflow.Input("region", "Region", "string", true, Map.of()),
                        new Workflow.Input("tenant", "Tenant", "string", true, Map.of()),
                        new Workflow.Input("locale", "Locale", "string", false, Map.of())));
            when(workflowService.getWorkflow("wf-a-3")).thenReturn(workflowWithRequiredInput);

            ApiCollectionVersionUpdateDTO apiCollectionVersionUpdateDTO =
                apiCollectionFacade.updateApiCollectionProjectVersion(100L, 3, false);

            ProjectDeploymentWorkflow workflowARow = capturedProjectDeploymentWorkflows().get(0);

            assertThat(workflowARow.isEnabled()).isFalse();
            assertThat(workflowARow.getInputs()).isEqualTo(Map.of("region", "eu"));
            assertThat(apiCollectionVersionUpdateDTO.missingInputs())
                .containsExactly(new MissingInput("listOrders", "tenant"));
            assertThat(apiCollectionVersionUpdateDTO.disabledEndpointNames()).containsExactly("listOrders");
        }

        private List<ProjectDeploymentWorkflow> capturedProjectDeploymentWorkflows() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProjectDeploymentWorkflow>> projectDeploymentWorkflowsCaptor =
                ArgumentCaptor.forClass(List.class);

            verify(projectDeploymentFacade).updateProjectDeployment(
                eq(projectDeployment), projectDeploymentWorkflowsCaptor.capture(), eq(List.of()));

            return projectDeploymentWorkflowsCaptor.getValue();
        }

        private void stubVersionThreeWorkflows(boolean includeWorkflowB) {
            List<ProjectWorkflow> projectWorkflows = includeWorkflowB
                ? List.of(
                    new ProjectWorkflow(42L, 3, "wf-a-3", WORKFLOW_A_UUID),
                    new ProjectWorkflow(42L, 3, "wf-b-3", WORKFLOW_B_UUID))
                : List.of(new ProjectWorkflow(42L, 3, "wf-a-3", WORKFLOW_A_UUID));

            when(projectWorkflowService.getProjectWorkflows(42L, 3)).thenReturn(projectWorkflows);
        }

        private static ProjectDeploymentWorkflow createProjectDeploymentWorkflow(
            long id, String workflowId, boolean enabled, List<ProjectDeploymentWorkflowConnection> connections,
            Map<String, ?> inputs) {

            ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

            projectDeploymentWorkflow.setConnections(connections);
            projectDeploymentWorkflow.setEnabled(enabled);
            projectDeploymentWorkflow.setId(id);
            projectDeploymentWorkflow.setInputs(inputs);
            projectDeploymentWorkflow.setProjectDeploymentId(200L);
            projectDeploymentWorkflow.setWorkflowId(workflowId);

            return projectDeploymentWorkflow;
        }

        private static ComponentConnection slackSlot(String componentName) {
            return new ComponentConnection(componentName, 1, "postMessage", "slack", true);
        }

        private static ProjectVersion publishedProjectVersion(int version) {
            return new ProjectVersion(version, ProjectVersion.Status.PUBLISHED.ordinal(), null, null);
        }
    }

    private ApiCollectionEndpoint capturedUpdatedEndpoint() {
        ArgumentCaptor<ApiCollectionEndpoint> apiCollectionEndpointCaptor =
            ArgumentCaptor.forClass(ApiCollectionEndpoint.class);

        verify(apiCollectionEndpointService).update(apiCollectionEndpointCaptor.capture());

        return apiCollectionEndpointCaptor.getValue();
    }

    @Test
    void testDeleteApiCollectionEndpointDeletesAndPublishesAuditEvent() {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(7L);
        apiCollectionEndpoint.setName("list-invoices");
        apiCollectionEndpoint.setPath("invoices");

        when(apiCollectionEndpointService.getOpenApiEndpoint(7L)).thenReturn(apiCollectionEndpoint);

        apiCollectionFacade.deleteApiCollectionEndpoint(7L);

        verify(apiCollectionEndpointService).delete(7L);
        verify(apiCollectionAuditPublisher).publish(
            ApiCollectionAuditEvent.API_COLLECTION_ENDPOINT_DELETED, 100L,
            Map.of("httpMethod", HttpMethod.GET, "name", "list-invoices", "path", "invoices"));
    }

    @Test
    void testEnableApiCollectionEndpointEnablesTheWorkflowOnTheBackingDeployment() {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(7L);
        apiCollectionEndpoint.setName("list-invoices");
        apiCollectionEndpoint.setPath("invoices");
        apiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setEnabled(false);
        projectDeploymentWorkflow.setProjectDeploymentId(200L);
        projectDeploymentWorkflow.setWorkflowId("workflow-1");

        when(apiCollectionEndpointService.getOpenApiEndpoint(7L)).thenReturn(apiCollectionEndpoint);
        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(901L))
            .thenReturn(projectDeploymentWorkflow);
        when(projectWorkflowService.getProjectWorkflowUuid(200L, "workflow-1")).thenReturn("uuid-1");
        doAnswer(invocation -> {
            projectDeploymentWorkflow.setEnabled(invocation.getArgument(2));

            return null;
        }).when(projectDeploymentFacade)
            .enableProjectDeploymentWorkflow(200L, "workflow-1", true);

        ApiCollectionEndpointDTO apiCollectionEndpointDTO = apiCollectionFacade.enableApiCollectionEndpoint(7L, true);

        verify(projectDeploymentFacade).enableProjectDeploymentWorkflow(200L, "workflow-1", true);

        assertThat(apiCollectionEndpointDTO.id()).isEqualTo(7L);
        assertThat(apiCollectionEndpointDTO.enabled()).isTrue();
    }

    @Test
    void testGetApiCollectionEndpointsOrdersByPathThenMethod() {
        ApiCollectionEndpoint postInvoices = createEndpoint(1L, "invoices", HttpMethod.POST, 901L);
        ApiCollectionEndpoint getOrders = createEndpoint(2L, "orders", HttpMethod.GET, 902L);
        ApiCollectionEndpoint getInvoices = createEndpoint(3L, "invoices", HttpMethod.GET, 903L);

        when(apiCollectionEndpointService.getApiEndpoints(100L))
            .thenReturn(List.of(postInvoices, getOrders, getInvoices));

        for (long projectDeploymentWorkflowId : List.of(901L, 902L, 903L)) {
            ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

            projectDeploymentWorkflow.setProjectDeploymentId(200L);
            projectDeploymentWorkflow.setWorkflowId("workflow-" + projectDeploymentWorkflowId);

            when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(projectDeploymentWorkflowId))
                .thenReturn(projectDeploymentWorkflow);
            when(projectWorkflowService.getProjectWorkflowUuid(200L, "workflow-" + projectDeploymentWorkflowId))
                .thenReturn("uuid-" + projectDeploymentWorkflowId);
        }

        List<ApiCollectionEndpointDTO> apiCollectionEndpointDTOs = apiCollectionFacade.getApiCollectionEndpoints(100L);

        assertThat(apiCollectionEndpointDTOs).extracting(ApiCollectionEndpointDTO::id)
            .containsExactly(3L, 1L, 2L);
        assertThat(apiCollectionEndpointDTOs).extracting(ApiCollectionEndpointDTO::workflowUuid)
            .containsExactly("uuid-903", "uuid-901", "uuid-902");
    }

    private static ApiCollectionEndpoint createEndpoint(
        long id, String path, HttpMethod httpMethod, long projectDeploymentWorkflowId) {

        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);
        apiCollectionEndpoint.setHttpMethod(httpMethod);
        apiCollectionEndpoint.setId(id);
        apiCollectionEndpoint.setName(path);
        apiCollectionEndpoint.setPath(path);
        apiCollectionEndpoint.setProjectDeploymentWorkflowId(projectDeploymentWorkflowId);

        return apiCollectionEndpoint;
    }

    @Test
    void testGetApiCollectionEndpointReturnsTheEndpointWithItsWorkflowUuid() {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(7L);
        apiCollectionEndpoint.setName("list-invoices");
        apiCollectionEndpoint.setPath("invoices");
        apiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setProjectDeploymentId(200L);
        projectDeploymentWorkflow.setWorkflowId("workflow-1");

        when(apiCollectionEndpointService.getOpenApiEndpoint(7L)).thenReturn(apiCollectionEndpoint);
        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(901L))
            .thenReturn(projectDeploymentWorkflow);
        when(projectWorkflowService.getProjectWorkflowUuid(200L, "workflow-1")).thenReturn("uuid-1");

        ApiCollectionEndpointDTO apiCollectionEndpointDTO = apiCollectionFacade.getApiCollectionEndpoint(7L);

        assertThat(apiCollectionEndpointDTO.name()).isEqualTo("list-invoices");
        assertThat(apiCollectionEndpointDTO.workflowUuid()).isEqualTo("uuid-1");
    }

    @Test
    void testGetApiCollectionEndpointReturnsConnectionsAndInputs() {
        stubEndpointWorkflow(false, 4);

        ApiCollectionEndpointDTO apiCollectionEndpointDTO = apiCollectionFacade.getApiCollectionEndpoint(7L);

        assertThat(apiCollectionEndpointDTO.connections()).hasSize(1);
        assertThat(apiCollectionEndpointDTO.connections()
            .get(0)
            .getConnectionId()).isEqualTo(61L);
        assertThat(apiCollectionEndpointDTO.inputs()
            .get("region")).isEqualTo("eu");
    }

    @Test
    void testUpdateApiCollectionEndpointWorkflowKeepsEnabledAndVersion() {
        ProjectDeploymentWorkflow projectDeploymentWorkflow = stubEndpointWorkflow(true, 4);

        ProjectDeploymentWorkflowConnection projectDeploymentWorkflowConnection =
            new ProjectDeploymentWorkflowConnection(62L, "key", "node");

        apiCollectionFacade.updateApiCollectionEndpointWorkflow(
            7L, List.of(projectDeploymentWorkflowConnection), Map.of("region", "us"));

        ArgumentCaptor<ProjectDeploymentWorkflow> captor = ArgumentCaptor.forClass(ProjectDeploymentWorkflow.class);

        verify(projectDeploymentFacade).updateProjectDeploymentWorkflow(captor.capture());

        ProjectDeploymentWorkflow updatedProjectDeploymentWorkflow = captor.getValue();

        assertThat(updatedProjectDeploymentWorkflow.getId()).isEqualTo(901L);
        assertThat(updatedProjectDeploymentWorkflow.getConnections()).containsExactly(
            projectDeploymentWorkflowConnection);
        assertThat(updatedProjectDeploymentWorkflow.getInputs()
            .get("region")).isEqualTo("us");
        assertThat(updatedProjectDeploymentWorkflow.isEnabled()).isEqualTo(projectDeploymentWorkflow.isEnabled());
        assertThat(updatedProjectDeploymentWorkflow.isEnabled()).isTrue();
        assertThat(updatedProjectDeploymentWorkflow.getVersion()).isEqualTo(4);
    }

    private ProjectDeploymentWorkflow stubEndpointWorkflow(boolean enabled, int version) {
        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(100L);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(7L);
        apiCollectionEndpoint.setName("list-invoices");
        apiCollectionEndpoint.setPath("invoices");
        apiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setConnections(List.of(new ProjectDeploymentWorkflowConnection(61L, "key", "node")));
        projectDeploymentWorkflow.setEnabled(enabled);
        projectDeploymentWorkflow.setId(901L);
        projectDeploymentWorkflow.setInputs(Map.of("region", "eu"));
        projectDeploymentWorkflow.setProjectDeploymentId(200L);
        projectDeploymentWorkflow.setVersion(version);
        projectDeploymentWorkflow.setWorkflowId("workflow-1");

        when(apiCollectionEndpointService.getOpenApiEndpoint(7L)).thenReturn(apiCollectionEndpoint);
        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(901L))
            .thenReturn(projectDeploymentWorkflow);
        lenient().when(projectWorkflowService.getProjectWorkflowUuid(200L, "workflow-1"))
            .thenReturn("uuid-1");

        return projectDeploymentWorkflow;
    }

    /**
     * Stubs the whole update path so the endpoint resolves onto the project deployment workflow
     * {@code resolvedProjectDeploymentWorkflowId}. The persisted row points at 901, so passing 902 models a source
     * endpoint that moved onto another workflow.
     */
    private void stubEndpointUpdate(long resolvedProjectDeploymentWorkflowId) {
        ApiCollectionEndpoint persistedApiCollectionEndpoint = new ApiCollectionEndpoint();

        persistedApiCollectionEndpoint.setId(501L);
        persistedApiCollectionEndpoint.setApiCollectionId(100L);
        persistedApiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        persistedApiCollectionEndpoint.setName("getOrders");
        persistedApiCollectionEndpoint.setPath("orders");
        persistedApiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

        when(apiCollectionEndpointService.getOpenApiEndpoint(501L)).thenReturn(persistedApiCollectionEndpoint);

        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setId(100L);
        apiCollection.setProjectDeploymentId(200L);

        when(apiCollectionService.getApiCollection(100L)).thenReturn(apiCollection);
        when(projectWorkflowService.fetchProjectWorkflowWorkflowId(eq(200L), any()))
            .thenReturn(Optional.of("workflow-1"));

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setId(resolvedProjectDeploymentWorkflowId);
        projectDeploymentWorkflow.setProjectDeploymentId(200L);
        projectDeploymentWorkflow.setWorkflowId("workflow-1");

        when(projectDeploymentWorkflowService.fetchProjectDeploymentWorkflow(200L, "workflow-1"))
            .thenReturn(Optional.of(projectDeploymentWorkflow));
        when(apiCollectionEndpointService.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(resolvedProjectDeploymentWorkflowId))
            .thenReturn(projectDeploymentWorkflow);
        when(projectWorkflowService.getProjectWorkflowUuid(200L, "workflow-1")).thenReturn("wf-uuid");
    }

    private static ApiCollectionEndpointDTO endpointDTO(String workflowUuid) {
        return new ApiCollectionEndpointDTO(
            100L, null, null, true, HttpMethod.GET, 501L, null, null, "listOrders", "orders", 901L, 0, workflowUuid);
    }

    private void stubPersistedApiCollectionAndDeployment() {
        ApiCollection persistedApiCollection = new ApiCollection();

        persistedApiCollection.setId(100L);
        persistedApiCollection.setProjectDeploymentId(200L);

        when(apiCollectionService.getApiCollection(100L)).thenReturn(persistedApiCollection);

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(200L);
        projectDeployment.setProjectId(42L);
        projectDeployment.setProjectVersion(3);
        projectDeployment.setEnvironment(Environment.STAGING);

        when(projectDeploymentService.getProjectDeployment(200L)).thenReturn(projectDeployment);

        Project project = new Project();

        project.setWorkspaceId(5L);

        when(projectService.getProjectDeploymentProject(200L)).thenReturn(project);
    }

    private static ApiCollectionDTO createApiCollectionDTO() {
        return new ApiCollectionDTO(
            1, "billing-v1", null, null, null, false, List.of(), Environment.STAGING, null, null, null, "billing",
            null, 42L, null, 0, 3, List.of(), 0, null);
    }

    private static ApiCollectionDTO updateApiCollectionDTO(String name) {
        // id=100L matches the persisted collection stubbed by stubPersistedApiCollectionAndDeployment();
        // projectDeploymentId=0L mirrors production reality — the REST model marks projectDeploymentId
        // readOnly, so a PUT body never carries it and toApiCollection() falls back to the primitive default.
        return new ApiCollectionDTO(
            1, "billing-v1", null, null, null, false, List.of(), Environment.STAGING, 100L, null, null, name,
            null, 42L, null, 0, 3, List.of(), 0, null);
    }
}
