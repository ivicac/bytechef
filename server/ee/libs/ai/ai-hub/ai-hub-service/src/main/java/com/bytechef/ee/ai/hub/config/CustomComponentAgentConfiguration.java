/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import com.bytechef.ee.automation.ai.tool.CustomComponentTools;
import com.bytechef.ee.automation.ai.tool.ReadCustomComponentTools;
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
 * Registers the custom-component Copilot subagent {@link ChatClient} beans consumed by the ai_hub agents via
 * {@code CustomComponentAgentToolCallback}. The ASK client is read-only; the BUILD client owns the full CRUD tool set
 * and authors/iterates component source until it compiles.
 *
 * <p>
 * These beans live in EE (not the CE {@code CopilotConfiguration}, where the skills subagent chat clients live) because
 * {@link CustomComponentTools}/{@link ReadCustomComponentTools} are EE tools. The subagents deliberately omit
 * {@code openCustomComponentTab} — the parent ai_hub agent owns opening the component in the panel.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class CustomComponentAgentConfiguration {

    @Bean
    ChatClient customComponentAskSubAgentChatClient(
        ChatModel chatModel, ReadCustomComponentTools readCustomComponentTools,
        @Value("classpath:prompt_custom_component_ask.txt") Resource promptResource) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptResource))
            .defaultTools(readCustomComponentTools)
            .build();
    }

    @Bean
    ChatClient customComponentBuildSubAgentChatClient(
        ChatModel chatModel, CustomComponentTools customComponentTools,
        ReadCustomComponentTools readCustomComponentTools,
        @Value("classpath:prompt_custom_component_build.txt") Resource promptResource) {

        return ChatClient.builder(chatModel)
            .defaultSystem(readPrompt(promptResource))
            .defaultTools(customComponentTools, readCustomComponentTools)
            .build();
    }

    private String readPrompt(Resource resource) {
        try {
            InputStream inputStream = resource.getInputStream();

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read custom component prompt resource: " + resource.getDescription(), exception);
        }
    }
}
