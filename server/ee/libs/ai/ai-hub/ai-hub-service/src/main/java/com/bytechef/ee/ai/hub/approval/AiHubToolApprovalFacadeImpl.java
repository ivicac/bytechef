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
import com.bytechef.ee.ai.hub.agent.AiHubSpringAIAgent;
import com.bytechef.ee.ai.hub.agent.AiHubToolCallbackWrappers;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatAccessPolicy;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.exception.ConflictException;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.ai.hub.toolsearch.AiHubChatBindingToolCallbackResolver;
import com.bytechef.ee.ai.hub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.ee.ai.hub.util.Source;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Two identities are in play, and they are used for different things. The REQUESTER ({@code requestedByUserId}) is
 * whoever sent the turn that raised the approval; the RESOLVER ({@code decidedByUserId}, the current principal) is
 * whoever decided it — the chat owner or any INSTANCE admin (see {@link #canResolve}; there is no workspace-membership
 * check on the admin branch). Since a chat can be shared, the two are no longer necessarily the same person: a
 * participant's turn can raise an approval the owner resolves.
 * </p>
 *
 * <p>
 * <b>Which identity is used where.</b> Tool RESOLUTION and the continuation turn follow the REQUESTER — the tool
 * population, asset-file scoping and {@link AiHubStateKeys#AUTHENTICATED_USER_ID} must match the turn being continued,
 * or the owner's user-global connectors, MCP servers and skills leak into a participant's turn (and an admin resolver
 * cannot find an owner-user-global tool at all). EXECUTION follows a two-way split decided by which of the three
 * populations {@link #findCallback} resolved the callback from: a CHAT-SCOPED attached tool runs as the chat's OWNER,
 * because the binding, its pinned connection and the resources behind it are the owner's; a CATALOG or a PINNED tool —
 * globally registered and owned by nobody — runs as the REQUESTER, whose instruction it serves.
 * </p>
 *
 * <p>
 * Neither branch is the RESOLVER. The approver is recorded ({@code decidedByUserId} and the audit event) and never
 * impersonated, so approving a call never lends the approver's authority to it. Execution runs inside
 * {@link SecurityContextRehydrator#withUserSecurityContext}, which resolves that identity's own login and authorities,
 * so both the executed tool's {@code @PreAuthorize} facades and the {@link AgentToolInvocationContext} the call carries
 * name the same person. When the identity cannot be established — no rehydrator on the classpath, or the user no longer
 * exists — the row is marked {@code FAILED} rather than falling back to the resolver's ambient context.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubToolApprovalFacadeImpl implements AiHubToolApprovalFacade {

    private static final Logger log = LoggerFactory.getLogger(AiHubToolApprovalFacadeImpl.class);

    private final AiHubChatAccessPolicy accessPolicy;
    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final UserService userService;
    private final AiHubChatStreamer chatStreamer;
    private final InFlightAiHubRunRegistry inFlightRunRegistry;
    private final AiHubChatBindingToolCallbackResolver chatBindingResolver;
    private final AiHubGlobalToolCatalog askGlobalToolCatalog;
    private final AiHubGlobalToolCatalog buildGlobalToolCatalog;
    private final AiHubSpringAIAgent askSpringAIAgent;
    private final AiHubSpringAIAgent buildSpringAIAgent;
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
        AiHubChatAccessPolicy accessPolicy, AiHubToolApprovalService approvalService, AiHubChatService chatService,
        UserService userService, AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry,
        AiHubChatBindingToolCallbackResolver chatBindingResolver,
        @Qualifier("aiHubAskGlobalToolCatalog") AiHubGlobalToolCatalog askGlobalToolCatalog,
        @Qualifier("aiHubBuildGlobalToolCatalog") AiHubGlobalToolCatalog buildGlobalToolCatalog,
        @Qualifier("aiHubAskSpringAIAgent") AiHubSpringAIAgent askSpringAIAgent,
        @Qualifier("aiHubBuildSpringAIAgent") AiHubSpringAIAgent buildSpringAIAgent,
        List<LocalAgent> localAgents, @Nullable SecurityContextRehydrator securityContextRehydrator,
        @Nullable AiHubAuditPublisher auditPublisher, @Nullable ToolExecutionRecorder toolExecutionRecorder,
        AiHubToolApprovalMetrics metrics, PlatformTransactionManager transactionManager) {

        this(
            accessPolicy, approvalService, chatService, userService, chatStreamer, inFlightRunRegistry,
            chatBindingResolver, askGlobalToolCatalog, buildGlobalToolCatalog, askSpringAIAgent, buildSpringAIAgent,
            localAgents, securityContextRehydrator, auditPublisher, toolExecutionRecorder, metrics,
            transactionManager, Clock.systemUTC());
    }

    @SuppressFBWarnings("EI")
    AiHubToolApprovalFacadeImpl(
        AiHubChatAccessPolicy accessPolicy, AiHubToolApprovalService approvalService, AiHubChatService chatService,
        UserService userService, AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry,
        AiHubChatBindingToolCallbackResolver chatBindingResolver, AiHubGlobalToolCatalog askGlobalToolCatalog,
        AiHubGlobalToolCatalog buildGlobalToolCatalog, AiHubSpringAIAgent askSpringAIAgent,
        AiHubSpringAIAgent buildSpringAIAgent, List<LocalAgent> localAgents,
        @Nullable SecurityContextRehydrator securityContextRehydrator, @Nullable AiHubAuditPublisher auditPublisher,
        @Nullable ToolExecutionRecorder toolExecutionRecorder, AiHubToolApprovalMetrics metrics,
        PlatformTransactionManager transactionManager, Clock clock) {

        this.accessPolicy = accessPolicy;
        this.approvalService = approvalService;
        this.chatService = chatService;
        this.userService = userService;
        this.chatStreamer = chatStreamer;
        this.inFlightRunRegistry = inFlightRunRegistry;
        this.chatBindingResolver = chatBindingResolver;
        this.askGlobalToolCatalog = askGlobalToolCatalog;
        this.buildGlobalToolCatalog = buildGlobalToolCatalog;
        this.askSpringAIAgent = askSpringAIAgent;
        this.buildSpringAIAgent = buildSpringAIAgent;
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
     * {@code chatService.getById} is the authorization check: it requires the chat to belong to the requested workspace
     * and the caller to be able to VIEW it ({@link AiHubChatAccessPolicy#canView}), throwing {@link NotFoundException}
     * otherwise. Viewability, not ownership — the approval card renders for everyone the chat is shared with, so every
     * viewer needs to read the row. Only {@link #resolve} is owner-or-admin, through {@link #canResolve}. There is no
     * separate workspace-role check, because a tool approval belongs to one chat, not to the workspace at large.
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

        String toolResult = approved ? executeAndPersistOutcome(decided, chat) : null;

        String runId = startContinuation(decided, chat, workspaceId, toolResult);

        return new Resolution(decided, runId != null, runId);
    }

    /**
     * Delegates to {@link AiHubChatAccessPolicy#canManage} rather than keeping a private copy of the same
     * owner-or-admin check: the chat owner, or any INSTANCE admin ({@code AuthorityConstants.ADMIN}), may resolve an
     * approval raised on that chat. Deliberately not a workspace-role {@code @PreAuthorize} — the approval belongs to
     * the chat, not the workspace's resource-visibility graph — but note the admin branch of {@code canManage} is also
     * NOT workspace-scoped: it checks only the instance-wide {@code ADMIN} authority, with no check that the admin
     * belongs to {@code chat}'s workspace. An instance admin can resolve an approval in a workspace they are not a
     * member of; routing through the shared policy means a future fix to that gap in {@code AiHubChatAccessPolicyImpl}
     * applies here too, rather than only where it is fixed first.
     */
    private boolean canResolve(AiHubChat chat, long userId) {
        return accessPolicy.canManage(chat, userId);
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
    private @Nullable String executeAndPersistOutcome(AiHubToolApproval decided, AiHubChat chat) {
        String toolResult = execute(decided, chat);

        if (decided.getStatus() == AiHubToolApproval.Status.FAILED) {
            approvalService.save(decided);
            metrics.record("failed");
        }

        return toolResult;
    }

    /**
     * Runs the approved tool call with the row's stored arguments, under the EXECUTION identity the population the
     * callback was resolved from selects — the chat's owner for a chat-scoped attached tool, the requester for a
     * catalog or pinned one; never the resolver (see the class Javadoc). Mutates {@code approval} to {@code FAILED}
     * (with {@link AiHubToolApproval#getExecutionError} set) when the tool cannot be found, that identity cannot be
     * established, or the call throws; the caller is responsible for persisting the mutated row.
     */
    private @Nullable String execute(AiHubToolApproval approval, AiHubChat chat) {
        Optional<ResolvedToolCallback> resolvedOptional = findCallback(approval, chat);

        if (resolvedOptional.isEmpty()) {
            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError("Tool " + approval.getToolName() + " is no longer available in this chat");

            return null;
        }

        ResolvedToolCallback resolved = resolvedOptional.get();
        long executionUserId = executionUserId(approval, chat, resolved.population());

        if (securityContextRehydrator == null || userService.fetchUser(executionUserId)
            .isEmpty()) {

            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError(
                "Tool " + approval.getToolName() + " cannot run: the security context of user " + executionUserId
                    + " could not be established");

            return null;
        }

        return securityContextRehydrator.withUserSecurityContext(
            executionUserId, () -> call(approval, chat, resolved.callback(), executionUserId));
    }

    /**
     * The identity an approved call executes as. A CHAT_SCOPED callback came from the chat's own attached tools, whose
     * bindings, pinned connections and backing resources belong to the chat's owner, so it runs as the owner. A CATALOG
     * or PINNED callback is registered globally and belongs to nobody, so it runs as the requester — the person whose
     * instruction raised the call. The resolver is never an option: they decided the call, they did not make it.
     *
     * <p>
     * The two branches coincide whenever the requester IS the owner, which is every unshared chat, so this only
     * diverges for a participant's gated call in a shared chat.
     * </p>
     */
    private static long executionUserId(
        AiHubToolApproval approval, AiHubChat chat, ToolPopulation population) {

        return population == ToolPopulation.CHAT_SCOPED ? chat.getUserId() : approval.getRequestedByUserId();
    }

    /**
     * Invokes the resolved callback. Called inside {@link SecurityContextRehydrator#withUserSecurityContext}, which is
     * why {@link #toolContextFor} can capture the ambient {@code Authentication} and get the execution identity's
     * rather than the resolver's.
     */
    private @Nullable String call(
        AiHubToolApproval approval, AiHubChat chat, ToolCallback resolvedCallback, long executionUserId) {

        ToolCallback callback = AiHubToolCallbackWrappers.wrap(resolvedCallback, securityContextRehydrator);
        ToolContext toolContext = new ToolContext(toolContextFor(approval, chat, executionUserId));

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
     * Hub rather than derived from a component, so {@code CONTRIBUTED} is the nearest match.
     */
    private static ToolExecutionKind toolExecutionKind(AiHubToolApproval approval) {
        return approval.getToolKind() == AiHubToolApproval.ToolKind.COMPONENT
            ? ToolExecutionKind.COMPONENT
            : ToolExecutionKind.CONTRIBUTED;
    }

    /**
     * Resolves the {@link ToolCallback} the original gated call would have invoked, searching the same three
     * populations {@link AiHubToolCallbackWrappers#wrap} gates in the first place: the chat's own attached tools, the
     * global catalog matching the row's {@code mode}, and finally the mode's agent's PINNED static tool callbacks — a
     * tool added via {@code toolCallbacks(...)} on the agent's builder (e.g. the BUILD agent's deployment
     * delete/rollback/promote tools) never appears in either of the first two populations, since it is registered once
     * at builder time rather than resolved per chat or per catalog lookup. Empty when the tool is in none of the three
     * (e.g. a connector was detached, or a catalog tool was removed between the request and the decision).
     *
     * <p>
     * Reports WHICH population answered alongside the callback, because that is what {@link #executionUserId} needs:
     * the populations are the only thing that distinguishes a chat-scoped attached tool from a globally registered one.
     * The row's own {@code toolKind} is not a substitute — it records only whether the gated callback was a
     * {@code ClusterElementToolCallback}, so the owner's user-global MCP-server and skill tools, which reach this
     * method through the chat-bound population, are stored as {@code CATALOG}.
     * </p>
     *
     * <p>
     * The invocation context carries {@code requestedByUserId} as its {@code userId} — the requester, NOT the resolver.
     * That is what reproduces the population the original gated call saw: the resolver is the owner or an instance
     * admin, so resolving as the resolver would compute {@code senderIsOwner = Objects.equals(ownerUserId, userId)}
     * differently from the turn being continued — un-gating the owner's user-global connectors, MCP servers and skills
     * for a participant's turn when the owner resolves, and failing to find an owner-user-global tool at all when an
     * admin does.
     * </p>
     */
    private Optional<ResolvedToolCallback> findCallback(AiHubToolApproval approval, AiHubChat chat) {
        List<ToolCallback> chatCallbacks = chatBindingResolver.resolve(
            new AiHubToolInvocationContext(
                chat.getWorkspaceId(), approval.getRequestedByUserId(), Source.AI_HUB.toAgentSourceOrdinal(), null,
                (long) approval.getEnvironment(), chat.getThreadId(), chat.getUserId()));

        Optional<ToolCallback> chatCallback = findByName(chatCallbacks, approval.getToolName());

        if (chatCallback.isPresent()) {
            return chatCallback.map(callback -> new ResolvedToolCallback(callback, ToolPopulation.CHAT_SCOPED));
        }

        boolean build = "BUILD".equals(approval.getMode());
        AiHubGlobalToolCatalog catalog = build ? buildGlobalToolCatalog : askGlobalToolCatalog;
        Optional<ToolCallback> catalogCallback = findByName(catalog.toolCallbacks(), approval.getToolName());

        if (catalogCallback.isPresent()) {
            return catalogCallback.map(callback -> new ResolvedToolCallback(callback, ToolPopulation.CATALOG));
        }

        AiHubSpringAIAgent pinnedAgent = build ? buildSpringAIAgent : askSpringAIAgent;

        return findByName(pinnedAgent.pinnedToolCallbacks(), approval.getToolName())
            .map(callback -> new ResolvedToolCallback(callback, ToolPopulation.PINNED));
    }

    /**
     * Which of the three populations {@link #findCallback} searches answered. {@code CHAT_SCOPED} is the chat's own
     * attached tools, {@code CATALOG} the searchable global catalog for the row's mode, {@code PINNED} the mode's
     * agent's always-on tool callbacks.
     */
    private enum ToolPopulation {
        CHAT_SCOPED, CATALOG, PINNED
    }

    private record ResolvedToolCallback(ToolCallback callback, ToolPopulation population) {
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
     *
     * <p>
     * The two maps can carry different user ids, and each carries the one its consumers need.
     * {@link AiHubToolInvocationContext}'s {@code userId} is the requester, so the executed call sees the same tool
     * population and the same asset-file scoping as the turn it belongs to. {@link AgentToolInvocationContext}'s
     * {@code userId} is the EXECUTION identity, and travels beside an {@code authentication} captured from the ambient
     * context — which this method is called inside {@link SecurityContextRehydrator#withUserSecurityContext} precisely
     * so that it names that same person. The pair therefore never names two people, and what
     * {@link com.bytechef.ai.copilot.tool.RehydrateContextToolCallback} restores on a worker thread is the identity the
     * call is authorized as, not whoever approved it.
     * </p>
     */
    private Map<String, Object> toolContextFor(AiHubToolApproval approval, AiHubChat chat, long executionUserId) {
        Map<String, Object> toolContext = new HashMap<>(
            new AiHubToolInvocationContext(
                chat.getWorkspaceId(), approval.getRequestedByUserId(), Source.AI_HUB.toAgentSourceOrdinal(), null,
                (long) approval.getEnvironment(), chat.getThreadId(), chat.getUserId())
                    .toToolContext());

        toolContext.putAll(
            AgentToolInvocationContext.builder()
                .workspaceId(chat.getWorkspaceId())
                .userId(executionUserId)
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
     *
     * <p>
     * The run state's {@code userId} is the requester, not the resolver: the continuation is still driven by the
     * requester's original instruction and can call further tools, so {@link AiHubStateKeys#AUTHENTICATED_USER_ID} must
     * stay what that key documents itself to be — the current sender — rather than becoming whoever happened to
     * approve. The chat's owner travels separately as {@link AiHubStateKeys#VERIFIED_OWNER_USER_ID}, and the resolver's
     * own identity is on the row ({@code decidedByUserId}) and in the audit event.
     * </p>
     *
     * <p>
     * The dispatch runs inside {@link SecurityContextRehydrator#withUserSecurityContext} for that same requester, so
     * the ambient context the run starts under is theirs and not the approver's. Scope of that, precisely: the agent's
     * individual tool calls were already the requester's — {@code AiHubSpringAIAgent.toolContext} carries the run
     * state's {@code AUTHENTICATED_USER_ID} as the {@link AgentToolInvocationContext} {@code userId}, which
     * {@code RehydrateContextToolCallback} turns into a per-call security context on the worker thread. What this
     * covers is everything else the run touches on the dispatching thread before the first scheduler hop, which
     * otherwise inherits the resolver's context. It cannot reach work a Reactor scheduler picks up later; identity that
     * must survive a hop travels in the run state and the tool context, as it does everywhere else here.
     * </p>
     *
     * <p>
     * Fail-closed: when that identity cannot be established the continuation is not started at all, rather than started
     * under the approver's. A whole turn can call many tools, so the blast radius of guessing is much wider than for
     * one stored call — and no continuation is an outcome this method already produces (a run in flight, no registered
     * agent variant) and every caller already handles, so the cost is a status message, not the decision or the tool
     * result, both of which are committed by then.
     * </p>
     *
     * <p>
     * {@code workspaceId} arrives as a primitive from {@link #resolve}'s own parameter rather than being read back off
     * {@code chat}: {@link AiHubChat#getWorkspaceId()} is a {@code @Nullable Long}, and unboxing it here would be a
     * potential NPE that nothing local rules out. {@link #resolve} has already established the two are the same value —
     * a chat whose workspace differs from the requested one is rejected as not-found before this point — so taking the
     * parameter makes non-nullness structural instead of an invariant held one method away.
     * </p>
     */
    private @Nullable String startContinuation(
        AiHubToolApproval approval, AiHubChat chat, long workspaceId, @Nullable String toolResult) {

        if (inFlightRunRegistry.isInFlight(chat.getThreadId())) {
            return null;
        }

        LocalAgent localAgent = localAgentMap.get("BUILD".equals(approval.getMode()) ? "ai_hub_build" : "ai_hub_ask");

        if (localAgent == null) {
            return null;
        }

        long requesterUserId = approval.getRequestedByUserId();

        if (securityContextRehydrator == null || userService.fetchUser(requesterUserId)
            .isEmpty()) {

            log.warn(
                "Not starting the continuation turn for approval id={}: the security context of requesting user {}"
                    + " could not be established, and the turn must not run as the approver",
                approval.getId(), requesterUserId);

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
            state, requesterUserId, workspaceId, chat.getThreadId(), approval.getEnvironment(),
            TenantContext.getCurrentTenantId(), chat.getUserId());

        AgUiParameters parameters = new AgUiParameters();

        parameters.setThreadId(chat.getThreadId());
        parameters.setRunId(runId);
        parameters.setMessages(List.of(userMessage));
        parameters.setState(state);

        return securityContextRehydrator.withUserSecurityContext(requesterUserId, () -> {
            chatStreamer.runAgent(localAgent, parameters, chat.getThreadId());

            return runId;
        });
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

        String comment = approval.getComment();

        if (comment != null && !comment.isBlank()) {
            text.append("\nComment: ")
                .append(comment);
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
