/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.tool.memory;

import com.bytechef.component.ai.agent.utils.cluster.AutoMemoryResourceResolver;
import com.bytechef.component.ai.agent.utils.cluster.ServiceBackedAutoMemoryDirectoryOps;
import com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext;
import com.bytechef.platform.ai.agent.memory.AutoMemoryTools;
import com.bytechef.platform.ai.auto.memory.AiAutoMemoryOwner;
import com.bytechef.platform.ai.auto.memory.AiAutoMemoryPrincipalType;
import com.bytechef.platform.ai.auto.memory.AiAutoMemoryService;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Augments the system message with the AI Hub memory system prompt and registers {@link AutoMemoryTools} tool callbacks
 * bound to the memory owner (workspace, user, environment) carried in the request's tool context. A request without a
 * workspace or user in its tool context is passed through unchanged.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AiHubAutoMemoryToolsAdvisor implements BaseChatMemoryAdvisor {

    private final AiAutoMemoryService aiAutoMemoryService;
    private final int order;
    private final String memorySystemPrompt;

    private AiHubAutoMemoryToolsAdvisor(
        AiAutoMemoryService aiAutoMemoryService, int order, String memorySystemPrompt) {

        this.aiAutoMemoryService = aiAutoMemoryService;
        this.order = order;
        this.memorySystemPrompt = memorySystemPrompt;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Prompt prompt = chatClientRequest.prompt();

        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolOptions)) {
            return chatClientRequest;
        }

        AiAutoMemoryOwner owner = resolveOwner(toolOptions.getToolContext());

        if (owner == null) {
            return chatClientRequest;
        }

        List<ToolCallback> memoryToolCallbacks = memoryToolCallbacks(owner);

        Prompt augmentedPrompt = prompt.augmentSystemMessage(
            prompt.getSystemMessage()
                .getText() + System.lineSeparator() + System.lineSeparator() + memorySystemPrompt);

        ToolCallingChatOptions toolOptionsCopy = toolOptions.mutate()
            .build();

        List<ToolCallback> existingToolCallbacks = toolOptionsCopy.getToolCallbacks();

        List<ToolCallback> toolCallbacks = existingToolCallbacks == null
            ? new ArrayList<>()
            : new ArrayList<>(existingToolCallbacks);

        Set<String> existingNames = toolCallbacks.stream()
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .collect(Collectors.toSet());

        memoryToolCallbacks.stream()
            .filter(toolCallback -> !existingNames.contains(toolCallback.getToolDefinition()
                .name()))
            .forEach(toolCallbacks::add);

        ToolCallingChatOptions mergedOptions = ((ToolCallingChatOptions.Builder<?>) toolOptionsCopy.mutate())
            .toolCallbacks(new ArrayList<>(toolCallbacks))
            .build();

        return chatClientRequest.mutate()
            .prompt(augmentedPrompt.mutate()
                .chatOptions(mergedOptions)
                .build())
            .build();
    }

    private List<ToolCallback> memoryToolCallbacks(AiAutoMemoryOwner owner) {
        AutoMemoryTools autoMemoryTools = new AutoMemoryTools(
            new AutoMemoryResourceResolver(aiAutoMemoryService, owner),
            new ServiceBackedAutoMemoryDirectoryOps(aiAutoMemoryService, owner));

        MethodToolCallbackProvider methodToolCallbackProvider = MethodToolCallbackProvider.builder()
            .toolObjects(autoMemoryTools)
            .build();

        return Arrays.asList(methodToolCallbackProvider.getToolCallbacks());
    }

    private static @Nullable AiAutoMemoryOwner resolveOwner(@Nullable Map<String, Object> toolContextMap) {
        if (toolContextMap == null || toolContextMap.isEmpty()) {
            return null;
        }

        AiHubToolInvocationContext context = AiHubToolInvocationContext.fromToolContext(
            new ToolContext(toolContextMap));

        if (context == null || context.workspaceId() == null || context.userId() == null) {
            return null;
        }

        Environment environment = Environment.values()[AiHubToolInvocationContext.resolveEnvironmentOrDefault(context)];

        return new AiAutoMemoryOwner(
            context.workspaceId(), AiAutoMemoryPrincipalType.USER, context.userId(), environment);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private AiAutoMemoryService aiAutoMemoryService;
        private int order = HIGHEST_PRECEDENCE + 200;
        private Resource memorySystemPrompt;

        private Builder() {
        }

        public Builder order(int order) {
            this.order = order;

            return this;
        }

        @SuppressFBWarnings("EI_EXPOSE_REP2")
        public Builder aiAutoMemoryService(AiAutoMemoryService aiAutoMemoryService) {
            this.aiAutoMemoryService = aiAutoMemoryService;

            return this;
        }

        public Builder memorySystemPrompt(Resource memorySystemPrompt) {
            Assert.notNull(memorySystemPrompt, "Memory system prompt must not be null");

            this.memorySystemPrompt = memorySystemPrompt;

            return this;
        }

        public AiHubAutoMemoryToolsAdvisor build() {
            Assert.notNull(aiAutoMemoryService, "aiAutoMemoryService must not be null");
            Assert.notNull(memorySystemPrompt, "Memory system prompt must not be null");

            PromptTemplate promptTemplate = PromptTemplate.builder()
                .resource(memorySystemPrompt)
                .build();

            return new AiHubAutoMemoryToolsAdvisor(aiAutoMemoryService, order, promptTemplate.render());
        }
    }
}
