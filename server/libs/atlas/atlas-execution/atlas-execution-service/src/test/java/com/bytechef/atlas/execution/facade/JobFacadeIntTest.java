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

package com.bytechef.atlas.execution.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.converter.StringToWorkflowTaskConverter;
import com.bytechef.atlas.configuration.converter.WorkflowTaskToStringConverter;
import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.event.ResumeJobEvent;
import com.bytechef.atlas.coordinator.event.StartJobEvent;
import com.bytechef.atlas.coordinator.event.TaskExecutionCompleteEvent;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.repository.JobRepository;
import com.bytechef.atlas.execution.repository.TaskExecutionRepository;
import com.bytechef.atlas.execution.repository.jdbc.converter.StringToWebhooksConverter;
import com.bytechef.atlas.execution.repository.jdbc.converter.WebhooksToStringConverter;
import com.bytechef.atlas.execution.service.ContextService;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.JobServiceImpl;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.execution.service.TaskExecutionServiceImpl;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.commons.data.jdbc.converter.ExecutionErrorToStringConverter;
import com.bytechef.commons.data.jdbc.converter.FileEntryToStringConverter;
import com.bytechef.commons.data.jdbc.converter.MapWrapperToStringConverter;
import com.bytechef.commons.data.jdbc.converter.StringToFileEntryConverter;
import com.bytechef.commons.data.jdbc.converter.StringToMapWrapperConverter;
import com.bytechef.jackson.config.JacksonConfiguration;
import com.bytechef.liquibase.config.LiquibaseConfiguration;
import com.bytechef.test.config.jdbc.AbstractIntTestJdbcConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.Validate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.ObjectMapper;

/**
 * Ivica Cardic
 */
@SpringBootTest(
    properties = {
        "bytechef.workflow.repository.jdbc.enabled=true"
    })
@Import({
    JacksonConfiguration.class, LiquibaseConfiguration.class, PostgreSQLContainerConfiguration.class
})
@RecordApplicationEvents
public class JobFacadeIntTest {

    private static final String WORKFLOW_ID = "demo:1234";

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private JobFacade jobFacade;

    @Autowired
    private JobRepository jobRepository;

    @MockitoSpyBean
    private JobService jobService;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private TaskFileStorage taskFileStorage;

    @Autowired
    private WorkflowService workflowService;

    @Test
    public void testRequiredParameters() {
        // The referenced workflow does not exist (the mocked WorkflowService returns null), so job creation must reject
        // the request rather than proceed with a null workflow.
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> jobFacade.createJob(new JobParametersDTO("aGVsbG8x", Collections.emptyMap())));
    }

    @Test
    public void testDeleteJobRecursivelyDeletesChildJobsAndTaskExecutions() {
        Job parentJob = jobRepository.save(newJob());
        long parentJobId = Validate.notNull(parentJob.getId(), "id");

        TaskExecution parentTaskExecution = taskExecutionRepository.save(newTaskExecution(parentJobId, null));

        long parentTaskExecutionId = Validate.notNull(parentTaskExecution.getId(), "id");

        Job childJob = jobRepository.save(newJob());

        childJob.setParentTaskExecutionId(parentTaskExecutionId);

        jobRepository.save(childJob);

        long childJobId = Validate.notNull(childJob.getId(), "id");

        TaskExecution childTaskExecution = taskExecutionRepository.save(newTaskExecution(childJobId, null));

        long childTaskExecutionId = Validate.notNull(childTaskExecution.getId(), "id");

        jobFacade.deleteJob(parentJobId);

        assertThat(jobRepository.findById(parentJobId)).isEmpty();
        assertThat(jobRepository.findById(childJobId)).isEmpty();
        assertThat(taskExecutionRepository.findById(parentTaskExecutionId)).isEmpty();
        assertThat(taskExecutionRepository.findById(childTaskExecutionId)).isEmpty();
    }

    @Test
    public void testOptimisticLockRejectsConcurrentStoppedResumeClaim() {
        Job job = newJob();

        job.setStatus(Job.Status.STOPPED);

        long jobId = Validate.notNull(jobRepository.save(job)
            .getId(), "id");

        // Two racers read the run at the same version and both attempt to claim it (STOPPED -> STARTED).
        Job racerA = jobRepository.findById(jobId)
            .orElseThrow();
        Job racerB = jobRepository.findById(jobId)
            .orElseThrow();

        racerA.setStatus(Job.Status.STARTED);

        jobRepository.save(racerA);

        racerB.setStatus(Job.Status.STARTED);

        // The loser's stale-version update must be rejected — the mechanism behind JobResumeFacadeImpl's single-winner
        // resume claim (one OK, one GONE) and its rejection of resurrecting a run the expiry sweep flipped to FAILED.
        assertThatThrownBy(() -> jobRepository.save(racerB)).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jobRepository.findById(jobId))
            .get()
            .extracting(Job::getStatus)
            .isEqualTo(Job.Status.STARTED);
    }

    @Test
    public void testTryClaimResumeReturnsFalseWithoutThrowingWhenClaimIsLost() {
        Job job = newJob();

        job.setStatus(Job.Status.STOPPED);

        long jobId = Validate.notNull(jobRepository.save(job)
            .getId(), "id");

        Job racerA = jobRepository.findById(jobId)
            .orElseThrow();
        Job racerB = jobRepository.findById(jobId)
            .orElseThrow();

        assertThat(jobService.tryClaimResume(racerA)).isTrue();

        // The loser's stale-version claim must return false, NOT throw OptimisticLockingFailureException, so the
        // @Transactional resume facade is not left with a rollback-only transaction when it reports GONE.
        assertThat(jobService.tryClaimResume(racerB)).isFalse();
    }

    @Test
    public void testResumeToStatusStartedTransitionsStoppedJob() {
        Job job = newJob();

        job.setStatus(Job.Status.STOPPED);

        long jobId = Validate.notNull(jobRepository.save(job)
            .getId(), "id");

        assertThat(jobService.resumeToStatusStarted(jobId)
            .getStatus()).isEqualTo(Job.Status.STARTED);
    }

    @Test
    public void testResumeToStatusStartedRejectsAStartedJob() {
        long jobId = saveJob(Job.Status.STARTED);

        assertThatThrownBy(() -> jobService.resumeToStatusStarted(jobId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testResumeJobRejectsARestartOfAStartedJob() {
        long jobId = saveJob(Job.Status.FAILED);

        jobFacade.resumeJob(jobId);

        assertThatThrownBy(() -> jobFacade.resumeJob(jobId))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(applicationEvents.stream(ResumeJobEvent.class)).hasSize(1);
    }

    @Test
    public void testResumeClaimedJobPublishesAStartedResumeEvent() {
        jobFacade.resumeClaimedJob(1L, 2L, Map.of("approved", true));

        assertThat(applicationEvents.stream(ResumeJobEvent.class))
            .singleElement()
            .satisfies(resumeJobEvent -> {
                assertThat(resumeJobEvent.getJobId()).isEqualTo(1L);
                assertThat(resumeJobEvent.isStarted()).isTrue();
                assertThat(resumeJobEvent.getTaskExecutionId()).isEqualTo(2L);
                assertThat(resumeJobEvent.getData()).isEqualTo(Map.of("approved", true));
            });
    }

    @Test
    public void testConcurrentResumeApprovalsCompleteTheWaitingTaskExecutionOnce() throws Exception {
        long jobId = saveJob(Job.Status.STOPPED);

        long taskExecutionId = saveStartedTaskExecution(jobId);

        CyclicBarrier statusCheckBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            Object job = invocation.callRealMethod();

            statusCheckBarrier.await(10, TimeUnit.SECONDS);

            return job;
        }).when(jobService)
            .getJob(jobId);

        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstApproval = executorService.submit(() -> resumeApproval(jobId, "uuid-1"));
            Future<Boolean> secondApproval = executorService.submit(() -> resumeApproval(jobId, "uuid-2"));

            List<Boolean> results = List.of(
                firstApproval.get(30, TimeUnit.SECONDS), secondApproval.get(30, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);
        } finally {
            executorService.shutdownNow();
        }

        verify(taskFileStorage, times(1)).storeTaskExecutionOutput(eq(jobId), eq(taskExecutionId), anyMap());
    }

    @Test
    public void testResumeApprovalRejectsAJobThatIsNotStopped() {
        long jobId = saveJob(Job.Status.STARTED);

        saveStartedTaskExecution(jobId);

        assertThatThrownBy(() -> jobFacade.resumeApproval(jobId, "uuid-1", true))
            .isInstanceOf(IllegalStateException.class);

        verify(taskFileStorage, never()).storeTaskExecutionOutput(eq(jobId), anyLong(), anyMap());

        assertThat(applicationEvents.stream(TaskExecutionCompleteEvent.class)).isEmpty();
    }

    @Test
    public void testResumeApprovalCompletesTheWaitingTaskExecution() {
        long jobId = saveJob(Job.Status.STOPPED);

        long taskExecutionId = saveStartedTaskExecution(jobId);

        jobFacade.resumeApproval(jobId, "uuid-1", true);

        assertThat(jobRepository.findById(jobId))
            .get()
            .extracting(Job::getStatus)
            .isEqualTo(Job.Status.STARTED);

        verify(taskFileStorage).storeTaskExecutionOutput(jobId, taskExecutionId, Map.of("approved", true));

        assertThat(applicationEvents.stream(TaskExecutionCompleteEvent.class)).hasSize(1);
    }

    @Test
    public void testCreateJobPassesTheJobIdToTheCallbackInsideTheCreationTransaction() {
        stubWorkflow();

        AtomicReference<Job> linkedJob = new AtomicReference<>();

        long jobId = jobFacade.createJob(
            new JobParametersDTO(WORKFLOW_ID, Map.of()),
            createdJobId -> linkedJob.set(jobRepository.findById(createdJobId)
                .orElseThrow()));

        assertThat(linkedJob.get()
            .getId()).isEqualTo(jobId);
        assertThat(applicationEvents.stream(StartJobEvent.class)).hasSize(1);
    }

    @Test
    public void testCreateJobRollsTheJobBackWhenTheCallbackFails() {
        stubWorkflow();

        AtomicReference<Long> createdJobIdReference = new AtomicReference<>();

        assertThatThrownBy(
            () -> jobFacade.createJob(
                new JobParametersDTO(WORKFLOW_ID, Map.of()),
                createdJobId -> {
                    createdJobIdReference.set(createdJobId);

                    throw new IllegalStateException("linking failed");
                }))
                    .isInstanceOf(IllegalStateException.class);

        assertThat(createdJobIdReference.get()).isNotNull();
        assertThat(jobRepository.findById(createdJobIdReference.get())).isEmpty();
        assertThat(applicationEvents.stream(StartJobEvent.class)).isEmpty();
    }

    private boolean resumeApproval(long jobId, String uuid) {
        try {
            jobFacade.resumeApproval(jobId, uuid, true);

            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private long saveJob(Job.Status status) {
        Job job = newJob();

        job.setStatus(status);

        return Validate.notNull(jobRepository.save(job)
            .getId(), "id");
    }

    private long saveStartedTaskExecution(long jobId) {
        TaskExecution taskExecution = newTaskExecution(jobId, null);

        taskExecution.setStatus(TaskExecution.Status.STARTED);

        return Validate.notNull(taskExecutionRepository.save(taskExecution)
            .getId(), "id");
    }

    private void stubWorkflow() {
        Workflow workflow = mock(Workflow.class);

        when(workflow.getId()).thenReturn(WORKFLOW_ID);
        when(workflow.getInputs()).thenReturn(List.of());
        when(workflow.getTasks()).thenReturn(List.of());

        when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(workflow);
    }

    @Test
    public void testResumeToStatusStartedResumesSuspendedSubflowChild() {
        Job parentJob = jobRepository.save(newJob());
        long parentJobId = Validate.notNull(parentJob.getId(), "id");

        TaskExecution parentTaskExecution = taskExecutionRepository.save(newTaskExecution(parentJobId, null));
        long parentTaskExecutionId = Validate.notNull(parentTaskExecution.getId(), "id");

        Job job = newJob();

        job.setParentTaskExecutionId(parentTaskExecutionId);
        job.setStatus(Job.Status.STOPPED);

        long jobId = Validate.notNull(jobRepository.save(job)
            .getId(), "id");

        assertThat(jobService.resumeToStatusStarted(jobId)
            .getStatus()).isEqualTo(Job.Status.STARTED);
    }

    private static Job newJob() {
        Job job = new Job();

        job.setStatus(Job.Status.COMPLETED);
        job.setWorkflowId(WORKFLOW_ID);

        return job;
    }

    private static TaskExecution newTaskExecution(long jobId, Long parentId) {
        Map<String, Object> taskMap = new HashMap<>();

        taskMap.put("name", "task1");
        taskMap.put("type", "test/v1/noop");

        TaskExecution taskExecution = TaskExecution.builder()
            .workflowTask(new WorkflowTask(taskMap))
            .build();

        taskExecution.setJobId(jobId);
        taskExecution.setTaskNumber(1);

        if (parentId != null) {
            taskExecution.setParentId(parentId);
        }

        return taskExecution;
    }

    @ComponentScan(basePackages = {
        "com.bytechef.atlas.execution.facade", "com.bytechef.atlas.configuration.converter"
    })
    @EnableAutoConfiguration
    @Configuration
    public static class WorkflowExecutionIntTestConfiguration {

        @Bean
        ContextService contextService() {
            return mock(ContextService.class);
        }

        @Bean
        WorkflowService workflowService() {
            return mock(WorkflowService.class);
        }

        @Bean
        TaskFileStorage taskFileStorage() {
            return mock(TaskFileStorage.class);
        }

        @Bean
        JobFacade jobFacade(
            ApplicationEventPublisher eventPublisher, ContextService contextService, JobService jobService,
            TaskExecutionService taskExecutionService, TaskFileStorage taskFileStorage,
            WorkflowService workflowService) {

            return new JobFacadeImpl(
                eventPublisher, contextService, jobService, taskExecutionService, taskFileStorage, workflowService);
        }

        @Bean
        JobService jobService(JobRepository jobRepository) {
            return new JobServiceImpl(jobRepository);
        }

        @Bean
        TaskExecutionService taskExecutionService(TaskExecutionRepository taskExecutionRepository) {
            return new TaskExecutionServiceImpl(taskExecutionRepository);
        }

        @EnableJdbcAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
        public static class WorkflowIntTestJdbcConfiguration extends AbstractIntTestJdbcConfiguration {

            private final ObjectMapper objectMapper;

            @SuppressFBWarnings("EI")
            public WorkflowIntTestJdbcConfiguration(ObjectMapper objectMapper) {
                this.objectMapper = objectMapper;
            }

            @Override
            protected List<?> userConverters() {
                return Arrays.asList(
                    new ExecutionErrorToStringConverter(objectMapper),
                    new FileEntryToStringConverter(objectMapper),
                    new MapWrapperToStringConverter(objectMapper),
                    new StringToFileEntryConverter(objectMapper),
                    new StringToMapWrapperConverter(objectMapper),
                    new StringToWebhooksConverter(objectMapper),
                    new StringToWorkflowTaskConverter(objectMapper),
                    new WebhooksToStringConverter(objectMapper),
                    new WorkflowTaskToStringConverter(objectMapper));
            }
        }
    }
}
