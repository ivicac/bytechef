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

package com.bytechef.platform.webhook.web.websocket;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.component.definition.TriggerDefinition.WebhookMethod;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.job.sync.SseStreamBridge;
import com.bytechef.platform.job.sync.executor.WebSocketEmitter;
import com.bytechef.platform.job.sync.executor.WebSocketEmitterRegistry;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket handler for webhook connections with workflow execution streaming support. Establishes connections at
 * /webhooks/{id} and streams workflow execution events in real-time using the JobSyncExecutor bridge pattern similar to
 * SSE streaming.
 *
 * <p>
 * When a WebSocket connection is established with a callSid, the handler reads the websocket subflow definition from
 * the workflow trigger's extensions and executes it via the JobFacade.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class WebhookWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookWebSocketHandler.class);
    private static final int MAX_PENDING_EVENTS = 100;
    private static final String SESSION_LIMIT_SECONDS = "sessionLimitSeconds";
    private static final String WEBSOCKET_TASKS = "websocketTasks";

    private final BrowserVoiceSessionTokenService browserVoiceSessionTokenService;
    private final CallSessionRegistry callSessionRegistry;
    private final JobFacade jobFacade;
    private final JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry;
    private final ObjectMapper objectMapper;
    private final VoiceMetricsRecorder voiceMetricsRecorder;
    private final WebhookWorkflowExecutor webhookWorkflowExecutor;
    private final WebSocketEmitterRegistry webSocketEmitterRegistry;
    private final WorkflowContinuationHelper workflowContinuationHelper;
    private final WorkflowService workflowService;

    private final long maxSessionDurationSeconds;

    private final Cache<String, AutoCloseable> streamHandles;
    private final Cache<String, List<Map<String, Object>>> pendingEvents;
    private final Cache<String, String> sessionIdToCallSid;
    private final Cache<String, String> firstTaskNameByCallSid;
    private final Cache<String, Long> sessionOpenedAtBySessionId;
    private final ScheduledExecutorService sessionTimeoutScheduler;
    private final Cache<String, ScheduledFuture<?>> sessionTimeoutsBySessionId;

    @Autowired
    @SuppressFBWarnings("EI")
    public WebhookWebSocketHandler(
        BrowserVoiceSessionTokenService browserVoiceSessionTokenService, CallSessionRegistry callSessionRegistry,
        JobFacade jobFacade,
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, ObjectMapper objectMapper,
        VoiceMetricsRecorder voiceMetricsRecorder,
        WebhookWorkflowExecutor webhookWorkflowExecutor,
        WebSocketEmitterRegistry webSocketEmitterRegistry,
        WorkflowContinuationHelper workflowContinuationHelper,
        WorkflowService workflowService) {

        this(browserVoiceSessionTokenService, callSessionRegistry, jobFacade, jobPrincipalAccessorRegistry,
            objectMapper,
            voiceMetricsRecorder, webhookWorkflowExecutor, webSocketEmitterRegistry, workflowContinuationHelper,
            workflowService, 30L * 60L);
    }

    @SuppressFBWarnings("EI")
    public WebhookWebSocketHandler(
        BrowserVoiceSessionTokenService browserVoiceSessionTokenService, CallSessionRegistry callSessionRegistry,
        JobFacade jobFacade,
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, ObjectMapper objectMapper,
        VoiceMetricsRecorder voiceMetricsRecorder,
        WebhookWorkflowExecutor webhookWorkflowExecutor,
        WebSocketEmitterRegistry webSocketEmitterRegistry,
        WorkflowContinuationHelper workflowContinuationHelper,
        WorkflowService workflowService,
        long maxSessionDurationSeconds) {

        this.browserVoiceSessionTokenService = browserVoiceSessionTokenService;
        this.callSessionRegistry = callSessionRegistry;
        this.jobFacade = jobFacade;
        this.jobPrincipalAccessorRegistry = jobPrincipalAccessorRegistry;
        this.objectMapper = objectMapper;
        this.voiceMetricsRecorder = voiceMetricsRecorder;
        this.webhookWorkflowExecutor = webhookWorkflowExecutor;
        this.webSocketEmitterRegistry = webSocketEmitterRegistry;
        this.workflowContinuationHelper = workflowContinuationHelper;
        this.workflowService = workflowService;
        this.maxSessionDurationSeconds = maxSessionDurationSeconds;
        this.sessionTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voice-session-timeout");

            thread.setDaemon(true);

            return thread;
        });

        this.streamHandles = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .removalListener((key, value, cause) -> {
                if (value != null) {
                    closeHandle((AutoCloseable) value);
                }
            })
            .build();

        this.pendingEvents = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

        this.sessionIdToCallSid = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

        this.firstTaskNameByCallSid = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

        this.sessionOpenedAtBySessionId = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();

        this.sessionTimeoutsBySessionId = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String webhookId = extractId(uri);
        String sessionKey = session.getId();
        String callSid = extractCallSid(uri);
        String sessionToken = extractQueryParam(uri, "sessionToken");

        log.info(
            "WebSocket connection established for webhook: {}, sessionId: {}, callSid: {}, hasToken: {}",
            webhookId, sessionKey, callSid, sessionToken != null);

        // Browser-voice path: no callSid, but a single-use sessionToken minted via
        // POST /webhooks/{webhookId}/voice-session-token. Validate the token, then synthesise a callSid
        // so the rest of the handler (registry, sub-workflow spawn, continuation-on-close) works unchanged.
        if (callSid == null && sessionToken != null) {
            if (webhookId == null || !browserVoiceSessionTokenService.consume(sessionToken, webhookId)) {
                log.warn("Browser-voice WS upgrade rejected: invalid token for webhook={}", webhookId);

                Map<String, Object> error = new LinkedHashMap<>();

                error.put("type", "error");
                error.put("message", "Invalid or expired session token");

                sendMessage(session, error);
                session.close(CloseStatus.POLICY_VIOLATION);

                return;
            }

            callSid = "browser-" + UUID.randomUUID();
        }

        if (callSid != null) {

            if (callSessionRegistry.hasSession(callSid)) {
                log.info("Reusing existing session for callSid: {}", callSid);

                Optional<CallSessionRegistry.CallSession> existingSessionOpt = callSessionRegistry.getSessionByCallSid(
                    callSid);

                if (existingSessionOpt.isPresent()) {
                    CallSessionRegistry.CallSession existingCallSession = existingSessionOpt.get();
                    Optional<WebSocketSession> existingWebSocketSessionOpt =
                        callSessionRegistry.getWebSocketSessionById(
                            existingCallSession.getWebSocketSessionId());

                    if (existingWebSocketSessionOpt.isPresent() && existingWebSocketSessionOpt.get()
                        .isOpen()) {

                        WebSocketSession existingWebSocketSession = existingWebSocketSessionOpt.get();

                        log.info(
                            "Existing session is still open, reusing: callSid={}, existingSessionId={}", callSid,
                            existingWebSocketSession.getId());

                        Map<String, Object> connected = new LinkedHashMap<>();

                        connected.put("event", "connected");
                        connected.put("id", webhookId);
                        connected.put("callSid", callSid);
                        connected.put("reused", true);

                        sendMessage(session, connected);

                        session.close(CloseStatus.NORMAL.withReason("Session already exists for this callSid"));

                        return;
                    } else {
                        log.info("Existing session is closed, creating new session: callSid={}", callSid);

                        callSessionRegistry.removeSessionByCallSid(callSid);
                    }
                }
            }

            CallSessionRegistry.CallMetadata metadata = new CallSessionRegistry.CallMetadata(
                null, null, null, null);

            callSessionRegistry.registerSession(callSid, session, metadata);
            sessionIdToCallSid.put(sessionKey, callSid);
            sessionOpenedAtBySessionId.put(sessionKey, System.nanoTime());
            voiceMetricsRecorder.recordSessionOpened();

            scheduleMaxDurationClose(session, sessionKey);

            if (webhookId != null) {
                startWebsocketSubflow(callSid, webhookId);
            }
        }

        Map<String, Object> connected = new LinkedHashMap<>();
        connected.put("event", "connected");
        connected.put("id", webhookId);

        if (callSid != null) {
            connected.put("callSid", callSid);
        }

        sendMessage(session, connected);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // Browser voice / Twilio media-stream path: forward the inbound audio frame to the FIRST task's emitter
        // in the sub-workflow's linear chain so the component (e.g. deepgram/v1/voiceAgent or a leading STT) sees it.
        String callSid = sessionIdToCallSid.getIfPresent(session.getId());

        if (callSid == null) {
            return;
        }

        Optional<CallSessionRegistry.CallSession> callSessionOpt = callSessionRegistry.getSessionByCallSid(callSid);

        if (callSessionOpt.isEmpty()) {
            return;
        }

        Long subJobId = callSessionOpt.get()
            .getSubJobId();
        String firstTaskName = firstTaskNameByCallSid.getIfPresent(callSid);

        if (subJobId == null || firstTaskName == null) {
            return;
        }

        Optional<WebSocketEmitter> emitterOpt = webSocketEmitterRegistry.get(subJobId, firstTaskName);

        if (emitterOpt.isEmpty()) {
            return;
        }

        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];

        payload.get(bytes);

        emitterOpt.get()
            .dispatchBinaryMessage(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionKey = session.getId();
        String payload = message.getPayload();

        if (log.isDebugEnabled()) {
            log.debug("Webhook WS inbound: sessionId={}, payload={}", sessionKey, payload);
        }

        try {
            Map<String, Object> request = objectMapper.readValue(payload, new TypeReference<>() {});

            String action = (String) request.get("action");

            if ("execute".equals(action)) {
                executeWorkflow(session, request);
            } else {
                Map<String, Object> ack = new LinkedHashMap<>();
                ack.put("event", "ack");
                ack.put("data", payload);

                sendMessage(session, ack);
            }
        } catch (Exception exception) {
            log.error("Error processing WebSocket message", exception);

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("event", "error");
            error.put("message", "Error processing message: " + exception.getMessage());

            sendMessage(session, error);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionKey = session.getId();

        log.info("WebSocket connection closed: sessionId={}, status={}", sessionKey, status);

        String callSid = sessionIdToCallSid.getIfPresent(sessionKey);

        if (callSid != null) {
            Optional<CallSessionRegistry.CallSession> callSessionOpt =
                callSessionRegistry.getSessionByCallSid(callSid);

            if (callSessionOpt.isPresent()) {
                CallSessionRegistry.CallSession callSession = callSessionOpt.get();

                if (callSession.isSignalCompletionOnClose()) {
                    log.info(
                        "Signaling workflow completion on WebSocket close: callSid={}", callSid);

                    Long subJobId = callSession.getSubJobId();

                    if (subJobId != null) {
                        try {
                            jobFacade.stopJob(subJobId);
                        } catch (Exception exception) {
                            log.warn(
                                "Failed to stop sub-workflow on WebSocket close: callSid={}, subJobId={}",
                                callSid, subJobId, exception);
                        }
                    }

                    callSession.signalCompletion();

                    String workflowExecutionId = callSession.getWorkflowExecutionId();

                    if (workflowExecutionId != null) {
                        Map<String, Object> afterCallData = new LinkedHashMap<>();

                        afterCallData.put("callSid", callSid);
                        afterCallData.put("callStatus", "websocket_closed");
                        afterCallData.put("closeStatusCode", status.getCode());
                        afterCallData.put("closeReason", status.getReason());

                        if (callSession.getCallDuration() != null) {
                            afterCallData.put("callDuration", callSession.getCallDuration());
                        }

                        workflowContinuationHelper.createContinuationJob(
                            workflowExecutionId, afterCallData);
                    }
                }
            }

            callSessionRegistry.removeSessionByCallSid(callSid);
            sessionIdToCallSid.invalidate(sessionKey);
        }

        AutoCloseable handle = streamHandles.getIfPresent(sessionKey);

        if (handle != null) {
            closeHandle(handle);
            streamHandles.invalidate(sessionKey);
        }

        pendingEvents.invalidate(sessionKey);

        // Cancel the max-duration scheduled close (this close handler may run before the timer fires, e.g.
        // when the user clicks End or the WS drops naturally).
        ScheduledFuture<?> pendingTimeout = sessionTimeoutsBySessionId.getIfPresent(sessionKey);

        if (pendingTimeout != null) {
            pendingTimeout.cancel(false);
            sessionTimeoutsBySessionId.invalidate(sessionKey);
        }

        Long openedAt = sessionOpenedAtBySessionId.getIfPresent(sessionKey);

        if (openedAt != null) {
            long durationNanos = System.nanoTime() - openedAt;

            voiceMetricsRecorder.recordSessionClosed(java.time.Duration.ofNanos(durationNanos));
            sessionOpenedAtBySessionId.invalidate(sessionKey);
        } else if (status != null && !CloseStatus.NORMAL.equalsCode(status)) {
            voiceMetricsRecorder.recordSessionError();
        }
    }

    private void scheduleMaxDurationClose(WebSocketSession session, String sessionKey) {
        scheduleMaxDurationClose(session, sessionKey, maxSessionDurationSeconds);
    }

    private void scheduleMaxDurationClose(WebSocketSession session, String sessionKey, long durationSeconds) {
        if (durationSeconds <= 0) {
            return;
        }

        ScheduledFuture<?> future = sessionTimeoutScheduler.schedule(() -> {
            if (!session.isOpen()) {
                return;
            }

            log.info(
                "Closing WebSocket due to max session duration ({}s) reached: sessionId={}",
                durationSeconds, sessionKey);

            try {
                session.close(CloseStatus.NORMAL.withReason("Maximum session duration reached"));
            } catch (IOException ioException) {
                log.warn(
                    "Failed to close WS at max duration: sessionId={}", sessionKey, ioException);
            }
        }, durationSeconds, TimeUnit.SECONDS);

        sessionTimeoutsBySessionId.put(sessionKey, future);
    }

    private void executeWorkflow(WebSocketSession session, Map<String, Object> request) throws Exception {
        String sessionKey = session.getId();
        URI uri = session.getUri();
        String webhookId = extractId(uri);

        Map<String, Object> start = new LinkedHashMap<>();
        start.put("event", "start");
        start.put("message", "Workflow execution started");
        start.put("webhookId", webhookId);

        sendMessage(session, start);

        try {
            WebhookRequest webhookRequest = buildWebhookRequest(request);

            WorkflowExecutionId workflowExecutionId = (WorkflowExecutionId) request.get("workflowExecutionId");

            if (workflowExecutionId == null) {
                throw new IllegalArgumentException("workflowExecutionId is required");
            }

            WebSocketStreamBridge streamBridge = new WebSocketStreamBridge(sessionKey);

            webhookWorkflowExecutor.executeAsync(workflowExecutionId, webhookRequest, streamBridge);
        } catch (Exception exception) {
            log.error("Error executing workflow", exception);

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("event", "error");
            error.put("message", exception.getMessage());

            sendMessage(session, error);
        }
    }

    /**
     * Reads the websocket subflow definition from the workflow trigger's extensions and executes it. The subflow
     * definition is stored as a string in the trigger's {@code websocketTasks} extension field within the workflow
     * definition JSON.
     */
    private void startWebsocketSubflow(String callSid, String webhookIdString) {
        Optional<CallSessionRegistry.CallSession> callSessionOpt = callSessionRegistry.getSessionByCallSid(callSid);

        if (callSessionOpt.isEmpty()) {
            log.warn("Cannot start websocket subflow: no session found for callSid={}", callSid);

            return;
        }

        CallSessionRegistry.CallSession callSession = callSessionOpt.get();

        callSession.setWorkflowExecutionId(webhookIdString);

        Thread.startVirtualThread(() -> {
            try {
                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(webhookIdString);

                String websocketSubflowDefinition = getWebsocketSubflowDefinition(workflowExecutionId);

                if (websocketSubflowDefinition == null || websocketSubflowDefinition.isBlank()) {
                    log.warn(
                        "No websocket subflow definition found in trigger for callSid={}", callSid);

                    return;
                }

                applyTriggerSessionLimit(callSid, workflowExecutionId);

                log.info("Starting websocket subflow: callSid={}", callSid);

                Workflow subflowWorkflow = workflowService.create(
                    websocketSubflowDefinition, Workflow.Format.JSON, Workflow.SourceType.JDBC);

                Map<String, Object> inputs = new LinkedHashMap<>();

                inputs.put("callSid", callSid);
                inputs.put("mainWorkflowExecutionId", webhookIdString);

                JobParametersDTO jobParameters = new JobParametersDTO(subflowWorkflow.getId(), inputs);

                long subJobId = jobFacade.createJob(jobParameters);

                callSession.setSubJobId(subJobId);

                // Build the linear task chain by array order. Inbound (WS audio) goes to task[0]; each task's
                // outbound feeds the next task's inbound; the last task's outbound goes back to the WS session.
                // No explicit subscribesTo/publishesTo DSL — task order in the sub-workflow IS the wiring.
                List<String> taskNames = subflowWorkflow.getTasks()
                    .stream()
                    .map(workflowTask -> workflowTask.getName())
                    .toList();

                firstTaskNameByCallSid.put(callSid, taskNames.isEmpty() ? "" : taskNames.get(0));

                Optional<WebSocketSession> webSocketSessionOpt = callSessionRegistry.getWebSocketSession(callSid);

                if (webSocketSessionOpt.isPresent()) {
                    WebSocketSession wsSession = webSocketSessionOpt.get();

                    wireTaskChain(subJobId, taskNames, wsSession);
                }

                log.info(
                    "Websocket subflow started: callSid={}, subJobId={}, tasks={}", callSid, subJobId, taskNames);
            } catch (Exception exception) {
                log.error("Failed to start websocket subflow: callSid={}", callSid, exception);
            }
        });
    }

    /**
     * Wires a linear chain of task emitters: each task's outbound feeds the next task's inbound dispatch; the last
     * task's outbound goes to the WS session. The first task's inbound is fed by the WS handler's
     * {@link #handleBinaryMessage}/{@link #handleTextMessage} forwarding to its registered emitter by name.
     *
     * <p>
     * The chain is built optimistically: we register {@code awaitEmitter} callbacks per task, each of which fires when
     * the worker post-output processor registers that task's emitter. Tasks complete asynchronously and may register in
     * any order — the {@link CompletableFuture}-backed registry handles both register-before-await and
     * await-before-register cases.
     */
    private void wireTaskChain(long subJobId, List<String> taskNames, WebSocketSession wsSession) {
        if (taskNames.isEmpty()) {
            return;
        }

        for (int index = 0; index < taskNames.size(); index++) {
            String name = taskNames.get(index);
            boolean isLast = index == taskNames.size() - 1;
            String nextName = isLast ? null : taskNames.get(index + 1);

            webSocketEmitterRegistry.awaitEmitter(subJobId, name)
                .thenAccept(emitter -> {
                    if (isLast) {
                        attachOutboundBridge(wsSession, emitter);
                    } else {
                        webSocketEmitterRegistry.awaitEmitter(subJobId, nextName)
                            .thenAccept(nextEmitter -> {
                                emitter.addOutboundListener(nextEmitter::dispatchMessage);
                                emitter.addOutboundBinaryListener(nextEmitter::dispatchBinaryMessage);
                                // Forward cancelTurn DOWNSTREAM: when this task cancels a turn, the next
                                // task hears about it and aborts whatever it was doing for that turn.
                                emitter.addOutboundTurnCancelListener(nextEmitter::cancelTurn);
                            });
                    }
                });
        }

        // Wire UPSTREAM cancelTurn propagation. When any task in the chain cancels a turn, propagate the
        // cancel to every other task so STT/agent/TTS all abort coherently. The simplest and correct policy
        // is "broadcast across the chain" — chain length is small (1-4) so the fan-out cost is negligible.
        for (int index = 0; index < taskNames.size(); index++) {
            String sourceName = taskNames.get(index);

            for (int otherIndex = 0; otherIndex < taskNames.size(); otherIndex++) {
                if (otherIndex == index) {
                    continue;
                }

                String targetName = taskNames.get(otherIndex);

                webSocketEmitterRegistry.awaitEmitter(subJobId, sourceName)
                    .thenAccept(sourceEmitter -> webSocketEmitterRegistry.awaitEmitter(subJobId, targetName)
                        .thenAccept(targetEmitter -> sourceEmitter.addOutboundTurnCancelListener(
                            turnId -> targetEmitter.cancelTurn(turnId))));
            }
        }
    }

    /**
     * Hooks the emitter's outbound channels to the live WS session. Outbound text/JSON from the workflow becomes
     * {@link TextMessage}s, outbound binary (TTS audio) becomes {@link BinaryMessage}s.
     */
    private void attachOutboundBridge(WebSocketSession wsSession, WebSocketEmitter emitter) {
        emitter.addOutboundListener(payload -> {
            if (!wsSession.isOpen()) {
                return;
            }

            try {
                if (payload instanceof String stringPayload) {
                    wsSession.sendMessage(new TextMessage(stringPayload));
                } else {
                    wsSession.sendMessage(new TextMessage(JsonUtils.write(payload)));
                }
            } catch (IOException ioException) {
                log.warn(
                    "Failed to forward outbound text to WS session: sessionId={}", wsSession.getId(), ioException);
            }
        });

        emitter.addOutboundBinaryListener(bytes -> {
            if (!wsSession.isOpen()) {
                return;
            }

            try {
                wsSession.sendMessage(new BinaryMessage(bytes));
            } catch (IOException ioException) {
                log.warn(
                    "Failed to forward outbound binary to WS session: sessionId={}", wsSession.getId(), ioException);
            }
        });
    }

    /**
     * If the trigger defines a {@code sessionLimitSeconds} parameter (value &gt; 0), cancel the default max-duration
     * timeout that was scheduled at connection time and reschedule with the trigger's value. When the trigger value is
     * 0 the default timeout (set at construction) continues to apply.
     */
    private void applyTriggerSessionLimit(String callSid, WorkflowExecutionId workflowExecutionId) {
        int triggerLimitSeconds = getSessionLimitSeconds(workflowExecutionId);

        if (triggerLimitSeconds <= 0) {
            return;
        }

        Optional<WebSocketSession> wsSessionOpt = callSessionRegistry.getWebSocketSession(callSid);

        if (wsSessionOpt.isEmpty()) {
            return;
        }

        WebSocketSession wsSession = wsSessionOpt.get();
        String sessionKey = wsSession.getId();

        ScheduledFuture<?> existingTimeout = sessionTimeoutsBySessionId.getIfPresent(sessionKey);

        if (existingTimeout != null) {
            existingTimeout.cancel(false);
            sessionTimeoutsBySessionId.invalidate(sessionKey);
        }

        scheduleMaxDurationClose(wsSession, sessionKey, triggerLimitSeconds);
    }

    /**
     * Reads the {@code sessionLimitSeconds} parameter from the trigger. Returns 0 when not set or set to 0 (meaning no
     * limit).
     */
    private int getSessionLimitSeconds(WorkflowExecutionId workflowExecutionId) {
        try {
            WorkflowTrigger workflowTrigger = resolveWorkflowTrigger(workflowExecutionId);

            return MapUtils.getInteger(workflowTrigger.getParameters(), SESSION_LIMIT_SECONDS, 0);
        } catch (Exception exception) {
            log.warn(
                "Failed to read sessionLimitSeconds from trigger for workflowExecutionId={}", workflowExecutionId,
                exception);

            return 0;
        }
    }

    /**
     * Resolves the websocket subflow definition string from the workflow trigger's extensions.
     */
    private String getWebsocketSubflowDefinition(WorkflowExecutionId workflowExecutionId) {
        WorkflowTrigger workflowTrigger = resolveWorkflowTrigger(workflowExecutionId);

        return workflowTrigger.getExtension(WEBSOCKET_TASKS, String.class, null);
    }

    private WorkflowTrigger resolveWorkflowTrigger(WorkflowExecutionId workflowExecutionId) {
        JobPrincipalAccessor jobPrincipalAccessor =
            jobPrincipalAccessorRegistry.getJobPrincipalAccessor(workflowExecutionId.getType());

        String workflowId = jobPrincipalAccessor.getWorkflowId(
            workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());

        Workflow workflow = workflowService.getWorkflow(workflowId);

        return WorkflowTrigger.of(workflowExecutionId.getTriggerName(), workflow);
    }

    private WebhookRequest buildWebhookRequest(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers = (Map<String, List<String>>) request.getOrDefault(
            "headers", new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, List<String>> parameters = (Map<String, List<String>>) request.getOrDefault(
            "parameters", new LinkedHashMap<>());

        Object bodyContent = request.getOrDefault("body", new LinkedHashMap<>());

        WebhookRequest.WebhookBodyImpl body = new WebhookRequest.WebhookBodyImpl(
            bodyContent,
            WebhookRequest.WebhookBodyImpl.ContentType.JSON,
            "application/json",
            bodyContent.toString());

        String methodName = (String) request.getOrDefault("method", "POST");

        WebhookMethod method;

        try {
            method = WebhookMethod.valueOf(methodName.toUpperCase());
        } catch (IllegalArgumentException illegalArgumentException) {
            method = WebhookMethod.POST;
        }

        return new WebhookRequest(headers, parameters, body, method);
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> data) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            String json = JsonUtils.write(data);

            session.sendMessage(new TextMessage(json));

            if (log.isDebugEnabled()) {
                log.debug("Sent WebSocket message: sessionId={}, event={}", session.getId(), data.get("event"));
            }
        } catch (IOException ioException) {
            log.error("Failed to send WebSocket message to session: {}", session.getId(), ioException);
        }
    }

    private static String extractId(URI uri) {
        if (uri == null) {
            return null;
        }

        String path = uri.getPath();

        if (path == null) {
            return null;
        }

        String[] segments = path.split("/");

        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];

            if (!segment.isEmpty() && !"webhooks".equals(segment)) {
                return segment;
            }
        }

        return null;
    }

    private static String extractCallSid(URI uri) {
        return extractQueryParam(uri, "callSid");
    }

    private static String extractQueryParam(URI uri, String paramName) {
        if (uri == null || paramName == null) {
            return null;
        }

        String query = uri.getQuery();

        if (query == null || query.isEmpty()) {
            return null;
        }

        String[] params = query.split("&");

        for (String param : params) {
            String[] keyValue = param.split("=");

            if (keyValue.length == 2 && paramName.equals(keyValue[0])) {
                return keyValue[1];
            }
        }

        return null;
    }

    private static void closeHandle(AutoCloseable handle) {
        try {
            handle.close();
        } catch (Exception exception) {
            if (log.isTraceEnabled()) {
                log.trace("Failed to close handle", exception);
            }
        }
    }

    private class WebSocketStreamBridge implements SseStreamBridge {

        private final String sessionKey;

        public WebSocketStreamBridge(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        @Override
        public void onEvent(Object payload) {
            String callSid = sessionIdToCallSid.getIfPresent(sessionKey);

            if (callSid != null) {
                Optional<WebSocketSession> sessionOpt = callSessionRegistry.getWebSocketSession(callSid);

                if (sessionOpt.isPresent() && sessionOpt.get()
                    .isOpen()) {

                    WebSocketSession session = sessionOpt.get();

                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("event", "stream");
                    event.put("data", payload);

                    sendMessage(session, event);
                } else {
                    bufferEvent(payload);
                }
            } else {
                bufferEvent(payload);
            }
        }

        @Override
        public void onComplete() {
            String callSid = sessionIdToCallSid.getIfPresent(sessionKey);

            if (callSid != null) {
                Optional<WebSocketSession> sessionOpt = callSessionRegistry.getWebSocketSession(callSid);

                if (sessionOpt.isPresent() && sessionOpt.get()
                    .isOpen()) {

                    WebSocketSession session = sessionOpt.get();

                    Map<String, Object> complete = new LinkedHashMap<>();
                    complete.put("event", "complete");
                    complete.put("message", "Workflow execution completed");

                    sendMessage(session, complete);

                    try {
                        session.close(CloseStatus.NORMAL);
                    } catch (IOException ioException) {
                        log.error("Error closing WebSocket session: {}", sessionKey, ioException);
                    }
                }

                callSessionRegistry.removeSessionByCallSid(callSid);
                sessionIdToCallSid.invalidate(sessionKey);
            }

            AutoCloseable handle = streamHandles.getIfPresent(sessionKey);

            if (handle != null) {
                closeHandle(handle);
                streamHandles.invalidate(sessionKey);
            }

            pendingEvents.invalidate(sessionKey);
        }

        @Override
        public void onError(Throwable throwable) {
            String callSid = sessionIdToCallSid.getIfPresent(sessionKey);

            WebSocketSession session = null;

            if (callSid != null) {
                Optional<WebSocketSession> sessionOpt = callSessionRegistry.getWebSocketSession(callSid);

                session = sessionOpt.orElse(null);
            }

            if (session != null && session.isOpen()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("event", "error");

                if (throwable instanceof CancellationException) {
                    error.put("message", "Workflow execution aborted");
                } else {
                    error.put("message", throwable.getMessage());
                }

                sendMessage(session, error);

                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ioException) {
                    log.error("Error closing WebSocket session: {}", sessionKey, ioException);
                }
            }

            if (callSid != null) {
                callSessionRegistry.removeSessionByCallSid(callSid);
                sessionIdToCallSid.invalidate(sessionKey);
            }

            AutoCloseable handle = streamHandles.getIfPresent(sessionKey);

            if (handle != null) {
                closeHandle(handle);
                streamHandles.invalidate(sessionKey);
            }

            pendingEvents.invalidate(sessionKey);
        }

        private void bufferEvent(Object payload) {
            List<Map<String, Object>> events = pendingEvents.get(sessionKey, key -> new ArrayList<>());

            if (events.size() < MAX_PENDING_EVENTS) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("event", "stream");
                event.put("data", payload);
                events.add(event);
            }
        }
    }
}
