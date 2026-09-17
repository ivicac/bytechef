/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.ee.embedded.configuration.exception.ConnectionNotEntitledException;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ConnectedUserWorkflowConnectionResolverTest {

    private static final long CONNECTED_USER_ID = 7L;

    @Mock
    private ConnectedUserConnectionFacade connectedUserConnectionFacade;

    @Mock
    private WorkflowConnectionSlots workflowConnectionSlots;

    private ConnectedUserWorkflowConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConnectedUserWorkflowConnectionResolver(connectedUserConnectionFacade, workflowConnectionSlots);
    }

    @Test
    void testResolveUsesComponentConnectionKeyAndNodeName() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.connections())
            .containsExactly(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1"));
        assertThat(resolved.isComplete()).isTrue();
    }

    @Test
    void testResolveUsesClusterElementKeyUnderRootNode() {
        stubSlots(new ComponentConnection("openAi", 1, "aiAgent1", "openAi_1", true));
        stubEntitled("openAi", connection(21L, "openAi"));

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.connections())
            .containsExactly(new ProjectDeploymentWorkflowConnection(21L, "openAi_1", "aiAgent1"));
    }

    @Test
    void testResolvePrefersRequestedConnection() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"), connection(12L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of("slack", 12L),
            List.of(new ProjectDeploymentWorkflowConnection(11L, "slack", "postMessage1")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(12L);
    }

    @Test
    void testResolveKeepsCurrentlyWiredConnectionForTheComponent() {
        stubSlots(
            new ComponentConnection("slack", 1, "postMessage1", "slack", true),
            new ComponentConnection("slack", 1, "postMessage2", "slack", true));
        stubEntitled("slack", connection(11L, "slack"), connection(12L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of(),
            List.of(new ProjectDeploymentWorkflowConnection(12L, "slack", "renamedNode")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(12L, 12L);
    }

    /**
     * A connection wired to another component's slot is never a candidate for this one, even when it comes first in the
     * current wiring: only the connected user's connections of this component are.
     */
    @Test
    void testResolveIgnoresAConnectionCurrentlyWiredToAnotherComponent() {
        stubSlots(
            new ComponentConnection("jira", 1, "createIssue1", "jira", true),
            new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("jira", connection(31L, "jira"));
        stubEntitled("slack", connection(11L, "slack"), connection(12L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of(),
            List.of(
                new ProjectDeploymentWorkflowConnection(31L, "jira", "createIssue1"),
                new ProjectDeploymentWorkflowConnection(12L, "slack", "postMessage1")));

        assertThat(resolved.connections()).containsExactly(
            new ProjectDeploymentWorkflowConnection(31L, "jira", "createIssue1"),
            new ProjectDeploymentWorkflowConnection(12L, "slack", "postMessage1"));
    }

    @Test
    void testResolveFallsThroughWhenCurrentlyWiredConnectionIsNoLongerEntitled() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        ResolvedWorkflowConnections resolved = resolver.resolve(
            "wf-1", CONNECTED_USER_ID, Map.of(),
            List.of(new ProjectDeploymentWorkflowConnection(99L, "slack", "postMessage1")));

        assertThat(resolved.connections())
            .extracting(ProjectDeploymentWorkflowConnection::getConnectionId)
            .containsExactly(11L);
    }

    @Test
    void testResolveRejectsRequestedConnectionTheUserIsNotEntitledTo() {
        stubSlots(new ComponentConnection("slack", 1, "postMessage1", "slack", true));
        stubEntitled("slack", connection(11L, "slack"));

        assertThatThrownBy(() -> resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of("slack", 99L), List.of()))
            .isInstanceOf(ConnectionNotEntitledException.class);
    }

    @Test
    void testResolveReportsMissingRequiredComponentAndSkipsOptionalOne() {
        stubSlots(
            new ComponentConnection("slack", 1, "postMessage1", "slack", true),
            new ComponentConnection("httpClient", 1, "get1", "httpClient", false));
        stubEntitled("slack");
        stubEntitled("httpClient");

        ResolvedWorkflowConnections resolved = resolver.resolve("wf-1", CONNECTED_USER_ID, Map.of(), List.of());

        assertThat(resolved.missingComponentNames()).containsExactly("slack");
        assertThat(resolved.firstMissingComponentName()).isEqualTo("slack");
        assertThat(resolved.connections()).isEmpty();
    }

    private void stubSlots(ComponentConnection... componentConnections) {
        when(workflowConnectionSlots.getSlots("wf-1")).thenReturn(List.of(componentConnections));
    }

    private void stubEntitled(String componentName, ConnectionDTO... connectionDTOs) {
        lenient().when(connectedUserConnectionFacade.getConnections(CONNECTED_USER_ID, componentName, List.of()))
            .thenReturn(List.of(connectionDTOs));
    }

    private static ConnectionDTO connection(long id, String componentName) {
        ConnectionDTO connectionDTO = mock(ConnectionDTO.class);

        lenient().when(connectionDTO.id())
            .thenReturn(id);
        lenient().when(connectionDTO.componentName())
            .thenReturn(componentName);

        return connectionDTO;
    }
}
