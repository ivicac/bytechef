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

package com.bytechef.task.dispatcher.subflow.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.coordinator.event.JobStatusApplicationEvent;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.JobInputConstants;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.PendingSubflowRequest;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowRequestConstants;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class AgentSubflowLauncherTest {

    private static final int MAX_DEPTH = 10;

    @Mock
    private ChildJobPrincipalFactory childJobPrincipalFactory;

    @Mock
    private JobFacade jobFacade;

    @Mock
    private JobService jobService;

    @Mock
    private TaskExecutionService taskExecutionService;

    @Test
    void testLaunchesSubflowOnAgentStop() {
        long agentJobId = 100L;

        PendingSubflowRequest request = new PendingSubflowRequest(
            "wf-99", "newWorkflowCall", Map.of("amount", 5), false, PlatformType.AUTOMATION);

        ActionContext.Suspend suspend = new ActionContext.Suspend(
            Map.of(SubflowRequestConstants.PENDING_SUBFLOW, request), null);

        Job agentJob = new Job();

        agentJob.setId(agentJobId);
        agentJob.setMetadata(new HashMap<>());

        when(jobService.getJob(agentJobId)).thenReturn(agentJob);

        TaskExecution suspendedTask = new TaskExecution();

        suspendedTask.setMetadata(Map.of(MetadataConstants.SUSPEND, suspend));

        when(taskExecutionService.fetchLastJobTaskExecution(agentJobId)).thenReturn(Optional.of(suspendedTask));
        when(childJobPrincipalFactory.createPrincipalLinkedJob(eq(agentJobId), any())).thenReturn(200L);

        AgentSubflowLauncher launcher = new AgentSubflowLauncher(
            childJobPrincipalFactory, jobFacade, jobService, MAX_DEPTH, taskExecutionService);

        launcher.onApplicationEvent(new JobStatusApplicationEvent(agentJobId, Job.Status.STOPPED));

        ArgumentCaptor<JobParametersDTO> jobParametersDTOArgumentCaptor =
            ArgumentCaptor.forClass(JobParametersDTO.class);

        verify(childJobPrincipalFactory).createPrincipalLinkedJob(
            eq(agentJobId), jobParametersDTOArgumentCaptor.capture());
        verify(jobService).update(agentJob);

        JobParametersDTO jobParametersDTO = jobParametersDTOArgumentCaptor.getValue();

        // The reserved __triggerName key is seeded alongside the inputsName-keyed entry so a sub-agent workflow's
        // branch dispatcher can route its workflowCall case portably.
        assertThat(jobParametersDTO.getInputs())
            .containsEntry(JobInputConstants.TRIGGER_NAME_INPUT, "newWorkflowCall")
            .containsKey("newWorkflowCall");
    }

    @Test
    void testIgnoresStopWithoutPendingSubflowRequest() {
        Job job = new Job();

        job.setId(100L);
        job.setMetadata(new HashMap<>());

        when(jobService.getJob(100L)).thenReturn(job);
        when(taskExecutionService.fetchLastJobTaskExecution(100L)).thenReturn(Optional.empty());

        AgentSubflowLauncher launcher = new AgentSubflowLauncher(
            childJobPrincipalFactory, jobFacade, jobService, MAX_DEPTH, taskExecutionService);

        launcher.onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        verify(childJobPrincipalFactory, never()).createPrincipalLinkedJob(anyLong(), any());
    }

    @Test
    void testIdempotentWhenAlreadyLaunched() {
        Job agentJob = new Job();

        agentJob.setId(100L);
        agentJob.setMetadata(Map.of(SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID, 200L));

        when(jobService.getJob(100L)).thenReturn(agentJob);

        AgentSubflowLauncher launcher = new AgentSubflowLauncher(
            childJobPrincipalFactory, jobFacade, jobService, MAX_DEPTH, taskExecutionService);

        launcher.onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        verify(childJobPrincipalFactory, never()).createPrincipalLinkedJob(anyLong(), any());
    }

    /**
     * I3: if the post-create idempotency metadata update fails (DB blip, optimistic-lock collision), the launcher must
     * re-throw rather than swallow. Re-throwing forces the broker to redeliver the {@code STOPPED} event with a logged
     * ERROR, surfacing the duplicate-launch risk to operators -- swallowing silently leaves an orphan sub-workflow and
     * a hidden double-execution window. See review finding I3.
     */
    @Test
    void testRethrowsWhenMetadataUpdateFails() {
        long agentJobId = 100L;

        PendingSubflowRequest request = new PendingSubflowRequest(
            "wf-99", "newWorkflowCall", Map.of("amount", 5), false, PlatformType.AUTOMATION);

        ActionContext.Suspend suspend = new ActionContext.Suspend(
            Map.of(SubflowRequestConstants.PENDING_SUBFLOW, request), null);

        Job agentJob = new Job();

        agentJob.setId(agentJobId);
        agentJob.setMetadata(new HashMap<>());

        when(jobService.getJob(agentJobId)).thenReturn(agentJob);

        TaskExecution suspendedTask = new TaskExecution();

        suspendedTask.setMetadata(Map.of(MetadataConstants.SUSPEND, suspend));

        when(taskExecutionService.fetchLastJobTaskExecution(agentJobId)).thenReturn(Optional.of(suspendedTask));
        when(childJobPrincipalFactory.createPrincipalLinkedJob(eq(agentJobId), any())).thenReturn(200L);

        doThrow(new IllegalStateException("optimistic lock collision"))
            .when(jobService)
            .update(agentJob);

        AgentSubflowLauncher launcher = new AgentSubflowLauncher(
            childJobPrincipalFactory, jobFacade, jobService, MAX_DEPTH, taskExecutionService);

        assertThrows(
            IllegalStateException.class,
            () -> launcher.onApplicationEvent(new JobStatusApplicationEvent(agentJobId, Job.Status.STOPPED)));

        // The sub-workflow IS already launched at this point -- the bug being prevented is silently swallowing the
        // metadata-update failure, which would let broker redelivery launch a SECOND sub-workflow.

        verify(childJobPrincipalFactory).createPrincipalLinkedJob(eq(agentJobId), any());
    }

    @Test
    void testStampsIncrementedDepthOnLaunchedSubflow() {
        stubSuspendedAgent(100L, new HashMap<>());

        when(childJobPrincipalFactory.createPrincipalLinkedJob(eq(100L), any())).thenReturn(200L);

        newLauncher(MAX_DEPTH).onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        assertThat(captureLaunchedJobParameters().getMetadata())
            .containsEntry(SubflowRequestConstants.SUBFLOW_DEPTH, 1);
    }

    @Test
    void testCarriesDepthForwardFromTheCallingJob() {
        Map<String, Object> agentJobMetadata = new HashMap<>();

        agentJobMetadata.put(SubflowRequestConstants.SUBFLOW_DEPTH, 3);

        stubSuspendedAgent(100L, agentJobMetadata);

        when(childJobPrincipalFactory.createPrincipalLinkedJob(eq(100L), any())).thenReturn(200L);

        newLauncher(MAX_DEPTH).onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        assertThat(captureLaunchedJobParameters().getMetadata())
            .containsEntry(SubflowRequestConstants.SUBFLOW_DEPTH, 4);
    }

    @Test
    void testRefusesAndResumesTheAgentWhenDepthWouldBeExceeded() {
        Map<String, Object> agentJobMetadata = new HashMap<>();

        agentJobMetadata.put(SubflowRequestConstants.SUBFLOW_DEPTH, 2);
        agentJobMetadata.put(MetadataConstants.TASK_EXECUTION_RESUME_ID, 7L);

        Job agentJob = stubSuspendedAgent(100L, agentJobMetadata);

        agentJob.setStatus(Job.Status.STOPPED);

        newLauncher(2).onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        verify(childJobPrincipalFactory, never()).createPrincipalLinkedJob(anyLong(), any());
        verify(jobFacade).resumeJob(100L, 7L, Map.of("error", AgentSubflowLauncher.tooDeepError(2)));
    }

    @Test
    void testDoesNotResumeTwiceWhenTheRefusedStopEventIsRedelivered() {
        Map<String, Object> agentJobMetadata = new HashMap<>();

        agentJobMetadata.put(SubflowRequestConstants.SUBFLOW_DEPTH, 2);
        agentJobMetadata.put(MetadataConstants.TASK_EXECUTION_RESUME_ID, 7L);

        Job agentJob = stubSuspendedAgent(100L, agentJobMetadata);

        agentJob.setStatus(Job.Status.STARTED);

        newLauncher(2).onApplicationEvent(new JobStatusApplicationEvent(100L, Job.Status.STOPPED));

        verify(childJobPrincipalFactory, never()).createPrincipalLinkedJob(anyLong(), any());
        verify(jobFacade, never()).resumeJob(anyLong(), anyLong(), any());
    }

    private AgentSubflowLauncher newLauncher(int maxDepth) {
        return new AgentSubflowLauncher(
            childJobPrincipalFactory, jobFacade, jobService, maxDepth, taskExecutionService);
    }

    private Job stubSuspendedAgent(long agentJobId, Map<String, Object> agentJobMetadata) {
        PendingSubflowRequest request = new PendingSubflowRequest(
            "wf-99", "newWorkflowCall", Map.of("amount", 5), false, PlatformType.AUTOMATION);

        Job agentJob = new Job();

        agentJob.setId(agentJobId);
        agentJob.setMetadata(agentJobMetadata);

        when(jobService.getJob(agentJobId)).thenReturn(agentJob);

        TaskExecution suspendedTask = new TaskExecution();

        suspendedTask.setMetadata(
            Map.of(
                MetadataConstants.SUSPEND,
                new ActionContext.Suspend(Map.of(SubflowRequestConstants.PENDING_SUBFLOW, request), null)));

        when(taskExecutionService.fetchLastJobTaskExecution(agentJobId)).thenReturn(Optional.of(suspendedTask));

        return agentJob;
    }

    private JobParametersDTO captureLaunchedJobParameters() {
        ArgumentCaptor<JobParametersDTO> jobParametersDTOArgumentCaptor =
            ArgumentCaptor.forClass(JobParametersDTO.class);

        verify(childJobPrincipalFactory).createPrincipalLinkedJob(anyLong(), jobParametersDTOArgumentCaptor.capture());

        return jobParametersDTOArgumentCaptor.getValue();
    }
}
