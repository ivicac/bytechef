/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.config;

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.mcp.tool.automation.ClusterElementTools;
import com.bytechef.ai.mcp.tool.automation.ProjectTools;
import com.bytechef.ai.mcp.tool.automation.ProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ReadProjectTools;
import com.bytechef.ai.mcp.tool.automation.ReadProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ScriptTools;
import com.bytechef.ai.mcp.tool.automation.WorkflowExecutionTools;
import com.bytechef.ai.mcp.tool.platform.ComponentTools;
import com.bytechef.ai.mcp.tool.platform.FirecrawlTools;
import com.bytechef.ai.mcp.tool.platform.TaskTools;
import com.bytechef.ai.mcp.tool.platform.WorkflowInstructionTools;
import com.bytechef.ai.mcp.tool.platform.WorkflowValidatorTools;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.ee.ai.copilot.agent.ClusterElementSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.CodeEditorSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.ConverterSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.CopilotChatClientResolver;
import com.bytechef.ee.ai.copilot.agent.SkillsSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.WorkflowCodeEditorSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.WorkflowEditorSpringAIAgent;
import com.bytechef.ee.ai.copilot.agent.WorkflowExecutionSpringAIAgent;
import com.bytechef.ee.ai.copilot.util.Mode;
import com.bytechef.ee.ai.copilot.util.Source;
import com.bytechef.ee.ai.mcp.tool.automation.ReadSkillsTools;
import com.bytechef.ee.ai.mcp.tool.automation.SkillsTools;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * @version ee
 *
 * @author Marko Kriskovic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
public class CopilotConfiguration {

    private final Resource promptWorkflowEditorAskResource;
    private final Resource promptWorkflowEditorBuildResource;
    private final Resource promptCodeEditorAskResource;
    private final Resource promptCodeEditorBuildResource;
    private final Resource promptWorkflowCodeEditorAskResource;
    private final Resource promptWorkflowCodeEditorBuildResource;
    private final Resource promptConverterBuildResource;
    private final Resource promptClusterElementAskResource;
    private final Resource promptClusterElementBuildResource;
    private final Resource promptSkillsAskResource;
    private final Resource promptSkillsBuildResource;
    private final Resource promptWorkflowExecutionAskResource;
    private final Resource promptWorkflowExecutionBuildResource;
    private final WorkflowValidatorTools workflowValidatorTools;
    private final WorkflowInstructionTools workflowInstructionTools;
    private final State state = new State();

    @SuppressFBWarnings("EI")
    public CopilotConfiguration(
        @Value("classpath:prompt_workflow_editor_ask.txt") Resource promptWorkflowEditorAskResource,
        @Value("classpath:prompt_workflow_editor_build.txt") Resource promptWorkflowEditorBuildResource,
        @Value("classpath:prompt_code_editor_ask.txt") Resource promptCodeEditorAskResource,
        @Value("classpath:prompt_code_editor_build.txt") Resource promptCodeEditorBuildResource,
        @Value("classpath:prompt_workflow_code_editor_ask.txt") Resource promptWorkflowCodeEditorAskResource,
        @Value("classpath:prompt_workflow_code_editor_build.txt") Resource promptWorkflowCodeEditorBuildResource,
        @Value("classpath:prompt_converter_build.txt") Resource promptConverterBuildResource,
        @Value("classpath:prompt_cluster_element_ask.txt") Resource promptClusterElementAskResource,
        @Value("classpath:prompt_cluster_element_build.txt") Resource promptClusterElementBuildResource,
        @Value("classpath:prompt_skills_ask.txt") Resource promptSkillsAskResource,
        @Value("classpath:prompt_skills_build.txt") Resource promptSkillsBuildResource,
        @Value("classpath:prompt_workflow_execution_ask.txt") Resource promptWorkflowExecutionAskResource,
        @Value("classpath:prompt_workflow_execution_build.txt") Resource promptWorkflowExecutionBuildResource,
        WorkflowValidatorTools workflowValidatorTools, WorkflowInstructionTools workflowInstructionTools) {

        this.workflowValidatorTools = workflowValidatorTools;
        this.workflowInstructionTools = workflowInstructionTools;
        this.promptWorkflowEditorAskResource = promptWorkflowEditorAskResource;
        this.promptWorkflowEditorBuildResource = promptWorkflowEditorBuildResource;
        this.promptCodeEditorAskResource = promptCodeEditorAskResource;
        this.promptCodeEditorBuildResource = promptCodeEditorBuildResource;
        this.promptWorkflowCodeEditorAskResource = promptWorkflowCodeEditorAskResource;
        this.promptWorkflowCodeEditorBuildResource = promptWorkflowCodeEditorBuildResource;
        this.promptConverterBuildResource = promptConverterBuildResource;
        this.promptClusterElementAskResource = promptClusterElementAskResource;
        this.promptClusterElementBuildResource = promptClusterElementBuildResource;
        this.promptSkillsAskResource = promptSkillsAskResource;
        this.promptSkillsBuildResource = promptSkillsBuildResource;
        this.promptWorkflowExecutionAskResource = promptWorkflowExecutionAskResource;
        this.promptWorkflowExecutionBuildResource = promptWorkflowExecutionBuildResource;
    }

    @Bean
    CodeEditorSpringAIAgent codeEditorAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, Optional<FirecrawlTools> firecrawlTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {
        String name = Source.CODE_EDITOR.name() + "_" + Mode.ASK.name();

        List<Object> tools = new ArrayList<>(
            List.of(readProjectWorkflowTools, componentTools, workflowValidatorTools, workflowInstructionTools));

        firecrawlTools.ifPresent(tools::add);

        return CodeEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptCodeEditorAskResource))
            .tools(tools)
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    CodeEditorSpringAIAgent codeEditorBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ScriptTools scriptTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.CODE_EDITOR.name() + "_" + Mode.BUILD.name();

        return CodeEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptCodeEditorBuildResource))
            .tools(
                List.of(
                    readProjectWorkflowTools, scriptTools, componentTools, workflowValidatorTools,
                    workflowInstructionTools))
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    WorkflowCodeEditorSpringAIAgent workflowCodeEditorAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools, Optional<FirecrawlTools> firecrawlTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {

        String name = Source.WORKFLOW_CODE_EDITOR.name() + "_" + Mode.ASK.name();

        List<Object> tools = new ArrayList<>(
            List.of(
                readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools));

        firecrawlTools.ifPresent(tools::add);

        return WorkflowCodeEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowCodeEditorAskResource))
            .tools(tools)
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    WorkflowCodeEditorSpringAIAgent workflowCodeEditorBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools, Optional<FirecrawlTools> firecrawlTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {

        String name = Source.WORKFLOW_CODE_EDITOR.name() + "_" + Mode.BUILD.name();

        List<Object> tools = new ArrayList<>(
            List.of(
                readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools));

        firecrawlTools.ifPresent(tools::add);

        return WorkflowCodeEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowCodeEditorBuildResource))
            .tools(tools)
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    ClusterElementSpringAIAgent clusterElementAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {

        String name = Source.CLUSTER_ELEMENT.name() + "_" + Mode.ASK.name();

        return ClusterElementSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptClusterElementAskResource))
            .tools(
                List.of(
                    readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                    workflowInstructionTools))
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    ClusterElementSpringAIAgent clusterElementBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ClusterElementTools clusterElementTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools, TaskTools taskTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.CLUSTER_ELEMENT.name() + "_" + Mode.BUILD.name();

        return ClusterElementSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptClusterElementBuildResource))
            .tools(
                List.of(
                    readProjectWorkflowTools, clusterElementTools, componentTools, taskTools, workflowValidatorTools,
                    workflowInstructionTools))
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore copilotPgVectorStore) {
        return QuestionAnswerAdvisor.builder(copilotPgVectorStore)
            .build();
    }

    @Bean
    WorkflowEditorSpringAIAgent workflowEditorAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools, TaskTools taskTools,
        Optional<FirecrawlTools> firecrawlTools, WorkflowService workflowService,
        WorkflowNodeOutputFacade workflowNodeOutputFacade, QuestionAnswerAdvisor questionAnswerAdvisor,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.WORKFLOW_EDITOR.name() + "_" + Mode.ASK.name();

        List<Object> tools = new ArrayList<>(
            List.of(
                readProjectTools, readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools));

        firecrawlTools.ifPresent(tools::add);

        return WorkflowEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowEditorAskResource))
            .state(state)
            .tools(tools)
            .advisor(questionAnswerAdvisor)
            .workflowService(workflowService)
            .workflowNodeOutputFacade(workflowNodeOutputFacade)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    WorkflowEditorSpringAIAgent workflowEditorBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ProjectTools projectTools,
        ProjectWorkflowTools projectWorkflowTools, ComponentTools componentTools, TaskTools taskTools,
        ScriptTools scriptTools, WorkflowService workflowService, WorkflowNodeOutputFacade workflowNodeOutputFacade,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider)
        throws AGUIException {

        String name = Source.WORKFLOW_EDITOR.name() + "_" + Mode.BUILD.name();

        return WorkflowEditorSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowEditorBuildResource))
            .state(state)
            .tools(
                List.of(
                    projectTools, projectWorkflowTools, componentTools, taskTools, scriptTools, workflowValidatorTools,
                    workflowInstructionTools))
            .workflowService(workflowService)
            .workflowNodeOutputFacade(workflowNodeOutputFacade)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    ConverterSpringAIAgent converterBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ProjectTools projectTools,
        ProjectWorkflowTools projectWorkflowTools, TaskTools taskTools, ScriptTools scriptTools)
        throws AGUIException {

        String name = Source.CONVERTER.name() + "_" + Mode.BUILD.name();

        return ConverterSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptConverterBuildResource))
            .state(state)
            .tools(
                List.of(
                    projectTools, projectWorkflowTools, taskTools, scriptTools, workflowValidatorTools,
                    workflowInstructionTools))
            .build();
    }

    @Bean
    SkillsSpringAIAgent skillsAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ReadSkillsTools readSkillsTools)
        throws AGUIException {

        String name = Source.SKILLS.name() + "_" + Mode.ASK.name();

        return SkillsSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptSkillsAskResource))
            .state(state)
            .tools(
                List.of(
                    readSkillsTools, readProjectTools, readProjectWorkflowTools, workflowValidatorTools,
                    workflowInstructionTools))
            .build();
    }

    @Bean
    SkillsSpringAIAgent skillsBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, SkillsTools skillsTools)
        throws AGUIException {

        String name = Source.SKILLS.name() + "_" + Mode.BUILD.name();

        return SkillsSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptSkillsBuildResource))
            .state(state)
            .tools(
                List.of(
                    skillsTools, readProjectTools, readProjectWorkflowTools, workflowValidatorTools,
                    workflowInstructionTools))
            .build();
    }

    @Bean
    WorkflowExecutionSpringAIAgent workflowExecutionAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, WorkflowExecutionTools workflowExecutionTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools,
        Optional<FirecrawlTools> firecrawlTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {

        String name = Source.WORKFLOW_EXECUTION.name() + "_" + Mode.ASK.name();

        List<Object> tools = new ArrayList<>(
            List.of(
                workflowExecutionTools, readProjectWorkflowTools, componentTools, workflowValidatorTools,
                workflowInstructionTools));

        firecrawlTools.ifPresent(tools::add);

        return WorkflowExecutionSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowExecutionAskResource))
            .tools(tools)
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    @Bean
    WorkflowExecutionSpringAIAgent workflowExecutionBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, WorkflowExecutionTools workflowExecutionTools,
        ProjectWorkflowTools projectWorkflowTools, ScriptTools scriptTools, TaskTools taskTools,
        ObjectProvider<CopilotChatClientResolver> overrideChatClientResolverProvider) throws AGUIException {

        String name = Source.WORKFLOW_EXECUTION.name() + "_" + Mode.BUILD.name();

        List<Object> tools = new ArrayList<>(
            List.of(
                workflowExecutionTools, projectWorkflowTools, scriptTools, taskTools, workflowValidatorTools,
                workflowInstructionTools));

        return WorkflowExecutionSpringAIAgent.builder()
            .agentId(name.toLowerCase())
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptWorkflowExecutionBuildResource))
            .tools(tools)
            .state(state)
            .overrideChatClientResolver(overrideChatClientResolverProvider.getIfAvailable())
            .build();
    }

    /**
     * Stateless Code Editor ASK sub-agent {@link ChatClient}, consumed by {@code CodeEditorAgentToolCallback} on the AI
     * Hub ASK agent. Same system prompt and tool catalog as {@link #codeEditorAskSpringAIAgent} (Firecrawl tools added
     * when present in the deployment), no {@link ChatMemory}.
     */
    @Bean
    ChatClient codeEditorAskSubAgentChatClient(
        ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, Optional<FirecrawlTools> firecrawlTools) {

        ChatClient.Builder builder = ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptCodeEditorAskResource));

        if (firecrawlTools.isPresent()) {
            builder.defaultTools(
                readProjectWorkflowTools, componentTools, workflowValidatorTools, workflowInstructionTools,
                firecrawlTools.get());
        } else {
            builder.defaultTools(
                readProjectWorkflowTools, componentTools, workflowValidatorTools, workflowInstructionTools);
        }

        return builder.build();
    }

    /**
     * Stateless Code Editor BUILD sub-agent {@link ChatClient}, consumed by {@code CodeEditorAgentToolCallback} on the
     * AI Hub BUILD agent. Adds {@link ScriptTools} for write-capable script mutations, mirroring
     * {@link #codeEditorBuildSpringAIAgent}.
     */
    @Bean
    ChatClient codeEditorBuildSubAgentChatClient(
        ChatModel chatModel, ScriptTools scriptTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptCodeEditorBuildResource))
            .defaultTools(
                readProjectWorkflowTools, scriptTools, componentTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Workflow Editor ASK sub-agent {@link ChatClient}, consumed by {@code WorkflowEditorAgentToolCallback}
     * on the AI Hub ASK agent. Same system prompt and tool catalog as {@link #workflowEditorAskSpringAIAgent},
     * including the {@link QuestionAnswerAdvisor} RAG grounding (Firecrawl tools added when present), no
     * {@link ChatMemory}.
     */
    @Bean
    ChatClient workflowEditorAskSubAgentChatClient(
        ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools, TaskTools taskTools,
        Optional<FirecrawlTools> firecrawlTools, QuestionAnswerAdvisor questionAnswerAdvisor) {

        ChatClient.Builder builder = ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptWorkflowEditorAskResource))
            .defaultAdvisors(questionAnswerAdvisor);

        if (firecrawlTools.isPresent()) {
            builder.defaultTools(
                readProjectTools, readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools, firecrawlTools.get());
        } else {
            builder.defaultTools(
                readProjectTools, readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools);
        }

        return builder.build();
    }

    /**
     * Stateless Workflow Editor BUILD sub-agent {@link ChatClient}, consumed by {@code WorkflowEditorAgentToolCallback}
     * on the AI Hub BUILD agent. Mirrors {@link #workflowEditorBuildSpringAIAgent}'s tool catalog (write-capable
     * project / project-workflow / script tools), no {@link ChatMemory}.
     */
    @Bean
    ChatClient workflowEditorBuildSubAgentChatClient(
        ChatModel chatModel, ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools, TaskTools taskTools,
        ScriptTools scriptTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptWorkflowEditorBuildResource))
            .defaultTools(
                projectTools, projectWorkflowTools, taskTools, scriptTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Converter BUILD sub-agent {@link ChatClient}, consumed by {@code ConverterAgentToolCallback} on the AI
     * Hub BUILD agent. BUILD-only — there is no ASK converter variant. Mirrors {@link #converterBuildSpringAIAgent}'s
     * tool catalog (project / project-workflow / task / script tools), no {@link ChatMemory}.
     */
    @Bean
    ChatClient converterBuildSubAgentChatClient(
        ChatModel chatModel, ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools, TaskTools taskTools,
        ScriptTools scriptTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptConverterBuildResource))
            .defaultTools(
                projectTools, projectWorkflowTools, taskTools, scriptTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Cluster Element ASK sub-agent {@link ChatClient}, consumed by {@code ClusterElementAgentToolCallback}
     * on the AI Hub ASK agent. Same system prompt and tool catalog as {@link #clusterElementAskSpringAIAgent}, no
     * {@link ChatMemory}.
     */
    @Bean
    ChatClient clusterElementAskSubAgentChatClient(
        ChatModel chatModel, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptClusterElementAskResource))
            .defaultTools(
                readProjectWorkflowTools, componentTools, taskTools, workflowValidatorTools, workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Cluster Element BUILD sub-agent {@link ChatClient}, consumed by {@code ClusterElementAgentToolCallback}
     * on the AI Hub BUILD agent. Adds {@link ClusterElementTools} for write-capable cluster-element shape mutations,
     * mirroring {@link #clusterElementBuildSpringAIAgent}.
     */
    @Bean
    ChatClient clusterElementBuildSubAgentChatClient(
        ChatModel chatModel, ClusterElementTools clusterElementTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools, TaskTools taskTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptClusterElementBuildResource))
            .defaultTools(
                readProjectWorkflowTools, clusterElementTools, componentTools, taskTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Skills ASK sub-agent {@link ChatClient}, consumed by {@code SkillsAgentToolCallback} so the AI Hub ASK
     * agent can delegate skills work without registering {@link ReadSkillsTools} directly. Same system prompt and tool
     * catalog as {@link #skillsAskSpringAIAgent}, no {@link ChatMemory} — each AI Hub turn that delegates here runs in
     * an isolated context so state doesn't bleed across AI Hub conversations.
     */
    @Bean
    ChatClient skillsAskSubAgentChatClient(
        ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ReadSkillsTools readSkillsTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptSkillsAskResource))
            .defaultTools(
                readSkillsTools, readProjectTools, readProjectWorkflowTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Skills BUILD sub-agent {@link ChatClient}, consumed by {@code SkillsAgentToolCallback} on the AI Hub
     * BUILD agent. Mirrors {@link #skillsBuildSpringAIAgent}'s prompt + tool list (write-capable {@link SkillsTools}),
     * no {@link ChatMemory}.
     */
    @Bean
    ChatClient skillsBuildSubAgentChatClient(
        ChatModel chatModel, ReadProjectTools readProjectTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, SkillsTools skillsTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptSkillsBuildResource))
            .defaultTools(
                skillsTools, readProjectTools, readProjectWorkflowTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
    }

    /**
     * Stateless Workflow Execution ASK sub-agent {@link ChatClient}, consumed by
     * {@code WorkflowExecutionAgentToolCallback} on the AI Hub ASK agent. Same system prompt and tool catalog as
     * {@link #workflowExecutionAskSpringAIAgent} (Firecrawl tools added when present in the deployment), no
     * {@link ChatMemory}.
     */
    @Bean
    ChatClient workflowExecutionAskSubAgentChatClient(
        ChatModel chatModel, WorkflowExecutionTools workflowExecutionTools,
        ReadProjectWorkflowTools readProjectWorkflowTools, ComponentTools componentTools,
        Optional<FirecrawlTools> firecrawlTools) {

        ChatClient.Builder builder = ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptWorkflowExecutionAskResource));

        if (firecrawlTools.isPresent()) {
            builder.defaultTools(
                workflowExecutionTools, readProjectWorkflowTools, componentTools, workflowValidatorTools,
                workflowInstructionTools, firecrawlTools.get());
        } else {
            builder.defaultTools(
                workflowExecutionTools, readProjectWorkflowTools, componentTools, workflowValidatorTools,
                workflowInstructionTools);
        }

        return builder.build();
    }

    /**
     * Stateless Workflow Execution BUILD sub-agent {@link ChatClient}, consumed by
     * {@code WorkflowExecutionAgentToolCallback} on the AI Hub BUILD agent. Mirrors
     * {@link #workflowExecutionBuildSpringAIAgent}'s tool catalog (write-capable project-workflow / script / task
     * tools), no {@link ChatMemory}.
     */
    @Bean
    ChatClient workflowExecutionBuildSubAgentChatClient(
        ChatModel chatModel, WorkflowExecutionTools workflowExecutionTools, ProjectWorkflowTools projectWorkflowTools,
        ScriptTools scriptTools, TaskTools taskTools) {

        return ChatClient.builder(chatModel)
            .defaultSystem(getSystemPrompt(promptWorkflowExecutionBuildResource))
            .defaultTools(
                workflowExecutionTools, projectWorkflowTools, scriptTools, taskTools, workflowValidatorTools,
                workflowInstructionTools)
            .build();
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
