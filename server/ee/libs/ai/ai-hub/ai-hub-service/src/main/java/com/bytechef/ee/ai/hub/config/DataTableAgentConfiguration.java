/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.copilot.agent.DataTableSpringAIAgent;
import com.bytechef.ai.copilot.agent.OverrideChatClientResolver;
import com.bytechef.ai.copilot.tool.RehydrateContextToolCallback;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.util.Mode;
import com.bytechef.ai.copilot.util.Source;
import com.bytechef.automation.data.table.configuration.facade.WorkspaceDataTableFacade;
import com.bytechef.ee.ai.hub.task.AiHubTaskArtifactService;
import com.bytechef.ee.ai.hub.tool.AiHubToolMutationArtifactRecorder;
import com.bytechef.ee.automation.ai.tool.datatable.DataTableToolCallbacksFactory;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the Data Table Copilot panel source agents ({@code data_table_ask}/{@code data_table_build}) and the AI Hub
 * Data Table subagent {@link ChatClient} beans. These beans live in EE (not the CE {@code CopilotConfiguration}, where
 * the other Copilot panel source agents live) because {@link DataTableToolCallbacksFactory} and the data-table
 * services/facades it wraps are EE.
 *
 * <p>
 * Gated so the beans exist when either the Copilot panel or the AI Hub surface is enabled, since both consume these
 * agents.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnExpression("${bytechef.ai.copilot.enabled:false} or ${bytechef.ai.hub.enabled:false}")
public class DataTableAgentConfiguration {

    @Value("classpath:prompt_data_table_ask.txt")
    private Resource promptDataTableAskResource;

    @Value("classpath:prompt_data_table_build.txt")
    private Resource promptDataTableBuildResource;

    private final State state = new State();

    @Bean
    DataTableToolCallbacksFactory dataTableToolCallbacksFactory(
        WorkspaceDataTableFacade workspaceDataTableFacade, DataTableService dataTableService,
        DataTableRowService dataTableRowService,
        ObjectProvider<AiHubTaskArtifactService> aiHubTaskArtifactServiceProvider) {

        AiHubTaskArtifactService aiHubTaskArtifactService = aiHubTaskArtifactServiceProvider.getIfAvailable();

        return new DataTableToolCallbacksFactory(
            workspaceDataTableFacade, dataTableService, dataTableRowService,
            aiHubTaskArtifactService != null ? new AiHubToolMutationArtifactRecorder(aiHubTaskArtifactService)
                : null);
    }

    @Bean
    DataTableSpringAIAgent dataTableAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, DataTableToolCallbacksFactory dataTableToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.DATA_TABLE.name() + "_" + Mode.ASK.name();

        return DataTableSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptDataTableAskResource))
            .state(state)
            .toolCallbacks(
                wrapToolCallbacks(securityContextRehydrator, dataTableToolCallbacksFactory.readToolCallbacks()))
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    DataTableSpringAIAgent dataTableBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, DataTableToolCallbacksFactory dataTableToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.DATA_TABLE.name() + "_" + Mode.BUILD.name();

        return DataTableSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptDataTableBuildResource))
            .state(state)
            .toolCallbacks(
                wrapToolCallbacks(securityContextRehydrator, dataTableToolCallbacksFactory.writeToolCallbacks()))
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    ChatClient dataTableAskSubAgentChatClient(
        ChatModel chatModel, DataTableToolCallbacksFactory dataTableToolCallbacksFactory) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptDataTableAskResource))
            .defaultToolCallbacks(dataTableToolCallbacksFactory.readToolCallbacks())
            .build();
    }

    @Bean
    ChatClient dataTableBuildSubAgentChatClient(
        ChatModel chatModel, DataTableToolCallbacksFactory dataTableToolCallbacksFactory) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptDataTableBuildResource))
            .defaultToolCallbacks(dataTableToolCallbacksFactory.writeToolCallbacks())
            .build();
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
                "Failed to read data table prompt resource: " + resource.getDescription(), exception);
        }
    }
}
