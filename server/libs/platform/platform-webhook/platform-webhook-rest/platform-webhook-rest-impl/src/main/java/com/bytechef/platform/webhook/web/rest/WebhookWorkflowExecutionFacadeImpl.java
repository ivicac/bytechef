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

package com.bytechef.platform.webhook.web.rest;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.platform.component.domain.WebhookTriggerFlags;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.job.sync.SseStreamBridge;
import com.bytechef.platform.webhook.executor.WebhookExecutionResult;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutionFacade;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link WebhookWorkflowExecutionFacade}. Pulls together {@link WebhookWorkflowExecutor},
 * {@link TriggerDefinitionService}, {@link JobPrincipalAccessorRegistry}, and {@link WorkflowService} so a single call
 * site can dispatch a webhook-triggered workflow without reproducing the disabled-check + flag-fetch sequence the
 * existing {@code WebhookTriggerController} runs inline today.
 *
 * <p>
 * Lives next to the existing controller in {@code platform-webhook-rest-impl} because:
 * </p>
 * <ul>
 * <li>The controller remains a primary consumer (HTTP/SSE transport).</li>
 * <li>The future copilot bridge (in {@code ai-copilot-service}) consumes the facade through its API interface in
 * {@code platform-webhook-api}, which any service module already transitively imports — so the impl's location is
 * irrelevant to non-HTTP consumers.</li>
 * </ul>
 *
 * <p>
 * {@code @ConditionalOnCoordinator} mirrors the controller — the facade only makes sense in apps that host the
 * coordinator (which is what runs the workflow execution). The copilot service-module also runs in coordinator-equipped
 * deployments, so the gate is consistent across consumers.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnCoordinator
public class WebhookWorkflowExecutionFacadeImpl implements WebhookWorkflowExecutionFacade {

    private final JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry;
    private final TriggerDefinitionService triggerDefinitionService;
    private final WebhookWorkflowExecutor webhookWorkflowExecutor;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public WebhookWorkflowExecutionFacadeImpl(
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, TriggerDefinitionService triggerDefinitionService,
        WebhookWorkflowExecutor webhookWorkflowExecutor, WorkflowService workflowService) {

        this.jobPrincipalAccessorRegistry = jobPrincipalAccessorRegistry;
        this.triggerDefinitionService = triggerDefinitionService;
        this.webhookWorkflowExecutor = webhookWorkflowExecutor;
        this.workflowService = workflowService;
    }

    @Override
    public WebhookExecutionResult executeSync(
        WorkflowExecutionId workflowExecutionId, WebhookRequest webhookRequest) {

        if (isWorkflowDisabled(workflowExecutionId)) {
            // Match the controller's wording so HTTP responses don't drift if/when the controller adopts the facade.
            return new WebhookExecutionResult.Disabled("Workflow is disabled.");
        }

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, webhookRequest);

        return new WebhookExecutionResult.Ok(outputs);
    }

    @Override
    public CompletableFuture<Void> executeStreaming(
        WorkflowExecutionId workflowExecutionId, WebhookRequest webhookRequest, SseStreamBridge sseStreamBridge) {

        if (isWorkflowDisabled(workflowExecutionId)) {
            // Mirror the controller's pre-existing behaviour: synchronously notify the bridge so the SSE client
            // sees a single error event before the stream closes. The future is completed-in-place because there
            // is no async work to await.
            sseStreamBridge.onError(new IllegalStateException("Workflow is disabled."));

            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = webhookWorkflowExecutor.executeAsync(
            workflowExecutionId, webhookRequest, sseStreamBridge);

        // Whether-complete chain mirrors what the controller does today: success → onComplete; failure → onError
        // with the throwable's message. Centralising this here means transports don't have to remember to wire it.
        future.whenComplete((unused, throwable) -> {
            if (throwable != null) {
                sseStreamBridge.onError(throwable);
            } else {
                sseStreamBridge.onComplete();
            }
        });

        return future;
    }

    @Override
    public WebhookTriggerFlags getWebhookTriggerFlags(WorkflowExecutionId workflowExecutionId) {
        WorkflowNodeType workflowNodeType = getComponentOperation(workflowExecutionId);

        return triggerDefinitionService.getWebhookTriggerFlags(
            workflowNodeType.name(), workflowNodeType.version(), workflowNodeType.operation());
    }

    @Override
    public boolean hasStreamingTask(WorkflowExecutionId workflowExecutionId) {
        Workflow workflow = workflowService.getWorkflow(getWorkflowId(workflowExecutionId));

        // `getTasks(true)` flattens nested task-dispatcher branches (loop, branch, parallel) so a streaming AI task
        // buried inside a conditional still gets detected. Without flattening, a workflow whose top-level only has a
        // `loop` containing `openAi/streamAsk` would miss the heuristic.
        return workflow.getTasks(true)
            .stream()
            .map(com.bytechef.atlas.configuration.domain.WorkflowTask::getType)
            .filter(java.util.Objects::nonNull)
            .anyMatch(type -> type.toLowerCase()
                .contains("stream"));
    }

    @Override
    public boolean isWorkflowDisabled(WorkflowExecutionId workflowExecutionId) {
        JobPrincipalAccessor jobPrincipalAccessor =
            jobPrincipalAccessorRegistry.getJobPrincipalAccessor(workflowExecutionId.getType());

        return !jobPrincipalAccessor.isWorkflowEnabled(
            workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());
    }

    private WorkflowNodeType getComponentOperation(WorkflowExecutionId workflowExecutionId) {
        Workflow workflow = workflowService.getWorkflow(getWorkflowId(workflowExecutionId));

        WorkflowTrigger workflowTrigger = WorkflowTrigger.of(workflowExecutionId.getTriggerName(), workflow);

        return WorkflowNodeType.ofType(workflowTrigger.getType());
    }

    private String getWorkflowId(WorkflowExecutionId workflowExecutionId) {
        JobPrincipalAccessor jobPrincipalAccessor = jobPrincipalAccessorRegistry.getJobPrincipalAccessor(
            workflowExecutionId.getType());

        if (workflowExecutionId.getJobPrincipalId() == -1) {
            return jobPrincipalAccessor.getLastWorkflowId(workflowExecutionId.getWorkflowUuid());
        }

        return jobPrincipalAccessor.getWorkflowId(
            workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());
    }
}
