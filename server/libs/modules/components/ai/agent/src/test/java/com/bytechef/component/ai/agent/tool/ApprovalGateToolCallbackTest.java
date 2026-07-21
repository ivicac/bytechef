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

package com.bytechef.component.ai.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * @author Ivica Cardic
 */
class ApprovalGateToolCallbackTest {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final ClusterElementDefinitionService clusterElementDefinitionService = mock(
        ClusterElementDefinitionService.class);
    private ActionContextAware actionContext;

    @BeforeEach
    void setUp() {
        actionContext = mock(
            ActionContextAware.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(ActionContext.class));

        when(delegate.getToolDefinition()).thenReturn(
            DefaultToolDefinition.builder()
                .name("SLACK_SEND_MESSAGE")
                .description("Send a Slack message")
                .inputSchema("{}")
                .build());
    }

    @Test
    void testFirstCallDeliversDefaultChatChannelAndSuspends() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{\"channel\": \"#general\"}", null);

        assertThat(result).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        // No channels configured on the agent node -> the gate defaults to the chat approval channel.
        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("approval"), eq(1), eq("chat"), anyMap(), eq("https://example.com/resume/abc123"), isNull(),
            eq(actionContext));

        ArgumentCaptor<ActionContext.Suspend> suspendCaptor = ArgumentCaptor.forClass(ActionContext.Suspend.class);

        verify(actionContext).suspend(suspendCaptor.capture());

        Map<String, Object> continueParameters = new java.util.HashMap<>(
            suspendCaptor.getValue()
                .continueParameters());

        assertThat(continueParameters)
            .containsEntry(ToolSuspendConstants.GATED_TOOL_NAME, "SLACK_SEND_MESSAGE")
            .containsEntry(ToolSuspendConstants.GATED_TOOL_INPUT, "{\"channel\": \"#general\"}");

        // The delegate must NOT execute before approval.
        verify(delegate, never()).call(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void testSecondFlaggedCallInSameRoundDefersWithoutSecondSuspend() {
        when(actionContext.getSuspend()).thenReturn(
            new ActionContext.Suspend(Map.of(), java.time.Instant.now()));

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{}", null);

        // A second sentinel in one round would break SuspendableToolCallingManager's single-suspend invariant —
        // the gate returns a plain deferral instead.
        assertThat(result).contains("deferred");
        assertThat(result).isNotEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        verify(actionContext, never()).suspend(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void testEditorEnvironmentSkipsChannelDeliveryButStillSuspends() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{}", null);

        assertThat(result).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        org.mockito.Mockito.verifyNoInteractions(clusterElementDefinitionService);
        verify(actionContext).suspend(any());
    }

    @Test
    void testEditorEnvironmentEmitsApprovalRequestEventThroughToolContext() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);

        java.util.Queue<Map<String, Object>> bufferedEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();

        org.springframework.ai.chat.model.ToolContext toolContext = new org.springframework.ai.chat.model.ToolContext(
            Map.of(
                com.bytechef.platform.ai.constant.AiAgentToolContextKey.SSE_BUFFERED_EVENTS, bufferedEvents,
                com.bytechef.platform.ai.constant.AiAgentToolContextKey.SSE_EMITTER_REFERENCE,
                new java.util.concurrent.atomic.AtomicReference<>()));

        ApprovalGateToolCallback gate = new ApprovalGateToolCallback(
            delegate, List.of(), Map.of(), clusterElementDefinitionService, actionContext);

        String result = gate.call("{\"channel\": \"#general\"}", toolContext);

        assertThat(result).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        // Editor runs have no channel listeners; the card event must ride the agent's SSE stream instead —
        // buffered here because no emitter is attached yet, drained when the client connects.
        assertThat(bufferedEvents).hasSize(1);
        assertThat(bufferedEvents.peek())
            .containsEntry("__eventType", "approval_request")
            .containsEntry("resumeId", "abc123")
            .containsKey("formTitle");

        org.mockito.Mockito.verifyNoInteractions(clusterElementDefinitionService);
    }
}
