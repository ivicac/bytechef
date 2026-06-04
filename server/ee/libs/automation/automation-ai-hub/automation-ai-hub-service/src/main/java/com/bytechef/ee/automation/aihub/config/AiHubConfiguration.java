/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.config;

import com.agui.core.exception.AGUIException;
import com.agui.core.state.State;
import com.bytechef.ai.mcp.tool.automation.ClusterElementTools;
import com.bytechef.ai.mcp.tool.automation.ProjectTools;
import com.bytechef.ai.mcp.tool.automation.ProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ReadProjectTools;
import com.bytechef.ai.mcp.tool.automation.ReadProjectWorkflowTools;
import com.bytechef.ai.mcp.tool.automation.ScriptTools;
import com.bytechef.ai.mcp.tool.platform.ComponentTools;
import com.bytechef.ai.mcp.tool.platform.TaskDispatcherTools;
import com.bytechef.ai.mcp.tool.platform.TaskTools;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.assetfile.service.AssetFileFacade;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.facade.WorkspaceConnectionFacade;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.data.table.configuration.service.DataTableService;
import com.bytechef.automation.data.table.execution.service.DataTableRowService;
import com.bytechef.automation.knowledgebase.facade.WorkspaceKnowledgeBaseFacade;
import com.bytechef.automation.mcp.facade.McpProjectFacade;
import com.bytechef.ee.ai.mcp.tool.automation.ClusterElementAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.CodeEditorAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.ConverterAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.SkillsAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.WorkflowEditorAgentToolCallback;
import com.bytechef.ee.ai.mcp.tool.automation.WorkflowExecutionAgentToolCallback;
import com.bytechef.ee.automation.aihub.agent.AiHubRoutingAgent;
import com.bytechef.ee.automation.aihub.agent.WebhookBridgeAgent;
import com.bytechef.ee.automation.aihub.artifact.ArtifactGeneratorRegistry;
import com.bytechef.ee.automation.aihub.tool.AddDataTableColumnToolCallback;
import com.bytechef.ee.automation.aihub.tool.AddDataTableRowToolCallback;
import com.bytechef.ee.automation.aihub.tool.AddKnowledgeBaseDocumentToolCallback;
import com.bytechef.ee.automation.aihub.tool.CloneApiCollectionToolCallback;
import com.bytechef.ee.automation.aihub.tool.CloneAssetFileToolCallback;
import com.bytechef.ee.automation.aihub.tool.CloneDataTableToolCallback;
import com.bytechef.ee.automation.aihub.tool.CloneKnowledgeBaseToolCallback;
import com.bytechef.ee.automation.aihub.tool.CloneMcpProjectToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateApiCollectionToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateAssetFileToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateContextStoreSourceToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateDataTableFromCsvToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateMcpProjectToolCallback;
import com.bytechef.ee.automation.aihub.tool.CreateProjectDeploymentToolCallback;
import com.bytechef.ee.automation.aihub.tool.DeleteContextStoreSourceToolCallback;
import com.bytechef.ee.automation.aihub.tool.DeleteDataTableRowToolCallback;
import com.bytechef.ee.automation.aihub.tool.DeleteKnowledgeBaseDocumentToolCallback;
import com.bytechef.ee.automation.aihub.tool.DeleteProjectDeploymentToolCallback;
import com.bytechef.ee.automation.aihub.tool.DescribeSourceComponentEntitiesToolCallback;
import com.bytechef.ee.automation.aihub.tool.GetAssetFileContentToolCallback;
import com.bytechef.ee.automation.aihub.tool.GetContextStoreRecordToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListApiCollectionsToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListAssetFilesToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListAvailableSourceComponentsToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListChatWorkflowsToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListConnectionsForComponentToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListContextSourcesToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListDataTablesToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListKnowledgeBasesToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListMcpServersToolCallback;
import com.bytechef.ee.automation.aihub.tool.ListProjectDeploymentsToolCallback;
import com.bytechef.ee.automation.aihub.tool.PromoteWorkflowToolCallback;
import com.bytechef.ee.automation.aihub.tool.PropertyOptionsResolver;
import com.bytechef.ee.automation.aihub.tool.QueryDataTableToolCallback;
import com.bytechef.ee.automation.aihub.tool.QueryKnowledgeBaseToolCallback;
import com.bytechef.ee.automation.aihub.tool.RefreshContextStoreSourceToolCallback;
import com.bytechef.ee.automation.aihub.tool.RollbackProjectDeploymentToolCallback;
import com.bytechef.ee.automation.aihub.tool.RunChatWorkflowToolCallback;
import com.bytechef.ee.automation.aihub.tool.SearchContextStoreToolCallback;
import com.bytechef.ee.automation.aihub.tool.SemanticSearchContextStoreToolCallback;
import com.bytechef.ee.automation.aihub.tool.SetContextStoreSourceEnabledToolCallback;
import com.bytechef.ee.automation.aihub.tool.ToggleProjectDeploymentToolCallback;
import com.bytechef.ee.automation.aihub.tool.UpdateContextStoreSourceToolCallback;
import com.bytechef.ee.automation.aihub.tool.UpdateDataTableRowToolCallback;
import com.bytechef.ee.automation.aihub.tool.UpdateProjectDeploymentToolCallback;
import com.bytechef.ee.automation.apiplatform.configuration.facade.ApiCollectionFacade;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreSourceFacade;
import com.bytechef.ee.automation.contextstore.service.WorkspaceContextStoreSourceService;
import com.bytechef.ee.platform.aihub.agent.AiHubSpringAIAgent;
import com.bytechef.ee.platform.aihub.agent.WebhookResumeRegistry;
import com.bytechef.ee.platform.aihub.agent.WorkflowChatGuard;
import com.bytechef.ee.platform.aihub.agent.WorkflowChatJobRegistry;
import com.bytechef.ee.platform.aihub.config.ResearchConfiguration;
import com.bytechef.ee.platform.aihub.metric.AiHubToolAttachMetrics;
import com.bytechef.ee.platform.aihub.metric.WorkflowChatMetrics;
import com.bytechef.ee.platform.aihub.personalagent.AiHubPersonalAgentService;
import com.bytechef.ee.platform.aihub.progress.ProgressReportingToolCallback;
import com.bytechef.ee.platform.aihub.task.AiHubTask;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactService;
import com.bytechef.ee.platform.aihub.task.AiHubTaskService;
import com.bytechef.ee.platform.aihub.task.AiHubTaskToolFacade;
import com.bytechef.ee.platform.aihub.tool.AiHubTaskArtifactRecorder;
import com.bytechef.ee.platform.aihub.tool.AskUserQuestionToolCallback;
import com.bytechef.ee.platform.aihub.tool.AttachTaskToolToolCallback;
import com.bytechef.ee.platform.aihub.tool.CloneAiHubPersonalAgentToolCallback;
import com.bytechef.ee.platform.aihub.tool.CreateAiHubPersonalAgentToolCallback;
import com.bytechef.ee.platform.aihub.tool.CreateConnectionToolCallback;
import com.bytechef.ee.platform.aihub.tool.CreateWorkflowChatToolCallback;
import com.bytechef.ee.platform.aihub.tool.DeleteAiHubPersonalAgentToolCallback;
import com.bytechef.ee.platform.aihub.tool.ListAiHubPersonalAgentsToolCallback;
import com.bytechef.ee.platform.aihub.tool.ListAiHubTasksToolCallback;
import com.bytechef.ee.platform.aihub.tool.ListTaskToolsToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenAiHubPersonalAgentTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenDataTableTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenFileTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenKnowledgeBaseTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenWorkflowChatTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.OpenWorkflowTabToolCallback;
import com.bytechef.ee.platform.aihub.tool.RemoveTaskToolToolCallback;
import com.bytechef.ee.platform.aihub.tool.SelectConnectionToolCallback;
import com.bytechef.ee.platform.aihub.tool.UpdateAiHubPersonalAgentToolCallback;
import com.bytechef.ee.platform.aihub.tool.memory.DbAutoMemoryDirectoryOps;
import com.bytechef.ee.platform.aihub.tool.memory.DbMemoryResourceResolver;
import com.bytechef.ee.platform.aihub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.platform.aihub.toolsearch.AiHubTaskBindingToolCallbackResolver;
import com.bytechef.ee.platform.aihub.toolsearch.ToolSearchCatalogFeeder;
import com.bytechef.ee.platform.aihub.util.Mode;
import com.bytechef.ee.platform.aihub.util.Source;
import com.bytechef.ee.platform.contextstore.service.ContextStoreQueryService;
import com.bytechef.ee.platform.contextstore.service.ContextStoreSemanticSearchService;
import com.bytechef.platform.ai.agent.memory.AutoMemoryTools;
import com.bytechef.platform.ai.agent.memory.AutoMemoryToolsAdvisor;
import com.bytechef.platform.ai.auto.memory.AiAutoMemoryService;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.component.service.ConnectionDefinitionService;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.facade.WorkflowFacade;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutionFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI Hub LLM agent wiring — declares the {@code ai_hub_ask} / {@code ai_hub_build} routing agents (registered as AG-UI
 * {@link com.agui.server.LocalAgent} beans), the underlying Spring AI agents that the routing layer delegates to for
 * {@code kind = STANDARD} turns, and the {@link WebhookBridgeAgent} that handles {@code kind = WORKFLOW_CHAT} turns.
 *
 * <p>
 * The {@code @Bean} methods consume both CC domain types (task / memory / personal-agent services + Command
 * Center–owned tool callbacks) and shared LLM infrastructure (chat memory advisor, tool search advisor, project /
 * data-table / knowledge-base / mcp-project tool callbacks).
 * </p>
 *
 * <p>
 * Gated on {@code bytechef.ai.hub.enabled=true} so a deployment can toggle the AI Hub surface independently of any
 * other AI agents that may be enabled in the same process.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubConfiguration {

    private final Resource promptAiHubAskResource;
    private final Resource promptAiHubAutoMemoryToolsResource;
    private final Resource promptAiHubBuildResource;
    private final State state = new State();

    @SuppressFBWarnings("EI")
    public AiHubConfiguration(
        @Value("classpath:prompt_ai_hub_ask.txt") Resource promptAiHubAskResource,
        @Value("classpath:prompt/ai_hub_auto_memory_tools_system_prompt.md") Resource promptAiHubAutoMemoryToolsResource,
        @Value("classpath:prompt_ai_hub_build.txt") Resource promptAiHubBuildResource) {

        this.promptAiHubAskResource = promptAiHubAskResource;
        this.promptAiHubAutoMemoryToolsResource = promptAiHubAutoMemoryToolsResource;
        this.promptAiHubBuildResource = promptAiHubBuildResource;
    }

    @Bean
    AiHubSpringAIAgent aiHubAskSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ObjectProvider<ToolCallback> toolCallbackProvider,
        @Qualifier("researchChatClient") ObjectProvider<ChatClient> researchChatClientProvider,
        @Qualifier("skillsAskSubAgentChatClient") ObjectProvider<ChatClient> skillsAskSubAgentChatClientProvider,
        @Qualifier("clusterElementAskSubAgentChatClient") //
        ObjectProvider<ChatClient> clusterElementAskSubAgentChatClientProvider,
        @Qualifier("codeEditorAskSubAgentChatClient") //
        ObjectProvider<ChatClient> codeEditorAskSubAgentChatClientProvider,
        @Qualifier("workflowEditorAskSubAgentChatClient") //
        ObjectProvider<ChatClient> workflowEditorAskSubAgentChatClientProvider,
        @Qualifier("workflowExecutionAskSubAgentChatClient") //
        ObjectProvider<ChatClient> workflowExecutionAskSubAgentChatClientProvider,
        ArtifactGeneratorRegistry artifactGeneratorRegistry, AiHubTaskService taskService,
        DataTableService dataTableService,
        DataTableRowService dataTableRowService, WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade,
        KnowledgeBaseFacade knowledgeBaseFacade, KnowledgeBaseService knowledgeBaseService,
        AiHubTaskToolFacade taskToolFacade,
        ClusterElementDefinitionService clusterElementDefinitionService,
        ComponentDefinitionService componentDefinitionService,
        ConnectionDefinitionService connectionDefinitionService,
        ConnectionService connectionService,
        WorkspaceConnectionFacade workspaceConnectionFacade,
        UserService userService, AuthorityService authorityService,
        PropertyOptionsResolver propertyOptionsResolver,
        ObjectProvider<ContextStoreQueryService> contextStoreQueryServiceProvider,
        ObjectProvider<ContextStoreSemanticSearchService> contextStoreSemanticSearchServiceProvider,
        ObjectProvider<WorkspaceContextStoreSourceService> workspaceContextStoreSourceServiceProvider,
        ObjectProvider<ApiCollectionFacade> apiCollectionFacadeProvider,
        ObjectProvider<AiHubPersonalAgentService> aiHubPersonalAgentServiceProvider,
        @Qualifier("aiHubAskToolSearchToolCallAdvisor") //
        ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider,
        ObjectProvider<AiHubTaskBindingToolCallbackResolver> taskBindingToolCallbackResolverProvider,
        ObjectProvider<AiHubSpringAIAgent.OverrideChatClientResolver> overrideChatClientResolverProvider,
        AiHubToolAttachMetrics aiHubToolAttachMetrics, JsonMapper jsonMapper)
        throws AGUIException {

        String name = Source.AI_HUB.name() + "_" + Mode.ASK.name();

        List<ToolCallback> toolCallbacks = new ArrayList<>(toolCallbackProvider.orderedStream()
            .toList());

        researchChatClientProvider.ifAvailable(
            researchChatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    ResearchConfiguration.createResearchToolCallback(researchChatClient), "research")));

        toolCallbacks.add(new OpenFileTabToolCallback());
        toolCallbacks.add(new OpenWorkflowTabToolCallback());
        toolCallbacks.add(new OpenWorkflowChatTabToolCallback());
        toolCallbacks.add(new OpenDataTableTabToolCallback());
        toolCallbacks.add(new OpenKnowledgeBaseTabToolCallback());
        toolCallbacks.add(new ListDataTablesToolCallback(dataTableService));
        toolCallbacks.add(
            new QueryDataTableToolCallback(
                artifactGeneratorRegistry, taskService, dataTableRowService, dataTableService));
        toolCallbacks.add(
            new ListKnowledgeBasesToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(
            new QueryKnowledgeBaseToolCallback(knowledgeBaseFacade, knowledgeBaseService));
        toolCallbacks.add(
            new AttachTaskToolToolCallback(taskService, taskToolFacade, connectionService, aiHubToolAttachMetrics));
        toolCallbacks.add(
            new RemoveTaskToolToolCallback(taskService, taskToolFacade));
        toolCallbacks.add(new AskUserQuestionToolCallback(aiHubToolAttachMetrics, jsonMapper));

        // Task / connection state visibility — read-only. Lets the LLM avoid duplicate attaches and pick
        // existing connections before escalating to createConnection.
        registerToolAttachStateVisibilityToolCallbacks(
            toolCallbacks, taskService, taskToolFacade, connectionDefinitionService, workspaceConnectionFacade,
            propertyOptionsResolver, aiHubToolAttachMetrics, jsonMapper);

        // Resource-discovery tools surface workspace state the LLM may want to reference but doesn't yet have a
        // way to enumerate. Read-only — both the ASK and BUILD agents register them so a casual ASK turn can
        // resolve "the staging customers API" / "my last research thread" to a concrete id without forcing the
        // user to switch into BUILD just to look something up. Workflow-execution lookups are now delegated to
        // the workflow_execution_agent specialist (registered via registerCopilotSubAgentToolCallbacks).
        toolCallbacks.add(new ListAiHubTasksToolCallback(taskService));

        apiCollectionFacadeProvider.ifAvailable(
            apiCollectionFacade -> toolCallbacks.add(new ListApiCollectionsToolCallback(apiCollectionFacade)));

        aiHubPersonalAgentServiceProvider.ifAvailable(aiHubPersonalAgentService -> {
            toolCallbacks.add(new ListAiHubPersonalAgentsToolCallback(aiHubPersonalAgentService));
            toolCallbacks.add(new OpenAiHubPersonalAgentTabToolCallback(aiHubPersonalAgentService,
                taskService));
        });

        // Copilot specialist sub-agent delegation. Each is registered only when its backing ChatClient
        // bean is present (the Copilot gate bytechef.ai.copilot.enabled is independent of AI Hub's
        // gate — if Copilot is disabled the beans are absent and the registrations are silently
        // skipped). Converter is BUILD-only and passed as null here.
        registerCopilotSubAgentToolCallbacks(
            toolCallbacks, skillsAskSubAgentChatClientProvider, clusterElementAskSubAgentChatClientProvider,
            codeEditorAskSubAgentChatClientProvider, workflowEditorAskSubAgentChatClientProvider, null,
            workflowExecutionAskSubAgentChatClientProvider);

        // Context Store consume + discovery — read-only; safe on the ASK agent. Define-side callbacks
        // (create/update/delete/refresh/setEnabled) are mutations and live on the BUILD agent only.
        registerContextStoreReadOnlyToolCallbacks(
            toolCallbacks, contextStoreQueryServiceProvider, workspaceContextStoreSourceServiceProvider,
            clusterElementDefinitionService);
        registerContextStoreSemanticSearchToolCallback(
            toolCallbacks, contextStoreSemanticSearchServiceProvider, workspaceContextStoreSourceServiceProvider);

        AiHubSpringAIAgent.Builder builder = AiHubSpringAIAgent.builder()
            .agentId(name.toLowerCase() + "_llm")
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptAiHubAskResource))
            .toolCallbacks(toolCallbacks)
            .state(state)
            // Used by RehydrateSecurityContextToolCallback wrapper to look up the invocation user +
            // authorities so every tool runs under the user's SecurityContext on Reactor scheduler threads.
            // Without this, @PreAuthorize-protected facade calls (ProjectFacadeImpl, etc.) throw
            // AuthorizationDeniedException because the bounded-elastic thread has no SecurityContext.
            .userService(userService)
            .authorityService(authorityService);

        toolSearchToolCallAdvisorProvider.ifAvailable(builder::advisor);

        taskBindingToolCallbackResolverProvider.ifAvailable(builder::taskToolBindingResolver);

        // Per-personal-agent LLM model override. Bean is only present when AI Gateway is enabled; absent → no
        // override capability, agents fall back to workspace default ChatClient (unchanged behaviour).
        overrideChatClientResolverProvider.ifAvailable(builder::overrideChatClientResolver);

        return builder.build();
    }

    @Bean
    AiHubSpringAIAgent aiHubBuildSpringAIAgent(
        ChatMemory chatMemory, ChatModel chatModel, ObjectProvider<ToolCallback> toolCallbackProvider,
        @Qualifier("researchChatClient") ObjectProvider<ChatClient> researchChatClientProvider,
        @Qualifier("dataAnalystChatClient") ObjectProvider<ChatClient> dataAnalystChatClientProvider,
        @Qualifier("imageGeneratorChatClient") ObjectProvider<ChatClient> imageGeneratorChatClientProvider,
        @Qualifier("slideBuilderChatClient") ObjectProvider<ChatClient> slideBuilderChatClientProvider,
        @Qualifier("skillsBuildSubAgentChatClient") ObjectProvider<ChatClient> skillsBuildSubAgentChatClientProvider,
        @Qualifier("clusterElementBuildSubAgentChatClient") //
        ObjectProvider<ChatClient> clusterElementBuildSubAgentChatClientProvider,
        @Qualifier("codeEditorBuildSubAgentChatClient") //
        ObjectProvider<ChatClient> codeEditorBuildSubAgentChatClientProvider,
        @Qualifier("workflowEditorBuildSubAgentChatClient") //
        ObjectProvider<ChatClient> workflowEditorBuildSubAgentChatClientProvider,
        @Qualifier("workflowExecutionBuildSubAgentChatClient") //
        ObjectProvider<ChatClient> workflowExecutionBuildSubAgentChatClientProvider,
        @Qualifier("converterBuildSubAgentChatClient") //
        ObjectProvider<ChatClient> converterBuildSubAgentChatClientProvider,
        ArtifactGeneratorRegistry artifactGeneratorRegistry,
        AssetFileFacade assetFileFacade, AiHubTaskArtifactService taskArtifactService,
        AiHubTaskArtifactRecorder aiHubTaskArtifactRecorder,
        AiHubTaskService taskService, AiAutoMemoryService aiHubMemoryService,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService,
        ProjectWorkflowService projectWorkflowService,
        TriggerDefinitionService triggerDefinitionService,
        WorkflowFacade workflowFacade, WorkflowService workflowService, DataTableService dataTableService,
        DataTableRowService dataTableRowService, WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade,
        KnowledgeBaseFacade knowledgeBaseFacade, KnowledgeBaseService knowledgeBaseService,
        com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
        com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        ComponentDefinitionService componentDefinitionService,
        ClusterElementDefinitionService clusterElementDefinitionService,
        ConnectionDefinitionService connectionDefinitionService,
        ConnectionService connectionService,
        WorkspaceConnectionFacade workspaceConnectionFacade,
        UserService userService, AuthorityService authorityService,
        PropertyOptionsResolver propertyOptionsResolver,
        AiHubTaskToolFacade taskToolFacade,
        ObjectProvider<WorkspaceContextStoreSourceFacade> workspaceContextStoreSourceFacadeProvider,
        ObjectProvider<ContextStoreQueryService> contextStoreQueryServiceProvider,
        ObjectProvider<ContextStoreSemanticSearchService> contextStoreSemanticSearchServiceProvider,
        ObjectProvider<WorkspaceContextStoreSourceService> workspaceContextStoreSourceServiceProvider,
        ObjectProvider<ApiCollectionFacade> apiCollectionFacadeProvider,
        ObjectProvider<McpProjectFacade> mcpProjectFacadeProvider,
        ObjectProvider<com.bytechef.automation.mcp.facade.WorkspaceMcpServerFacade> workspaceMcpServerFacadeProvider,
        ObjectProvider<AiHubPersonalAgentService> aiHubPersonalAgentServiceProvider,
        @Qualifier("aiHubBuildToolSearchToolCallAdvisor") //
        ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider,
        ObjectProvider<AiHubTaskBindingToolCallbackResolver> taskBindingToolCallbackResolverProvider,
        ObjectProvider<AiHubSpringAIAgent.OverrideChatClientResolver> overrideChatClientResolverProvider,
        AiHubToolAttachMetrics aiHubToolAttachMetrics, JsonMapper jsonMapper)
        throws AGUIException {

        String name = Source.AI_HUB.name() + "_" + Mode.BUILD.name();

        List<ToolCallback> toolCallbacks = new ArrayList<>(toolCallbackProvider.orderedStream()
            .toList());

        registerSubAgentToolCallbacks(
            toolCallbacks, researchChatClientProvider, dataAnalystChatClientProvider,
            imageGeneratorChatClientProvider, slideBuilderChatClientProvider, assetFileFacade);

        toolCallbacks.add(new OpenFileTabToolCallback());
        toolCallbacks.add(new OpenWorkflowTabToolCallback());
        toolCallbacks.add(new OpenWorkflowChatTabToolCallback());
        toolCallbacks.add(new OpenDataTableTabToolCallback());
        toolCallbacks.add(new OpenKnowledgeBaseTabToolCallback());
        toolCallbacks.add(new ListDataTablesToolCallback(dataTableService));
        toolCallbacks.add(
            new QueryDataTableToolCallback(
                artifactGeneratorRegistry, taskService, dataTableRowService, dataTableService));
        toolCallbacks.add(
            new ListKnowledgeBasesToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(
            new QueryKnowledgeBaseToolCallback(knowledgeBaseFacade, knowledgeBaseService));

        registerDataTableMutationToolCallbacks(
            toolCallbacks, dataTableRowService, dataTableService, taskArtifactService);
        toolCallbacks.add(
            new AddKnowledgeBaseDocumentToolCallback(
                knowledgeBaseDocumentFacade, workspaceKnowledgeBaseFacade, taskArtifactService));
        toolCallbacks.add(
            new DeleteKnowledgeBaseDocumentToolCallback(
                knowledgeBaseDocumentFacade, knowledgeBaseDocumentService, workspaceKnowledgeBaseFacade,
                taskArtifactService));
        toolCallbacks.add(new CloneKnowledgeBaseToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(
            new ListChatWorkflowsToolCallback(
                projectDeploymentService, projectDeploymentWorkflowService, projectWorkflowService,
                triggerDefinitionService, workflowFacade, workflowService));
        toolCallbacks.add(
            new RunChatWorkflowToolCallback(
                projectDeploymentService, projectDeploymentWorkflowService, projectWorkflowService,
                workflowFacade, workflowService, taskArtifactService));
        toolCallbacks.add(new CreateWorkflowChatToolCallback(taskService));

        aiHubPersonalAgentServiceProvider.ifAvailable(aiHubPersonalAgentService -> {
            toolCallbacks.add(new ListAiHubPersonalAgentsToolCallback(aiHubPersonalAgentService));
            toolCallbacks.add(new OpenAiHubPersonalAgentTabToolCallback(aiHubPersonalAgentService,
                taskService));
            toolCallbacks.add(new CreateAiHubPersonalAgentToolCallback(aiHubPersonalAgentService));
            toolCallbacks.add(new UpdateAiHubPersonalAgentToolCallback(aiHubPersonalAgentService));
            toolCallbacks.add(new DeleteAiHubPersonalAgentToolCallback(aiHubPersonalAgentService));
            toolCallbacks.add(new CloneAiHubPersonalAgentToolCallback(aiHubPersonalAgentService));
        });

        // Copilot specialist sub-agent delegation. Write-capable variants for the BUILD agent, plus
        // the BUILD-only Converter sub-agent. Skips registrations when the corresponding ChatClient
        // bean is absent (Copilot disabled).
        registerCopilotSubAgentToolCallbacks(
            toolCallbacks, skillsBuildSubAgentChatClientProvider, clusterElementBuildSubAgentChatClientProvider,
            codeEditorBuildSubAgentChatClientProvider, workflowEditorBuildSubAgentChatClientProvider,
            converterBuildSubAgentChatClientProvider, workflowExecutionBuildSubAgentChatClientProvider);
        toolCallbacks.add(new CreateConnectionToolCallback(componentDefinitionService, jsonMapper));
        toolCallbacks.add(new SelectConnectionToolCallback(componentDefinitionService, jsonMapper));
        toolCallbacks.add(new ListProjectDeploymentsToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new CreateProjectDeploymentToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new UpdateProjectDeploymentToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new DeleteProjectDeploymentToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new RollbackProjectDeploymentToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new ToggleProjectDeploymentToolCallback(projectDeploymentFacade));
        toolCallbacks.add(new PromoteWorkflowToolCallback(projectDeploymentFacade));

        registerContextStoreToolCallbacks(
            toolCallbacks, workspaceContextStoreSourceFacadeProvider, contextStoreQueryServiceProvider,
            workspaceContextStoreSourceServiceProvider, clusterElementDefinitionService);
        registerContextStoreSemanticSearchToolCallback(
            toolCallbacks, contextStoreSemanticSearchServiceProvider, workspaceContextStoreSourceServiceProvider);
        toolCallbacks.add(
            new AttachTaskToolToolCallback(taskService, taskToolFacade, connectionService, aiHubToolAttachMetrics));
        toolCallbacks.add(
            new RemoveTaskToolToolCallback(taskService, taskToolFacade));
        toolCallbacks.add(new AskUserQuestionToolCallback(aiHubToolAttachMetrics, jsonMapper));

        // Task / connection state visibility — mirrors the ASK agent. The two callbacks together let the LLM
        // resolve "is this already set up?" and "do I have a connection for this?" without escalating to the user.
        registerToolAttachStateVisibilityToolCallbacks(
            toolCallbacks, taskService, taskToolFacade, connectionDefinitionService, workspaceConnectionFacade,
            propertyOptionsResolver, aiHubToolAttachMetrics, jsonMapper);

        apiCollectionFacadeProvider.ifAvailable(apiCollectionFacade -> {
            toolCallbacks.add(new CreateApiCollectionToolCallback(apiCollectionFacade));
            toolCallbacks.add(new CloneApiCollectionToolCallback(apiCollectionFacade));
            toolCallbacks.add(new ListApiCollectionsToolCallback(apiCollectionFacade));
        });

        // Resource discovery — read-only and always-on. Mirrors the same registrations on the ASK agent so
        // a "list my tasks" turn works identically regardless of which mode is active. Workflow-execution
        // lookups are delegated to the workflow_execution_agent specialist.
        toolCallbacks.add(new ListAiHubTasksToolCallback(taskService));
        mcpProjectFacadeProvider.ifAvailable(mcpProjectFacade -> {
            toolCallbacks.add(new CreateMcpProjectToolCallback(mcpProjectFacade));
            toolCallbacks.add(new CloneMcpProjectToolCallback(mcpProjectFacade));
        });

        workspaceMcpServerFacadeProvider.ifAvailable(
            workspaceMcpServerFacade -> toolCallbacks.add(new ListMcpServersToolCallback(workspaceMcpServerFacade)));

        // Auto-memory is now exposed via the forked AutoMemoryToolsAdvisor (DB-backed Resource seam),
        // registered as an advisor below rather than as standalone tool callbacks.

        toolCallbacks.add(new CloneAssetFileToolCallback(assetFileFacade));
        toolCallbacks.add(new CreateAssetFileToolCallback(assetFileFacade, aiHubTaskArtifactRecorder));
        toolCallbacks.add(new GetAssetFileContentToolCallback(assetFileFacade));
        toolCallbacks.add(new ListAssetFilesToolCallback(assetFileFacade));

        AiHubSpringAIAgent.Builder buildBuilder = AiHubSpringAIAgent.builder()
            .agentId(name.toLowerCase() + "_llm")
            .chatMemory(chatMemory)
            .chatModel(chatModel)
            .systemMessage(getSystemPrompt(promptAiHubBuildResource))
            .toolCallbacks(toolCallbacks)
            .threadUserIdResolver(threadId -> taskService.findByThreadId(threadId)
                .map(AiHubTask::getUserId)
                .orElse(null))
            .state(state)
            // Mirrors aiHubAskSpringAIAgent — SecurityContext rehydration on tool execution so
            // @PreAuthorize-protected facade calls don't throw on Reactor scheduler threads.
            .userService(userService)
            .authorityService(authorityService);

        toolSearchToolCallAdvisorProvider.ifAvailable(buildBuilder::advisor);

        buildBuilder.advisor(
            AutoMemoryToolsAdvisor.builder()
                .autoMemoryTools(
                    new AutoMemoryTools(
                        new DbMemoryResourceResolver(aiHubMemoryService),
                        new DbAutoMemoryDirectoryOps(aiHubMemoryService)))
                .memorySystemPrompt(promptAiHubAutoMemoryToolsResource)
                .build());

        taskBindingToolCallbackResolverProvider.ifAvailable(buildBuilder::taskToolBindingResolver);
        overrideChatClientResolverProvider.ifAvailable(buildBuilder::overrideChatClientResolver);

        return buildBuilder.build();
    }

    @Bean
    @ConditionalOnBean(WebhookWorkflowExecutionFacade.class)
    WebhookBridgeAgent webhookBridgeAgent(
        WebhookWorkflowExecutionFacade webhookFacade, AiHubTaskService taskService,
        WebhookResumeRegistry webhookResumeRegistry, JsonMapper jsonMapper, AssetFileFacade assetFileFacade,
        WorkflowChatMetrics workflowChatMetrics, WorkflowChatJobRegistry workflowChatJobRegistry,
        ChatMemory chatMemory, WorkflowChatGuard workflowChatGuard,
        ObjectProvider<com.bytechef.atlas.execution.facade.JobFacade> jobFacadeProvider) throws AGUIException {

        return new WebhookBridgeAgent(
            webhookFacade, taskService, webhookResumeRegistry, jsonMapper, assetFileFacade,
            workflowChatMetrics, workflowChatJobRegistry, chatMemory, workflowChatGuard,
            jobFacadeProvider.getIfAvailable());
    }

    @Bean
    AiHubRoutingAgent aiHubAskRoutingAgent(
        @Qualifier("aiHubAskSpringAIAgent") AiHubSpringAIAgent aiHubAskSpringAIAgent,
        ObjectProvider<WebhookBridgeAgent> webhookBridgeAgentProvider,
        AiHubTaskService taskService, AssetFileFacade assetFileFacade,
        ObjectProvider<AiHubPersonalAgentService> aiHubPersonalAgentServiceProvider)
        throws AGUIException {

        return new AiHubRoutingAgent(
            (Source.AI_HUB.name() + "_" + Mode.ASK.name()).toLowerCase(),
            aiHubAskSpringAIAgent,
            webhookBridgeAgentProvider.getIfAvailable(),
            taskService, assetFileFacade, aiHubPersonalAgentServiceProvider.getIfAvailable());
    }

    @Bean
    AiHubRoutingAgent aiHubBuildRoutingAgent(
        @Qualifier("aiHubBuildSpringAIAgent") AiHubSpringAIAgent aiHubBuildSpringAIAgent,
        ObjectProvider<WebhookBridgeAgent> webhookBridgeAgentProvider,
        AiHubTaskService taskService, AssetFileFacade assetFileFacade,
        ObjectProvider<AiHubPersonalAgentService> aiHubPersonalAgentServiceProvider)
        throws AGUIException {

        return new AiHubRoutingAgent(
            (Source.AI_HUB.name() + "_" + Mode.BUILD.name()).toLowerCase(),
            aiHubBuildSpringAIAgent,
            webhookBridgeAgentProvider.getIfAvailable(),
            taskService, assetFileFacade, aiHubPersonalAgentServiceProvider.getIfAvailable());
    }

    @Bean
    AiHubGlobalToolCatalog aiHubAskGlobalToolCatalog(
        ReadProjectTools readProjectTools, ReadProjectWorkflowTools readProjectWorkflowTools,
        ComponentTools componentTools, TaskTools taskTools, TaskDispatcherTools taskDispatcherTools) {

        return globalToolCatalog(
            ToolSearchCatalogFeeder.GLOBAL_ASK_SESSION_ID, readProjectTools, readProjectWorkflowTools, componentTools,
            taskTools, taskDispatcherTools);
    }

    @Bean
    AiHubGlobalToolCatalog aiHubBuildGlobalToolCatalog(
        ProjectTools projectTools, ProjectWorkflowTools projectWorkflowTools, ComponentTools componentTools,
        TaskTools taskTools, TaskDispatcherTools taskDispatcherTools, ScriptTools scriptTools,
        ClusterElementTools clusterElementTools) {

        return globalToolCatalog(
            ToolSearchCatalogFeeder.GLOBAL_BUILD_SESSION_ID, projectTools, projectWorkflowTools, componentTools,
            taskTools, taskDispatcherTools, scriptTools, clusterElementTools);
    }

    private static AiHubGlobalToolCatalog globalToolCatalog(String sessionId, Object... toolObjects) {
        return new AiHubGlobalToolCatalog(sessionId, List.of(ToolCallbacks.from(toolObjects)));
    }

    /**
     * Registers the optional ChatClient-based sub-agent tool callbacks (research, data analyst, image generator, slide
     * builder) on the supplied tool list. Each is only added when its backing ChatClient bean is present. Extracted to
     * keep the BUILD-agent bean method within Checkstyle's per-method line limit.
     *
     * <p>
     * The older {@code workflow_builder} ChatClient sub-agent is intentionally absent — it has been superseded by the
     * Copilot {@code workflow_editor_agent} specialist registered through
     * {@link #registerCopilotSubAgentToolCallbacks}. The Copilot specialist persists workflows internally via
     * {@code ProjectWorkflowTools}, eliminating the JSON round-trip {@code workflow_builder} required.
     * </p>
     */
    private static void registerSubAgentToolCallbacks(
        List<ToolCallback> toolCallbacks, ObjectProvider<ChatClient> researchChatClientProvider,
        ObjectProvider<ChatClient> dataAnalystChatClientProvider,
        ObjectProvider<ChatClient> imageGeneratorChatClientProvider,
        ObjectProvider<ChatClient> slideBuilderChatClientProvider, AssetFileFacade assetFileFacade) {

        researchChatClientProvider.ifAvailable(
            researchChatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    ResearchConfiguration.createResearchToolCallback(researchChatClient), "research")));

        dataAnalystChatClientProvider.ifAvailable(
            dataAnalystChatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    DataAnalystConfiguration.createDataAnalystToolCallback(
                        dataAnalystChatClient, assetFileFacade),
                    "data_analyst")));

        imageGeneratorChatClientProvider.ifAvailable(
            imageGeneratorChatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    ImageGeneratorConfiguration.createImageGeneratorToolCallback(imageGeneratorChatClient),
                    "image_generator")));

        slideBuilderChatClientProvider.ifAvailable(
            slideBuilderChatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    SlideBuilderConfiguration.createSlideBuilderToolCallback(slideBuilderChatClient),
                    "slide_builder")));
    }

    /**
     * Registers the data table write-mutation callbacks (add row, update row, delete row, add column, create from CSV,
     * clone) on the supplied tool list. Extracted to keep the BUILD-agent bean method within Checkstyle's per-method
     * line limit; logically a single block of related callbacks.
     */
    private static void registerDataTableMutationToolCallbacks(
        List<ToolCallback> toolCallbacks, DataTableRowService dataTableRowService,
        DataTableService dataTableService, AiHubTaskArtifactService taskArtifactService) {

        toolCallbacks.add(new AddDataTableRowToolCallback(dataTableRowService, dataTableService, taskArtifactService));
        toolCallbacks.add(
            new UpdateDataTableRowToolCallback(dataTableRowService, dataTableService, taskArtifactService));
        toolCallbacks.add(
            new DeleteDataTableRowToolCallback(dataTableRowService, dataTableService, taskArtifactService));
        toolCallbacks.add(new AddDataTableColumnToolCallback(dataTableService, taskArtifactService));
        toolCallbacks.add(new CreateDataTableFromCsvToolCallback(dataTableRowService, dataTableService));
        toolCallbacks.add(new CloneDataTableToolCallback(dataTableService));
    }

    /**
     * Registers the Copilot specialist sub-agent ToolCallbacks (skills, cluster element, code editor, workflow editor,
     * converter) on the supplied tool list. Each is only added when its backing ChatClient bean is present — Copilot
     * disabled or a particular specialist missing skips silently. Mirrors {@link #registerSubAgentToolCallbacks} for
     * the older ChatClient sub-agents (research / data_analyst / image_generator / slide_builder).
     *
     * <p>
     * The {@code converterProvider} is nullable because the ASK agent has no Converter specialist (Copilot only ships a
     * BUILD-mode Converter agent); callers from the ASK bean pass {@code null} and the converter registration is
     * skipped.
     * </p>
     */
    private static void registerCopilotSubAgentToolCallbacks(
        List<ToolCallback> toolCallbacks,
        ObjectProvider<ChatClient> skillsSubAgentChatClientProvider,
        ObjectProvider<ChatClient> clusterElementSubAgentChatClientProvider,
        ObjectProvider<ChatClient> codeEditorSubAgentChatClientProvider,
        ObjectProvider<ChatClient> workflowEditorSubAgentChatClientProvider,
        @Nullable ObjectProvider<ChatClient> converterSubAgentChatClientProvider,
        ObjectProvider<ChatClient> workflowExecutionSubAgentChatClientProvider) {

        skillsSubAgentChatClientProvider.ifAvailable(
            chatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(new SkillsAgentToolCallback(chatClient), "skills_agent")));

        clusterElementSubAgentChatClientProvider.ifAvailable(
            chatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    new ClusterElementAgentToolCallback(chatClient), "cluster_element_agent")));

        codeEditorSubAgentChatClientProvider.ifAvailable(
            chatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    new CodeEditorAgentToolCallback(chatClient), "code_editor_agent")));

        workflowEditorSubAgentChatClientProvider.ifAvailable(
            chatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    new WorkflowEditorAgentToolCallback(chatClient), "workflow_editor_agent")));

        workflowExecutionSubAgentChatClientProvider.ifAvailable(
            chatClient -> toolCallbacks.add(
                new ProgressReportingToolCallback(
                    new WorkflowExecutionAgentToolCallback(chatClient), "workflow_execution_agent")));

        if (converterSubAgentChatClientProvider != null) {
            converterSubAgentChatClientProvider.ifAvailable(
                chatClient -> toolCallbacks.add(
                    new ProgressReportingToolCallback(
                        new ConverterAgentToolCallback(chatClient), "converter_agent")));
        }
    }

    /**
     * Registers state-visibility callbacks for the autonomous tool-attach flow: {@code listTaskTools} and
     * {@code listConnectionsForComponent}. Read-only on both agents — the LLM uses them to avoid duplicate attaches and
     * to surface an existing connection before falling back to {@code createConnection}.
     */
    private static void registerToolAttachStateVisibilityToolCallbacks(
        List<ToolCallback> toolCallbacks, AiHubTaskService taskService, AiHubTaskToolFacade taskToolFacade,
        ConnectionDefinitionService connectionDefinitionService, WorkspaceConnectionFacade workspaceConnectionFacade,
        PropertyOptionsResolver propertyOptionsResolver, AiHubToolAttachMetrics aiHubToolAttachMetrics,
        JsonMapper jsonMapper) {

        toolCallbacks.add(new ListTaskToolsToolCallback(taskService, taskToolFacade, aiHubToolAttachMetrics,
            jsonMapper));
        toolCallbacks.add(
            new ListConnectionsForComponentToolCallback(
                connectionDefinitionService, workspaceConnectionFacade, propertyOptionsResolver,
                aiHubToolAttachMetrics, jsonMapper));
    }

    /**
     * Registers the Context Store read-only tool callbacks (consume + discovery) on the supplied tool list. Shared
     * between the ASK and BUILD agents — both surfaces benefit from being able to enumerate sources, search records,
     * and explore the source-component catalog without escalating to a mutation.
     */
    private static void registerContextStoreReadOnlyToolCallbacks(
        List<ToolCallback> toolCallbacks,
        ObjectProvider<ContextStoreQueryService> contextStoreQueryServiceProvider,
        ObjectProvider<WorkspaceContextStoreSourceService> workspaceContextStoreSourceServiceProvider,
        ClusterElementDefinitionService clusterElementDefinitionService) {

        ContextStoreQueryService contextStoreQueryService = contextStoreQueryServiceProvider.getIfAvailable();
        WorkspaceContextStoreSourceService workspaceContextStoreSourceService =
            workspaceContextStoreSourceServiceProvider.getIfAvailable();

        if (workspaceContextStoreSourceService != null) {
            toolCallbacks.add(new ListContextSourcesToolCallback(workspaceContextStoreSourceService));
        }

        if (workspaceContextStoreSourceService != null && contextStoreQueryService != null) {
            toolCallbacks.add(
                new SearchContextStoreToolCallback(contextStoreQueryService, workspaceContextStoreSourceService));
            toolCallbacks.add(
                new GetContextStoreRecordToolCallback(contextStoreQueryService, workspaceContextStoreSourceService));
        }

        // Discovery — needs neither the Context Store service stack nor a workspace context. Always-on.
        toolCallbacks.add(new ListAvailableSourceComponentsToolCallback());
        toolCallbacks.add(new DescribeSourceComponentEntitiesToolCallback(clusterElementDefinitionService));
    }

    /**
     * Registers the Context Store semantic-search tool callback on the supplied tool list, gated on the presence of an
     * {@link ContextStoreSemanticSearchService} bean. CE-no-embedding deployments and multi-tenant deployments don't
     * have the bean and therefore skip the registration silently — the callback is read-only and safe on both ASK and
     * BUILD agents.
     */
    private static void registerContextStoreSemanticSearchToolCallback(
        List<ToolCallback> toolCallbacks,
        ObjectProvider<ContextStoreSemanticSearchService> contextStoreSemanticSearchServiceProvider,
        ObjectProvider<WorkspaceContextStoreSourceService> workspaceContextStoreSourceServiceProvider) {

        ContextStoreSemanticSearchService contextStoreSemanticSearchService =
            contextStoreSemanticSearchServiceProvider.getIfAvailable();
        WorkspaceContextStoreSourceService workspaceContextStoreSourceService =
            workspaceContextStoreSourceServiceProvider.getIfAvailable();

        if (contextStoreSemanticSearchService == null || workspaceContextStoreSourceService == null) {
            return;
        }

        toolCallbacks.add(
            new SemanticSearchContextStoreToolCallback(
                contextStoreSemanticSearchService, workspaceContextStoreSourceService));
    }

    /**
     * Registers all Context Store tool callbacks (consume + discovery + define) on the supplied tool list. Used by the
     * BUILD agent only since define-side callbacks are mutations. Define-side callbacks delegate to
     * {@link WorkspaceContextStoreSourceFacade} — admin role is enforced at the facade level; chat-level user
     * confirmation is expected before execution per CC mutation-callback precedent (matches
     * {@code CreateProjectDeploymentToolCallback}).
     *
     * <p>
     * {@code SemanticSearchContextStoreToolCallback} is registered separately via
     * {@link #registerContextStoreSemanticSearchToolCallback} on both the ASK and BUILD agents, gated on
     * {@link ContextStoreSemanticSearchService} bean presence (CE-no-embedding deployments skip it silently).
     * </p>
     */
    private static void registerContextStoreToolCallbacks(
        List<ToolCallback> toolCallbacks,
        ObjectProvider<WorkspaceContextStoreSourceFacade> workspaceContextStoreSourceFacadeProvider,
        ObjectProvider<ContextStoreQueryService> contextStoreQueryServiceProvider,
        ObjectProvider<WorkspaceContextStoreSourceService> workspaceContextStoreSourceServiceProvider,
        ClusterElementDefinitionService clusterElementDefinitionService) {

        registerContextStoreReadOnlyToolCallbacks(
            toolCallbacks, contextStoreQueryServiceProvider, workspaceContextStoreSourceServiceProvider,
            clusterElementDefinitionService);

        WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade =
            workspaceContextStoreSourceFacadeProvider.getIfAvailable();
        WorkspaceContextStoreSourceService workspaceContextStoreSourceService =
            workspaceContextStoreSourceServiceProvider.getIfAvailable();

        if (workspaceContextStoreSourceFacade != null && workspaceContextStoreSourceService != null) {
            toolCallbacks.add(new CreateContextStoreSourceToolCallback(workspaceContextStoreSourceFacade));
            toolCallbacks.add(new UpdateContextStoreSourceToolCallback(
                workspaceContextStoreSourceFacade, workspaceContextStoreSourceService));
            toolCallbacks.add(new DeleteContextStoreSourceToolCallback(
                workspaceContextStoreSourceFacade, workspaceContextStoreSourceService));
            toolCallbacks.add(new RefreshContextStoreSourceToolCallback(
                workspaceContextStoreSourceFacade, workspaceContextStoreSourceService));
            toolCallbacks.add(new SetContextStoreSourceEnabledToolCallback(
                workspaceContextStoreSourceFacade, workspaceContextStoreSourceService));
        }
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
