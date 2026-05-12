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

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.TriggerDefinition.WebhookValidateResponse;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.ai.constant.AiAgentSseEventType;
import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import com.bytechef.platform.ai.stt.service.TranscribeService;
import com.bytechef.platform.component.domain.WebhookTriggerFlags;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.file.storage.TempFileStorage;
import com.bytechef.platform.job.sync.SseStreamBridge;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutionFacade;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.platform.webhook.rest.AbstractWebhookTriggerController;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMapAdapter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Webhook trigger controller that handles incoming webhook requests from external services.
 *
 * @author Ivica Cardic
 */
@RestController
@CrossOrigin
@ConditionalOnCoordinator
public class WebhookTriggerController extends AbstractWebhookTriggerController {

    private static final Logger log = LoggerFactory.getLogger(WebhookTriggerController.class);

    private final TranscribeService transcribeService;
    private final WebhookWorkflowExecutionFacade webhookFacade;
    private final WebhookWorkflowExecutor webhookWorkflowExecutor;

    @SuppressFBWarnings("EI")
    public WebhookTriggerController(
        ApplicationProperties applicationProperties, JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry,
        TempFileStorage tempFileStorage, TranscribeService transcribeService,
        TriggerDefinitionService triggerDefinitionService,
        WebhookWorkflowExecutionFacade webhookFacade, WebhookWorkflowExecutor webhookWorkflowExecutor,
        WorkflowService workflowService) {

        super(
            jobPrincipalAccessorRegistry, applicationProperties.getPublicUrl(), tempFileStorage,
            triggerDefinitionService, webhookWorkflowExecutor, workflowService);

        this.transcribeService = transcribeService;
        this.webhookFacade = webhookFacade;
        this.webhookWorkflowExecutor = webhookWorkflowExecutor;
    }

    /**
     * Executes a workflow based on the provided webhook trigger. Supports HEAD, GET, and POST HTTP methods for
     * triggering different behaviors within the workflow.
     *
     * <p>
     * <b>Security Note:</b> CSRF protection is intentionally disabled for this endpoint. Webhook callbacks from
     * external services cannot include CSRF tokens. Security is maintained through webhook secret validation and
     * signature verification as implemented by individual webhook trigger components.
     *
     * @param id                  the unique identifier of the workflow execution, extracted from the path variable.
     * @param httpServletRequest  the HTTP request object containing client request details and metadata.
     * @param httpServletResponse the HTTP response object to send responses back to the client.
     * @return a {@link ResponseEntity} object representing the outcome of the workflow execution.
     */
    @SuppressFBWarnings("SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING")
    @RequestMapping(
        method = {
            RequestMethod.HEAD, RequestMethod.GET, RequestMethod.POST
        },
        value = "/webhooks/{id}")
    public ResponseEntity<?> executeWorkflow(
        @PathVariable String id, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(id);

        return TenantContext.callWithTenantId(workflowExecutionId.getTenantId(), () -> {
            ResponseEntity<?> responseEntity;

            boolean head = Objects.equals(httpServletRequest.getMethod(), RequestMethod.HEAD.name());
            // Route through the facade so the controller and AG-UI bridge share one implementation of
            // isWorkflowDisabled (rather than each calling AbstractWebhookTriggerController's inherited helper
            // independently). Same call path internally, but the indirection means a future change to the
            // disabled-check semantics — e.g. introducing a "soft disabled" status — only needs touching the
            // facade and both consumers pick it up. doProcessTrigger stays inline because it returns
            // ResponseEntity<Object> with rich HTTP-shaped responses (validateOnEnable mapping, error shapes)
            // that the facade's typed WebhookExecutionResult envelope deliberately excludes.
            boolean disabled = webhookFacade.isWorkflowDisabled(workflowExecutionId);

            if (head || disabled) {
                WebhookTriggerFlags webhookTriggerFlags = webhookFacade.getWebhookTriggerFlags(workflowExecutionId);

                WebhookRequest webhookRequest = getWebhookRequest(httpServletRequest, webhookTriggerFlags);

                if (webhookTriggerFlags.workflowSyncOnEnableValidation()) {
                    responseEntity = doValidateOnEnable(workflowExecutionId, webhookRequest);
                } else if (disabled && webhookTriggerFlags.workflowSyncExecution()) {
                    responseEntity = ResponseEntity.status(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("detail", "Workflow is disabled."));
                } else {
                    responseEntity = ResponseEntity.ok()
                        .build();
                }
            } else {
                responseEntity = doProcessTrigger(workflowExecutionId, null, httpServletRequest, httpServletResponse);
            }

            return responseEntity;
        });
    }

    /**
     * Handles Server-Sent Events (SSE) streaming for workflow execution based on the webhook trigger. This method
     * configures a REST endpoint to stream events in real time to the client.
     *
     * <p>
     * <b>Security Note:</b> CSRF protection is intentionally disabled for this endpoint. Webhook callbacks from
     * external services cannot include CSRF tokens. Security is maintained through webhook secret validation and
     * signature verification as implemented by individual webhook trigger components.
     *
     * @param id                 the unique identifier of the workflow execution, extracted from the path variable.
     * @param httpServletRequest the HTTP request object containing client request details and metadata.
     * @return an {@link SseEmitter} object that streams events to the client.
     */
    @SuppressFBWarnings("SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING")
    @RequestMapping(
        method = {
            RequestMethod.GET, RequestMethod.POST
        }, value = "/webhooks/{id}/sse",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sseStreamWorkflow(@PathVariable String id, HttpServletRequest httpServletRequest)
        throws Exception {

        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(id);

        return TenantContext.callWithTenantId(workflowExecutionId.getTenantId(), () -> {
            WebhookSseStreamBridge bridge = new WebhookSseStreamBridge(emitter);

            // Delegate to the facade so the disabled-check + executor dispatch + whenComplete chain stay in
            // exactly one place. The non-streaming HEAD/GET/POST endpoint above still calls into
            // AbstractWebhookTriggerController for its (more involved) sync orchestration — refactoring that
            // path through the facade is a follow-up; the streaming method is the cleanest no-behaviour-change
            // win because it only consults the disabled-check, builds a WebhookRequest, and dispatches.
            //
            // We still need the trigger flags here (not used by the facade for the streaming path) to drive
            // getWebhookRequest, which inspects flags.webhookRawBody to decide whether to capture the raw
            // request body.
            WebhookTriggerFlags webhookTriggerFlags = getWebhookTriggerFlags(workflowExecutionId);
            WebhookRequest webhookRequest = getWebhookRequest(httpServletRequest, webhookTriggerFlags);

            webhookFacade.executeStreaming(workflowExecutionId, webhookRequest, bridge);

            return emitter;
        });
    }

    /**
     * Transcribes audio uploaded via multipart/form-data. The webhookId is the same base64-encoded
     * {@link WorkflowExecutionId} used by the other routes on this controller.
     *
     * @param webhookId the base64-encoded workflow execution identifier.
     * @param audio     the audio part (max 25 MB).
     * @param locale    optional BCP-47 locale hint (e.g. {@code "en"}).
     * @return a {@link TranscribeResponse} with the transcript text, audio duration, and detected locale.
     */
    @SuppressFBWarnings("SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING")
    @PostMapping("/webhooks/{webhookId}/transcribe")
    public ResponseEntity<TranscribeResponse> transcribe(
        @PathVariable String webhookId,
        @RequestPart("audio") MultipartFile audio,
        @RequestParam(name = "locale", required = false) String locale) throws IOException {

        validateWebhookExists(webhookId);

        if (audio.getSize() > 25 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .build();
        }

        TranscriptResult result = transcribeService.transcribe(
            audio.getInputStream(),
            audio.getContentType() == null ? "audio/webm" : audio.getContentType(),
            locale,
            Map.of());

        return ResponseEntity.ok(new TranscribeResponse(result.text(), result.durationMs(), result.detectedLocale()));
    }

    public record TranscribeResponse(String text, long durationMs, String locale) {
    }

    private void validateWebhookExists(String webhookId) {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(webhookId);

        TenantContext.runWithTenantId(workflowExecutionId.getTenantId(), () -> {
            if (isWorkflowDisabled(workflowExecutionId)) {
                throw new IllegalStateException("Workflow is disabled.");
            }
        });
    }

    private ResponseEntity<?> doValidateOnEnable(
        WorkflowExecutionId workflowExecutionId, WebhookRequest webhookRequest) {

        WebhookValidateResponse response = webhookWorkflowExecutor.validateOnEnable(
            workflowExecutionId, webhookRequest);

        return ResponseEntity.status(response.status())
            .headers(
                response.headers() == null
                    ? null
                    : HttpHeaders.readOnlyHttpHeaders(new MultiValueMapAdapter<>(response.headers())))
            .body(response.body());
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(
                SseEmitter.event()
                    .name(name)
                    .data(data instanceof String ? JsonUtils.write(data) : data));
        } catch (Exception exception) {
            if (log.isTraceEnabled()) {
                log.trace(exception.getMessage(), exception);
            }
        }
    }

    /**
     * Bridge that broadcasts streamed payloads to SSE clients for webhook workflow execution. Events arrive on
     * message-broker threads concurrently with job-status events and client-initiated disconnects, so callbacks must be
     * idempotent and skip sends once the emitter is closed.
     */
    private static class WebhookSseStreamBridge implements SseStreamBridge {

        private final AtomicBoolean completed = new AtomicBoolean();
        private final SseEmitter emitter;

        private WebhookSseStreamBridge(SseEmitter emitter) {
            this.emitter = emitter;

            emitter.onCompletion(() -> completed.set(true));
            emitter.onTimeout(() -> completed.set(true));
            emitter.onError(throwable -> completed.set(true));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onEvent(Object payload) {
            if (completed.get()) {
                return;
            }

            if (payload instanceof Map<?, ?> map) {
                if (map.containsKey(AiAgentSseEventType.EVENT_TYPE)) {
                    String eventType = (String) map.get(AiAgentSseEventType.EVENT_TYPE);

                    Map<String, Object> eventData = new LinkedHashMap<>((Map<String, Object>) map);

                    eventData.remove(AiAgentSseEventType.EVENT_TYPE);

                    sendEvent(emitter, eventType, eventData);

                    return;
                }

                if (map.containsKey("event")) {
                    String event = (String) map.get("event");
                    Object data = map.entrySet()
                        .stream()
                        .filter(entry -> !"event".equals(entry.getKey()))
                        .findFirst()
                        .map(Map.Entry::getValue)
                        .orElse(null);

                    sendEvent(emitter, event, data);

                    return;
                }
            }

            sendEvent(emitter, "stream", payload);
        }

        @Override
        public void onComplete() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }

            try {
                emitter.complete();
            } catch (Exception exception) {
                if (log.isTraceEnabled()) {
                    log.trace(exception.getMessage(), exception);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }

            try {
                sendEvent(emitter, "error", Objects.toString(throwable.getMessage(), "An error occurred"));
            } finally {
                try {
                    emitter.complete();
                } catch (Exception exception) {
                    if (log.isTraceEnabled()) {
                        log.trace(exception.getMessage(), exception);
                    }
                }
            }
        }
    }
}
