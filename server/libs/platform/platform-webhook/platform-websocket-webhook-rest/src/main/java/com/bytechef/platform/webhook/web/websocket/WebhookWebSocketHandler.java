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

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.webhook.voice.VoiceSessionConnectionResolver;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine.VoiceSession;
import com.bytechef.platform.webhook.voice.WebSocketEmitter;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/**
 * WebSocket handler for webhook connections, and the entry point for browser voice sessions. Established at
 * {@code /webhooks/{id}/wss}.
 *
 * <p>
 * An upgrade carrying a {@code sessionToken} is a voice session: the handler resolves the deployed trigger, starts its
 * Voice Agent cluster element on {@link VoiceSessionEngine}, and tracks the session in {@link VoiceSessionRegistry}. No
 * Atlas job runs while the caller is connected; Atlas re-enters only once the session ends, through the continuation
 * job {@link WorkflowContinuationHelper} creates with the session's transcript.
 *
 * <p>
 * A session outlives a single socket. A close the server did not initiate detaches the session for
 * {@link VoiceSessionRegistry#RESUME_WINDOW}, and an upgrade carrying {@code resumeSessionId} within that window picks
 * the same conversation back up. A server-initiated close ({@code session_limit}, {@code silence_timeout},
 * {@code provider_error}, {@code provider_closed}, {@code server_shutdown}) or an elapsed resume window ends the
 * session. Server-initiated means the handler recorded the close before sending it; the reason in a close frame is
 * never trusted on its own.
 *
 * <p>
 * An upgrade without a session token is refused: browser voice sessions are the endpoint's only use. (A
 * webhook-over-WebSocket {@code execute} action once lived here; no client sent it, and it cast the parsed JSON's
 * {@code workflowExecutionId} string straight to {@link WorkflowExecutionId}, so it could only ever fail.)
 *
 * @author Ivica Cardic
 */
@Component
public class WebhookWebSocketHandler extends AbstractWebSocketHandler implements SmartLifecycle {

    static final String CLIENT_CLOSED = "client_closed";
    static final String PROVIDER_CLOSED = "provider_closed";
    static final String PROVIDER_ERROR = "provider_error";
    static final String SERVER_SHUTDOWN = "server_shutdown";
    static final String SESSION_LIMIT = "session_limit";
    static final String SILENCE_TIMEOUT = "silence_timeout";

    /** Close reason for a session refused because its trigger has no Voice Agent element. */
    static final String VOICE_AGENT_MISSING = "voice_agent_missing";

    /** Close reason for a session refused because its workflow is disabled. */
    static final String WORKFLOW_DISABLED = "workflow_disabled";

    private static final Logger log = LoggerFactory.getLogger(WebhookWebSocketHandler.class);

    /** True on the session timer thread, so work that must not stall every session's timers can move off it. */
    private static final ThreadLocal<Boolean> ON_TIMER_THREAD = ThreadLocal.withInitial(() -> false);

    /**
     * The reason a browser closes with, under code 1000, when the caller hangs up on purpose. Kept apart from the
     * reasons the server records when it closes a socket itself: those name decisions the server made, while this one
     * is the client's own word, and it only ever ends the session the client is already leaving.
     */
    private static final String CLIENT_END_REASON = CLIENT_CLOSED;

    private static final int DEFAULT_SILENCE_TIMEOUT_SECONDS = 120;

    /** How long {@link #stop()} waits for session starts and handed-off session ends still running. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private static final String SESSION_LIMIT_SECONDS = "sessionLimitSeconds";
    private static final long SILENCE_CHECK_INTERVAL_SECONDS = 5;
    private static final String SILENCE_TIMEOUT_SECONDS = "silenceTimeoutSeconds";

    private final BrowserVoiceSessionTokenService browserVoiceSessionTokenService;
    private final TriggerResolver triggerResolver;
    private final VoiceMetricsRecorder voiceMetricsRecorder;
    private final VoiceSessionConnectionResolver voiceSessionConnectionResolver;
    private final VoiceSessionEngine voiceSessionEngine;
    private final VoiceSessionRegistry voiceSessionRegistry;
    private final WorkflowContinuationHelper workflowContinuationHelper;

    private final long maxSessionDurationSeconds;

    private final Cache<String, LiveSession> liveSessionsBySessionId;

    /**
     * The reason the server gave for each socket it closed, taken back when that socket's close arrives. A close
     * frame's reason is whatever the client sent, so only this map may end a session with a server reason.
     */
    private final Map<String, String> serverCloseReasonsByWebSocketSessionId = new ConcurrentHashMap<>();
    private final Cache<String, String> sessionIdByWebSocketSessionId;
    private final ScheduledExecutorService sessionTimeoutScheduler;
    private final Cache<String, List<ScheduledFuture<?>>> sessionTimersBySessionId;

    /**
     * Session starts and handed-off session ends still running on their virtual threads. Virtual threads are daemon
     * threads, so {@link #stop()} joins these rather than let the JVM exit under a continuation job.
     */
    private final Set<Thread> handOffThreads = ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    /**
     * Set by {@link #stop()} before it walks the live sessions, and cleared by {@link #start()}. Kept apart from
     * {@link #running}, which is false until the container calls {@link #start()}: this flag means shutdown has begun,
     * not that the handler has yet to start.
     *
     * <p>
     * Clearing it on a restart is safe: a start still running from before {@code stop()} — one its bounded wait gave up
     * on — then simply joins the live sessions of a node that is serving again, instead of ending itself.
     */
    private volatile boolean shuttingDown;

    @Autowired
    @SuppressFBWarnings("EI")
    public WebhookWebSocketHandler(
        BrowserVoiceSessionTokenService browserVoiceSessionTokenService, TriggerResolver triggerResolver,
        VoiceMetricsRecorder voiceMetricsRecorder, VoiceSessionConnectionResolver voiceSessionConnectionResolver,
        VoiceSessionEngine voiceSessionEngine, VoiceSessionRegistry voiceSessionRegistry,
        WorkflowContinuationHelper workflowContinuationHelper) {

        this(
            browserVoiceSessionTokenService, triggerResolver, voiceMetricsRecorder, voiceSessionConnectionResolver,
            voiceSessionEngine, voiceSessionRegistry, workflowContinuationHelper, 30L * 60L);
    }

    @SuppressFBWarnings("EI")
    public WebhookWebSocketHandler(
        BrowserVoiceSessionTokenService browserVoiceSessionTokenService, TriggerResolver triggerResolver,
        VoiceMetricsRecorder voiceMetricsRecorder, VoiceSessionConnectionResolver voiceSessionConnectionResolver,
        VoiceSessionEngine voiceSessionEngine, VoiceSessionRegistry voiceSessionRegistry,
        WorkflowContinuationHelper workflowContinuationHelper, long maxSessionDurationSeconds) {

        this.browserVoiceSessionTokenService = browserVoiceSessionTokenService;
        this.triggerResolver = triggerResolver;
        this.voiceMetricsRecorder = voiceMetricsRecorder;
        this.voiceSessionConnectionResolver = voiceSessionConnectionResolver;
        this.voiceSessionEngine = voiceSessionEngine;
        this.voiceSessionRegistry = voiceSessionRegistry;
        this.workflowContinuationHelper = workflowContinuationHelper;
        this.maxSessionDurationSeconds = maxSessionDurationSeconds;
        this.sessionTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                () -> {
                    ON_TIMER_THREAD.set(true);

                    runnable.run();
                },
                "voice-session-timeout");

            thread.setDaemon(true);

            return thread;
        });

        this.sessionIdByWebSocketSessionId = Caffeine.newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

        this.liveSessionsBySessionId = Caffeine.newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

        this.sessionTimersBySessionId = Caffeine.newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();
    }

    @Override
    public void start() {
        // A bean the container starts again after stop() serves upgrades again rather than refusing them forever.
        shuttingDown = false;
        running = true;
    }

    /**
     * Ends every live session on this node as {@code server_shutdown} before the application goes down, so each one's
     * post-call workflow still runs: the browser learns why, the provider connection closes, and the continuation job
     * is created — synchronously, on the stopping thread, while the database and broker are still up. The default phase
     * stops this before the web server's graceful shutdown.
     */
    @Override
    public void stop() {
        running = false;

        // Set before the live sessions are read: a start that joins them afterwards finds the flag and ends itself.
        shuttingDown = true;

        for (String sessionId : List.copyOf(liveSessionsBySessionId.asMap()
            .keySet())) {

            try {
                finalizeSession(sessionId, SERVER_SHUTDOWN, true);
            } catch (RuntimeException runtimeException) {
                log.warn("Failed to end voice session on shutdown: sessionId={}", sessionId, runtimeException);
            }
        }

        awaitHandOffs();
    }

    /**
     * Waits, up to {@link #SHUTDOWN_WAIT}, for session starts and handed-off session ends: a start that loses the race
     * to shutdown ends itself as {@code server_shutdown}, and an end handed off the timer thread creates its
     * continuation job, only if the JVM is still up.
     */
    private void awaitHandOffs() {
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();

        while (!handOffThreads.isEmpty()) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());

            if (remainingMillis <= 0) {
                log.warn(
                    "Stopped waiting for voice sessions after {}s: {} session start(s) or end(s) still running",
                    SHUTDOWN_WAIT.toSeconds(), handOffThreads.size());

                return;
            }

            for (Thread handOffThread : List.copyOf(handOffThreads)) {
                try {
                    handOffThread.join(Math.max(1L, remainingMillis));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread()
                        .interrupt();

                    return;
                }
            }
        }
    }

    /** Runs {@code task} on a virtual thread {@link #stop()} waits for. */
    private void startHandOff(Runnable task) {
        Thread handOffThread = Thread.ofVirtual()
            .unstarted(() -> {
                try {
                    task.run();
                } finally {
                    handOffThreads.remove(Thread.currentThread());
                }
            });

        handOffThreads.add(handOffThread);

        handOffThread.start();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String webhookId = extractId(uri);
        String sessionToken = extractQueryParam(uri, "sessionToken");

        log.info(
            "WebSocket connection established for webhook: {}, webSocketSessionId: {}, hasToken: {}", webhookId,
            session.getId(), sessionToken != null);

        if (shuttingDown) {
            // Refused before the token is spent, so an embedding app that mints a new token can start a fresh session
            // elsewhere. The browser client itself does not retry: server_shutdown is one of its terminal reasons.
            log.info("Voice WS upgrade refused, the server is shutting down: webSocketSessionId={}", session.getId());

            session.close(CloseStatus.SERVICE_RESTARTED.withReason(SERVER_SHUTDOWN));

            return;
        }

        if (sessionToken == null) {
            log.warn("Webhook WS upgrade rejected: no session token for webhook={}", webhookId);

            Map<String, Object> error = new LinkedHashMap<>();

            error.put("type", "error");
            error.put("message", "A voice session token is required");

            sendMessage(session, error);
            session.close(CloseStatus.POLICY_VIOLATION);

            return;
        }

        if (webhookId == null || !browserVoiceSessionTokenService.consume(sessionToken, webhookId)) {
            log.warn("Browser-voice WS upgrade rejected: invalid token for webhook={}", webhookId);

            Map<String, Object> error = new LinkedHashMap<>();

            error.put("type", "error");
            error.put("message", "Invalid or expired session token");

            sendMessage(session, error);
            session.close(CloseStatus.POLICY_VIOLATION);

            return;
        }

        String resumeSessionId = extractQueryParam(uri, "resumeSessionId");

        if (resumeSessionId != null && resumeSession(session, resumeSessionId, webhookId)) {
            return;
        }

        String sessionId = UUID.randomUUID()
            .toString();

        VoiceSessionRegistry.Session voiceSession = voiceSessionRegistry.register(sessionId, session, webhookId);

        sessionIdByWebSocketSessionId.put(session.getId(), sessionId);
        voiceMetricsRecorder.recordSessionOpened();

        startHandOff(() -> startSession(session, voiceSession, webhookId));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];

        payload.get(bytes);

        String sessionId = sessionIdByWebSocketSessionId.getIfPresent(session.getId());

        if (sessionId == null) {
            return;
        }

        voiceSessionRegistry.get(sessionId)
            .ifPresent(voiceSession -> {
                voiceSession.touchInboundAudio();

                Long engineSessionId = voiceSession.engineSessionId();

                if (engineSessionId != null) {
                    voiceSessionEngine.emitter(engineSessionId)
                        .ifPresent(emitter -> emitter.dispatchBinaryMessage(bytes));
                }
            });
    }

    /**
     * A voice session's audio travels as binary frames; the browser's text frames are control notices: a muted caller's
     * keepalive, and the end-of-call notice it sends just before closing. A keepalive counts as caller activity for the
     * silence timeout. None of them is answered — the close carries the outcome — and none reaches the provider.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        if (VoiceControlFrame.isKeepalive(payload)) {
            String sessionId = sessionIdByWebSocketSessionId.getIfPresent(session.getId());

            if (sessionId != null) {
                voiceSessionRegistry.get(sessionId)
                    .ifPresent(VoiceSessionRegistry.Session::touchInboundAudio);
            }

            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Voice WS control frame: webSocketSessionId={}, payload={}", session.getId(), payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String webSocketSessionId = session.getId();

        log.info("WebSocket connection closed: webSocketSessionId={}, status={}", webSocketSessionId, status);

        // Taken before any early return, so a socket closed for a session that is already gone leaves nothing behind.
        String serverCloseReason = serverCloseReasonsByWebSocketSessionId.remove(webSocketSessionId);
        String sessionId = sessionIdByWebSocketSessionId.getIfPresent(webSocketSessionId);

        if (sessionId == null) {
            // A rejected upgrade never registered a session.
            return;
        }

        sessionIdByWebSocketSessionId.invalidate(webSocketSessionId);

        Optional<VoiceSessionRegistry.Session> voiceSessionOptional = voiceSessionRegistry.get(sessionId);

        if (voiceSessionOptional.isEmpty()) {
            return;
        }

        VoiceSessionRegistry.Session voiceSession = voiceSessionOptional.get();

        if (WebSocketSessionDecorator.unwrap(voiceSession.webSocketSession()) != session) {
            // A stale socket closing after its session was resumed elsewhere must not end or detach it.
            return;
        }

        // Only a close the server recorded ends the session with a server reason; the frame's reason is the client's
        // word, so a client sending silence_timeout or session_limit is treated as a drop and stays resumable.
        String endReason = serverCloseReason;

        if (endReason == null && isClientHangUp(status)) {
            endReason = CLIENT_END_REASON;
        }

        if (endReason == null) {
            Instant detachedAt;

            // Detach and cancel under the monitor startSession holds while it checks the session is attached and
            // schedules timers: a drop racing the element's start either detaches first, and start schedules nothing,
            // or finds the timers already registered and cancels them here. Cancelling before detaching, outside the
            // monitor, left a window where start scheduled timers that outlived the drop until the resume window ended.
            synchronized (voiceSession) {
                voiceSessionRegistry.detach(sessionId);
                cancelTimers(sessionId);

                detachedAt = voiceSession.detachedAt();
            }

            sessionTimeoutScheduler.schedule(
                () -> finalizeIfStillDetached(sessionId, detachedAt), VoiceSessionRegistry.RESUME_WINDOW.toMillis(),
                TimeUnit.MILLISECONDS);

            log.info("Voice session detached, awaiting resume: sessionId={}", sessionId);

            return;
        }

        finalizeSession(sessionId, endReason, false);
    }

    /**
     * Closes the session's socket with {@code silence_timeout} when no inbound audio arrived for the trigger's
     * {@code silenceTimeoutSeconds}. Provider audio does not count: the point is a caller who walked away.
     */
    void closeIfSilent(String sessionId) {
        LiveSession liveSession = liveSessionsBySessionId.getIfPresent(sessionId);

        if (liveSession == null || liveSession.silenceTimeoutSeconds() <= 0) {
            return;
        }

        voiceSessionRegistry.get(sessionId)
            .ifPresent(voiceSession -> {
                Duration silence = Duration.between(voiceSession.lastInboundAudioAt(), voiceSessionRegistry.now());

                if (silence.toSeconds() >= liveSession.silenceTimeoutSeconds()) {
                    log.info("Closing voice session after {}s of silence: sessionId={}", silence.toSeconds(),
                        sessionId);

                    closeForServerReason(voiceSession.webSocketSession(),
                        CloseStatus.NORMAL.withReason(SILENCE_TIMEOUT));
                }
            });
    }

    /**
     * Ends a session the caller dropped and did not resume. Holds the same monitor {@link VoiceSessionRegistry#resume}
     * holds, so a resume either wins and keeps the session or loses and finds it gone.
     */
    void finalizeIfStillDetached(String sessionId, @Nullable Instant detachedAt) {
        if (detachedAt == null) {
            return;
        }

        Optional<VoiceSessionRegistry.Session> voiceSessionOptional = voiceSessionRegistry.get(sessionId);

        if (voiceSessionOptional.isEmpty()) {
            return;
        }

        VoiceSessionRegistry.Session voiceSession = voiceSessionOptional.get();
        LiveSession liveSession;

        synchronized (voiceSession) {
            if (!detachedAt.equals(voiceSession.detachedAt())) {
                return;
            }

            liveSession = liveSessionsBySessionId.getIfPresent(sessionId);

            voiceSession.markEnded();
            liveSessionsBySessionId.invalidate(sessionId);
            voiceSessionRegistry.remove(sessionId);
        }

        endSession(voiceSession, liveSession, CLIENT_CLOSED);
    }

    /** Notes that the server is closing this socket for {@code reason}; called just before the close is sent. */
    void recordServerClose(String webSocketSessionId, String reason) {
        serverCloseReasonsByWebSocketSessionId.put(webSocketSessionId, reason);
    }

    boolean hasLiveSession(String sessionId) {
        return liveSessionsBySessionId.getIfPresent(sessionId) != null;
    }

    /**
     * How many of the session's session-limit and silence timers are still pending; zero once a session has ended.
     */
    int scheduledTimerCount(String sessionId) {
        List<ScheduledFuture<?>> timers = sessionTimersBySessionId.getIfPresent(sessionId);

        if (timers == null) {
            return 0;
        }

        return (int) timers.stream()
            .filter(timer -> !timer.isDone())
            .count();
    }

    static Map<String, Object> sessionOutput(
        VoiceSessionRegistry.Session voiceSession, @Nullable VoiceSession engineSession, String endReason,
        Instant endedAt) {

        Map<String, Object> output = new LinkedHashMap<>();

        output.put("sessionId", voiceSession.sessionId());
        output.put(
            "startedAt", voiceSession.startedAt()
                .toString());
        output.put(
            "durationSeconds", Duration.between(voiceSession.startedAt(), endedAt)
                .toSeconds());
        output.put("endReason", endReason);
        output.put(
            "transcript", engineSession == null ? List.of()
                : engineSession.transcript()
                    .entries());
        output.put(
            "toolCalls", engineSession == null ? List.of()
                : engineSession.transcript()
                    .toolCalls());

        return output;
    }

    private void startSession(WebSocketSession session, VoiceSessionRegistry.Session voiceSession, String webhookId) {
        WorkflowExecutionId workflowExecutionId;

        try {
            workflowExecutionId = WorkflowExecutionId.parse(webhookId);
        } catch (RuntimeException runtimeException) {
            failStart(session, voiceSession, runtimeException);

            return;
        }

        // The trigger, its connections and the element's context all live in the webhook's tenant, as for an HTTP
        // webhook delivery. startSessionInTenant handles its own failures: TenantContext rewraps anything that escapes.
        TenantContext.runWithTenantId(
            workflowExecutionId.getTenantId(),
            () -> startSessionInTenant(session, voiceSession, webhookId, workflowExecutionId));
    }

    private void startSessionInTenant(
        WebSocketSession session, VoiceSessionRegistry.Session voiceSession, String webhookId,
        WorkflowExecutionId workflowExecutionId) {

        String sessionId = voiceSession.sessionId();

        try {
            if (!triggerResolver.isWorkflowEnabled(workflowExecutionId)) {
                refuseDisabledWorkflow(session, voiceSession);

                return;
            }

            WorkflowTrigger workflowTrigger = triggerResolver.resolve(workflowExecutionId);

            Map<String, Object> inputs = new LinkedHashMap<>();

            inputs.put("sessionId", sessionId);
            inputs.put(
                "startedAt", voiceSession.startedAt()
                    .toString());
            inputs.put("mainWorkflowExecutionId", webhookId);

            VoiceSession engineSession = voiceSessionEngine.start(
                workflowTrigger.getExtensions(),
                voiceSessionConnectionResolver.resolveDeployed(workflowExecutionId, workflowTrigger), inputs,
                // The deployment's principal is what Component Rules resolve a workspace from, and its environment is
                // what environment-scoped tools read. The deployment-workflow row id is not reachable from the
                // webhook id here, and nothing a voice tool runs reads it.
                workflowExecutionId.getJobPrincipalId(), null, triggerResolver.getEnvironmentId(workflowExecutionId),
                workflowExecutionId.getType(), false, emitter -> attachOutboundBridge(voiceSession, emitter));

            voiceSession.engineSessionId(engineSession.sessionId());

            LiveSession liveSession = new LiveSession(
                engineSession,
                MapUtils.getInteger(
                    workflowTrigger.getParameters(), SILENCE_TIMEOUT_SECONDS, DEFAULT_SILENCE_TIMEOUT_SECONDS),
                sessionLimitSeconds(workflowTrigger));

            boolean attached;

            // The monitor detach and resume hold: a socket that drops while the element starts either detaches before
            // this check, and no timers are scheduled, or after it, and its close cancels them.
            synchronized (voiceSession) {
                liveSessionsBySessionId.put(sessionId, liveSession);

                attached = voiceSessionRegistry.isAttached(sessionId);

                if (attached) {
                    scheduleTimers(voiceSession, liveSession);
                }
            }

            if (shuttingDown) {
                // stop() may have read the live sessions before this one joined them; nothing else would end it. A
                // session its caller already ended while the element started leaves nothing to finalize, and still
                // needs the same cleanup as outside shutdown.
                if (!finalizeSession(sessionId, SERVER_SHUTDOWN, true)) {
                    stopIfEndedWhileStarting(sessionId, liveSession);
                }

                return;
            }

            if (!attached) {
                // Otherwise it detached while starting: a resume, or the resume window's finalize, takes it from here.
                stopIfEndedWhileStarting(sessionId, liveSession);

                return;
            }

            sendMessage(voiceSession.webSocketSession(), connectedEvent(webhookId, sessionId, liveSession, false));
        } catch (VoiceSessionEngine.NoVoiceAgentException noVoiceAgentException) {
            // Matched by type, not message: a provider failure that happens to quote the message is still a failure.
            refuseWithoutVoiceAgent(session, voiceSession);
        } catch (Exception exception) {
            failStart(session, voiceSession, exception);
        }
    }

    /**
     * Stops the element of a session that ended while it was starting. The close that ended it ran before the element
     * had an engine id, so it could not stop the engine; nothing else ever will. The live session is taken back only if
     * no finalize already took it, so an engine that a finalize already stopped is not stopped twice.
     */
    private void stopIfEndedWhileStarting(String sessionId, LiveSession liveSession) {
        if (voiceSessionRegistry.get(sessionId)
            .isPresent()) {

            return;
        }

        if (liveSessionsBySessionId.asMap()
            .remove(sessionId, liveSession)) {

            voiceSessionEngine.stop(liveSession.engineSession()
                .sessionId());
        }
    }

    /**
     * Refuses a session whose trigger has no Voice Agent. No conversation took place, so — as the editor test path
     * already does — nothing is recorded as a session: the session is removed before the socket closes, and the close
     * creates no continuation job.
     */
    private void refuseWithoutVoiceAgent(WebSocketSession session, VoiceSessionRegistry.Session voiceSession) {
        log.warn("Voice session refused, the trigger has no Voice Agent: sessionId={}", voiceSession.sessionId());

        // Counted as opened on upgrade; a refusal must balance that like a failed start does.
        voiceMetricsRecorder.recordSessionError();

        synchronized (voiceSession) {
            voiceSession.markEnded();
            voiceSessionRegistry.remove(voiceSession.sessionId());
        }

        Map<String, Object> error = new LinkedHashMap<>();

        error.put("type", "error");
        error.put("message", "No Voice Agent configured on the trigger");

        WebSocketSession currentSocket = Objects.requireNonNullElse(voiceSession.webSocketSession(), session);

        sendMessage(currentSocket, error);

        // The reason tells a client with no session id yet that this refusal is final, not a dropped connection.
        closeQuietly(currentSocket, CloseStatus.POLICY_VIOLATION.withReason(VOICE_AGENT_MISSING));
    }

    private void failStart(WebSocketSession session, VoiceSessionRegistry.Session voiceSession, Exception exception) {
        log.error("Failed to start voice session: sessionId={}", voiceSession.sessionId(), exception);

        voiceMetricsRecorder.recordSessionError();

        Map<String, Object> error = new LinkedHashMap<>();

        error.put("type", "error");
        error.put("message", Objects.toString(exception.getMessage(), "Failed to start the voice session"));

        WebSocketSession currentSocket = Objects.requireNonNullElse(voiceSession.webSocketSession(), session);

        sendMessage(currentSocket, error);
        closeForServerReason(currentSocket, CloseStatus.POLICY_VIOLATION.withReason(PROVIDER_ERROR));
    }

    /**
     * Refuses a session whose deployment has the workflow disabled. The session is removed before the socket closes, so
     * the close finds nothing to end: a disabled workflow gets no continuation job.
     */
    private void refuseDisabledWorkflow(WebSocketSession session, VoiceSessionRegistry.Session voiceSession) {
        log.info("Voice session refused, the workflow is disabled: sessionId={}", voiceSession.sessionId());

        // Counted as opened on upgrade; a refusal must balance that like a failed start does.
        voiceMetricsRecorder.recordSessionError();

        synchronized (voiceSession) {
            voiceSession.markEnded();
            voiceSessionRegistry.remove(voiceSession.sessionId());
        }

        Map<String, Object> error = new LinkedHashMap<>();

        error.put("type", "error");
        error.put("message", "The workflow behind this voice session is disabled.");

        WebSocketSession currentSocket = Objects.requireNonNullElse(voiceSession.webSocketSession(), session);

        sendMessage(currentSocket, error);

        // The reason tells a client with no session id yet that this refusal is final, not a dropped connection.
        closeQuietly(currentSocket, CloseStatus.POLICY_VIOLATION.withReason(WORKFLOW_DISABLED));
    }

    /**
     * Re-attaches a detached session to a new socket. The outbound bridge always writes to the registry's current
     * socket, so it needs no re-wiring; only the socket mapping and the timers are rebuilt.
     *
     * @return false when the session cannot be resumed and a fresh one must start
     */
    private boolean resumeSession(WebSocketSession session, String resumeSessionId, String webhookId) {
        boolean sameWebhook = voiceSessionRegistry.get(resumeSessionId)
            .map(VoiceSessionRegistry.Session::workflowExecutionId)
            .filter(webhookId::equals)
            .isPresent();

        if (!sameWebhook) {
            // The token just consumed was minted for webhookId, and minting is open to anyone; a session id belonging
            // to another webhook must not be reachable with it. The session's webhook id is fixed at registration, so
            // checking it before taking the session's monitor is safe.
            log.info(
                "Voice session could not be resumed, starting a new one: resumeSessionId={} is not a session of this " +
                    "webhook",
                resumeSessionId);

            return false;
        }

        Optional<VoiceSessionRegistry.Session> resumed = voiceSessionRegistry.resume(resumeSessionId, session);

        if (resumed.isEmpty()) {
            log.info("Voice session could not be resumed, starting a new one: resumeSessionId={}", resumeSessionId);

            return false;
        }

        VoiceSessionRegistry.Session voiceSession = resumed.get();
        LiveSession liveSession = liveSessionsBySessionId.getIfPresent(resumeSessionId);

        if (liveSession == null) {
            // Detached before its element had started, or finalized between resume and here.
            voiceSessionRegistry.remove(resumeSessionId);

            return false;
        }

        sessionIdByWebSocketSessionId.put(session.getId(), resumeSessionId);

        scheduleTimers(voiceSession, liveSession);

        sendMessage(voiceSession.webSocketSession(), connectedEvent(webhookId, resumeSessionId, liveSession, true));

        log.info("Voice session resumed: sessionId={}", resumeSessionId);

        return true;
    }

    /**
     * Ends a session for good.
     *
     * @param closeSocket whether the session's socket is still open and must be told why it ends: true when the server
     *                    decided on its own (the provider hung up), false when the socket's close is what got us here
     * @return whether this call ended the session; false when it was already gone
     */
    private boolean finalizeSession(String sessionId, String endReason, boolean closeSocket) {
        Optional<VoiceSessionRegistry.Session> voiceSessionOptional = voiceSessionRegistry.get(sessionId);

        if (voiceSessionOptional.isEmpty()) {
            return false;
        }

        VoiceSessionRegistry.Session voiceSession = voiceSessionOptional.get();
        LiveSession liveSession;
        WebSocketSession attachedSocket;

        // The monitor startSession holds while it checks the session is attached and schedules its timers. Ending the
        // session under it means those timers are either never scheduled (the session is already gone) or already
        // registered and cancelled here. Sends and closes stay outside the lock.
        synchronized (voiceSession) {
            if (voiceSessionRegistry.get(sessionId)
                .isEmpty()) {

                // Another close already ended it.
                return false;
            }

            liveSession = liveSessionsBySessionId.getIfPresent(sessionId);
            attachedSocket = voiceSession.webSocketSession();

            voiceSession.markEnded();
            liveSessionsBySessionId.invalidate(sessionId);
            voiceSessionRegistry.remove(sessionId);
            cancelTimers(sessionId);
        }

        if (closeSocket) {
            closeForServerReason(attachedSocket, CloseStatus.NORMAL.withReason(endReason));
        }

        endSession(voiceSession, liveSession, endReason);

        return true;
    }

    private void endSession(
        VoiceSessionRegistry.Session voiceSession, @Nullable LiveSession liveSession, String endReason) {

        String tenantId = tenantIdOf(voiceSession);

        if (ON_TIMER_THREAD.get()) {
            // The one timer thread serves every session on this node: a slow engine stop or continuation job here
            // would hold back every other session's timers. The decision to end was already made under the session's
            // monitor; only the work moves.
            startHandOff(
                () -> TenantContext.runWithTenantId(
                    tenantId, () -> endSessionInTenant(voiceSession, liveSession, endReason)));

            return;
        }

        // The continuation job and everything it reads live in the webhook's tenant. endSessionInTenant never throws:
        // TenantContext rewraps anything that escapes its block.
        TenantContext.runWithTenantId(tenantId, () -> endSessionInTenant(voiceSession, liveSession, endReason));
    }

    private static String tenantIdOf(VoiceSessionRegistry.Session voiceSession) {
        String workflowExecutionId = voiceSession.workflowExecutionId();

        if (workflowExecutionId == null) {
            return TenantContext.getCurrentTenantId();
        }

        try {
            return WorkflowExecutionId.parse(workflowExecutionId)
                .getTenantId();
        } catch (RuntimeException runtimeException) {
            // A session whose id never parsed failed at start; ending it touches no tenant data worth scoping.
            return TenantContext.getCurrentTenantId();
        }
    }

    private void endSessionInTenant(
        VoiceSessionRegistry.Session voiceSession, @Nullable LiveSession liveSession, String endReason) {

        cancelTimers(voiceSession.sessionId());

        Long engineSessionId = voiceSession.engineSessionId();

        if (engineSessionId != null) {
            try {
                voiceSessionEngine.stop(engineSessionId);
            } catch (Exception exception) {
                log.warn(
                    "Failed to stop voice session: sessionId={}, engineSessionId={}", voiceSession.sessionId(),
                    engineSessionId, exception);
            }
        }

        Instant endedAt = voiceSessionRegistry.now();
        String workflowExecutionId = voiceSession.workflowExecutionId();

        if (workflowExecutionId != null) {
            workflowContinuationHelper.createContinuationJob(
                workflowExecutionId,
                sessionOutput(
                    voiceSession, liveSession == null ? null : liveSession.engineSession(), endReason, endedAt));
        }

        voiceMetricsRecorder.recordSessionClosed(Duration.between(voiceSession.startedAt(), endedAt));

        log.info("Voice session ended: sessionId={}, endReason={}", voiceSession.sessionId(), endReason);
    }

    private void scheduleTimers(VoiceSessionRegistry.Session voiceSession, LiveSession liveSession) {
        String sessionId = voiceSession.sessionId();

        cancelTimers(sessionId);

        List<ScheduledFuture<?>> timers = new CopyOnWriteArrayList<>();

        if (liveSession.sessionLimitSeconds() > 0) {
            long elapsedSeconds = Duration.between(voiceSession.startedAt(), voiceSessionRegistry.now())
                .toSeconds();
            long remainingSeconds = Math.max(0, liveSession.sessionLimitSeconds() - elapsedSeconds);

            timers.add(
                sessionTimeoutScheduler.schedule(
                    () -> closeAtSessionLimit(sessionId, liveSession.sessionLimitSeconds()), remainingSeconds,
                    TimeUnit.SECONDS));
        }

        if (liveSession.silenceTimeoutSeconds() > 0) {
            timers.add(
                sessionTimeoutScheduler.scheduleWithFixedDelay(
                    () -> runSilenceCheck(sessionId), SILENCE_CHECK_INTERVAL_SECONDS, SILENCE_CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS));
        }

        sessionTimersBySessionId.put(sessionId, timers);
    }

    private void cancelTimers(String sessionId) {
        List<ScheduledFuture<?>> timers = sessionTimersBySessionId.getIfPresent(sessionId);

        if (timers == null) {
            return;
        }

        for (ScheduledFuture<?> timer : timers) {
            timer.cancel(false);
        }

        sessionTimersBySessionId.invalidate(sessionId);
    }

    private void closeAtSessionLimit(String sessionId, long sessionLimitSeconds) {
        voiceSessionRegistry.get(sessionId)
            .ifPresent(voiceSession -> {
                log.info(
                    "Closing voice session at its session limit ({}s): sessionId={}", sessionLimitSeconds, sessionId);

                closeForServerReason(voiceSession.webSocketSession(), CloseStatus.NORMAL.withReason(SESSION_LIMIT));
            });
    }

    private void runSilenceCheck(String sessionId) {
        try {
            closeIfSilent(sessionId);
        } catch (Exception exception) {
            // A periodic task that throws is never run again; keep checking.
            log.warn("Silence check failed: sessionId={}", sessionId, exception);
        }
    }

    private long sessionLimitSeconds(WorkflowTrigger workflowTrigger) {
        int triggerLimitSeconds = MapUtils.getInteger(workflowTrigger.getParameters(), SESSION_LIMIT_SECONDS, 0);

        return triggerLimitSeconds > 0 ? triggerLimitSeconds : maxSessionDurationSeconds;
    }

    private static Map<String, Object> connectedEvent(
        String webhookId, String sessionId, LiveSession liveSession, boolean resumed) {

        Map<String, Object> connected = new LinkedHashMap<>();

        connected.put("event", "connected");
        connected.put("id", webhookId);
        connected.put("sessionId", sessionId);
        connected.put(
            "outputSampleRate", liveSession.engineSession()
                .outputSampleRate());
        connected.put("silenceTimeoutSeconds", liveSession.silenceTimeoutSeconds());

        if (resumed) {
            connected.put("resumed", true);
        }

        return connected;
    }

    /**
     * A deliberate hang-up: code 1000 with {@link #CLIENT_END_REASON}. Anything else the browser sends — a drop, a
     * close with no reason, the same reason under another code — keeps the session resumable.
     */
    private static boolean isClientHangUp(@Nullable CloseStatus status) {
        return status != null && status.getCode() == CloseStatus.NORMAL.getCode() &&
            CLIENT_END_REASON.equals(status.getReason());
    }

    /**
     * Closes a socket for a reason the server decided on, recording the reason first: the container may call
     * {@link #afterConnectionClosed} on this very thread, and it must find the reason already there.
     */
    private void closeForServerReason(@Nullable WebSocketSession session, CloseStatus closeStatus) {
        if (session == null || !session.isOpen()) {
            return;
        }

        String reason = closeStatus.getReason();

        if (reason != null) {
            recordServerClose(session.getId(), reason);
        }

        closeQuietly(session, closeStatus);
    }

    /**
     * Hooks the element's outbound channels to whichever socket the session is attached to right now: JSON events
     * become {@link TextMessage}s, audio becomes {@link BinaryMessage}s. While the session is detached, output is
     * dropped. A provider failure closes that socket with {@code provider_error}, after the engine's {@code error}
     * event, and the close ends the session. A provider that hangs up on its own (an {@code end_call} tool, its own
     * duration cap) completes the emitter, and the session ends as {@code provider_closed} whether or not a socket is
     * attached; a completion the handler caused by stopping the engine is ignored.
     */
    private void attachOutboundBridge(VoiceSessionRegistry.Session voiceSession, WebSocketEmitter emitter) {
        AtomicBoolean providerFailed = new AtomicBoolean();

        emitter.addOutboundListener(payload -> {
            WebSocketSession webSocketSession = voiceSession.webSocketSession();

            if (webSocketSession == null || !webSocketSession.isOpen()) {
                return;
            }

            try {
                if (payload instanceof String stringPayload) {
                    webSocketSession.sendMessage(new TextMessage(stringPayload));
                } else {
                    webSocketSession.sendMessage(new TextMessage(JsonUtils.write(payload)));
                }
            } catch (IOException ioException) {
                log.warn(
                    "Failed to forward outbound text to WS session: sessionId={}", voiceSession.sessionId(),
                    ioException);
            }
        });

        emitter.addOutboundBinaryListener(bytes -> {
            WebSocketSession webSocketSession = voiceSession.webSocketSession();

            if (webSocketSession == null || !webSocketSession.isOpen()) {
                return;
            }

            try {
                webSocketSession.sendMessage(new BinaryMessage(bytes));
            } catch (IOException ioException) {
                log.warn(
                    "Failed to forward outbound binary to WS session: sessionId={}", voiceSession.sessionId(),
                    ioException);
            }
        });

        emitter.addErrorListener(throwable -> {
            providerFailed.set(true);

            log.warn("Voice Agent failed mid-session: sessionId={}", voiceSession.sessionId(), throwable);

            if (voiceSession.webSocketSession() == null) {
                // Detached: no socket close will ever report this failure, so end the session now rather than let the
                // resume window relabel it client_closed. finalizeSession takes the socket under the session's
                // monitor, so a resume that attached in the meantime is still closed with the reason.
                finalizeSession(voiceSession.sessionId(), PROVIDER_ERROR, true);

                return;
            }

            closeForServerReason(voiceSession.webSocketSession(), CloseStatus.NORMAL.withReason(PROVIDER_ERROR));
        });

        // WebSocketEmitter.error completes the emitter after its error listeners run, so a failure reaches here too;
        // it has already been reported as provider_error.
        emitter.addCompletionListener(() -> {
            if (providerFailed.get() || voiceSession.ended()) {
                return;
            }

            log.info("Voice Agent closed the session: sessionId={}", voiceSession.sessionId());

            finalizeSession(voiceSession.sessionId(), PROVIDER_CLOSED, true);
        });
    }

    private void sendMessage(@Nullable WebSocketSession session, Map<String, Object> data) {
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

    private static void closeQuietly(@Nullable WebSocketSession session, CloseStatus closeStatus) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            session.close(closeStatus);
        } catch (IOException ioException) {
            log.warn("Failed to close WS session: webSocketSessionId={}", session.getId(), ioException);
        }
    }

    /**
     * The webhook id is the path segment before the {@code wss} suffix: {@code /webhooks/{id}/wss}.
     */
    private static @Nullable String extractId(@Nullable URI uri) {
        if (uri == null) {
            return null;
        }

        String path = uri.getPath();

        if (path == null) {
            return null;
        }

        String[] segments = path.split("/");

        for (int index = segments.length - 1; index >= 0; index--) {
            String segment = segments[index];

            if (!segment.isEmpty() && !"webhooks".equals(segment) && !"wss".equals(segment)) {
                return segment;
            }
        }

        return null;
    }

    private static @Nullable String extractQueryParam(@Nullable URI uri, String paramName) {
        if (uri == null) {
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

    /**
     * What the handler keeps about a started session beside the registry record: the engine's session and the trigger's
     * timing settings, which a resume needs to rebuild the timers.
     */
    private record LiveSession(VoiceSession engineSession, int silenceTimeoutSeconds, long sessionLimitSeconds) {
    }
}
