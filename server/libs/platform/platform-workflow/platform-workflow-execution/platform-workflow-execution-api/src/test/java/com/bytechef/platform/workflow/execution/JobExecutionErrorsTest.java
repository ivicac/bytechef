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

package com.bytechef.platform.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.error.ExecutionError;
import com.bytechef.exception.ExecutionException;
import com.bytechef.platform.workflow.execution.exception.JobErrorType;
import com.bytechef.platform.workflow.execution.exception.TaskExecutionErrorType;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class JobExecutionErrorsTest {

    private static final long JOB_ID = 4567L;

    private final TaskExecutionService taskExecutionService = mock(TaskExecutionService.class);

    @Test
    void testCheckForErrorReportsFailedBranchTaskWhenLastTaskWasCancelled() {
        TaskExecution forkJoinTaskExecution = createTaskExecution(1L, TaskExecution.Status.FAILED, null);
        TaskExecution failedBranchTaskExecution = createTaskExecution(2L, TaskExecution.Status.FAILED, "branch failed");
        TaskExecution cancelledBranchTaskExecution = createTaskExecution(3L, TaskExecution.Status.CANCELLED, null);

        stubTaskExecutions(
            cancelledBranchTaskExecution, forkJoinTaskExecution, failedBranchTaskExecution,
            cancelledBranchTaskExecution);

        assertThatThrownBy(() -> JobExecutionErrors.checkForError(createJob(Job.Status.FAILED), taskExecutionService))
            .isInstanceOfSatisfying(ExecutionException.class, executionException -> {
                assertThat(executionException.getMessage()).isEqualTo("branch failed");
                assertThat(executionException.getErrorKey()).isEqualTo(
                    TaskExecutionErrorType.TASK_EXECUTION_FAILED.getErrorKey());
                assertThat(executionException.getEntityClass()).isEqualTo(TaskExecution.class);
            });
    }

    @Test
    void testCheckForErrorReportsMostRecentFailedTaskWithError() {
        TaskExecution earlierFailedTaskExecution = createTaskExecution(1L, TaskExecution.Status.FAILED, "first");
        TaskExecution laterFailedTaskExecution = createTaskExecution(2L, TaskExecution.Status.FAILED, "second");
        TaskExecution cancelledTaskExecution = createTaskExecution(3L, TaskExecution.Status.CANCELLED, null);

        stubTaskExecutions(
            cancelledTaskExecution, earlierFailedTaskExecution, laterFailedTaskExecution, cancelledTaskExecution);

        assertThatThrownBy(() -> JobExecutionErrors.checkForError(createJob(Job.Status.FAILED), taskExecutionService))
            .isInstanceOf(ExecutionException.class)
            .hasMessage("second");
    }

    @Test
    void testCheckForErrorIgnoresRetriedFailureOfCompletedJob() {
        TaskExecution failedAttemptTaskExecution = createTaskExecution(1L, TaskExecution.Status.FAILED, "try again");
        TaskExecution retriedTaskExecution = createTaskExecution(2L, TaskExecution.Status.COMPLETED, null);

        stubTaskExecutions(retriedTaskExecution, failedAttemptTaskExecution, retriedTaskExecution);

        assertThatCode(() -> JobExecutionErrors.checkForError(createJob(Job.Status.COMPLETED), taskExecutionService))
            .doesNotThrowAnyException();
    }

    @Test
    void testCheckForErrorReportsLastFailedTask() {
        TaskExecution failedTaskExecution = createTaskExecution(1L, TaskExecution.Status.FAILED, "boom");

        stubTaskExecutions(failedTaskExecution, failedTaskExecution);

        assertThatThrownBy(() -> JobExecutionErrors.checkForError(createJob(Job.Status.FAILED), taskExecutionService))
            .isInstanceOf(ExecutionException.class)
            .hasMessage("boom");
    }

    @Test
    void testCheckForErrorFallsBackToJobWhenNoTaskCarriesError() {
        TaskExecution cancelledTaskExecution = createTaskExecution(1L, TaskExecution.Status.CANCELLED, null);

        stubTaskExecutions(cancelledTaskExecution, cancelledTaskExecution);

        assertThatThrownBy(() -> JobExecutionErrors.checkForError(createJob(Job.Status.FAILED), taskExecutionService))
            .isInstanceOfSatisfying(ExecutionException.class, executionException -> {
                assertThat(executionException.getMessage())
                    .isEqualTo("Job " + JOB_ID + " failed but no error details are available.");
                assertThat(executionException.getErrorKey()).isEqualTo(JobErrorType.JOB_FAILED.getErrorKey());
            });
    }

    private void stubTaskExecutions(TaskExecution lastTaskExecution, TaskExecution... jobTaskExecutions) {
        when(taskExecutionService.fetchLastJobTaskExecution(JOB_ID))
            .thenReturn(Optional.of(lastTaskExecution));
        when(taskExecutionService.getJobTaskExecutions(JOB_ID))
            .thenReturn(List.of(jobTaskExecutions));
    }

    private static Job createJob(Job.Status status) {
        Job job = new Job(JOB_ID);

        job.setStatus(status);

        return job;
    }

    private static TaskExecution createTaskExecution(
        long id, TaskExecution.Status status, @Nullable String errorMessage) {

        TaskExecution taskExecution = new TaskExecution();

        taskExecution.setId(id);
        taskExecution.setJobId(JOB_ID);
        taskExecution.setStatus(status);

        if (errorMessage != null) {
            taskExecution.setError(new ExecutionError(errorMessage, List.of()));
        }

        return taskExecution;
    }
}
