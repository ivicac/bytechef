/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.chat.AiHubChatService.AiHubChatMessage;
import com.bytechef.ee.ai.hub.chat.AiHubChatService.AiHubChatPatch;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatRepository;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatTurnRepository;
import com.bytechef.ee.ai.hub.exception.ConflictException;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.subagent.SubAgentSessionMemoryContributor;
import com.bytechef.ee.ai.hub.tool.AiHubAgentType;
import com.bytechef.ee.platform.resource.grant.service.ResourceGrantService;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link AiHubChatServiceImpl}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AiHubChatServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long OTHER_WORKSPACE_ID = 2L;
    private static final long USER_ID = 10L;
    private static final long OTHER_USER_ID = 99L;
    private static final String THREAD_ID = "thread-abc";

    @Mock
    private AiHubChatRepository chatRepository;

    @Mock
    private AiHubChatTurnRepository turnRepository;

    @Mock
    private ObjectProvider<com.bytechef.ee.ai.hub.memory.AiHubSessionMemory> aiHubSessionMemoryProvider;

    @Mock
    private com.bytechef.ee.ai.hub.memory.AiHubSessionMemory aiHubSessionMemory;

    @Mock
    private org.springframework.ai.session.SessionService sessionService;

    @Mock
    private org.springframework.ai.session.SessionRepository sessionRepository;

    @Mock
    private com.bytechef.atlas.execution.facade.JobFacade jobFacade;

    @Mock
    private com.bytechef.ee.ai.hub.agent.WorkflowChatJobRegistry jobRegistry;

    @Mock
    private com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry inFlightRunRegistry;

    @Mock
    private ObjectProvider<ResourceGrantService> resourceGrantServiceProvider;

    @Mock
    private ResourceGrantService resourceGrantService;

    private AiHubChatServiceImpl chatService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        lenient().when(aiHubSessionMemoryProvider.getIfAvailable())
            .thenReturn(aiHubSessionMemory);
        lenient().when(aiHubSessionMemory.sessionService())
            .thenReturn(sessionService);
        lenient().when(aiHubSessionMemory.sessionRepository())
            .thenReturn(sessionRepository);

        // The optional tool-search and tool-approval ObjectProviders are left null (the delete path's index clear
        // and approval cleanup both guard on null) — the same shape the previous @InjectMocks wiring produced.
        chatService = new AiHubChatServiceImpl(
            chatRepository, turnRepository, new OwnerOnlyAccessPolicy(), jobFacade, jobRegistry, inFlightRunRegistry,
            null, aiHubSessionMemoryProvider, null, null, null);
    }

    private static org.springframework.ai.session.SessionEvent sessionEvent(
        org.springframework.ai.chat.messages.MessageType type, String text, Instant timestamp) {

        org.springframework.ai.session.SessionEvent event = mock(org.springframework.ai.session.SessionEvent.class);

        org.springframework.ai.chat.messages.Message message =
            type == org.springframework.ai.chat.messages.MessageType.USER
                ? new org.springframework.ai.chat.messages.UserMessage(text)
                : new org.springframework.ai.chat.messages.AssistantMessage(text);

        lenient().when(event.getMessageType())
            .thenReturn(type);
        lenient().when(event.getMessage())
            .thenReturn(message);
        lenient().when(event.getTimestamp())
            .thenReturn(timestamp);

        return event;
    }

    @Test
    void testCreateInsertsNewChat() {
        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        ArgumentCaptor<AiHubChat> captor = ArgumentCaptor.forClass(AiHubChat.class);

        when(chatRepository.save(captor.capture())).thenAnswer(invocation -> {
            AiHubChat captured = invocation.getArgument(0);

            captured.setId(42L);

            return captured;
        });

        AiHubChat result = chatService.create(WORKSPACE_ID, USER_ID, 0, THREAD_ID);

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(result.getThreadId()).isEqualTo(THREAD_ID);
        assertThat(result.getStatus()).isEqualTo(AiHubChatStatus.ACTIVE);
        assertThat(result.getMessageCount()).isZero();
        assertThat(result.getVisibility()).isEqualTo(ResourceVisibility.PRIVATE);

        verify(chatRepository).save(any(AiHubChat.class));
    }

    @Test
    void testCreateIsIdempotent() {
        AiHubChat existing =
            buildChat(7L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(existing));

        AiHubChat result = chatService.create(WORKSPACE_ID, USER_ID, 0, THREAD_ID);

        assertThat(result.getId()).isEqualTo(7L);

        verify(chatRepository, never()).save(any());
    }

    @Test
    void testListReturnsFilteredByStatus() {
        List<AiHubChat> activeList = List.of(
            buildChat(1L, USER_ID, "t-1", AiHubChatStatus.ACTIVE),
            buildChat(2L, USER_ID, "t-2", AiHubChatStatus.ACTIVE));

        when(chatRepository.findByWorkspaceIdAndUserIdAndEnvironmentAndStatusOrderByUpdatedAtDesc(
            eq(WORKSPACE_ID), eq(USER_ID), eq(0), eq(AiHubChatStatus.ACTIVE.ordinal()),
            anyInt()))
                .thenReturn(activeList);

        List<AiHubChat> result =
            chatService.list(WORKSPACE_ID, USER_ID, 0, AiHubChatStatus.ACTIVE);

        assertThat(result).hasSize(2);
        assertThat(result)
            .allSatisfy(
                chat -> assertThat(chat.getStatus()).isEqualTo(AiHubChatStatus.ACTIVE));
    }

    @Test
    void testLoadMessagesThrowsNotFoundOnOwnershipMismatch() {
        // Probe-oracle defense: cross-user access returns the same NotFoundException as a missing row, with no
        // hint that the chat actually exists in another user's namespace.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.loadMessages(1L, WORKSPACE_ID, OTHER_USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");
    }

    @Test
    void testLoadMessagesThrowsNotFoundOnWorkspaceMismatch() {
        // Probe-oracle defense: cross-workspace access returns the same NotFoundException as a missing row.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.loadMessages(1L, OTHER_WORKSPACE_ID, USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLoadMessagesQueriesChatMemory() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        Instant now = Instant.parse("2026-04-23T10:00:00Z");

        List<org.springframework.ai.session.SessionEvent> events =
            List.of(sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "Hello", now));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);

        List<AiHubChatMessage> messages = chatService.loadMessages(1L, WORKSPACE_ID, USER_ID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)
            .role()).isEqualTo("USER");
        assertThat(messages.get(0)
            .content()).isEqualTo("Hello");
    }

    @Test
    void testPatchAppliesNonNullFieldsOnly() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        chat.setTitle("Old title");
        chat.setMessageCount(3);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));
        when(chatRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AiHubChatPatch patch = new AiHubChatPatch("New title", null, null, null);

        AiHubChat result = chatService.patch(1L, WORKSPACE_ID, USER_ID, patch);

        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getMessageCount()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo(AiHubChatStatus.ACTIVE);
    }

    @Test
    void testPatchThrowsNotFoundOnOwnershipMismatch() {
        // Probe-oracle defense: cross-user access returns NotFoundException with the same opaque message as a
        // missing row.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(
            () -> chatService.patch(
                1L, WORKSPACE_ID, OTHER_USER_ID, new AiHubChatPatch("New title", null, null, null)))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("AiHubChat not found");
    }

    @Test
    void testPatchThrowsNotFoundOnWorkspaceMismatch() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(
            () -> chatService.patch(
                1L, OTHER_WORKSPACE_ID, USER_ID, new AiHubChatPatch("New title", null, null, null)))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("AiHubChat not found");
    }

    @Test
    void testDeleteCascadesToChatMemoryAndRemovesRow() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        chatService.delete(1L, WORKSPACE_ID, USER_ID);

        verify(sessionService).delete(THREAD_ID);
        verify(chatRepository).delete(chat);
    }

    /**
     * A specialist subagent keeps its own session under {@code <threadId>:<agentType>}; deleting the chat must take
     * those with it rather than leaving one conversation's memory behind for the next one.
     */
    @Test
    void testDeletePurgesSpecialistSessionsAlongsideTheParentSession() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        chatService.delete(1L, WORKSPACE_ID, USER_ID);

        verify(sessionService).delete(THREAD_ID);
        verify(sessionService).delete(
            SubAgentSessionMemoryContributor.sessionKey(THREAD_ID, AiHubAgentType.DATA_ANALYST.key()));
        verify(sessionService).delete(
            SubAgentSessionMemoryContributor.sessionKey(THREAD_ID, AiHubAgentType.RESEARCH.key()));
        verify(chatRepository).delete(chat);
    }

    /**
     * One failing session delete must not abandon the rest — the purge is best-effort per session, not all-or-nothing.
     */
    @Test
    void testDeleteContinuesPurgingAfterASessionDeleteFails() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        doThrow(new IllegalStateException("session store unavailable")).when(sessionService)
            .delete(THREAD_ID);

        chatService.delete(1L, WORKSPACE_ID, USER_ID);

        verify(sessionService).delete(
            SubAgentSessionMemoryContributor.sessionKey(THREAD_ID, AiHubAgentType.DATA_ANALYST.key()));
        verify(chatRepository).delete(chat);
    }

    @Test
    void testDeleteThrowsNotFoundOnOwnershipMismatch() {
        // Probe-oracle defense: cross-user access returns NotFoundException, not ForbiddenException.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.delete(1L, WORKSPACE_ID, OTHER_USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");

        verify(sessionService, never()).delete(any());
        verify(chatRepository, never()).delete(any());
    }

    @Test
    void testDeleteThrowsNotFoundOnWorkspaceMismatch() {
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.delete(1L, OTHER_WORKSPACE_ID, USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");

        verify(sessionService, never()).delete(any());
        verify(chatRepository, never()).delete(any());
    }

    @Test
    void testCreateThrowsConflictWhenThreadIdBoundToDifferentWorkspace() {
        AiHubChat existing =
            buildChat(7L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        // The threadId is already claimed by a row living in another workspace, so the create must collide rather
        // than return the existing row idempotently.
        existing.setWorkspaceId(OTHER_WORKSPACE_ID);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> chatService.create(WORKSPACE_ID, USER_ID, 0, THREAD_ID))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining(THREAD_ID);

        verify(chatRepository, never()).save(any());
    }

    @Test
    void testCreateThrowsConflictWhenThreadIdOwnedByDifferentUser() {
        long otherUserId = 999L;
        AiHubChat existing =
            buildChat(7L, otherUserId, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(existing));

        // Without the cross-user check the call would fall through to save() and trigger a 500 from the
        // DataIntegrityViolationException — which also leaks a probe oracle that the threadId is taken.
        assertThatThrownBy(() -> chatService.create(WORKSPACE_ID, USER_ID, 0, THREAD_ID))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining(THREAD_ID);

        verify(chatRepository, never()).save(any());
    }

    @Test
    void testCreateWorkflowChatPersistsNewRowWithUuidThreadId() {
        // Always-new semantics: service inserts a fresh chat on every call with kind=WORKFLOW_CHAT, the
        // supplied title, and a UUID-prefixed threadId so chat-memory rows are isolated per chat. The
        // partial unique index that previously enforced one-row-per-(workspace, user, environment, workflow)
        // was dropped in 20260505000001; this method no longer reads from the repository before saving.
        ArgumentCaptor<AiHubChat> captor = ArgumentCaptor.forClass(AiHubChat.class);

        when(chatRepository.save(captor.capture())).thenAnswer(invocation -> {
            AiHubChat captured = invocation.getArgument(0);

            captured.setId(77L);

            return captured;
        });

        AiHubChat result = chatService.createWorkflowChat(
            WORKSPACE_ID, USER_ID, 0, "wf-exec-id", 99L, "Project — Reply Bot");

        assertThat(result.getId()).isEqualTo(77L);
        assertThat(result.getKind()).isEqualTo(AiHubChatKind.WORKFLOW_CHAT);
        assertThat(result.getTitle()).isEqualTo("Project — Reply Bot");
        assertThat(result.getWorkflowExecutionId()).isEqualTo("wf-exec-id");
        assertThat(result.getProjectDeploymentId()).isEqualTo(99L);
        // Pin the threadId shape (plain UUID) without locking the random value. A regression that returned
        // to deterministic threadIds keyed off (workflow_execution_id, user) would re-introduce the
        // session-store cross-talk the always-new design is meant to eliminate. The kind column is the
        // authoritative discriminator for routing.
        assertThat(result.getThreadId())
            .as("threadId must be a plain UUID so session-store events are isolated per chat")
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(result.getVisibility()).isEqualTo(ResourceVisibility.PRIVATE);
    }

    @Test
    void testCreateWorkflowChatProducesDistinctRowsOnEveryCall() {
        // Always-new invariant: two consecutive calls with the same (workspace, user, environment, workflow,
        // deployment) tuple must produce two distinct chat rows with two distinct threadIds. This is
        // the core behavior change from the prior find-or-create design — pin it explicitly so a regression
        // that re-introduces the lookup would fail here.
        when(chatRepository.save(any(AiHubChat.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AiHubChat first = chatService.createWorkflowChat(
            WORKSPACE_ID, USER_ID, 0, "wf-exec-id", 99L, "Project — Reply Bot");
        AiHubChat second = chatService.createWorkflowChat(
            WORKSPACE_ID, USER_ID, 0, "wf-exec-id", 99L, "Project — Reply Bot");

        assertThat(first.getThreadId())
            .as("two clicks on the same workflow row must produce distinct threadIds")
            .isNotEqualTo(second.getThreadId());
    }

    @Test
    void testCreateAgentChatStampsAgentChatKind() {
        // Only the kind separates an agent chat from a workflow chat; everything else — the always-new UUID threadId,
        // the bound execution, the deployment, the initial title — is the shared bridged-chat shape. The kind is what
        // survives an agent being undeployed, which is the whole reason it is persisted rather than re-derived.
        when(chatRepository.save(any(AiHubChat.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AiHubChat result = chatService.createAgentChat(WORKSPACE_ID, USER_ID, 0, "wf-exec-id", 99L, "Agent1");

        assertThat(result.getKind()).isEqualTo(AiHubChatKind.AGENT_CHAT);
        assertThat(result.getTitle()).isEqualTo("Agent1");
        assertThat(result.getWorkflowExecutionId()).isEqualTo("wf-exec-id");
        assertThat(result.getProjectDeploymentId()).isEqualTo(99L);
        assertThat(result.getThreadId())
            .as("threadId must be a plain UUID so session-store events are isolated per chat")
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(result.getVisibility()).isEqualTo(ResourceVisibility.PRIVATE);
    }

    private static AiHubChat buildChat(
        long id, long userId, String threadId, AiHubChatStatus status) {

        AiHubChat chat = new AiHubChat();

        chat.setId(id);
        chat.setUserId(userId);
        chat.setThreadId(threadId);
        chat.setStatus(status);
        chat.setMessageCount(0);
        chat.setCreatedAt(LocalDateTime.now());
        chat.setUpdatedAt(LocalDateTime.now());
        // Fixtures live in WORKSPACE_ID; cross-workspace tests pass OTHER_WORKSPACE_ID as the REQUESTER, which is
        // what the ownership check compares the row's column against.
        chat.setWorkspaceId(WORKSPACE_ID);

        return chat;
    }

    @Test
    void testCancelAiHubRunDelegatesToInFlightRegistryWithChatThreadId() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        when(inFlightRunRegistry.cancel(THREAD_ID, null)).thenReturn(true);

        boolean cancelled = chatService.cancelAiHubRun(1L, WORKSPACE_ID, USER_ID, null);

        assertThat(cancelled).isTrue();
        verify(inFlightRunRegistry).cancel(THREAD_ID, null);
    }

    @Test
    void testCancelAiHubRunReturnsFalseWhenNoRunIsInFlight() {
        // Idempotent: clicking Stop after the run completed must NOT throw — it returns false so the client
        // can render "nothing to cancel" without exception-handling gymnastics.
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        when(inFlightRunRegistry.cancel(THREAD_ID, null)).thenReturn(false);

        assertThat(chatService.cancelAiHubRun(1L, WORKSPACE_ID, USER_ID, null)).isFalse();
    }

    @Test
    void testCancelAiHubRunThrowsOnCrossUserAccess() {
        // Ownership gate: another user can't cancel my run even within the same workspace. Mirrors the
        // probe-oracle defence used elsewhere — same NotFoundException as a missing row to avoid leaking
        // chat existence.
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        long otherUserId = USER_ID + 1;

        assertThatThrownBy(() -> chatService.cancelAiHubRun(1L, WORKSPACE_ID, otherUserId, null))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTruncateMessagesFromDeletesAtAndAfterIndex() {
        // Three messages in chat-memory at t=100, t=200, t=300. Truncating from index 1 should delete t=200
        // and t=300, leaving t=100 intact. The DELETE uses timestamp >= cutoff so any messages at the exact
        // cutoff time are also dropped — which is the right semantic: messages indistinguishable in ordering
        // belong to the same truncation window.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "one", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.ASSISTANT, "two", Instant.ofEpochMilli(200)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "three", Instant.ofEpochMilli(300)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);
        when(sessionRepository.compactEvents(eq(THREAD_ID), eq(List.of()), any(), anyLong())).thenReturn(true);

        when(chatRepository.save(any(AiHubChat.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        int deleted = chatService.truncateMessagesFrom(1L, WORKSPACE_ID, USER_ID, 1);

        assertThat(deleted).isEqualTo(2);

        // Truncating from visible message index 1 keeps only the first event; pin the boundary so an off-by-one in
        // the index-to-event mapping would fail the test.
        ArgumentCaptor<List<org.springframework.ai.session.SessionEvent>> keptCaptor =
            ArgumentCaptor.forClass(List.class);

        verify(sessionRepository).compactEvents(eq(THREAD_ID), eq(List.of()), keptCaptor.capture(), eq(0L));

        assertThat(keptCaptor.getValue()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTruncateMessagesFromIsNoOpForIndexPastEnd() {
        // Index past the end of the history is a no-op rather than an error. This lets the client send "truncate
        // from N" without racing the server on history length — the worst case is no rows deleted, which is
        // recoverable, vs. a 400 the user can't act on.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "one", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.ASSISTANT, "two", Instant.ofEpochMilli(200)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);

        int deleted = chatService.truncateMessagesFrom(1L, WORKSPACE_ID, USER_ID, 5);

        assertThat(deleted).isZero();

        // Nothing replaced and no save() either — the chat row's updatedAt is preserved when nothing
        // changed, so the sidebar doesn't re-sort for an effectively-no-op call.
        verify(sessionRepository, never()).compactEvents(any(), any(), any(), anyLong());
        verify(chatRepository, never()).save(any());
    }

    @Test
    void testTruncateMessagesFromRefusesNegativeIndex() {
        // Negative index would delete every row if mapped through the cutoff path. The service-layer guard
        // makes the contract explicit at the boundary even though the GraphQL surface should reject this
        // earlier — defense in depth catches a future caller that bypasses the GraphQL layer.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.truncateMessagesFrom(1L, WORKSPACE_ID, USER_ID, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void testTruncateMessagesFromRefusesCrossUserAccess() {
        // Probe-oracle defense: cross-user truncation returns the same NotFoundException as a missing row.
        // Without this, the uniform "not found" probe could be circumvented by sending a truncate and
        // observing whether the call short-circuits or proceeds.
        AiHubChat chat =
            buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.truncateMessagesFrom(1L, WORKSPACE_ID, OTHER_USER_ID, 0))
            .isInstanceOf(NotFoundException.class);
    }

    /**
     * A non-owner the chat has been shared with can read it but not manage it — pins that {@code loadMessages} routes
     * through {@link AiHubChatAccessPolicy#canView} while {@code patch} routes through
     * {@link AiHubChatAccessPolicy#canManage}, and the two disagree for a granted non-owner.
     */
    @Test
    void testGrantedNonOwnerCanLoadMessagesButNotPatch() {
        AiHubChatServiceImpl grantedChatService = grantedChatService();
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));
        when(sessionService.getEvents(THREAD_ID)).thenReturn(List.of());

        assertThat(grantedChatService.loadMessages(1L, WORKSPACE_ID, OTHER_USER_ID)).isEmpty();

        assertThatThrownBy(
            () -> grantedChatService.patch(
                1L, WORKSPACE_ID, OTHER_USER_ID, new AiHubChatPatch("New title", null, null, null)))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("AiHubChat not found");
    }

    /**
     * A non-owner may contribute a turn only when the chat's {@link AiHubChatParticipation} allows it — pins that
     * {@code appendAssistantMessage} routes through {@link AiHubChatAccessPolicy#canParticipate}, not merely
     * {@code canView}.
     */
    @Test
    void testParticipateModeNonOwnerCanAppendAssistantMessage() {
        AiHubChatServiceImpl grantedChatService = grantedChatService();
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        chat.setParticipation(AiHubChatParticipation.PARTICIPATE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));
        when(chatRepository.save(any(AiHubChat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        grantedChatService.appendAssistantMessage(1L, WORKSPACE_ID, OTHER_USER_ID, "resumed");

        verify(sessionRepository).appendEvent(any());
    }

    /**
     * The counterpart to {@link #testParticipateModeNonOwnerCanAppendAssistantMessage}: a view-only non-owner is
     * granted {@code canView} but must still be refused a turn.
     */
    @Test
    void testViewModeNonOwnerCannotAppendAssistantMessage() {
        AiHubChatServiceImpl grantedChatService = grantedChatService();
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> grantedChatService.appendAssistantMessage(1L, WORKSPACE_ID, OTHER_USER_ID, "resumed"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");
    }

    /**
     * {@code getByThreadId}'s one production caller ({@code attachAiHubChatTool}) writes a new tool binding —
     * component, connection, arbitrary parameters — into the chat it resolves, so the gate must be {@code canManage},
     * not {@code canView}. Pins the owner side of that: the owner passes regardless of the chat's own
     * {@link AiHubChatParticipation}.
     */
    @Test
    void testGetByThreadIdAllowsOwner() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        assertThat(chatService.getByThreadId(THREAD_ID, WORKSPACE_ID, USER_ID)).isSameAs(chat);
    }

    /**
     * A granted non-owner at the default {@code participation = VIEW} can {@code canView} the chat but must not reach
     * {@code getByThreadId} — under the pre-fix {@code canView} gate this would have succeeded, letting a bystander who
     * was only meant to watch the conversation attach a tool to it.
     */
    @Test
    void testGetByThreadIdRefusesGrantedNonOwnerAtViewParticipation() {
        AiHubChatServiceImpl grantedChatService = grantedChatService();
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> grantedChatService.getByThreadId(THREAD_ID, WORKSPACE_ID, OTHER_USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");
    }

    /**
     * The counterpart to {@link #testGetByThreadIdRefusesGrantedNonOwnerAtViewParticipation}: even a granted non-owner
     * who may contribute turns ({@code participation = PARTICIPATE}) must still be refused — attaching a tool is a
     * management action, not a turn, so {@code canParticipate} granting turns does not extend to it.
     */
    @Test
    void testGetByThreadIdRefusesGrantedNonOwnerAtParticipateParticipation() {
        AiHubChatServiceImpl grantedChatService = grantedChatService();
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        chat.setParticipation(AiHubChatParticipation.PARTICIPATE);

        when(chatRepository.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> grantedChatService.getByThreadId(THREAD_ID, WORKSPACE_ID, OTHER_USER_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("AiHubChat not found");
    }

    /**
     * Pins the union semantics of {@link AiHubChatServiceImpl#listSharedWithMe}: the {@code WORKSPACE}-reach half
     * ({@code chat11}, {@code chat12}) is always included, and the {@code PRIVATE}-candidate half ({@code chat13},
     * {@code chat14}) is narrowed down to the ids {@link ResourceGrantService#filterGrantedResourceIds} reports —
     * {@code chat13} has no grant and must not appear. The merged result is re-sorted by {@code updatedAt} descending
     * rather than trusting the two queries' independent orderings.
     */
    @Test
    void testListSharedWithMeUnionsReachAndGrants() {
        LocalDateTime now = LocalDateTime.now();

        AiHubChat chat11 = buildChat(11L, OTHER_USER_ID, "thread-11", AiHubChatStatus.ACTIVE);
        AiHubChat chat12 = buildChat(12L, OTHER_USER_ID, "thread-12", AiHubChatStatus.ACTIVE);
        AiHubChat chat13 = buildChat(13L, OTHER_USER_ID, "thread-13", AiHubChatStatus.ACTIVE);
        AiHubChat chat14 = buildChat(14L, OTHER_USER_ID, "thread-14", AiHubChatStatus.ACTIVE);

        chat11.setUpdatedAt(now);
        chat12.setUpdatedAt(now.minusMinutes(1));
        chat13.setUpdatedAt(now.minusMinutes(2));
        chat14.setUpdatedAt(now.minusMinutes(3));

        when(
            chatRepository.findSharedByReach(
                WORKSPACE_ID, USER_ID, 0, AiHubChatStatus.ACTIVE.ordinal(), ResourceVisibility.WORKSPACE.ordinal(),
                100))
                    .thenReturn(List.of(chat11, chat12));
        when(
            chatRepository.findPrivateCandidates(
                WORKSPACE_ID, USER_ID, 0, AiHubChatStatus.ACTIVE.ordinal(), ResourceVisibility.PRIVATE.ordinal(),
                500))
                    .thenReturn(List.of(chat13, chat14));
        when(resourceGrantServiceProvider.getIfAvailable()).thenReturn(resourceGrantService);
        when(
            resourceGrantService.filterGrantedResourceIds(
                AiHubChatVisibilityPolicy.RESOURCE_TYPE, USER_ID, List.of(13L, 14L)))
                    .thenReturn(Set.of(14L));

        List<AiHubChat> result = chatServiceWithResourceGrants().listSharedWithMe(WORKSPACE_ID, USER_ID, 0);

        assertThat(result).extracting(AiHubChat::getId)
            .containsExactly(11L, 12L, 14L);
    }

    /**
     * On a CE deployment {@code resourceGrantServiceProvider.getIfAvailable()} returns {@code null} — the module has no
     * bean to hand out. {@code listSharedWithMe} must degrade to the {@code WORKSPACE}-reach half alone rather than
     * throwing, and must not spend a second query on private candidates it has no way to filter.
     */
    @Test
    void testListSharedWithMeWithoutGrantServiceReturnsReachOnly() {
        AiHubChat chat11 = buildChat(11L, OTHER_USER_ID, "thread-11", AiHubChatStatus.ACTIVE);

        when(
            chatRepository.findSharedByReach(
                WORKSPACE_ID, USER_ID, 0, AiHubChatStatus.ACTIVE.ordinal(), ResourceVisibility.WORKSPACE.ordinal(),
                100))
                    .thenReturn(List.of(chat11));
        when(resourceGrantServiceProvider.getIfAvailable()).thenReturn(null);

        List<AiHubChat> result = chatServiceWithResourceGrants().listSharedWithMe(WORKSPACE_ID, USER_ID, 0);

        assertThat(result).extracting(AiHubChat::getId)
            .containsExactly(11L);
        verify(chatRepository, never()).findPrivateCandidates(
            anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Builds a service instance wired with a non-null {@code resourceGrantServiceProvider} — every other test
     * constructor in this class passes a literal {@code null} for it, which only exercises the "module absent entirely"
     * branch, not the "module present, bean unavailable" branch {@code getIfAvailable()} can also return.
     */
    private AiHubChatServiceImpl chatServiceWithResourceGrants() {
        return new AiHubChatServiceImpl(
            chatRepository, turnRepository, new OwnerOnlyAccessPolicy(), jobFacade, jobRegistry, inFlightRunRegistry,
            null, aiHubSessionMemoryProvider, null, null, resourceGrantServiceProvider);
    }

    /**
     * Builds a service instance wired with a policy that grants {@code canView} unconditionally and follows the chat's
     * own {@link AiHubChatParticipation} for {@code canParticipate} — standing in for a workspace member the chat has
     * been shared with, without re-exercising {@link AiHubChatAccessPolicyImpl}'s own visibility-resolution logic
     * (covered by {@code AiHubChatAccessPolicyTest}).
     */
    private AiHubChatServiceImpl grantedChatService() {
        return new AiHubChatServiceImpl(
            chatRepository, turnRepository, new GrantedNonOwnerAccessPolicy(), jobFacade, jobRegistry,
            inFlightRunRegistry, null, aiHubSessionMemoryProvider, null, null, null);
    }

    private static final class GrantedNonOwnerAccessPolicy implements AiHubChatAccessPolicy {

        @Override
        public boolean canView(AiHubChat chat, long userId) {
            return true;
        }

        @Override
        public boolean canParticipate(AiHubChat chat, long userId) {
            return chat.getParticipation() == AiHubChatParticipation.PARTICIPATE;
        }

        @Override
        public boolean canManage(AiHubChat chat, long userId) {
            return chat.getUserId() == userId;
        }
    }

    /**
     * Pins {@code loadMessages}' attribution zip: turn rows are matched to {@code USER} events by their shared ordinal
     * position, not by timestamp or content, so the middle {@code ASSISTANT} row must stay unattributed while the two
     * {@code USER} rows around it pick up the two recorded turns in order.
     */
    @Test
    void testLoadMessagesAssignsAuthorsByUserEventOrder() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "a", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.ASSISTANT, "b", Instant.ofEpochMilli(200)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "c", Instant.ofEpochMilli(300)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);

        AiHubChatTurn firstTurn = new AiHubChatTurn(1L, 3L, "run-1");
        AiHubChatTurn secondTurn = new AiHubChatTurn(1L, 4L, "run-2");

        when(turnRepository.findAllByChatIdOrderByCreatedDateAsc(1L)).thenReturn(List.of(firstTurn, secondTurn));

        List<AiHubChatMessage> messages = chatService.loadMessages(1L, WORKSPACE_ID, USER_ID);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)
            .authorUserId()).isEqualTo(3L);
        assertThat(messages.get(1)
            .authorUserId()).isNull();
        assertThat(messages.get(2)
            .authorUserId()).isEqualTo(4L);
    }

    /**
     * A channel-born chat never goes through the REST dispatch path that calls {@code recordTurn}, so it has no turn
     * rows at all. {@code loadMessages} must leave every {@code authorUserId} null rather than fail or default to the
     * chat owner.
     */
    @Test
    void testLoadMessagesWithoutTurnRowsLeavesAuthorsNull() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "hello", Instant.ofEpochMilli(100)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);
        when(turnRepository.findAllByChatIdOrderByCreatedDateAsc(1L)).thenReturn(List.of());

        List<AiHubChatMessage> messages = chatService.loadMessages(1L, WORKSPACE_ID, USER_ID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)
            .authorUserId()).isNull();
    }

    /**
     * A phantom turn row (a run that registered and then died before writing its first event) shifts every zip position
     * after it, so the pre-fix behavior would attribute the SECOND {@code USER} event to the phantom's sender rather
     * than leaving it unattributed or attributing it to the real third turn. Pins that a turn-row surplus degrades the
     * WHOLE load to null authors instead: both {@code USER} rows come back null, not just the one whose position
     * collides with the phantom.
     */
    @Test
    void testLoadMessagesReturnsNullAuthorsWhenTurnRowsOutnumberUserEvents() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "a", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.ASSISTANT, "b", Instant.ofEpochMilli(200)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "c", Instant.ofEpochMilli(300)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);

        AiHubChatTurn firstTurn = new AiHubChatTurn(1L, 3L, "run-1");
        AiHubChatTurn phantomTurn = new AiHubChatTurn(1L, 4L, "run-phantom");
        AiHubChatTurn thirdTurn = new AiHubChatTurn(1L, 5L, "run-3");

        when(turnRepository.findAllByChatIdOrderByCreatedDateAsc(1L))
            .thenReturn(List.of(firstTurn, phantomTurn, thirdTurn));

        List<AiHubChatMessage> messages = chatService.loadMessages(1L, WORKSPACE_ID, USER_ID);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)
            .authorUserId()).isNull();
        assertThat(messages.get(2)
            .authorUserId()).isNull();
    }

    /**
     * Measures the repository round-trips the {@code /status} poll costs, at the actual database boundary rather than
     * at the service seam. Three thread ids resolve in ONE {@code findAllByThreadIdIn}, and the per-id
     * {@code findByThreadId} the old loop issued once per thread is not called at all — so a tick over N sidebar
     * threads is 1 query, not N.
     *
     * <p>
     * The companion assertion is the one that makes this a comparison rather than a claim: three calls to the
     * single-row {@code findByThreadIdViewable} still cost three {@code findByThreadId} queries, which is exactly what
     * the endpoint used to do per tick.
     * </p>
     */
    @Test
    void testFindAllByThreadIdViewableCostsOneQueryForManyThreads() {
        AiHubChat firstChat = buildChat(1L, USER_ID, "t-1", AiHubChatStatus.ACTIVE);
        AiHubChat secondChat = buildChat(2L, USER_ID, "t-2", AiHubChatStatus.ACTIVE);
        AiHubChat strangersChat = buildChat(3L, OTHER_USER_ID, "t-3", AiHubChatStatus.ACTIVE);

        List<String> threadIds = List.of("t-1", "t-2", "t-3");

        when(chatRepository.findAllByThreadIdIn(threadIds))
            .thenReturn(List.of(firstChat, secondChat, strangersChat));

        Map<String, AiHubChat> chatByThreadId = chatService.findAllByThreadIdViewable(threadIds, USER_ID);

        // The stranger's chat was returned by the batch query and dropped by canView, exactly as the single-row
        // overload would have dropped it — batching saves queries, not authorization.
        assertThat(chatByThreadId).containsOnlyKeys("t-1", "t-2");

        verify(chatRepository, times(1)).findAllByThreadIdIn(threadIds);
        verify(chatRepository, never()).findByThreadId(anyString());

        // The old per-id shape, for comparison: one query per thread.
        when(chatRepository.findByThreadId(anyString())).thenReturn(Optional.empty());

        for (String threadId : threadIds) {
            chatService.findByThreadIdViewable(threadId, USER_ID);
        }

        verify(chatRepository, times(3)).findByThreadId(anyString());
    }

    @Test
    void testFindAllByThreadIdViewableIssuesNoQueryForAnEmptyRequest() {
        assertThat(chatService.findAllByThreadIdViewable(List.of(), USER_ID)).isEmpty();

        verify(chatRepository, never()).findAllByThreadIdIn(any());
    }

    /**
     * The mirror image of the surplus case, and the reachable one: a channel-born chat accumulates {@code USER} session
     * events with no turn rows, and once its owner shares it at {@code PARTICIPATE} the Hub turns that follow add turn
     * rows that are FEWER than the total {@code USER} events. Zipping those rows by position would attribute them to
     * the earliest channel messages instead — naming the wrong people as soon as a second distinct author makes the
     * client render labels at all. Pins that a turn-row deficit degrades the whole load to null authors, exactly as a
     * surplus does.
     */
    @Test
    void testLoadMessagesReturnsNullAuthorsWhenTurnRowsAreFewerThanUserEvents() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "slack-1", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "slack-2", Instant.ofEpochMilli(200)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "hub-1", Instant.ofEpochMilli(300)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);

        AiHubChatTurn hubTurn = new AiHubChatTurn(1L, 4L, "run-1");

        when(turnRepository.findAllByChatIdOrderByCreatedDateAsc(1L)).thenReturn(List.of(hubTurn));

        List<AiHubChatMessage> messages = chatService.loadMessages(1L, WORKSPACE_ID, USER_ID);

        assertThat(messages).hasSize(3);
        assertThat(messages).allSatisfy(
            message -> assertThat(message.authorUserId()).isNull());
    }

    /**
     * Truncation must keep the turn history in step with the visible transcript it truncates, or a later turn is
     * attributed against a row that no longer represents it. Four events (USER, ASSISTANT, USER, ASSISTANT) truncated
     * from visible index 2 (the second USER event) keep only the first USER/ASSISTANT pair — one retained USER event —
     * so the turn table must be trimmed to keep exactly one row.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testTruncateMessagesFromAlsoTruncatesTurns() {
        AiHubChat chat = buildChat(1L, USER_ID, THREAD_ID, AiHubChatStatus.ACTIVE);

        when(chatRepository.findById(1L)).thenReturn(Optional.of(chat));

        List<org.springframework.ai.session.SessionEvent> events = List.of(
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "one", Instant.ofEpochMilli(100)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.ASSISTANT, "two", Instant.ofEpochMilli(200)),
            sessionEvent(org.springframework.ai.chat.messages.MessageType.USER, "three", Instant.ofEpochMilli(300)),
            sessionEvent(
                org.springframework.ai.chat.messages.MessageType.ASSISTANT, "four", Instant.ofEpochMilli(400)));

        when(sessionService.getEvents(THREAD_ID)).thenReturn(events);
        when(sessionRepository.compactEvents(eq(THREAD_ID), eq(List.of()), any(), anyLong())).thenReturn(true);
        when(chatRepository.save(any(AiHubChat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int deleted = chatService.truncateMessagesFrom(1L, WORKSPACE_ID, USER_ID, 2);

        assertThat(deleted).isEqualTo(2);
        verify(turnRepository).deleteFromOrdinal(1L, 1);
    }

    @Test
    void testRecordTurnInsertsARow() {
        ArgumentCaptor<AiHubChatTurn> captor = ArgumentCaptor.forClass(AiHubChatTurn.class);

        when(turnRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        chatService.recordTurn(1L, USER_ID, "run-123");

        AiHubChatTurn saved = captor.getValue();

        assertThat(saved.getChatId()).isEqualTo(1L);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getRunId()).isEqualTo("run-123");
    }
}
