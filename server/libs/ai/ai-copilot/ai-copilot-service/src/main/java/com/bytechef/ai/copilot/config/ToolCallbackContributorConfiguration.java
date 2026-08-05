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

package com.bytechef.ai.copilot.config;

import com.bytechef.ai.copilot.tool.ClusterElementAgentToolCallback;
import com.bytechef.ai.copilot.tool.CodeEditorAgentToolCallback;
import com.bytechef.ai.copilot.tool.ConverterAgentToolCallback;
import com.bytechef.ai.copilot.tool.DataTableAgentToolCallback;
import com.bytechef.ai.copilot.tool.KnowledgeBaseAgentToolCallback;
import com.bytechef.ai.copilot.tool.SkillsAgentToolCallback;
import com.bytechef.ai.copilot.tool.WorkflowEditorAgentToolCallback;
import com.bytechef.ai.copilot.tool.WorkflowExecutionAgentToolCallback;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolCatalog;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolVariant;
import com.bytechef.ai.mcp.server.spi.McpServerToolCallbackContributor;
import com.bytechef.automation.ai.tool.DeploymentToolCallbacksFactory;
import com.bytechef.automation.ai.tool.McpServerToolCallbacksFactory;
import com.bytechef.automation.ai.tool.SkillsTools;
import com.bytechef.automation.ai.tool.WorkspaceScopeResolver;
import com.bytechef.automation.ai.tool.WorkspaceScopedFlatToolCallback;
import com.bytechef.automation.ai.tool.WorkspaceScopedSubAgentToolCallback;
import com.bytechef.automation.ai.tool.knowledgebase.KnowledgeBaseToolCallbacksFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Ivica Cardic
 */
@Configuration
public class ToolCallbackContributorConfiguration {

    public static final Set<String> INTELLIGENT_TOOL_NAMES = Set.of(
        "buildWorkflow", "importWorkflow", "configureClusterElement", "writeScript", "authorSkill",
        "debugWorkflowExecution", "configureMcpServer");

    private static final String MCP_PROJECT_WORKFLOW_PARAMETERS_TOOL_NAME = "updateMcpProjectWorkflowParameters";

    private static final Set<String> WORKSPACE_SCOPED_MCP_TOOL_NAMES = Set.of(
        "listMcpServers", "createMcpServer", "listMcpProjectWorkflows");

    private static final Set<String> WORKSPACE_SCOPED_DEPLOYMENT_TOOL_NAMES = Set.of("listProjectDeployments");

    @Bean
    McpServerToolCallbackContributor copilotAgentToolCallbackContributor(
        ObjectProvider<SkillsTools> skillsToolsProvider,
        @Qualifier("workflowEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowEditorProvider,
        @Qualifier("codeEditorBuildSubAgentChatClient") ObjectProvider<ChatClient> codeEditorProvider,
        @Qualifier("clusterElementBuildSubAgentChatClient") ObjectProvider<ChatClient> clusterElementProvider,
        @Qualifier("skillsBuildSubAgentChatClient") ObjectProvider<ChatClient> skillsProvider,
        @Qualifier("workflowExecutionBuildSubAgentChatClient") ObjectProvider<ChatClient> workflowExecutionProvider,
        @Qualifier("converterBuildSubAgentChatClientSupplier") //
        ObjectProvider<Supplier<ChatClient>> converterSupplierProvider,
        @Qualifier("knowledgeBaseBuildSubAgentChatClient") ObjectProvider<ChatClient> knowledgeBaseProvider,
        @Qualifier("dataTableBuildSubAgentChatClient") ObjectProvider<ChatClient> dataTableProvider) {

        return () -> {
            List<ToolCallback> toolCallbacks = new ArrayList<>();

            skillsToolsProvider.ifAvailable(
                skillsTools -> toolCallbacks.addAll(List.of(ToolCallbacks.from(skillsTools))));

            workflowEditorProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new WorkflowEditorAgentToolCallback(chatClient)));
            codeEditorProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new CodeEditorAgentToolCallback(chatClient)));
            clusterElementProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new ClusterElementAgentToolCallback(chatClient)));
            skillsProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new SkillsAgentToolCallback(chatClient)));
            workflowExecutionProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new WorkflowExecutionAgentToolCallback(chatClient)));
            converterSupplierProvider.ifAvailable(
                converterChatClientSupplier -> toolCallbacks.add(
                    new ConverterAgentToolCallback(converterChatClientSupplier)));
            knowledgeBaseProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new KnowledgeBaseAgentToolCallback(chatClient)));
            dataTableProvider.ifAvailable(
                chatClient -> toolCallbacks.add(new DataTableAgentToolCallback(chatClient)));

            return toolCallbacks;
        };
    }
}
