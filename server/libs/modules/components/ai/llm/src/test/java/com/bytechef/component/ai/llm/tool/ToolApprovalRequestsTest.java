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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext.Suspend;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ToolApprovalRequestsTest {

    @Test
    void testRaiseWithNoResumeUrlThrowsApprovalUnavailable() {
        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getResumeUrl()).thenReturn(null);

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", null, List.of(), Map.of(), Map.of(),
            mock(ClusterElementDefinitionService.class), actionContext, null);

        assertThatThrownBy(() -> ToolApprovalRequests.raise(request))
            .isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void testRaiseMarksTheSuspendAsAnApproval() {
        ActionContextAware actionContext = mock(ActionContextAware.class);
        AtomicReference<Suspend> suspendReference = new AtomicReference<>();

        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.getSuspend()).thenAnswer(invocation -> suspendReference.get());

        doAnswer(invocation -> {
            suspendReference.set(invocation.getArgument(0));

            return null;
        }).when(actionContext)
            .suspend(any(Suspend.class));

        ToolApprovalRequests.Request request = new ToolApprovalRequests.Request(
            "gatedTool", "{}", "title", "description", Instant.parse("2030-01-01T00:00:00Z"), List.of(), Map.of(),
            Map.of(), mock(ClusterElementDefinitionService.class), actionContext, null);

        ToolApprovalRequests.raise(request);

        Map<String, ?> continueParameters = suspendReference.get()
            .continueParameters();

        assertThat(continueParameters.get(MetadataConstants.APPROVAL_RESUME)).isEqualTo(true);
        assertThat(continueParameters.get(MetadataConstants.APPROVAL_RAISED_AT)).isInstanceOf(String.class);
        assertThat(continueParameters.get(MetadataConstants.APPROVAL_FORM_PARAMETERS))
            .isEqualTo(Map.of(FORM_TITLE, "title", FORM_DESCRIPTION, "description"));
    }
}
