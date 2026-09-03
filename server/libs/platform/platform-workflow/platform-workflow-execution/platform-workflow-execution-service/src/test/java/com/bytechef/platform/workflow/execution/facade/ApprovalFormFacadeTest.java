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

package com.bytechef.platform.workflow.execution.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.constant.WorkflowConstants;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.component.definition.ActionContext.Suspend;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.workflow.execution.JobResumeId;
import com.bytechef.platform.workflow.execution.service.TaskStateService;
import com.bytechef.platform.workflow.execution.token.ApprovalTokensImpl;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    ObjectMapperSetupExtension.class, MockitoExtension.class
})
public class ApprovalFormFacadeTest {

    private static final long JOB_ID = 42L;
    private static final long TASK_EXECUTION_ID = 7L;

    @Mock
    private JobService jobService;

    @Mock
    private TaskExecutionService taskExecutionService;

    @Mock
    private TaskStateService taskStateService;

    private ApprovalFormFacadeImpl approvalFormFacade;
    private JobResumeId jobResumeId;

    @BeforeEach
    void setUp() {
        // Unconfigured (no secret, not required) -> resolveInnerToken passes the legacy token through unchanged.
        ApprovalTokensImpl approvalTokens = new ApprovalTokensImpl(
            Clock.systemUTC(), null, List.of(), Duration.ofDays(30), Duration.ofSeconds(60), false);

        approvalFormFacade = new ApprovalFormFacadeImpl(approvalTokens, jobService, taskExecutionService,
            taskStateService);

        jobResumeId = JobResumeId.of(JOB_ID);
    }

    @Test
    void testTheSuspendContinueParametersOverrideTheTaskExecutionParameters() {
        // An agent suspend's task execution is the agent node, whose parameters describe the prompt, not the tool
        // awaiting approval. The suspend carries the right title and description, so it wins.
        stubStoppedJobWithTaskExecutionParameters(
            Map.of("formTitle", "AI Agent", "formDescription", "Answer the user"));
        stubStoredSuspend(
            Map.of("formTitle", "Approve tool call: SLACK_SEND_MESSAGE", "formDescription", "channel: #general"));

        Map<String, Object> approvalForm = new HashMap<>(approvalFormFacade.getApprovalForm(jobResumeId.toString()));

        assertThat(approvalForm)
            .containsEntry("formTitle", "Approve tool call: SLACK_SEND_MESSAGE")
            .containsEntry("formDescription", "channel: #general");
    }

    @Test
    void testTaskExecutionParametersAreKeptWhenTheSuspendCarriesNoOverride() {
        stubStoppedJobWithTaskExecutionParameters(
            Map.of("formTitle", "Approve the refund", "formDescription", "over 1000"));
        stubStoredSuspend(Map.of("formUrl", "https://example.com/resume/abc"));

        Map<String, Object> approvalForm = new HashMap<>(approvalFormFacade.getApprovalForm(jobResumeId.toString()));

        assertThat(approvalForm).containsEntry("formTitle", "Approve the refund");
    }

    @Test
    void testTaskExecutionParametersAreKeptWhenNoSuspendStateIsStored() {
        // The state is deleted on resume; a standalone Approval action's own suspend also never carries these keys.
        stubStoppedJobWithTaskExecutionParameters(
            Map.of("formTitle", "Approve the refund", "formDescription", "over 1000"));

        when(taskStateService.<Suspend>fetchValue(jobResumeId))
            .thenReturn(Optional.empty());

        Map<String, Object> approvalForm = new HashMap<>(approvalFormFacade.getApprovalForm(jobResumeId.toString()));

        assertThat(approvalForm)
            .containsEntry("formTitle", "Approve the refund")
            .containsEntry("formDescription", "over 1000");
    }

    private void stubStoppedJobWithTaskExecutionParameters(Map<String, ?> taskExecutionParameters) {
        Job job = new Job(JOB_ID);

        job.setStatus(Job.Status.STOPPED);

        Map<String, Object> jobMetadata = new HashMap<>();

        jobMetadata.put(MetadataConstants.TASK_EXECUTION_RESUME_ID, TASK_EXECUTION_ID);

        job.setMetadata(jobMetadata);

        when(jobService.getJob(JOB_ID)).thenReturn(job);

        WorkflowTask workflowTask = new WorkflowTask(Map.of(
            WorkflowConstants.NAME, "agent",
            WorkflowConstants.TYPE, "aiAgent/v1/chat",
            WorkflowConstants.PARAMETERS, taskExecutionParameters));

        TaskExecution taskExecution = new TaskExecution();

        taskExecution.setWorkflowTask(workflowTask);

        when(taskExecutionService.getTaskExecution(TASK_EXECUTION_ID)).thenReturn(taskExecution);
    }

    private void stubStoredSuspend(Map<String, ?> continueParameters) {
        when(taskStateService.<Suspend>fetchValue(jobResumeId))
            .thenReturn(Optional.of(new Suspend(continueParameters, null)));
    }
}
