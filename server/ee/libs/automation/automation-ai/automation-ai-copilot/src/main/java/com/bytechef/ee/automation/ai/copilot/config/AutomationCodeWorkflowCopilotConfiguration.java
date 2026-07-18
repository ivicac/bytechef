/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.copilot.config;

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.copilot.tool.RehydrateContextToolCallback;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ee.automation.ai.copilot.agent.CodeWorkflowSpringAIAgent;
import com.bytechef.ee.automation.ai.tool.CodeWorkflowTools;
import com.bytechef.ee.automation.ai.tool.ReadCodeWorkflowTools;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the automation in-editor code-workflow Copilot agent pair ({@code code_workflow_ask} /
 * {@code code_workflow_build}). These beans join the same {@code List<LocalAgent>} the EE {@code CopilotApiController}
 * resolves by agentId, so the in-editor client reaches them through the ordinary {@code /ai/chat/{agentId}} route,
 * exactly like the embedded twin registered by {@code EmbeddedCopilotConfiguration}.
 *
 * <p>
 * The ASK agent is read-only ({@link ReadCodeWorkflowTools} only); the BUILD agent additionally owns
 * {@link CodeWorkflowTools} (create/update). Both use {@link CodeWorkflowSpringAIAgent}, a minimal
 * {@code SpringAIAgent} subclass, because there is no CE code-workflow agent class to extend. The tool callbacks are
 * wrapped with {@link RehydrateContextToolCallback} so the caller's tenant/security context is re-established on the
 * {@code boundedElastic} worker thread that executes the tool call — required because every
 * {@code ProjectCodeWorkflowFacade} method backing these tools is {@code @PreAuthorize("hasAuthority(ADMIN)")}-gated.
 * </p>
 *
 * <p>
 * Placement: this configuration lives in a dedicated {@code automation-ai-copilot} module rather than inside
 * {@code automation-ai-tool} (the module holding {@link CodeWorkflowTools}/{@link ReadCodeWorkflowTools}). Adding a
 * dependency on {@code ai-copilot-service} directly to {@code automation-ai-tool} would not create a Gradle cycle
 * (verified in both directions), but {@code automation-ai-tool} is also a dependency of {@code ai-hub-service}, whose
 * agents are wired independently of {@code ai-copilot-service}; pulling agent/ag-ui wiring into the shared tool module
 * would leak an unrelated, heavier dependency chain into that consumer and would break the "-ai-tool modules hold only
 * {@code @Tool} classes" shape both the CE and EE tool modules otherwise follow. A sibling module mirrors the precedent
 * set by {@code embedded-ai-copilot} sitting next to {@code embedded-ai-tool}.
 * </p>
 *
 * <p>
 * The {@code getSystemPrompt}/{@code wrapTools} helpers are private in the CE {@code CopilotConfiguration}, so minimal
 * local equivalents are replicated here, the same pattern {@code EmbeddedCopilotConfiguration} follows.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
public class AutomationCodeWorkflowCopilotConfiguration {

    private final Resource promptCodeWorkflowCopilotAskResource;
    private final Resource promptCodeWorkflowCopilotBuildResource;
    private final State state = new State();

    @SuppressFBWarnings("EI")
    public AutomationCodeWorkflowCopilotConfiguration(
        @Value("classpath:prompt_code_workflow_copilot_ask.txt") Resource promptCodeWorkflowCopilotAskResource,
        @Value("classpath:prompt_code_workflow_copilot_build.txt") Resource promptCodeWorkflowCopilotBuildResource) {

        this.promptCodeWorkflowCopilotAskResource = promptCodeWorkflowCopilotAskResource;
        this.promptCodeWorkflowCopilotBuildResource = promptCodeWorkflowCopilotBuildResource;
    }

    @Bean
    CodeWorkflowSpringAIAgent codeWorkflowAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadCodeWorkflowTools readCodeWorkflowTools,
        SecurityContextRehydrator securityContextRehydrator) throws AGUIException {

        return CodeWorkflowSpringAIAgent.builder()
            .agentId("code_workflow_ask")
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptCodeWorkflowCopilotAskResource))
            .state(state)
            .toolCallbacks(wrapTools(securityContextRehydrator, List.of(readCodeWorkflowTools)))
            .build();
    }

    @Bean
    CodeWorkflowSpringAIAgent codeWorkflowBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, CodeWorkflowTools codeWorkflowTools,
        ReadCodeWorkflowTools readCodeWorkflowTools, SecurityContextRehydrator securityContextRehydrator)
        throws AGUIException {

        return CodeWorkflowSpringAIAgent.builder()
            .agentId("code_workflow_build")
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptCodeWorkflowCopilotBuildResource))
            .state(state)
            .toolCallbacks(
                wrapTools(securityContextRehydrator, List.of(codeWorkflowTools, readCodeWorkflowTools)))
            .build();
    }

    /**
     * Local equivalent of the private {@code CopilotConfiguration.wrapTools}: wraps each tool's callbacks in a
     * {@link RehydrateContextToolCallback} so the caller's tenant/security context is re-established on the worker
     * thread that executes the tool.
     */
    private List<ToolCallback> wrapTools(SecurityContextRehydrator securityContextRehydrator, List<Object> tools) {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        for (Object tool : tools) {
            if (tool instanceof ToolCallback toolCallback) {
                toolCallbacks.add(RehydrateContextToolCallback.wrap(toolCallback, securityContextRehydrator));
            } else {
                for (ToolCallback toolCallback : ToolCallbacks.from(tool)) {
                    toolCallbacks.add(RehydrateContextToolCallback.wrap(toolCallback, securityContextRehydrator));
                }
            }
        }

        return toolCallbacks;
    }

    private String getSystemPrompt(Resource systemPromptResource) {
        try {
            InputStream inputStream = systemPromptResource.getInputStream();

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read system prompt resource: " + systemPromptResource.getDescription(), exception);
        }
    }
}
