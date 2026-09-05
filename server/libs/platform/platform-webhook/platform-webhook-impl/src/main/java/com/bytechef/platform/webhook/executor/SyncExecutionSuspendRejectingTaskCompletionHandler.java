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

import com.bytechef.atlas.coordinator.event.JobStatusApplicationEvent;
import com.bytechef.atlas.coordinator.task.completion.TaskCompletionHandler;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.error.ExecutionError;
import com.bytechef.platform.component.constant.MetadataConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.Validate;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Fails a synchronous webhook run whose task suspended. The in-process sync engine cannot pause and resume a job, so
 * without this handler a suspended task (an approval, a wait, an AI agent tool awaiting approval) would be completed
 * like any other and the workflow would continue past it. It must be the first handler in the sync engine's completion
 * chain. {@link WebhookWorkflowExecutorImpl#executeSync} rejects workflows with a statically known suspending step
 * before they start; this handler covers the ones only visible at runtime.
 *
 * @author Ivica Cardic
 */
public class SyncExecutionSuspendRejectingTaskCompletionHandler implements TaskCompletionHandler {

    static final String MESSAGE =
        "Workflows that return a synchronous response can't contain approval or wait steps, because those steps " +
            "pause the run. Step '%s' (%s) would pause it. Use a trigger that responds asynchronously, or remove " +
            "the step.";

    private final ApplicationEventPublisher eventPublisher;
    private final JobService jobService;
    private final TaskExecutionService taskExecutionService;

    @SuppressFBWarnings("EI")
    public SyncExecutionSuspendRejectingTaskCompletionHandler(
        ApplicationEventPublisher eventPublisher, JobService jobService, TaskExecutionService taskExecutionService) {

        this.eventPublisher = eventPublisher;
        this.jobService = jobService;
        this.taskExecutionService = taskExecutionService;
    }

    static String getMessage(String taskName, String taskType) {
        return String.format(MESSAGE, taskName, taskType);
    }

    @Override
    public boolean canHandle(TaskExecution taskExecution) {
        return taskExecution.getMetadata()
            .containsKey(MetadataConstants.JOB_RESUME_ID);
    }

    @Override
    public void handle(TaskExecution taskExecution) {
        taskExecution.setHandled(true);

        Instant endDate = Instant.now();

        taskExecution.setEndDate(endDate);
        taskExecution.setError(
            new ExecutionError(getMessage(taskExecution.getName(), taskExecution.getType()), List.of()));
        taskExecution.setStatus(TaskExecution.Status.FAILED);

        taskExecution = taskExecutionService.update(taskExecution);

        while (taskExecution.getParentId() != null) {
            taskExecution = taskExecutionService.getTaskExecution(taskExecution.getParentId());

            taskExecution.setEndDate(endDate);
            taskExecution.setStatus(TaskExecution.Status.FAILED);

            taskExecution = taskExecutionService.update(taskExecution);
        }

        Job job = jobService.getTaskExecutionJob(Validate.notNull(taskExecution.getId(), "id"));

        Validate.notNull(job, "No job found for task execution %s", taskExecution.getId());

        job.setEndDate(endDate);
        job.setStatus(Job.Status.FAILED);

        job = jobService.update(job);

        eventPublisher
            .publishEvent(new JobStatusApplicationEvent(Validate.notNull(job.getId(), "id"), job.getStatus()));
    }
}
