/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat.repository;

import com.bytechef.ee.ai.hub.chat.AiHubChat;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link AiHubChat} metadata rows. Workspace-aware queries filter on the nullable
 * {@code ai_hub_chat.workspace_id} column, which loaded rows carry directly. A chat with a null workspace matches no
 * workspace-scoped query.
 *
 * <p>
 * Message contents remain in Spring AI's session store, keyed by {@code thread_id}, and are not managed here.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubChatRepository extends CrudRepository<AiHubChat, Long> {

    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId
          AND cct.user_id = :userId
          AND cct.environment = :environment
          AND cct.status = :status
        ORDER BY cct.updated_at DESC
        LIMIT :limit
        """)
    List<AiHubChat> findByWorkspaceIdAndUserIdAndEnvironmentAndStatusOrderByUpdatedAtDesc(
        long workspaceId, long userId, int environment, int status, int limit);

    /**
     * Same as {@link #findByWorkspaceIdAndUserIdAndEnvironmentAndStatusOrderByUpdatedAtDesc} but across ALL statuses —
     * used when a caller (e.g. the tool-binding lookup) needs every chat regardless of active/archived state.
     */
    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId
          AND cct.user_id = :userId
          AND cct.environment = :environment
        ORDER BY cct.updated_at DESC
        LIMIT :limit
        """)
    List<AiHubChat> findByWorkspaceIdAndUserIdAndEnvironmentOrderByUpdatedAtDesc(
        long workspaceId, long userId, int environment, int limit);

    Optional<AiHubChat> findByThreadIdAndUserId(String threadId, long userId);

    Optional<AiHubChat> findByThreadId(String threadId);

    /**
     * Batch counterpart of {@link #findByThreadId}, for the {@code /status} poll. That endpoint is handed every thread
     * id the sidebar knows about on a 20-second interval, and resolving them one at a time made the poll's cost N
     * round-trips per tick where the {@code /in-flight} endpoint it replaced cost none. Rows the caller may not view
     * are filtered afterwards, in the service, by the same access policy the single-row lookup applies —
     * {@code thread_id} carries a global UNIQUE constraint, so this returns at most one row per id.
     */
    List<AiHubChat> findAllByThreadIdIn(Collection<String> threadIds);

    /**
     * Lists every active chat of the given kinds owned by the user in the given workspace+environment. Used by the
     * bulk-archive cleanup path; the result list is fed back through the regular {@code save} loop so each row gets the
     * standard updatedAt bump and validation, rather than running a single UPDATE statement that bypasses entity
     * lifecycle hooks.
     *
     * <p>
     * Returns at most {@code limit} rows so a workspace with thousands of stale chats doesn't materialise the whole
     * list at once. The bulk-archive caller passes a generous cap (matching the sidebar list cap) — running the archive
     * twice catches any overflow.
     * </p>
     */
    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId
          AND cct.user_id = :userId
          AND cct.environment = :environment
          AND cct.kind IN (:kinds)
          AND cct.status = :status
        LIMIT :limit
        """)
    List<AiHubChat> findByWorkspaceIdAndUserIdAndEnvironmentAndKindInAndStatus(
        long workspaceId, long userId, int environment, Collection<Integer> kinds, int status, int limit);

    /**
     * Chats another workspace member already reaches directly: {@code visibility = WORKSPACE}, owned by someone other
     * than the caller. Paired with {@link #findPrivateCandidates} in {@code AiHubChatServiceImpl#listSharedWithMe} —
     * this half needs no grant lookup, since workspace visibility already applies to every member.
     */
    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId AND cct.user_id <> :userId AND cct.environment = :environment
          AND cct.status = :status AND cct.visibility = :visibility
        ORDER BY cct.updated_at DESC LIMIT :limit
        """)
    List<AiHubChat> findSharedByReach(
        long workspaceId, long userId, int environment, int status, int visibility, int limit);

    /**
     * Candidate {@code PRIVATE} chats owned by other users, to be filtered down to the ones {@code userId} has been
     * individually granted. The caller passes a window well beyond {@link #findSharedByReach}'s own limit — a grant on
     * a chat outside this window is not listed; see {@code AiHubChatServiceImpl#listSharedWithMe}.
     */
    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId AND cct.user_id <> :userId AND cct.environment = :environment
          AND cct.status = :status AND cct.visibility = :privateVisibility
        ORDER BY cct.updated_at DESC LIMIT :limit
        """)
    List<AiHubChat> findPrivateCandidates(
        long workspaceId, long userId, int environment, int status, int privateVisibility, int limit);

    /**
     * Inserts a channel-born agent chat, doing nothing when {@code thread_id} is already taken. Used by the
     * find-or-create recorder path: a busy channel can deliver two turns of the same conversation concurrently, and
     * both would then miss the preceding {@code findByThreadId} and race to insert the same {@code thread_id}.
     *
     * <p>
     * {@code ON CONFLICT DO NOTHING} rather than a caught {@code DuplicateKeyException}: PostgreSQL aborts the whole
     * transaction on a constraint violation, so catching the exception afterwards still fails at commit. The loser of
     * the race gets {@code 0} back and re-reads the winner's row.
     * </p>
     *
     * @return {@code 1} when this call inserted the row, {@code 0} when another one already had
     */
    @Modifying
    @Query("""
        INSERT INTO ai_hub_chat (
            user_id, thread_id, title, message_count, status, environment, kind, workspace_id, ai_agent_id,
            visibility, participation, auto_titled, created_at, updated_at)
        VALUES (
            :userId, :threadId, :title, 0, :status, :environment, :kind, :workspaceId, :aiAgentId,
            :visibility, :participation, true, :now, :now)
        ON CONFLICT (thread_id) DO NOTHING
        """)
    int insertAgentChatIfAbsent(
        long userId, String threadId, @Nullable String title, int status, int environment, int kind, long workspaceId,
        long aiAgentId, LocalDateTime now, int visibility, int participation);
}
