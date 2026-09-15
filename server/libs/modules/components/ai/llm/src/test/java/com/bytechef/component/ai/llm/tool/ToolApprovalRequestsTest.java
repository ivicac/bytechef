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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import java.util.List;
import java.util.Map;
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
}
