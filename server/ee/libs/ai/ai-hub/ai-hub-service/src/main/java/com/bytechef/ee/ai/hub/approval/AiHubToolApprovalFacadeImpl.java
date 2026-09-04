/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.agui.core.message.UserMessage;
import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.ee.ai.hub.agent.AiHubChatStreamer;
import com.bytechef.ee.ai.hub.agent.AiHubRunState;
import com.bytechef.ee.ai.hub.agent.AiHubToolCallbackWrappers;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.exception.ConflictException;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.ai.hub.toolsearch.AiHubChatBindingToolCallbackResolver;
import com.bytechef.ee.ai.hub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.ee.ai.hub.util.Source;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementation of {@link AiHubToolApprovalFacade}. Not {@code @Transactional} at class level. {@link #resolve} runs
 * in three separate steps, each committed independently, rather than one wrapping transaction:
 * <ol>
 * <li>{@link #expireIfPastDeadline} — outside any transaction opened by this facade; nothing here needs to be atomic
 * with anything else, and wrapping it in a transaction that is later rolled back by a thrown {@code ConflictException}
 * would silently discard the {@code EXPIRED} write (a real {@link org.springframework.transaction.TransactionManager}
 * rolls back on any {@code RuntimeException} escaping the callback — {@code ConflictException} is one).</li>
 * <li>{@link #persistDecision} — the resolver's decision (who, when, comment, APPROVED/REJECTED) is saved and committed
 * inside its own {@link TransactionTemplate} block, and the audit event is published, BEFORE the tool ever runs. Two
 * concurrent resolves both reading {@code PENDING} race here: the loser's save fails the {@link AiHubToolApproval}'s
 * optimistic {@code @Version} check, surfaced as {@link OptimisticLockingFailureException} and translated to the same
 * not-found-shaped conflict every other enumeration-safe failure uses.</li>
 * <li>{@link #executeAndPersistOutcome} — runs the approved tool call AFTER the decision has committed, outside any
 * transaction, so a slow external API never holds a pooled DB connection open. A thrown execution flips the row to
 * {@code FAILED} and persists that as a second, independent write; the decision itself stays committed either way.</li>
 * </ol>
 *
 * <p>
 * The approved tool call executes under the RESOLVER's own security context, not the requester's: the requester is
 * always the chat owner and the resolver is the owner or a workspace admin, so impersonating the requester via
 * {@code SecurityUtils.runAs} would buy nothing and cost a rebuilt {@code Authentication}. Both ids are recorded on the
 * row ({@code requestedByUserId} at creation, {@code decidedByUserId} here) and the CURRENT authentication is carried
 * into the tool context.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubToolApprovalFacadeImpl implements AiHubToolApprovalFacade {

    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final UserService userService;
    private final AiHubChatStreamer chatStreamer;
    private final InFlightAiHubRunRegistry inFlightRunRegistry;
    private final AiHubChatBindingToolCallbackResolver chatBindingResolver;
    private final AiHubGlobalToolCatalog askGlobalToolCatalog;
    private final AiHubGlobalToolCatalog buildGlobalToolCatalog;
    private final Map<String, LocalAgent> localAgentMap;
    private final @Nullable SecurityContextRehydrator securityContextRehydrator;
    private final @Nullable AiHubAuditPublisher auditPublisher;
    private final @Nullable ToolExecutionRecorder toolExecutionRecorder;
    private final AiHubToolApprovalMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Marked {@code @Autowired} because this class has two constructors: Spring auto-selects a constructor only when
     * there is exactly one candidate, and would otherwise fall back to a no-arg constructor that does not exist,
     * failing context startup.
     */
    @Autowired
    @SuppressFBWarnings("EI")
    public AiHubToolApprovalFacadeImpl(
        AiHubToolApprovalService approvalService, AiHubChatService chatService, UserService userService,
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry,
        AiHubChatBindingToolCallbackResolver chatBindingResolver,
        @Qualifier("aiHubAskGlobalToolCatalog") AiHubGlobalToolCatalog askGlobalToolCatalog,
        @Qualifier("aiHubBuildGlobalToolCatalog") AiHubGlobalToolCatalog buildGlobalToolCatalog,
        List<LocalAgent> localAgents, @Nullable SecurityContextRehydrator securityContextRehydrator,
        @Nullable AiHubAuditPublisher auditPublisher, @Nullable ToolExecutionRecorder toolExecutionRecorder,
        AiHubToolApprovalMetrics metrics, PlatformTransactionManager transactionManager) {

        this(
            approvalService, chatService, userService, chatStreamer, inFlightRunRegistry, chatBindingResolver,
            askGlobalToolCatalog, buildGlobalToolCatalog, localAgents, securityContextRehydrator, auditPublisher,
            toolExecutionRecorder, metrics, transactionManager, Clock.systemUTC());
    }

    @SuppressFBWarnings("EI")
    AiHubToolApprovalFacadeImpl(
        AiHubToolApprovalService approvalService, AiHubChatService chatService, UserService userService,
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry,
        AiHubChatBindingToolCallbackResolver chatBindingResolver, AiHubGlobalToolCatalog askGlobalToolCatalog,
        AiHubGlobalToolCatalog buildGlobalToolCatalog, List<LocalAgent> localAgents,
        @Nullable SecurityContextRehydrator securityContextRehydrator, @Nullable AiHubAuditPublisher auditPublisher,
        @Nullable ToolExecutionRecorder toolExecutionRecorder, AiHubToolApprovalMetrics metrics,
        PlatformTransactionManager transactionManager, Clock clock) {

        this.approvalService = approvalService;
        this.chatService = chatService;
        this.userService = userService;
        this.chatStreamer = chatStreamer;
        this.inFlightRunRegistry = inFlightRunRegistry;
        this.chatBindingResolver = chatBindingResolver;
        this.askGlobalToolCatalog = askGlobalToolCatalog;
        this.buildGlobalToolCatalog = buildGlobalToolCatalog;
        this.localAgentMap = localAgents.stream()
            .collect(Collectors.toMap(LocalAgent::getAgentId, localAgent -> localAgent));
        this.securityContextRehydrator = securityContextRehydrator;
        this.auditPublisher = auditPublisher;
        this.toolExecutionRecorder = toolExecutionRecorder;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * {@code chatService.getById} is the authorization check: it enforces chat ownership within the requested workspace
     * and throws {@link NotFoundException} otherwise. There is no separate workspace-role check, because a tool
     * approval belongs to one chat, not to the workspace at large.
     */
    @Override
    public List<AiHubToolApproval> list(long workspaceId, long chatId) {
        long currentUserId = userService.getCurrentUser()
            .getId();

        chatService.getById(chatId, workspaceId, currentUserId);

        return approvalService.list(chatId);
    }

    @Override
    public Resolution resolve(long workspaceId, long approvalId, boolean approved, @Nullable String comment) {
        long currentUserId = userService.getCurrentUser()
            .getId();

        AiHubToolApproval approval = approvalService.get(approvalId);
        AiHubChat chat = chatService.findByThreadId(approval.getThreadId())
            .orElseThrow(() -> new NotFoundException("AiHubToolApproval", approvalId));

        if (!Objects.equals(chat.getWorkspaceId(), workspaceId) || !canResolve(chat, currentUserId)) {
            throw new NotFoundException("AiHubToolApproval", approvalId);
        }

        if (approval.getStatus() != AiHubToolApproval.Status.PENDING) {
            throw new ConflictException("Approval " + approvalId + " is already " + approval.getStatus());
        }

        expireIfPastDeadline(approval);

        AiHubToolApproval decided = persistDecision(approval, currentUserId, approved, comment);

        String toolResult = approved ? executeAndPersistOutcome(decided, chat, currentUserId) : null;

        String runId = startContinuation(decided, chat, currentUserId, toolResult);

        return new Resolution(decided, runId != null, runId);
    }

    /**
     * Chat-ownership-based authorization: the chat owner, or any workspace admin, may resolve an approval raised on
     * that chat. Deliberately not a workspace-role {@code @PreAuthorize} — the approval belongs to the chat, not the
     * workspace's resource-visibility graph.
     */
    private boolean canResolve(AiHubChat chat, long userId) {
        return chat.getUserId() == userId || SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);
    }

    /**
     * Flips {@code approval} to {@code EXPIRED} and persists it, then throws, when its deadline has already passed.
     * Runs outside {@link #transactionTemplate} on purpose: this write is not part of the decision the caller is about
     * to make, and it must survive the very {@code ConflictException} it throws — a real transaction manager rolls back
     * any write made inside a callback that a {@code RuntimeException} escapes, so wrapping this in the same
     * transaction as the throw would silently discard it.
     */
    private void expireIfPastDeadline(AiHubToolApproval approval) {
        if (!approval.isExpired(Instant.now(clock))) {
            return;
        }

        approval.setStatus(AiHubToolApproval.Status.EXPIRED);
        approvalService.save(approval);
        metrics.record("expired");

        throw new ConflictException("Approval " + approval.getId() + " has expired");
    }

    /**
     * Persists the resolver's decision — who decided, when, their comment, and {@code APPROVED}/{@code REJECTED} —
     * inside its own committed {@link #transactionTemplate} block, and publishes the audit event, before the tool ever
     * runs. A concurrent resolve of the same row loses the {@link AiHubToolApproval}'s optimistic {@code @Version}
     * check here; that failure is translated to the same not-found-shaped conflict every other enumeration-safe failure
     * in this facade uses, rather than leaking as a raw persistence exception.
     */
    private AiHubToolApproval persistDecision(
        AiHubToolApproval approval, long currentUserId, boolean approved, @Nullable String comment) {

        approval.setDecidedByUserId(currentUserId);
        approval.setDecidedAt(Instant.now(clock));
        approval.setComment(comment);
        approval.setStatus(approved ? AiHubToolApproval.Status.APPROVED : AiHubToolApproval.Status.REJECTED);

        try {
            return transactionTemplate.execute(status -> {
                AiHubToolApproval decided = approvalService.save(approval);

                publishDecision(decided, approved);
                metrics.record(decided.getStatus()
                    .name()
                    .toLowerCase(Locale.ROOT));

                return decided;
            });
        } catch (OptimisticLockingFailureException exception) {
            throw new NotFoundException("AiHubToolApproval", approval.getId());
        }
    }

    /**
     * Runs the approved tool call AFTER {@link #persistDecision} has committed, never inside that transaction, so a
     * slow external API does not hold a pooled DB connection open. A thrown execution flips {@code decided} to
     * {@code FAILED} (see {@link #execute}) and persists that as a second, independent write — the decision itself
     * stays committed either way.
     */
    private @Nullable String executeAndPersistOutcome(AiHubToolApproval decided, AiHubChat chat, long userId) {
        String toolResult = execute(decided, chat, userId);

        if (decided.getStatus() == AiHubToolApproval.Status.FAILED) {
            approvalService.save(decided);
            metrics.record("failed");
        }

        return toolResult;
    }

    /**
     * Runs the approved tool call with the row's stored arguments, under the CURRENT (resolver's) security context —
     * see the class Javadoc. Mutates {@code approval} to {@code FAILED} (with
     * {@link AiHubToolApproval#getExecutionError} set) when the tool cannot be found or throws; the caller is
     * responsible for persisting the mutated row.
     */
    private @Nullable String execute(AiHubToolApproval approval, AiHubChat chat, long userId) {
        Optional<ToolCallback> callbackOptional = findCallback(approval, chat, userId);

        if (callbackOptional.isEmpty()) {
            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError("Tool " + approval.getToolName() + " is no longer available in this chat");

            return null;
        }

        ToolCallback callback = AiHubToolCallbackWrappers.wrap(callbackOptional.get(), securityContextRehydrator);
        ToolContext toolContext = new ToolContext(toolContextFor(approval, chat, userId));

        try {
            return toolExecutionRecorder == null
                ? callback.call(approval.getArguments(), toolContext)
                : toolExecutionRecorder.record(
                    ToolExecutionEvent
                        .builder(ToolExecutionSurface.AI_AGENT, toolExecutionKind(approval), approval.getToolName())
                        .componentName(approval.getComponentName())
                        .componentVersion(approval.getComponentVersion())
                        .connectionId(approval.getConnectionId())
                        .environment(approval.getEnvironment())
                        .workspaceId(chat.getWorkspaceId()),
                    () -> callback.call(approval.getArguments(), toolContext));
        } catch (RuntimeException exception) {
            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError(
                exception.getMessage() == null ? exception.toString() : exception.getMessage());

            return null;
        }
    }

    /**
     * {@link ToolExecutionKind} has no CATALOG-equivalent constant (only {@code COMPONENT}, {@code WORKFLOW},
     * {@code CONTRIBUTED}, {@code MANAGEMENT_TOOL}); a catalog tool is a hand-built callback contributed directly by AI
     * Hub rather than derived from a component, so {@code CONTRIBUTED} is the nearest match. See this task's report for
     * the full reasoning.
     */
    private static ToolExecutionKind toolExecutionKind(AiHubToolApproval approval) {
        return approval.getToolKind() == AiHubToolApproval.ToolKind.COMPONENT
            ? ToolExecutionKind.COMPONENT
            : ToolExecutionKind.CONTRIBUTED;
    }

    /**
     * Resolves the {@link ToolCallback} the original gated call would have invoked: first the chat's own attached
     * tools, then the global catalog matching the row's {@code mode}. Empty when the tool is no longer attached to the
     * chat and no longer in the catalog (e.g. a connector was detached, or a catalog tool was removed between the
     * request and the decision).
     */
    private Optional<ToolCallback> findCallback(AiHubToolApproval approval, AiHubChat chat, long userId) {
        List<ToolCallback> chatCallbacks = chatBindingResolver.resolve(
            new AiHubToolInvocationContext(
                chat.getWorkspaceId(), userId, Source.AI_HUB.toAgentSourceOrdinal(), null,
                (long) approval.getEnvironment(), chat.getThreadId()));

        Optional<ToolCallback> chatCallback = findByName(chatCallbacks, approval.getToolName());

        if (chatCallback.isPresent()) {
            return chatCallback;
        }

        AiHubGlobalToolCatalog catalog =
            "BUILD".equals(approval.getMode()) ? buildGlobalToolCatalog : askGlobalToolCatalog;

        return findByName(catalog.toolCallbacks(), approval.getToolName());
    }

    private static Optional<ToolCallback> findByName(List<ToolCallback> callbacks, String toolName) {
        return callbacks.stream()
            .filter(callback -> toolName.equals(
                callback.getToolDefinition()
                    .name()))
            .findFirst();
    }

    /**
     * Builds the {@link ToolContext} map the original gated call would have carried: the AI-Hub-specific
     * {@link AiHubToolInvocationContext} map merged with the surface-neutral {@link AgentToolInvocationContext} map
     * (the same two maps {@code AiHubSpringAIAgent.toolContext} builds), plus the mode key the approval gate reads.
     */
    private Map<String, Object> toolContextFor(AiHubToolApproval approval, AiHubChat chat, long userId) {
        Map<String, Object> toolContext = new HashMap<>(
            new AiHubToolInvocationContext(
                chat.getWorkspaceId(), userId, Source.AI_HUB.toAgentSourceOrdinal(), null,
                (long) approval.getEnvironment(), chat.getThreadId())
                    .toToolContext());

        toolContext.putAll(
            AgentToolInvocationContext.builder()
                .workspaceId(chat.getWorkspaceId())
                .userId(userId)
                .environmentId((long) approval.getEnvironment())
                .conversationId(chat.getThreadId())
                .tenantId(TenantContext.getCurrentTenantId())
                .authentication(SecurityContextHolder.getContext()
                    .getAuthentication())
                .llmProvider(approval.getLlmProvider())
                .llmModel(approval.getLlmModel())
                .build()
                .toToolContext());

        toolContext.put(AiHubApprovalGateToolCallback.TOOL_CONTEXT_MODE_KEY, approval.getMode());

        return toolContext;
    }

    /**
     * Starts a continuation turn on the owning chat reporting the decision's outcome, unless a run is already in flight
     * for the chat's thread (a fresh user turn started the same run) or no matching agent variant is registered.
     * Returns the started run's id, or {@code null} when no continuation was started.
     */
    private @Nullable String startContinuation(
        AiHubToolApproval approval, AiHubChat chat, long userId, @Nullable String toolResult) {

        if (inFlightRunRegistry.isInFlight(chat.getThreadId())) {
            return null;
        }

        LocalAgent localAgent = localAgentMap.get("BUILD".equals(approval.getMode()) ? "ai_hub_build" : "ai_hub_ask");

        if (localAgent == null) {
            return null;
        }

        String runId = UUID.randomUUID()
            .toString();
        UserMessage userMessage = new UserMessage();

        userMessage.setId(UUID.randomUUID()
            .toString());
        userMessage.setContent(continuationText(approval, toolResult));

        State state = new State();

        state.set("mode", approval.getMode());

        if (approval.getLlmProvider() != null) {
            state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, approval.getLlmProvider());
        }

        if (approval.getLlmModel() != null) {
            state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, approval.getLlmModel());
        }

        AiHubRunState.inject(
            state, userId, chat.getWorkspaceId(), chat.getThreadId(), approval.getEnvironment(),
            TenantContext.getCurrentTenantId());

        AgUiParameters parameters = new AgUiParameters();

        parameters.setThreadId(chat.getThreadId());
        parameters.setRunId(runId);
        parameters.setMessages(List.of(userMessage));
        parameters.setState(state);

        chatStreamer.runAgent(localAgent, parameters, chat.getThreadId());

        return runId;
    }

    /**
     * Renders the continuation message the resolution posts back into the conversation. Prefixed with
     * {@link AiHubRunState#CONTINUATION_PREFIX} so the client's transcript renderer shows it as a status line on the
     * approval card rather than a new chat bubble.
     */
    static String continuationText(AiHubToolApproval approval, @Nullable String toolResult) {
        String decision = switch (approval.getStatus()) {
            case APPROVED -> "approved and executed";
            case REJECTED -> "rejected";
            case FAILED -> "approved but the tool failed";
            default -> approval.getStatus()
                .name()
                .toLowerCase(Locale.ROOT);
        };

        StringBuilder text = new StringBuilder()
            .append(AiHubRunState.CONTINUATION_PREFIX)
            .append(approval.getId())
            .append(" ")
            .append(decision)
            .append("]\nTool: ")
            .append(approval.getComponentName() == null ? "" : approval.getComponentName() + "/")
            .append(approval.getToolName());

        if (approval.getComment() != null && !approval.getComment()
            .isBlank()) {

            text.append("\nComment: ")
                .append(approval.getComment());
        }

        if (toolResult != null) {
            text.append("\nResult:\n")
                .append(toolResult);
        }

        if (approval.getExecutionError() != null) {
            text.append("\nError: ")
                .append(approval.getExecutionError());
        }

        return text.toString();
    }

    /**
     * Publishes the decision as {@code AI_HUB_TOOL_APPROVAL_APPROVED} or {@code AI_HUB_TOOL_APPROVAL_REJECTED} — never
     * the row's {@code arguments}. {@code approved} reflects the resolver's decision (not the eventual execution
     * outcome), so a decision to approve is still published as APPROVED even when the tool execution itself failed.
     */
    private void publishDecision(AiHubToolApproval approval, boolean approved) {
        if (auditPublisher == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();

        data.put("approvalId", approval.getId());
        data.put("chatId", approval.getChatId());
        data.put("toolName", approval.getToolName());
        data.put("componentName", approval.getComponentName());
        data.put("requestedByUserId", approval.getRequestedByUserId());
        data.put("decidedByUserId", approval.getDecidedByUserId());

        auditPublisher.publish(
            approved ? AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_APPROVED : AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_REJECTED,
            data);
    }
}
