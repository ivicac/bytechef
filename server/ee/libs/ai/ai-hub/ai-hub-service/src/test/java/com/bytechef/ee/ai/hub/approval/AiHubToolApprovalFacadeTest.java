/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agui.core.message.UserMessage;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.bytechef.ai.copilot.tool.SecurityContextRehydrator;
import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.ee.ai.hub.agent.AiHubChatStreamer;
import com.bytechef.ee.ai.hub.agent.AiHubSpringAIAgent;
import com.bytechef.ee.ai.hub.agent.InFlightAiHubRunRegistry;
import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatAccessPolicy;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.OwnerOnlyAccessPolicy;
import com.bytechef.ee.ai.hub.exception.ConflictException;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.ai.hub.toolsearch.AiHubChatBindingToolCallbackResolver;
import com.bytechef.ee.ai.hub.toolsearch.AiHubGlobalToolCatalog;
import com.bytechef.ee.ai.hub.util.AiHubStateKeys;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.AuthorityService;
import com.bytechef.platform.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalFacadeTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long CHAT_OWNER_ID = 3L;
    private static final long CHAT_ID = 10L;
    private static final long INSTANCE_ADMIN_ID = 8L;
    private static final long PARTICIPANT_ID = 5L;
    private static final String ADMIN_LOGIN = "admin";
    private static final String OWNER_LOGIN = "owner";
    private static final String PARTICIPANT_LOGIN = "participant";
    private static final String THREAD_ID = "thread-1";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    private AiHubToolApprovalService approvalService;
    private AiHubChatService chatService;
    private UserService userService;
    private AiHubChatStreamer chatStreamer;
    private InFlightAiHubRunRegistry inFlightRunRegistry;
    private AiHubChatBindingToolCallbackResolver chatBindingResolver;
    private AiHubGlobalToolCatalog askGlobalToolCatalog;
    private AiHubGlobalToolCatalog buildGlobalToolCatalog;
    private AiHubSpringAIAgent askSpringAIAgent;
    private AiHubSpringAIAgent buildSpringAIAgent;
    private LocalAgent buildAgent;
    private SecurityContextRehydrator securityContextRehydrator;
    private AiHubAuditPublisher auditPublisher;
    private ToolCallback sendEmailCallback;
    private PlatformTransactionManager transactionManager;
    private AiHubToolApprovalFacadeImpl facade;

    @BeforeEach
    void setUp() {
        approvalService = mock(AiHubToolApprovalService.class);
        chatService = mock(AiHubChatService.class);
        userService = mock(UserService.class);
        chatStreamer = mock(AiHubChatStreamer.class);
        inFlightRunRegistry = mock(InFlightAiHubRunRegistry.class);
        chatBindingResolver = mock(AiHubChatBindingToolCallbackResolver.class);

        ToolDefinition sendEmailDefinition = mock(ToolDefinition.class);

        when(sendEmailDefinition.name()).thenReturn("sendEmail");

        sendEmailCallback = mock(ToolCallback.class);

        when(sendEmailCallback.getToolDefinition()).thenReturn(sendEmailDefinition);

        askGlobalToolCatalog = new AiHubGlobalToolCatalog("ask-session", List.of());
        buildGlobalToolCatalog = new AiHubGlobalToolCatalog("build-session", List.of(sendEmailCallback));

        askSpringAIAgent = mock(AiHubSpringAIAgent.class);
        buildSpringAIAgent = mock(AiHubSpringAIAgent.class);

        when(askSpringAIAgent.pinnedToolCallbacks()).thenReturn(List.of());
        when(buildSpringAIAgent.pinnedToolCallbacks()).thenReturn(List.of());

        buildAgent = mock(LocalAgent.class);

        when(buildAgent.getAgentId()).thenReturn("ai_hub_build");

        AiHubChat chat = new AiHubChat(CHAT_OWNER_ID);

        chat.setId(CHAT_ID);
        chat.setThreadId(THREAD_ID);
        chat.setWorkspaceId(WORKSPACE_ID);

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatBindingResolver.resolve(any())).thenReturn(List.of());
        when(approvalService.save(any(AiHubToolApproval.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User owner = user(CHAT_OWNER_ID, OWNER_LOGIN);
        User participant = user(PARTICIPANT_ID, PARTICIPANT_LOGIN);
        User instanceAdmin = user(INSTANCE_ADMIN_ID, ADMIN_LOGIN);

        when(userService.getCurrentUser()).thenReturn(owner);
        when(userService.fetchUser(CHAT_OWNER_ID)).thenReturn(Optional.of(owner));
        when(userService.fetchUser(PARTICIPANT_ID)).thenReturn(Optional.of(participant));
        when(userService.fetchUser(INSTANCE_ADMIN_ID)).thenReturn(Optional.of(instanceAdmin));

        securityContextRehydrator = new SecurityContextRehydrator(userService, mock(AuthorityService.class));
        auditPublisher = mock(AiHubAuditPublisher.class);

        transactionManager = noopTransactionManager();

        facade = newFacade(new OwnerOnlyAccessPolicy());

        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(OWNER_LOGIN, "n/a", List.of()));
    }

    /**
     * Builds a facade over the shared collaborators with the given access policy, so the tests that need an
     * instance-admin resolver can supply a policy that admits one without every other test losing the real
     * owner-vs-stranger behaviour {@link OwnerOnlyAccessPolicy} gives them.
     */
    private AiHubToolApprovalFacadeImpl newFacade(AiHubChatAccessPolicy accessPolicy) {
        return new AiHubToolApprovalFacadeImpl(
            accessPolicy, approvalService, chatService, userService, chatStreamer, inFlightRunRegistry,
            chatBindingResolver, askGlobalToolCatalog, buildGlobalToolCatalog, askSpringAIAgent, buildSpringAIAgent,
            List.of(buildAgent), securityContextRehydrator, auditPublisher, null,
            new AiHubToolApprovalMetrics(emptyMeterRegistryProvider()), transactionManager, clock);
    }

    private static User user(long id, String login) {
        User user = mock(User.class);

        when(user.getId()).thenReturn(id);
        when(user.getLogin()).thenReturn(login);
        when(user.getAuthorityIds()).thenReturn(List.of());

        return user;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AiHubToolApproval pendingApproval(String toolName, String mode) {
        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setId(99L);
        approval.setChatId(CHAT_ID);
        approval.setThreadId(THREAD_ID);
        approval.setRequestedByUserId(CHAT_OWNER_ID);
        approval.setToolKind(AiHubToolApproval.ToolKind.CATALOG);
        approval.setToolName(toolName);
        approval.setArguments("{\"to\":\"a@b.com\"}");
        approval.setStatus(AiHubToolApproval.Status.PENDING);
        approval.setMode(mode);
        approval.setEnvironment(0);
        approval.setExpiresAt(Instant.now(clock)
            .plusSeconds(3600));

        when(approvalService.get(99L)).thenReturn(approval);

        return approval;
    }

    @Test
    void testStrangerCannotResolve() {
        pendingApproval("sendEmail", "BUILD");

        User stranger = mock(User.class);

        when(stranger.getId()).thenReturn(4L);
        when(userService.getCurrentUser()).thenReturn(stranger);

        assertThatThrownBy(() -> facade.resolve(WORKSPACE_ID, 99L, true, null))
            .isInstanceOf(NotFoundException.class);

        verify(chatStreamer, never()).runAgent(any(), any(), any());
    }

    /**
     * Regression test for the transaction-rollback bug: the expiry write must be committed independently of the
     * {@code ConflictException} the same call throws, so it asserts on the row actually handed to
     * {@code approvalService.save} (the persistence boundary) rather than on the in-memory {@code approval} reference —
     * mutating a Java object survives a hypothetical rollback regardless of whether the write itself did, so asserting
     * on the mutated reference alone cannot tell a correct implementation from a rolled-back one.
     *
     * <p>
     * The second assertion — that {@link #transactionManager} never opened a transaction for this call — is the
     * property that actually distinguishes the fixed code from the original bug: the original {@code decide(...)} ran
     * the whole method, expiry branch included, inside {@code transactionTemplate.execute(...)}, which always calls
     * {@code PlatformTransactionManager.getTransaction(...)}. Under a real transaction manager that transaction would
     * roll back on the very {@code ConflictException} this path throws, silently discarding the {@code EXPIRED} write —
     * a failure a plain saved-argument assertion cannot detect, since {@link #approvalService} is itself a mock with no
     * real transactional behaviour to roll back. This test would have failed against the pre-fix code: expiry ran
     * inside the transactional block, so {@code getTransaction} would have been invoked.
     * </p>
     */
    @Test
    void testExpiredRowFlipsToExpired() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        approval.setExpiresAt(Instant.now(clock)
            .minusSeconds(1));

        assertThatThrownBy(() -> facade.resolve(WORKSPACE_ID, 99L, true, null))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("expired");

        ArgumentCaptor<AiHubToolApproval> savedCaptor = ArgumentCaptor.forClass(AiHubToolApproval.class);

        verify(approvalService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.EXPIRED);

        verify(transactionManager, never()).getTransaction(any());
        verify(chatStreamer, never()).runAgent(any(), any(), any());
    }

    /**
     * Two concurrent resolves both reading {@code PENDING}: the loser's decision-persisting save fails the row's
     * optimistic {@code @Version} check, surfaced by Spring Data as {@link OptimisticLockingFailureException}. The
     * facade must translate that into the same not-found-shaped conflict every other enumeration-safe failure uses,
     * rather than leaking the persistence exception — and must never reach tool execution for the losing call.
     */
    @Test
    void testConcurrentResolveTranslatesOptimisticLockFailureToNotFound() {
        pendingApproval("sendEmail", "BUILD");

        when(approvalService.save(any(AiHubToolApproval.class)))
            .thenThrow(new OptimisticLockingFailureException("stale version"));

        assertThatThrownBy(() -> facade.resolve(WORKSPACE_ID, 99L, true, null))
            .isInstanceOf(NotFoundException.class);

        verify(sendEmailCallback, never()).call(any(), any());
        verify(chatStreamer, never()).runAgent(any(), any(), any());
    }

    @Test
    void testRejectRecordsDecisionAndStartsContinuationWithoutExecuting() {
        pendingApproval("sendEmail", "BUILD");

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, false, "not now");

        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.REJECTED);
        assertThat(resolution.approval()
            .getDecidedByUserId()).isEqualTo(CHAT_OWNER_ID);
        assertThat(resolution.approval()
            .getComment()).isEqualTo("not now");
        assertThat(resolution.continuationStarted()).isTrue();

        verify(sendEmailCallback, never()).call(any(), any());

        ArgumentCaptor<AgUiParameters> parametersCaptor = ArgumentCaptor.forClass(AgUiParameters.class);

        verify(chatStreamer).runAgent(any(), parametersCaptor.capture(), eq(THREAD_ID));

        String content = ((UserMessage) parametersCaptor.getValue()
            .getMessages()
            .get(0)).getContent();

        assertThat(content).startsWith("[tool-approval #");
        assertThat(content).contains("rejected");
    }

    @Test
    void testApproveExecutesTheStoredArguments() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        when(sendEmailCallback.call(eq(approval.getArguments()), any())).thenReturn("{\"sent\":true}");

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.APPROVED);

        verify(sendEmailCallback).call(eq(approval.getArguments()), any());

        ArgumentCaptor<AgUiParameters> parametersCaptor = ArgumentCaptor.forClass(AgUiParameters.class);

        verify(chatStreamer).runAgent(any(), parametersCaptor.capture(), eq(THREAD_ID));

        String content = ((UserMessage) parametersCaptor.getValue()
            .getMessages()
            .get(0)).getContent();

        assertThat(content).contains("{\"sent\":true}");
    }

    /**
     * A participant raised the approval and the OWNER resolved it. Everything that reproduces the requester's turn —
     * the tool population {@code findCallback} searches, the executed call's tool context, and the continuation's run
     * state — must follow the REQUESTER, with the chat's owner travelling separately as {@code ownerUserId}. Passing
     * the resolver instead makes {@code AiHubChatBindingToolCallbackResolver} compute
     * {@code senderIsOwner = Objects.equals(ownerUserId, userId)} as true, which un-gates the owner's user-global
     * connectors, external MCP servers and AI skills inside a turn the participant is still driving, and hands the
     * whole continuation the same population.
     */
    @Test
    void testResolvingAParticipantsApprovalKeepsTheRequesterAsTheTurnsUser() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        approval.setRequestedByUserId(PARTICIPANT_ID);

        when(sendEmailCallback.call(eq(approval.getArguments()), any())).thenReturn("{\"sent\":true}");

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(resolution.approval()
            .getDecidedByUserId()).isEqualTo(CHAT_OWNER_ID);

        ArgumentCaptor<AiHubToolInvocationContext> invocationContextCaptor =
            ArgumentCaptor.forClass(AiHubToolInvocationContext.class);

        verify(chatBindingResolver).resolve(invocationContextCaptor.capture());

        AiHubToolInvocationContext invocationContext = invocationContextCaptor.getValue();

        assertThat(invocationContext.userId()).isEqualTo(PARTICIPANT_ID);
        assertThat(invocationContext.ownerUserId()).isEqualTo(CHAT_OWNER_ID);

        ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

        verify(sendEmailCallback).call(eq(approval.getArguments()), toolContextCaptor.capture());

        Map<String, Object> toolContext = toolContextCaptor.getValue()
            .getContext();

        assertThat(toolContext.get(AiHubToolInvocationContext.TOOL_CONTEXT_USER_ID_KEY)).isEqualTo(PARTICIPANT_ID);
        assertThat(toolContext.get(AiHubToolInvocationContext.TOOL_CONTEXT_OWNER_USER_ID_KEY))
            .isEqualTo(CHAT_OWNER_ID);

        ArgumentCaptor<AgUiParameters> parametersCaptor = ArgumentCaptor.forClass(AgUiParameters.class);

        verify(chatStreamer).runAgent(any(), parametersCaptor.capture(), eq(THREAD_ID));

        Map<String, Object> stateMap = parametersCaptor.getValue()
            .getState()
            .getState();

        assertThat(stateMap.get(AiHubStateKeys.AUTHENTICATED_USER_ID)).isEqualTo(PARTICIPANT_ID);
        assertThat(stateMap.get(AiHubStateKeys.USER_ID)).isEqualTo(PARTICIPANT_ID);
        assertThat(stateMap.get(AiHubStateKeys.VERIFIED_OWNER_USER_ID)).isEqualTo(CHAT_OWNER_ID);
    }

    /**
     * Regression test for the pinned-tool gap: {@code findCallback} used to search only the chat-bound and global
     * catalog populations, so a gated PINNED tool (e.g. the BUILD agent's {@code deleteProjectDeployment}, added
     * directly to the agent's builder rather than the searchable catalog) could never be found again at resolve time.
     * Approving it hit the {@code callbackOptional.isEmpty()} branch, flipped the row to {@code FAILED} with "no longer
     * available in this chat", and the destructive action never ran — this test fails against that code because
     * {@link #sendEmailCallback} is absent from BOTH catalogs and both {@code chatBindingResolver.resolve} results, and
     * is registered ONLY on {@link #buildSpringAIAgent}'s pinned tool list.
     */
    @Test
    void testApprovePinnedToolActuallyExecutes() {
        ToolDefinition deleteDeploymentDefinition = mock(ToolDefinition.class);

        when(deleteDeploymentDefinition.name()).thenReturn("deleteProjectDeployment");

        ToolCallback deleteDeploymentCallback = mock(ToolCallback.class);

        when(deleteDeploymentCallback.getToolDefinition()).thenReturn(deleteDeploymentDefinition);
        when(buildSpringAIAgent.pinnedToolCallbacks()).thenReturn(List.of(deleteDeploymentCallback));

        AiHubToolApproval approval = pendingApproval("deleteProjectDeployment", "BUILD");

        when(deleteDeploymentCallback.call(eq(approval.getArguments()), any())).thenReturn("{\"deleted\":true}");

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.APPROVED);
        assertThat(resolution.approval()
            .getExecutionError()).isNull();

        verify(deleteDeploymentCallback).call(eq(approval.getArguments()), any());
    }

    @Test
    void testApproveWhenTheToolThrowsMarksFailed() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        when(sendEmailCallback.call(eq(approval.getArguments()), any())).thenThrow(new RuntimeException("boom"));

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.FAILED);
        assertThat(resolution.approval()
            .getExecutionError()).isEqualTo("boom");
        assertThat(resolution.continuationStarted()).isTrue();

        verify(chatStreamer, times(1)).runAgent(any(), any(), eq(THREAD_ID));
    }

    @Test
    void testContinuationSkippedWhenARunIsInFlight() {
        pendingApproval("sendEmail", "BUILD");

        when(inFlightRunRegistry.isInFlight(THREAD_ID)).thenReturn(true);

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, false, null);

        assertThat(resolution.continuationStarted()).isFalse();
        assertThat(resolution.runId()).isNull();
        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.REJECTED);

        verify(chatStreamer, never()).runAgent(any(), any(), any());
        verify(approvalService).save(any(AiHubToolApproval.class));
    }

    /**
     * The CATALOG half of the execution-identity split. A participant raised the gated call and the OWNER approved it;
     * the tool is a global catalog tool, so it must execute as the REQUESTER — the person whose instruction raised it —
     * and never borrow the approver's authority. Asserts the login the executing tool actually observes on its own
     * thread, not merely that a rehydration wrapper was invoked: a wrapper carrying the wrong login would satisfy the
     * latter. The approver stays recorded on the row and in the audit event.
     */
    @Test
    void testCatalogToolApprovedByTheOwnerExecutesAsTheRequester() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        approval.setRequestedByUserId(PARTICIPANT_ID);

        List<String> observedLogins = new ArrayList<>();

        when(sendEmailCallback.call(eq(approval.getArguments()), any())).thenAnswer(invocation -> {
            observedLogins.add(SecurityUtils.getCurrentUserLogin());

            return "{\"sent\":true}";
        });

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(observedLogins).containsExactly(PARTICIPANT_LOGIN);
        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.APPROVED);
        assertThat(resolution.approval()
            .getDecidedByUserId()).isEqualTo(CHAT_OWNER_ID);

        ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

        verify(sendEmailCallback).call(eq(approval.getArguments()), toolContextCaptor.capture());

        assertThat(toolContextCaptor.getValue()
            .getContext()
            .get(AgentToolInvocationContext.TOOL_CONTEXT_USER_ID_KEY)).isEqualTo(PARTICIPANT_ID);

        assertThat(auditedDecision(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_APPROVED))
            .containsEntry("decidedByUserId", CHAT_OWNER_ID)
            .containsEntry("requestedByUserId", PARTICIPANT_ID);
    }

    /**
     * The CHAT-SCOPED half of the execution-identity split, and the case where running as the approver is furthest from
     * right: an INSTANCE ADMIN — who need not even be a member of the chat's workspace — approves a participant's gated
     * call on a tool the chat's owner attached. The binding, its pinned connection and the resources behind it are the
     * owner's, so it must execute as the OWNER: neither the admin (whose authority nobody asked for) nor the requester
     * (who cannot reach the owner's connection).
     */
    @Test
    void testChatScopedToolApprovedByAnInstanceAdminExecutesAsTheOwner() {
        ToolDefinition postMessageDefinition = mock(ToolDefinition.class);

        when(postMessageDefinition.name()).thenReturn("postMessage");

        ToolCallback postMessageCallback = mock(ToolCallback.class);

        when(postMessageCallback.getToolDefinition()).thenReturn(postMessageDefinition);
        when(chatBindingResolver.resolve(any())).thenReturn(List.of(postMessageCallback));

        AiHubToolApproval approval = pendingApproval("postMessage", "BUILD");

        approval.setRequestedByUserId(PARTICIPANT_ID);
        approval.setToolKind(AiHubToolApproval.ToolKind.COMPONENT);
        approval.setComponentName("slack");

        List<String> observedLogins = new ArrayList<>();

        when(postMessageCallback.call(eq(approval.getArguments()), any())).thenAnswer(invocation -> {
            observedLogins.add(SecurityUtils.getCurrentUserLogin());

            return "{\"posted\":true}";
        });

        User instanceAdmin = user(INSTANCE_ADMIN_ID, ADMIN_LOGIN);

        when(userService.getCurrentUser()).thenReturn(instanceAdmin);

        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    ADMIN_LOGIN, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        AiHubToolApprovalFacade.Resolution resolution =
            newFacade(new OwnerOrInstanceAdminAccessPolicy(INSTANCE_ADMIN_ID)).resolve(
                WORKSPACE_ID, 99L, true, null);

        assertThat(observedLogins).containsExactly(OWNER_LOGIN);
        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.APPROVED);
        assertThat(resolution.approval()
            .getDecidedByUserId()).isEqualTo(INSTANCE_ADMIN_ID);

        ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

        verify(postMessageCallback).call(eq(approval.getArguments()), toolContextCaptor.capture());

        assertThat(toolContextCaptor.getValue()
            .getContext()
            .get(AgentToolInvocationContext.TOOL_CONTEXT_USER_ID_KEY)).isEqualTo(CHAT_OWNER_ID);

        assertThat(auditedDecision(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_APPROVED))
            .containsEntry("decidedByUserId", INSTANCE_ADMIN_ID)
            .containsEntry("requestedByUserId", PARTICIPANT_ID);
    }

    /**
     * The continuation turn is dispatched as the REQUESTER, not the approver. The turn can call further tools, so
     * starting it under the resolver's ambient context would hand the approver's authority to everything the run
     * touches on the dispatching thread — undoing one call later the split the tool execution just established. Asserts
     * the login observed at the moment of dispatch, inside {@code chatStreamer.runAgent}.
     */
    @Test
    void testContinuationTurnIsDispatchedAsTheRequester() {
        AiHubToolApproval approval = pendingApproval("sendEmail", "BUILD");

        approval.setRequestedByUserId(PARTICIPANT_ID);

        when(sendEmailCallback.call(eq(approval.getArguments()), any())).thenReturn("{\"sent\":true}");

        List<String> dispatchLogins = new ArrayList<>();

        when(chatStreamer.runAgent(any(), any(), eq(THREAD_ID))).thenAnswer(invocation -> {
            dispatchLogins.add(SecurityUtils.getCurrentUserLogin());

            return null;
        });

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(dispatchLogins).containsExactly(PARTICIPANT_LOGIN);
        assertThat(resolution.continuationStarted()).isTrue();
        assertThat(resolution.approval()
            .getDecidedByUserId()).isEqualTo(CHAT_OWNER_ID);
    }

    /**
     * Fail-closed on the continuation: when the requester's identity cannot be established — their account is gone — no
     * turn is started, rather than one started under the approver's context. Uses a chat-scoped tool so the execution
     * identity is the owner and the tool still runs, isolating the continuation's own guard: the decision and the tool
     * result stay committed, only the status turn is skipped, which is an outcome every caller of {@code Resolution}
     * already handles.
     */
    @Test
    void testContinuationIsNotStartedWhenTheRequesterCannotBeResolved() {
        ToolDefinition postMessageDefinition = mock(ToolDefinition.class);

        when(postMessageDefinition.name()).thenReturn("postMessage");

        ToolCallback postMessageCallback = mock(ToolCallback.class);

        when(postMessageCallback.getToolDefinition()).thenReturn(postMessageDefinition);
        when(chatBindingResolver.resolve(any())).thenReturn(List.of(postMessageCallback));

        AiHubToolApproval approval = pendingApproval("postMessage", "BUILD");

        approval.setRequestedByUserId(PARTICIPANT_ID);

        when(postMessageCallback.call(eq(approval.getArguments()), any())).thenReturn("{\"posted\":true}");
        when(userService.fetchUser(PARTICIPANT_ID)).thenReturn(Optional.empty());

        AiHubToolApprovalFacade.Resolution resolution = facade.resolve(WORKSPACE_ID, 99L, true, null);

        assertThat(resolution.continuationStarted()).isFalse();
        assertThat(resolution.runId()).isNull();
        assertThat(resolution.approval()
            .getStatus()).isEqualTo(AiHubToolApproval.Status.APPROVED);

        verify(postMessageCallback).call(eq(approval.getArguments()), any());
        verify(chatStreamer, never()).runAgent(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditedDecision(AiHubAuditEvent event) {
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditPublisher).publish(eq(event), dataCaptor.capture());

        return dataCaptor.getValue();
    }

    /**
     * Test fixture standing in for the production policy's owner-or-instance-admin {@code canManage}: true for the
     * chat's own owner and for the one user id given as the instance admin, false for anyone else. Deliberately not a
     * Mockito mock, for the same reason {@link OwnerOnlyAccessPolicy} is not.
     */
    private record OwnerOrInstanceAdminAccessPolicy(long adminUserId) implements AiHubChatAccessPolicy {

        @Override
        public boolean canView(AiHubChat chat, long userId) {
            return chat.getUserId() == userId || adminUserId == userId;
        }

        @Override
        public boolean canParticipate(AiHubChat chat, long userId) {
            return canView(chat, userId);
        }

        @Override
        public boolean canManage(AiHubChat chat, long userId) {
            return canView(chat, userId);
        }
    }

    /**
     * Returns a transaction manager that simply runs the callback inline (single-threaded), with no real transactional
     * semantics — sufficient for unit tests that mock the persistence layer.
     */
    private static PlatformTransactionManager noopTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        return manager;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> emptyMeterRegistryProvider() {
        return mock(ObjectProvider.class);
    }
}
