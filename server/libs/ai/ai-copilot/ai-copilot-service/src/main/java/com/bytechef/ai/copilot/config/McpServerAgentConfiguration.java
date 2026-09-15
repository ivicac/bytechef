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

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.ai.copilot.agent.OverrideChatClientResolver;
import com.bytechef.ai.copilot.agent.SliceSpringAIAgent;
import com.bytechef.ai.copilot.tool.RehydrateContextToolCallback;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolCatalog;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolScope;
import com.bytechef.ai.copilot.tool.catalog.IntelligentToolVariant;
import com.bytechef.ai.copilot.util.Mode;
import com.bytechef.ai.copilot.util.Source;
import com.bytechef.automation.ai.mcp.facade.McpProjectFacade;
import com.bytechef.automation.ai.mcp.facade.McpProjectWorkflowFacade;
import com.bytechef.automation.ai.mcp.facade.WorkspaceMcpServerFacade;
import com.bytechef.automation.ai.tool.McpServerToolCallbacksFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the MCP Server Copilot panel source agents ({@code mcp_server_ask}/{@code mcp_server_build}). The
 * {@link McpServerToolCallbacksFactory} bean comes from {@code ToolCallbacksFactoryConfiguration}; every bean here
 * carries the same {@code @ConditionalOnBean} gate on the MCP facades so the slice skips silently when the MCP feature
 * module is absent.
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
public class McpServerAgentConfiguration {

    private static final String MCP_PROJECT_WORKFLOW_PARAMETERS_TOOL_NAME = "updateMcpProjectWorkflowParameters";

    @Value("classpath:prompt_mcp_server_ask.txt")
    private Resource promptMcpServerAskResource;

    @Value("classpath:prompt_mcp_server_build.txt")
    private Resource promptMcpServerBuildResource;

    private final State state = new State();

    @Bean
    @ConditionalOnBean({
        McpProjectFacade.class, McpProjectWorkflowFacade.class, WorkspaceMcpServerFacade.class
    })
    SliceSpringAIAgent mcpServerAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, McpServerToolCallbacksFactory mcpServerToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider,
        CopilotGuardrailsAdvisorFactory copilotGuardrailsAdvisorFactory)
        throws AGUIException {

        String name = Source.MCP_SERVER.name() + "_" + Mode.ASK.name();

        return SliceSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptMcpServerAskResource))
            .state(state)
            .toolCallbacks(askToolCallbacks(securityContextRehydrator, mcpServerToolCallbacksFactory))
            .advisors(copilotGuardrailsAdvisorFactory.guardrailsAdvisors())
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    @ConditionalOnBean({
        McpProjectFacade.class, McpProjectWorkflowFacade.class, WorkspaceMcpServerFacade.class
    })
    SliceSpringAIAgent mcpServerBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, McpServerToolCallbacksFactory mcpServerToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator, IntelligentToolCatalog intelligentToolCatalog,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider,
        CopilotGuardrailsAdvisorFactory copilotGuardrailsAdvisorFactory)
        throws AGUIException {

        String name = Source.MCP_SERVER.name() + "_" + Mode.BUILD.name();

        return SliceSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptMcpServerBuildResource))
            .state(state)
            .toolCallbacks(
                buildToolCallbacks(securityContextRehydrator, mcpServerToolCallbacksFactory, intelligentToolCatalog))
            .advisors(copilotGuardrailsAdvisorFactory.guardrailsAdvisors())
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    /**
     * Package-private so {@code McpServerAgentConfigurationTest} can assert on the resolved tool names directly —
     * {@link SliceSpringAIAgent} does not expose its wrapped {@link ToolCallback} list.
     */
    List<ToolCallback> askToolCallbacks(
        SecurityContextRehydrator securityContextRehydrator,
        McpServerToolCallbacksFactory mcpServerToolCallbacksFactory) {

        return wrapToolCallbacks(securityContextRehydrator, mcpServerToolCallbacksFactory.readToolCallbacks());
    }

    /**
     * Package-private so {@code McpServerAgentConfigurationTest} can assert on the resolved tool names directly —
     * {@link SliceSpringAIAgent} does not expose its wrapped {@link ToolCallback} list.
     *
     * <p>
     * Also fetches {@code configureMcpServer} from the shared {@link IntelligentToolCatalog}, scoped to
     * {@link IntelligentToolScope#MCP_SERVER} — the panel's only path to a server's tool mapping, so
     * {@value #MCP_PROJECT_WORKFLOW_PARAMETERS_TOOL_NAME} is filtered out of the write tools, as it is on the AI Hub
     * and management MCP surfaces. Registered bare (no {@link RehydrateContextToolCallback} wrapping), matching how
     * {@code ProjectAgentConfiguration} registers its own panel-scoped intelligent delegates.
     * </p>
     */
    List<ToolCallback> buildToolCallbacks(
        SecurityContextRehydrator securityContextRehydrator,
        McpServerToolCallbacksFactory mcpServerToolCallbacksFactory, IntelligentToolCatalog intelligentToolCatalog) {

        List<ToolCallback> writeToolCallbacks = mcpServerToolCallbacksFactory.writeToolCallbacks()
            .stream()
            .filter(toolCallback -> !MCP_PROJECT_WORKFLOW_PARAMETERS_TOOL_NAME.equals(
                toolCallback.getToolDefinition()
                    .name()))
            .toList();

        List<ToolCallback> toolCallbacks = new ArrayList<>(
            wrapToolCallbacks(securityContextRehydrator, writeToolCallbacks));

        toolCallbacks.addAll(
            intelligentToolCatalog.getForPanel(
                IntelligentToolScope.MCP_SERVER, IntelligentToolVariant.BUILD, (chatClient, definition) -> chatClient,
                (toolCallback, definition) -> toolCallback));

        return toolCallbacks;
    }

    private List<ToolCallback> wrapToolCallbacks(
        SecurityContextRehydrator securityContextRehydrator, List<ToolCallback> toolCallbacks) {

        List<ToolCallback> wrapped = new ArrayList<>(toolCallbacks.size());

        for (ToolCallback toolCallback : toolCallbacks) {
            wrapped.add(RehydrateContextToolCallback.wrap(toolCallback, securityContextRehydrator));
        }

        return wrapped;
    }

    private String readPrompt(Resource resource) {
        try {
            InputStream inputStream = resource.getInputStream();

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read mcp server prompt resource: " + resource.getDescription(), exception);
        }
    }
}
