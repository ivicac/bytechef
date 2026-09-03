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

package com.bytechef.component.ai.llm.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.ai.constant.AiAgentToolContextKey;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.Decision;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ToolCall;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class RuleEnforcingToolCallbackTest {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final ComponentRuleEnforcer componentRuleEnforcer = mock(ComponentRuleEnforcer.class);
    private final ClusterElementDefinitionService clusterElementDefinitionService = mock(
        ClusterElementDefinitionService.class);

    private ActionContextAware actionContext;

    private static final ClusterElement CLUSTER_ELEMENT = new ClusterElement(
        null, null, Map.of(), null, "slack/v1/sendMessage", Map.of(), "slack_1");

    @BeforeEach
    void setUp() {
        actionContext = mock(
            ActionContextAware.class,
            withSettings().extraInterfaces(ActionContext.class));

        when(delegate.getToolDefinition()).thenReturn(
            DefaultToolDefinition.builder()
                .name("SLACK_SEND_MESSAGE")
                .description("Send a Slack message")
                .inputSchema("{}")
                .build());
    }

    @Test
    void testAllowExecutesTheDelegateAndRecordsTheOutput() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());
        when(delegate.call(anyString(), any())).thenReturn("sent");

        assertThat(newToolCallback().call("{\"channel\": \"#general\"}", null)).isEqualTo("sent");

        verify(componentRuleEnforcer).recordAfterCall(any(), eq("sent"));
    }

    @Test
    void testBlockReturnsADenialAndNeverExecutesTheDelegate() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Block("nope"));

        String result = newToolCallback().call("{}", null);

        assertThat(result)
            .contains("\"blocked\":true")
            .contains("nope");

        verify(delegate, never()).call(anyString(), any());
        verify(componentRuleEnforcer, never()).recordAfterCall(any(), any());
    }

    @Test
    void testBlockRecordsARuleBlockedOutcomeEventWhenARecorderIsPresent() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Block("nope"));

        ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

        newToolCallback(toolExecutionRecorder).call("{}", null);

        ArgumentCaptor<ToolExecutionEvent> toolExecutionEventCaptor = ArgumentCaptor.forClass(ToolExecutionEvent.class);

        verify(toolExecutionRecorder).record(toolExecutionEventCaptor.capture());

        ToolExecutionEvent toolExecutionEvent = toolExecutionEventCaptor.getValue();

        assertThat(toolExecutionEvent.toolName()).isEqualTo("SLACK_SEND_MESSAGE");
        assertThat(toolExecutionEvent.outcome()).isEqualTo(ToolExecutionOutcome.RULE_BLOCKED);
    }

    @Test
    void testBlockWithNoRecorderNeverFailsTheCall() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Block("nope"));

        assertThat(newToolCallback().call("{}", null)).contains("\"blocked\":true");
    }

    @Test
    void testTheToolCallCarriesTheElementNameAndTheModelChosenArguments() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());

        newToolCallback().call("{\"channel\": \"#general\"}", null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        ToolCall toolCall = toolCallCaptor.getValue();

        assertThat(toolCall.componentName()).isEqualTo("slack");
        assertThat(toolCall.toolName()).isEqualTo("sendMessage");
        assertThat(toolCall.toolCallName()).isEqualTo("SLACK_SEND_MESSAGE");
        assertThat(toolCall.inputParameters()
            .get("channel")).isEqualTo("#general");
    }

    @Test
    void testTheToolCallCarriesTheJobPrincipalAndPlatformType() {
        when(actionContext.getJobPrincipalId()).thenReturn(5L);
        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());

        newToolCallback().call("{}", null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        ToolCall toolCall = toolCallCaptor.getValue();

        assertThat(toolCall.jobPrincipalId()).isEqualTo(5L);
        assertThat(toolCall.platformType()).isEqualTo(PlatformType.AUTOMATION);
    }

    @Test
    void testAnEnforcerFailureNeverFailsTheToolCall() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenThrow(new IllegalStateException("database is down"));
        when(delegate.call(anyString(), any())).thenReturn("sent");

        assertThat(newToolCallback().call("{}", null)).isEqualTo("sent");
    }

    @Test
    void testUnwrapDoesNotStripTheRuleLayer() {
        // The gate-resume path unwraps to the innermost non-delegating callback to bypass the gate. The rule layer
        // must survive that, so a human-approved re-execution is still rule-checked.
        RuleEnforcingToolCallback toolCallback = newToolCallback();

        assertThat(DelegatingToolCallback.unwrap(toolCallback)).isSameAs(toolCallback);
    }

    @Test
    void testRequireApprovalSuspendsWithTheGateContinueParametersPlusTheRuleIds() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(
            new Decision.RequireApproval(
                List.of(7L, 9L), "Approve tool call", "because the rule says so",
                Instant.now()
                    .plusSeconds(3600)));

        assertThat(newToolCallback().call("{\"channel\": \"#general\"}", null))
            .isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        ArgumentCaptor<ActionContext.Suspend> suspendCaptor = ArgumentCaptor.forClass(ActionContext.Suspend.class);

        verify(actionContext).suspend(suspendCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> continueParameters = (Map<String, Object>) suspendCaptor.getValue()
            .continueParameters();

        assertThat(continueParameters)
            .containsEntry(ToolSuspendConstants.GATED_TOOL_NAME, "SLACK_SEND_MESSAGE")
            .containsEntry(ToolSuspendConstants.RULE_IDS, List.of(7L, 9L));

        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testRequireApprovalDefersWhenAnotherToolAlreadySuspendedThisRound() {
        when(actionContext.getSuspend()).thenReturn(new ActionContext.Suspend(Map.of(), Instant.now()));
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(
            new Decision.RequireApproval(List.of(7L), "t", "d", Instant.now()));

        assertThat(newToolCallback().call("{}", null)).contains("deferred");

        verify(actionContext, never()).suspend(any());
        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testAnApprovedReExecutionPassesTheReviewerToTheEnforcer() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());
        when(delegate.call(anyString(), any())).thenReturn("sent");

        ToolContext toolContext = new ToolContext(Map.of(AiAgentToolContextKey.APPROVED_BY, "@jane"));

        newToolCallback().call("{}", toolContext);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        assertThat(toolCallCaptor.getValue()
            .approvedBy()).isEqualTo("@jane");
    }

    /**
     * The backstop spec §3 promised: an approval raised by a rule reaches the Approval Tasks page whatever transport is
     * configured. Without it a webhook- or schedule-triggered run with no channels reaches nobody — the chat channel
     * has a jobId but no listener — and the turn hangs until expiry, with no row anywhere to resolve it from. The admin
     * who authored the rule may have no relationship to the agent node at all, so there is nobody to have attached the
     * channel by hand.
     */
    @Test
    void testARuleApprovalWithNoConfiguredChannelsStillReachesTheApprovalTaskChannel() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(requireApproval());

        assertThat(newToolCallback().call("{}", null)).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("approvalTask"), eq(1), eq("approvalTask"), anyMap(), eq("https://example.com/resume/abc123"), isNull(),
            eq(actionContext));
        verify(actionContext).suspend(any());
    }

    @Test
    void testDeliveryStillSucceedsWhenOnlyTheApprovalTaskChannelDelivers() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(requireApproval());
        when(
            clusterElementDefinitionService.executeApprovalChannel(
                eq("chat"), anyInt(), anyString(), anyMap(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("no chat listener"));

        assertThat(newToolCallback().call("{}", null)).isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        verify(actionContext).suspend(any());
    }

    @Test
    void testDeliveryFailsWhenEveryChannelIncludingTheApprovalTaskChannelFails() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(false);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(requireApproval());
        when(
            clusterElementDefinitionService.executeApprovalChannel(
                anyString(), anyInt(), anyString(), anyMap(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("channel_not_found"));

        // Nobody was notified, so suspending would leave the turn paused on a request that does not exist anywhere.
        assertThatThrownBy(() -> newToolCallback().call("{}", null)).isInstanceOf(IllegalStateException.class);

        verify(actionContext, never()).suspend(any());
    }

    private static Decision requireApproval() {
        return new Decision.RequireApproval(
            List.of(7L), "Approve tool call", "because the rule says so", Instant.now()
                .plusSeconds(3600));
    }

    private RuleEnforcingToolCallback newToolCallback() {
        return newToolCallback(null);
    }

    private RuleEnforcingToolCallback newToolCallback(@Nullable ToolExecutionRecorder toolExecutionRecorder) {
        return new RuleEnforcingToolCallback(
            delegate, CLUSTER_ELEMENT, List.of(componentRuleEnforcer), 3L, (ActionContext) actionContext, List.of(),
            Map.of(), clusterElementDefinitionService, toolExecutionRecorder);
    }
}
