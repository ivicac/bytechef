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

package com.bytechef.component.approval.cluster.tool;

import static com.bytechef.component.approval.constant.ApprovalConstants.APPROVAL;
import static com.bytechef.component.definition.ai.agent.BaseToolFunction.TOOLS;

import com.bytechef.component.approval.action.ApprovalRequestApprovalAction;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionContext.Suspend;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.ComponentDsl.ModifiableClusterElementDefinition;
import com.bytechef.component.definition.Property;
import com.bytechef.platform.ai.constant.AiAgentToolSseContext;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.ai.tool.ToolSuspension;
import com.bytechef.platform.ai.tool.ToolSuspensionException;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.SuspendAwareSseEmitterHandler;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolFunction;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts the {@link ApprovalRequestApprovalAction} into a {@link MultipleConnectionsToolFunction} so the AI agent can
 * invoke it as a tool. It is built by hand because {@code ComponentDsl#tool(...)} supports only single-connection
 * performs, while this action's {@link MultipleConnectionsPerformFunction} needs every connection and must be able to
 * suspend the agent.
 *
 * <p>
 * Each invocation gets its {@link ActionContext} from {@link ClusterElementContextAware#toActionContext}. That context
 * keeps the tool's own component and action (connections reach the action through {@code componentConnections}), but
 * forwards {@code suspend(...)} and the resume URL to the agent's action context, so the action's suspend suspends the
 * agent's task, where {@code SuspendableToolCallingManager} picks it up.
 *
 * <p>
 * The action's perform always suspends (or throws) and returns {@code null}, so the tool returns the suspended tool
 * result (see {@link ToolSuspension}) instead; on resume the approver's answer replaces it as this tool call's
 * response. When the request cannot be sent, the tool throws {@link ToolSuspensionException}, which fails the agent
 * rather than letting the model continue without the approval.
 *
 * <p>
 * A workflow editor test run's in-memory job cannot be resumed through the resume URL, so there the tool neither sends
 * the request nor suspends, and tells the model that nothing was approved.
 *
 * <p>
 * The suspend carries the tool's own input parameters, because the approval form would otherwise be built from the
 * suspended task's parameters, which are the agent's.
 *
 * @author Ivica Cardic
 */
public class ApprovalRequestApprovalTool {

    static final String EDITOR_ENVIRONMENT_RESULT =
        "Approval requests cannot be sent in a workflow editor test run, so nothing was approved. Do not perform the " +
            "action that needed approval; tell the user that it needs approval, which is only possible when the " +
            "workflow runs outside the editor.";

    public static ModifiableClusterElementDefinition<MultipleConnectionsToolFunction> of(
        ClusterElementDefinitionService clusterElementDefinitionService) {

        ModifiableActionDefinition actionDefinition = ApprovalRequestApprovalAction.of(clusterElementDefinitionService);
        MultipleConnectionsPerformFunction performFunction = (MultipleConnectionsPerformFunction) actionDefinition
            .getPerform()
            .orElseThrow();

        Optional<List<? extends Property>> propertiesOptional = actionDefinition.getProperties();

        return ComponentDsl.<MultipleConnectionsToolFunction>clusterElement("requestApproval")
            .title("Request Approval")
            .description("Sends an approval request and waits for a human to approve or reject.")
            .type(TOOLS)
            .properties(propertiesOptional.orElse(List.of()))
            .object(() -> (inputParameters, connectionParameters, extensions, componentConnections, context) -> {
                if (context.isEditorEnvironment()) {
                    return EDITOR_ENVIRONMENT_RESULT;
                }

                ClusterElementContextAware clusterElementContextAware = (ClusterElementContextAware) context;

                // On the agent path toActionContext returns the LIVE shared agent context, so this is the same
                // surface SuspendableToolCallingManager reads.
                ActionContext actionContext = clusterElementContextAware.toActionContext(
                    APPROVAL, 1, "requestApproval", null);

                // Only ONE suspend may exist per tool round (two sentinels make the loop manager throw and fail the
                // whole turn — after channels already delivered live requests). If another tool call already
                // suspended this round (a second requestApproval, or a gated tool that ran first), defer this one
                // with a plain response so the model retries it after the pending approval resolves — mirroring
                // ApprovalGateToolCallback's guard.
                if (((ActionContextAware) actionContext).getSuspend() != null) {
                    return "{\"deferred\": true, \"reason\": \"Another tool call is awaiting approval. Retry this " +
                        "tool call after the pending approval is resolved.\"}";
                }

                Object result = performFunction.apply(inputParameters, componentConnections, extensions, actionContext);

                if (result instanceof SuspendAwareSseEmitterHandler suspendAwareSseEmitterHandler) {
                    // In editor runs the action returns the approval card event as a one-shot emitter output, drained
                    // by the task pipeline's post-output processor — but the tool path invokes perform directly and
                    // has no post-output processing. Drain the handler inline through an emitter that forwards the card
                    // onto the agent's live SSE stream (bound for this thread by AiAgentToolFacade), so the suspend
                    // inside it still lands on the shared action context AND the canvas test chat renders the card.
                    suspendAwareSseEmitterHandler.handle(new AgentStreamForwardingSseEmitter());
                }

                Suspend suspend = ((ActionContextAware) actionContext).getSuspend();

                if (suspend != null) {
                    Map<String, Object> continueParameters = new HashMap<>(suspend.continueParameters());

                    continueParameters.put(MetadataConstants.APPROVAL_FORM_PARAMETERS, inputParameters.toMap());

                    actionContext.suspend(new Suspend(continueParameters, suspend.expiresAt()));
                }

                return ToolSuspension.suspendedToolResult(actionContext);
            });
    }

    /**
     * Forwards a drained editor card event onto the agent's live SSE stream — sending to the connected emitter when one
     * is attached, otherwise buffering it for when the client connects — using the emitter reference / buffered-events
     * queue that {@code AiAgentToolFacade} binds to the current thread for the duration of the tool call. Silently
     * drops the event only when neither is present (no agent stream is attached), matching the gate's behaviour.
     */
    private static final class AgentStreamForwardingSseEmitter
        implements ActionDefinition.SseEmitterHandler.SseEmitter {

        @Override
        public void addTimeoutListener(Runnable timeoutListener) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Throwable throwable) {
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(Object data) {
            Object emitterReferenceObject = AiAgentToolSseContext.getEmitterReference();

            if (emitterReferenceObject instanceof AtomicReference<?> emitterReference
                && emitterReference.get() instanceof ActionDefinition.SseEmitterHandler.SseEmitter sseEmitter) {

                sseEmitter.send(data);

                return;
            }

            Object bufferedEventsObject = AiAgentToolSseContext.getBufferedEvents();

            if (bufferedEventsObject instanceof Queue<?> queue) {
                ((Queue<Object>) queue).add(data);
            }
        }
    }

    private ApprovalRequestApprovalTool() {
    }
}
