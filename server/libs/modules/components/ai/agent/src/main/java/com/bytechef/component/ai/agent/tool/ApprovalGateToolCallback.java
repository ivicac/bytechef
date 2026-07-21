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

package com.bytechef.component.ai.agent.tool;

import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_DESCRIPTION;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_TITLE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.platform.ai.constant.AiAgentSseEventType;
import com.bytechef.platform.ai.constant.AiAgentToolContextKey;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Platform-enforced per-tool approval gate (HITL phase 3). Wraps a tool callback whose cluster-element entry carries
 * {@code requiresApproval: true}: instead of executing, the first call delivers a standard approval request (tool name
 * + the AI-chosen arguments) through the agent's APPROVAL_CHANNELS cluster elements — defaulting to the chat channel
 * when none are configured — and suspends the workflow. On resume, approval executes the tool with the original
 * arguments and rejection feeds a denial back into the agent loop (see
 * {@code AbstractAiAgentChatAction.buildPatchedRequestSpec}). Enforcement lives here, in the platform — the LLM cannot
 * invoke a flagged tool un-gated.
 *
 * @author Ivica Cardic
 */
public class ApprovalGateToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGateToolCallback.class);

    private static final String CHAT_APPROVAL_CHANNEL_COMPONENT = "approval";
    private static final String CHAT_APPROVAL_CHANNEL_NAME = "chat";

    private final ToolCallback delegate;
    private final List<ClusterElement> approvalChannelClusterElements;
    private final Map<String, ComponentConnection> componentConnections;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ActionContextAware actionContext;

    public ApprovalGateToolCallback(
        ToolCallback delegate, List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContext actionContext) {

        this.delegate = delegate;
        this.approvalChannelClusterElements = List.copyOf(approvalChannelClusterElements);
        this.componentConnections = Map.copyOf(componentConnections);
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.actionContext = (ActionContextAware) actionContext;
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
        // Only ONE suspend may exist per tool round (SuspendableToolCallingManager throws on two sentinels). If
        // another tool call already suspended this round, defer this one with a plain tool response so the LLM
        // retries it after the pending approval is resolved.
        if (actionContext.getSuspend() != null) {
            return "{\"deferred\": true, \"reason\": \"Another tool call is awaiting approval. Retry this tool " +
                "call after the pending approval is resolved.\"}";
        }

        String resumeUrl = actionContext.getResumeUrl();

        if (resumeUrl == null) {
            throw new IllegalStateException(
                "Cannot raise an approval request for tool '" + getName() + "'. Ensure the server's public URL is " +
                    "configured and the workflow is running in a proper execution context.");
        }

        String formUrl = resumeUrl.replace("/job/resume/", "/resume/");

        if (actionContext.isEditorEnvironment()) {
            // Editor test runs have no channel listeners (channels are production transports), but the agent's
            // SSE stream IS connected — send the approval card event through the ToolContext's emitter, the same
            // path ask_user_question uses, so the canvas test chat renders the card.
            sendEditorApprovalRequestEvent(toolContext, formUrl, toolInput);
        } else {
            deliverApprovalRequest(formUrl, toolInput);
        }

        Map<String, Object> continueParameters = new HashMap<>();

        continueParameters.put(ToolSuspendConstants.GATED_TOOL_NAME, getName());
        continueParameters.put(ToolSuspendConstants.GATED_TOOL_INPUT, toolInput);
        continueParameters.put("formUrl", formUrl);

        Instant expiresAt = Instant.now()
            .plus(60, ChronoUnit.DAYS);

        actionContext.suspend(new ActionContext.Suspend(continueParameters, expiresAt));

        return ToolSuspendConstants.SUSPENDED_SENTINEL;
    }

    @SuppressWarnings("unchecked")
    private void sendEditorApprovalRequestEvent(@Nullable ToolContext toolContext, String formUrl, String toolInput) {
        if (toolContext == null) {
            return;
        }

        Map<String, Object> eventData = new LinkedHashMap<>();

        eventData.put(AiAgentSseEventType.EVENT_TYPE, AiAgentSseEventType.APPROVAL_REQUEST);
        eventData.put("resumeId", formUrl.substring(formUrl.lastIndexOf('/') + 1));
        eventData.put("formUrl", formUrl);
        eventData.put(FORM_TITLE, "Approve tool call: " + getName());
        eventData.put(
            FORM_DESCRIPTION,
            "The AI agent wants to call the tool '" + getName() + "' with these arguments:\n\n" + toolInput);
        eventData.put("inputs", List.of());

        Map<String, Object> toolContextMap = toolContext.getContext();

        Object emitterReferenceObject = toolContextMap.get(AiAgentToolContextKey.SSE_EMITTER_REFERENCE);

        if (emitterReferenceObject instanceof AtomicReference<?> emitterReference
            && emitterReference.get() instanceof ActionDefinition.SseEmitterHandler.SseEmitter sseEmitter) {

            try {
                sseEmitter.send(eventData);

                return;
            } catch (Exception exception) {
                log.warn("SSE send of approval_request failed, falling back to buffering: {}", exception.getMessage());
            }
        }

        Object bufferedEventsObject = toolContextMap.get(AiAgentToolContextKey.SSE_BUFFERED_EVENTS);

        if (bufferedEventsObject instanceof Queue<?> queue) {
            ((Queue<Map<String, Object>>) queue).add(eventData);
        }
    }

    private void deliverApprovalRequest(String formUrl, String toolInput) {
        Map<String, Object> channelInputParameters = new HashMap<>();

        channelInputParameters.put(FORM_TITLE, "Approve tool call: " + getName());
        channelInputParameters.put(
            FORM_DESCRIPTION,
            "The AI agent wants to call the tool '" + getName() + "' with these arguments:\n\n" + toolInput);

        if (approvalChannelClusterElements.isEmpty()) {
            // No channels configured on the agent node — default to the chat channel targeting the run's
            // originating conversation. The chat channel itself fails loudly when the run has no job/chat origin,
            // which keeps the "no silent no-op" rule intact for webhook/schedule runs without configured channels.
            clusterElementDefinitionService.executeApprovalChannel(
                CHAT_APPROVAL_CHANNEL_COMPONENT, 1, CHAT_APPROVAL_CHANNEL_NAME, channelInputParameters, formUrl,
                null, actionContext);

            return;
        }

        for (ClusterElement approvalChannel : approvalChannelClusterElements) {
            ComponentConnection componentConnection = componentConnections.get(
                approvalChannel.getWorkflowNodeName());

            Map<String, Object> mergedInputParameters = new HashMap<>(channelInputParameters);

            mergedInputParameters.putAll(approvalChannel.getParameters());

            clusterElementDefinitionService.executeApprovalChannel(
                approvalChannel.getComponentName(), approvalChannel.getComponentVersion(),
                approvalChannel.getClusterElementName(), mergedInputParameters, formUrl, componentConnection,
                actionContext);
        }
    }

    private String getName() {
        ToolDefinition toolDefinition = delegate.getToolDefinition();

        return toolDefinition.name();
    }
}
