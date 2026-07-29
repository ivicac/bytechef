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

package com.bytechef.automation.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.ai.mcp.dto.McpProjectWorkflowSummaryDTO;
import com.bytechef.automation.ai.mcp.facade.McpProjectWorkflowFacade;
import com.bytechef.automation.ai.mcp.facade.WorkspaceMcpServerFacade;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ToolContext;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class ListMcpProjectWorkflowsToolCallbackTest {

    private McpProjectWorkflowFacade mcpProjectWorkflowFacade;
    private ListMcpProjectWorkflowsToolCallback toolCallback;
    private WorkspaceMcpServerFacade workspaceMcpServerFacade;

    @BeforeEach
    void beforeEach() {
        mcpProjectWorkflowFacade = mock(McpProjectWorkflowFacade.class);
        workspaceMcpServerFacade = mock(WorkspaceMcpServerFacade.class);
        toolCallback = new ListMcpProjectWorkflowsToolCallback(mcpProjectWorkflowFacade, workspaceMcpServerFacade);
    }

    @Test
    void testToolDefinitionName() {
        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("listMcpProjectWorkflows");
    }

    @Test
    void testMissingMcpServerIdReturnsError() {
        String result = toolCallback.call("{}");

        assertThat(result)
            .contains("error")
            .contains("mcpServerId is required");
    }

    @Test
    void testReturnsTheFacadeSummaries() {
        mockWorkspaceMcpServer(7L, 5L);

        when(mcpProjectWorkflowFacade.getMcpServerMcpProjectWorkflows(5L)).thenReturn(
            List.of(
                new McpProjectWorkflowSummaryDTO(
                    300L, 400L, "workflow-1", "Weather Lookup", false, "get_weather", "Fetch weather",
                    Map.of("toolName", "get_weather"), null)));

        String result = toolCallback.call("{\"mcpServerId\": 5}", workspaceToolContext(7L));

        assertThat(result)
            .contains("\"mcpProjectWorkflowId\":400")
            .contains("get_weather")
            .contains("Weather Lookup")
            .contains("\"toolCallable\":false");
    }

    @Test
    void testSurfacesAFacadeFailureAsToolError() {
        mockWorkspaceMcpServer(7L, 5L);

        when(mcpProjectWorkflowFacade.getMcpServerMcpProjectWorkflows(5L))
            .thenThrow(new IllegalArgumentException("McpServer not found"));

        assertThat(toolCallback.call("{\"mcpServerId\": 5}", workspaceToolContext(7L)))
            .contains("error")
            .contains("McpServer not found");
    }

    @Nested
    class WorkspaceScopeTest {

        @Test
        void testRejectsAnInvocationWithoutWorkspaceContext() {
            String result = toolCallback.call("{\"mcpServerId\": 5}");

            assertThat(result).contains("Workspace context unavailable");
            verify(mcpProjectWorkflowFacade, never()).getMcpServerMcpProjectWorkflows(anyLong());
        }

        @Test
        void testRejectsAnMcpServerOutsideTheWorkspace() {
            mockWorkspaceMcpServer(7L, 6L);

            String result = toolCallback.call("{\"mcpServerId\": 5}", workspaceToolContext(7L));

            assertThat(result).contains("MCP server 5 not found in the current workspace");
            verify(mcpProjectWorkflowFacade, never()).getMcpServerMcpProjectWorkflows(anyLong());
        }

        @Test
        void testListsAnMcpServerOfTheWorkspace() {
            mockWorkspaceMcpServer(7L, 5L);

            when(mcpProjectWorkflowFacade.getMcpServerMcpProjectWorkflows(5L)).thenReturn(List.of());

            String result = toolCallback.call("{\"mcpServerId\": 5}", workspaceToolContext(7L));

            assertThat(result).isEqualTo("[]");
            verify(mcpProjectWorkflowFacade).getMcpServerMcpProjectWorkflows(5L);
        }
    }

    private void mockWorkspaceMcpServer(long workspaceId, long mcpServerId) {
        McpServer mcpServer = mock(McpServer.class);

        when(mcpServer.getId()).thenReturn(mcpServerId);
        when(workspaceMcpServerFacade.getWorkspaceMcpServers(workspaceId)).thenReturn(List.of(mcpServer));
    }

    private static ToolContext workspaceToolContext(long workspaceId) {
        return new ToolContext(Map.of(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, workspaceId));
    }
}
