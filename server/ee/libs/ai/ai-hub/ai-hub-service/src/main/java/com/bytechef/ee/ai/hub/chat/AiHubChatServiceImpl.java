/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import com.bytechef.ai.agent.tool.AgentTypeRegistry;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.agent.WorkflowChatJobRegistry;
import com.bytechef.ee.ai.hub.approval.AiHubToolApprovalService;
import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatRepository;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatTurnRepository;
import com.bytechef.ee.ai.hub.exception.ConflictException;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.memory.AiHubSessionMemory;
import com.bytechef.ee.ai.hub.subagent.SubAgentSessionMemoryContributor;
import com.bytechef.ee.ai.hub.toolsearch.ToolSearchCatalogFeeder;
import com.bytechef.ee.ai.hub.util.EnumOrdinals;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.platform.ai.sensitivedata.PiiTokenSessionStore;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.security.domain.ResourceVisibility;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Default implementation of {@link AiHubChatService}.
 *
 * <p>
 * Message contents are stored and owned by Spring AI's session store, keyed by {@code AiHubChat.threadId} as the
 * session id. This service reaches them through {@code AiHubSessionMemory}: reads via {@code SessionService}, and the
 * few write paths ({@code appendAssistantMessage}, {@code truncateMessagesFrom}, delete cleanup) via
 * {@code SessionRepository}. The agent's own turns are written by the session memory advisor, not here.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
@Transactional
public class AiHubChatServiceImpl implements AiHubChatService {

    private static final Logger log = LoggerFactory.getLogger(AiHubChatServiceImpl.class);

    private static final int LIST_LIMIT = 100;

    /**
     * Hard upper bound on the number of messages returned by {@link #loadMessages}. Without this cap a long-running
     * chat could accumulate thousands of messages and force the controller to materialise them all in memory; the UI
     * never needs more than the most recent slice.
     */
    private static final int MESSAGE_LIMIT = 500;

    private final AiHubChatRepository chatRepository;
    private final AiHubChatTurnRepository turnRepository;
    private final AiHubChatAccessPolicy accessPolicy;
    private final Clock clock;
    private final JobFacade jobFacade;
    private final WorkflowChatJobRegistry jobRegistry;
    private final InFlightAiHubRunRegistry inFlightRunRegistry;
    private final ObjectProvider<ToolSearchCatalogFeeder> toolSearchCatalogFeederProvider;
    private final ObjectProvider<AiHubSessionMemory> aiHubSessionMemoryProvider;
    private final @Nullable AiHubAuditPublisher auditPublisher;
    private final @Nullable ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider;
    private final @Nullable ObjectProvider<ResourceGrantService> resourceGrantServiceProvider;
    private final @Nullable PiiTokenSessionStore piiTokenSessionStore;

    /**
     * {@code toolApprovalServiceProvider} and {@code resourceGrantServiceProvider} are nullable at both levels — the
     * {@link ObjectProvider} itself, so hand-built test constructors predating each feature keep compiling with a plain
     * {@code null} argument, and what each yields, so a deployment where the tool approval module is disabled, or a CE
     * deployment with no {@code ResourceGrantService} bean, still gets a working {@link AiHubChatServiceImpl}.
     * {@link #deleteApprovals} and {@link #deleteGrants} each guard on both.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiHubChatServiceImpl(
        AiHubChatRepository chatRepository, AiHubChatTurnRepository turnRepository,
        AiHubChatAccessPolicy accessPolicy, JobFacade jobFacade,
        WorkflowChatJobRegistry jobRegistry, InFlightAiHubRunRegistry inFlightRunRegistry,
        ObjectProvider<ToolSearchCatalogFeeder> toolSearchCatalogFeederProvider,
        ObjectProvider<AiHubSessionMemory> aiHubSessionMemoryProvider,
        @Nullable AiHubAuditPublisher auditPublisher,
        @Nullable ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider,
        @Nullable ObjectProvider<ResourceGrantService> resourceGrantServiceProvider,
        @Nullable PiiTokenSessionStore piiTokenSessionStore) {

        this.chatRepository = chatRepository;
        this.turnRepository = turnRepository;
        this.accessPolicy = accessPolicy;
        this.clock = Clock.systemUTC();
        this.jobFacade = jobFacade;
        this.jobRegistry = jobRegistry;
        this.inFlightRunRegistry = inFlightRunRegistry;
        // ObjectProvider so a deployment running with command center disabled (or running ahead of the tool-search
        // advisor configuration) still gets a working AiHubChatService — the per-chat tool index just isn't cleared
        // on delete, and search falls back to the workspace-wide catalog.
        this.toolSearchCatalogFeederProvider = toolSearchCatalogFeederProvider;
        this.aiHubSessionMemoryProvider = aiHubSessionMemoryProvider;
        // Nullable so unit tests constructing this impl without an audit publisher (and any deployment that
        // doesn't supply the EE bean) degrade to a no-op; publishChatCreated/publishChatDeleted guard on null.
        this.auditPublisher = auditPublisher;
        this.toolApprovalServiceProvider = toolApprovalServiceProvider;
        this.resourceGrantServiceProvider = resourceGrantServiceProvider;

        // Nullable for the same reason: a deployment without the guardrails module still gets a working
        // AiHubChatService, just without conversation-scoped PII tokens to evict.
        this.piiTokenSessionStore = piiTokenSessionStore;
    }

    private void publishChatCreated(AiHubChat chat) {
        if (auditPublisher == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();

        data.put("chatId", chat.getId());
        data.put("threadId", chat.getThreadId());

        if (chat.getKind() != null) {
            data.put("kind", chat.getKind()
                .name());
        }

        if (chat.getEnvironment() != null) {
            data.put("environment", chat.getEnvironment()
                .name());
        }

        auditPublisher.publish(AiHubAuditEvent.AI_HUB_CHAT_CREATED, data);
    }

    private void publishChatDeleted(AiHubChat chat) {
        if (auditPublisher == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();

        data.put("chatId", chat.getId());
        data.put("threadId", chat.getThreadId());

        auditPublisher.publish(AiHubAuditEvent.AI_HUB_CHAT_DELETED, data);
    }

    /**
     * Stamps a freshly-built chat with its owning workspace and saves it. The workspace is a column on the row, so
     * there is nothing else to write.
     */
    private AiHubChat saveNew(AiHubChat chat, long workspaceId) {
        chat.setWorkspaceId(workspaceId);

        return chatRepository.save(chat);
    }

    /**
     * Returns true when {@code workspaceId} owns the given chat, per its {@code workspace_id} column. A chat with a
     * null workspace is owned by nobody, so every check fails closed.
     */
    private static boolean workspaceOwnsChat(long workspaceId, AiHubChat chat) {
        return Objects.equals(chat.getWorkspaceId(), workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getWorkspaceId(long chatId) {
        // A null workspace_id is the same data-integrity state a missing membership row used to represent: the chat
        // is unreachable through every workspace-scoped path, so fail loudly rather than return a sentinel.
        return chatRepository.findById(chatId)
            .map(AiHubChat::getWorkspaceId)
            .orElseThrow(() -> new NotFoundException("No workspace for ai_hub_chat id=" + chatId));
    }

    @Override
    @Transactional(readOnly = true)
    public String getThreadId(long chatId) {
        return chatRepository.findById(chatId)
            .orElseThrow(() -> new NotFoundException("No ai_hub_chat row for id=" + chatId))
            .getThreadId();
    }

    @Override
    public AiHubChat create(long workspaceId, long userId, int environment, String threadId) {
        // thread_id has a global UNIQUE constraint per the Liquibase migration. Look up by threadId only (NOT
        // (threadId, userId)) so a cross-user collision surfaces as an explicit ConflictException instead of falling
        // through to save() and triggering a DataIntegrityViolationException → default 500. The 500 path also leaks a
        // probe oracle: the constraint violation reveals that another user has claimed the threadId.
        Optional<AiHubChat> existing = chatRepository.findByThreadId(threadId);

        if (existing.isPresent()) {
            AiHubChat found = existing.get();

            if (found.getUserId() == userId && workspaceOwnsChat(workspaceId, found)) {
                // Idempotent return — but we deliberately do NOT update `environment` on the existing row. A
                // chat's environment is an immutable property of its origin: re-running create() from a
                // different environment must not retroactively reassign the row, otherwise audit + usage analytics
                // (which group by environment) would silently lose attribution for the chat's earlier turns.
                return found;
            }

            // Same wording for cross-user and cross-workspace collisions so the response does not let the caller
            // distinguish "owned by another user" from "owned by you in another workspace".
            throw new ConflictException("AiHubChat %s already exists".formatted(threadId));
        }

        LocalDateTime now = LocalDateTime.now(clock);

        AiHubChat chat = new AiHubChat(userId);

        chat.setThreadId(threadId);
        chat.setStatus(AiHubChatStatus.ACTIVE);
        chat.setMessageCount(0);
        chat.setEnvironment(EnumOrdinals.fromOrdinal(environment, Environment.class));
        chat.setCreatedAt(now);
        chat.setUpdatedAt(now);

        AiHubChat saved;

        try {
            saved = saveNew(chat, workspaceId);
        } catch (DataIntegrityViolationException exception) {
            // TOCTOU race: another concurrent request inserted the same threadId between the findByThreadId() check
            // above and this save(). At READ_COMMITTED there is no SELECT FOR UPDATE in the lookup, so the unique
            // constraint is the only thing that catches the duplicate. Re-throw with the same generic ConflictException
            // wording the existing-row branch above uses so the response is indistinguishable from a normal collision —
            // does not leak whether the colliding row is owned by the caller or another user.
            throw new ConflictException("AiHubChat %s already exists".formatted(threadId), exception);
        }

        publishChatCreated(saved);

        return saved;
    }

    @Override
    public AiHubChat createWorkflowChat(
        long workspaceId, long userId, int environment, String workflowExecutionId, long projectDeploymentId,
        @Nullable String title) {

        return createWebhookBridgedChat(
            AiHubChatKind.WORKFLOW_CHAT, workspaceId, userId, environment, workflowExecutionId, projectDeploymentId,
            title);
    }

    @Override
    public AiHubChat createAgentChat(
        long workspaceId, long userId, int environment, String workflowExecutionId, long projectDeploymentId,
        @Nullable String title) {

        return createWebhookBridgedChat(
            AiHubChatKind.AGENT_CHAT, workspaceId, userId, environment, workflowExecutionId, projectDeploymentId,
            title);
    }

    /**
     * Shared insert for the two webhook-bridged kinds. They differ only in the persisted {@code kind} — an agent chat
     * binds to the workflow inside the agent's hidden {@code __AI_AGENT__} project — so the row construction, the
     * always-new threadId, and the title handling all live here rather than being duplicated per kind.
     */
    private AiHubChat createWebhookBridgedChat(
        AiHubChatKind kind, long workspaceId, long userId, int environment, String workflowExecutionId,
        long projectDeploymentId, @Nullable String title) {

        // Always-new semantics (per user request, May 2026): every click on a workflow or agent row starts a fresh
        // chat rather than restoring a prior thread. Past chats remain accessible via the chats list — they're just
        // not the default landing target. The threadId is a plain UUID so session-store events are isolated per
        // chat, not shared across a (user, workflow) tuple. The kind column on this row is the authoritative
        // discriminator for routing; nothing parses the threadId.
        LocalDateTime now = LocalDateTime.now(clock);

        AiHubChat chat = new AiHubChat(userId);

        chat.setThreadId(UUID.randomUUID()
            .toString());
        chat.setStatus(AiHubChatStatus.ACTIVE);
        chat.setMessageCount(0);
        chat.setKind(kind);
        chat.setWorkflowExecutionId(workflowExecutionId);
        chat.setProjectDeploymentId(projectDeploymentId);
        chat.setEnvironment(EnumOrdinals.fromOrdinal(environment, Environment.class));
        chat.setCreatedAt(now);
        chat.setUpdatedAt(now);

        // Bridged chats don't fire the LLM-driven generateAiHubChatTitle loop (the bridge bypasses the LLM agent),
        // so without an initial title the row would render as "New AiHubChat" forever. The client passes
        // `${projectName} — ${workflowLabel}` for a workflow chat and the agent's title for an agent chat;
        // null/blank skips this and falls back to the UI default.
        if (title != null && !title.isBlank()) {
            chat.setTitle(title);
        }

        AiHubChat saved = saveNew(chat, workspaceId);

        publishChatCreated(saved);

        return saved;
    }

    private void scheduleChatToolSessionClear(long chatId) {
        if (toolSearchCatalogFeederProvider == null) {
            return;
        }

        ToolSearchCatalogFeeder feeder = toolSearchCatalogFeederProvider.getIfAvailable();

        if (feeder == null) {
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    clearIndex(feeder, chatId);
                }
            });
        } else {
            clearIndex(feeder, chatId);
        }
    }

    private static void clearIndex(ToolSearchCatalogFeeder feeder, long chatId) {
        try {
            feeder.clearChatSession(chatId);
        } catch (RuntimeException exception) {
            // The chat row is already gone at this point — a stale per-chat session in the vector
            // store is benign (it just keeps a few orphaned rows that never match a future search). Log and move
            // on rather than propagating to the caller, who has no recovery path anyway.
            log.warn("Failed to clear tool search session for deleted chat {}", chatId, exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiHubChat>
        list(long workspaceId, long userId, int environment, @Nullable AiHubChatStatus status) {
        // The query already filters by workspaceId via the JOIN; the entity itself is workspace-agnostic and
        // callers needing the workspace for a chat ID query the relation explicitly.
        //
        // A null status means "all statuses" — the tool-binding lookup (removeAiHubChatTool) scans every chat
        // regardless of active/archived state. Without this branch the status.ordinal() below NPEs.
        if (status == null) {
            return chatRepository.findByWorkspaceIdAndUserIdAndEnvironmentOrderByUpdatedAtDesc(
                workspaceId, userId, environment, LIST_LIMIT);
        }

        return chatRepository.findByWorkspaceIdAndUserIdAndEnvironmentAndStatusOrderByUpdatedAtDesc(
            workspaceId, userId, environment, status.ordinal(), LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiHubChat> listSharedWithMe(long workspaceId, long userId, int environment) {
        int active = AiHubChatStatus.ACTIVE.ordinal();
        List<AiHubChat> shared = new ArrayList<>(
            chatRepository.findSharedByReach(
                workspaceId, userId, environment, active, ResourceVisibility.WORKSPACE.ordinal(), LIST_LIMIT));
        ResourceGrantService resourceGrantService =
            resourceGrantServiceProvider == null ? null : resourceGrantServiceProvider.getIfAvailable();

        if (resourceGrantService != null) {
            List<AiHubChat> candidates = chatRepository.findPrivateCandidates(
                workspaceId, userId, environment, active, ResourceVisibility.PRIVATE.ordinal(), LIST_LIMIT * 5);
            Set<Long> grantedIds = resourceGrantService.filterGrantedResourceIds(
                AiHubChatVisibilityPolicy.RESOURCE_TYPE, userId,
                candidates.stream()
                    .map(AiHubChat::getId)
                    .toList());

            for (AiHubChat candidate : candidates) {
                if (grantedIds.contains(candidate.getId())) {
                    shared.add(candidate);
                }
            }
        }

        shared.sort(Comparator.comparing(AiHubChat::getUpdatedAt)
            .reversed());

        return shared.size() > LIST_LIMIT ? shared.subList(0, LIST_LIMIT) : shared;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiHubChatMessage> loadMessages(
        long chatId, long requesterWorkspaceId, long requesterUserId) {

        AiHubChat chat = loadViewable(chatId, requesterWorkspaceId, requesterUserId);

        AiHubSessionMemory sessionMemory = aiHubSessionMemoryProvider.getIfAvailable();

        if (sessionMemory == null) {
            return List.of();
        }

        List<SessionEvent> allEvents = sessionMemory.sessionService()
            .getEvents(chat.getThreadId());

        List<AiHubChatTurn> turns = turnRepository.findAllByChatIdOrderByCreatedDateAsc(chat.getId());

        // Visible rows stay exactly the rows truncateMessagesFrom indexes over. The tool activity between two
        // visible rows (tool-calling assistant turns with blank text, tool responses) is attached to the PRECEDING
        // visible row as a JSON blob instead of extra rows, so the client can rebuild tool cards on reload without
        // shifting the truncation indexes. Activity before the first visible row attaches to that first row.
        List<SessionEvent> visibleEvents = new ArrayList<>();
        List<List<Map<String, String>>> toolEventsPerRow = new ArrayList<>();
        List<Map<String, String>> leadingToolEvents = new ArrayList<>();

        for (SessionEvent event : allEvents) {
            if (isVisibleConversationEvent(event)) {
                if (visibleEvents.size() >= MESSAGE_LIMIT) {
                    break;
                }

                visibleEvents.add(event);

                List<Map<String, String>> ownToolEvents = new ArrayList<>();

                // A visible assistant row can itself carry tool calls alongside its text.
                appendToolEvents(ownToolEvents, event);

                toolEventsPerRow.add(ownToolEvents);
            } else {
                appendToolEvents(
                    visibleEvents.isEmpty() ? leadingToolEvents : toolEventsPerRow.get(visibleEvents.size() - 1),
                    event);
            }
        }

        if (!leadingToolEvents.isEmpty() && !toolEventsPerRow.isEmpty()) {
            toolEventsPerRow.get(0)
                .addAll(0, leadingToolEvents);
        }

        long totalUserEventCount = allEvents.stream()
            .filter(event -> isVisibleConversationEvent(event) && event.getMessageType() == MessageType.USER)
            .count();

        // The zip is positional, so it is only sound when turn rows and USER events correspond one-for-one. A
        // surplus of turn rows (a phantom run, or stale rows left by a truncation whose CAS committed against a
        // non-relational session store while the ambient transaction rolled back) shifts every position after it.
        // A DEFICIT shifts them just as badly and is the more reachable direction: recordTurn has one caller, the
        // REST dispatch path, while USER session events are also written by the channel/webhook path, which records
        // no turn rows at all. A channel-born chat with N Slack USER events and zero turn rows that is later shared
        // at PARTICIPATE accumulates 2 turn rows against N+2 USER events, and a <= test would zip those two rows
        // onto Slack messages 1 and 2 -- naming the wrong people the moment a second distinct author makes the
        // client render labels at all. Equality degrades the whole transcript to unknown authorship instead.
        boolean reliableTurnAttribution = turns.size() == totalUserEventCount;

        List<AiHubChatMessage> messages = new ArrayList<>();
        int userEventIndex = 0;

        for (int i = 0; i < visibleEvents.size(); i++) {
            SessionEvent event = visibleEvents.get(i);
            List<Map<String, String>> toolEvents = toolEventsPerRow.get(i);
            Long authorUserId = null;

            if (event.getMessageType() == MessageType.USER) {
                if (reliableTurnAttribution && userEventIndex < turns.size()) {
                    authorUserId = turns.get(userEventIndex)
                        .getUserId();
                }

                userEventIndex++;
            }

            messages.add(new AiHubChatMessage(
                event.getMessageType()
                    .name(),
                event.getMessage()
                    .getText(),
                event.getTimestamp(),
                toolEvents.isEmpty() ? null : JsonUtils.write(toolEvents),
                authorUserId));
        }

        return messages;
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChatTranscriptSummary summarizeTranscript(
        long chatId, long requesterWorkspaceId, long requesterUserId) {

        AiHubChat chat = loadViewable(chatId, requesterWorkspaceId, requesterUserId);

        AiHubSessionMemory sessionMemory = aiHubSessionMemoryProvider.getIfAvailable();

        if (sessionMemory == null) {
            return new AiHubChatTranscriptSummary(0, null);
        }

        List<SessionEvent> allEvents = sessionMemory.sessionService()
            .getEvents(chat.getThreadId());

        // Uncapped on purpose, unlike loadMessages: this walks to the end of the transcript because the LAST visible
        // message is the whole point, and it accumulates one counter and one string rather than MESSAGE_LIMIT rows
        // with their tool-event JSON, so the cap that bounds loadMessages' memory has nothing to bound here.
        int messageCount = 0;
        String lastMessageContent = null;

        for (SessionEvent event : allEvents) {
            if (isVisibleConversationEvent(event)) {
                messageCount++;

                Message message = event.getMessage();

                lastMessageContent = message.getText();
            }
        }

        return new AiHubChatTranscriptSummary(messageCount, lastMessageContent);
    }

    private static void appendToolEvents(List<Map<String, String>> target, SessionEvent event) {
        Message message = event.getMessage();

        if (message instanceof AssistantMessage assistantMessage) {
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                target.add(Map.of(
                    "arguments", Objects.requireNonNullElse(toolCall.arguments(), ""),
                    "id", Objects.requireNonNullElse(toolCall.id(), ""),
                    "kind", "call",
                    "name", Objects.requireNonNullElse(toolCall.name(), "")));
            }
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                target.add(Map.of(
                    "id", Objects.requireNonNullElse(toolResponse.id(), ""),
                    "kind", "result",
                    "name", Objects.requireNonNullElse(toolResponse.name(), ""),
                    "response", Objects.requireNonNullElse(toolResponse.responseData(), "")));
            }
        }
    }

    private static boolean isVisibleConversationEvent(SessionEvent event) {
        Message message = event.getMessage();

        if (message == null
            || (event.getMessageType() != MessageType.USER && event.getMessageType() != MessageType.ASSISTANT)) {

            return false;
        }

        String text = message.getText();

        return text != null && !text.isBlank();
    }

    @Override
    public AiHubChat patch(
        long chatId, long requesterWorkspaceId, long requesterUserId,
        AiHubChatPatch chatPatch) {

        AiHubChat chat =
            loadManageable(chatId, requesterWorkspaceId, requesterUserId);

        if (chatPatch.title() != null) {
            chat.setTitle(chatPatch.title());

            // Lock the title against further automatic regeneration. The patch path is invoked from both the
            // user-rename surface AND the LLM-generated title write — both should result in the title sticking,
            // so the regeneration fire-on-every-turn loop in generateAiHubChatTitle stops calling the
            // LLM
            // for this row. Distinguishing user vs LLM at this layer would require a flag on
            // AiHubChatPatch
            // and per-caller propagation; not worth the surface for the same end-state.
            chat.setAutoTitled(false);
        }

        if (chatPatch.lastPreview() != null) {
            chat.setLastPreview(chatPatch.lastPreview());
        }

        Integer messageCount = chatPatch.messageCount();

        if (messageCount != null) {
            chat.setMessageCount(messageCount);
        }

        if (chatPatch.status() != null) {
            chat.setStatus(chatPatch.status());
        }

        chat.setUpdatedAt(LocalDateTime.now(clock));

        return chatRepository.save(chat);
    }

    @Override
    public AiHubChat patchSharing(long chatId, ResourceVisibility visibility, AiHubChatParticipation participation) {
        AiHubChat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new NotFoundException("AiHubChat not found"));

        chat.setVisibility(visibility);
        chat.setParticipation(participation);
        chat.setUpdatedAt(LocalDateTime.now(clock));

        return chatRepository.save(chat);
    }

    @Override
    public int bulkArchiveWorkflowChatAiHubChats(long workspaceId, long userId, int environment) {
        // Cap at LIST_LIMIT to bound memory + write traffic in pathological cases (a workspace with thousands of
        // stale workflow chats). Re-running the bulk archive picks up the overflow on the next call. The same cap
        // is used by the sidebar listing query so anything visible to the user is reachable in one bulk run.
        // Both webhook-bridged kinds are archived together: an agent chat is one of these rows too, and the
        // "clear out the chats bound to workflows" cleanup the user is asking for makes no distinction between a
        // workflow they wired themselves and one an agent generated.
        List<AiHubChat> activeWorkflowChats =
            chatRepository.findByWorkspaceIdAndUserIdAndEnvironmentAndKindInAndStatus(
                workspaceId, userId, environment,
                List.of(AiHubChatKind.WORKFLOW_CHAT.ordinal(), AiHubChatKind.AGENT_CHAT.ordinal()),
                AiHubChatStatus.ACTIVE.ordinal(), LIST_LIMIT);

        if (activeWorkflowChats.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int archived = 0;

        for (AiHubChat chat : activeWorkflowChats) {
            // Defensive: re-check ownership in the loop. The repository query already filters by userId AND
            // workspace_id; if a future change relaxes that filter (or someone discovers a query-level bypass), the
            // ownership check here is the authoritative gate. It reads the loaded row's own column, so it costs
            // nothing.
            if (chat.getUserId() != userId || !workspaceOwnsChat(workspaceId, chat)) {
                continue;
            }

            chat.setStatus(AiHubChatStatus.ARCHIVED);
            chat.setUpdatedAt(now);

            chatRepository.save(chat);

            archived++;
        }

        return archived;
    }

    @Override
    @Transactional
    public int truncateMessagesFrom(
        long chatId, long requesterWorkspaceId, long requesterUserId, int fromMessageIndex) {

        AiHubChat chat =
            loadParticipable(chatId, requesterWorkspaceId, requesterUserId);

        if (fromMessageIndex < 0) {
            // Defensive — a negative index would delete every row. The GraphQL surface should reject this earlier
            // but the service-layer check makes the contract explicit at the boundary.
            throw new IllegalArgumentException("fromMessageIndex must be non-negative; got " + fromMessageIndex);
        }

        AiHubSessionMemory sessionMemory = aiHubSessionMemoryProvider.getIfAvailable();

        if (sessionMemory == null) {
            return 0;
        }

        String threadId = chat.getThreadId();

        // Read the version BEFORE the events (the safe ordering for compactEvents' CAS): the version is then
        // guaranteed to be <= the version of the events read next, so a concurrent append between the two reads
        // fails the CAS instead of silently losing the appended event.
        long eventVersion = sessionMemory.sessionRepository()
            .getEventVersion(threadId);

        List<SessionEvent> allEvents = sessionMemory.sessionService()
            .getEvents(threadId);

        // Map the UI's message index (over the visible USER/ASSISTANT transcript that loadMessages returns) to a
        // position in the full event list, then drop that event and everything after it (interleaved tool events
        // included). Index past the end is a no-op rather than an error.
        int visibleCount = 0;
        int cutoffEventIndex = -1;

        for (int i = 0; i < allEvents.size(); i++) {
            if (isVisibleConversationEvent(allEvents.get(i))) {
                if (visibleCount == fromMessageIndex) {
                    cutoffEventIndex = i;

                    break;
                }

                visibleCount++;
            }
        }

        if (cutoffEventIndex < 0) {
            return 0;
        }

        int deleted = allEvents.size() - cutoffEventIndex;

        // Truncation keeps the prefix as the active window and archives nothing — dropped events are gone, not
        // compacted. A false return means a concurrent writer appended mid-truncate; surface it as a conflict
        // rather than silently discarding whichever side lost.
        boolean truncated = sessionMemory.sessionRepository()
            .compactEvents(threadId, List.of(), new ArrayList<>(allEvents.subList(0, cutoffEventIndex)), eventVersion);

        if (!truncated) {
            throw new ConflictException("The conversation changed while truncating; retry");
        }

        int keptUserEvents = 0;

        for (SessionEvent event : allEvents.subList(0, cutoffEventIndex)) {
            if (isVisibleConversationEvent(event) && event.getMessageType() == MessageType.USER) {
                keptUserEvents++;
            }
        }

        turnRepository.deleteFromOrdinal(chatId, keptUserEvents);

        // Bump the chat's updatedAt so the sidebar re-sorts to the top — same convention every other
        // mutation here uses. messageCount is not authoritative for chat-memory rows (it tracks user-perceived
        // turns, not chat-memory entries) so we leave it untouched.
        chat.setUpdatedAt(LocalDateTime.now(clock));
        chatRepository.save(chat);

        return deleted;
    }

    @Override
    public void appendAssistantMessage(long chatId, long requesterWorkspaceId, long requesterUserId, String content) {
        AiHubChat chat = loadParticipable(chatId, requesterWorkspaceId, requesterUserId);

        if (content == null || content.isBlank()) {
            return;
        }

        AiHubSessionMemory sessionMemory = aiHubSessionMemoryProvider.getIfAvailable();

        if (sessionMemory == null) {
            return;
        }

        String threadId = chat.getThreadId();

        sessionMemory.sessionRepository()
            .appendEvent(
                SessionEvent.builder()
                    .sessionId(threadId)
                    .message(new AssistantMessage(content))
                    .build());

        chat.setUpdatedAt(LocalDateTime.now(clock));
        chatRepository.save(chat);
    }

    @Override
    public boolean cancelWorkflowChatTurn(long chatId, long requesterWorkspaceId, long requesterUserId) {
        // Participability check, the same gate truncateMessagesFrom/appendAssistantMessage use.
        // We don't restrict to kind=WORKFLOW_CHAT here: the cancel-turn surface is workflow-chat-specific by name
        // and the GraphQL mutation is the only entry point, but if a future caller wires this against a standard
        // chat by mistake, the registry lookup returns null and we surface a clean false rather than
        // accidentally cancelling something else.
        AiHubChat chat =
            loadParticipable(chatId, requesterWorkspaceId, requesterUserId);

        Long jobId = jobRegistry.get(chat.getId());

        if (jobId == null) {
            // No in-flight job for this chat — either the turn finished between the user's stop click
            // and this handler firing, or there was never one running. Idempotent no-op; the client can render
            // "nothing to cancel" if it cares to distinguish.
            return false;
        }

        try {
            jobFacade.stopJob(jobId);
        } finally {
            // Clear the registry slot regardless of stopJob outcome. Even if the cancel failed (e.g. the job
            // already finished server-side between our get() and stopJob() calls), the entry is now stale and
            // a duplicate cancel attempt for this chat should be a no-op rather than reissuing
            // stopJob against the same id.
            jobRegistry.clear(chat.getId());
        }

        return true;
    }

    @Override
    public boolean cancelAiHubRun(long chatId, long requesterWorkspaceId, long requesterUserId, String runId) {
        // Sibling of cancelWorkflowChatTurn for the LLM-agent kinds (STANDARD / TASK). The agent
        // run is tracked in InFlightAiHubRunRegistry rather than the WorkflowChatJobRegistry, so cancellation
        // is a sink-completion signal — the AGUI agent.runAgent call doesn't expose a Disposable we can
        // dispose, but flipping the registry entry to terminated and completing the sink stops fan-out to
        // SSE subscribers and makes subsequent isInFlight probes return false. That's the contract the
        // client needs: a re-mount after the cancel sees a not-in-flight chat and stops showing the
        // streaming UI. Passing runId lets the registry tombstone a run that hasn't registered yet.
        AiHubChat chat = loadParticipable(chatId, requesterWorkspaceId, requesterUserId);

        return inFlightRunRegistry.cancel(chat.getThreadId(), runId);
    }

    @Override
    public void delete(long chatId, long requesterWorkspaceId, long requesterUserId) {
        AiHubChat chat =
            loadManageable(chatId, requesterWorkspaceId, requesterUserId);

        deleteApprovals(chatId);

        turnRepository.deleteAllByChatId(chatId);

        // Delete the chat row first inside the @Transactional boundary; the chat-memory rows are deleted
        // afterCommit. Mirrors AssetFileFacadeImpl.scheduleBlobDeleteAfterCommit: a rollback restores the chat
        // row consistently with no orphan messages possible from a rolled-back delete, and a post-commit chat-memory
        // failure leaves orphan messages (best-effort cleanup) instead of zombie chats the user can still see.
        chatRepository.delete(chat);

        deleteGrants(chatId);

        publishChatDeleted(chat);

        scheduleChatMemoryDeleteAfterCommit(chat.getThreadId());

        // Same after-commit semantics for the per-chat tool-search session. If the chat delete rolls
        // back, the session stays populated and remains queryable by the agent — correct, since the chat
        // row is still alive. On commit, the session entries are dropped so the vector store doesn't accumulate
        // orphans for every deleted chat.
        scheduleChatToolSessionClear(chatId);

        // Same after-commit semantics again, and evicted across every user of the conversation since a chat is
        // deleted as a whole. The store's own evict contract is to fail soft and log rather than propagate, but a
        // statement that failed inside this transaction aborts the transaction on PostgreSQL whether or not its
        // exception is swallowed, so the eviction has to run outside it.
        scheduleTokenSessionEvictAfterCommit(requesterWorkspaceId, chat.getThreadId());
    }

    /**
     * Mirrors {@link #scheduleChatMemoryDeleteAfterCommit}: the eviction runs after the chat delete commits, and inline
     * only when no transaction is active. Running it inline inside the caller's transaction would let a failed DELETE
     * poison that transaction — PostgreSQL aborts the whole transaction on a failed statement, so the store swallowing
     * its own exception does not save the commit that follows, and the user's chat delete would fail with the chat row
     * still there.
     */
    private void scheduleTokenSessionEvictAfterCommit(long workspaceId, String threadId) {
        PiiTokenSessionStore tokenSessionStore = piiTokenSessionStore;

        if (tokenSessionStore == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            tokenSessionStore.evict(workspaceId, threadId);

            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                tokenSessionStore.evict(workspaceId, threadId);
            }
        });
    }

    /**
     * Deletes every tool approval recorded for the chat, matching how {@code ai_hub_chat_tool} rows are cleaned up.
     * {@code toolApprovalServiceProvider} is nullable (both the provider itself, for the four hand-built test
     * constructors, and what it yields, for a deployment where the tool approval module is disabled), so both are
     * guarded before use.
     */
    private void deleteApprovals(long chatId) {
        if (toolApprovalServiceProvider == null) {
            return;
        }

        AiHubToolApprovalService toolApprovalService = toolApprovalServiceProvider.getIfAvailable();

        if (toolApprovalService == null) {
            return;
        }

        toolApprovalService.deleteByChat(chatId);
    }

    /**
     * Deletes every named-user grant recorded against the chat. {@code resourceGrantServiceProvider} is nullable (both
     * the provider itself, for hand-built test constructors and CE builds with no resource-grant module on the
     * classpath, and what it yields, for a CE build that carries the module but not the {@code ResourceGrantService}
     * bean), so both are guarded before use. {@code resource_id} is a polymorphic column with no foreign key, so a
     * grant row would otherwise outlive its chat and could later collide with a recycled id.
     */
    private void deleteGrants(long chatId) {
        if (resourceGrantServiceProvider == null) {
            return;
        }

        ResourceGrantService resourceGrantService = resourceGrantServiceProvider.getIfAvailable();

        if (resourceGrantService == null) {
            return;
        }

        resourceGrantService.deleteGrants(AiHubChatVisibilityPolicy.RESOURCE_TYPE, chatId);
    }

    private void scheduleChatMemoryDeleteAfterCommit(String threadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteSessionMessages(threadId);

            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                deleteSessionMessages(threadId);
            }
        });
    }

    private void deleteSessionMessages(String threadId) {
        AiHubSessionMemory sessionMemory = aiHubSessionMemoryProvider.getIfAvailable();

        if (sessionMemory == null) {
            return;
        }

        deleteSession(sessionMemory, threadId);

        // Specialist subagents keep their own per-conversation sessions keyed <threadId>:<agentType>. SessionRepository
        // has no prefix listing, so the keys are reconstructed from the registry. Registered types include panel agents
        // that never own a specialist session; deleting a key that was never created is a no-op, and the alternative --
        // a hand-maintained delegate list -- would rot silently.
        for (String agentTypeKey : AgentTypeRegistry.keys()) {
            deleteSession(sessionMemory, SubAgentSessionMemoryContributor.sessionKey(threadId, agentTypeKey));
        }
    }

    /**
     * Each delete is individually guarded so one failure does not abandon the rest — the pre-existing best-effort
     * contract, applied per session rather than to the whole sweep.
     */
    private void deleteSession(AiHubSessionMemory sessionMemory, String sessionId) {
        try {
            sessionMemory.sessionService()
                .delete(sessionId);
        } catch (RuntimeException exception) {
            log.warn(
                "Failed to delete session messages for sessionId {}; leaving orphan messages for background cleanup",
                sessionId, exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiHubChat> findByThreadId(String threadId) {
        return chatRepository.findByThreadId(threadId);
    }

    @Override
    public void recordTurn(long chatId, long userId, @Nullable String runId) {
        turnRepository.save(new AiHubChatTurn(chatId, userId, runId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiHubChatTurn> findLatestTurn(long chatId) {
        return turnRepository.findFirstByChatIdOrderByCreatedDateDesc(chatId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChat getById(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return loadViewable(chatId, requesterWorkspaceId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChat getViewable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return loadViewable(chatId, requesterWorkspaceId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChat getParticipable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return loadParticipable(chatId, requesterWorkspaceId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChat getManageable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return loadManageable(chatId, requesterWorkspaceId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubChat getByThreadId(String threadId, long requesterWorkspaceId, long requesterUserId) {
        Optional<AiHubChat> chatOptional = chatRepository.findByThreadId(threadId);

        if (chatOptional.isEmpty()) {
            throw new NotFoundException("AiHubChat not found");
        }

        AiHubChat chat = chatOptional.get();

        if (!workspaceOwnsChat(requesterWorkspaceId, chat) || !accessPolicy.canManage(chat, requesterUserId)) {
            log.warn(
                "AiHubChat access denied: requester userId={} workspaceId={} attempted to access "
                    + "threadId={} owned by userId={}. Returning 404 to avoid leaking existence.",
                requesterUserId, requesterWorkspaceId, threadId, chat.getUserId());

            throw new NotFoundException("AiHubChat not found");
        }

        return chat;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiHubChat> findByThreadIdViewable(String threadId, long requesterUserId) {
        return chatRepository.findByThreadId(threadId)
            .filter(chat -> accessPolicy.canView(chat, requesterUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, AiHubChat> findAllByThreadIdViewable(Collection<String> threadIds, long requesterUserId) {
        if (threadIds.isEmpty()) {
            return Map.of();
        }

        Map<String, AiHubChat> chatByThreadId = new LinkedHashMap<>();

        for (AiHubChat chat : chatRepository.findAllByThreadIdIn(threadIds)) {
            // Same predicate as the single-row overload, applied row by row rather than in SQL: canView spans the
            // chat's visibility rung, workspace membership and the resource-grant table, so it is not expressible as
            // a WHERE clause. What the batch saves is the N chat lookups, not the authorization.
            if (accessPolicy.canView(chat, requesterUserId)) {
                chatByThreadId.put(chat.getThreadId(), chat);
            }
        }

        return chatByThreadId;
    }

    /**
     * Loads {@code chatId} and verifies the requester may view it — either as owner/admin, or, for a non-owner, per
     * {@link AiHubChatAccessPolicy#canView}.
     */
    private AiHubChat loadViewable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canView);
    }

    /**
     * Loads {@code chatId} and verifies the requester may contribute a turn to it, per
     * {@link AiHubChatAccessPolicy#canParticipate}.
     */
    private AiHubChat loadParticipable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canParticipate);
    }

    /**
     * Loads {@code chatId} and verifies the requester may manage it — owner or admin only, per
     * {@link AiHubChatAccessPolicy#canManage}.
     */
    private AiHubChat loadManageable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canManage);
    }

    /**
     * Loads {@code chatId}, verifies it lives in {@code requesterWorkspaceId}, and verifies {@code allowed} accepts the
     * requester. The workspace check is required because the controller-level workspaceId query parameter would
     * otherwise be a decorative no-op — a caller could read or mutate any chat reachable from any workspace they merely
     * claim to be operating in.
     *
     * <p>
     * Probe-oracle defense: "does not exist", "exists in another workspace", and "not permitted for this requester" all
     * return 404 with an opaque message so an authenticated attacker cannot enumerate chat ids across the install.
     * Server-side logging at WARN preserves the audit trail for security review.
     */
    private AiHubChat load(
        long chatId, long requesterWorkspaceId, long requesterUserId, BiPredicate<AiHubChat, Long> allowed) {

        AiHubChat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new NotFoundException("AiHubChat not found"));

        if (!workspaceOwnsChat(requesterWorkspaceId, chat) || !allowed.test(chat, requesterUserId)) {
            log.warn(
                "AiHubChat access denied: requester userId={} workspaceId={} attempted to access chatId={} owned by "
                    + "userId={}. Returning 404 to avoid leaking existence.",
                requesterUserId, requesterWorkspaceId, chatId, chat.getUserId());

            throw new NotFoundException("AiHubChat not found");
        }

        return chat;
    }
}
