/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.copilot.config;

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.copilot.agent.OverrideChatClientResolver;
import com.bytechef.ai.copilot.agent.SliceSpringAIAgent;
import com.bytechef.ai.copilot.tool.RehydrateContextToolCallback;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.util.Mode;
import com.bytechef.ai.copilot.util.Source;
import com.bytechef.ee.automation.ai.tool.componentrule.ComponentRuleToolCallbacksFactory;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the Component Rule Copilot panel source agents ({@code component_rule_ask}/{@code component_rule_build})
 * and the {@link ComponentRuleToolCallbacksFactory} bean they — and the AI Hub's flat/catalog component-rule
 * registrations, see {@code AiHubConfiguration#componentRuleFlatCrudToolCallbacks}/
 * {@code #componentRuleCatalogToolCallbacks} — consume. Lives in EE (not the CE {@code CopilotConfiguration}, where the
 * CE panel source agents live) because {@link ComponentRuleToolCallbacksFactory} and the {@link ComponentRuleService}
 * it wraps are both EE.
 *
 * <p>
 * Gated so the beans exist when either the Copilot panel or the AI Hub surface is enabled, since both consume them —
 * this is the {@code ContextStoreAgentConfiguration} style rather than {@code ApiCollectionAgentConfiguration}'s
 * Copilot-only {@code @ConditionalOnProperty}, which silently drops its domain's tools from both hub surfaces on a
 * deployment that runs the AI Hub with the Copilot panel off. There is deliberately no third conjunct: unlike Context
 * Store, Component Rules has no {@code bytechef.<feature>.enabled} property of its own — the feature ships whenever EE
 * does. {@code @ConditionalOnEEVersion} is still load-bearing: {@code ComponentRuleServiceImpl} is itself EE-gated, so
 * activating on a non-{@code ee} edition would fail server startup on an unsatisfiable {@link ComponentRuleService}
 * dependency. Both surfaces tolerate the absence of these beans ({@code ObjectProvider} injection).
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnEEVersion
@ConditionalOnExpression("${bytechef.ai.copilot.enabled:false} or ${bytechef.ai.hub.enabled:false}")
public class ComponentRuleAgentConfiguration {

    @Value("classpath:prompt_component_rule_ask.txt")
    private Resource promptComponentRuleAskResource;

    @Value("classpath:prompt_component_rule_build.txt")
    private Resource promptComponentRuleBuildResource;

    private final State state = new State();

    @Bean
    ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory(
        ComponentRuleService componentRuleService, ComponentDefinitionService componentDefinitionService,
        ClusterElementDefinitionService clusterElementDefinitionService, Evaluator evaluator) {

        return new ComponentRuleToolCallbacksFactory(
            componentRuleService, componentDefinitionService, clusterElementDefinitionService, evaluator);
    }

    @Bean
    SliceSpringAIAgent componentRuleAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel,
        ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.COMPONENT_RULE.name() + "_" + Mode.ASK.name();

        return SliceSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptComponentRuleAskResource))
            .state(state)
            .toolCallbacks(
                wrapToolCallbacks(securityContextRehydrator, componentRuleToolCallbacksFactory.readToolCallbacks()))
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    SliceSpringAIAgent componentRuleBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel,
        ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory,
        SecurityContextRehydrator securityContextRehydrator,
        ObjectProvider<OverrideChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.COMPONENT_RULE.name() + "_" + Mode.BUILD.name();

        return SliceSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(readPrompt(promptComponentRuleBuildResource))
            .state(state)
            .toolCallbacks(
                wrapToolCallbacks(securityContextRehydrator, componentRuleToolCallbacksFactory.writeToolCallbacks()))
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
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
                "Failed to read component rule prompt resource: " + resource.getDescription(), exception);
        }
    }
}
