/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import com.bytechef.ee.automation.ai.tool.CodeWorkflowTools;
import com.bytechef.ee.automation.ai.tool.ReadCodeWorkflowTools;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the code-workflow Copilot subagent {@link ChatClient} beans consumed by the ai_hub agents via
 * {@code CodeWorkflowAgentToolCallback}. The ASK client is read-only; the BUILD client owns the full create/update tool
 * set and authors/iterates project source until it compiles.
 *
 * <p>
 * These beans live in EE (not the CE {@code CopilotConfiguration}, where the skills subagent chat clients live) because
 * {@link CodeWorkflowTools}/{@link ReadCodeWorkflowTools} are EE tools. The subagents deliberately omit an open-tab
 * tool — the parent ai_hub agent owns opening the code workflow in the panel.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class CodeWorkflowAgentConfiguration {

    @Bean
    ChatClient codeWorkflowAskSubAgentChatClient(
        ChatModel chatModel, ReadCodeWorkflowTools readCodeWorkflowTools,
        @Value("classpath:prompt_code_workflow_ask.txt") Resource promptResource) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptResource))
            .defaultTools(readCodeWorkflowTools)
            .build();
    }

    @Bean
    ChatClient codeWorkflowBuildSubAgentChatClient(
        ChatModel chatModel, CodeWorkflowTools codeWorkflowTools, ReadCodeWorkflowTools readCodeWorkflowTools,
        @Value("classpath:prompt_code_workflow_build.txt") Resource promptResource) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptResource))
            .defaultTools(codeWorkflowTools, readCodeWorkflowTools)
            .build();
    }

    private String readPrompt(Resource resource) {
        try {
            InputStream inputStream = resource.getInputStream();

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read code workflow prompt resource: " + resource.getDescription(), exception);
        }
    }
}
