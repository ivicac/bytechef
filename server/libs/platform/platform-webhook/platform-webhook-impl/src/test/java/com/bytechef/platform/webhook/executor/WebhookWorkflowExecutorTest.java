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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.event.TaskExecutionCompleteEvent;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.exception.ExecutionException;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.component.trigger.TriggerOutput;
import com.bytechef.platform.component.trigger.WebhookRequest;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.job.sync.SseStreamBridge;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor.JobFactoryFunction;
import com.bytechef.platform.plan.domain.PlanLimits;
import com.bytechef.platform.plan.provider.PlanLimitsProvider;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.JobCompletionAwaiter;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.platform.workflow.execution.exception.TaskExecutionErrorType;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
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
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
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
    private ObjectProvider<PlanLimitsProvider> planLimitsProviderObjectProvider;

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
            eventPublisher, jobPrincipalAccessorRegistry, jobSyncExecutor, planLimitsProviderObjectProvider,
            principalJobFacade, sseStreamBridgeRegistry, syncJobTaskFileStorage, triggerDefinitionService,
            webhookWorkflowSyncExecutor, workflowService);

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
        Workflow workflow = workflow(workflowTask("respond", "webhook/v1/responseToWebhookRequest"));

        lenient()
            .when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(workflow);
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

        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any(), any(Duration.class)))
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

        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any(), any(Duration.class)))
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
            .execute(
                any(JobParametersDTO.class), jobFactoryFunctionArgumentCaptor.capture(), eq(true), any(),
                any(Duration.class));

        JobParametersDTO jobParametersDTO = new JobParametersDTO(WORKFLOW_ID, Map.of());

        Job job = job(8L, Job.Status.CREATED);

        when(principalJobFacade.createJobWithoutDispatch(jobParametersDTO, JOB_PRINCIPAL_ID, PlatformType.AUTOMATION))
            .thenReturn(job);

        JobFactoryFunction jobFactoryFunction = jobFactoryFunctionArgumentCaptor.getValue();

        assertThat(jobFactoryFunction.apply(jobParametersDTO)).isSameAs(job);

        verify(principalJobFacade, never()).createJob(any(JobParametersDTO.class), any(Long.class), any());
    }

    @Test
    public void testExecuteSyncUsesDefaultTimeoutWithoutPlanLimitsProvider() throws Exception {
        stubTrigger("payload");
        stubJobRun(job(8L, Job.Status.COMPLETED), List.of());

        webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        verify(jobSyncExecutor)
            .execute(
                any(JobParametersDTO.class), any(), eq(true), any(), eq(JobCompletionAwaiter.DEFAULT_SYNC_TIMEOUT));
    }

    @Test
    public void testExecuteSyncCapsTimeoutAtPlanSyncRunTimeout() throws Exception {
        stubPlanSyncRunTimeout(Duration.ofSeconds(30));
        stubTrigger("payload");
        stubJobRun(job(8L, Job.Status.COMPLETED), List.of());

        webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        verify(jobSyncExecutor)
            .execute(any(JobParametersDTO.class), any(), eq(true), any(), eq(Duration.ofSeconds(30)));
    }

    @Test
    public void testExecuteSyncPlanSyncRunTimeoutCannotExtendDefault() throws Exception {
        stubPlanSyncRunTimeout(JobCompletionAwaiter.DEFAULT_SYNC_TIMEOUT.plusHours(1));
        stubTrigger("payload");
        stubJobRun(job(8L, Job.Status.COMPLETED), List.of());

        webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest())
            .get();

        verify(jobSyncExecutor)
            .execute(
                any(JobParametersDTO.class), any(), eq(true), any(), eq(JobCompletionAwaiter.DEFAULT_SYNC_TIMEOUT));
    }

    @Test
    public void testExecuteSyncRejectsWorkflowWithApprovalStepBeforeRunning() {
        Workflow workflow = workflow(workflowTask("approve", "approval/v1/requestApproval"));

        when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(workflow);

        assertThatThrownBy(() -> webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest()))
            .isInstanceOf(ConfigurationException.class)
            .hasMessage(
                SyncExecutionSuspendRejectingTaskCompletionHandler.getMessage(
                    "approve", "approval/v1/requestApproval"));

        verify(webhookWorkflowSyncExecutor, never()).execute(any(), any());
        verify(jobSyncExecutor, never()).execute(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    public void testExecuteSyncRejectsWorkflowWithWaitStepBeforeRunning() {
        Workflow workflow = workflow(
            workflowTask("respond", "webhook/v1/responseToWebhookRequest"),
            workflowTask("pause", "wait/v1/waitAfterTimeInterval"));

        when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(workflow);

        assertThatThrownBy(() -> webhookWorkflowExecutor.executeSync(workflowExecutionId, mockWebhookRequest()))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("'pause' (wait/v1/waitAfterTimeInterval)");

        verify(jobSyncExecutor, never()).execute(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    public void testHasSuspendingTaskDetectsApprovalWaitAndLegacyWaitForApproval() {
        Workflow approvalWorkflow = workflow(workflowTask("approve", "approval/v1/requestApproval"));
        Workflow waitWorkflow = workflow(workflowTask("pause", "wait/v1/waitOnWebhookCall"));
        Workflow legacyApprovalWorkflow = workflow(workflowTask("legacy", "waitForApproval/v1"));
        Workflow plainWorkflow = workflow(workflowTask("respond", "webhook/v1/responseToWebhookRequest"));

        when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(approvalWorkflow, waitWorkflow, legacyApprovalWorkflow, plainWorkflow);

        assertThat(webhookWorkflowExecutor.hasSuspendingTask(workflowExecutionId)).isTrue();
        assertThat(webhookWorkflowExecutor.hasSuspendingTask(workflowExecutionId)).isTrue();
        assertThat(webhookWorkflowExecutor.hasSuspendingTask(workflowExecutionId)).isTrue();
        assertThat(webhookWorkflowExecutor.hasSuspendingTask(workflowExecutionId)).isFalse();
    }

    @Test
    public void testStreamFailsWhenTerminalEventDoesNotArriveWithinPlanTimeout() {
        stubPlanSyncRunTimeout(Duration.ofMillis(50));

        CompletableFuture<Void> registryCompletion = stubStreamRun();
        SseStreamBridge sseStreamBridge = mock(SseStreamBridge.class);

        CompletableFuture<Void> streamFuture = webhookWorkflowExecutor.stream(
            workflowExecutionId, mockWebhookRequest(), sseStreamBridge);

        assertThatThrownBy(() -> streamFuture.get(5, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(TimeoutException.class);

        verify(sseStreamBridge, timeout(5000))
            .onError(argThat(throwable -> ExceptionUtils.getRootCause(throwable) instanceof TimeoutException));
        verify(sseStreamBridge, never()).onComplete();

        assertThat(registryCompletion).isNotDone();
    }

    @Test
    public void testStreamCompletesWhenTerminalEventArrives() throws Exception {
        CompletableFuture<Void> registryCompletion = stubStreamRun();
        SseStreamBridge sseStreamBridge = mock(SseStreamBridge.class);

        CompletableFuture<Void> streamFuture = webhookWorkflowExecutor.stream(
            workflowExecutionId, mockWebhookRequest(), sseStreamBridge);

        registryCompletion.complete(null);

        streamFuture.get(5, TimeUnit.SECONDS);

        verify(sseStreamBridge, timeout(5000)).onComplete();
        verify(sseStreamBridge, never()).onError(any());
    }

    private CompletableFuture<Void> stubStreamRun() {
        stubTrigger("payload");

        when(jobPrincipalAccessor.isWorkflowEnabled(JOB_PRINCIPAL_ID, WORKFLOW_UUID)).thenReturn(true);
        when(principalJobFacade.createJob(any(JobParametersDTO.class), eq(JOB_PRINCIPAL_ID), any()))
            .thenReturn(9L);

        CompletableFuture<Void> registryCompletion = new CompletableFuture<>();

        when(sseStreamBridgeRegistry.register(eq(9L), any()))
            .thenReturn(new SseStreamBridgeRegistry.Registration(() -> {}, registryCompletion));

        return registryCompletion;
    }

    private void stubPlanSyncRunTimeout(Duration syncRunTimeout) {
        PlanLimitsProvider planLimitsProvider = mock(PlanLimitsProvider.class);
        PlanLimits planLimits = mock(PlanLimits.class);

        when(planLimits.syncRunTimeout()).thenReturn(syncRunTimeout);
        when(planLimitsProvider.getPlanLimits(any())).thenReturn(planLimits);
        when(planLimitsProviderObjectProvider.getIfAvailable()).thenReturn(planLimitsProvider);
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
        when(jobSyncExecutor.execute(any(JobParametersDTO.class), any(), eq(true), any(), any(Duration.class)))
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

    private static Workflow workflow(WorkflowTask... workflowTasks) {
        Workflow workflow = mock(Workflow.class);

        lenient().when(workflow.getTasks(true))
            .thenReturn(List.of(workflowTasks));

        return workflow;
    }

    private static WorkflowTask workflowTask(String name, String type) {
        return new WorkflowTask(Map.of("name", name, "type", type));
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
