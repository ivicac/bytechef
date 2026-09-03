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

package com.bytechef.component.ai.agent.utils.cluster;

import com.bytechef.component.ai.llm.tool.DelegatingToolCallback;
import com.bytechef.component.ai.llm.tool.ToolApprovalRequests;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Platform-enforced approval gate. Wraps a tool callback attached beneath an {@link AiAgentUtilsApprovalGateTool}:
 * instead of executing, the first call delivers a standard approval request (tool name + the AI-chosen arguments)
 * through that gate's APPROVAL_CHANNELS cluster elements — defaulting to the chat channel when none are configured —
 * and suspends the workflow. On resume, approval executes the tool with the original arguments and rejection feeds a
 * denial back into the agent loop (see {@code AbstractAiAgentChatAction.buildPatchedRequestSpec}). Enforcement lives
 * here, in the platform — the LLM cannot invoke a flagged tool un-gated.
 *
 * @author Ivica Cardic
 */
public class ApprovalGateToolCallback implements DelegatingToolCallback {

    private final ToolCallback delegate;
    private static final Duration DEFAULT_APPROVAL_EXPIRY = Duration.ofDays(60);

    private final List<ClusterElement> approvalChannelClusterElements;
    private final Map<String, ComponentConnection> componentConnections;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ActionContextAware actionContext;

    @Nullable
    private final ToolExecutionRecorder toolExecutionRecorder;

    @Nullable
    private final Duration approvalExpiry;

    public ApprovalGateToolCallback(
        ToolCallback delegate, List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContext actionContext) {

        this(
            delegate, approvalChannelClusterElements, componentConnections, clusterElementDefinitionService,
            actionContext, null, null);
    }

    public ApprovalGateToolCallback(
        ToolCallback delegate, List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContext actionContext,
        @Nullable ToolExecutionRecorder toolExecutionRecorder) {

        this(
            delegate, approvalChannelClusterElements, componentConnections, clusterElementDefinitionService,
            actionContext, toolExecutionRecorder, null);
    }

    public ApprovalGateToolCallback(
        ToolCallback delegate, List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContext actionContext,
        @Nullable ToolExecutionRecorder toolExecutionRecorder, @Nullable Duration approvalExpiry) {

        this.delegate = delegate;
        this.approvalChannelClusterElements = List.copyOf(approvalChannelClusterElements);
        this.componentConnections = Map.copyOf(componentConnections);
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.actionContext = (ActionContextAware) actionContext;
        this.toolExecutionRecorder = toolExecutionRecorder;
        this.approvalExpiry = approvalExpiry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * Exposes the gated tool so the resume path can execute the invocation the human approved instead of raising a
     * second approval request.
     */
    @Override
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        // Only ONE suspend may exist per tool round (SuspendableToolCallingManager throws on two sentinels). If
        // another tool call already suspended this round, defer this one with a plain tool response so the LLM
        // retries it after the pending approval is resolved.
        if (actionContext.getSuspend() != null) {
            return "{\"deferred\": true, \"reason\": \"Another tool call is awaiting approval. Retry this tool " +
                "call after the pending approval is resolved.\"}";
        }

        Instant expiresAt = Instant.now()
            .plus(approvalExpiry != null ? approvalExpiry : DEFAULT_APPROVAL_EXPIRY);

        String result = ToolApprovalRequests.raise(
            new ToolApprovalRequests.Request(
                getName(), toolInput, "Approve tool call: " + getName(),
                "The AI agent wants to call the tool '" + getName() + "' with these arguments:\n\n" + toolInput,
                expiresAt, approvalChannelClusterElements, componentConnections, Map.of(),
                clusterElementDefinitionService, actionContext, toolContext));

        recordGateRaised();

        return result;
    }

    /**
     * Emits a tool-invocation audit event for the raised gate. Name-and-outcome only — the AI-chosen arguments are
     * deliberately excluded from the audit trail, matching the recorder's payload-free event contract.
     */
    private void recordGateRaised() {
        if (toolExecutionRecorder == null) {
            return;
        }

        toolExecutionRecorder.record(
            ToolExecutionEvent
                .builder(ToolExecutionSurface.AI_AGENT, ToolExecutionKind.COMPONENT, getName())
                .jobId(actionContext.getJobId())
                .outcome(ToolExecutionOutcome.APPROVAL_REQUIRED)
                .build());
    }

    private String getName() {
        ToolDefinition toolDefinition = delegate.getToolDefinition();

        return toolDefinition.name();
    }
}
