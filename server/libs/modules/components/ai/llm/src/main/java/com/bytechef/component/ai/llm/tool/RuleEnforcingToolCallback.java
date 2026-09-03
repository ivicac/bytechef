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

package com.bytechef.component.ai.llm.tool;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.ai.constant.AiAgentToolContextKey;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.Decision;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ToolCall;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Applies administrative component rules to one agent tool call. Wraps every callback
 * {@link ClusterElementToolCallbacks} produces, so a tool contributed by a provider element is governed exactly like a
 * plain component action attached as a tool.
 *
 * <p>
 * It reports the delegate's tool definition as its own, but is deliberately NOT a {@link DelegatingToolCallback}:
 * {@code DelegatingToolCallback.unwrap} is recursive and the gate-resume path uses it to execute an approved tool
 * without re-raising the gate. The rule layer must survive that unwrap and stay innermost, so a human-approved
 * re-execution is still rule-checked.
 * </p>
 *
 * <p>
 * A blocked call returns a denial to the model rather than throwing, mirroring the gate's rejection: the agent can
 * explain the refusal and replan instead of failing the run. Enforcement never fails a turn — an enforcer that throws
 * is logged and the call proceeds.
 * </p>
 *
 * @author Ivica Cardic
 */
public class RuleEnforcingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RuleEnforcingToolCallback.class);

    private final ActionContextAware actionContext;
    private final List<ClusterElement> approvalChannelClusterElements;
    private final ClusterElement clusterElement;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final Map<String, ComponentConnection> componentConnections;
    private final List<ComponentRuleEnforcer> componentRuleEnforcers;

    @Nullable
    private final Long connectionId;

    private final ToolCallback delegate;

    @Nullable
    private final ToolExecutionRecorder toolExecutionRecorder;

    @SuppressFBWarnings("EI2")
    public RuleEnforcingToolCallback(
        ToolCallback delegate, ClusterElement clusterElement, List<ComponentRuleEnforcer> componentRuleEnforcers,
        @Nullable Long connectionId, ActionContext actionContext,
        List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections,
        ClusterElementDefinitionService clusterElementDefinitionService,
        @Nullable ToolExecutionRecorder toolExecutionRecorder) {

        this.delegate = delegate;
        this.clusterElement = clusterElement;
        this.componentRuleEnforcers = List.copyOf(componentRuleEnforcers);
        this.connectionId = connectionId;
        this.actionContext = (ActionContextAware) actionContext;
        this.approvalChannelClusterElements = List.copyOf(approvalChannelClusterElements);
        this.componentConnections = Map.copyOf(componentConnections);
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.toolExecutionRecorder = toolExecutionRecorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        if (componentRuleEnforcers.isEmpty()) {
            return delegate.call(toolInput, toolContext);
        }

        ToolCall toolCall = toToolCall(toolInput, toolContext);
        Decision decision = checkBeforeCall(toolCall);

        if (decision instanceof Decision.Block block) {
            recordRuleBlocked(toolCall);

            return JsonUtils.write(Map.of("blocked", true, "reason", block.reason()));
        }

        if (decision instanceof Decision.RequireApproval requireApproval) {
            // Only ONE suspend may exist per tool round; another tool already suspended, so defer this one the way
            // the approval gate does and let the model retry after the pending approval resolves.
            if (actionContext.getSuspend() != null) {
                return "{\"deferred\": true, \"reason\": \"Another tool call is awaiting approval. Retry this tool " +
                    "call after the pending approval is resolved.\"}";
            }

            return ToolApprovalRequests.raise(
                new ToolApprovalRequests.Request(
                    toolCall.toolCallName(), toolInput, requireApproval.title(), requireApproval.description(),
                    requireApproval.expiresAt(), approvalChannelClusterElements, componentConnections,
                    Map.of(
                        ToolSuspendConstants.RULE_IDS, requireApproval.ruleIds(),
                        ToolSuspendConstants.RULE_COMPONENT_NAME, toolCall.componentName(),
                        ToolSuspendConstants.RULE_TOOL_NAME, toolCall.toolName()),
                    clusterElementDefinitionService, actionContext, toolContext));
        }

        String result = delegate.call(toolInput, toolContext);

        recordAfterCall(toolCall, result);

        return result;
    }

    /**
     * Records a human's decision on an approval a rule raised. Called from the agent's resume branch, which must not
     * depend on the rule module.
     */
    public static void recordApprovalResolution(
        List<ComponentRuleEnforcer> componentRuleEnforcers, ToolCall toolCall, List<Long> ruleIds, boolean approved,
        @Nullable String approvedBy) {

        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                componentRuleEnforcer.recordApprovalResolution(ruleIds, toolCall, approved, approvedBy);
            } catch (RuntimeException exception) {
                log.warn("Could not record the approval resolution for rules {}", ruleIds, exception);
            }
        }
    }

    private Decision checkBeforeCall(ToolCall toolCall) {
        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                Decision decision = componentRuleEnforcer.checkBeforeCall(toolCall);

                if (!(decision instanceof Decision.Allow)) {
                    return decision;
                }
            } catch (RuntimeException exception) {
                log.warn(
                    "Could not evaluate component rules for tool '{}'; allowing the call",
                    toolCall.toolCallName(), exception);
            }
        }

        return new Decision.Allow();
    }

    /**
     * Emits a tool-invocation audit event for a rule block, so it appears on the Tool Invocations page alongside
     * {@code APPROVAL_REQUIRED} and {@code APPROVAL_DENIED}. Name and outcome only — the AI-chosen arguments are
     * deliberately excluded from the audit trail, matching the approval gate's recorder contract.
     */
    private void recordRuleBlocked(ToolCall toolCall) {
        if (toolExecutionRecorder == null) {
            return;
        }

        toolExecutionRecorder.record(
            ToolExecutionEvent
                .builder(ToolExecutionSurface.AI_AGENT, ToolExecutionKind.COMPONENT, toolCall.toolCallName())
                .jobId(actionContext.getJobId())
                .outcome(ToolExecutionOutcome.RULE_BLOCKED)
                .build());
    }

    private void recordAfterCall(ToolCall toolCall, @Nullable Object result) {
        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                componentRuleEnforcer.recordAfterCall(toolCall, result);
            } catch (RuntimeException exception) {
                log.warn("Could not record AFTER-phase rules for tool '{}'", toolCall.toolCallName(), exception);
            }
        }
    }

    private ToolCall toToolCall(String toolInput, @Nullable ToolContext toolContext) {
        ToolDefinition toolDefinition = delegate.getToolDefinition();

        // Neither ActionContextAware nor JobContextAware exposes a task execution id, so a rule can only correlate
        // this call to a job, not to one specific task execution within it.
        return new ToolCall(
            clusterElement.getComponentName(), clusterElement.getClusterElementName(), toolDefinition.name(),
            parseToolInput(toolInput), connectionId, actionContext.getJobId(), null,
            fetchApprovedBy(toolContext), actionContext.getJobPrincipalId(), actionContext.getPlatformType());
    }

    /**
     * The model's arguments as a map, so a condition reads them the way it reads a workflow node's input. Malformed
     * JSON yields an empty map rather than failing the call: the delegate is the authority on its own input.
     */
    private static Map<String, ?> parseToolInput(String toolInput) {
        try {
            return JsonUtils.readMap(toolInput);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    @Nullable
    private static String fetchApprovedBy(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Map<String, Object> context = toolContext.getContext();

        return (String) context.get(AiAgentToolContextKey.APPROVED_BY);
    }
}
