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

package com.bytechef.automation.ai.mcp.web.graphql;

import static com.bytechef.platform.component.constant.WorkflowConstants.NEW_WORKFLOW_CALL;
import static com.bytechef.platform.component.constant.WorkflowConstants.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.ai.mcp.facade.McpProjectWorkflowFacade;
import com.bytechef.automation.ai.mcp.service.McpProjectWorkflowService;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.ProjectWorkflowType;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.platform.configuration.constant.WorkflowExtConstants;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class McpProjectWorkflowGraphQlControllerTest {

    private static final long PROJECT_ID = 1L;
    private static final int PROJECT_VERSION = 1;

    @Test
    void testToolEligibleProjectVersionWorkflowsLeavesOutGeneratedAiAgentWorkflows() {
        ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        ProjectWorkflow agentProjectWorkflow = new ProjectWorkflow(
            PROJECT_ID, PROJECT_VERSION, "agent-workflow", ProjectWorkflowType.AI_AGENT);
        ProjectWorkflow ordinaryProjectWorkflow = new ProjectWorkflow(
            PROJECT_ID, PROJECT_VERSION, "ordinary-workflow", ProjectWorkflowType.WORKFLOW);

        Workflow toolCallableWorkflow = toolCallableWorkflow();

        when(projectWorkflowService.getProjectWorkflows(PROJECT_ID, PROJECT_VERSION))
            .thenReturn(List.of(agentProjectWorkflow, ordinaryProjectWorkflow));
        when(workflowService.getWorkflow("ordinary-workflow")).thenReturn(toolCallableWorkflow);
        when(workflowService.getWorkflow("agent-workflow")).thenReturn(toolCallableWorkflow);

        McpProjectWorkflowGraphQlController mcpProjectWorkflowGraphQlController =
            new McpProjectWorkflowGraphQlController(
                mock(McpProjectWorkflowFacade.class), mock(McpProjectWorkflowService.class),
                mock(ProjectDeploymentWorkflowService.class), projectWorkflowService, workflowService);

        List<ProjectWorkflow> projectWorkflows =
            mcpProjectWorkflowGraphQlController.toolEligibleProjectVersionWorkflows(PROJECT_ID, PROJECT_VERSION);

        assertThat(projectWorkflows).containsExactly(ordinaryProjectWorkflow);

        verify(workflowService, never()).getWorkflow("agent-workflow");
    }

    private static Workflow toolCallableWorkflow() {
        WorkflowTrigger workflowTrigger = mock(WorkflowTrigger.class);

        when(workflowTrigger.getType()).thenReturn(WORKFLOW + "/v1/" + NEW_WORKFLOW_CALL);

        Workflow workflow = mock(Workflow.class);

        when(workflow.getExtensions(eq(WorkflowExtConstants.TRIGGERS), eq(WorkflowTrigger.class), any()))
            .thenReturn(List.of(workflowTrigger));

        return workflow;
    }
}
