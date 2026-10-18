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

import com.bytechef.atlas.configuration.domain.Task;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcher;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcherResolver;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Fails a synchronous webhook run before a step that can pause it runs, so the step never delivers anything, for
 * example an approval request nobody could act on. It must be the first dispatcher in the sync engine's chain.
 *
 * @author Ivica Cardic
 */
public class SyncExecutionSuspendRejectingTaskDispatcher
    implements TaskDispatcher<TaskExecution>, TaskDispatcherResolver {

    private final ApplicationEventPublisher eventPublisher;
    private final JobService jobService;
    private final TaskExecutionService taskExecutionService;

    @SuppressFBWarnings("EI")
    public SyncExecutionSuspendRejectingTaskDispatcher(
        ApplicationEventPublisher eventPublisher, JobService jobService, TaskExecutionService taskExecutionService) {

        this.eventPublisher = eventPublisher;
        this.jobService = jobService;
        this.taskExecutionService = taskExecutionService;
    }

    @Override
    public void dispatch(TaskExecution taskExecution) {
        SyncExecutionSuspendRejectingTaskCompletionHandler.failJob(
            taskExecution, eventPublisher, jobService, taskExecutionService);
    }

    @Override
    public @Nullable TaskDispatcher<? extends Task> resolve(Task task) {
        if (task instanceof TaskExecution taskExecution &&
            SuspendingTasks.isSuspending(taskExecution.getWorkflowTask())) {

            return this;
        }

        return null;
    }
}
