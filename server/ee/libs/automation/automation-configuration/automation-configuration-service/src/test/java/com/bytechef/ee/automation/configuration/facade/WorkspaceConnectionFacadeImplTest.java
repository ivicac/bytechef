/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.domain.WorkspaceConnection;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.service.ConnectionVisibilityResolver;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.WorkspaceConnectionService;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.domain.ConnectionVisibility;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.exception.ConnectionErrorType;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.ConnectionLifecycleFacade;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceConnectionFacadeImplTest {

    private static final long WORKSPACE_ID = 1L;
    private static final String CURRENT_USER = "admin@example.com";

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ConnectionFacade connectionFacade;

    @Mock
    private ConnectionLifecycleFacade connectionLifecycleFacade;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Mock
    private ConnectionVisibilityResolver connectionVisibilityResolver;

    @Mock
    private ProjectService projectService;

    @Mock
    private TagService tagService;

    @Mock
    private UserService userService;

    @Mock
    private WorkflowTestConfigurationService workflowTestConfigurationService;

    @Mock
    private WorkspaceConnectionService workspaceConnectionService;

    @Mock
    private WorkspaceFacade workspaceFacade;

    private WorkspaceConnectionFacadeImpl workspaceConnectionFacade;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> emptyProvider = mock(ObjectProvider.class);

        when(emptyProvider.getIfAvailable()).thenReturn(null);

        workspaceConnectionFacade = new WorkspaceConnectionFacadeImpl(
            applicationEventPublisher, connectionFacade, connectionLifecycleFacade, connectionService,
            connectionVisibilityResolver, emptyProvider, projectDeploymentWorkflowService,
            projectService, tagService, userService, workflowTestConfigurationService, workspaceConnectionService,
            workspaceFacade);
    }

    @Test
    void testDemoteToPrivateUpdatesVisibility() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN"))
                .thenReturn(true);

            stubWorkspaceContainsConnection(10L);

            when(projectDeploymentWorkflowService.isConnectionUsed(10L)).thenReturn(false);

            workspaceConnectionFacade.demoteToPrivate(WORKSPACE_ID, 10L);

            verify(connectionService).updateVisibility(10L, ConnectionVisibility.PRIVATE);
        }
    }

    @Test
    void testDemoteToPrivateAllowsCreatorWhenNotAdmin() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN"))
                .thenReturn(false);
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn(CURRENT_USER);

            stubWorkspaceContainsConnection(10L);

            when(projectDeploymentWorkflowService.isConnectionUsed(10L)).thenReturn(false);

            ConnectionDTO dto = ConnectionDTO.builder()
                .createdBy(CURRENT_USER)
                .build();

            when(connectionFacade.getConnection(10L)).thenReturn(dto);

            workspaceConnectionFacade.demoteToPrivate(WORKSPACE_ID, 10L);

            verify(connectionService).updateVisibility(10L, ConnectionVisibility.PRIVATE);
        }
    }

    @Test
    void testDemoteToPrivateBlockedWhenNonAdminAndNotCreator() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN"))
                .thenReturn(false);
            securityUtils.when(SecurityUtils::getCurrentUserLogin)
                .thenReturn(CURRENT_USER);

            ConnectionDTO dto = ConnectionDTO.builder()
                .createdBy("someone-else@example.com")
                .build();

            when(connectionFacade.getConnection(10L)).thenReturn(dto);

            assertThatThrownBy(() -> workspaceConnectionFacade.demoteToPrivate(WORKSPACE_ID, 10L))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("administrator or the connection creator");

            verify(connectionService, never()).updateVisibility(eq(10L), any());
            // Authz is evaluated before workspace/deployment validation, so those paths are never
            // consulted for an unauthorized caller — no info leak via differential error messages.
            verify(workspaceConnectionService, never()).getWorkspaceConnections(anyLong());
            verify(projectDeploymentWorkflowService, never()).isConnectionUsed(anyLong());
        }
    }

    @Test
    void testDemoteToPrivateBlockedWhenConnectionIsUsed() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN"))
                .thenReturn(true);

            stubWorkspaceContainsConnection(10L);

            when(projectDeploymentWorkflowService.isConnectionUsed(10L)).thenReturn(true);

            assertThatThrownBy(() -> workspaceConnectionFacade.demoteToPrivate(WORKSPACE_ID, 10L))
                .isInstanceOf(ConfigurationException.class);

            verify(connectionService, never()).updateVisibility(eq(10L), any());
        }
    }

    @Test
    void testDemoteToPrivateBlockedWhenConnectionNotInWorkspace() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(() -> SecurityUtils.hasCurrentUserThisAuthority("ROLE_ADMIN"))
                .thenReturn(true);

            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> workspaceConnectionFacade.demoteToPrivate(WORKSPACE_ID, 10L))
                .isInstanceOf(ConfigurationException.class);

            verify(connectionService, never()).updateVisibility(eq(10L), any());
        }
    }

    @Test
    void testPromoteToWorkspaceUpdatesVisibilityAndAudits() {
        stubWorkspaceContainsConnection(10L);

        Connection connection = mock(Connection.class);

        when(connection.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
        when(connectionService.getConnection(10L)).thenReturn(connection);

        workspaceConnectionFacade.promoteToWorkspace(WORKSPACE_ID, 10L);

        verify(connectionService).updateVisibility(10L, ConnectionVisibility.WORKSPACE);
    }

    /**
     * Pins the {@code CONNECTION_ALREADY_AT_TARGET_VISIBILITY} error-key contract that
     * {@code promoteAllPrivateToWorkspace} relies on to classify concurrent races as {@code skipped} (not
     * {@code failed}). If the error key or the thrown exception class ever changes, bulk promote will start counting
     * benign races as real failures — this test makes that regression explicit.
     */
    @Test
    void testPromoteToWorkspaceThrowsAlreadyAtTargetWhenConnectionIsWorkspace() {
        stubWorkspaceContainsConnection(10L);

        Connection connection = mock(Connection.class);

        when(connection.getVisibility()).thenReturn(ConnectionVisibility.WORKSPACE);
        when(connectionService.getConnection(10L)).thenReturn(connection);

        assertThatThrownBy(() -> workspaceConnectionFacade.promoteToWorkspace(WORKSPACE_ID, 10L))
            .isInstanceOfSatisfying(
                ConfigurationException.class,
                exception -> assertThat(exception.getErrorKey())
                    .isEqualTo(ConnectionErrorType.CONNECTION_ALREADY_AT_TARGET_VISIBILITY.getErrorKey()));

        verify(connectionService, never()).updateVisibility(eq(10L), any());
    }

    @Test
    void testPromoteAllPrivateToWorkspaceTreatsAlreadyAtTargetVisibilityAsBenign() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubCurrentUserIsWorkspaceMember(securityUtils);

            WorkspaceConnection wc1 = mock(WorkspaceConnection.class);

            when(wc1.getConnectionId()).thenReturn(10L);
            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID)).thenReturn(List.of(wc1));

            Connection privateConn = mock(Connection.class);

            when(privateConn.getId()).thenReturn(10L);
            when(privateConn.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(connectionService.getConnections(List.of(10L))).thenReturn(List.of(privateConn));

            // Simulate the per-row promote racing with another writer: by the time we look up the
            // connection inside promoteToWorkspace it's already WORKSPACE.
            Connection raced = mock(Connection.class);

            when(raced.getVisibility()).thenReturn(ConnectionVisibility.WORKSPACE);
            when(connectionService.getConnection(10L)).thenReturn(raced);

            var result = workspaceConnectionFacade.promoteAllPrivateToWorkspace(WORKSPACE_ID);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.promoted()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(0);
            assertThat(result.failures()).isEmpty();
        }
    }

    @Test
    void testPromoteAllPrivateToWorkspaceCollectsPartialFailures() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubCurrentUserIsWorkspaceMember(securityUtils);

            WorkspaceConnection wc1 = mock(WorkspaceConnection.class);
            WorkspaceConnection wc2 = mock(WorkspaceConnection.class);

            when(wc1.getConnectionId()).thenReturn(10L);
            when(wc2.getConnectionId()).thenReturn(20L);
            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
                .thenReturn(List.of(wc1, wc2));

            Connection ok = mock(Connection.class);
            Connection failing = mock(Connection.class);

            when(ok.getId()).thenReturn(10L);
            when(ok.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(failing.getId()).thenReturn(20L);
            when(failing.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(connectionService.getConnections(anyList()))
                .thenReturn(List.of(ok, failing));
            when(connectionService.getConnection(10L)).thenReturn(ok);
            when(connectionService.getConnection(20L)).thenReturn(failing);

            // Stub both updateVisibility calls symmetrically so Mockito strict-stubbing doesn't flag the
            // (10L, WORKSPACE) invocation as a similar-but-mismatched stubbing of the (20L, WORKSPACE) throw.
            when(connectionService.updateVisibility(10L, ConnectionVisibility.WORKSPACE))
                .thenReturn(ok);
            org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(connectionService)
                .updateVisibility(20L, ConnectionVisibility.WORKSPACE);

            var result = workspaceConnectionFacade.promoteAllPrivateToWorkspace(WORKSPACE_ID);

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.promoted()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.failures()).hasSize(1);

            // Pin connectionId, errorCode AND message. A regression that swallowed the exception message
            // (e.g. replacing it with the class name or a generic "Error") would otherwise pass this test
            // because the count-and-id assertions alone do not cover message payload fidelity.
            assertThat(result.failures()
                .get(0)
                .connectionId()).isEqualTo("20");
            assertThat(result.failures()
                .get(0)
                .errorCode()).isEqualTo("UNEXPECTED");
            assertThat(result.failures()
                .get(0)
                .message()).isEqualTo("Unexpected error: RuntimeException");
            verify(connectionService).updateVisibility(10L, ConnectionVisibility.WORKSPACE);
        }
    }

    /**
     * Happy-path single-connection promote from PRIVATE. The state matrix is otherwise covered by the already-at-target
     * test; this pins the most common path so a regression that breaks only the PRIVATE→WORKSPACE transition cannot
     * slip through.
     */
    @Test
    void testPromoteAllPrivateToWorkspacePromotesSinglePrivateConnection() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubCurrentUserIsWorkspaceMember(securityUtils);

            WorkspaceConnection workspaceConnection = mock(WorkspaceConnection.class);

            when(workspaceConnection.getConnectionId()).thenReturn(10L);
            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
                .thenReturn(List.of(workspaceConnection));

            Connection privateConnection = mock(Connection.class);

            when(privateConnection.getId()).thenReturn(10L);
            when(privateConnection.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(connectionService.getConnections(List.of(10L))).thenReturn(List.of(privateConnection));
            when(connectionService.getConnection(10L)).thenReturn(privateConnection);
            when(connectionService.updateVisibility(10L, ConnectionVisibility.WORKSPACE)).thenReturn(privateConnection);

            var result = workspaceConnectionFacade.promoteAllPrivateToWorkspace(WORKSPACE_ID);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.promoted()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            assertThat(result.failed()).isEqualTo(0);
            assertThat(result.failures()).isEmpty();
            verify(connectionService).updateVisibility(10L, ConnectionVisibility.WORKSPACE);
        }
    }

    @Test
    void testPromoteAllPrivateToWorkspaceContinuesAfterMidLoopFailure() {
        // Pin the "partial failure surfaces to the caller instead of bailing on the first error" contract from
        // CLAUDE.md. Three PRIVATE connections; the middle one throws. The bulk call must continue past it,
        // promote the third, and return a BulkPromoteResult{promoted=2, failed=1, failures=[middle]} so callers
        // can render "2 promoted, 1 failed (see details)" instead of a short-circuit with ambiguous state.
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubCurrentUserIsWorkspaceMember(securityUtils);

            WorkspaceConnection wc1 = mock(WorkspaceConnection.class);
            WorkspaceConnection wc2 = mock(WorkspaceConnection.class);
            WorkspaceConnection wc3 = mock(WorkspaceConnection.class);

            when(wc1.getConnectionId()).thenReturn(10L);
            when(wc2.getConnectionId()).thenReturn(20L);
            when(wc3.getConnectionId()).thenReturn(30L);
            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
                .thenReturn(List.of(wc1, wc2, wc3));

            Connection first = mock(Connection.class);
            Connection middle = mock(Connection.class);
            Connection last = mock(Connection.class);

            when(first.getId()).thenReturn(10L);
            when(first.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(middle.getId()).thenReturn(20L);
            when(middle.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(last.getId()).thenReturn(30L);
            when(last.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(connectionService.getConnections(anyList()))
                .thenReturn(List.of(first, middle, last));
            when(connectionService.getConnection(10L)).thenReturn(first);
            when(connectionService.getConnection(20L)).thenReturn(middle);
            when(connectionService.getConnection(30L)).thenReturn(last);

            when(connectionService.updateVisibility(10L, ConnectionVisibility.WORKSPACE))
                .thenReturn(first);
            org.mockito.Mockito.doThrow(new RuntimeException("middle-failed"))
                .when(connectionService)
                .updateVisibility(20L, ConnectionVisibility.WORKSPACE);
            when(connectionService.updateVisibility(30L, ConnectionVisibility.WORKSPACE))
                .thenReturn(last);

            var result = workspaceConnectionFacade.promoteAllPrivateToWorkspace(WORKSPACE_ID);

            assertThat(result.promoted()).isEqualTo(2);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures()
                .get(0)
                .connectionId()).isEqualTo("20");
            // Raw RuntimeException messages are sanitized to the simple class name (see
            // WorkspaceConnectionFacadeImpl#sanitizedFailureMessage) so JDBC/DataAccess messages with
            // bind params or schema detail don't leak into the admin toast. Assert the sanitized shape.
            assertThat(result.failures()
                .get(0)
                .message()).isEqualTo("Unexpected error: RuntimeException");

            // Loop continued past the middle failure — last connection was still promoted.
            verify(connectionService).updateVisibility(10L, ConnectionVisibility.WORKSPACE);
            verify(connectionService).updateVisibility(30L, ConnectionVisibility.WORKSPACE);
        }
    }

    @Test
    void testPromoteAllPrivateToWorkspacePromotesOnlyPrivate() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubCurrentUserIsWorkspaceMember(securityUtils);

            WorkspaceConnection wc1 = mock(WorkspaceConnection.class);
            WorkspaceConnection wc2 = mock(WorkspaceConnection.class);
            WorkspaceConnection wc3 = mock(WorkspaceConnection.class);

            when(wc1.getConnectionId()).thenReturn(10L);
            when(wc2.getConnectionId()).thenReturn(20L);
            when(wc3.getConnectionId()).thenReturn(30L);
            when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
                .thenReturn(List.of(wc1, wc2, wc3));

            Connection privateConn = mock(Connection.class);
            Connection workspaceConn = mock(Connection.class);
            Connection organizationConn = mock(Connection.class);

            when(privateConn.getId()).thenReturn(10L);
            when(privateConn.getVisibility()).thenReturn(ConnectionVisibility.PRIVATE);
            when(workspaceConn.getVisibility()).thenReturn(ConnectionVisibility.WORKSPACE);
            when(organizationConn.getVisibility()).thenReturn(ConnectionVisibility.ORGANIZATION);
            when(connectionService.getConnections(List.of(10L, 20L, 30L)))
                .thenReturn(List.of(privateConn, workspaceConn, organizationConn));

            // promoteToWorkspace re-fetches a single connection inside its own validation
            when(connectionService.getConnection(10L)).thenReturn(privateConn);

            var result = workspaceConnectionFacade.promoteAllPrivateToWorkspace(WORKSPACE_ID);

            assertThat(result.promoted()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(0);
            verify(connectionService).updateVisibility(10L, ConnectionVisibility.WORKSPACE);
            verify(connectionService, never()).updateVisibility(eq(20L), any());
            verify(connectionService, never()).updateVisibility(eq(30L), any());
        }
    }

    private void stubWorkspaceContainsConnection(long connectionId) {
        WorkspaceConnection workspaceConnection = mock(WorkspaceConnection.class);

        when(workspaceConnection.getConnectionId()).thenReturn(connectionId);

        when(workspaceConnectionService.getWorkspaceConnections(WORKSPACE_ID))
            .thenReturn(List.of(workspaceConnection));
    }

    private void stubCurrentUserIsWorkspaceMember(MockedStatic<SecurityUtils> securityUtils) {
        securityUtils.when(SecurityUtils::getCurrentUserLogin)
            .thenReturn(CURRENT_USER);

        User currentUser = mock(User.class);

        when(currentUser.getId()).thenReturn(99L);
        when(userService.fetchUserByLogin(CURRENT_USER)).thenReturn(java.util.Optional.of(currentUser));

        Workspace workspace = mock(Workspace.class);

        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspaceFacade.getUserWorkspaces(99L)).thenReturn(List.of(workspace));
    }
}
