/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.ee.ai.hub.agent.NonEmptyToolCallback;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import com.bytechef.ee.ai.hub.toolsearch.ClusterElementToolCallback;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class AiHubApprovalGateToolCallbackTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long CHAT_ID = 11L;
    private static final String THREAD_ID = "thread-1";

    private final AiHubToolApprovalService approvalService = mock(AiHubToolApprovalService.class);
    private final AiHubChatService chatService = mock(AiHubChatService.class);
    private final AiHubToolApprovalMetrics metrics = mock(AiHubToolApprovalMetrics.class);
    private final ToolCallback delegate = callback("sendEmail", "{\"sent\":true}");

    @Test
    void testUngatedToolPassesThrough() {
        ToolCallback wrapped = gate(Set.of());

        assertThat(wrapped.call("{\"to\":\"a@b\"}", toolContext())).isEqualTo("{\"sent\":true}");
    }

    @Test
    void testGatedToolPersistsAPendingRowAndReturnsTheEnvelope() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenAnswer(invocation -> {
            AiHubToolApproval approval = invocation.getArgument(0);

            approval.setId(1234L);

            return approval;
        });

        String result = gate(Set.of("sendEmail")).call("{\"to\":\"a@b\"}", toolContext());

        Map<String, Object> envelope = JsonUtils.read(result, new TypeReference<>() {});

        assertThat(envelope.get("kind")).isEqualTo("tool-approval-request");
        assertThat(envelope.get("approvalId")).isEqualTo(1234);
        assertThat(envelope.get("toolName")).isEqualTo("sendEmail");
        assertThat(envelope.get("awaitingApproval")).isEqualTo(true);
        verify(delegate, never()).call(anyString(), any());

        ArgumentCaptor<AiHubToolApproval> captor = ArgumentCaptor.forClass(AiHubToolApproval.class);

        verify(approvalService).createPending(captor.capture());

        AiHubToolApproval saved = captor.getValue();

        assertThat(saved.getChatId()).isEqualTo(CHAT_ID);
        assertThat(saved.getRequestedByUserId()).isEqualTo(USER_ID);
        assertThat(saved.getArguments()).isEqualTo("{\"to\":\"a@b\"}");
        assertThat(saved.getMode()).isEqualTo("BUILD");
        assertThat(saved.getStatus()).isEqualTo(AiHubToolApproval.Status.PENDING);
    }

    @Test
    void testSecondGatedCallWhileOnePendingIsDeferred() {
        AiHubToolApproval pending = new AiHubToolApproval();

        pending.setId(9L);
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.of(pending));

        String result = gate(Set.of("sendEmail")).call("{}", toolContext());

        assertThat(result).contains("\"deferred\":true");
        verify(approvalService, never()).createPending(any());
    }

    @Test
    void testPersistenceFailureRefusesTheTool() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenThrow(new IllegalStateException("db down"));

        String result = gate(Set.of("sendEmail")).call("{}", toolContext());

        assertThat(result).contains("approval could not be recorded");
        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testUnknownThreadPassesThrough() {
        ToolCallback wrapped = gate(Set.of("sendEmail"));

        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        assertThat(wrapped.call("{}", toolContext())).isEqualTo("{\"sent\":true}");
    }

    @Test
    void testComponentToolPersistsClusterElementMetadata() {
        ClusterElementToolCallback clusterElementToolCallback = spy(new ClusterElementToolCallback(
            "slack_sendMessage", "Send a Slack message", "{}", "slack", 2, "sendMessage",
            mock(ClusterElementDefinitionService.class), mock(ConnectionService.class), 42L, Map.of()));

        ToolCallback wrappedDelegate = NonEmptyToolCallback.wrap(clusterElementToolCallback);

        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenAnswer(invocation -> {
            AiHubToolApproval approval = invocation.getArgument(0);

            approval.setId(5678L);

            return approval;
        });

        ToolCallback wrapped = gate(Set.of("slack_sendMessage"), wrappedDelegate);

        wrapped.call("{}", toolContext());

        ArgumentCaptor<AiHubToolApproval> captor = ArgumentCaptor.forClass(AiHubToolApproval.class);

        verify(approvalService).createPending(captor.capture());

        AiHubToolApproval saved = captor.getValue();

        assertThat(saved.getToolKind()).isEqualTo(AiHubToolApproval.ToolKind.COMPONENT);
        assertThat(saved.getComponentName()).isEqualTo("slack");
        assertThat(saved.getComponentVersion()).isEqualTo(2);
        assertThat(saved.getConnectionId()).isEqualTo(42L);
        verify(clusterElementToolCallback, never()).call(anyString(), any());
    }

    @Test
    void testEnvelopeBuildFailureSupersedesTheJustCreatedRowAndRefusesTheTool() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenAnswer(invocation -> {
            AiHubToolApproval approval = invocation.getArgument(0);

            approval.setId(4321L);

            return approval;
        });

        String result = gate(Set.of("sendEmail")).call("not-valid-json", toolContext());

        assertThat(result).contains("approval could not be recorded");
        verify(delegate, never()).call(anyString(), any());

        ArgumentCaptor<AiHubToolApproval> captor = ArgumentCaptor.forClass(AiHubToolApproval.class);

        verify(approvalService).save(captor.capture());

        AiHubToolApproval superseded = captor.getValue();

        assertThat(superseded.getId()).isEqualTo(4321L);
        assertThat(superseded.getStatus()).isEqualTo(AiHubToolApproval.Status.SUPERSEDED);
    }

    @Test
    void testSupersedeSaveFailureAfterEnvelopeBuildFailureStillRefusesTheToolWithoutPropagating() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenAnswer(invocation -> {
            AiHubToolApproval approval = invocation.getArgument(0);

            approval.setId(4321L);

            return approval;
        });
        when(approvalService.save(any())).thenThrow(new IllegalStateException("connection pool exhausted"));

        String result = gate(Set.of("sendEmail")).call("not-valid-json", toolContext());

        assertThat(result).contains("approval could not be recorded");
        verify(delegate, never()).call(anyString(), any());
    }

    private ToolCallback gate(Set<String> required) {
        return gate(required, delegate);
    }

    private ToolCallback gate(Set<String> required, ToolCallback delegateCallback) {
        AiHubChat chat = new AiHubChat(USER_ID);

        chat.setId(CHAT_ID);
        chat.setWorkspaceId(WORKSPACE_ID);
        chat.setThreadId(THREAD_ID);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubToolApprovalPolicy policy = (workspaceId, userId, chatId) -> new AiHubToolApprovalPolicy.Decision(
            required, Set.of());

        return new AiHubApprovalGateToolCallback(delegateCallback, policy, approvalService, chatService, metrics);
    }

    private static ToolContext toolContext() {
        Map<String, Object> context = new HashMap<>(
            AgentToolInvocationContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userId(USER_ID)
                .environmentId(0L)
                .conversationId(THREAD_ID)
                .llmProvider("anthropic")
                .llmModel("claude-fable-5-1")
                .build()
                .toToolContext());

        context.put(AiHubApprovalGateToolCallback.TOOL_CONTEXT_MODE_KEY, "BUILD");

        return new ToolContext(context);
    }

    private static ToolCallback callback(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);

        when(callback.getToolDefinition()).thenReturn(
            ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build());
        when(callback.call(anyString(), any())).thenReturn(result);

        return callback;
    }
}
