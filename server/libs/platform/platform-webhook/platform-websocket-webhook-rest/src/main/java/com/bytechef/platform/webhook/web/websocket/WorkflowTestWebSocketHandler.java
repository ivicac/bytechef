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
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.job.sync.executor.WebSocketEmitter;
import com.bytechef.platform.job.sync.executor.WebSocketEmitterRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * WebSocket handler for in-editor workflow test voice sessions. Accepts upgrades at
 * {@code /internal/workflow-tests/{workflowId}/wss?sessionToken=...&sampleRate=...} and runs the workflow's embedded
 * {@code websocketTasks} sub-workflow against the live audio stream.
 *
 * <p>
 * Differs from {@link WebhookWebSocketHandler}: keyed by workflowId (not webhookId), reads the draft workflow directly
 * via {@link WorkflowService}, and does not perform the workflow-continuation-on-close dance (test sessions have no
 * outer job to resume). Reuses {@link CallSessionRegistry} for the WS-session-to-sub-workflow binding so the
 * worker-side {@code WebSocketEmitter} bridge can find the session by its synthetic test-prefix callSid.
 *
 * @author Ivica Cardic
 */
@Component
public class WorkflowTestWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTestWebSocketHandler.class);
    private static final String TEST_CALL_SID_PREFIX = "test-";
    private static final String WEBSOCKET_TASKS = "websocketTasks";

    private final CallSessionRegistry callSessionRegistry;
    private final JobFacade jobFacade;
    private final WorkflowService workflowService;
    private final WorkflowTestVoiceSessionTokenService tokenService;
    private final WebSocketEmitterRegistry webSocketEmitterRegistry;
    private final Cache<String, String> sessionIdToCallSid;
    private final Cache<String, String> firstTaskNameByCallSid;

    @SuppressFBWarnings("EI")
    public WorkflowTestWebSocketHandler(
        CallSessionRegistry callSessionRegistry, JobFacade jobFacade,
        WebSocketEmitterRegistry webSocketEmitterRegistry,
        WorkflowService workflowService, WorkflowTestVoiceSessionTokenService tokenService) {

        this.callSessionRegistry = callSessionRegistry;
        this.jobFacade = jobFacade;
        this.webSocketEmitterRegistry = webSocketEmitterRegistry;
        this.workflowService = workflowService;
        this.tokenService = tokenService;
        this.sessionIdToCallSid = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();
        this.firstTaskNameByCallSid = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String workflowId = extractWorkflowId(uri);
        String sessionToken = extractQueryParam(uri, "sessionToken");

        if (workflowId == null || sessionToken == null) {
            log.warn("Test WS upgrade missing workflowId or sessionToken: uri={}", uri);

            sendError(session, "Missing workflowId or sessionToken");
            session.close(CloseStatus.BAD_DATA);

            return;
        }

        if (!tokenService.consume(sessionToken, workflowId)) {
            log.warn("Test WS upgrade rejected: invalid or expired token for workflowId={}", workflowId);

            sendError(session, "Invalid or expired session token");
            session.close(CloseStatus.POLICY_VIOLATION);

            return;
        }

        String callSid = TEST_CALL_SID_PREFIX + UUID.randomUUID();

        CallSessionRegistry.CallMetadata metadata = new CallSessionRegistry.CallMetadata(null, null, null, null);

        callSessionRegistry.registerSession(callSid, session, metadata);
        sessionIdToCallSid.put(session.getId(), callSid);

        Map<String, Object> connected = new LinkedHashMap<>();

        connected.put("event", "connected");
        connected.put("workflowId", workflowId);
        connected.put("callSid", callSid);

        sendJson(session, connected);

        startSubflow(callSid, workflowId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        forwardInboundToEmitter(session, emitter -> emitter.dispatchMessage(message.getPayload()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];

        payload.get(bytes);

        forwardInboundToEmitter(session, emitter -> emitter.dispatchBinaryMessage(bytes));
    }

    private void
        forwardInboundToEmitter(WebSocketSession session, java.util.function.Consumer<WebSocketEmitter> action) {
        String callSid = sessionIdToCallSid.getIfPresent(session.getId());

        if (callSid == null) {
            return;
        }

        String firstTaskName = firstTaskNameByCallSid.getIfPresent(callSid);

        if (firstTaskName == null) {
            return;
        }

        Optional<CallSessionRegistry.CallSession> callSessionOpt = callSessionRegistry.getSessionByCallSid(callSid);

        if (callSessionOpt.isEmpty()) {
            return;
        }

        Long subJobId = callSessionOpt.get()
            .getSubJobId();

        if (subJobId == null) {
            return;
        }

        webSocketEmitterRegistry.get(subJobId, firstTaskName)
            .ifPresent(action);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Test WS closed: sessionId={}, status={}", session.getId(), status);

        String callSid = sessionIdToCallSid.getIfPresent(session.getId());

        if (callSid != null) {
            Optional<CallSessionRegistry.CallSession> callSessionOpt = callSessionRegistry.getSessionByCallSid(callSid);

            if (callSessionOpt.isPresent()) {
                Long subJobId = callSessionOpt.get()
                    .getSubJobId();

                if (subJobId != null) {
                    try {
                        jobFacade.stopJob(subJobId);
                    } catch (Exception exception) {
                        log.warn(
                            "Failed to stop test sub-workflow on WS close: callSid={}, subJobId={}",
                            callSid, subJobId, exception);
                    }
                }
            }

            callSessionRegistry.removeSessionByCallSid(callSid);
            sessionIdToCallSid.invalidate(session.getId());
        }
    }

    private void startSubflow(String callSid, String workflowId, WebSocketSession session) {
        Thread.startVirtualThread(() -> {
            try {
                Workflow workflow = workflowService.getWorkflow(workflowId);

                String websocketSubflowDefinition = resolveWebsocketTasks(workflow);

                if (websocketSubflowDefinition == null || websocketSubflowDefinition.isBlank()) {
                    log.warn(
                        "Test WS: no websocketTasks extension found on any trigger for workflowId={}", workflowId);

                    sendError(session, "Workflow has no websocketTasks defined on its trigger");
                    session.close(CloseStatus.BAD_DATA);

                    return;
                }

                Workflow subflowWorkflow = workflowService.create(
                    websocketSubflowDefinition, Workflow.Format.JSON, Workflow.SourceType.JDBC);

                Map<String, Object> inputs = new LinkedHashMap<>();

                inputs.put("callSid", callSid);
                inputs.put("testMode", true);

                JobParametersDTO jobParameters = new JobParametersDTO(subflowWorkflow.getId(), inputs);

                long subJobId = jobFacade.createJob(jobParameters);

                callSessionRegistry.getSessionByCallSid(callSid)
                    .ifPresent(callSession -> callSession.setSubJobId(subJobId));

                // Wire the linear chain. Array order IS the inter-task wiring: WS audio → task[0] → task[1] →
                // ... → task[N-1] → back to WS. No explicit subscribesTo/publishesTo DSL.
                List<String> taskNames = subflowWorkflow.getTasks()
                    .stream()
                    .map(workflowTask -> workflowTask.getName())
                    .toList();

                firstTaskNameByCallSid.put(callSid, taskNames.isEmpty() ? "" : taskNames.get(0));

                wireTaskChain(subJobId, taskNames, session);

                log.info(
                    "Test WS sub-workflow started: workflowId={}, callSid={}, subJobId={}, tasks={}",
                    workflowId, callSid, subJobId, taskNames);
            } catch (Exception exception) {
                log.error("Failed to start test sub-workflow: workflowId={}", workflowId, exception);

                sendError(session, "Failed to start sub-workflow: " + exception.getMessage());
            }
        });
    }

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
                                emitter.addOutboundTurnCancelListener(nextEmitter::cancelTurn);
                            });
                    }
                });
        }

        // Broadcast cancelTurn across the chain — see WebhookWebSocketHandler.wireTaskChain for rationale.
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
                    "Failed to forward outbound text to test WS session: sessionId={}", wsSession.getId(),
                    ioException);
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
                    "Failed to forward outbound binary to test WS session: sessionId={}", wsSession.getId(),
                    ioException);
            }
        });
    }

    private String resolveWebsocketTasks(Workflow workflow) {
        List<WorkflowTrigger> triggers = WorkflowTrigger.of(workflow);

        for (WorkflowTrigger trigger : triggers) {
            String websocketTasks = trigger.getExtension(WEBSOCKET_TASKS, String.class, null);

            if (websocketTasks != null && !websocketTasks.isBlank()) {
                return websocketTasks;
            }
        }

        return null;
    }

    private static String extractWorkflowId(URI uri) {
        if (uri == null) {
            return null;
        }

        String path = uri.getPath();

        // .../workflow-tests/{workflowId}/wss
        int wssIndex = path.lastIndexOf("/wss");

        if (wssIndex < 0) {
            return null;
        }

        String head = path.substring(0, wssIndex);
        int slashIndex = head.lastIndexOf('/');

        if (slashIndex < 0 || slashIndex == head.length() - 1) {
            return null;
        }

        return head.substring(slashIndex + 1);
    }

    private static String extractQueryParam(URI uri, String name) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }

        String prefix = name + "=";

        for (String pair : uri.getQuery()
            .split("&")) {

            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length());
            }
        }

        return null;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            session.sendMessage(new TextMessage(JsonUtils.write(data)));
        } catch (IOException ioException) {
            log.warn("Failed to send test WS message: sessionId={}", session.getId(), ioException);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        Map<String, Object> error = new LinkedHashMap<>();

        error.put("type", "error");
        error.put("message", message);

        sendJson(session, error);
    }
}
