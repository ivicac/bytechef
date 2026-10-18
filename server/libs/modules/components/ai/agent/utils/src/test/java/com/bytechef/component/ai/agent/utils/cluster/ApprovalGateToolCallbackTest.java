/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.ai.agent.utils.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.SseEmitterHandler.SseEmitter;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.ai.tool.AiAgentToolContext;
import com.bytechef.platform.ai.tool.AiAgentToolContext.SseTransport;
import com.bytechef.platform.ai.tool.ToolSuspension;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * @author Ivica Cardic
 */
class ApprovalGateToolCallbackTest {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final ClusterElementDefinitionService clusterElementDefinitionService = mock(
        ClusterElementDefinitionService.class);
    private final AtomicReference<ActionContext.Suspend> suspendReference = new AtomicReference<>();
    private ActionContextAware actionContext;

    @BeforeEach
    void setUp() {
        actionContext = mock(
            ActionContextAware.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(ActionContext.class));

        doAnswer(invocation -> {
            suspendReference.set(invocation.getArgument(0));

            return null;
        }).when(actionContext)
            .suspend(any(ActionContext.Suspend.class));

        when(actionContext.getSuspend()).thenAnswer(invocation -> suspendReference.get());

        when(delegate.getToolDefinition()).thenReturn(
            DefaultToolDefinition.builder()
                .name("SLACK_SEND_MESSAGE")
                .description("Send a Slack message")
                .inputSchema("{}")
                .build());
    }

    @Test
    void testFirstCallDeliversDefaultChatChannelAndSuspends() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{\"channel\": \"#general\"}", null);

        assertThat(ToolSuspension.isSuspendedToolResult(result, suspendReference.get())).isTrue();

        // No channels configured on the agent node -> the gate defaults to the chat approval channel.
        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("chat"), eq(1), eq("chat"), anyMap(), eq("https://example.com/resume/abc123"), isNull(),
            eq(actionContext));

        ActionContext.Suspend suspend = suspendReference.get();

        assertThat(suspend).isNotNull();

        Map<String, Object> continueParameters = new HashMap<>(suspend.continueParameters());

        assertThat(continueParameters)
            .containsEntry(ToolSuspendConstants.GATED_TOOL_NAME, "SLACK_SEND_MESSAGE")
            .containsEntry(ToolSuspendConstants.GATED_TOOL_INPUT, "{\"channel\": \"#general\"}");

        // The delegate must NOT execute before approval.
        verify(delegate, never()).call(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void testFirstCallRecordsApprovalRequiredAuditEvent() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(actionContext.getJobId()).thenReturn(42L);

        ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext, toolExecutionRecorder);

        gate.call("{\"channel\": \"#general\"}", null);

        ArgumentCaptor<ToolExecutionEvent> eventCaptor = ArgumentCaptor.forClass(ToolExecutionEvent.class);

        verify(toolExecutionRecorder).record(eventCaptor.capture());

        ToolExecutionEvent toolExecutionEvent = eventCaptor.getValue();

        assertThat(toolExecutionEvent.surface()).isEqualTo(ToolExecutionSurface.AI_AGENT);
        assertThat(toolExecutionEvent.toolName()).isEqualTo("SLACK_SEND_MESSAGE");
        assertThat(toolExecutionEvent.outcome()).isEqualTo(ToolExecutionOutcome.APPROVAL_REQUIRED);
        assertThat(toolExecutionEvent.jobId()).isEqualTo(42L);
    }

    @Test
    void testFirstCallScopesTheApprovalRequiredEventToTheRunEnvironmentAndWorkspace() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(actionContext.getEnvironmentId()).thenReturn(2L);
        when(actionContext.getJobPrincipalId()).thenReturn(5L);
        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

        ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);
        ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver = mock(ToolInvocationWorkspaceResolver.class);

        when(toolInvocationWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L)).thenReturn(9L);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext, toolExecutionRecorder,
            toolInvocationWorkspaceResolver, null);

        gate.call("{\"channel\": \"#general\"}", null);

        ToolExecutionEvent toolExecutionEvent = captureRecordedEvent(toolExecutionRecorder);

        assertThat(toolExecutionEvent.environment()).isEqualTo(2);
        assertThat(toolExecutionEvent.workspaceId()).isEqualTo(9L);
    }

    @Test
    void testFirstCallRecordsTheEnvironmentWithoutAWorkspaceWhenNoResolverIsPresent() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(actionContext.getEnvironmentId()).thenReturn(1L);
        when(actionContext.getJobPrincipalId()).thenReturn(5L);
        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

        ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext, toolExecutionRecorder,
            null, null);

        gate.call("{\"channel\": \"#general\"}", null);

        ToolExecutionEvent toolExecutionEvent = captureRecordedEvent(toolExecutionRecorder);

        assertThat(toolExecutionEvent.environment()).isEqualTo(1);
        assertThat(toolExecutionEvent.workspaceId()).isNull();
    }

    @Test
    void testFirstCallLeavesTheEventUnscopedWhenTheRunHasNoEnvironmentOrWorkspace() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);
        when(actionContext.getEnvironmentId()).thenReturn(null);

        ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);
        ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver = mock(ToolInvocationWorkspaceResolver.class);

        when(toolInvocationWorkspaceResolver.resolve(any(), any())).thenReturn(null);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext, toolExecutionRecorder,
            toolInvocationWorkspaceResolver, null);

        gate.call("{\"channel\": \"#general\"}", null);

        ToolExecutionEvent toolExecutionEvent = captureRecordedEvent(toolExecutionRecorder);

        assertThat(toolExecutionEvent.environment()).isNull();
        assertThat(toolExecutionEvent.workspaceId()).isNull();
    }

    @Test
    void testSecondFlaggedCallInSameRoundDefersWithoutSecondSuspend() {
        when(actionContext.getSuspend()).thenReturn(
            new ActionContext.Suspend(Map.of(), Instant.now()));

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{}", null);

        // A second sentinel in one round would break SuspendableToolCallingManager's single-suspend invariant —
        // the gate returns a plain deferral instead.
        assertThat(result).contains("deferred");
        assertThat(ToolSuspension.hasSuspendedToolResultFormat(result)).isFalse();

        verify(actionContext, never()).suspend(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void testEditorEnvironmentSkipsChannelDeliveryButStillSuspends() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{}", null);

        assertThat(ToolSuspension.isSuspendedToolResult(result, suspendReference.get())).isTrue();

        org.mockito.Mockito.verifyNoInteractions(clusterElementDefinitionService);
        verify(actionContext, atLeastOnce()).suspend(any());
    }

    @Test
    void testEditorEnvironmentEmitsApprovalRequestEventThroughToolContext() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);

        SseTransport sseTransport = new SseTransport();

        ToolContext toolContext = new ToolContext(new AiAgentToolContext(actionContext, sseTransport).toMap());

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{\"channel\": \"#general\"}", toolContext);

        assertThat(ToolSuspension.isSuspendedToolResult(result, suspendReference.get())).isTrue();

        // Editor runs have no channel listeners; the card event must ride the agent's SSE stream instead —
        // buffered here because no emitter is attached yet, drained when the client connects.
        SseEmitter sseEmitter = mock(SseEmitter.class);

        sseTransport.attach(sseEmitter, exception -> {
            throw new IllegalStateException(exception);
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);

        verify(sseEmitter).send(eventCaptor.capture());

        assertThat(eventCaptor.getValue())
            .containsEntry("__eventType", "approval_request")
            .containsEntry("resumeId", "abc123")
            .containsKey("formTitle");

        org.mockito.Mockito.verifyNoInteractions(clusterElementDefinitionService);
    }

    @Test
    void testFanOutDeliversRemainingChannelsWhenOneFails() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);

        when(clusterElementDefinitionService.executeApprovalChannel(
            eq("slack"), anyInt(), anyString(), anyMap(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("channel_not_found"));

        List<ClusterElement> approvalChannels = List.of(
            new ClusterElement(null, null, Map.of(), null, "slack/v1/approvalChannel", Map.of(), "slack_1"),
            new ClusterElement(
                null, null, Map.of(), null, "approvalTask/v1/approvalTask", Map.of(), "approvalTask_1"));

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, approvalChannels, Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{}", null);

        assertThat(ToolSuspension.isSuspendedToolResult(result, suspendReference.get())).isTrue();

        // The failing Slack channel must not stop the fallback channel from delivering or the gate from suspending.
        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("approvalTask"), anyInt(), anyString(), anyMap(), anyString(), any(), any());
        verify(actionContext, atLeastOnce()).suspend(any());
    }

    @Test
    void testFanOutFailsWhenEveryChannelFails() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);

        when(clusterElementDefinitionService.executeApprovalChannel(
            anyString(), anyInt(), anyString(), anyMap(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("channel_not_found"));

        List<ClusterElement> approvalChannels = List.of(
            new ClusterElement(null, null, Map.of(), null, "slack/v1/approvalChannel", Map.of(), "slack_1"));

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, approvalChannels, Map.of(), clusterElementDefinitionService, actionContext);

        // When every configured channel fails nobody was notified, so the gate must fail rather than suspend into a
        // silent no-op that would leave the run paused with no delivered approval request.
        assertThatThrownBy(() -> gate.call("{}", null)).isInstanceOf(IllegalStateException.class);

        verify(actionContext, never()).suspend(any());
    }

    @Test
    void testConfiguredApprovalExpiryDrivesSuspendExpiry() {
        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext, null,
            null, Duration.ofHours(4));

        gate.call("{}", null);

        ActionContext.Suspend suspend = suspendReference.get();

        assertThat(suspend).isNotNull();

        Instant expiresAt = suspend.expiresAt();

        assertThat(expiresAt).isBetween(
            Instant.now()
                .plus(Duration.ofHours(3)),
            Instant.now()
                .plus(Duration.ofHours(5)));
    }

    private static ToolExecutionEvent captureRecordedEvent(ToolExecutionRecorder toolExecutionRecorder) {
        ArgumentCaptor<ToolExecutionEvent> toolExecutionEventCaptor = ArgumentCaptor.forClass(ToolExecutionEvent.class);

        verify(toolExecutionRecorder).record(toolExecutionEventCaptor.capture());

        return toolExecutionEventCaptor.getValue();
    }
}
