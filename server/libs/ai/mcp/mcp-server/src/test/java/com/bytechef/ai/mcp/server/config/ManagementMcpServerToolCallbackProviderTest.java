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

package com.bytechef.ai.mcp.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ai.mcp.tool.McpToolCallbackContributor;
import com.bytechef.ai.mcp.tool.automation.ClusterElementTools;
import com.bytechef.ai.mcp.tool.automation.ProjectTools;
import com.bytechef.ai.mcp.tool.automation.ProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ScriptTools;
import com.bytechef.ai.mcp.tool.platform.ComponentTools;
import com.bytechef.ai.mcp.tool.platform.TaskDispatcherTools;
import com.bytechef.ai.mcp.tool.platform.TaskTools;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

class ManagementMcpServerToolCallbackProviderTest {

    @Test
    void includesContributedCallbacksAlongsideDirectTools() {
        ToolCallback contributed = mock(ToolCallback.class);

        when(contributed.getToolDefinition()).thenReturn(
            ToolDefinition.builder()
                .name("workflow_editor_agent")
                .description("d")
                .inputSchema("{\"type\":\"object\"}")
                .build());

        McpToolCallbackContributor contributor = () -> List.of(contributed);

        ManagementMcpServerConfiguration configuration = new ManagementMcpServerConfiguration(
            mock(ComponentTools.class), mock(ProjectTools.class), mock(ProjectWorkflowTools.class),
            mock(TaskTools.class), mock(TaskDispatcherTools.class), mock(ScriptTools.class),
            mock(ClusterElementTools.class), List.of(contributor));

        ToolCallbackProvider provider = configuration.toolCallbackProvider();

        List<String> names = Arrays.stream(provider.getToolCallbacks())
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .toList();

        assertThat(names).contains("workflow_editor_agent");
    }

    @Test
    void worksWithNoContributors() {
        ManagementMcpServerConfiguration configuration = new ManagementMcpServerConfiguration(
            mock(ComponentTools.class), mock(ProjectTools.class), mock(ProjectWorkflowTools.class),
            mock(TaskTools.class), mock(TaskDispatcherTools.class), mock(ScriptTools.class),
            mock(ClusterElementTools.class), List.of());

        ToolCallbackProvider provider = configuration.toolCallbackProvider();

        assertThat(provider.getToolCallbacks()).isNotNull();
    }
}
