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

package com.bytechef.automation.ai.mcp.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.ai.mcp.domain.McpProject;
import com.bytechef.automation.ai.mcp.service.McpProjectService;
import com.bytechef.automation.ai.mcp.service.McpProjectWorkflowService;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.platform.mcp.domain.McpServer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.relational.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins that deleting an MCP server disables none of its project deployments when the caller may not edit any one of
 * them. The listener runs inside the delete's transaction, so a denial on a later deployment rolls back the earlier
 * ones' rows -- but not the provider-side webhook and listener subscriptions their triggers already removed.
 *
 * @author Ivica Cardic
 */
class McpServerBeforeDeleteEventListenerAuthorizationTest {

    private static final long MCP_SERVER_ID = 1L;
    private static final long PROJECT_DEPLOYMENT_A_ID = 100L;
    private static final long PROJECT_DEPLOYMENT_B_ID = 200L;

    private final McpProjectService mcpProjectService = mock(McpProjectService.class);
    private final McpProjectWorkflowService mcpProjectWorkflowService = mock(McpProjectWorkflowService.class);
    private final ProjectDeploymentFacade projectDeploymentFacade = mock(ProjectDeploymentFacade.class);
    private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService =
        mock(ProjectDeploymentWorkflowService.class);

    @BeforeEach
    void setUp() {
        McpProject mcpProjectA = new McpProject(PROJECT_DEPLOYMENT_A_ID, MCP_SERVER_ID);

        mcpProjectA.setId(10L);

        McpProject mcpProjectB = new McpProject(PROJECT_DEPLOYMENT_B_ID, MCP_SERVER_ID);

        mcpProjectB.setId(20L);

        when(mcpProjectService.getMcpServerMcpProjects(MCP_SERVER_ID)).thenReturn(List.of(mcpProjectA, mcpProjectB));
    }

    @Test
    void testDeletingAServerDisablesNoDeploymentWhenALaterDeploymentMayNotBeEdited() {
        doThrow(new AccessDeniedException("Access Denied")).when(projectDeploymentFacade)
            .checkEnableProjectDeployment(PROJECT_DEPLOYMENT_B_ID, false);

        assertThatThrownBy(() -> createListener().onBeforeDelete(beforeDeleteEvent()))
            .isInstanceOf(AccessDeniedException.class);

        verify(projectDeploymentFacade, never()).enableProjectDeployment(anyLong(), anyBoolean());
        verify(mcpProjectService, never()).delete(anyLong());
        verify(projectDeploymentService, never()).delete(anyLong());
    }

    @Test
    void testDeletingAServerChecksEveryDeploymentBeforeDisablingAny() {
        createListener().onBeforeDelete(beforeDeleteEvent());

        InOrder inOrder = inOrder(projectDeploymentFacade);

        inOrder.verify(projectDeploymentFacade)
            .checkEnableProjectDeployment(PROJECT_DEPLOYMENT_A_ID, false);
        inOrder.verify(projectDeploymentFacade)
            .checkEnableProjectDeployment(PROJECT_DEPLOYMENT_B_ID, false);
        inOrder.verify(projectDeploymentFacade)
            .enableProjectDeployment(PROJECT_DEPLOYMENT_A_ID, false);
        inOrder.verify(projectDeploymentFacade)
            .enableProjectDeployment(PROJECT_DEPLOYMENT_B_ID, false);
    }

    private McpServerBeforeDeleteEventListener createListener() {
        return new McpServerBeforeDeleteEventListener(
            mcpProjectService, mcpProjectWorkflowService, projectDeploymentWorkflowService, projectDeploymentService,
            projectDeploymentFacade);
    }

    @SuppressWarnings("unchecked")
    private static BeforeDeleteEvent<McpServer> beforeDeleteEvent() {
        BeforeDeleteEvent<McpServer> beforeDeleteEvent = mock(BeforeDeleteEvent.class);
        Identifier identifier = mock(Identifier.class);

        when(beforeDeleteEvent.getId()).thenReturn(identifier);
        when(identifier.getValue()).thenReturn(MCP_SERVER_ID);

        return beforeDeleteEvent;
    }
}
