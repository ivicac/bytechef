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

package com.bytechef.platform.webhook.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.event.TaskExecutionCompleteEvent;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.exception.ExecutionException;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.component.trigger.TriggerOutput;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor.JobFactoryFunction;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.platform.workflow.execution.exception.TaskExecutionErrorType;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for the synchronous webhook path, which runs the job in-process through {@link JobSyncExecutor}. The
 * behaviour under test is how the HTTP reply is assembled from the engine's per-task completion callback: the
 * {@code WEBHOOK_RESPONSE}-tagged task output collected through the callback becomes the reply (the last one to
 * complete wins), a batch (collection) trigger output produces one job per element, a run without a tagged response
 * falls back to the job outputs, a failed job surfaces its error, and the job row is created without coordinator
 * dispatch so the distributed engine never races the in-process one.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
public class WebhookWorkflowExecutorTest {

    private static final long JOB_PRINCIPAL_ID = 100L;
    private static final String TRIGGER_NAME = "trigger_1";
    private static final String WORKFLOW_ID = "workflow-id";
    private static final String WORKFLOW_UUID = "workflow-uuid";

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private JobPrincipalAccessor jobPrincipalAccessor;

    @Mock
    private JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry;

    @Mock
    private JobSyncExecutor jobSyncExecutor;

    @Mock
    private PrincipalJobFacade principalJobFacade;

    @Mock
    private SseStreamBridgeRegistry sseStreamBridgeRegistry;

    @Mock
    private TaskFileStorage syncJobTaskFileStorage;

    @Mock
    private TriggerDefinitionService triggerDefinitionService;

    @Mock
    private WebhookWorkflowSyncExecutor webhookWorkflowSyncExecutor;

    @Mock
    private WorkflowService workflowService;

    private WebhookWorkflowExecutorImpl webhookWorkflowExecutor;
    private WorkflowExecutionId workflowExecutionId;

    @BeforeEach
    public void setUp() {
        webhookWorkflowExecutor = new WebhookWorkflowExecutorImpl(
            eventPublisher, jobPrincipalAccessorRegistry, jobSyncExecutor, principalJobFacade,
            sseStreamBridgeRegistry, syncJobTaskFileStorage, triggerDefinitionService, webhookWorkflowSyncExecutor,
            workflowService);

        workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, JOB_PRINCIPAL_ID, WORKFLOW_UUID, TRIGGER_NAME);

        lenient()
            .when(jobPrincipalAccessorRegistry.getJobPrincipalAccessor(PlatformType.AUTOMATION))
            .thenReturn(jobPrincipalAccessor);
        lenient()
            .when(jobPrincipalAccessor.getInputMap(JOB_PRINCIPAL_ID, WORKFLOW_UUID))
            .thenReturn(Map.of());
        lenient()
            .when(jobPrincipalAccessor.getWorkflowId(JOB_PRINCIPAL_ID, WORKFLOW_UUID))
            .thenReturn(WORKFLOW_ID);
    }

    @Test
    public void testExecuteSyncReturnsWebhookResponseCollectedFromCallback() throws Exception {
        stubTrigger("payload");

        FileEntry output = mockFileEntry();

        stubJobRun(job(1L, Job.Status.COMPLETED), List.of(webhookResponseTaskExecution(10L, output)));

        when(syncJobTaskFileStorage.readTaskExecutionOutput(output)).thenReturn("response");

        stubJobOutputs(1L, "response");

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs).isEqualTo(Map.of(MetadataConstants.WEBHOOK_RESPONSE, "response"));
    }

    @Test
    public void testExecuteSyncLastCompletedWebhookResponseWins() throws Exception {
        stubTrigger("payload");

        FileEntry earlyOutput = mockFileEntry();
        FileEntry lateOutput = mockFileEntry();

        stubJobRun(
            job(2L, Job.Status.COMPLETED),
            List.of(webhookResponseTaskExecution(20L, earlyOutput), webhookResponseTaskExecution(21L, lateOutput)));

        when(syncJobTaskFileStorage.readTaskExecutionOutput(earlyOutput)).thenReturn("early-response");
        when(syncJobTaskFileStorage.readTaskExecutionOutput(lateOutput)).thenReturn("late-response");

        stubJobOutputs(2L, "late-response");

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs).isEqualTo(Map.of(MetadataConstants.WEBHOOK_RESPONSE, "late-response"));

        verify(syncJobTaskFileStorage, never())
            .storeJobOutputs(2L, Map.of(MetadataConstants.WEBHOOK_RESPONSE, "early-response"));
    }

    @Test
    public void testExecuteSyncIgnoresUntaggedTaskCompletions() throws Exception {
        stubTrigger("payload");

        TaskExecution untaggedTaskExecution = new TaskExecution();

        untaggedTaskExecution.setId(30L);
        untaggedTaskExecution.setOutput(mockFileEntry());

        stubJobRun(job(3L, Job.Status.COMPLETED), List.of(untaggedTaskExecution));

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs).isNull();

        verify(syncJobTaskFileStorage, never()).readTaskExecutionOutput(any());
    }

    @Test
    public void testExecuteSyncBatchCollectionReturnsListPerElement() throws Exception {
        when(webhookWorkflowSyncExecutor.execute(eq(workflowExecutionId), any()))
            .thenReturn(new TriggerOutput(List.of("first", "second"), null, false));

        FileEntry firstOutput = mockFileEntry();
        FileEntry secondOutput = mockFileEntry();

        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any()))
            .thenAnswer(invocation -> {
                JobParametersDTO jobParametersDTO = invocation.getArgument(0);
                Consumer<TaskExecutionCompleteEvent> callback = invocation.getArgument(3);

                Map<String, ?> inputs = jobParametersDTO.getInputs();

                if ("first".equals(inputs.get(TRIGGER_NAME))) {
                    callback.accept(new TaskExecutionCompleteEvent(webhookResponseTaskExecution(40L, firstOutput)));

                    return job(4L, Job.Status.COMPLETED);
                }

                callback.accept(new TaskExecutionCompleteEvent(webhookResponseTaskExecution(50L, secondOutput)));

                return job(5L, Job.Status.COMPLETED);
            });

        when(syncJobTaskFileStorage.readTaskExecutionOutput(firstOutput)).thenReturn("first-response");
        when(syncJobTaskFileStorage.readTaskExecutionOutput(secondOutput)).thenReturn("second-response");

        stubJobOutputs(4L, "first-response");
        stubJobOutputs(5L, "second-response");

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs)
            .isEqualTo(
                List.of(
                    Map.of(MetadataConstants.WEBHOOK_RESPONSE, "first-response"),
                    Map.of(MetadataConstants.WEBHOOK_RESPONSE, "second-response")));
    }

    @Test
    public void testExecuteSyncReturnsNullWhenNoWebhookResponseAndNoJobOutputs() throws Exception {
        stubTrigger("payload");
        stubJobRun(job(6L, Job.Status.COMPLETED), List.of());

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs).isNull();
    }

    @Test
    public void testExecuteSyncFallsBackToJobOutputsWhenNoWebhookResponse() throws Exception {
        stubTrigger("payload");

        Job job = job(7L, Job.Status.COMPLETED);

        FileEntry jobOutputs = mockFileEntry();

        job.setOutputs(jobOutputs);

        stubJobRun(job, List.of());

        doReturn(Map.of("result", "value")).when(syncJobTaskFileStorage)
            .readJobOutputs(jobOutputs);

        Object outputs = webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        assertThat(outputs).isEqualTo(Map.of("result", "value"));
    }

    @Test
    public void testExecuteSyncSurfacesFailedJobError() {
        stubTrigger("payload");

        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any()))
            .thenThrow(new ExecutionException("boom", TaskExecutionErrorType.TASK_EXECUTION_FAILED));

        assertThatThrownBy(() -> webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest()))
            .isInstanceOf(ExecutionException.class)
            .hasMessage("boom");
    }

    @Test
    public void testExecuteSyncCreatesJobRowWithoutCoordinatorDispatch() throws Exception {
        stubTrigger("payload");
        stubJobRun(job(8L, Job.Status.COMPLETED), List.of());

        webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        ArgumentCaptor<JobFactoryFunction> jobFactoryFunctionArgumentCaptor = ArgumentCaptor.forClass(
            JobFactoryFunction.class);

        verify(jobSyncExecutor)
            .execute(any(JobParametersDTO.class), jobFactoryFunctionArgumentCaptor.capture(), eq(true), any());

        JobParametersDTO jobParametersDTO = new JobParametersDTO(WORKFLOW_ID, Map.of());

        Job job = job(8L, Job.Status.CREATED);

        when(principalJobFacade.createJobWithoutDispatch(jobParametersDTO, JOB_PRINCIPAL_ID, PlatformType.AUTOMATION))
            .thenReturn(job);

        JobFactoryFunction jobFactoryFunction = jobFactoryFunctionArgumentCaptor.getValue();

        assertThat(jobFactoryFunction.apply(jobParametersDTO)).isSameAs(job);

        verify(principalJobFacade, never()).createJob(any(JobParametersDTO.class), any(Long.class), any());
    }

    private void stubTrigger(Object triggerValue) {
        when(webhookWorkflowSyncExecutor.execute(eq(workflowExecutionId), any()))
            .thenReturn(new TriggerOutput(triggerValue, null, false));
    }

    /**
     * Stubs the engine run: each given task execution is delivered to the completion callback in order, then the job is
     * returned as the run result.
     */
    private void stubJobRun(Job job, List<TaskExecution> completedTaskExecutions) {
        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any()))
            .thenAnswer(invocation -> {
                Consumer<TaskExecutionCompleteEvent> callback = invocation.getArgument(3);

                for (TaskExecution taskExecution : completedTaskExecutions) {
                    callback.accept(new TaskExecutionCompleteEvent(taskExecution));
                }

                return job;
            });
    }

    private void stubJobOutputs(long jobId, String webhookResponse) {
        Map<String, Object> outputsMap = Map.of(MetadataConstants.WEBHOOK_RESPONSE, webhookResponse);

        FileEntry outputsFileEntry = mockFileEntry();

        when(syncJobTaskFileStorage.storeJobOutputs(jobId, outputsMap)).thenReturn(outputsFileEntry);

        doReturn(outputsMap).when(syncJobTaskFileStorage)
            .readJobOutputs(outputsFileEntry);
    }

    private static Job job(long id, Job.Status status) {
        Job job = new Job();

        job.setId(id);
        job.setStatus(status);

        return job;
    }

    private static TaskExecution webhookResponseTaskExecution(long id, FileEntry output) {
        TaskExecution taskExecution = new TaskExecution();

        taskExecution.setId(id);
        taskExecution.putMetadata(MetadataConstants.WEBHOOK_RESPONSE, true);
        taskExecution.setOutput(output);

        return taskExecution;
    }

    private static FileEntry mockFileEntry() {
        return mock(FileEntry.class);
    }

    private static WebhookRequest mockWebhookRequest() {
        return mock(WebhookRequest.class);
    }
}
