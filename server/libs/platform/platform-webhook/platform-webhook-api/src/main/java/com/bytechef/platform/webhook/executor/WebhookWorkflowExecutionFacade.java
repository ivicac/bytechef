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

package com.bytechef.platform.webhook.executor;

import com.bytechef.platform.component.domain.WebhookTriggerFlags;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.job.sync.SseStreamBridge;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import java.util.concurrent.CompletableFuture;

/**
 * Transport-agnostic facade for orchestrating webhook-triggered workflow executions. Pulls together the disabled-check,
 * trigger-flag lookup, and {@link WebhookWorkflowExecutor} dispatch so callers don't have to reproduce the sequence
 * across each new transport.
 *
 * <p>
 * Two consumers today:
 * </p>
 * <ul>
 * <li><b>WebhookTriggerController</b> — the HTTP/SSE transport. The controller renders {@link WebhookExecutionResult}
 * into Spring {@link org.springframework.http.ResponseEntity} and wires the streaming variant to a
 * {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}-backed {@link SseStreamBridge}.</li>
 * <li><b>WebhookBridgeAgent</b> (AI Copilot, EE) — the AG-UI transport. The bridge translates
 * {@link WebhookExecutionResult} into AG-UI {@code RUN_FINISHED}/{@code RUN_ERROR} events and wraps a
 * {@link SseStreamBridge} that translates webhook stream chunks into AG-UI {@code TEXT_MESSAGE_CONTENT} events.</li>
 * </ul>
 *
 * <p>
 * Both transports consume the same facade through different {@link SseStreamBridge} implementations. Adding a third
 * transport (e.g. a CLI invocation) is one more {@link SseStreamBridge} impl, not a controller refactor.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface WebhookWorkflowExecutionFacade {

    /**
     * Executes a workflow synchronously. Use for non-streaming chat replies (workflows whose terminal step is
     * {@code chat/responseToRequest}); for streaming AI agent workflows, callers should route through
     * {@link #executeStreaming(WorkflowExecutionId, WebhookRequest, SseStreamBridge)} so per-token deltas reach the
     * bridge during execution.
     *
     * <p>
     * Returns:
     * </p>
     * <ul>
     * <li>{@link WebhookExecutionResult.Ok} with the workflow's output payload — typically a {@link java.util.Map}, may
     * be {@code null} when the workflow produces no output.</li>
     * <li>{@link WebhookExecutionResult.Disabled} when {@link #isWorkflowDisabled(WorkflowExecutionId)} is true. The
     * caller decides how to surface the disabled state (HTTP 410 GONE for the controller; AG-UI {@code RUN_ERROR} for
     * the copilot bridge).</li>
     * </ul>
     *
     * @param workflowExecutionId the workflow execution to invoke
     * @param webhookRequest      the request payload to pass into the trigger
     * @return the typed result envelope; never {@code null}
     */
    WebhookExecutionResult executeSync(WorkflowExecutionId workflowExecutionId, WebhookRequest webhookRequest);

    /**
     * Executes a workflow with streaming output. Events are pushed to {@code sseStreamBridge} as the workflow runs;
     * completion / error are signalled via {@link SseStreamBridge#onComplete()} / {@link SseStreamBridge#onError}.
     *
     * <p>
     * When the workflow is disabled the bridge receives a synchronous {@link SseStreamBridge#onError} call with an
     * {@code IllegalStateException}, mirroring the HTTP controller's pre-existing behaviour. The returned future
     * completes immediately in that case.
     * </p>
     *
     * @param workflowExecutionId the workflow execution to invoke
     * @param webhookRequest      the request payload
     * @param sseStreamBridge     the transport-specific event sink (SSE emitter wrap, AG-UI bridge, etc.)
     * @return a future that completes when the workflow run finishes or errors
     */
    CompletableFuture<Void> executeStreaming(
        WorkflowExecutionId workflowExecutionId, WebhookRequest webhookRequest, SseStreamBridge sseStreamBridge);

    /**
     * Returns the trigger flags associated with the workflow's webhook trigger. Cached lookup — the underlying
     * {@code TriggerDefinitionService} call is the same one the controller already uses to decide between sync and
     * async dispatch on the HTTP path. The copilot bridge consumes this to pick between
     * {@link #executeSync(WorkflowExecutionId, WebhookRequest)} and
     * {@link #executeStreaming(WorkflowExecutionId, WebhookRequest, SseStreamBridge)}.
     */
    WebhookTriggerFlags getWebhookTriggerFlags(WorkflowExecutionId workflowExecutionId);

    /**
     * Returns {@code true} when the workflow contains at least one task whose action streams output (e.g.
     * {@code openAi/v1/streamAsk}, {@code aiAgent/v1/streamChat}). The copilot bridge consults this flag BEFORE looking
     * at the trigger's {@code workflowSyncExecution} property: a chat workflow whose trigger demands sync semantics for
     * HTTP transport (so embedded chat clients keep working) but contains a streaming AI task needs the bridge to route
     * through {@link #executeStreaming} so per-token deltas flow to the AG-UI client. Otherwise the streaming task's
     * events would land in JobSyncExecutor's in-process bridge cache — unreachable from EE worker processes — and the
     * user would see a blank assistant reply.
     *
     * <p>
     * Detection is a syntactic match on task type strings. A task type containing {@code "stream"} (case-insensitive)
     * is considered streaming. This is intentionally a heuristic, not a registry lookup: it covers every existing
     * streaming action without coupling the facade to the AI component module, and it keeps the per-turn cost to a
     * single workflow load. False positives (a non-streaming action whose name happens to contain "stream") are benign
     * — the streaming path works for non-streaming tasks too, just without bridge-emitted SSE events.
     * </p>
     */
    boolean hasStreamingTask(WorkflowExecutionId workflowExecutionId);

    /**
     * Checks whether the workflow associated with the given execution id is currently disabled. Both transports use
     * this to short-circuit the dispatch path before the executor is invoked.
     */
    boolean isWorkflowDisabled(WorkflowExecutionId workflowExecutionId);
}
