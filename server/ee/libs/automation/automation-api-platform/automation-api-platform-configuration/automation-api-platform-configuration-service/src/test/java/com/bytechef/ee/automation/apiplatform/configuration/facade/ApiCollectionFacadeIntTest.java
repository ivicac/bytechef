/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollection;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollectionEndpoint.HttpMethod;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionEndpointService;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.ee.automation.configuration.domain.WorkspaceUser;
import com.bytechef.ee.automation.configuration.repository.WorkspaceUserRepository;
import com.bytechef.ee.automation.configuration.security.constant.WorkspaceRole;
import com.bytechef.platform.configuration.domain.Environment;
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
 * collection and endpoint resolvers (see {@link ApiCollectionFacadeIntTestConfiguration}). Every denial is paired with
 * an allowance on the same guard.
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
    private static final long PROJECT = 300L;
    private static final long PROJECT_DEPLOYMENT = 200L;

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
    private ProjectDeploymentFacade projectDeploymentFacade;

    @Autowired
    private ProjectDeploymentService projectDeploymentService;

    @Autowired
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @Autowired
    private UserService userService;

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

        when(projectDeploymentService.fetchProjectDeployment(PROJECT_DEPLOYMENT))
            .thenReturn(Optional.of(projectDeployment));

        Project project = new Project();

        project.setId(PROJECT);
        project.setWorkspaceId(WORKSPACE);

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
    class UpdateApiCollectionEndpointWorkflow {

        @BeforeEach
        void beforeEach() {
            ApiCollectionEndpoint apiCollectionEndpoint = apiCollectionEndpointService.getOpenApiEndpoint(
                API_COLLECTION_ENDPOINT);

            apiCollectionEndpoint.setProjectDeploymentWorkflowId(901L);

            ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

            projectDeploymentWorkflow.setId(901L);
            projectDeploymentWorkflow.setProjectDeploymentId(PROJECT_DEPLOYMENT);
            projectDeploymentWorkflow.setWorkflowId("workflow-1");

            when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(901L))
                .thenReturn(projectDeploymentWorkflow);
            when(projectWorkflowService.getProjectWorkflowUuid(PROJECT_DEPLOYMENT, "workflow-1")).thenReturn("uuid");
        }

        @Test
        void testEditorCanUpdateEndpointWorkflow() {
            authenticate(EDITOR);

            assertThatCode(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(), Map.of()))
                        .doesNotThrowAnyException();

            verify(projectDeploymentFacade).updateProjectDeploymentWorkflow(any());
        }

        @Test
        void testViewerIsDeniedUpdateEndpointWorkflow() {
            authenticate(VIEWER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentFacade, never()).updateProjectDeploymentWorkflow(any());
        }

        @Test
        void testMemberOfAnotherWorkspaceIsDeniedUpdateEndpointWorkflow() {
            authenticate(OUTSIDER);

            assertThatThrownBy(
                () -> apiCollectionFacade.updateApiCollectionEndpointWorkflow(
                    API_COLLECTION_ENDPOINT, List.of(), Map.of()))
                        .isInstanceOf(AccessDeniedException.class);

            verify(projectDeploymentFacade, never()).updateProjectDeploymentWorkflow(any());
        }
    }

    private static void authenticate(String login) {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(login, "n/a",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
