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

package com.bytechef.automation.ai.a2a.server.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.automation.ai.a2a.domain.A2aServer;
import com.bytechef.automation.ai.a2a.service.A2aProjectService;
import com.bytechef.automation.ai.a2a.service.A2aProjectWorkflowService;
import com.bytechef.automation.ai.a2a.service.A2aServerService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.platform.ai.a2a.A2AAgentRequest;
import com.bytechef.platform.ai.a2a.A2AAgentResult;
import com.bytechef.platform.plan.provider.PlanLimitsProvider;
import com.bytechef.platform.workflow.execution.JobCompletionAwaiter;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @author Ivica Cardic
 */
class AutomationA2AServerFacadeTest {

    private final A2aProjectService a2aProjectService = mock(A2aProjectService.class);
    private final A2aProjectWorkflowService a2aProjectWorkflowService = mock(A2aProjectWorkflowService.class);
    private final A2aServerService a2aServerService = mock(A2aServerService.class);
    private final JobService jobService = mock(JobService.class);
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService =
        mock(ProjectDeploymentWorkflowService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PlanLimitsProvider> planLimitsProviderObjectProvider =
        (ObjectProvider<PlanLimitsProvider>) mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ApprovalTokens> approvalTokensObjectProvider =
        (ObjectProvider<ApprovalTokens>) mock(ObjectProvider.class);

    private final AutomationA2AServerFacade facade = new AutomationA2AServerFacade(
        a2aProjectService, a2aProjectWorkflowService, a2aServerService, approvalTokensObjectProvider,
        mock(JobCompletionAwaiter.class), jobService, planLimitsProviderObjectProvider,
        mock(PrincipalJobFacade.class), projectDeploymentWorkflowService, "https://example.com",
        mock(TaskExecutionService.class), mock(TaskFileStorage.class), mock(WorkflowService.class));

    @Test
    void testExecuteReturnsErrorWhenServerDisabled() {
        A2aServer a2aServer = new A2aServer();

        a2aServer.setEnabled(false);

        when(a2aServerService.getA2aServer("secret")).thenReturn(a2aServer);

        A2AAgentResult result = facade.execute(new A2AAgentRequest("secret", "hi", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("disabled");
    }

    @Test
    void testExecuteReturnsErrorWhenNoWorkflowExposed() {
        A2aServer a2aServer = new A2aServer();

        a2aServer.setEnabled(true);
        a2aServer.setId(1L);

        when(a2aServerService.getA2aServer("secret")).thenReturn(a2aServer);
        when(a2aProjectService.getA2aServerA2aProjects(1L)).thenReturn(List.of());

        A2AAgentResult result = facade.execute(new A2AAgentRequest("secret", "hi", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No agent-backed workflow");
    }

    @Test
    void testPollRunReturnsNullWhenJobIsMissing() {
        when(jobService.fetchJob(1L)).thenReturn(Optional.empty());

        assertThat(facade.pollRun(1L)).isNull();
    }

    @Test
    void testPollRunReturnsNullWhileRunIsMidResume() {
        Job job = mock(Job.class);

        // STARTED = the resume is in flight; the stored task must stay input-required until the run settles.
        when(job.getStatus()).thenReturn(Job.Status.STARTED);
        when(jobService.fetchJob(2L)).thenReturn(Optional.of(job));

        assertThat(facade.pollRun(2L)).isNull();
    }

    @Test
    void testPollRunReturnsInputRequiredWhileRunStaysStopped() {
        Job job = mock(Job.class);

        when(job.getId()).thenReturn(3L);
        when(job.getStatus()).thenReturn(Job.Status.STOPPED);
        when(jobService.fetchJob(3L)).thenReturn(Optional.of(job));

        A2AAgentResult result = facade.pollRun(3L);

        assertThat(result).isNotNull();
        assertThat(result.inputRequired()).isTrue();
        assertThat(result.jobId()).isEqualTo(3L);
        assertThat(result.text()).contains("Approval required");
    }

    @Test
    void testPollRunReturnsTextWhenRunCompletes() {
        Job job = mock(Job.class);

        when(job.getStatus()).thenReturn(Job.Status.COMPLETED);
        when(job.getOutputs()).thenReturn(null);
        when(jobService.fetchJob(4L)).thenReturn(Optional.of(job));

        A2AAgentResult result = facade.pollRun(4L);

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.inputRequired()).isFalse();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void testPollRunReturnsErrorWhenRunFails() {
        Job job = mock(Job.class);

        when(job.getStatus()).thenReturn(Job.Status.FAILED);
        when(jobService.fetchJob(5L)).thenReturn(Optional.of(job));

        A2AAgentResult result = facade.pollRun(5L);

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("failed after the approval");
    }
}
