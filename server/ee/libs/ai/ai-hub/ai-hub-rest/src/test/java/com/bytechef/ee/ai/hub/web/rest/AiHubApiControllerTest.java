/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.ee.ai.hub.agent.AiHubChatStreamer;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.approval.AiHubToolApprovalService;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatAccessPolicy;
import com.bytechef.ee.ai.hub.chat.AiHubChatParticipation;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.AiHubChatTurn;
import com.bytechef.ee.ai.hub.metric.AiHubChatSharingMetrics;
import com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry;
import com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.PresenceEntry;
import com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.PresenceState;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.ee.ai.hub.web.rest.AiHubApiController.PresenceRequest;
import com.bytechef.ee.ai.hub.web.rest.AiHubApiController.ThreadStatus;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Hand-built unit tests for {@link AiHubApiController} — the module carries no {@code GraphQlTest}-style harness, so
 * every collaborator is a plain Mockito mock and every endpoint is called directly rather than through {@code MockMvc}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubApiControllerTest {

    private static final long OWNER_USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long WORKSPACE_ID = 7L;
    private static final long CHAT_ID = 100L;
    private static final String THREAD_ID = "thread-1";

    /**
     * A participant the chat has been shared with at {@code PARTICIPATE} — not the chat's owner — may still send a turn
     * through {@code chat}: {@code enforceThreadAccess} gates on {@link AiHubChatAccessPolicy#canParticipate}, stubbed
     * directly here rather than exercised through {@code AiHubChatAccessPolicyImpl}, which has its own test. The
     * recorded turn is attributed to the sender's own id, not the owner's.
     */
    @Test
    void testChatAllowsAParticipantWithParticipateAccessAndRecordsTheTurn() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        LocalAgent askAgent = mock(LocalAgent.class);

        when(askAgent.getAgentId()).thenReturn("ai_hub_ask");

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(askAgent), chatService, accessPolicy, userService,
            workspaceFacade, mock(AiHubToolApprovalService.class));

        User currentUser = buildUser(OTHER_USER_ID);
        Workspace workspace = buildWorkspace(WORKSPACE_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(workspaceFacade.getUserWorkspaces(OTHER_USER_ID)).thenReturn(List.of(workspace));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(CHAT_ID)).thenReturn(WORKSPACE_ID);

        when(accessPolicy.canParticipate(chat, OTHER_USER_ID)).thenReturn(true);
        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(false);

        SseEmitter emitter = new SseEmitter();

        when(chatStreamer.runAgent(eq(askAgent), any(AgUiParameters.class), eq(THREAD_ID))).thenReturn(emitter);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        SseEmitter result = controller.chat(agUiParameters);

        assertThat(result).isSameAs(emitter);
        verify(chatService).recordTurn(CHAT_ID, OTHER_USER_ID, "run-1");
    }

    /**
     * Pins that {@code enforceThreadAccess} gates {@code chat} on {@code canParticipate}, not {@code canView}: a sender
     * with only view-only access (canView passes, canParticipate does not) is rejected with 403 from {@code chat}, yet
     * the same sender reaches {@code attach} successfully — that endpoint gates on viewability alone, since attaching
     * to an in-flight stream is a read, not a turn.
     */
    @Test
    void testChatRejectsAViewOnlyNonOwnerButAttachAllowsIt() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class));

        User currentUser = buildUser(OTHER_USER_ID);
        Workspace workspace = buildWorkspace(WORKSPACE_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(workspaceFacade.getUserWorkspaces(OTHER_USER_ID)).thenReturn(List.of(workspace));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        when(accessPolicy.canView(chat, OTHER_USER_ID)).thenReturn(true);
        when(accessPolicy.canParticipate(chat, OTHER_USER_ID)).thenReturn(false);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        assertThatThrownBy(() -> controller.chat(agUiParameters))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verify(chatService, never()).recordTurn(anyLong(), anyLong(), any());

        SseEmitter emitter = new SseEmitter();

        when(chatStreamer.attachToRun(THREAD_ID)).thenReturn(Optional.of(emitter));

        SseEmitter attachResult = controller.attach(THREAD_ID);

        assertThat(attachResult).isSameAs(emitter);
    }

    @Test
    void testChatThrowsTurnInFlightExceptionNamingTheRunningUser() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class));

        User currentUser = buildUser(OWNER_USER_ID);
        Workspace workspace = buildWorkspace(WORKSPACE_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(workspaceFacade.getUserWorkspaces(OWNER_USER_ID)).thenReturn(List.of(workspace));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(CHAT_ID)).thenReturn(WORKSPACE_ID);
        when(accessPolicy.canParticipate(chat, OWNER_USER_ID)).thenReturn(true);
        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(true);

        AiHubChatTurn runningTurn = new AiHubChatTurn(CHAT_ID, OTHER_USER_ID, "run-running");

        when(chatService.findLatestTurn(CHAT_ID)).thenReturn(Optional.of(runningTurn));

        User runningUser = buildUser(OTHER_USER_ID);

        when(runningUser.getLogin()).thenReturn("alice");
        when(userService.fetchUser(OTHER_USER_ID)).thenReturn(Optional.of(runningUser));

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-new");

        assertThatThrownBy(() -> controller.chat(agUiParameters))
            .isInstanceOf(TurnInFlightException.class)
            .satisfies(exception -> {
                TurnInFlightException turnInFlightException = (TurnInFlightException) exception;

                assertThat(turnInFlightException.getRunningUser()
                    .userId()).isEqualTo(OTHER_USER_ID);
                assertThat(turnInFlightException.getRunningUser()
                    .userName()).isEqualTo("alice");
            });

        verify(chatService, never()).recordTurn(anyLong(), anyLong(), any());
    }

    /**
     * {@code handleTurnInFlight} is the {@code @ExceptionHandler} Spring invokes to turn a thrown
     * {@link TurnInFlightException} into the 409 response body — the actual rejection path a caller experiences.
     */
    @Test
    void testHandleTurnInFlightRecordsATurnConflict() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), mock(AiHubPresenceRegistry.class),
            buildSharingMetrics(meterRegistry));

        TurnInFlightException exception = new TurnInFlightException(
            new TurnInFlightException.RunningUser(OTHER_USER_ID, "alice"));

        ResponseEntity<Map<String, Object>> response = controller.handleTurnInFlight(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(meterRegistry.counter(AiHubChatSharingMetrics.TURN_CONFLICT_COUNTER)
            .count()).isEqualTo(1.0);
    }

    /**
     * Pins the ordering a phantom turn row depends on: {@code recordTurn} must fire only after {@code runAgent} has
     * returned without throwing, never before. A turn recorded ahead of a run that then fails to register would
     * permanently offset {@code loadMessages}' ordinal zip between turn rows and {@code USER} events for every later
     * turn in the chat — misattributing them, not just leaving them unattributed. This test exists so a future refactor
     * that moves {@code recordTurn} back ahead of the run fails here.
     */
    @Test
    void testChatRecordsTheTurnOnlyAfterRunAgentSucceeds() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        LocalAgent askAgent = mock(LocalAgent.class);

        when(askAgent.getAgentId()).thenReturn("ai_hub_ask");

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(askAgent), chatService, accessPolicy, userService,
            workspaceFacade, mock(AiHubToolApprovalService.class));

        User currentUser = buildUser(OWNER_USER_ID);
        Workspace workspace = buildWorkspace(WORKSPACE_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(workspaceFacade.getUserWorkspaces(OWNER_USER_ID)).thenReturn(List.of(workspace));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(CHAT_ID)).thenReturn(WORKSPACE_ID);
        when(accessPolicy.canParticipate(chat, OWNER_USER_ID)).thenReturn(true);
        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(false);

        SseEmitter emitter = new SseEmitter();

        when(chatStreamer.runAgent(eq(askAgent), any(AgUiParameters.class), eq(THREAD_ID))).thenReturn(emitter);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        controller.chat(agUiParameters);

        InOrder order = inOrder(chatStreamer, chatService);

        order.verify(chatStreamer)
            .runAgent(eq(askAgent), any(AgUiParameters.class), eq(THREAD_ID));
        order.verify(chatService)
            .recordTurn(CHAT_ID, OWNER_USER_ID, "run-1");
    }

    /**
     * Companion to {@link #testChatRecordsTheTurnOnlyAfterRunAgentSucceeds}: when the resolved agent variant is not
     * registered, {@code chat} throws 404 before ever reaching {@code runAgent}, and {@code recordTurn} must not have
     * fired either — recording a turn is conditioned on the run actually starting, not merely on the in-flight check
     * having passed.
     */
    @Test
    void testChatRecordsNoTurnWhenTheAgentVariantIsUnregistered() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class));

        User currentUser = buildUser(OWNER_USER_ID);
        Workspace workspace = buildWorkspace(WORKSPACE_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(workspaceFacade.getUserWorkspaces(OWNER_USER_ID)).thenReturn(List.of(workspace));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(CHAT_ID)).thenReturn(WORKSPACE_ID);
        when(accessPolicy.canParticipate(chat, OWNER_USER_ID)).thenReturn(true);
        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(false);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        assertThatThrownBy(() -> controller.chat(agUiParameters))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        verify(chatService, never()).recordTurn(anyLong(), anyLong(), any());
        verify(chatStreamer, never()).runAgent(any(), any(), any());
    }

    @Test
    void testInFlightStatusDelegatesToStatusAndAnswersFalseForOmittedThreads() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OTHER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(inFlightRunRegistry.getInFlightThreadIds()).thenReturn(List.of("t-viewable", "t-not-viewable"));

        AiHubChat viewableChat = buildChat(CHAT_ID, OWNER_USER_ID);
        AiHubChat neverInFlightChat = buildChat(CHAT_ID + 1, OWNER_USER_ID);

        // "t-not-viewable" and "t-unknown" are simply absent from the batch's result, which is how the batch
        // expresses both "no such thread" and "not yours" — the same conflation the single-row lookup made.
        when(chatService.findAllByThreadIdViewable(anyCollection(), eq(OTHER_USER_ID)))
            .thenReturn(Map.of("t-viewable", viewableChat, "t-never-in-flight", neverInFlightChat));
        when(presenceRegistry.presence(any())).thenReturn(List.of());

        Map<String, Boolean> result = controller.inFlightStatus(
            List.of("t-viewable", "t-not-viewable", "t-unknown", "t-never-in-flight"));

        assertThat(result).containsEntry("t-viewable", true)
            .containsEntry("t-not-viewable", false)
            .containsEntry("t-unknown", false)
            .containsEntry("t-never-in-flight", false);
    }

    @Test
    void testStatusReportsInFlightRunningUserMessageCountAndPresence() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OWNER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(inFlightRunRegistry.getInFlightThreadIds()).thenReturn(List.of(THREAD_ID));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        chat.setMessageCount(5);
        chat.setUpdatedAt(LocalDateTime.of(2026, 9, 2, 10, 0, 0));

        when(chatService.findAllByThreadIdViewable(anyCollection(), eq(OWNER_USER_ID)))
            .thenReturn(Map.of(THREAD_ID, chat));

        AiHubChatTurn runningTurn = new AiHubChatTurn(CHAT_ID, OTHER_USER_ID, "run-running");

        when(chatService.findLatestTurn(CHAT_ID)).thenReturn(Optional.of(runningTurn));

        User runningUser = buildUser(OTHER_USER_ID);

        when(runningUser.getLogin()).thenReturn("alice");
        when(userService.fetchUser(OTHER_USER_ID)).thenReturn(Optional.of(runningUser));

        List<PresenceEntry> presenceEntries = List.of(
            new PresenceEntry(OWNER_USER_ID, "owner", PresenceState.VIEWING, Instant.parse("2026-09-02T10:00:00Z")));

        when(presenceRegistry.presence(THREAD_ID)).thenReturn(presenceEntries);

        Map<String, ThreadStatus> result = controller.status(List.of(THREAD_ID));

        ThreadStatus threadStatus = result.get(THREAD_ID);

        assertThat(threadStatus).isNotNull();
        assertThat(threadStatus.inFlight()).isTrue();
        assertThat(threadStatus.runningUserId()).isEqualTo(OTHER_USER_ID);
        assertThat(threadStatus.runningUserName()).isEqualTo("alice");
        assertThat(threadStatus.messageCount()).isEqualTo(5);
        assertThat(threadStatus.presence()).isEqualTo(presenceEntries);
    }

    @Test
    void testStatusOmitsThreadsTheCallerCannotView() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OTHER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(inFlightRunRegistry.getInFlightThreadIds()).thenReturn(List.of());
        when(chatService.findAllByThreadIdViewable(anyCollection(), eq(OTHER_USER_ID))).thenReturn(Map.of());

        Map<String, ThreadStatus> result = controller.status(List.of("t-hidden"));

        assertThat(result).doesNotContainKey("t-hidden");
        assertThat(result).isEmpty();
    }

    /**
     * The sidebar polls this endpoint with its whole thread list every 20 seconds, and the {@code /in-flight} endpoint
     * it replaced short-circuited on the in-flight set before touching the database, so a per-id lookup turned a free
     * tick into N round-trips. Pins that the endpoint resolves every supplied id in ONE service call regardless of how
     * many are supplied, and never falls back to the single-row lookup.
     *
     * <p>
     * Batching cannot widen access: the batch applies the same {@code canView} predicate per row inside the service, so
     * a thread the caller may not view is absent from its result exactly as it was from {@code findByThreadIdViewable},
     * and the loop below can only report on ids the batch returned.
     * </p>
     */
    @Test
    void testStatusResolvesEverySuppliedThreadInOneLookup() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OWNER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(inFlightRunRegistry.getInFlightThreadIds()).thenReturn(List.of());
        when(presenceRegistry.presence(any())).thenReturn(List.of());

        AiHubChat firstChat = buildChat(CHAT_ID, OWNER_USER_ID);
        AiHubChat secondChat = buildChat(CHAT_ID + 1, OWNER_USER_ID);
        AiHubChat thirdChat = buildChat(CHAT_ID + 2, OWNER_USER_ID);

        when(chatService.findAllByThreadIdViewable(anyCollection(), eq(OWNER_USER_ID)))
            .thenReturn(Map.of("t-1", firstChat, "t-2", secondChat, "t-3", thirdChat));

        Map<String, ThreadStatus> result = controller.status(List.of("t-1", "t-2", "t-3", "t-hidden"));

        assertThat(result).containsOnlyKeys("t-1", "t-2", "t-3");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> threadIdsCaptor = ArgumentCaptor.forClass(Collection.class);

        verify(chatService, times(1)).findAllByThreadIdViewable(threadIdsCaptor.capture(), eq(OWNER_USER_ID));

        assertThat(threadIdsCaptor.getValue()).containsExactly("t-1", "t-2", "t-3", "t-hidden");

        verify(chatService, never()).findByThreadIdViewable(anyString(), anyLong());
    }

    /**
     * Enumeration safety on the two endpoints that resolve a thread through {@code enforceThreadViewable}. A caller who
     * guesses a thread id must not be able to tell a chat that does not exist from one that exists but is not theirs,
     * so a non-viewable thread answers exactly as an unknown one does — same status, same reason. The shared-sessions
     * spec's error table fixes that answer as not-found.
     */
    @Test
    void testAttachAnswersAnUnknownAndANonViewableThreadIdentically() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OTHER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(chatService.findByThreadId("unknown-thread")).thenReturn(Optional.empty());

        ResponseStatusException unknownThreadException = catchResponseStatusException(
            () -> controller.attach("unknown-thread"));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(accessPolicy.canView(chat, OTHER_USER_ID)).thenReturn(false);

        ResponseStatusException nonViewableThreadException = catchResponseStatusException(
            () -> controller.attach(THREAD_ID));

        assertThat(unknownThreadException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(nonViewableThreadException.getStatusCode()).isEqualTo(unknownThreadException.getStatusCode());
        assertThat(nonViewableThreadException.getReason()).isEqualTo(unknownThreadException.getReason());

        verify(chatStreamer, never()).attachToRun(anyString());
    }

    /**
     * The {@code presence} half of {@link #testAttachAnswersAnUnknownAndANonViewableThreadIdentically} — the same
     * {@code enforceThreadViewable} gate, reached from the endpoint the client's heartbeat drives.
     */
    @Test
    void testPresenceAnswersAnUnknownAndANonViewableThreadIdentically() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OTHER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(chatService.findByThreadId("unknown-thread")).thenReturn(Optional.empty());

        ResponseStatusException unknownThreadException = catchResponseStatusException(
            () -> controller.presence("unknown-thread", new PresenceRequest("VIEWING")));

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(accessPolicy.canView(chat, OTHER_USER_ID)).thenReturn(false);

        ResponseStatusException nonViewableThreadException = catchResponseStatusException(
            () -> controller.presence(THREAD_ID, new PresenceRequest("VIEWING")));

        assertThat(unknownThreadException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(nonViewableThreadException.getStatusCode()).isEqualTo(unknownThreadException.getStatusCode());
        assertThat(nonViewableThreadException.getReason()).isEqualTo(unknownThreadException.getReason());

        verify(presenceRegistry, never()).heartbeat(any(), anyLong(), any(), any());
        verify(presenceRegistry, never()).leave(any(), anyLong());
    }

    @Test
    void testPresenceHeartbeatRecordsTheRequestedState() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OWNER_USER_ID);

        when(currentUser.getLogin()).thenReturn("owner");
        when(userService.getCurrentUser()).thenReturn(currentUser);

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(accessPolicy.canView(chat, OWNER_USER_ID)).thenReturn(true);

        controller.presence(THREAD_ID, new PresenceRequest("TYPING"));

        verify(presenceRegistry).heartbeat(THREAD_ID, OWNER_USER_ID, "owner", PresenceState.TYPING);
        verify(presenceRegistry, never()).leave(any(), anyLong());
    }

    @Test
    void testPresenceLeftRemovesThePresenceEntryInsteadOfHeartbeating() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OWNER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(accessPolicy.canView(chat, OWNER_USER_ID)).thenReturn(true);

        controller.presence(THREAD_ID, new PresenceRequest("LEFT"));

        verify(presenceRegistry).leave(THREAD_ID, OWNER_USER_ID);
        verify(presenceRegistry, never()).heartbeat(any(), anyLong(), any(), any());
    }

    /**
     * {@code PresenceState.valueOf} throws {@link IllegalArgumentException} on an unrecognized name, which would
     * surface as a 500. A malformed request body is the caller's error, so the endpoint answers 400 and records
     * nothing.
     */
    @Test
    void testPresenceRejectsAnUnknownStateWithBadRequest() {
        AiHubChatStreamer chatStreamer = mock(AiHubChatStreamer.class);
        InFlightAiHubRunRegistry inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatAccessPolicy accessPolicy = mock(AiHubChatAccessPolicy.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);
        AiHubPresenceRegistry presenceRegistry = mock(AiHubPresenceRegistry.class);

        AiHubApiController controller = newController(
            chatStreamer, inFlightRunRegistry, List.of(), chatService, accessPolicy, userService, workspaceFacade,
            mock(AiHubToolApprovalService.class), presenceRegistry);

        User currentUser = buildUser(OWNER_USER_ID);

        when(userService.getCurrentUser()).thenReturn(currentUser);

        AiHubChat chat = buildChat(CHAT_ID, OWNER_USER_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(accessPolicy.canView(chat, OWNER_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> controller.presence(THREAD_ID, new PresenceRequest("SLEEPING")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> controller.presence(THREAD_ID, new PresenceRequest(null)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(presenceRegistry, never()).heartbeat(any(), anyLong(), any(), any());
        verify(presenceRegistry, never()).leave(any(), anyLong());
    }

    private static AiHubApiController newController(
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry, List<LocalAgent> localAgents,
        AiHubChatService chatService, AiHubChatAccessPolicy accessPolicy, UserService userService,
        WorkspaceFacade workspaceFacade, AiHubToolApprovalService toolApprovalService) {

        return newController(
            chatStreamer, inFlightRunRegistry, localAgents, chatService, accessPolicy, userService, workspaceFacade,
            toolApprovalService, mock(AiHubPresenceRegistry.class));
    }

    private static AiHubApiController newController(
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry, List<LocalAgent> localAgents,
        AiHubChatService chatService, AiHubChatAccessPolicy accessPolicy, UserService userService,
        WorkspaceFacade workspaceFacade, AiHubToolApprovalService toolApprovalService,
        AiHubPresenceRegistry presenceRegistry) {

        return newController(
            chatStreamer, inFlightRunRegistry, localAgents, chatService, accessPolicy, userService, workspaceFacade,
            toolApprovalService, presenceRegistry, buildSharingMetrics(new SimpleMeterRegistry()));
    }

    @SuppressWarnings("unchecked")
    private static AiHubApiController newController(
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry, List<LocalAgent> localAgents,
        AiHubChatService chatService, AiHubChatAccessPolicy accessPolicy, UserService userService,
        WorkspaceFacade workspaceFacade, AiHubToolApprovalService toolApprovalService,
        AiHubPresenceRegistry presenceRegistry, AiHubChatSharingMetrics sharingMetrics) {

        ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider = mock(ObjectProvider.class);

        lenient().when(toolApprovalServiceProvider.getIfAvailable())
            .thenReturn(toolApprovalService);

        return new AiHubApiController(
            chatStreamer, inFlightRunRegistry, localAgents, chatService, accessPolicy, userService, workspaceFacade,
            toolApprovalServiceProvider, presenceRegistry, sharingMetrics);
    }

    @SuppressWarnings("unchecked")
    private static AiHubChatSharingMetrics buildSharingMetrics(MeterRegistry meterRegistry) {
        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        lenient().when(meterRegistryProvider.getIfAvailable())
            .thenReturn(meterRegistry);

        return new AiHubChatSharingMetrics(meterRegistryProvider);
    }

    private static AgUiParameters buildAgUiParameters(long workspaceId, String threadId, String runId) {
        AgUiParameters agUiParameters = new AgUiParameters();
        State state = new State();

        state.set(AiHubStateKeys.WORKSPACE_ID, workspaceId);

        agUiParameters.setState(state);
        agUiParameters.setThreadId(threadId);
        agUiParameters.setRunId(runId);

        return agUiParameters;
    }

    private static AiHubChat buildChat(long id, long ownerUserId) {
        AiHubChat chat = new AiHubChat(ownerUserId);

        chat.setId(id);
        chat.setThreadId(THREAD_ID);
        chat.setParticipation(AiHubChatParticipation.PARTICIPATE);
        chat.setUpdatedAt(LocalDateTime.of(2026, 9, 2, 10, 0, 0));

        return chat;
    }

    /**
     * Runs {@code callable} and returns the {@link ResponseStatusException} it threw, so a test can compare two calls'
     * status AND reason against each other rather than asserting one expected value per call — which is what an
     * enumeration-safety claim actually requires.
     */
    private static ResponseStatusException catchResponseStatusException(Runnable callable) {
        try {
            callable.run();
        } catch (ResponseStatusException exception) {
            return exception;
        }

        throw new AssertionError("Expected a ResponseStatusException, but none was thrown");
    }

    private static User buildUser(long id) {
        User user = mock(User.class);

        lenient().when(user.getId())
            .thenReturn(id);

        return user;
    }

    private static Workspace buildWorkspace(long id) {
        Workspace workspace = mock(Workspace.class);

        when(workspace.getId()).thenReturn(id);

        return workspace;
    }
}
