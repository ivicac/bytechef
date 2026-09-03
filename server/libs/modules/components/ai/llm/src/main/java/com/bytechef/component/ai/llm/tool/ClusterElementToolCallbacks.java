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

import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolCallbackProviderFunction;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolFunction;
import com.bytechef.platform.component.definition.ai.agent.ToolCallbackProviderFunction;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ToolCall;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * @author Ivica Cardic
 */
public class ClusterElementToolCallbacks {

    private final AiAgentToolFacade aiAgentToolFacade;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final List<ComponentRuleEnforcer> componentRuleEnforcers;

    @Nullable
    private final ToolExecutionRecorder toolExecutionRecorder;

    @Nullable
    private final ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver;

    public ClusterElementToolCallbacks(
        AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService) {

        this(aiAgentToolFacade, clusterElementDefinitionService, List.of(), null, null);
    }

    public ClusterElementToolCallbacks(
        AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
        List<ComponentRuleEnforcer> componentRuleEnforcers) {

        this(aiAgentToolFacade, clusterElementDefinitionService, componentRuleEnforcers, null, null);
    }

    @SuppressFBWarnings("EI")
    public ClusterElementToolCallbacks(
        AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
        List<ComponentRuleEnforcer> componentRuleEnforcers, @Nullable ToolExecutionRecorder toolExecutionRecorder,
        @Nullable ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver) {

        this.aiAgentToolFacade = aiAgentToolFacade;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.componentRuleEnforcers = List.copyOf(componentRuleEnforcers);
        this.toolExecutionRecorder = toolExecutionRecorder;
        this.toolInvocationWorkspaceResolver = toolInvocationWorkspaceResolver;
    }

    public List<ToolCallback> build(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections, ActionContext context) {

        return build(clusterElement, componentConnections, null, context, List.of());
    }

    public List<ToolCallback> build(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections,
        @Nullable String subagentConversationId, ActionContext context) {

        return build(clusterElement, componentConnections, subagentConversationId, context, List.of());
    }

    public List<ToolCallback> build(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections, ActionContext context,
        List<ClusterElement> approvalChannelClusterElements) {

        return build(clusterElement, componentConnections, null, context, approvalChannelClusterElements);
    }

    public List<ToolCallback> build(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections,
        @Nullable String subagentConversationId, ActionContext context,
        List<ClusterElement> approvalChannelClusterElements) {

        List<ToolCallback> toolCallbacks = doBuild(
            clusterElement, componentConnections, subagentConversationId, context);

        if (componentRuleEnforcers.isEmpty()) {
            return toolCallbacks;
        }

        ComponentConnection componentConnection = componentConnections.get(clusterElement.getWorkflowNodeName());
        Long connectionId = componentConnection == null ? null : componentConnection.connectionId();

        List<ToolCallback> ruleEnforcingToolCallbacks = new ArrayList<>();

        for (ToolCallback toolCallback : toolCallbacks) {
            // An already-governed callback is left alone. The approval gate declares itself a TOOLS cluster element,
            // so the gate's own element passes through here a second time carrying callbacks an inner build() call
            // already wrapped: unwrapping lands on a rule layer exactly when that has happened. Wrapping again would
            // put a rule layer OUTSIDE the gate, and DelegatingToolCallback.unwrap cannot strip it (the rule layer is
            // deliberately not delegating) — so the gate-resume branch would re-enter the gate and suspend forever,
            // and every gated call would be rule-evaluated twice.
            if (DelegatingToolCallback.unwrap(toolCallback) instanceof RuleEnforcingToolCallback) {
                ruleEnforcingToolCallbacks.add(toolCallback);

                continue;
            }

            ruleEnforcingToolCallbacks.add(
                new RuleEnforcingToolCallback(
                    toolCallback, clusterElement, componentRuleEnforcers, connectionId, context,
                    approvalChannelClusterElements, componentConnections, clusterElementDefinitionService,
                    toolExecutionRecorder, toolInvocationWorkspaceResolver));
        }

        return ruleEnforcingToolCallbacks;
    }

    /**
     * Records a rule-raised approval's outcome. The agent action calls this rather than the SPI, so it never imports
     * the rule module.
     */
    public void recordRuleApprovalResolution(
        List<Long> ruleIds, String componentName, String toolName, String toolCallName, boolean approved,
        @Nullable String approvedBy, @Nullable Long jobId) {

        RuleEnforcingToolCallback.recordApprovalResolution(
            componentRuleEnforcers,
            new ToolCall(
                componentName, toolName, toolCallName, Map.of(), null, jobId, null, approvedBy, null, null),
            ruleIds, approved, approvedBy);
    }

    private List<ToolCallback> doBuild(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections,
        @Nullable String subagentConversationId, ActionContext context) {

        Object clusterElementFunction = clusterElementDefinitionService.getClusterElement(
            clusterElement.getComponentName(), clusterElement.getComponentVersion(),
            clusterElement.getClusterElementName());

        if (clusterElementFunction instanceof MultipleConnectionsToolCallbackProviderFunction providerFunction) {
            try {
                ToolCallback[] providerToolCallbacks = providerFunction
                    .apply(
                        ParametersFactory.create(clusterElement.getParameters()),
                        getConnectionParameters(componentConnections, clusterElement),
                        ParametersFactory.create(clusterElement.getExtensions()),
                        componentConnections, context)
                    .getToolCallbacks();

                return Arrays.asList(providerToolCallbacks);
            } catch (Exception exception) {
                throw initializationException(clusterElement, exception, context);
            }
        } else if (clusterElementFunction instanceof ToolCallbackProviderFunction toolCallbackProviderFunction) {
            try {
                ComponentConnection componentConnection = componentConnections.get(
                    clusterElement.getWorkflowNodeName());

                ToolCallback[] providerToolCallbacks = toolCallbackProviderFunction
                    .apply(
                        ParametersFactory.create(clusterElement.getParameters()),
                        ParametersFactory.create(componentConnection), context)
                    .getToolCallbacks();

                return Arrays.asList(providerToolCallbacks);
            } catch (Exception exception) {
                throw initializationException(clusterElement, exception, context);
            }
        } else if (clusterElementFunction instanceof MultipleConnectionsToolFunction) {
            return List.of(
                aiAgentToolFacade.getFunctionToolCallback(
                    clusterElement, componentConnections, subagentConversationId, context));
        } else {
            ComponentConnection componentConnection = componentConnections.get(clusterElement.getWorkflowNodeName());

            return List.of(
                aiAgentToolFacade.getFunctionToolCallback(clusterElement, componentConnection, context));
        }
    }

    private static Parameters getConnectionParameters(
        Map<String, ComponentConnection> componentConnections, ClusterElement clusterElement) {

        ComponentConnection componentConnection = componentConnections.get(clusterElement.getWorkflowNodeName());

        return ParametersFactory.create(componentConnection);
    }

    private static RuntimeException initializationException(
        ClusterElement clusterElement, Throwable cause, ActionContext context) {

        Class<? extends Throwable> causeClass = cause.getClass();

        String message = String.format(
            "Failed to initialize tool callback for cluster element '%s' (component=%s v%d): %s",
            clusterElement.getClusterElementName(), clusterElement.getComponentName(),
            clusterElement.getComponentVersion(),
            cause.getMessage() == null ? causeClass.getSimpleName() : cause.getMessage());

        context.log(log -> log.error(message, cause));

        return new IllegalStateException(message, cause);
    }
}
