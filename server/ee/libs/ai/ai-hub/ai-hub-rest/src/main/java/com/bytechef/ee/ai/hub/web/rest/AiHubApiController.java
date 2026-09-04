/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.rest;

import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.ee.ai.hub.agent.AiHubChatStreamer;
import com.bytechef.ee.ai.hub.agent.AiHubRunState;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.approval.AiHubToolApprovalService;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatAccessPolicy;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.AiHubChatTurn;
import com.bytechef.ee.ai.hub.security.WorkspaceAccessGuard;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.ee.ai.hub.util.Mode;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Metrics;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI dispatch entry point for the {@code ai_hub} agent. Resolves the LLM-backed AI Hub routing agent (build vs ask
 * variant based on {@link Mode}) and runs it through the AG-UI service.
 *
 * <p>
 * The {@code ai_hub} agent is the only AG-UI-dispatched agent that is bound to a {@link AiHubChat} — its
 * {@code threadId} keys a row in {@code ai_hub_chat} and every tool callback invoked from the agent records its work
 * against that row's {@code id}. Keeping the dispatcher in the {@code automation-ai-hub-rest} module means CC owns
 * end-to-end the path that creates, mutates, and reads its own chat surface.
 * </p>
 *
 * <p>
 * Spring's {@code RequestMappingHandlerMapping} resolves the literal path {@code /ai/chat/ai_hub} ahead of any generic
 * {@code /ai/chat/{agentId}} mapping that may exist elsewhere, so dispatchers can co-exist without endpoint collisions.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.platform:}/internal")
@ConditionalOnEEVersion
@ConditionalOnCoordinator
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class AiHubApiController {

    private static final Logger log = LoggerFactory.getLogger(AiHubApiController.class);

    private final Map<String, LocalAgent> localAgentMap;
    private final AiHubChatStreamer chatStreamer;
    private final InFlightAiHubRunRegistry inFlightRunRegistry;
    private final AiHubChatService chatService;
    private final AiHubChatAccessPolicy accessPolicy;
    private final UserService userService;
    private final WorkspaceFacade workspaceFacade;
    private final ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider;

    @SuppressFBWarnings("EI")
    public AiHubApiController(
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry,
        List<LocalAgent> localAgents, AiHubChatService chatService, AiHubChatAccessPolicy accessPolicy,
        UserService userService, WorkspaceFacade workspaceFacade,
        ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider) {

        this.chatStreamer = chatStreamer;
        this.inFlightRunRegistry = inFlightRunRegistry;
        this.localAgentMap = localAgents.stream()
            .collect(Collectors.toMap(LocalAgent::getAgentId, localAgent -> localAgent));
        this.chatService = chatService;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.workspaceFacade = workspaceFacade;
        this.toolApprovalServiceProvider = toolApprovalServiceProvider;
    }

    @Validated
    @PostMapping(value = "/ai/chat/ai_hub")
    public SseEmitter chat(@NonNull @RequestBody() AgUiParameters agUiParameters) {
        long userId = userService.getCurrentUser()
            .getId();

        long workspaceId = enforceWorkspaceAccess(agUiParameters, userId);

        String verifiedThreadId = enforceThreadAccess(agUiParameters, userId, workspaceId);

        Long ownerUserId = enforceTurnAvailableAndRecord(verifiedThreadId, userId, agUiParameters.getRunId());

        supersedePendingApprovals(verifiedThreadId);

        injectAuthenticatedContext(agUiParameters, userId, workspaceId, verifiedThreadId, ownerUserId);

        Mode mode = resolveMode(agUiParameters);

        String resolvedAgentId = mode == Mode.BUILD ? "ai_hub_build" : "ai_hub_ask";

        LocalAgent localAgent = localAgentMap.get(resolvedAgentId);

        if (localAgent == null) {
            log.warn(
                "ai_hub agent variant '{}' is not registered (mode={}); known agents: {}",
                resolvedAgentId, mode, localAgentMap.keySet());

            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agentId");
        }

        return chatStreamer.runAgent(localAgent, agUiParameters, verifiedThreadId);
    }

    /**
     * Resume / mirror an existing in-flight run for {@code threadId}. The client invokes this on mount when its probe
     * reports that a run is in flight — instead of reposting the user's message (which would start a duplicate agent
     * invocation), it opens an EventSource here and gets the buffered prefix + live tail.
     *
     * <p>
     * Auth gate matches the POST path: the resolved {@link AiHubChat} must belong to the authenticated user. 404 is
     * returned when no run is registered for the thread — the client treats that as "fell off the bus" and renders
     * history-only without a running pulse.
     * </p>
     */
    @GetMapping(value = "/ai/chat/ai_hub/{threadId}/attach")
    public SseEmitter attach(@PathVariable("threadId") String threadId) {
        long userId = userService.getCurrentUser()
            .getId();

        AiHubChat chat = enforceThreadViewable(threadId, userId);

        Optional<SseEmitter> emitterOptional = chatStreamer.attachToRun(threadId);

        if (emitterOptional.isEmpty()) {
            // Either the run completed before the client mounted, or it never existed. Either way, an attach with
            // no run to attach to is best surfaced as 404 so the client can fall back to history-only rendering.
            Metrics.counter(
                "bytechef.ai_hub.chat.attach_miss_total",
                "chatId", String.valueOf(chat.getId()))
                .increment();

            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No in-flight run for thread");
        }

        Metrics.counter("bytechef.ai_hub.chat.attach_hit_total")
            .increment();

        return emitterOptional.get();
    }

    /**
     * Reports which of the supplied {@code threadIds} currently have in-flight runs. Used by the sidebar on mount /
     * environment switch to paint a "running" pulse on each chat that's actively streaming, not just the focused one.
     * Filtering against a caller-supplied id set scopes the answer to threads the user actually owns — without it, an
     * enumeration of every in-flight thread would leak cross-workspace state.
     */
    @GetMapping(value = "/ai/chat/ai_hub/in-flight")
    public Map<String, Boolean> inFlightStatus(@RequestParam("threadIds") List<String> threadIds) {
        long userId = userService.getCurrentUser()
            .getId();

        // Build the per-thread answer in one pass: each id is either (a) unknown to the registry → false, (b)
        // not viewable by the caller → false (silently — same shape as not-in-flight), or (c) viewable by the
        // caller and in flight → true. The not-viewable case is treated as not-in-flight rather than 403 so a
        // malformed sidebar id list doesn't break the whole probe.
        Set<String> inFlight = new HashSet<>(inFlightRunRegistry.getInFlightThreadIds());

        return threadIds.stream()
            .collect(Collectors.toMap(
                threadId -> threadId, threadId -> isViewableInFlightThread(threadId, userId, inFlight)));
    }

    private boolean isViewableInFlightThread(String threadId, long userId, Collection<String> inFlightThreadIds) {
        if (!inFlightThreadIds.contains(threadId)) {
            return false;
        }

        Optional<AiHubChat> chat = chatService.findByThreadId(threadId);

        return chat.isPresent() && accessPolicy.canView(chat.get(), userId);
    }

    private AiHubChat enforceThreadViewable(String threadId, long userId) {
        if (threadId == null || threadId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing threadId");
        }

        Optional<AiHubChat> chat = chatService.findByThreadId(threadId);

        if (chat.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown thread");
        }

        AiHubChat row = chat.get();

        if (!accessPolicy.canView(row, userId)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "AiHubChat is not accessible to the current user");
        }

        return row;
    }

    /**
     * Reads the client-supplied {@code workspaceId} from the request state and enforces that the authenticated user is
     * a member of that workspace. Without this gate a malicious client could submit
     * {@code state.workspaceId=&lt;victim_ws&gt;} and have CC tool callbacks operate against a workspace they have no
     * access to.
     */
    private long enforceWorkspaceAccess(AgUiParameters agUiParameters, long userId) {
        Long requestedWorkspaceId = readLong(agUiParameters, AiHubStateKeys.WORKSPACE_ID);

        if (requestedWorkspaceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing workspaceId in request state");
        }

        if (!WorkspaceAccessGuard.isMember(workspaceFacade, userId, requestedWorkspaceId)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Workspace is not accessible to the current user");
        }

        return requestedWorkspaceId;
    }

    /**
     * Verifies that the resolved {@link AiHubChat} may receive a turn from the authenticated user (owner, admin, or a
     * participant the chat has been shared with at {@code PARTICIPATE}) AND lives in the requested workspace. Returns
     * the verified thread id, or {@code null} when none was supplied or no chat exists yet (first turn).
     */
    private String enforceThreadAccess(AgUiParameters agUiParameters, long userId, long workspaceId) {
        String threadId = agUiParameters.getThreadId();

        if (threadId == null || threadId.isBlank()) {
            threadId = readString(agUiParameters, AiHubStateKeys.THREAD_ID);
        }

        if (threadId == null || threadId.isBlank()) {
            return null;
        }

        Optional<AiHubChat> chat = chatService.findByThreadId(threadId);

        if (chat.isEmpty()) {
            Metrics.counter("bytechef.ai_hub.chat.threadid_unknown_total")
                .increment();

            return null;
        }

        AiHubChat row = chat.get();

        if (!accessPolicy.canParticipate(row, userId) || chatService.getWorkspaceId(row.getId()) != workspaceId) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "AiHubChat is not accessible to the current user");
        }

        return threadId;
    }

    /**
     * Enforces the one-turn-in-flight-per-chat rule and records this turn's sender, returning the chat's owner id for
     * downstream context injection. A no-op that returns {@code null} when {@code verifiedThreadId} is {@code null}
     * (the very first turn, before any chat row exists) or the chat cannot be re-resolved (a benign race with a
     * concurrent delete).
     *
     * @throws TurnInFlightException when another user's turn is already running on this thread
     */
    private @Nullable Long enforceTurnAvailableAndRecord(
        @Nullable String verifiedThreadId, long userId, String runId) {

        if (verifiedThreadId == null) {
            return null;
        }

        if (inFlightRunRegistry.isInFlight(verifiedThreadId)) {
            throw new TurnInFlightException(resolveRunningUser(verifiedThreadId));
        }

        Optional<AiHubChat> chat = chatService.findByThreadId(verifiedThreadId);

        if (chat.isEmpty()) {
            return null;
        }

        AiHubChat row = chat.get();

        chatService.recordTurn(row.getId(), userId, runId);

        return row.getUserId();
    }

    /**
     * Resolves the user whose turn is currently running on {@code threadId}, for the {@link TurnInFlightException}
     * body. Falls back to a fully-null {@link TurnInFlightException.RunningUser} when the chat or its latest turn can
     * no longer be resolved (a benign race with a concurrent delete) or the running user's login cannot be looked up.
     */
    private TurnInFlightException.RunningUser resolveRunningUser(String threadId) {
        Optional<AiHubChat> chat = chatService.findByThreadId(threadId);

        if (chat.isEmpty()) {
            return new TurnInFlightException.RunningUser(null, null);
        }

        Optional<AiHubChatTurn> latestTurn = chatService.findLatestTurn(chat.get()
            .getId());

        if (latestTurn.isEmpty()) {
            return new TurnInFlightException.RunningUser(null, null);
        }

        long runningUserId = latestTurn.get()
            .getUserId();
        String runningUserName = userService.fetchUser(runningUserId)
            .map(User::getLogin)
            .orElse(null);

        return new TurnInFlightException.RunningUser(runningUserId, runningUserName);
    }

    /**
     * Writes the verified identity values into reserved keys on the AG-UI state. The agent's
     * {@code buildInvocationContext} reads from these server-controlled keys (not from the original request fields), so
     * the {@code AiHubToolInvocationContext} carried into tool callbacks is constructed from authenticated-session data
     * — not from user-controlled request body. Delegates the actual key-writing to {@link AiHubRunState#inject}, shared
     * with the tool-approval resolution facade's continuation turn.
     */
    private void injectAuthenticatedContext(
        AgUiParameters agUiParameters, long userId, long workspaceId, @Nullable String verifiedThreadId,
        @Nullable Long ownerUserId) {

        State state = agUiParameters.getState();

        if (state == null) {
            state = new State();
            agUiParameters.setState(state);
        }

        long environmentId = AiHubRunState.clampEnvironmentId(readLong(agUiParameters, AiHubStateKeys.ENVIRONMENT_ID));

        AiHubRunState.inject(
            state, userId, workspaceId, verifiedThreadId, environmentId, TenantContext.getCurrentTenantId(),
            ownerUserId);
    }

    /**
     * Marks every pending tool approval on the resolved chat as {@code SUPERSEDED} before the new turn runs. A pending
     * approval belongs to the tool call that raised it, and that call's continuation never runs once a fresh user
     * message starts a new turn — leaving the row {@code PENDING} would let a stale approval card resolve against a
     * conversation state that has already moved on. A no-op when no chat resolves yet (first turn) or the tool approval
     * module is disabled.
     */
    private void supersedePendingApprovals(@Nullable String verifiedThreadId) {
        if (verifiedThreadId == null) {
            return;
        }

        AiHubToolApprovalService toolApprovalService = toolApprovalServiceProvider.getIfAvailable();

        if (toolApprovalService == null) {
            return;
        }

        Optional<AiHubChat> chat = chatService.findByThreadId(verifiedThreadId);

        if (chat.isEmpty()) {
            return;
        }

        toolApprovalService.supersedePending(chat.get()
            .getId());
    }

    /**
     * Resolves the {@link Mode} from the AG-UI request state. Missing/blank state defaults to {@link Mode#ASK}; an
     * unrecognised mode string is rejected with 400 so a stale client surfaces a "please refresh" toast instead of
     * silently degrading to ASK.
     */
    private Mode resolveMode(AgUiParameters agUiParameters) {
        State state = agUiParameters.getState();

        if (state == null) {
            return Mode.ASK;
        }

        Map<String, Object> stateMap = state.getState();

        if (stateMap == null) {
            return Mode.ASK;
        }

        Object rawMode = stateMap.get("mode");

        if (!(rawMode instanceof String stringMode) || stringMode.isBlank()) {
            return Mode.ASK;
        }

        try {
            return Mode.valueOf(stringMode);
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown mode '{}' on ai_hub request; rejecting with 400", stringMode);

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown mode: " + stringMode, exception);
        }
    }

    @ExceptionHandler(TurnInFlightException.class)
    public ResponseEntity<Map<String, Object>> handleTurnInFlight(TurnInFlightException exception) {
        TurnInFlightException.RunningUser runningUser = exception.getRunningUser();

        Map<String, Object> body = new HashMap<>();

        body.put("error", "TURN_IN_FLIGHT");
        body.put("runningUserId", runningUser.userId());
        body.put("runningUserName", runningUser.userName());

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        String reason = exception.getReason();

        if (reason == null) {
            return ResponseEntity.status(statusCode)
                .build();
        }

        return ResponseEntity.status(statusCode)
            .body(Map.of("error", reason));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException exception) {
        Metrics.counter(
            "bytechef.ai_hub.chat.unexpected_failure_total",
            "exception", exception.getClass()
                .getSimpleName())
            .increment();

        log.error("Unexpected runtime exception in AiHubApiController: {}", exception.toString(), exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "An unexpected error occurred while dispatching the agent"));
    }

    private static Long readLong(AgUiParameters agUiParameters, String key) {
        Object raw = readRaw(agUiParameters, key);

        if (raw instanceof Number numberValue) {
            return numberValue.longValue();
        }

        if (raw instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException exception) {
                log.warn("Malformed numeric value for state key '{}': '{}' is not parseable as Long",
                    key, stringValue);

                return null;
            }
        }

        return null;
    }

    private static String readString(AgUiParameters agUiParameters, String key) {
        Object raw = readRaw(agUiParameters, key);

        return raw == null ? null : raw.toString();
    }

    private static Object readRaw(AgUiParameters agUiParameters, String key) {
        State state = agUiParameters.getState();

        if (state == null) {
            return null;
        }

        Map<String, Object> stateMap = state.getState();

        return stateMap == null ? null : stateMap.get(key);
    }
}
