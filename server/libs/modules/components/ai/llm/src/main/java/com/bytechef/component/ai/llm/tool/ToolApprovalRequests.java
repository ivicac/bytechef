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

import static com.bytechef.component.definition.approval.ApprovalChannelFunction.EXPIRES_AT;
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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
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

/**
 * Raises one human-approval request for a tool call and suspends the agent turn, so the request is delivered and
 * resumed identically whether it came from an approval gate on the canvas or from an administrative component rule.
 *
 * <p>
 * The suspend carries the gate's continue parameters ({@code GATED_TOOL_NAME}, {@code GATED_TOOL_INPUT},
 * {@code formUrl}, {@code FORM_TITLE}, {@code FORM_DESCRIPTION}) whatever raised it, because
 * {@code AbstractAiAgentChatAction.buildPatchedRequestSpec} routes on {@code GATED_TOOL_NAME} — one resume branch
 * serves both. A caller adds its own keys through {@code additionalContinueParameters}.
 * </p>
 *
 * <p>
 * Delivery is the configured channels (or the chat channel when there are none) AND, always, the {@code approvalTask}
 * channel — so every request has a row on the Approval Tasks page whatever transport was configured. That backstop is
 * what stops a webhook- or schedule-triggered run from raising an approval that reaches nobody and hangs the turn until
 * expiry.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class ToolApprovalRequests {

    private static final String APPROVAL_TASK_APPROVAL_CHANNEL_COMPONENT = "approvalTask";
    private static final String APPROVAL_TASK_APPROVAL_CHANNEL_NAME = "approvalTask";
    private static final String CHAT_APPROVAL_CHANNEL_COMPONENT = "chat";
    private static final String CHAT_APPROVAL_CHANNEL_NAME = "chat";
    private static final String FORM_URL = "formUrl";

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalRequests.class);

    private ToolApprovalRequests() {
    }

    @SuppressFBWarnings("EI")
    public record Request(
        String toolName, String toolInput, String title, String description, Instant expiresAt,
        List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections, Map<String, Object> additionalContinueParameters,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContextAware actionContext,
        @Nullable ToolContext toolContext) {
    }

    /**
     * Delivers the request and suspends.
     *
     * @return the sentinel a suspending tool returns as its result
     */
    public static String raise(Request request) {
        ActionContextAware actionContext = request.actionContext();

        String resumeUrl = actionContext.getResumeUrl();

        if (resumeUrl == null) {
            throw new IllegalStateException(
                "Cannot raise an approval request for tool '" + request.toolName() + "'. Ensure the server's public " +
                    "URL is configured and the workflow is running in a proper execution context.");
        }

        String formUrl = resumeUrl.replace("/job/resume/", "/resume/");

        if (actionContext.isEditorEnvironment()) {
            sendEditorApprovalRequestEvent(request, formUrl);
        } else {
            deliverApprovalRequest(request, formUrl);
        }

        Map<String, Object> continueParameters = new HashMap<>(request.additionalContinueParameters());

        continueParameters.put(ToolSuspendConstants.GATED_TOOL_NAME, request.toolName());
        continueParameters.put(ToolSuspendConstants.GATED_TOOL_INPUT, request.toolInput());
        continueParameters.put(FORM_URL, formUrl);
        continueParameters.put(FORM_TITLE, request.title());
        continueParameters.put(FORM_DESCRIPTION, request.description());

        actionContext.suspend(new ActionContext.Suspend(continueParameters, request.expiresAt()));

        return ToolSuspendConstants.SUSPENDED_SENTINEL;
    }

    @SuppressWarnings("unchecked")
    private static void sendEditorApprovalRequestEvent(Request request, String formUrl) {
        ToolContext toolContext = request.toolContext();

        if (toolContext == null) {
            return;
        }

        Map<String, Object> eventData = new LinkedHashMap<>();

        eventData.put(AiAgentSseEventType.EVENT_TYPE, AiAgentSseEventType.APPROVAL_REQUEST);
        eventData.put("resumeId", formUrl.substring(formUrl.lastIndexOf('/') + 1));
        eventData.put("formUrl", formUrl);
        eventData.put(FORM_TITLE, request.title());
        eventData.put(FORM_DESCRIPTION, request.description());
        eventData.put(EXPIRES_AT, request.expiresAt()
            .toString());
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

            return;
        }

        // Neither an SSE emitter nor a buffered-events queue is present — only the streaming Chat action wires these
        // into the ToolContext, so an editor test run of the non-streaming Chat action reaches here and the approval
        // card is silently dropped (the run still suspends and is resolvable via the hosted form). Warn so a hung
        // test run is diagnosable instead of failing silently.
        log.warn(
            "No SSE emitter or buffered-events queue in the tool context; the editor approval card for tool '{}' was " +
                "not delivered. The run is still suspended and resolvable via the hosted approval form.",
            request.toolName());
    }

    private static void deliverApprovalRequest(Request request, String formUrl) {
        Map<String, Object> channelInputParameters = new HashMap<>();

        channelInputParameters.put(FORM_TITLE, request.title());
        channelInputParameters.put(FORM_DESCRIPTION, request.description());
        channelInputParameters.put(EXPIRES_AT, request.expiresAt()
            .toString());

        List<ClusterElement> approvalChannelClusterElements = request.approvalChannelClusterElements();
        ClusterElementDefinitionService clusterElementDefinitionService = request.clusterElementDefinitionService();
        ActionContextAware actionContext = request.actionContext();

        // Best-effort per channel: a failing channel is logged and skipped so the remaining channels still deliver
        // and the turn still suspends. Only when EVERY channel fails is the call failed — then nobody was notified
        // and suspending would be a silent no-op.
        Delivery delivery = new Delivery();

        // The approval-task channel always runs, whatever else is configured, so the request has a row on the
        // Approval Tasks page regardless of transport. Without it a webhook- or schedule-triggered run with no
        // channels reaches nobody: the chat channel has a jobId but no listener, and the turn hangs until expiry.
        // A rule-raised approval needs this most — the admin who authored the rule may have no relationship to the
        // agent node at all — but the gate benefits identically. Skipped only when an approval-task channel is
        // already attached, so an admin who configured one does not get two rows.
        if (!containsApprovalTaskChannel(approvalChannelClusterElements)) {
            deliver(
                delivery, APPROVAL_TASK_APPROVAL_CHANNEL_COMPONENT, APPROVAL_TASK_APPROVAL_CHANNEL_NAME,
                () -> clusterElementDefinitionService.executeApprovalChannel(
                    APPROVAL_TASK_APPROVAL_CHANNEL_COMPONENT, 1, APPROVAL_TASK_APPROVAL_CHANNEL_NAME,
                    channelInputParameters, formUrl, null, actionContext));
        }

        if (approvalChannelClusterElements.isEmpty()) {
            // No channels configured on the agent node — default to the chat channel targeting the run's
            // originating conversation. The chat channel throws when the run has no jobId (in-process runs); a
            // webhook/schedule run has a jobId but no chat listener, so the approval task raised above is what
            // makes the request reachable at all.
            deliver(
                delivery, CHAT_APPROVAL_CHANNEL_COMPONENT, CHAT_APPROVAL_CHANNEL_NAME,
                () -> clusterElementDefinitionService.executeApprovalChannel(
                    CHAT_APPROVAL_CHANNEL_COMPONENT, 1, CHAT_APPROVAL_CHANNEL_NAME, channelInputParameters, formUrl,
                    null, actionContext));
        } else {
            Map<String, ComponentConnection> componentConnections = request.componentConnections();

            for (ClusterElement approvalChannel : approvalChannelClusterElements) {
                ComponentConnection componentConnection = componentConnections.get(
                    approvalChannel.getWorkflowNodeName());

                Map<String, Object> mergedInputParameters = new HashMap<>(channelInputParameters);

                mergedInputParameters.putAll(approvalChannel.getParameters());

                deliver(
                    delivery, approvalChannel.getComponentName(), approvalChannel.getClusterElementName(),
                    () -> clusterElementDefinitionService.executeApprovalChannel(
                        approvalChannel.getComponentName(), approvalChannel.getComponentVersion(),
                        approvalChannel.getClusterElementName(), mergedInputParameters, formUrl, componentConnection,
                        actionContext));
            }
        }

        if (delivery.deliveredCount == 0) {
            throw new IllegalStateException(
                "None of the " + delivery.attemptedCount + " approval channels could deliver the approval request " +
                    "for tool '" + request.toolName() + "'.",
                delivery.lastException);
        }
    }

    private static boolean containsApprovalTaskChannel(List<ClusterElement> approvalChannelClusterElements) {
        for (ClusterElement approvalChannel : approvalChannelClusterElements) {
            if (APPROVAL_TASK_APPROVAL_CHANNEL_COMPONENT.equals(approvalChannel.getComponentName())) {
                return true;
            }
        }

        return false;
    }

    private static void deliver(
        Delivery delivery, String componentName, String clusterElementName, Runnable channelInvocation) {

        delivery.attemptedCount++;

        try {
            channelInvocation.run();

            delivery.deliveredCount++;
        } catch (Exception exception) {
            delivery.lastException = exception;

            log.warn(
                "Approval channel {}/{} failed to deliver the tool approval request: {}", componentName,
                clusterElementName, exception.getMessage());
        }
    }

    /**
     * The fan-out's running tally. A mutable holder rather than three locals because the per-channel invocation is a
     * lambda, and a lambda cannot assign to an enclosing local.
     */
    private static final class Delivery {

        private int attemptedCount;
        private int deliveredCount;

        @Nullable
        private Exception lastException;
    }
}
