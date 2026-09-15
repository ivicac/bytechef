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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.ai.mcp.domain.McpProject;
import com.bytechef.automation.ai.mcp.service.McpProjectService;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.repository.McpServerRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.relational.core.mapping.event.AfterSaveEvent;
import org.springframework.data.relational.core.mapping.event.BeforeConvertEvent;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit test for {@link McpServerAfterSaveEventListener}.
 *
 * @author Ivica Cardic
 */
public class McpServerAfterSaveEventListenerTest {

    private final McpProjectService mcpProjectService = mock(McpProjectService.class);
    private final McpServerRepository mcpServerRepository = mock(McpServerRepository.class);
    private final ProjectDeploymentFacade projectDeploymentFacade = mock(ProjectDeploymentFacade.class);

    @Test
    public void testSaveLeavingAnEnabledServerEnabledTouchesNoProjectDeployment() {
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(mcpServer(1L, true)));
        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(List.of(new McpProject(100L, 1L)));

        McpServer renamedMcpServer = mcpServer(1L, true);

        renamedMcpServer.setName("renamed");

        saveThroughListener(listener, renamedMcpServer);

        verify(projectDeploymentFacade, never()).checkEnableProjectDeployment(anyLong(), anyBoolean());
        verify(projectDeploymentFacade, never()).enableProjectDeployment(anyLong(), anyBoolean());
    }

    @Test
    public void testSaveLeavingADisabledServerDisabledTouchesNoProjectDeployment() {
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(mcpServer(1L, false)));
        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(List.of(new McpProject(100L, 1L)));

        saveThroughListener(listener, mcpServer(1L, false));

        verify(projectDeploymentFacade, never()).checkEnableProjectDeployment(anyLong(), anyBoolean());
        verify(projectDeploymentFacade, never()).enableProjectDeployment(anyLong(), anyBoolean());
    }

    @Test
    public void testSaveFlippingAServerToEnabledChecksEveryDeploymentBeforeEnablingAny() {
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(mcpServer(1L, false)));
        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(
            List.of(new McpProject(100L, 1L), new McpProject(200L, 1L)));

        saveThroughListener(listener, mcpServer(1L, true));

        InOrder inOrder = inOrder(projectDeploymentFacade);

        inOrder.verify(projectDeploymentFacade)
            .checkEnableProjectDeployment(100L, true);
        inOrder.verify(projectDeploymentFacade)
            .checkEnableProjectDeployment(200L, true);
        inOrder.verify(projectDeploymentFacade)
            .enableProjectDeployment(100L, true);
        inOrder.verify(projectDeploymentFacade)
            .enableProjectDeployment(200L, true);
    }

    @Test
    public void testSaveFlippingAServerToEnabledEnablesNoDeploymentWhenALaterDeploymentIsDenied() {
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(mcpServer(1L, false)));
        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(
            List.of(new McpProject(100L, 1L), new McpProject(200L, 1L)));
        doThrow(new AccessDeniedException("Access Denied")).when(projectDeploymentFacade)
            .checkEnableProjectDeployment(200L, true);

        assertThatThrownBy(() -> saveThroughListener(listener, mcpServer(1L, true)))
            .isInstanceOf(AccessDeniedException.class);

        verify(projectDeploymentFacade, never()).enableProjectDeployment(anyLong(), anyBoolean());
    }

    @Test
    public void testSaveFlippingAServerToDisabledDisablesProjectDeployments() {
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(mcpServer(1L, true)));
        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(List.of(new McpProject(100L, 1L)));

        saveThroughListener(listener, mcpServer(1L, false));

        verify(projectDeploymentFacade).checkEnableProjectDeployment(100L, false);
        verify(projectDeploymentFacade).enableProjectDeployment(100L, false);
    }

    @SuppressWarnings("unchecked")
    private static void saveThroughListener(McpServerAfterSaveEventListener listener, McpServer mcpServer) {
        listener.onBeforeConvert(new BeforeConvertEvent<>(mcpServer));

        AfterSaveEvent<McpServer> afterSaveEvent = mock(AfterSaveEvent.class);

        when(afterSaveEvent.getEntity()).thenReturn(mcpServer);

        listener.onAfterSave(afterSaveEvent);
    }

    private static McpServer mcpServer(long id, boolean enabled) {
        McpServer mcpServer = new McpServer();

        mcpServer.setId(id);
        mcpServer.setEnabled(enabled);

        return mcpServer;
    }

    @Test
    public void testOnAfterSaveEnabledServerEnablesProjectDeployments() {
        // Given
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        McpServer mcpServer = new McpServer();
        mcpServer.setId(1L);
        mcpServer.setEnabled(true);

        McpProject mcpProject1 = new McpProject(100L, 1L);
        McpProject mcpProject2 = new McpProject(200L, 1L);
        List<McpProject> mcpProjects = Arrays.asList(mcpProject1, mcpProject2);

        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(mcpProjects);

        @SuppressWarnings("unchecked")
        AfterSaveEvent<McpServer> event = mock(AfterSaveEvent.class);
        when(event.getEntity()).thenReturn(mcpServer);

        // When
        listener.onAfterSave(event);

        // Then
        verify(mcpProjectService).getMcpServerMcpProjects(1L);
        verify(projectDeploymentFacade).enableProjectDeployment(eq(100L), eq(true));
        verify(projectDeploymentFacade).enableProjectDeployment(eq(200L), eq(true));
    }

    @Test
    public void testOnAfterSaveDisabledServerDisablesProjectDeployments() {
        // Given
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        McpServer mcpServer = new McpServer();
        mcpServer.setId(1L);
        mcpServer.setEnabled(false);

        McpProject mcpProject1 = new McpProject(100L, 1L);
        McpProject mcpProject2 = new McpProject(200L, 1L);
        List<McpProject> mcpProjects = Arrays.asList(mcpProject1, mcpProject2);

        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(mcpProjects);

        @SuppressWarnings("unchecked")
        AfterSaveEvent<McpServer> event = mock(AfterSaveEvent.class);
        when(event.getEntity()).thenReturn(mcpServer);

        // When
        listener.onAfterSave(event);

        // Then
        verify(mcpProjectService).getMcpServerMcpProjects(1L);
        verify(projectDeploymentFacade).enableProjectDeployment(eq(100L), eq(false));
        verify(projectDeploymentFacade).enableProjectDeployment(eq(200L), eq(false));
    }

    @Test
    public void testOnAfterSaveServerWithNoProjectsNoFacadeCalls() {
        // Given
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        McpServer mcpServer = new McpServer();
        mcpServer.setId(1L);
        mcpServer.setEnabled(true);

        when(mcpProjectService.getMcpServerMcpProjects(1L)).thenReturn(Collections.emptyList());

        @SuppressWarnings("unchecked")
        AfterSaveEvent<McpServer> event = mock(AfterSaveEvent.class);
        when(event.getEntity()).thenReturn(mcpServer);

        // When
        listener.onAfterSave(event);

        // Then
        verify(mcpProjectService).getMcpServerMcpProjects(1L);
        verify(projectDeploymentFacade, times(0)).enableProjectDeployment(eq(100L), eq(true));
        verify(projectDeploymentFacade, times(0)).enableProjectDeployment(eq(200L), eq(true));
    }

    @Test
    public void testOnAfterSaveMultipleProjectsWithDifferentDeployments() {
        // Given
        McpServerAfterSaveEventListener listener = new McpServerAfterSaveEventListener(
            mcpProjectService, mcpServerRepository, projectDeploymentFacade);

        McpServer mcpServer = new McpServer();
        mcpServer.setId(2L);
        mcpServer.setEnabled(true);

        McpProject mcpProject1 = new McpProject(300L, 2L);
        McpProject mcpProject2 = new McpProject(400L, 2L);
        McpProject mcpProject3 = new McpProject(500L, 2L);
        List<McpProject> mcpProjects = Arrays.asList(mcpProject1, mcpProject2, mcpProject3);

        when(mcpProjectService.getMcpServerMcpProjects(2L)).thenReturn(mcpProjects);

        @SuppressWarnings("unchecked")
        AfterSaveEvent<McpServer> event = mock(AfterSaveEvent.class);
        when(event.getEntity()).thenReturn(mcpServer);

        // When
        listener.onAfterSave(event);

        // Then
        verify(mcpProjectService).getMcpServerMcpProjects(2L);
        verify(projectDeploymentFacade).enableProjectDeployment(eq(300L), eq(true));
        verify(projectDeploymentFacade).enableProjectDeployment(eq(400L), eq(true));
        verify(projectDeploymentFacade).enableProjectDeployment(eq(500L), eq(true));
    }
}
