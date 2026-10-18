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

import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_DESCRIPTION;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.FORM_TITLE;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.RESUME_ID;
import static com.bytechef.component.definition.approval.ApprovalChannelFunction.SHORT_RESUME_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext.Suspend;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @author Ivica Cardic
 */
class ToolApprovalRequestsTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void testRaiseWithNoResumeUrlThrowsApprovalUnavailable() {
        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getResumeUrl(any())).thenReturn(null);

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", null, List.of(), Map.of(), Map.of(),
            mock(ClusterElementDefinitionService.class), actionContext, null);

        assertThatThrownBy(() -> ToolApprovalRequests.raise(request))
            .isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void testRaiseMarksTheSuspendAsAnApproval() {
        ActionContextAware actionContext = mock(ActionContextAware.class);
        AtomicReference<Suspend> suspendReference = mockSuspendingActionContext(actionContext);

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", EXPIRES_AT, List.of(), Map.of(), Map.of(),
            mock(ClusterElementDefinitionService.class), actionContext, null);

        ToolApprovalRequests.raise(request);

        Map<String, ?> continueParameters = suspendReference.get()
            .continueParameters();

        assertThat(continueParameters.get(MetadataConstants.APPROVAL_RESUME)).isEqualTo(true);
        assertThat(continueParameters.get(MetadataConstants.APPROVAL_RAISED_AT)).isInstanceOf(String.class);
        assertThat(continueParameters.get(MetadataConstants.APPROVAL_FORM_PARAMETERS))
            .isEqualTo(Map.of(FORM_TITLE, "title", FORM_DESCRIPTION, "description"));
    }

    @Test
    void testRaiseKeepsServerOwnedChannelParameters() {
        ActionContextAware actionContext = mock(ActionContextAware.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);

        mockSuspendingActionContext(actionContext);

        ClusterElement slackChannel = new ClusterElement(
            null, null, Map.of(), null, "slack/v1/approvalChannel",
            Map.of(
                FORM_TITLE, "spoofed", "expiresAt", "never", "channel", "#general", SHORT_RESUME_ID, "spoofed"),
            "slack_1");

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", EXPIRES_AT, List.of(slackChannel), Map.of(), Map.of(),
            clusterElementDefinitionService, actionContext, null);

        ToolApprovalRequests.raise(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputParametersArgumentCaptor = ArgumentCaptor.forClass(Map.class);

        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("slack"), eq(1), eq("approvalChannel"), inputParametersArgumentCaptor.capture(), anyString(), isNull(),
            eq(actionContext));

        assertThat(inputParametersArgumentCaptor.getValue())
            .containsEntry(FORM_TITLE, "title")
            .containsEntry("expiresAt", EXPIRES_AT.toString())
            .containsEntry("channel", "#general")
            .containsEntry(RESUME_ID, "abc123")
            .doesNotContainKey(SHORT_RESUME_ID);
    }

    @Test
    void testRaisePassesTheInProcessShortResumeIdToChannels() {
        ActionContextAware actionContext = mock(ActionContextAware.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);

        mockSuspendingActionContext(actionContext);

        when(actionContext.mintShortResumeId("abc123", EXPIRES_AT)).thenReturn("short-1");

        ClusterElement telegramChannel = new ClusterElement(
            null, null, Map.of(), null, "telegram/v1/telegram", Map.of(), "telegram_1");

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", EXPIRES_AT, List.of(telegramChannel), Map.of(), Map.of(),
            clusterElementDefinitionService, actionContext, null);

        ToolApprovalRequests.raise(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputParametersArgumentCaptor = ArgumentCaptor.forClass(Map.class);

        verify(clusterElementDefinitionService).executeApprovalChannel(
            eq("telegram"), eq(1), eq("telegram"), inputParametersArgumentCaptor.capture(), anyString(), isNull(),
            eq(actionContext));

        assertThat(inputParametersArgumentCaptor.getValue())
            .containsEntry(RESUME_ID, "abc123")
            .containsEntry(SHORT_RESUME_ID, "short-1");
    }

    @Test
    void testRaiseReportsEveryChannelFailure() {
        ActionContextAware actionContext = mock(ActionContextAware.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);

        mockSuspendingActionContext(actionContext);

        when(clusterElementDefinitionService.executeApprovalChannel(
            anyString(), anyInt(), anyString(), anyMap(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("wrapper", new IllegalStateException("channel_not_found")));

        ClusterElement slackChannel = new ClusterElement(
            null, null, Map.of(), null, "slack/v1/approvalChannel", Map.of(), "slack_1");

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", EXPIRES_AT, List.of(slackChannel), Map.of(), Map.of(),
            clusterElementDefinitionService, actionContext, null);

        assertThatThrownBy(() -> ToolApprovalRequests.raise(request))
            .isInstanceOf(IllegalStateException.class)
            .hasNoCause()
            .hasMessageContaining("approvalTask/approvalTask: channel_not_found")
            .hasMessageContaining("slack/approvalChannel: channel_not_found")
            .satisfies(throwable -> assertThat(throwable.getSuppressed()).hasSize(2));
    }

    private static AtomicReference<Suspend> mockSuspendingActionContext(ActionContextAware actionContext) {
        AtomicReference<Suspend> suspendReference = new AtomicReference<>();

        when(actionContext.getResumeUrl(any())).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.getSuspend()).thenAnswer(invocation -> suspendReference.get());

        doAnswer(invocation -> {
            suspendReference.set(invocation.getArgument(0));

            return null;
        }).when(actionContext)
            .suspend(any(Suspend.class));

        return suspendReference;
    }
}
