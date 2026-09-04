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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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

        // The chat has been shared at PARTICIPATE; the sender (OTHER_USER_ID) is not the owner. The access policy —
        // not this test — decides what PARTICIPATE means, so it is stubbed directly rather than exercising
        // AiHubChatAccessPolicyImpl here.
        when(accessPolicy.canParticipate(chat, OTHER_USER_ID)).thenReturn(true);
        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(false);

        SseEmitter emitter = new SseEmitter();

        when(chatStreamer.runAgent(eq(askAgent), any(AgUiParameters.class), eq(THREAD_ID))).thenReturn(emitter);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        SseEmitter result = controller.chat(agUiParameters);

        assertThat(result).isSameAs(emitter);
        verify(chatService).recordTurn(CHAT_ID, OTHER_USER_ID, "run-1");
    }

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

        // View-only participation: canView passes, canParticipate does not.
        when(accessPolicy.canView(chat, OTHER_USER_ID)).thenReturn(true);
        when(accessPolicy.canParticipate(chat, OTHER_USER_ID)).thenReturn(false);

        AgUiParameters agUiParameters = buildAgUiParameters(WORKSPACE_ID, THREAD_ID, "run-1");

        assertThatThrownBy(() -> controller.chat(agUiParameters))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verify(chatService, never()).recordTurn(anyLong(), anyLong(), any());

        // The same view-only sender reaches attach() without error — viewability, not participability, gates it.
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

    @Test
    void testInFlightStatusReportsTrueOnlyForViewableInFlightIds() {
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

        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(inFlightRunRegistry.getInFlightThreadIds()).thenReturn(List.of("t-viewable", "t-not-viewable"));

        AiHubChat viewableChat = buildChat(CHAT_ID, OWNER_USER_ID);
        AiHubChat notViewableChat = buildChat(CHAT_ID + 1, OWNER_USER_ID);

        when(chatService.findByThreadId("t-viewable")).thenReturn(Optional.of(viewableChat));
        when(accessPolicy.canView(viewableChat, OTHER_USER_ID)).thenReturn(true);

        when(chatService.findByThreadId("t-not-viewable")).thenReturn(Optional.of(notViewableChat));
        when(accessPolicy.canView(notViewableChat, OTHER_USER_ID)).thenReturn(false);

        when(chatService.findByThreadId("t-unknown")).thenReturn(Optional.empty());

        Map<String, Boolean> result = controller.inFlightStatus(
            List.of("t-viewable", "t-not-viewable", "t-unknown", "t-never-in-flight"));

        assertThat(result).containsEntry("t-viewable", true)
            .containsEntry("t-not-viewable", false)
            .containsEntry("t-unknown", false)
            .containsEntry("t-never-in-flight", false);
    }

    @SuppressWarnings("unchecked")
    private static AiHubApiController newController(
        AiHubChatStreamer chatStreamer, InFlightAiHubRunRegistry inFlightRunRegistry, List<LocalAgent> localAgents,
        AiHubChatService chatService, AiHubChatAccessPolicy accessPolicy, UserService userService,
        WorkspaceFacade workspaceFacade, AiHubToolApprovalService toolApprovalService) {

        ObjectProvider<AiHubToolApprovalService> toolApprovalServiceProvider = mock(ObjectProvider.class);

        lenient().when(toolApprovalServiceProvider.getIfAvailable())
            .thenReturn(toolApprovalService);

        return new AiHubApiController(
            chatStreamer, inFlightRunRegistry, localAgents, chatService, accessPolicy, userService, workspaceFacade,
            toolApprovalServiceProvider);
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

        return chat;
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
