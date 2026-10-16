/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.repository.ProjectDeploymentRepository;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollection;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint.HttpMethod;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionEndpointDTO;
import com.bytechef.ee.automation.apiplatform.configuration.exception.ApiCollectionErrorType;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.exception.UserNotFoundException;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs the {@code ApiCollectionEndpoint} guards on {@link ApiCollectionFacadeImpl} through real Spring method security,
 * the production expression root and permission evaluator, the EE {@code PermissionServiceImpl} and the production API
 * collection and endpoint resolvers (see {@link ApiCollectionFacadeIntTestConfiguration}). Enabling an endpoint and
 * setting its connections also cross the gates of the real {@code ProjectDeploymentFacadeImpl}. Every denial is paired
 * with an allowance on the same guard.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ActiveProfiles("testint")
@SpringBootTest(
    classes = ApiCollectionFacadeIntTestConfiguration.class, properties = "bytechef.edition=ee")
class ApiCollectionFacadeIntTest {

    private static final long WORKSPACE = 1L;
    private static final long OTHER_WORKSPACE = 2L;
    private static final long API_COLLECTION = 10L;
    private static final long API_COLLECTION_ENDPOINT = 30L;
    private static final long CONNECTION = 70L;
    private static final long EDITABLE_FOREIGN_API_COLLECTION = 12L;
    private static final long EDITABLE_FOREIGN_API_COLLECTION_ENDPOINT = 42L;
    private static final long EDITABLE_FOREIGN_PROJECT_DEPLOYMENT = 220L;
    private static final long OTHER_WORKSPACE_API_COLLECTION = 11L;
    private static final long OTHER_WORKSPACE_API_COLLECTION_ENDPOINT = 41L;
    private static final long OTHER_WORKSPACE_PROJECT = 310L;
    private static final long OTHER_WORKSPACE_PROJECT_DEPLOYMENT = 210L;
    private static final long PROJECT = 300L;
    private static final long PROJECT_DEPLOYMENT = 200L;
    private static final long PROJECT_DEPLOYMENT_WORKFLOW = 901L;

    private static final String EDITOR = "editor";
    private static final String OUTSIDER = "outsider";
    private static final String VIEWER = "viewer";

    private static final Map<String, Long> USER_IDS = Map.of(EDITOR, 101L, OUTSIDER, 102L, VIEWER, 103L);
    private static final Map<String, WorkspaceRole> ROLES = Map.of(
        EDITOR + "@" + WORKSPACE, WorkspaceRole.EDITOR,
        OUTSIDER + "@" + OTHER_WORKSPACE, WorkspaceRole.ADMIN,
        VIEWER + "@" + WORKSPACE, WorkspaceRole.VIEWER);

    @Autowired
    private ApiCollectionEndpointService apiCollectionEndpointService;

    @Autowired
    private ApiCollectionFacade apiCollectionFacade;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Autowired
    private ProjectDeploymentRepository projectDeploymentRepository;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkspaceUserRepository workspaceUserRepository;

    @BeforeEach
    void beforeEach() {
        when(userService.getUser(anyString())).thenAnswer(invocation -> {
            String login = invocation.getArgument(0);

            Long userId = USER_IDS.get(login);

            if (userId == null) {
                throw new UserNotFoundException();
            }

            User user = new User();

            user.setId(userId);
            user.setLogin(login);

            return user;
        });

        when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironmentIsNull(anyLong(), anyLong()))
            .thenAnswer(invocation -> {
                long userId = invocation.getArgument(0);
                long workspaceId = invocation.getArgument(1);

                return USER_IDS.entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() == userId)
                    .map(entry -> ROLES.get(entry.getKey() + "@" + workspaceId))
                    .filter(role -> role != null)
                    .findFirst()
                    .map(role -> new WorkspaceUser(userId, workspaceId, role.ordinal()));
            });
        when(workspaceUserRepository.findByUserIdAndWorkspaceIdAndEnvironment(anyLong(), anyLong(), anyInt()))
            .thenReturn(Optional.empty());

        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setId(API_COLLECTION);
        apiCollection.setProjectDeploymentId(PROJECT_DEPLOYMENT);

        when(apiCollectionService.fetchApiCollection(API_COLLECTION)).thenReturn(Optional.of(apiCollection));

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setId(PROJECT_DEPLOYMENT);
        projectDeployment.setProjectId(PROJECT);

        when(projectDeploymentRepository.findById(PROJECT_DEPLOYMENT)).thenReturn(Optional.of(projectDeployment));
        when(projectDeploymentService.fetchProjectDeployment(PROJECT_DEPLOYMENT))
            .thenReturn(Optional.of(projectDeployment));

        Project project = new Project();

        project.setId(PROJECT);
        project.setWorkspaceId(WORKSPACE);

        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        when(projectService.fetchProject(PROJECT)).thenReturn(Optional.of(project));

        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(API_COLLECTION);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(API_COLLECTION_ENDPOINT);
        apiCollectionEndpoint.setName("list");
        apiCollectionEndpoint.setPath("items");

        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(API_COLLECTION_ENDPOINT))
            .thenReturn(Optional.of(apiCollectionEndpoint));
        when(apiCollectionEndpointService.getOpenApiEndpoint(API_COLLECTION_ENDPOINT))
            .thenReturn(apiCollectionEndpoint);
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class GetApiCollectionEndpoints {

        @Test
        void testViewerCanListEndpoints() {
            authenticate(VIEWER);

            assertThatCode(() -> apiCollectionFacade.getApiCollectionEndpoints(API_COLLECTION))
                .doesNotThrowAnyException();
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedListEndpoints() {
            authenticate(OUTSIDER);

            assertThatThrownBy(() -> apiCollectionFacade.getApiCollectionEndpoints(API_COLLECTION))
                .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).getApiEndpoints(anyLong());
        }
    }

    @Nested
    class DeleteApiCollectionEndpoint {

        @Test
        void testEditorCanDeleteEndpoint() {
            authenticate(EDITOR);

            assertThatCode(() -> apiCollectionFacade.deleteApiCollectionEndpoint(API_COLLECTION_ENDPOINT))
                .doesNotThrowAnyException();

            verify(apiCollectionEndpointService).delete(API_COLLECTION_ENDPOINT);
        }

        @Test
        void testViewerIsDeniedDeleteEndpoint() {
            authenticate(VIEWER);

            assertThatThrownBy(() -> apiCollectionFacade.deleteApiCollectionEndpoint(API_COLLECTION_ENDPOINT))
                .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).delete(anyLong());
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedDeleteEndpoint() {
            authenticate(OUTSIDER);

            assertThatThrownBy(() -> apiCollectionFacade.deleteApiCollectionEndpoint(API_COLLECTION_ENDPOINT))
                .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).delete(anyLong());
        }

        @Test
        void testUnknownEndpointIsDenied() {
            authenticate(EDITOR);

            assertThatThrownBy(() -> apiCollectionFacade.deleteApiCollectionEndpoint(999L))
                .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    class EnableApiCollectionEndpoint {

        @BeforeEach
        void beforeEach() {
            stubProjectDeploymentWorkflow();

            Workflow workflow = mock(Workflow.class);

            when(workflow.getExtensions(anyString(), any(), any())).thenReturn(List.of());
            when(workflow.getTasks(true)).thenReturn(List.of());
            when(workflowService.getWorkflow("workflow-1")).thenReturn(workflow);
        }

        @Test
        void testEditorCanEnableEndpointThroughTheDeploymentGate() {
            authenticate(EDITOR);

            assertThatCode(() -> apiCollectionFacade.enableApiCollectionEndpoint(API_COLLECTION_ENDPOINT, true))
                .doesNotThrowAnyException();

            verify(projectDeploymentWorkflowService).updateEnabled(PROJECT_DEPLOYMENT_WORKFLOW, true);
        }

        @Test
        void testViewerIsDeniedEnableEndpoint() {
            authenticate(VIEWER);

            assertThatThrownBy(() -> apiCollectionFacade.enableApiCollectionEndpoint(API_COLLECTION_ENDPOINT, true))
                .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentWorkflowService, never()).updateEnabled(anyLong(), eq(true));
        }

        @Test
        void testViewerIsDeniedTheDeploymentGateItself() {
            authenticate(VIEWER);

            assertThatThrownBy(
                () -> projectDeploymentFacade.enableProjectDeploymentWorkflow(PROJECT_DEPLOYMENT, "workflow-1", true))
                    .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentWorkflowService, never()).updateEnabled(anyLong(), eq(true));
        }
    }

    @Nested
    class UpdateApiCollectionEndpointWorkflow {

        @BeforeEach
        void beforeEach() {
            stubProjectDeploymentWorkflow();
        }

        @Test
        void testEditorCanSetEndpointConnectionThroughTheDeploymentGate() {
            stubConnection(Environment.PRODUCTION);

            authenticate(EDITOR);

            assertThatCode(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(connection()), Map.of()))
                        .doesNotThrowAnyException();

            ArgumentCaptor<ProjectDeploymentWorkflow> projectDeploymentWorkflowArgumentCaptor =
                ArgumentCaptor.forClass(ProjectDeploymentWorkflow.class);

            verify(projectDeploymentWorkflowService).update(projectDeploymentWorkflowArgumentCaptor.capture());

            ProjectDeploymentWorkflow projectDeploymentWorkflow = projectDeploymentWorkflowArgumentCaptor.getValue();

            assertThat(projectDeploymentWorkflow.getConnections())
                .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
                .containsExactly(CONNECTION);
        }

        @Test
        void testEditorIsRefusedAConnectionFromAnotherEnvironment() {
            stubConnection(Environment.DEVELOPMENT);

            authenticate(EDITOR);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(connection()), Map.of()))
                        .isInstanceOf(ConfigurationException.class);

            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        @Test
        void testViewerIsDeniedUpdateEndpointWorkflow() {
            authenticate(VIEWER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(connection()), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        @Test
        void testViewerIsDeniedTheDeploymentGateItself() {
            authenticate(VIEWER);

            ProjectDeploymentWorkflow projectDeploymentWorkflow =
                projectDeploymentWorkflowService.getProjectDeploymentWorkflow(PROJECT_DEPLOYMENT_WORKFLOW);

            assertThatThrownBy(() -> projectDeploymentFacade.updateProjectDeploymentWorkflow(projectDeploymentWorkflow))
                .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedUpdateEndpointWorkflow() {
            authenticate(OUTSIDER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        private static ProjectDeploymentWorkflowConnection connection() {
            return new ProjectDeploymentWorkflowConnection(CONNECTION, "connection", "trigger_1");
        }

        private void stubConnection(Environment environment) {
            Connection connection = new Connection();

            connection.setEnvironmentId(environment.ordinal());
            connection.setId(CONNECTION);
            connection.setName("connection");

            when(connectionService.getConnection(CONNECTION)).thenReturn(connection);
        }
    }

    @Nested
    class CreateApiCollectionEndpoint {

        @Test
        void testEditorPassesTheGate() {
            ApiCollection apiCollection = new ApiCollection();

            apiCollection.setId(API_COLLECTION);
            apiCollection.setProjectDeploymentId(PROJECT_DEPLOYMENT);

            when(apiCollectionService.getApiCollection(API_COLLECTION)).thenReturn(apiCollection);

            authenticate(EDITOR);

            assertThatThrownBy(
                () -> apiCollectionFacade.createApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
                    .isEqualTo(ApiCollectionErrorType.WORKFLOW_NOT_FOUND.getErrorKey());
        }

        @Test
        void testViewerIsDeniedCreateEndpoint() {
            authenticate(VIEWER);

            assertThatThrownBy(
                () -> apiCollectionFacade.createApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).create(any());
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedCreateEndpoint() {
            authenticate(OUTSIDER);

            assertThatThrownBy(
                () -> apiCollectionFacade.createApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).create(any());
        }

        private static ApiCollectionEndpointDTO endpointDTO() {
            return new ApiCollectionEndpointDTO(
                API_COLLECTION, null, null, false, HttpMethod.GET, null, null, null, "list", "items", 0L, 0, "uuid");
        }
    }

    @Nested
    class UpdateApiCollectionEndpoint {

        @Test
        void testEditorPassesTheGate() {
            ApiCollection apiCollection = new ApiCollection();

            apiCollection.setId(API_COLLECTION);
            apiCollection.setProjectDeploymentId(PROJECT_DEPLOYMENT);

            when(apiCollectionService.getApiCollection(API_COLLECTION)).thenReturn(apiCollection);

            authenticate(EDITOR);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(ConfigurationException.class)
                    .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
                    .isEqualTo(ApiCollectionErrorType.WORKFLOW_NOT_FOUND.getErrorKey());
        }

        @Test
        void testViewerIsDeniedUpdateEndpoint() {
            authenticate(VIEWER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).update(any());
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedUpdateEndpoint() {
            authenticate(OUTSIDER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(endpointDTO(), List.of(), Map.of()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).update(any());
        }

        @Test
        void testEditorIsDeniedAnEndpointOfAnotherWorkspaceNamedUnderAnEditableCollection() {
            stubEndpoint(
                OTHER_WORKSPACE_API_COLLECTION_ENDPOINT, OTHER_WORKSPACE_API_COLLECTION,
                OTHER_WORKSPACE_PROJECT_DEPLOYMENT, OTHER_WORKSPACE_PROJECT, OTHER_WORKSPACE);

            authenticate(EDITOR);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(
                    foreignEndpointDTO(OTHER_WORKSPACE_API_COLLECTION_ENDPOINT)))
                        .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(
                    foreignEndpointDTO(OTHER_WORKSPACE_API_COLLECTION_ENDPOINT), List.of(), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).update(any());
            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        @Test
        void testEditorIsDeniedAnEndpointOfAnotherEditableCollectionNamedUnderThisCollection() {
            stubEndpoint(
                EDITABLE_FOREIGN_API_COLLECTION_ENDPOINT, EDITABLE_FOREIGN_API_COLLECTION,
                EDITABLE_FOREIGN_PROJECT_DEPLOYMENT, PROJECT, WORKSPACE);

            authenticate(EDITOR);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(
                    foreignEndpointDTO(EDITABLE_FOREIGN_API_COLLECTION_ENDPOINT)))
                        .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpoint(
                    foreignEndpointDTO(EDITABLE_FOREIGN_API_COLLECTION_ENDPOINT), List.of(), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionEndpointService, never()).update(any());
            verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
        }

        private static ApiCollectionEndpointDTO endpointDTO() {
            return new ApiCollectionEndpointDTO(
                API_COLLECTION, null, null, false, HttpMethod.GET, API_COLLECTION_ENDPOINT, null, null, "list",
                "items", 0L, 0, "uuid");
        }

        private static ApiCollectionEndpointDTO foreignEndpointDTO(long apiCollectionEndpointId) {
            return new ApiCollectionEndpointDTO(
                API_COLLECTION, null, null, false, HttpMethod.GET, apiCollectionEndpointId, null, null, "hijacked",
                "hijacked", 0L, 0, null);
        }
    }

    @Nested
    class UpdateApiCollectionProjectVersion {

        @Test
        void testEditorPassesTheGate() {
            ApiCollection apiCollection = new ApiCollection();

            apiCollection.setId(API_COLLECTION);
            apiCollection.setProjectDeploymentId(PROJECT_DEPLOYMENT);

            when(apiCollectionService.getApiCollection(API_COLLECTION)).thenReturn(apiCollection);

            ProjectDeployment projectDeployment = new ProjectDeployment();

            projectDeployment.setId(PROJECT_DEPLOYMENT);
            projectDeployment.setProjectId(PROJECT);
            projectDeployment.setProjectVersion(2);

            when(projectDeploymentService.getProjectDeployment(PROJECT_DEPLOYMENT)).thenReturn(projectDeployment);
            when(projectService.getProject(PROJECT)).thenReturn(new Project());

            authenticate(EDITOR);

            assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionProjectVersion(API_COLLECTION, 3, false))
                .isInstanceOf(ConfigurationException.class)
                .extracting(exception -> ((ConfigurationException) exception).getErrorKey())
                .isEqualTo(ApiCollectionErrorType.PROJECT_VERSION_INVALID.getErrorKey());
        }

        @Test
        void testViewerIsDeniedUpdateProjectVersion() {
            authenticate(VIEWER);

            assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionProjectVersion(API_COLLECTION, 3, false))
                .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionService, never()).getApiCollection(anyLong());
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedUpdateProjectVersion() {
            authenticate(OUTSIDER);

            assertThatThrownBy(() -> apiCollectionFacade.updateApiCollectionProjectVersion(API_COLLECTION, 3, false))
                .isInstanceOf(AccessDeniedException.class);

            verify(apiCollectionService, never()).getApiCollection(anyLong());
        }
    }

    private void stubProjectDeploymentWorkflow() {
        ApiCollectionEndpoint apiCollectionEndpoint = apiCollectionEndpointService.getOpenApiEndpoint(
            API_COLLECTION_ENDPOINT);

        apiCollectionEndpoint.setProjectDeploymentWorkflowId(PROJECT_DEPLOYMENT_WORKFLOW);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setId(PROJECT_DEPLOYMENT_WORKFLOW);
        projectDeploymentWorkflow.setProjectDeploymentId(PROJECT_DEPLOYMENT);
        projectDeploymentWorkflow.setWorkflowId("workflow-1");

        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(PROJECT_DEPLOYMENT_WORKFLOW))
            .thenReturn(projectDeploymentWorkflow);
        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(PROJECT_DEPLOYMENT, "workflow-1"))
            .thenReturn(projectDeploymentWorkflow);

        ProjectDeployment projectDeployment = projectDeploymentService.fetchProjectDeployment(PROJECT_DEPLOYMENT)
            .orElseThrow();

        when(projectDeploymentService.getProjectDeployment(PROJECT_DEPLOYMENT)).thenReturn(projectDeployment);
        when(projectWorkflowService.getProjectWorkflowUuid(PROJECT_DEPLOYMENT, "workflow-1")).thenReturn("uuid");
    }

    private void stubEndpoint(
        long apiCollectionEndpointId, long apiCollectionId, long projectDeploymentId, long projectId,
        long workspaceId) {

        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setId(apiCollectionId);
        apiCollection.setProjectDeploymentId(projectDeploymentId);

        when(apiCollectionService.fetchApiCollection(apiCollectionId)).thenReturn(Optional.of(apiCollection));
        when(apiCollectionService.getApiCollection(apiCollectionId)).thenReturn(apiCollection);

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setId(projectDeploymentId);
        projectDeployment.setProjectId(projectId);

        when(projectDeploymentService.fetchProjectDeployment(projectDeploymentId))
            .thenReturn(Optional.of(projectDeployment));

        Project project = new Project();

        project.setId(projectId);
        project.setWorkspaceId(workspaceId);

        when(projectService.fetchProject(projectId)).thenReturn(Optional.of(project));

        ApiCollectionEndpoint apiCollectionEndpoint = new ApiCollectionEndpoint();

        apiCollectionEndpoint.setApiCollectionId(apiCollectionId);
        apiCollectionEndpoint.setHttpMethod(HttpMethod.GET);
        apiCollectionEndpoint.setId(apiCollectionEndpointId);
        apiCollectionEndpoint.setName("foreign");
        apiCollectionEndpoint.setPath("foreign");
        apiCollectionEndpoint.setProjectDeploymentWorkflowId(apiCollectionEndpointId + 1000);

        when(apiCollectionEndpointService.fetchApiCollectionEndpoint(apiCollectionEndpointId))
            .thenReturn(Optional.of(apiCollectionEndpoint));
        when(apiCollectionEndpointService.getOpenApiEndpoint(apiCollectionEndpointId))
            .thenReturn(apiCollectionEndpoint);
        when(apiCollectionEndpointService.update(any())).thenReturn(apiCollectionEndpoint);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setId(apiCollectionEndpointId + 1000);
        projectDeploymentWorkflow.setProjectDeploymentId(projectDeploymentId);
        projectDeploymentWorkflow.setWorkflowId("foreign-workflow");

        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(apiCollectionEndpointId + 1000))
            .thenReturn(projectDeploymentWorkflow);
    }

    private static void authenticate(String login) {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(login, "n/a",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
