/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatArtifact;
import com.bytechef.ee.ai.hub.chat.AiHubChatArtifactService;
import com.bytechef.ee.ai.hub.chat.AiHubChatParticipation;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.AiHubChatService.AiHubChatMessage;
import com.bytechef.ee.ai.hub.chat.AiHubChatService.AiHubChatPatch;
import com.bytechef.ee.ai.hub.chat.AiHubChatStatus;
import com.bytechef.ee.ai.hub.chat.TitleGenerationService;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.security.WorkspaceAccessGuard;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.context.EnvironmentContext;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL surface for AI Hub aiHubChats. Replaces the prior REST {@code TaskApiController} — the
 * {@link AiHubChatService} / {@link AiHubChatArtifactService} / {@link TitleGenerationService} contracts are unchanged;
 * only the transport differs.
 *
 * <p>
 * Authorization mirrors the REST controller exactly: every operation requires {@code isAuthenticated()} plus the
 * {@link WorkspaceAccessGuard} membership gate. Service-layer ownership checks remain the authoritative defence — a
 * caller who is a workspace member but not the chat owner still gets a 403-equivalent error from the service.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@PreAuthorize("isAuthenticated()")
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubChatGraphQlController {

    private static final Logger log = LoggerFactory.getLogger(AiHubChatGraphQlController.class);

    private final AiHubChatArtifactService chatArtifactService;
    private final AiHubChatService chatService;
    private final TitleGenerationService titleGenerationService;
    private final UserService userService;
    private final WorkspaceFacade workspaceFacade;

    @SuppressFBWarnings("EI")
    public AiHubChatGraphQlController(
        AiHubChatArtifactService chatArtifactService,
        AiHubChatService chatService,
        TitleGenerationService titleGenerationService, UserService userService, WorkspaceFacade workspaceFacade) {

        this.chatArtifactService = chatArtifactService;
        this.chatService = chatService;
        this.titleGenerationService = titleGenerationService;
        this.userService = userService;
        this.workspaceFacade = workspaceFacade;
    }

    @QueryMapping
    public List<AiHubChat> aiHubChats(
        @Argument long workspaceId, @Argument int environment,
        @Argument @Nullable AiHubChatStatus status) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        AiHubChatStatus effectiveStatus =
            status == null ? AiHubChatStatus.ACTIVE : status;

        return chatService.list(workspaceId, userId, environment, effectiveStatus);
    }

    @QueryMapping
    public List<AiHubChat> aiHubSharedChats(@Argument long workspaceId, @Argument int environment) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.listSharedWithMe(workspaceId, userId, environment);
    }

    @QueryMapping
    public List<AiHubChatMessage>
        aiHubChatMessages(@Argument long workspaceId, @Argument long id) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.loadMessages(id, workspaceId, userId);
    }

    @QueryMapping
    public List<AiHubChatArtifact> aiHubChatArtifactsByAiHubChat(
        @Argument long workspaceId, @Argument long id) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatArtifactService.listByChat(id, workspaceId, userId);
    }

    @MutationMapping
    public AiHubChat createAiHubChat(
        @Argument long workspaceId, @Argument int environment, @Argument String threadId) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.create(workspaceId, userId, environment, threadId);
    }

    @MutationMapping
    public AiHubChat createWorkflowChatAiHubChat(
        @Argument long workspaceId, @Argument int environment, @Argument String workflowExecutionId,
        @Argument long projectDeploymentId, @Argument @Nullable String title) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        // Always-new semantics: every call inserts a fresh chat row with a UUID-suffixed threadId.
        // Past aiHubChats bound to the same workflow remain reachable through the
        // aiHubChats list rather
        // than being restored on re-click. The title is stamped onto the new row.
        return chatService.createWorkflowChat(
            workspaceId, userId, environment, workflowExecutionId, projectDeploymentId, title);
    }

    @MutationMapping
    public AiHubChat createAgentChatAiHubChat(
        @Argument long workspaceId, @Argument int environment, @Argument String workflowExecutionId,
        @Argument long projectDeploymentId, @Argument @Nullable String title) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        // Same always-new semantics as the workflow-chat mutation above; only the persisted kind differs. Kept as its
        // own mutation rather than a flag on that one because the caller already knows which cascade the user picked,
        // and a boolean argument would let a client silently create a mislabelled row.
        return chatService.createAgentChat(
            workspaceId, userId, environment, workflowExecutionId, projectDeploymentId, title);
    }

    @MutationMapping
    public AiHubChat
        updateAiHubChat(@Argument AiHubChatPatchInput input) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, input.workspaceId());

        AiHubChatPatch patch = new AiHubChatPatch(
            input.title(), input.lastPreview(), input.messageCount(), input.status());

        return chatService.patch(input.id(), input.workspaceId(), userId, patch);
    }

    @MutationMapping
    public AiHubChat
        generateAiHubChatTitle(@Argument long workspaceId, @Argument long id) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        AiHubChat existing = chatService.getById(id, workspaceId, userId);

        if (!existing.isAutoTitled()) {
            // Locked — title was either set by the LLM on a previous regeneration or explicitly renamed by
            // the user. Short-circuit before loading messages or calling the LLM. The "auto-titled" flag
            // (versus the previous "title is empty" check) lets workflow chats — which start with a
            // label-based title that's still eligible for LLM regeneration — get a more meaningful title
            // after a few turns instead of being stuck on the project-name pattern forever.
            return existing;
        }

        List<AiHubChatMessage> messages = chatService.loadMessages(id, workspaceId, userId);

        String title = generateTitleForEnvironment(existing.getEnvironment(), messages);

        if (title.isEmpty()) {
            // The model returned a blank or over-length title. Reuse the row we already loaded above
            // for the idempotency check rather than issuing a second getById round-trip.
            return existing;
        }

        try {
            return chatService.patch(
                id, workspaceId, userId, new AiHubChatPatch(title, null, null, null));
        } catch (NotFoundException notFound) {
            // Race: the chat was deleted between the getById/loadMessages above and this patch.
            // Title generation is fire-and-forget on the client (one per turn while untitled), so a quick
            // delete after sending a message lands here. Surface as a benign no-op — the deleted row's
            // pre-delete snapshot is still a valid AiHubChat shape for the GraphQL response, and the
            // client has already removed the chat from its local state so this body is never
            // rendered. Logging at INFO so the race stays visible without spamming WARN/ERROR.
            log.info(
                "generateAiHubChatTitle no-op: chat {} was deleted during title generation",
                id);

            return existing;
        }
    }

    private String generateTitleForEnvironment(Environment environment, List<AiHubChatMessage> messages) {
        Environment previousEnvironment = EnvironmentContext.fetchCurrentEnvironment();

        EnvironmentContext.set(environment);

        try {
            return titleGenerationService.generateTitle(messages);
        } finally {
            if (previousEnvironment == null) {
                EnvironmentContext.clear();
            } else {
                EnvironmentContext.set(previousEnvironment);
            }
        }
    }

    @MutationMapping
    public boolean deleteAiHubChat(@Argument long workspaceId, @Argument long id) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        chatService.delete(id, workspaceId, userId);

        return true;
    }

    @MutationMapping
    public int
        bulkArchiveWorkflowChatAiHubChats(@Argument long workspaceId, @Argument int environment) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        // Service-layer per-row ownership re-check matches the rest of the chat API; we double-gate on
        // the workspace-membership guard here AND the per-chat ownership check inside the loop. Returning
        // the count rather than the affected row ids keeps the response small (no per-row latency the client has
        // to render); the sidebar refetches the aiHubChats query after this fires anyway.
        return chatService.bulkArchiveWorkflowChatAiHubChats(workspaceId, userId, environment);
    }

    @MutationMapping
    public boolean cancelWorkflowChatTurn(@Argument long workspaceId, @Argument long id) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.cancelWorkflowChatTurn(id, workspaceId, userId);
    }

    @MutationMapping
    public boolean cancelAiHubRun(@Argument long workspaceId, @Argument long id, @Argument String runId) {
        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.cancelAiHubRun(id, workspaceId, userId, runId);
    }

    @MutationMapping
    public int truncateAiHubChatMessages(
        @Argument long workspaceId, @Argument long id, @Argument int fromMessageIndex) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        return chatService.truncateMessagesFrom(id, workspaceId, userId, fromMessageIndex);
    }

    @MutationMapping
    public boolean appendAiHubChatAssistantMessage(
        @Argument long workspaceId, @Argument long id, @Argument String content) {

        long userId = userService.getCurrentUser()
            .getId();

        WorkspaceAccessGuard.verifyUserCanAccessWorkspace(workspaceFacade, userId, workspaceId);

        chatService.appendAssistantMessage(id, workspaceId, userId, content);

        return true;
    }

    /**
     * Resolves the owning workspace id for a chat, read straight off the loaded row's {@code workspace_id} column, so
     * the sidebar listing costs no extra query per row. The schema declares the field non-null and a chat with no
     * workspace is unreachable through every workspace-scoped path, so a null fails loudly here — the same contract
     * {@code AiHubChatService.getWorkspaceId} enforces for callers that only have a chat id.
     */
    @SchemaMapping(typeName = "AiHubChat", field = "workspaceId")
    public long chatWorkspaceId(AiHubChat chat) {
        Long workspaceId = chat.getWorkspaceId();

        if (workspaceId == null) {
            throw new NotFoundException("No workspace for ai_hub_chat id=" + chat.getId());
        }

        return workspaceId;
    }

    @SchemaMapping(typeName = "AiHubChat", field = "status")
    public String chatStatus(AiHubChat chat) {
        return chat.getStatus()
            .name();
    }

    @SchemaMapping(typeName = "AiHubChat", field = "kind")
    public String chatKind(AiHubChat chat) {
        return chat.getKind()
            .name();
    }

    @SchemaMapping(typeName = "AiHubChat", field = "createdAt")
    @Nullable
    public Long chatCreatedAt(AiHubChat chat) {
        return chat.getCreatedAt() == null ? null
            : chat.getCreatedAt()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }

    @SchemaMapping(typeName = "AiHubChat", field = "updatedAt")
    @Nullable
    public Long chatUpdatedAt(AiHubChat chat) {
        return chat.getUpdatedAt() == null ? null
            : chat.getUpdatedAt()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }

    @SchemaMapping(typeName = "AiHubChat", field = "visibility")
    public AiHubChatVisibility chatVisibility(AiHubChat chat) {
        return AiHubChatVisibilityMapper.toAiHubChatVisibility(chat.getVisibility());
    }

    @SchemaMapping(typeName = "AiHubChat", field = "participation")
    public AiHubChatParticipation chatParticipation(AiHubChat chat) {
        return chat.getParticipation();
    }

    @SchemaMapping(typeName = "AiHubChat", field = "ownerUserId")
    public long chatOwnerUserId(AiHubChat chat) {
        return chat.getUserId();
    }

    /**
     * Resolver for {@code AiHubChat.ownerName}. Declared as {@code @BatchMapping} rather than a per-row
     * {@code @SchemaMapping} — the client selects this field unconditionally on {@code aiHubChats}, so a per-row
     * {@code userService.fetchUser} was one lookup per chat on every sidebar load. Owner ids are de-duplicated first,
     * which is what actually collapses the cost: a sidebar is mostly the caller's own chats plus a handful shared by
     * others, so N rows resolve to one or two distinct owners.
     *
     * <p>
     * A login that no longer resolves maps to {@code null}, exactly as the per-row version did — {@code ownerName} is
     * nullable in the schema, and a deleted user must not blank the row.
     * </p>
     *
     * <p>
     * Returns a positional {@code List} rather than a {@code Map} keyed by the parent, which spring-graphql matches to
     * the input order ({@code BatchLoaderHandlerMethod#invokeForIterable}). The map form would key on
     * {@code AiHubChat}'s own {@code equals}, and the sibling {@link #messageAuthorName} could not use it safely at all
     * — {@code AiHubChatMessage} is a record, so two identical rows are equal and would collapse into one entry,
     * silently nulling the duplicate's author. Positional has neither problem, so both use it.
     * </p>
     */
    @BatchMapping(typeName = "AiHubChat", field = "ownerName")
    public List<String> chatOwnerName(List<AiHubChat> chats) {
        Map<Long, String> loginByUserId = resolveLoginsByUserId(
            chats.stream()
                .map(AiHubChat::getUserId));

        return chats.stream()
            .map(chat -> loginByUserId.get(chat.getUserId()))
            .toList();
    }

    /**
     * Resolver for {@code AiHubChat.isOwner}. Deliberately per-row, unlike its two batched neighbours, and not because
     * batching is hard: the per-row cost is not a database round-trip. {@code UserService.getCurrentUser()} is a
     * {@code SecurityContextHolder} read plus {@code UserRepository.findByLogin}, which is
     * {@code @Cacheable(USERS_BY_LOGIN_CACHE)} and tenant-keyed, evicted by {@code UserServiceImpl} on every user
     * mutation — so resolving it once per row costs a cache hit per row, not a query. That is the same cost
     * {@code ProjectOwnershipResolver#resolveOwnerUserId} already accepts, with the same reasoning written down, on the
     * hotter authorization path. Neither a {@code DataLoader} nor a per-request memo of the current user would buy
     * anything here, because there is no query to save.
     *
     * <p>
     * The two neighbours are batched because their cost IS a round-trip: they resolve through
     * {@code UserService#fetchUser(long)} to {@code findById}, which carries no {@code @Cacheable}.
     * </p>
     *
     * <p>
     * Batching this one would also be actively risky, which is the second reason to leave it alone. A
     * {@code @BatchMapping} body runs inside a {@code DataLoader} dispatch rather than on the request thread, and
     * {@code BatchLoaderHandlerMethod} restores no {@code SecurityContextHolder} thread-local — its
     * {@code springSecurityPresent} flag only resolves a {@code Principal} method argument. Whether the thread-local
     * survives the dispatch therefore depends on the app's executor and Reactor context propagation, which this module
     * cannot verify: it has no {@code GraphQlTest} harness for this controller, and a unit test calling the method
     * directly cannot see the difference. Getting it wrong would either throw {@code UserNotFoundException} for a whole
     * batch or mislabel ownership, which drives the rename/archive/delete controls. The two batched neighbours take an
     * explicit user id and touch no security context, so they carry none of this risk.
     * </p>
     */
    @SchemaMapping(typeName = "AiHubChat", field = "isOwner")
    public boolean chatIsOwner(AiHubChat chat) {
        long userId = userService.getCurrentUser()
            .getId();

        return chat.getUserId() == userId;
    }

    @SchemaMapping(typeName = "AiHubChatMessage", field = "timestamp")
    public long messageTimestamp(AiHubChatMessage message) {
        return message.timestamp()
            .toEpochMilli();
    }

    /**
     * Resolver for {@code AiHubChatMessage.authorName}. Batched like {@link #chatOwnerName}: a shared chat's transcript
     * carries an author id on every USER row, and a per-row lookup made opening one N messages long cost N queries for
     * what is almost always one or two distinct authors. A message with no resolved author — the whole transcript when
     * turn attribution is unreliable, see {@code AiHubChatServiceImpl.loadMessages} — maps to {@code null}, and
     * contributes no lookup.
     */
    @BatchMapping(typeName = "AiHubChatMessage", field = "authorName")
    public List<String> messageAuthorName(List<AiHubChatMessage> messages) {
        Map<Long, String> loginByUserId = resolveLoginsByUserId(
            messages.stream()
                .map(AiHubChatMessage::authorUserId));

        return messages.stream()
            .map(AiHubChatMessage::authorUserId)
            .map(authorUserId -> authorUserId == null ? null : loginByUserId.get(authorUserId))
            .toList();
    }

    /**
     * Resolves logins for the DISTINCT, non-null user ids in {@code userIds}, one lookup each. {@code UserService}
     * exposes no {@code IN}-clause batch read, so de-duplication rather than a single query is what bounds the cost
     * here — by the number of distinct people in the response, not by its number of rows. A user id that no longer
     * resolves is simply absent from the returned map, so callers read {@code null} for it.
     */
    private Map<Long, String> resolveLoginsByUserId(Stream<Long> userIds) {
        Map<Long, String> loginByUserId = new HashMap<>();

        userIds.filter(Objects::nonNull)
            .distinct()
            .forEach(userId -> userService.fetchUser(userId)
                .map(User::getLogin)
                .ifPresent(login -> loginByUserId.put(userId, login)));

        return loginByUserId;
    }

    public record AiHubChatPatchInput(
        long id, long workspaceId, @Nullable String title, @Nullable String lastPreview,
        @Nullable Integer messageCount, @Nullable AiHubChatStatus status) {
    }
}
