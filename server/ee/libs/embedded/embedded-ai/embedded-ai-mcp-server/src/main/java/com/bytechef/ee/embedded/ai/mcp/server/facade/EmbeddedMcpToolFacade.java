/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.mcp.server.facade;

import static com.bytechef.task.dispatcher.approval.constant.WaitForApprovalTaskDispatcherConstants.WAIT_FOR_APPROVAL;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.automation.configuration.security.AutomationAuthorizationContext;
import com.bytechef.commons.util.ConvertUtils;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.ee.embedded.ai.mcp.domain.McpIntegrationInstanceConfiguration;
import com.bytechef.ee.embedded.ai.mcp.domain.McpIntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.ai.mcp.domain.McpIntegrationInstanceTool;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.ee.embedded.configuration.domain.Integration;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfiguration;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceWorkflow;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.embedded.execution.constant.EmbeddedToolConstants;
import com.bytechef.ee.embedded.security.service.JwtTokenService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.ai.tool.FromAiResult;
import com.bytechef.platform.ai.tool.facade.AbstractToolFacade;
import com.bytechef.platform.ai.tool.util.FromAiInputSchemaUtils;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.constant.WorkflowConstants;
import com.bytechef.platform.component.definition.SuspendUtils;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.facade.ClusterElementDefinitionFacade;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.constant.JobInputConstants;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor;
import com.bytechef.platform.mcp.domain.McpComponent;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.domain.McpTool;
import com.bytechef.platform.mcp.service.McpComponentService;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.workflow.execution.JobCompletionAwaiter;
import com.bytechef.platform.workflow.execution.JobExecutionErrors;
import com.bytechef.platform.workflow.execution.facade.ApprovalInputException;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.token.ApprovalFormUrls;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool facade for the embedded MCP server. Handles both component-level tools (direct action execution) and integration
 * {@link IntegrationInstanceService}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class EmbeddedMcpToolFacade extends AbstractToolFacade {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedMcpToolFacade.class);

    private static final String APPROVAL = "approval";
    private static final Set<String> APPROVAL_COMPONENT_NAMES = Set.of(APPROVAL, WAIT_FOR_APPROVAL);
    private static final Set<String> SUSPENDING_COMPONENT_NAMES = Set.of(APPROVAL, "wait", WAIT_FOR_APPROVAL);
    private static final long RESUME_POLL_INTERVAL_MILLIS = 500;

    private final ObjectProvider<ApprovalTokens> approvalTokensObjectProvider;
    private final ClusterElementDefinitionFacade clusterElementDefinitionFacade;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ComponentDefinitionService componentDefinitionService;
    private final ConnectedUserService connectedUserService;
    private final JwtTokenService jwtTokenService;
    private final IntegrationInstanceConfigurationService integrationInstanceConfigurationService;
    private final IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;
    private final IntegrationInstanceService integrationInstanceService;
    private final IntegrationService integrationService;
    private final JobCompletionAwaiter jobCompletionAwaiter;
    private final JobResumeFacade jobResumeFacade;
    private final JobService jobService;
    private final JobSyncExecutor jobSyncExecutor;
    private final McpComponentService mcpComponentService;
    private final McpIntegrationInstanceToolService mcpIntegrationInstanceToolService;
    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final McpIntegrationInstanceConfigurationWorkflowService mcpIntegrationInstanceConfigurationWorkflowService;
    private final McpServerService mcpServerService;
    private final PrincipalJobFacade principalJobFacade;
    private final String publicUrl;
    private final TaskExecutionService taskExecutionService;
    private final TaskFileStorage taskFileStorage;
    private final ToolExecutionRecorder toolExecutionRecorder;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public EmbeddedMcpToolFacade(
        ObjectProvider<ApprovalTokens> approvalTokensObjectProvider,
        ClusterElementDefinitionFacade clusterElementDefinitionFacade,
        ClusterElementDefinitionService clusterElementDefinitionService,
        ComponentDefinitionService componentDefinitionService, ConnectedUserService connectedUserService,
        Evaluator evaluator, IntegrationInstanceConfigurationService integrationInstanceConfigurationService,
        IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService,
        IntegrationInstanceService integrationInstanceService,
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService, IntegrationService integrationService,
        JobCompletionAwaiter jobCompletionAwaiter, JobResumeFacade jobResumeFacade, JobService jobService,
        JobSyncExecutor jobSyncExecutor, JwtTokenService jwtTokenService,
        McpComponentService mcpComponentService,
        McpIntegrationInstanceConfigurationWorkflowService mcpIntegrationInstanceConfigurationWorkflowService,
        McpIntegrationInstanceToolService mcpIntegrationInstanceToolService,
        McpServerService mcpServerService, PrincipalJobFacade principalJobFacade, String publicUrl,
        TaskExecutionService taskExecutionService, TaskFileStorage taskFileStorage,
        ToolExecutionRecorder toolExecutionRecorder, WorkflowService workflowService) {
        super(evaluator);

        this.approvalTokensObjectProvider = approvalTokensObjectProvider;
        this.clusterElementDefinitionFacade = clusterElementDefinitionFacade;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.componentDefinitionService = componentDefinitionService;
        this.connectedUserService = connectedUserService;
        this.jwtTokenService = jwtTokenService;
        this.integrationInstanceConfigurationService = integrationInstanceConfigurationService;
        this.integrationInstanceConfigurationWorkflowService = integrationInstanceConfigurationWorkflowService;
        this.integrationInstanceService = integrationInstanceService;
        this.integrationService = integrationService;
        this.jobCompletionAwaiter = jobCompletionAwaiter;
        this.jobResumeFacade = jobResumeFacade;
        this.jobService = jobService;
        this.jobSyncExecutor = jobSyncExecutor;
        this.mcpComponentService = mcpComponentService;
        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.mcpIntegrationInstanceToolService = mcpIntegrationInstanceToolService;
        this.mcpIntegrationInstanceConfigurationWorkflowService = mcpIntegrationInstanceConfigurationWorkflowService;
        this.mcpServerService = mcpServerService;
        this.principalJobFacade = principalJobFacade;
        this.publicUrl = publicUrl;
        this.taskExecutionService = taskExecutionService;
        this.taskFileStorage = taskFileStorage;
        this.toolExecutionRecorder = toolExecutionRecorder;
        this.workflowService = workflowService;
    }

    public @Nullable FunctionToolCallback<Map<String, Object>, Object> getFunctionToolCallback(
        McpTool mcpTool, @Nullable String externalUserId, Environment environment, String tenantId) {
        if (!mcpTool.isEnabled()) {
            return null;
        }

        Long integrationInstanceId = fetchIntegrationInstanceId(
            externalUserId, mcpTool.getMcpComponentId(), environment);

        if (integrationInstanceId != null && !isToolEnabled(integrationInstanceId, mcpTool.getId())) {
            return null;
        }

        McpComponent mcpComponent = mcpComponentService.getMcpComponent(mcpTool.getMcpComponentId());

        ClusterElementDefinition clusterElementDefinition =
            clusterElementDefinitionService.getClusterElementDefinition(
                mcpComponent.getComponentName(), mcpComponent.getComponentVersion(), mcpTool.getName());

        List<FromAiResult> fromAiResults = extractFromAiResults(mcpTool.getParameters());

        String toolName = getToolName(
            clusterElementDefinition.getComponentName(), clusterElementDefinition.getName(), mcpTool.getParameters());

        FunctionToolCallback.Builder<Map<String, Object>, Object> builder = FunctionToolCallback
            .builder(
                toolName,
                getClusterElementToolCallbackFunction(
                    toolName, externalUserId, clusterElementDefinition.getComponentName(),
                    clusterElementDefinition.getComponentVersion(),
                    clusterElementDefinition.getName(), mcpTool.getParameters(),
                    mcpComponent.getMcpServerId(), environment, tenantId))
            .inputType(Map.class)
            .inputSchema(FromAiInputSchemaUtils.generateInputSchema(fromAiResults));

        String toolDescription = getToolDescription(mcpTool.getParameters(), null);

        if (toolDescription == null) {
            toolDescription = clusterElementDefinition.getDescription();
        }

        if (toolDescription != null) {
            builder.description(toolDescription);
        }

        return builder.build();
    }

    public List<ToolCallback> getFunctionToolCallbacks(
        McpIntegrationInstanceConfiguration mcpIntegrationInstanceConfiguration, @Nullable String externalUserId,
        Environment environment, String tenantId) {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        for (McpIntegrationInstanceConfigurationWorkflow mcpIntegrationInstanceConfigurationWorkflow : mcpIntegrationInstanceConfigurationWorkflowService
            .getMcpIntegrationInstanceConfigurationMcpIntegrationInstanceConfigurationWorkflows(
                mcpIntegrationInstanceConfiguration.getId())) {
            IntegrationInstanceConfigurationWorkflow integrationInstanceConfigurationWorkflow =
                integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflow(
                    mcpIntegrationInstanceConfigurationWorkflow.getIntegrationInstanceConfigurationWorkflowId());

            if (!integrationInstanceConfigurationWorkflow.isEnabled()) {
                continue;
            }

            IntegrationInstanceConfiguration integrationInstanceConfiguration =
                integrationInstanceConfigurationService.getIntegrationInstanceConfiguration(
                    integrationInstanceConfigurationWorkflow.getIntegrationInstanceConfigurationId());

            Integration integration = integrationService.getIntegration(
                integrationInstanceConfiguration.getIntegrationId());

            Long integrationInstanceId = fetchIntegrationInstanceId(
                externalUserId, integration.getComponentName(), environment);

            if (integrationInstanceId != null &&
                !isWorkflowEnabled(integrationInstanceId, mcpIntegrationInstanceConfigurationWorkflow.getId())) {
                continue;
            }

            Workflow workflow = workflowService.getWorkflow(integrationInstanceConfigurationWorkflow.getWorkflowId());

            WorkflowTrigger trigger = getMcpToolCallableTrigger(workflow);

            if (trigger == null) {
                continue;
            }

            Map<String, ?> workflowParameters = mcpIntegrationInstanceConfigurationWorkflow.getParameters();

            String toolName = getWorkflowToolName(workflowParameters, workflow.getLabel());

            List<FromAiResult> fromAiResults = extractFromAiResults(workflowParameters);

            FunctionToolCallback.Builder<Map<String, Object>, Object> builder = FunctionToolCallback
                .builder(
                    Objects.requireNonNull(
                        toolName,
                        () -> "Workflow %s exposes no tool name: configure one or give the workflow a label"
                            .formatted(workflow.getId())),
                    getWorkflowToolCallbackFunction(
                        toolName, externalUserId, integration.getComponentName(), integration.getId(),
                        integrationInstanceConfigurationWorkflow,
                        trigger.getName(), workflowParameters, mcpIntegrationInstanceConfiguration.getMcpServerId(),
                        hasSuspendingTask(workflow), environment, tenantId))
                .inputType(Map.class)
                .inputSchema(FromAiInputSchemaUtils.generateInputSchema(fromAiResults));

            String description = getToolDescription(workflowParameters, null);

            if (description == null) {
                description = workflow.getDescription();
            }

            if (description != null) {
                builder.description(description);
            }

            toolCallbacks.add(builder.build());
        }

        return toolCallbacks;
    }

    private @Nullable Long fetchIntegrationInstanceId(
        @Nullable String externalUserId, long mcpComponentId, Environment environment) {

        McpComponent mcpComponent = mcpComponentService.getMcpComponent(mcpComponentId);

        return fetchIntegrationInstanceId(externalUserId, mcpComponent.getComponentName(), environment);
    }

    private @Nullable Long fetchConnectedUserId(@Nullable String externalUserId, Environment environment) {
        if (externalUserId == null) {
            return null;
        }

        return connectedUserService.fetchConnectedUser(externalUserId, environment)
            .map(ConnectedUser::getId)
            .orElse(null);
    }

    private Optional<IntegrationInstance> fetchConnectedUserIntegrationInstance(
        @Nullable Long connectedUserId, String componentName, Environment environment) {

        if (connectedUserId == null) {
            return Optional.empty();
        }

        return integrationInstanceService.fetchIntegrationInstance(connectedUserId, componentName, environment);
    }

    private @Nullable Long fetchIntegrationInstanceId(
        @Nullable String externalUserId, String componentName, Environment environment) {

        return fetchConnectedUserIntegrationInstance(
            fetchConnectedUserId(externalUserId, environment), componentName, environment)
                .map(IntegrationInstance::getId)
                .orElse(null);
    }

    private Function<Map<String, Object>, Object> getClusterElementToolCallbackFunction(
        String toolName, @Nullable String externalUserId, String componentName, int componentVersion,
        String clusterElementName, Map<String, ?> parameters, long mcpServerId, Environment environment,
        String tenantId) {
        return request -> {
            McpServer mcpServer = readMcpServerConfiguration(() -> mcpServerService.getMcpServer(mcpServerId));

            if (!mcpServer.isEnabled()) {
                throw new IllegalStateException("MCP server is disabled");
            }

            Long connectedUserId = fetchConnectedUserId(externalUserId, environment);

            Long connectionId = fetchConnectedUserIntegrationInstance(connectedUserId, componentName, environment)
                .map(IntegrationInstance::getConnectionId)
                .orElse(null);

            ToolExecutionEvent.Builder eventBuilder = ToolExecutionEvent
                .builder(ToolExecutionSurface.MCP_EMBEDDED, ToolExecutionKind.COMPONENT, toolName)
                .tenantId(tenantId)
                .componentName(componentName)
                .componentVersion(componentVersion)
                .operationName(clusterElementName)
                .connectionId(connectionId)
                .mcpServerId(mcpServerId)
                .externalUserId(externalUserId)
                .connectedUserId(connectedUserId)
                .environment(environment.ordinal());

            if (connectionId == null
                && isConnectionRequired(componentDefinitionService, componentName, componentVersion)) {

                if (externalUserId == null) {
                    throw new IllegalStateException(getConnectedUserRequiredMessage(componentName));
                }

                long integrationId = getIntegrationId(componentName);

                Object connectionRequiredResponse = getConnectionRequiredResponse(
                    componentName, environment, externalUserId, integrationId, tenantId);

                toolExecutionRecorder.record(
                    eventBuilder.outcome(ToolExecutionOutcome.CONNECTION_REQUIRED)
                        .build());

                return connectionRequiredResponse;
            }

            Map<String, Object> resolvedParameters = new HashMap<>();

            for (Map.Entry<String, ?> entry : parameters.entrySet()) {
                resolvedParameters.put(entry.getKey(), resolveParameterValue(entry.getValue(), request));
            }

            Map<String, Object> mergedParameters = EmbeddedToolConstants.withConnectedUserContext(
                MapUtils.concat(request, resolvedParameters), externalUserId, environment);

            return toolExecutionRecorder.record(
                eventBuilder,
                () -> clusterElementDefinitionFacade.executeTool(
                    componentName, componentVersion, clusterElementName, mergedParameters, connectionId));
        };
    }

    private long getIntegrationId(String componentName) {
        return integrationService.getIntegrations()
            .stream()
            .filter(integration -> componentName.equals(integration.getComponentName()))
            .map(Integration::getId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No integration found for component: " + componentName));
    }

    private Function<Map<String, Object>, Object> getWorkflowToolCallbackFunction(
        String toolName, @Nullable String externalUserId, String componentName, long integrationId,
        IntegrationInstanceConfigurationWorkflow integrationInstanceConfigurationWorkflow, String triggerName,
        Map<String, ?> workflowParameters, long mcpServerId, boolean suspendable, Environment environment,
        String tenantId) {

        return inputParameters -> {
            McpServer mcpServer = readMcpServerConfiguration(() -> mcpServerService.getMcpServer(mcpServerId));

            if (!mcpServer.isEnabled()) {
                throw new IllegalStateException("MCP server is disabled");
            }

            Long connectedUserId = fetchConnectedUserId(externalUserId, environment);

            Long integrationInstanceId = fetchConnectedUserIntegrationInstance(
                connectedUserId, componentName, environment)
                    .map(IntegrationInstance::getId)
                    .orElse(null);

            ToolExecutionEvent.Builder eventBuilder = ToolExecutionEvent
                .builder(ToolExecutionSurface.MCP_EMBEDDED, ToolExecutionKind.WORKFLOW, toolName)
                .tenantId(tenantId)
                .componentName(componentName)
                .mcpServerId(mcpServerId)
                .externalUserId(externalUserId)
                .connectedUserId(connectedUserId)
                .integrationInstanceId(integrationInstanceId)
                .environment(environment.ordinal());

            if (integrationInstanceId == null) {
                if (externalUserId == null) {
                    throw new IllegalStateException(getConnectedUserRequiredMessage(componentName));
                }

                Object connectionRequiredResponse = getConnectionRequiredResponse(
                    componentName, environment, externalUserId, integrationId, tenantId);

                toolExecutionRecorder.record(
                    eventBuilder.outcome(ToolExecutionOutcome.CONNECTION_REQUIRED)
                        .build());

                return connectionRequiredResponse;
            }

            Map<String, Object> inputs = new HashMap<>(integrationInstanceConfigurationWorkflow.getInputs());

            Map<String, Object> triggerInputs = new HashMap<>();

            for (Map.Entry<String, ?> entry : workflowParameters.entrySet()) {
                triggerInputs.put(entry.getKey(), resolveParameterValue(entry.getValue(), inputParameters));
            }

            triggerInputs.putAll(inputParameters);

            inputs.put(triggerName, triggerInputs);
            inputs.put(JobInputConstants.TRIGGER_NAME_INPUT, triggerName);

            JobParametersDTO jobParametersDTO = new JobParametersDTO(
                integrationInstanceConfigurationWorkflow.getWorkflowId(), inputs);

            return toolExecutionRecorder.record(
                eventBuilder,
                () -> {
                    Job job = suspendable
                        ? executeSuspendableJob(jobParametersDTO, integrationInstanceId)
                        : executeSyncJob(jobParametersDTO, integrationInstanceId);

                    eventBuilder.jobId(job.getId());

                    if (job.getStatus() == Job.Status.STOPPED) {
                        return describeStoppedJob(job);
                    }

                    JobExecutionErrors.checkForError(job, taskExecutionService);
                    JobExecutionErrors.checkCompleted(job);

                    if (job.getOutputs() == null) {
                        return null;
                    }

                    return getCallableResponseOutput(job)
                        .orElseGet(() -> taskFileStorage.readJobOutputs(job.getOutputs()));
                });
        };
    }

    private Job executeSyncJob(JobParametersDTO jobParametersDTO, long integrationInstanceId) {
        return jobSyncExecutor.execute(
            jobParametersDTO,
            jobParameters -> principalJobFacade.createSyncJob(
                jobParameters, integrationInstanceId, PlatformType.EMBEDDED),
            false, taskExecutionCompleteEvent -> {});
    }

    private Job executeSuspendableJob(JobParametersDTO jobParametersDTO, long integrationInstanceId) {
        long jobId = principalJobFacade.createJob(jobParametersDTO, integrationInstanceId, PlatformType.EMBEDDED);

        Duration timeout = JobCompletionAwaiter.DEFAULT_SYNC_TIMEOUT;

        CompletableFuture<Job> jobFuture = jobCompletionAwaiter.await(jobId, timeout);

        try {
            return jobFuture.join();
        } catch (CompletionException completionException) {
            if (completionException.getCause() instanceof TimeoutException timeoutException) {
                throw new IllegalStateException(
                    "Job %d did not finish within %d seconds".formatted(jobId, timeout.toSeconds()), timeoutException);
            }

            throw completionException;
        }
    }

    private Map<String, Object> describeStoppedJob(Job job) {
        Map<String, Object> result = new HashMap<>();

        result.put("jobId", job.getId());

        if (isSuspendedForApproval(job)) {
            Object jobResumeId = job.getMetadata(MetadataConstants.JOB_RESUME_ID);

            String jobResumeIdString = jobResumeId == null ? null : jobResumeId.toString();
            ApprovalTokens approvalTokens = approvalTokensObjectProvider.getIfAvailable();

            String formUrl = ApprovalFormUrls
                .buildFormUrl(publicUrl, jobResumeIdString, approvalTokens, resolveApprovalExpiresAt(job))
                .orElse(null);

            result.put("status", "approval_required");
            result.put(
                "message",
                formUrl == null
                    ? "Approval required — the workflow run is paused waiting for a human decision."
                    : "Approval required — the workflow run is paused waiting for a human decision. " +
                        "Resolve it at: " + formUrl);

            if (formUrl != null) {
                result.put("formUrl", formUrl);
            }
        } else if (job.getMetadata(MetadataConstants.JOB_RESUME_ID) != null) {
            result.put("status", "suspended");
            result.put("message", "The workflow run is paused and resumes later on its own.");
        } else {
            result.put("status", "stopped");
            result.put("message", "The workflow run was stopped before it finished.");
        }

        return result;
    }

    private boolean isSuspendedForApproval(Job job) {
        return findPausedTaskExecution(job)
            .map(TaskExecution::getType)
            .map(WorkflowNodeType::ofType)
            .map(WorkflowNodeType::name)
            .filter(APPROVAL_COMPONENT_NAMES::contains)
            .isPresent();
    }

    private Optional<TaskExecution> findPausedTaskExecution(Job job) {
        Object taskExecutionResumeId = job.getMetadata(MetadataConstants.TASK_EXECUTION_RESUME_ID);

        if (job.getMetadata(MetadataConstants.JOB_RESUME_ID) != null && taskExecutionResumeId != null) {
            return Optional.of(
                taskExecutionService.getTaskExecution(ConvertUtils.convertValue(taskExecutionResumeId, Long.class)));
        }

        return taskExecutionService.fetchLastJobTaskExecution(Objects.requireNonNull(job.getId()))
            .filter(taskExecution -> taskExecution.getStatus() == TaskExecution.Status.STARTED);
    }

    public Optional<String> resolvePendingApprovalFormUrl(long jobId) {
        return fetchPendingApprovalJob(jobId)
            .flatMap(job -> ApprovalFormUrls.buildFormUrl(
                publicUrl, getJobResumeId(job), approvalTokensObjectProvider.getIfAvailable(),
                resolveApprovalExpiresAt(job)));
    }

    public Optional<String> resolvePendingApprovalResumeToken(long jobId) {
        return fetchPendingApprovalJob(jobId)
            .flatMap(job -> ApprovalFormUrls.buildResumeToken(
                getJobResumeId(job), approvalTokensObjectProvider.getIfAvailable(), resolveApprovalExpiresAt(job)));
    }

    public @Nullable Object awaitApprovedWorkflowRun(long jobId) {
        Instant deadline = Instant.now()
            .plus(JobCompletionAwaiter.DEFAULT_SYNC_TIMEOUT);

        Job job = jobService.getJob(jobId);

        while (job.getStatus() == Job.Status.STOPPED && Instant.now()
            .isBefore(deadline)) {

            try {
                Thread.sleep(RESUME_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread()
                    .interrupt();

                break;
            }

            job = jobService.getJob(jobId);
        }

        if (job.getStatus() == Job.Status.STOPPED) {
            return describeStoppedJob(job);
        }

        Duration remaining = Duration.between(Instant.now(), deadline);

        job = jobCompletionAwaiter.await(jobId, remaining.isNegative() ? Duration.ofSeconds(1) : remaining)
            .join();

        if (job.getStatus() == Job.Status.STOPPED) {
            return describeStoppedJob(job);
        }

        Job completedJob = job;

        JobExecutionErrors.checkForError(completedJob, taskExecutionService);

        if (completedJob.getOutputs() == null) {
            return null;
        }

        return getCallableResponseOutput(completedJob)
            .orElseGet(() -> taskFileStorage.readJobOutputs(completedJob.getOutputs()));
    }

    public @Nullable Object resolveApprovalAndAwait(String resumeToken, Map<String, Object> data, long jobId) {
        JobResumeFacade.JobResumeOutcome outcome;

        try {
            outcome = jobResumeFacade.resumeJob(resumeToken, data);
        } catch (ApprovalInputException approvalInputException) {
            return Map.of(
                "status", "approval_input_required",
                "message", approvalInputException.getMessage() + " Resolve the approval through its form instead.");
        }

        if (outcome != JobResumeFacade.JobResumeOutcome.OK) {
            return Map.of(
                "status", "approval_unavailable",
                "message", "The approval could no longer be resolved (" + outcome + ").");
        }

        return awaitApprovedWorkflowRun(jobId);
    }

    private Optional<Job> fetchPendingApprovalJob(long jobId) {
        return jobService.fetchJob(jobId)
            .filter(job -> job.getStatus() == Job.Status.STOPPED)
            .filter(job -> job.getMetadata(MetadataConstants.JOB_RESUME_ID) != null);
    }

    private static String getJobResumeId(Job job) {
        return String.valueOf(job.getMetadata(MetadataConstants.JOB_RESUME_ID));
    }

    private @Nullable Instant resolveApprovalExpiresAt(Job job) {
        Object taskExecutionResumeId = job.getMetadata(MetadataConstants.TASK_EXECUTION_RESUME_ID);

        if (taskExecutionResumeId == null) {
            return null;
        }

        try {
            TaskExecution taskExecution = taskExecutionService.getTaskExecution(
                ConvertUtils.convertValue(taskExecutionResumeId, Long.class));

            return SuspendUtils.extractSuspendExpiresAt(taskExecution.getMetadata());
        } catch (RuntimeException runtimeException) {
            log.warn(
                "Could not read the approval expiry of job {}: {}", job.getId(), runtimeException.getMessage());

            return null;
        }
    }

    private boolean isToolEnabled(long integrationInstanceId, long mcpToolId) {
        return mcpIntegrationInstanceToolService
            .fetchMcpIntegrationInstanceTool(integrationInstanceId, mcpToolId)
            .map(McpIntegrationInstanceTool::isEnabled)
            .orElse(false);
    }

    private boolean isWorkflowEnabled(long integrationInstanceId, long mcpIntegrationInstanceConfigurationWorkflowId) {
        return mcpIntegrationInstanceConfigurationWorkflowService
            .fetchMcpIntegrationInstanceConfigurationWorkflow(mcpIntegrationInstanceConfigurationWorkflowId)
            .map(mcpConfigWorkflow -> integrationInstanceWorkflowService
                .fetchIntegrationInstanceWorkflow(
                    integrationInstanceId, mcpConfigWorkflow.getIntegrationInstanceConfigurationWorkflowId())
                .map(IntegrationInstanceWorkflow::isEnabled)
                .orElse(false))
            .orElse(false);
    }

    private Map<String, Object> getConnectionRequiredResponse(
        String componentName, Environment environment, String externalUserId, long integrationId, String tenantId) {
        String jwtToken = jwtTokenService.generateJwtToken(
            externalUserId, integrationId, environment.ordinal(), tenantId);

        String setupUrl = publicUrl + "/connect.html?token=" + jwtToken;

        return Map.of(
            "error", "connection_required",
            "message",
            "The " + componentName + " integration is not connected for this user. " +
                "To connect, visit: " + setupUrl + " . " +
                "Instruct the user to visit this link to connect their account. Present it as a markdown link " +
                "labelled \"Connect " + componentName + "\" instead of printing the raw URL.",
            "setupUrl", setupUrl);
    }

    private static String getConnectedUserRequiredMessage(String componentName) {
        return "The " + componentName + " tool requires a connected user, but this MCP server does not require " +
            "authentication, so the caller is anonymous. Enable authentication on the MCP server to use this tool.";
    }

    private Optional<Object> getCallableResponseOutput(Job job) {
        try {
            return taskExecutionService.fetchLastJobTaskExecution(Objects.requireNonNull(job.getId()))
                .filter(
                    lastTaskExecution -> {
                        Map<String, ?> metadata = lastTaskExecution.getMetadata();

                        return metadata.containsKey(MetadataConstants.CALLABLE_RESPONSE);
                    })
                .map(lastTaskExecution -> {
                    ActionDefinition.CallableResponse callableResponse = ConvertUtils.convertValue(
                        taskFileStorage.readTaskExecutionOutput(lastTaskExecution.getOutput()),
                        ActionDefinition.CallableResponse.class);

                    return callableResponse.output();
                });
        } catch (IllegalArgumentException | ClassCastException exception) {
            log.warn(
                "Failed to extract callable response output from job {}: {}", job.getId(), exception.getMessage());

            return Optional.empty();
        }
    }

    static boolean isConnectionRequired(
        ComponentDefinitionService componentDefinitionService, String componentName, int componentVersion) {
        ComponentDefinition componentDefinition =
            componentDefinitionService.getComponentDefinition(componentName, componentVersion);

        return componentDefinition.getConnection() != null;
    }

    private static boolean hasSuspendingTask(Workflow workflow) {
        return workflow.getTasks(true)
            .stream()
            .map(WorkflowTask::getType)
            .filter(Objects::nonNull)
            .map(WorkflowNodeType::ofType)
            .map(WorkflowNodeType::name)
            .anyMatch(SUSPENDING_COMPONENT_NAMES::contains);
    }

    private static <T> T readMcpServerConfiguration(Supplier<T> read) {
        try {
            return AutomationAuthorizationContext.callSkippingChecks(read::get);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }

    private static @Nullable WorkflowTrigger getMcpToolCallableTrigger(Workflow workflow) {
        for (WorkflowTrigger workflowTrigger : WorkflowTrigger.of(workflow)) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            String name = workflowNodeType.name();
            String operation = workflowNodeType.operation();

            if (Objects.equals(name, WorkflowConstants.WORKFLOW) &&
                Objects.equals(operation, WorkflowConstants.NEW_WORKFLOW_CALL)) {
                return workflowTrigger;
            }
        }

        return null;
    }
}
