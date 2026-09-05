# Resumable Subflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `subflow/v1` child job can suspend (approval, wait, agent tool) and later resume, and its parent task completes normally — instead of both jobs parking forever.

**Architecture:** Two coupled guards make a subflow child unresumable today. `JobServiceImpl.resumeToStatusStarted` asserts `parentTaskExecutionId == null` (a 2018 line that predates suspend/resume), and `SubflowJobStatusEventListener` stops the parent job on *every* child `STOPPED`, unable to tell a suspend from a user's Stop. The assertion is deleted; the listener learns to distinguish the two using the `JOB_RESUME_ID` marker that `SuspendTaskCompletionHandler` already writes into the child job's metadata immediately before it publishes `STOPPED`. With the child resumable, the agent-tool guard `ERROR_AGENT_IS_SUBFLOW` no longer protects anything and is removed, and the agent bridge's never-cleared `LAUNCHED_SUBFLOW_JOB_ID` key — which parks a second bridged call on the same job — is cleared on resume.

**Tech Stack:** Java 25 / Spring Boot 4, Atlas engine (`atlas-execution`, `atlas-coordinator`), Spring `Assert`, in-memory Atlas repositories + `JobSyncExecutor` for in-process integration tests, Testcontainers PostgreSQL for `JobFacadeIntTest`, Mockito, React 19 + Vitest for the one client warning removed.

**Spec:** [docs/superpowers/specs/2026-09-05-resumable-subflows-design.md](../specs/2026-09-05-resumable-subflows-design.md)

## Deviations from the spec (read before starting)

The spec was written from a first reading; three details are adjusted here after reading the suspend path end to end. Each is a deliberate call.

1. **The suspend discriminator is the child JOB's `JOB_RESUME_ID` metadata, not the task execution's `SUSPEND` metadata.** `MetadataConstants.SUSPEND` is written onto a task execution only by `SuspendTaskDispatcherPreSendProcessor` — on the *resume* re-send — so it is absent at the moment `STOPPED` fires. What is reliably present is `MetadataConstants.JOB_RESUME_ID` on the **job**: `SuspendTaskCompletionHandler.handle` writes `JOB_RESUME_ID` and `TASK_EXECUTION_RESUME_ID` into the job metadata and only then calls `setStatusToStopped` and publishes the `STOPPED` event; `SuspendTaskDispatcherPreSendProcessor.process` removes `JOB_RESUME_ID` on resume; `TaskCoordinator.onStopJobEvent` (a user Stop) writes neither. `SuspendTaskDispatcherPreSendProcessor.canProcess` already uses exactly this test. The listener therefore checks `job.getMetadata(MetadataConstants.JOB_RESUME_ID) != null`.
2. **No in-process end-to-end resume.** `JobSyncExecutor` subscribes every coordinator route except `JOB_RESUME_EVENTS`, so the in-process rig cannot drive `TaskCoordinator.onResumeJobEvent`. The behaviour is proven in three layers instead: the assertion at `JobFacadeIntTest` level against a real database (Task 1); the listener's suspend-vs-stop split at unit level and in the in-process rig, where a real `SubflowTaskDispatcher` launches a real child that really suspends (Task 2); and the parent's completion by dispatching the child's terminal event to the real listener, the same technique `AgentSubflowBridgeIntTest.completeSubflowJobAndDispatchTerminalEvent` already uses. Adding a `JOB_RESUME_EVENTS` subscription to `JobSyncExecutor` would change the editor's Test-button executor and is out of scope.
3. **`LAUNCHED_SUBFLOW_JOB_ID` is cleared *before* `resumeJob`, not after.** `jobFacade.resumeJob` publishes a `ResumeJobEvent` that the coordinator handles asynchronously, and `resumeToStatusStarted` saves the job (bumping `@Version`). A metadata update after the publish would race that save and lose with `OptimisticLockingFailureException`. Clearing first is safe: if the publish then fails, a redelivered child-terminal event finds the agent job still `STOPPED`, clears an already-clear key, and publishes the resume again.

## Global Constraints

- **Java blank-line rules:** exactly one blank line before `if`/`for`/`while`/`try`/`switch`, except immediately after an opening `{`; one blank line between a variable modification and the statement that uses it; no blank line between the last member and a class's closing `}`.
- **No new inline comments in production or test code.** Rationale goes in the commit message body. Pre-existing comments in touched files stay unless the code they describe is deleted.
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule fails the build.
- **No `_`-prefixed methods. No short or cryptic names**, including lambda parameters (`taskExecution`, not `te`).
- **Test naming:** unit tests end in `Test`; only tests that boot a Spring context end in `IntTest`. Method names are camelCase without underscores, including private helpers.
- **Commit by path only.** This is a shared checkout with unrelated work in progress; every commit names its files explicitly (`git commit <paths> -m ...`, after `git add <paths>` for new files). Never `git add -A`, never `git stash`, never `--amend`.
- **Commit message shape:** imperative subject, no ticket prefix (this branch omits them); body carries the rationale; last line `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Client-only commits prefix the subject with `client - `.
- **Gradle verification:** redirect to a file and check `$?` on its own line; never judge a run piped into `tail`/`grep`. Use `--continue`, then `grep '^> Task .* FAILED' <file>`.
- **Scratch files** go under `/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad/`.
- `.claude/worktrees/*` contain stale copies of every touched file. They are not built and must not be edited.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `server/libs/atlas/atlas-execution/atlas-execution-service/src/main/java/com/bytechef/atlas/execution/service/JobServiceImpl.java` | Job state transitions | Delete the `parentTaskExecutionId == null` assertion in `resumeToStatusStarted` |
| `server/libs/atlas/atlas-execution/atlas-execution-service/src/test/java/com/bytechef/atlas/execution/facade/JobFacadeIntTest.java` | Real-DB tests of job state transitions | Add the subflow-child resume test |
| `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListener.java` | Propagate a child job's status to its parent task | Split `STOPPED, CANCELLED`; suspend leaves the parent alone |
| `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListenerTest.java` | Unit tests for the listener | **New** |
| `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowSuspendIntTest.java` | In-process rig: real dispatcher, real child suspend, real listener | **New**, replaces the disabled `SubflowTaskDispatcherIntTest` stub |
| `server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-parent-of-suspending-child.yaml` | Parent fixture calling `subflow/v1` | **New** |
| `server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-suspending-child.yaml` | Child fixture with a `suspend/v1` step and an output | **New** |
| `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListener.java` | Resume the agent when its bridged sub-workflow terminates | Clear `LAUNCHED_SUBFLOW_JOB_ID` before resuming |
| `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListenerTest.java` | Unit tests for the resume listener | Add the clear-key test |
| `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowBridgeIntTest.java` | In-process bridge tests | Add the two-sequential-calls scenario |
| `server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/SubflowToolSupport.java` | Shared guards for the two agent subflow tools | Drop the third guard and `ERROR_AGENT_IS_SUBFLOW` |
| `server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowTool.java`, `WorkflowCallAiAgentTool.java` | The two tools | Drop their `ERROR_AGENT_IS_SUBFLOW` mirrors |
| `server/libs/modules/components/workflow/src/test/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowToolTest.java`, `WorkflowCallAiAgentToolTest.java` | Tool tests | Delete `testToolReturnsErrorWhenAgentIsItselfASubflow` in each |
| `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ActionContextAware.java` | Context contract | Javadoc no longer claims a subflow cannot resume |
| `.agents/agents.md` | Agent feature deep-dive | Remove the "1-level nesting" passages |
| `client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.tsx`, `AgentSubAgentsCard.test.tsx` | Sub-agents card | Remove the nesting warning and its two tests |

---

### Task 1: Let `resumeToStatusStarted` resume a subflow child

**Files:**
- Modify: `server/libs/atlas/atlas-execution/atlas-execution-service/src/main/java/com/bytechef/atlas/execution/service/JobServiceImpl.java:159-163`
- Test: `server/libs/atlas/atlas-execution/atlas-execution-service/src/test/java/com/bytechef/atlas/execution/facade/JobFacadeIntTest.java`

**Interfaces:**
- Consumes: `Job.setParentTaskExecutionId(Long)`, `JobService.resumeToStatusStarted(long)`, the existing `newJob()` helper and `@Autowired JobRepository jobRepository` / `JobService jobService` fields in `JobFacadeIntTest`.
- Produces: `resumeToStatusStarted` transitions a `STOPPED` job with a non-null `parentTaskExecutionId` to `STARTED`. Tasks 2 and 4 rely on this.

- [ ] **Step 1: Write the failing test**

Add after `testResumeToStatusStartedIsIdempotentForAlreadyClaimedJob()` in `JobFacadeIntTest.java`:

```java
    @Test
    public void testResumeToStatusStartedResumesSuspendedSubflowChild() {
        Job job = newJob();

        job.setParentTaskExecutionId(42L);
        job.setStatus(Job.Status.STOPPED);

        long jobId = Validate.notNull(jobRepository.save(job)
            .getId(), "id");

        assertThat(jobService.resumeToStatusStarted(jobId)
            .getStatus()).isEqualTo(Job.Status.STARTED);
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:atlas:atlas-execution:atlas-execution-service:testIntegration --tests '*JobFacadeIntTest*' > $S/task1-red.log 2>&1
echo "EXIT=$?"
grep -E "ResumesSuspendedSubflowChild|Can't resume a subflow|tests completed" $S/task1-red.log
```

Expected: `EXIT=1`; the log names `testResumeToStatusStartedResumesSuspendedSubflowChild() FAILED` with `IllegalArgumentException: Can't resume a subflow`. Every other test in the class passes. (Docker must be running — Testcontainers.)

- [ ] **Step 3: Delete the assertion**

In `JobServiceImpl.java`, `resumeToStatusStarted`, delete these two lines (the blank line after the `Job job = ...` declaration stays, so the method opens with the declaration followed by one blank line and then the existing comment block):

```java
        Assert.isTrue(job.getParentTaskExecutionId() == null, "Can't resume a subflow");

```

The method's other `Assert.isTrue(isRestartable(job), ...)` stays, so the `org.springframework.util.Assert` import is still used.

- [ ] **Step 4: Run the test to verify it passes**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:atlas:atlas-execution:atlas-execution-service:spotlessApply :server:libs:atlas:atlas-execution:atlas-execution-service:testIntegration --tests '*JobFacadeIntTest*' > $S/task1-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/task1-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`, no `FAILED` task line.

- [ ] **Step 5: Commit**

```bash
git commit \
  server/libs/atlas/atlas-execution/atlas-execution-service/src/main/java/com/bytechef/atlas/execution/service/JobServiceImpl.java \
  server/libs/atlas/atlas-execution/atlas-execution-service/src/test/java/com/bytechef/atlas/execution/facade/JobFacadeIntTest.java \
  -m "Allow a subflow child job to be resumed" \
  -m "resumeToStatusStarted asserted parentTaskExecutionId == null since the original Atlas import
(25bcdeff88d), when resume meant a human restarting a stopped job and suspend/resume did not
exist. Any suspending step inside a subflow/v1 child - approval gate, wait, agent tool - made
the child STOPPED and then unresumable, so both jobs parked forever. No test pinned the line.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Teach `SubflowJobStatusEventListener` to tell a suspend from a stop

**Files:**
- Modify: `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListener.java:88-98`
- Create: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListenerTest.java`
- Create: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowSuspendIntTest.java`
- Delete: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcherIntTest.java` (a `@Disabled` stub containing only `// TODO`)
- Create: `server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-parent-of-suspending-child.yaml`
- Create: `server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-suspending-child.yaml`

**Interfaces:**
- Consumes: `Job.getMetadata(String)` (returns `Object`, null when absent), `MetadataConstants.JOB_RESUME_ID` (`"jobResumeId"`), `SubflowTaskDispatcher(ChildJobPrincipalFactory, JobService, SubflowResolver)`, `SubflowResolver.Subflow(String workflowId, String inputsName)`, `JobSyncExecutor`'s 16-arg constructor and `startJob`/`awaitJob`, `TaskFileStorage.storeJobOutputs(long, Map)`.
- Produces: a `STOPPED` child whose job metadata carries `JOB_RESUME_ID` publishes nothing; a `STOPPED` child without it, and any `CANCELLED` child, publishes `StopJobEvent(parentJobId)` exactly as before.

- [ ] **Step 1: Write the failing unit tests**

Create `SubflowJobStatusEventListenerTest.java`:

```java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.constant.WorkflowConstants;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.coordinator.event.JobStatusApplicationEvent;
import com.bytechef.atlas.coordinator.event.StopJobEvent;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.constant.MetadataConstants;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class SubflowJobStatusEventListenerTest {

    private static final long CHILD_JOB_ID = 200L;
    private static final long PARENT_JOB_ID = 100L;
    private static final long PARENT_TASK_EXECUTION_ID = 10L;

    @Mock
    private Evaluator evaluator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private JobService jobService;

    @Mock
    private TaskExecutionService taskExecutionService;

    @Mock
    private TaskFileStorage taskFileStorage;

    @Test
    void testSuspendedChildLeavesParentRunning() {
        Job childJob = newChildJob(Job.Status.STOPPED);

        childJob.setMetadata(Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1"));

        when(jobService.getJob(CHILD_JOB_ID)).thenReturn(childJob);

        newListener().onApplicationEvent(new JobStatusApplicationEvent(CHILD_JOB_ID, Job.Status.STOPPED));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void testStoppedChildStopsParent() {
        Job childJob = newChildJob(Job.Status.STOPPED);

        when(jobService.getJob(CHILD_JOB_ID)).thenReturn(childJob);
        when(taskExecutionService.getTaskExecution(PARENT_TASK_EXECUTION_ID)).thenReturn(newParentTaskExecution());

        newListener().onApplicationEvent(new JobStatusApplicationEvent(CHILD_JOB_ID, Job.Status.STOPPED));

        assertEquals(PARENT_JOB_ID, capturePublishedStopJobEvent().getJobId());
    }

    @Test
    void testCancelledChildStopsParentEvenWhenSuspendMarkerPresent() {
        Job childJob = newChildJob(Job.Status.CANCELLED);

        childJob.setMetadata(Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1"));

        when(jobService.getJob(CHILD_JOB_ID)).thenReturn(childJob);
        when(taskExecutionService.getTaskExecution(PARENT_TASK_EXECUTION_ID)).thenReturn(newParentTaskExecution());

        newListener().onApplicationEvent(new JobStatusApplicationEvent(CHILD_JOB_ID, Job.Status.CANCELLED));

        assertEquals(PARENT_JOB_ID, capturePublishedStopJobEvent().getJobId());
    }

    private StopJobEvent capturePublishedStopJobEvent() {
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        verify(eventPublisher).publishEvent(eventCaptor.capture());

        return assertInstanceOf(StopJobEvent.class, eventCaptor.getValue());
    }

    private SubflowJobStatusEventListener newListener() {
        return new SubflowJobStatusEventListener(
            evaluator, eventPublisher, jobService, taskExecutionService, taskFileStorage);
    }

    private static Job newChildJob(Job.Status status) {
        Job job = new Job();

        job.setId(CHILD_JOB_ID);
        job.setParentTaskExecutionId(PARENT_TASK_EXECUTION_ID);
        job.setStatus(status);

        return job;
    }

    private static TaskExecution newParentTaskExecution() {
        return TaskExecution.builder()
            .jobId(PARENT_JOB_ID)
            .workflowTask(
                new WorkflowTask(
                    Map.of(WorkflowConstants.NAME, "callChild", WorkflowConstants.TYPE, "subflow/v1")))
            .build();
    }
}
```

- [ ] **Step 2: Write the two fixtures**

`subflow-suspending-child.yaml`:

```yaml
---
label: "Suspending child"
outputs:
- name: "result"
  value: "${afterResume}"
tasks:
- name: "beforeSuspend"
  type: "var/v1/set"
  parameters:
    value: "reached the suspending step"
- name: "requestApproval"
  type: "suspend/v1"
- name: "afterResume"
  type: "var/v1/set"
  parameters:
    value: "the human approved"
```

`subflow-parent-of-suspending-child.yaml`:

```yaml
---
label: "Parent of a suspending child"
outputs:
- name: "childResult"
  value: "${callChild.result}"
tasks:
- name: "callChild"
  type: "subflow/v1"
  parameters:
    workflowUuid: "suspending-child-uuid"
- name: "afterChild"
  type: "var/v1/set"
  parameters:
    value: "${callChild.result}"
```

- [ ] **Step 3: Write the failing in-process integration test**

Delete `SubflowTaskDispatcherIntTest.java`. Create `SubflowSuspendIntTest.java` in the same package:

```java
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

package com.bytechef.task.dispatcher.subflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.event.JobStatusApplicationEvent;
import com.bytechef.atlas.coordinator.event.listener.ApplicationEventListener;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcherResolverFactory;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.repository.memory.InMemoryContextRepository;
import com.bytechef.atlas.execution.repository.memory.InMemoryJobRepository;
import com.bytechef.atlas.execution.repository.memory.InMemoryTaskExecutionRepository;
import com.bytechef.atlas.execution.service.ContextService;
import com.bytechef.atlas.execution.service.ContextServiceImpl;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.JobServiceImpl;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.execution.service.TaskExecutionServiceImpl;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.atlas.worker.task.handler.TaskHandler;
import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.message.broker.memory.AsyncMessageBroker;
import com.bytechef.message.event.MessageEvent;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor;
import com.bytechef.platform.workflow.execution.JobResumeId;
import com.bytechef.platform.workflow.execution.service.TaskStateService;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver;
import com.bytechef.platform.workflow.task.dispatcher.test.annotation.TaskDispatcherIntTest;
import com.bytechef.task.dispatcher.subflow.event.listener.SubflowJobStatusEventListener;
import com.bytechef.task.dispatcher.suspend.SuspendTaskDispatcherPreSendProcessor;
import com.bytechef.task.dispatcher.suspend.completion.SuspendTaskCompletionHandler;
import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.constant.TenantConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * @author Ivica Cardic
 */
@TaskDispatcherIntTest
class SubflowSuspendIntTest {

    private static final String PARENT_WORKFLOW = "subflow-parent-of-suspending-child";
    private static final String CHILD_WORKFLOW = "subflow-suspending-child";
    private static final Duration CHILD_LAUNCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PARENT_MUST_STAY_STARTED_FOR = Duration.ofSeconds(2);

    @Autowired
    private Environment environment;

    @Autowired
    private TaskExecutor taskExecutor;

    @Autowired
    private TaskFileStorage taskFileStorage;

    @Autowired
    private WorkflowService workflowService;

    @Test
    void testSuspendingChildLeavesParentRunningAndParentCompletesAfterChild() throws InterruptedException {
        Rig rig = new Rig();

        long parentJobId = rig.startParent();
        long childJobId = rig.awaitChildLaunched();

        Job childJob = rig.awaitJob(childJobId);

        assertEquals(Job.Status.STOPPED, childJob.getStatus());
        assertNotNull(childJob.getMetadata(MetadataConstants.JOB_RESUME_ID));

        rig.assertParentStaysStarted(parentJobId);

        rig.completeChild(childJobId, Map.of("result", "the human approved"));

        Job parentJob = rig.awaitJob(parentJobId);

        assertEquals(Job.Status.COMPLETED, parentJob.getStatus());
        assertEquals(
            "the human approved",
            taskFileStorage.readJobOutputs(parentJob.getOutputs())
                .get("childResult"));
    }

    private final class Rig {

        private final InMemoryTaskExecutionRepository taskExecutionRepository = new InMemoryTaskExecutionRepository();
        private final JobService jobService = new JobServiceImpl(
            new InMemoryJobRepository(taskExecutionRepository, new JsonMapper()));
        private final TaskExecutionService taskExecutionService = new TaskExecutionServiceImpl(
            taskExecutionRepository);
        private final AtomicReference<JobSyncExecutor> jobSyncExecutorReference = new AtomicReference<>();
        private final AtomicReference<Long> childJobIdReference = new AtomicReference<>();
        private final SubflowJobStatusEventListener subflowJobStatusEventListener;
        private final JobSyncExecutor jobSyncExecutor;

        private Rig() {
            ContextService contextService = new ContextServiceImpl(new InMemoryContextRepository());
            AsyncMessageBroker messageBroker = new AsyncMessageBroker(environment);
            ApplicationEventPublisher eventPublisher = createEventPublisher(messageBroker);
            TaskStateService taskStateService = new InMemoryTaskStateService();

            subflowJobStatusEventListener = new SubflowJobStatusEventListener(
                SpelEvaluator.create(), eventPublisher, jobService, taskExecutionService, taskFileStorage);

            ChildJobPrincipalFactory childJobPrincipalFactory = new StartingChildJobPrincipalFactory(
                jobSyncExecutorReference, childJobIdReference);
            SubflowResolver subflowResolver = (workflowUuid, triggerName, editorEnvironment) -> new SubflowResolver.Subflow(
                encodeWorkflowId(CHILD_WORKFLOW), "newWorkflowCall");

            TaskDispatcherResolverFactory subflowTaskDispatcherResolverFactory =
                taskDispatcher -> new SubflowTaskDispatcher(childJobPrincipalFactory, jobService, subflowResolver);

            Map<String, TaskHandler<?>> taskHandlerMap = Map.of(
                "var/v1/set", taskExecution -> taskExecution.getParameters()
                    .get("value"),
                "suspend/v1", taskExecution -> new ActionContext.Suspend(Map.of(), null));

            jobSyncExecutor = new JobSyncExecutor(
                contextService, SpelEvaluator.create(), jobService, -1, messageBroker,
                List.<ApplicationEventListener>of(subflowJobStatusEventListener),
                List.of((taskCompletionHandler, taskDispatcher) -> new SuspendTaskCompletionHandler(
                    contextService, eventPublisher, jobService, taskExecutionService, taskFileStorage,
                    taskStateService)),
                List.of(),
                List.of(new SuspendTaskDispatcherPreSendProcessor(jobService, taskStateService)),
                List.of(subflowTaskDispatcherResolverFactory),
                taskExecutionService, taskExecutor, taskHandlerMap::get, taskFileStorage, -1, workflowService);

            jobSyncExecutorReference.set(jobSyncExecutor);
        }

        long startParent() {
            return jobSyncExecutor.startJob(new JobParametersDTO(encodeWorkflowId(PARENT_WORKFLOW)));
        }

        long awaitChildLaunched() throws InterruptedException {
            Instant deadline = Instant.now()
                .plus(CHILD_LAUNCH_TIMEOUT);

            while (childJobIdReference.get() == null) {
                if (Instant.now()
                    .isAfter(deadline)) {

                    throw new AssertionError("The subflow dispatcher never launched the child job");
                }

                Thread.sleep(10);
            }

            return childJobIdReference.get();
        }

        Job awaitJob(long jobId) {
            return jobSyncExecutor.awaitJob(jobId, false);
        }

        void assertParentStaysStarted(long parentJobId) throws InterruptedException {
            Instant deadline = Instant.now()
                .plus(PARENT_MUST_STAY_STARTED_FOR);

            while (Instant.now()
                .isBefore(deadline)) {

                Job parentJob = jobService.getJob(parentJobId);

                assertEquals(
                    Job.Status.STARTED, parentJob.getStatus(),
                    "The parent must keep running while its child is suspended");

                Thread.sleep(50);
            }
        }

        void completeChild(long childJobId, Map<String, ?> outputs) {
            Job childJob = jobService.getJob(childJobId);

            childJob.setOutputs(taskFileStorage.storeJobOutputs(childJobId, outputs));
            childJob.setStatus(Job.Status.COMPLETED);

            jobService.update(childJob);

            subflowJobStatusEventListener.onApplicationEvent(
                new JobStatusApplicationEvent(childJobId, Job.Status.COMPLETED));
        }
    }

    private static ApplicationEventPublisher createEventPublisher(AsyncMessageBroker messageBroker) {
        return event -> {
            MessageEvent<?> messageEvent = (MessageEvent<?>) event;

            messageEvent.putMetadata(TenantConstants.CURRENT_TENANT_ID, TenantContext.getCurrentTenantId());

            messageBroker.send(messageEvent.getRoute(), messageEvent);
        };
    }

    private static String encodeWorkflowId(String workflowName) {
        return EncodingUtils.base64EncodeToString(workflowName.getBytes(StandardCharsets.UTF_8));
    }

    private static final class StartingChildJobPrincipalFactory implements ChildJobPrincipalFactory {

        private final AtomicReference<JobSyncExecutor> jobSyncExecutorReference;
        private final AtomicReference<Long> childJobIdReference;

        private StartingChildJobPrincipalFactory(
            AtomicReference<JobSyncExecutor> jobSyncExecutorReference, AtomicReference<Long> childJobIdReference) {

            this.jobSyncExecutorReference = jobSyncExecutorReference;
            this.childJobIdReference = childJobIdReference;
        }

        @Override
        public long createChildJob(long parentJobId, JobParametersDTO jobParametersDTO) {
            long childJobId = Objects.requireNonNull(jobSyncExecutorReference.get())
                .startJob(jobParametersDTO);

            childJobIdReference.set(childJobId);

            return childJobId;
        }

        @Override
        public long createPrincipalLinkedJob(long referenceJobId, JobParametersDTO jobParametersDTO) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryTaskStateService implements TaskStateService {

        private final Map<JobResumeId, Object> store = new HashMap<>();

        @Override
        public void delete(JobResumeId jobResumeId) {
            store.remove(jobResumeId);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> fetchValue(JobResumeId jobResumeId) {
            return Optional.ofNullable((T) store.get(jobResumeId));
        }

        @Override
        public void save(JobResumeId jobResumeId, Object value) {
            store.put(jobResumeId, value);
        }
    }
}
```

What is real here and what is not: the parent runs through the real coordinator, the real `SubflowTaskDispatcher` creates the child with a real `parentTaskExecutionId`, the child really suspends through `SuspendTaskCompletionHandler`, and the real `SubflowJobStatusEventListener` receives the child's `STOPPED`. The child's later completion is simulated by writing its outputs and status and dispatching its `COMPLETED` event to the listener directly — the same technique `AgentSubflowBridgeIntTest.completeSubflowJobAndDispatchTerminalEvent` uses — because the rig has no resume route (see Deviations, item 2). The parent's completion from that point on is real.

- [ ] **Step 4: Run both to verify they fail**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:test --tests '*SubflowJobStatusEventListenerTest*' --tests '*SubflowSuspendIntTest*' > $S/task2-red.log 2>&1
echo "EXIT=$?"
grep -E "FAILED|PASSED|tests completed" $S/task2-red.log
```

Expected: `EXIT=1`. `testSuspendedChildLeavesParentRunning` FAILED (a `StopJobEvent` was published). `testStoppedChildStopsParent` and `testCancelledChildStopsParentEvenWhenSuspendMarkerPresent` PASS — they describe today's behaviour and must keep passing. `testSuspendingChildLeavesParentRunningAndParentCompletesAfterChild` FAILED at `assertParentStaysStarted` with the parent in `STOPPED`.

If the integration test fails earlier than `assertParentStaysStarted` — at `awaitChildLaunched`, or with the child not `STOPPED` — the rig is mis-wired, not the code under test. Fix the rig before continuing; do not proceed to Step 5 with a red that is not the intended red.

- [ ] **Step 5: Split the listener's `STOPPED, CANCELLED` case**

In `SubflowJobStatusEventListener.java`, replace:

```java
                case STOPPED, CANCELLED -> {
                    TaskExecution subflowTaskExecution = taskExecutionService.getTaskExecution(
                        job.getParentTaskExecutionId());

                    eventPublisher.publishEvent(
                        new StopJobEvent(Objects.requireNonNull(subflowTaskExecution.getJobId())));

                }
```

with:

```java
                case CANCELLED -> stopParentJob(job);
                case STOPPED -> {
                    if (job.getMetadata(MetadataConstants.JOB_RESUME_ID) == null) {
                        stopParentJob(job);
                    }
                }
```

and add this method after `onApplicationEvent` (before `getCallableResponseOutput`):

```java
    private void stopParentJob(Job job) {
        TaskExecution subflowTaskExecution = taskExecutionService.getTaskExecution(job.getParentTaskExecutionId());

        eventPublisher.publishEvent(new StopJobEvent(Objects.requireNonNull(subflowTaskExecution.getJobId())));
    }
```

`MetadataConstants`, `TaskExecution`, `StopJobEvent` and `Objects` are already imported.

- [ ] **Step 6: Run the module's tests to verify everything passes**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:spotlessApply :server:libs:modules:task-dispatchers:subflow:check --continue > $S/task2-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/task2-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`. This runs the whole module including `AgentSubflowBridgeIntTest` (all `#5055` scenarios must still pass), checkstyle, PMD and SpotBugs.

- [ ] **Step 7: Commit**

```bash
git add \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListenerTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowSuspendIntTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-parent-of-suspending-child.yaml \
  server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-suspending-child.yaml
git rm -q server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcherIntTest.java
git commit \
  server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListener.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/SubflowJobStatusEventListenerTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowSuspendIntTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcherIntTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-parent-of-suspending-child.yaml \
  server/libs/modules/task-dispatchers/subflow/src/test/resources/workflows/subflow-suspending-child.yaml \
  -m "Keep the parent running when a subflow child suspends" \
  -m "SubflowJobStatusEventListener treated every STOPPED child as a stop and published
StopJobEvent for the parent, which then cancelled its subflow task. A suspend is also a
STOPPED transition, so a child that paused for an approval or an agent tool took its parent
down with it and neither could continue.

The child job's metadata already says which it is: SuspendTaskCompletionHandler writes
JOB_RESUME_ID (and TASK_EXECUTION_RESUME_ID) into the job right before publishing STOPPED,
and SuspendTaskDispatcherPreSendProcessor removes it on resume; a user Stop never writes it.
STOPPED with the marker now leaves the parent alone; STOPPED without it, and CANCELLED,
propagate as before.

SubflowSuspendIntTest replaces the long-disabled SubflowTaskDispatcherIntTest stub with a
real in-process run: the real dispatcher launches a real child that really suspends, and
the parent stays STARTED and later completes with the child's output.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Clear `LAUNCHED_SUBFLOW_JOB_ID` when the bridge resumes the agent

**Files:**
- Modify: `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListener.java:98-104`
- Test: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListenerTest.java`
- Test: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowBridgeIntTest.java`

**Interfaces:**
- Consumes: `SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID`, `JobService.update(Job)`, the existing `persistStoppedJob`, `encodeWorkflowId` and `RecordingChildJobPrincipalFactory` helpers in `AgentSubflowBridgeIntTest`.
- Produces: after the resume listener resumes an agent job, that job's metadata no longer contains `LAUNCHED_SUBFLOW_JOB_ID`, so a later suspend on the same job launches again.

- [ ] **Step 1: Write the failing unit test**

Add to `AgentSubflowResumeListenerTest.java` after `testResumesAgentWithSubflowOutputOnCompleted()`:

```java
    @Test
    void testClearsLaunchedSubflowJobIdBeforeResuming() {
        long subflowJobId = 200L;
        long agentJobId = 100L;

        Job subflowJob = new Job();

        subflowJob.setId(subflowJobId);
        subflowJob.setMetadata(Map.of(SubflowRequestConstants.AGENT_JOB_ID, agentJobId));

        Job agentJob = new Job();

        agentJob.setId(agentJobId);
        agentJob.setStatus(Job.Status.STOPPED);
        agentJob.setMetadata(
            Map.of(
                MetadataConstants.TASK_EXECUTION_RESUME_ID, 1L,
                SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID, subflowJobId));

        when(jobService.getJob(subflowJobId)).thenReturn(subflowJob);
        when(jobService.getJob(agentJobId)).thenReturn(agentJob);
        when(taskExecutionService.fetchLastJobTaskExecution(subflowJobId)).thenReturn(Optional.empty());

        AgentSubflowResumeListener listener = new AgentSubflowResumeListener(
            jobFacade, jobService, taskExecutionService, taskFileStorage);

        listener.onApplicationEvent(new JobStatusApplicationEvent(subflowJobId, Job.Status.COMPLETED));

        ArgumentCaptor<Job> updatedJobCaptor = ArgumentCaptor.forClass(Job.class);

        verify(jobService).update(updatedJobCaptor.capture());
        verify(jobFacade).resumeJob(eq(agentJobId), eq(1L), any());

        Job updatedAgentJob = updatedJobCaptor.getValue();

        assertEquals(agentJobId, updatedAgentJob.getId());
        assertFalse(
            updatedAgentJob.getMetadata()
                .containsKey(SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID));
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertFalse;` to the static imports (the file currently imports only `assertEquals` from `Assertions`).

Check how `testResumesAgentWithSubflowOutputOnCompleted` stubs `jobService.getJob(subflowJobId)` and `taskExecutionService.fetchLastJobTaskExecution` and match it exactly — under `MockitoExtension` strict stubs, an unused stub fails the test. If that test does not stub `getJob(subflowJobId)`, the listener reads it through another path; copy whichever stubbing it uses.

- [ ] **Step 2: Write the failing bridge scenario**

Add to `AgentSubflowBridgeIntTest.java` after `testLauncherCreatesSubflowJobWhenAgentStops()`:

```java
    @Test
    void testAgentCanBridgeTwoSequentialSubflowCalls() {
        InMemoryTaskExecutionRepository taskExecutionRepository = new InMemoryTaskExecutionRepository();
        JobService jobService = new JobServiceImpl(
            new InMemoryJobRepository(taskExecutionRepository, new JsonMapper()));
        TaskExecutionService taskExecutionService = new TaskExecutionServiceImpl(taskExecutionRepository);

        long agentJobId = persistStoppedJob(jobService, "agent-job");

        AtomicReference<JobParametersDTO> launchedJobParameters = new AtomicReference<>();

        ChildJobPrincipalFactory childJobPrincipalFactory = new RecordingChildJobPrincipalFactory(
            jobService, launchedJobParameters);

        AgentSubflowLauncher launcher = new AgentSubflowLauncher(
            childJobPrincipalFactory, jobService, taskExecutionService);
        AgentSubflowResumeListener resumeListener = new AgentSubflowResumeListener(
            new NoOpJobFacade(), jobService, taskExecutionService, taskFileStorage);

        suspendAgentForSubflow(taskExecutionService, agentJobId, encodeWorkflowId(FAST_SUBFLOW), "first");

        launcher.onApplicationEvent(new JobStatusApplicationEvent(agentJobId, Job.Status.STOPPED));

        assertNotNull(launchedJobParameters.get(), "The first bridged call must launch");

        long firstSubflowJobId = ((Number) jobService.getJob(agentJobId)
            .getMetadata()
            .get(SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID)).longValue();

        Job firstSubflowJob = jobService.getJob(firstSubflowJobId);

        firstSubflowJob.setStatus(Job.Status.COMPLETED);

        jobService.update(firstSubflowJob);

        resumeListener.onApplicationEvent(new JobStatusApplicationEvent(firstSubflowJobId, Job.Status.COMPLETED));

        assertFalse(
            jobService.getJob(agentJobId)
                .getMetadata()
                .containsKey(SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID),
            "Resuming the agent must clear the launched-sub-workflow marker");

        launchedJobParameters.set(null);

        suspendAgentForSubflow(taskExecutionService, agentJobId, encodeWorkflowId(FAST_SUBFLOW), "second");

        launcher.onApplicationEvent(new JobStatusApplicationEvent(agentJobId, Job.Status.STOPPED));

        assertNotNull(launchedJobParameters.get(), "The second bridged call on the same agent job must launch");
    }

    private static void suspendAgentForSubflow(
        TaskExecutionService taskExecutionService, long agentJobId, String subflowWorkflowId, String callName) {

        PendingSubflowRequest request = new PendingSubflowRequest(
            subflowWorkflowId, "newWorkflowCall", Map.of("call", callName), false, PlatformType.AUTOMATION);

        TaskExecution agentTaskExecution = TaskExecution.builder()
            .jobId(agentJobId)
            .workflowTask(
                new WorkflowTask(
                    Map.of(WorkflowConstants.NAME, "callWorkflow_" + callName, WorkflowConstants.TYPE,
                        "aiAgent/v1/chat")))
            .build();

        agentTaskExecution.putMetadata(
            MetadataConstants.SUSPEND,
            new Suspend(Map.of(SubflowRequestConstants.PENDING_SUBFLOW, request), null));

        taskExecutionService.create(agentTaskExecution);
    }

    private static final class NoOpJobFacade implements JobFacade {

        @Override
        public long createJob(JobParametersDTO jobParametersDTO) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteJob(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        @Deprecated
        public void resumeApproval(long jobId, String uuid, boolean approved) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resumeJob(long id) {
        }

        @Override
        public void resumeJob(long id, long taskExecutionId, Map<String, ?> data) {
        }

        @Override
        public void stopJob(long id) {
            throw new UnsupportedOperationException();
        }
    }
```

The two empty `resumeJob` bodies in `NoOpJobFacade` are genuinely empty method bodies, which Checkstyle's `EmptyBlock` rule allows (it rejects blocks containing only a comment). If Checkstyle objects anyway, give each a single `return;` statement.

The `RecordingChildJobPrincipalFactory` persists the launched sub-workflow job through `jobService.update`, so `jobService.getJob(launchedId)` in the test resolves it. `persistStoppedJob` leaves the agent job `STOPPED`, which is what the resume listener requires; the `NoOpJobFacade` does not change it, so it is still `STOPPED` for the second call without further setup.

- [ ] **Step 3: Run both to verify they fail**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:test --tests '*AgentSubflowResumeListenerTest*' --tests '*AgentSubflowBridgeIntTest*' > $S/task3-red.log 2>&1
echo "EXIT=$?"
grep -E "FAILED|tests completed" $S/task3-red.log
```

Expected: `EXIT=1`. `testClearsLaunchedSubflowJobIdBeforeResuming` FAILED (`jobService.update` never called). `testAgentCanBridgeTwoSequentialSubflowCalls` FAILED at "Resuming the agent must clear the launched-sub-workflow marker". Every other test in both classes passes.

- [ ] **Step 4: Clear the key before resuming**

In `AgentSubflowResumeListener.onApplicationEvent`, replace:

```java
        Map<String, ?> resumeData = status == Job.Status.COMPLETED
            ? buildCompletedResumeData(subflowJob)
            : buildFailedResumeData(subflowJob);

        jobFacade.resumeJob(
            agentJobId, MapUtils.getLong(agentJob.getMetadata(), MetadataConstants.TASK_EXECUTION_RESUME_ID),
            resumeData);
```

with:

```java
        Map<String, ?> resumeData = status == Job.Status.COMPLETED
            ? buildCompletedResumeData(subflowJob)
            : buildFailedResumeData(subflowJob);

        Map<String, Object> agentJobMetadata = new HashMap<>(agentJob.getMetadata());

        agentJobMetadata.remove(SubflowRequestConstants.LAUNCHED_SUBFLOW_JOB_ID);

        agentJob.setMetadata(agentJobMetadata);

        jobService.update(agentJob);

        jobFacade.resumeJob(
            agentJobId, MapUtils.getLong(agentJobMetadata, MetadataConstants.TASK_EXECUTION_RESUME_ID), resumeData);
```

Add `import java.util.HashMap;` to the imports (alphabetically between `java.util.Map` neighbours — the file already imports `java.util.Map`, `java.util.Objects`, `java.util.Optional`).

- [ ] **Step 5: Run the module's tests to verify everything passes**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:spotlessApply :server:libs:modules:task-dispatchers:subflow:check --continue > $S/task3-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/task3-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git commit \
  server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListener.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowResumeListenerTest.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/event/listener/AgentSubflowBridgeIntTest.java \
  -m "Let an agent bridge more than one sub-workflow call per job" \
  -m "AgentSubflowLauncher records LAUNCHED_SUBFLOW_JOB_ID on the agent job as a broker-redelivery
guard and nothing ever removed it. resumeToStatusStarted reuses the same job id, so the second
time the agent suspended for a sub-workflow the launcher saw the stale key, returned early, and
the agent sat in STOPPED for good. The only test on the key covered redelivery of one event.

The resume listener now removes the key before publishing the resume. Before, not after: the
coordinator's resumeToStatusStarted saves the job asynchronously and a later metadata write
would race it on @Version. If the publish fails after the clear, a redelivered terminal event
finds the agent still STOPPED, clears nothing, and publishes again.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Remove the `ERROR_AGENT_IS_SUBFLOW` guard

**Files:**
- Modify: `server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/SubflowToolSupport.java`
- Modify: `server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowTool.java:60-70`
- Modify: `server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/WorkflowCallAiAgentTool.java:63-70`
- Modify: `server/libs/modules/components/workflow/src/test/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowToolTest.java:157-192`
- Modify: `server/libs/modules/components/workflow/src/test/java/com/bytechef/component/workflow/cluster/WorkflowCallAiAgentToolTest.java:211-242`
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ActionContextAware.java:93-102`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SubflowToolSupport.requireGuardsPassed` runs two guards (agent context present; no suspend already pending this turn). `ERROR_AGENT_IS_SUBFLOW` no longer exists on any of the three classes.

- [ ] **Step 1: Delete the two tests**

In `WorkflowCallWorkflowToolTest.java`, delete the javadoc block that begins `/** C1 regression test: the spec calls out the agent-is-itself-a-sub-workflow case` together with the whole `testToolReturnsErrorWhenAgentIsItselfASubflow()` method that follows it (through its closing `}`).

In `WorkflowCallAiAgentToolTest.java`, delete the javadoc block that begins `/** Same C1-class regression coverage as {@code WorkflowCallWorkflowToolTest}` together with the whole `testToolReturnsErrorWhenAgentIsItselfASubflow()` method that follows it.

`TestAgentContext` stays in both files — the other tests use it.

- [ ] **Step 2: Confirm the deletion still compiles**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:components:workflow:compileTestJava > $S/task4-compile.log 2>&1
echo "EXIT=$?"
```

Expected: `EXIT=0` — the tests compile without the deleted methods (nothing else referenced `ERROR_AGENT_IS_SUBFLOW` in test code). This step exists to catch a mis-scoped deletion before touching production code.

- [ ] **Step 3: Remove the guard and the constants**

In `SubflowToolSupport.java`:

Delete these lines from the constants:

```java
    /** Generic — no tool name embedded, so both tools can share the literal value verbatim. */
    static final String ERROR_AGENT_IS_SUBFLOW =
        "Error: calling a sub-workflow as a tool is not supported when the agent itself runs as a sub-workflow.";

```

In `requireGuardsPassed`, delete everything from the comment `// The agent is itself a sub-workflow (parentTaskExecutionId != null).` through the closing `}` of the `if (actionContextAware.getParentTaskExecutionId() != null) {` block, plus the blank line before the comment. The method then ends:

```java
        if (actionContextAware.getSuspend() != null) {
            log.warn(
                "{} tool invoked after another tool already suspended the agent (jobId={})", toolLabel,
                actionContextAware.getJobId());

            throw new GuardFailure(alreadySuspendedError(toolLabel));
        }

        return actionContextAware;
    }
```

In the class javadoc, change `every such tool must run the same three guards` to `every such tool must run the same two guards`, and change `(agent context present, no suspend already pending this turn, the calling agent is not itself a sub-workflow) before` to `(agent context present, no suspend already pending this turn) before`. In the javadoc of `requireGuardsPassed`, change `Runs the three suspend guards in order` to `Runs the two suspend guards in order`.

In `WorkflowCallWorkflowTool.java`, delete:

```java
    static final String ERROR_AGENT_IS_SUBFLOW = SubflowToolSupport.ERROR_AGENT_IS_SUBFLOW;

```

In `WorkflowCallAiAgentTool.java`, delete:

```java
    static final String ERROR_AGENT_IS_SUBFLOW = SubflowToolSupport.ERROR_AGENT_IS_SUBFLOW;

```

In `ActionContextAware.java`, replace the javadoc of `getParentTaskExecutionId`:

```java
    /**
     * Retrieves the parent task execution id of the current job, if the job runs as a sub-workflow of another job.
     * Returns {@code null} for top-level jobs (the common case) and for editor-environment / in-process invocations
     * with no persisted Atlas Job. The agent-tool sub-workflow bridge ({@code WorkflowCallWorkflowTool}) uses this to
     * fail fast when the agent itself runs as a sub-workflow, because a job with {@code parentTaskExecutionId != null}
     * cannot be resumed (see {@code JobServiceImpl.resumeToStatusStarted}).
     *
     * @return the parent task execution id, or {@code null} when the job is top-level or unavailable
     */
```

with:

```java
    /**
     * Retrieves the parent task execution id of the current job, if the job runs as a sub-workflow of another job.
     * Returns {@code null} for top-level jobs (the common case) and for editor-environment / in-process invocations
     * with no persisted Atlas Job.
     *
     * @return the parent task execution id, or {@code null} when the job is top-level or unavailable
     */
```

- [ ] **Step 4: Run both modules' checks**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:components:workflow:spotlessApply :server:libs:platform:platform-component:platform-component-api:spotlessApply \
  :server:libs:modules:components:workflow:check :server:libs:platform:platform-component:platform-component-api:check --continue > $S/task4-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/task4-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`. If PMD reports `ERROR_AGENT_IS_SUBFLOW` still referenced somewhere, run `grep -rn ERROR_AGENT_IS_SUBFLOW server/ --include='*.java' | grep -v worktrees` — the only permitted hits are none.

- [ ] **Step 5: Commit**

```bash
git commit \
  server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/SubflowToolSupport.java \
  server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowTool.java \
  server/libs/modules/components/workflow/src/main/java/com/bytechef/component/workflow/cluster/WorkflowCallAiAgentTool.java \
  server/libs/modules/components/workflow/src/test/java/com/bytechef/component/workflow/cluster/WorkflowCallWorkflowToolTest.java \
  server/libs/modules/components/workflow/src/test/java/com/bytechef/component/workflow/cluster/WorkflowCallAiAgentToolTest.java \
  server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ActionContextAware.java \
  -m "Drop the agent-is-a-subflow guard from the subflow tools" \
  -m "SubflowToolSupport refused to suspend when the calling agent's job had a parent task
execution, returning ERROR_AGENT_IS_SUBFLOW, because such a job could never be resumed and
would park. That failure mode no longer exists: a subflow child can suspend and resume, and
its parent keeps running while it does. The guard was the source of the documented one-level
sub-agent nesting limit; with it gone an agent launched as a sub-workflow can use its own
sub-agent and call-workflow tools.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Retire the documentation and the client warning

**Files:**
- Modify: `.agents/agents.md:362-379` and `.agents/agents.md:800-803`
- Modify: `client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.tsx:135-165`
- Modify: `client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.test.tsx`

**Interfaces:**
- Consumes: nothing.
- Produces: no reference to a nesting limit remains in docs or client.

- [ ] **Step 1: Update `.agents/agents.md`**

In the *Sub-agents* section, replace the passage beginning `The only hard server-side guard is **cycle prevention**:` and ending `just with an agent-uuid-keyed resolution step in\nfront of it).` with:

```markdown
The only server-side guard is **cycle prevention**:
`AiAgentFacadeImpl.validateSubAgentReference`/`isReachable` walks the SUB_AGENT reference graph and
rejects a reference that would create a cycle (`AiAgentErrorType.SUB_AGENT_CYCLE`, including
self-reference). Acyclic chains nest to any depth: a sub-agent's own sub-agent and `callWorkflow`
tools work whether the sub-agent was reached through the bridge (top-level job linked by
`AGENT_JOB_ID` metadata) or launched as a real `subflow/v1` child, because a subflow child can
suspend and resume (`docs/superpowers/specs/2026-09-05-resumable-subflows-design.md`). `callAiAgent`
shares the durable-subflow-suspend runtime with `callWorkflow`, just with an agent-uuid-keyed
resolution step in front of it.
```

In the closing summary paragraph, replace:

```markdown
Sub-agents
wire in via `workflow/v1/callAiAgent`; only cycles are hard-blocked, deeper-than-1-level nesting
is a save-time warning only (`callAiAgent` itself refuses to suspend when already running as a
subflow).
```

with:

```markdown
Sub-agents
wire in via `workflow/v1/callAiAgent`; only cycles are blocked, and acyclic chains nest to any
depth.
```

- [ ] **Step 2: Remove the client warning**

In `AgentSubAgentsCard.tsx`, delete the three lines computing `candidateHasSubAgents`:

```tsx
                        const candidateHasSubAgents = (candidate?.elements ?? []).some(
                            (subElement) => subElement?.kind === 'SUB_AGENT'
                        );
```

and delete the block:

```tsx
                                {candidateHasSubAgents && (
                                    <p className="mt-2 flex items-center gap-1 text-xs text-content-warning">
                                        <TriangleAlertIcon aria-hidden className="size-3.5" />
                                        This agent has sub-agents of its own; they won&apos;t be callable when it runs
                                        as a sub-agent.
                                    </p>
                                )}
```

Then check whether `TriangleAlertIcon` is still used anywhere in the file (`grep -n TriangleAlertIcon client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.tsx`). If the only remaining hit is the import, remove `TriangleAlertIcon` from the `lucide-react` import list, keeping the remaining names alphabetically sorted.

In `AgentSubAgentsCard.test.tsx`, delete the two `it(...)` blocks whose assertions reference `/won't be callable when it runs as a sub-agent/i` — the one that expects the warning to be in the document and `'does not warn when the selected sub-agent has no sub-agents of its own'`. If `describe` then contains no tests, delete the whole file; otherwise leave the rest.

- [ ] **Step 3: Verify the client**

```bash
cd client && npx vitest run src/pages/automation/agents/components/detail/AgentSubAgentsCard.test.tsx 2>&1 | tail -5; npx eslint src/pages/automation/agents/components/detail/AgentSubAgentsCard.tsx src/pages/automation/agents/components/detail/AgentSubAgentsCard.test.tsx && npm run typecheck && echo "CLIENT OK"
```

Expected: remaining tests pass (or "No test files found" if the file was deleted), ESLint reports nothing, `npm run typecheck` reports nothing, `CLIENT OK` is printed. Node 22.19+ or 24 is required for the client; Node 26 fake-fails on jsdom.

- [ ] **Step 4: Commit — two commits, docs then client**

```bash
git commit .agents/agents.md \
  -m "Document that sub-agents nest to any depth" \
  -m "The one-level nesting limit came from ERROR_AGENT_IS_SUBFLOW, which is gone.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

```bash
git commit \
  client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.tsx \
  client/src/pages/automation/agents/components/detail/AgentSubAgentsCard.test.tsx \
  -m "client - Drop the sub-agent nesting warning" \
  -m "A sub-agent's own sub-agents are callable now that a subflow child can suspend and resume,
so the save-time warning described a limitation that no longer exists.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

If `AgentSubAgentsCard.test.tsx` was deleted rather than edited, use `git rm -q <path>` before the commit and keep the path in the `git commit` list.

---

### Task 6: Whole-branch verification

**Files:** none modified unless a formatter changes something.

- [ ] **Step 1: Run the affected server modules together**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew spotlessApply > $S/final-spotless.log 2>&1; echo "SPOTLESS EXIT=$?"
./gradlew \
  :server:libs:atlas:atlas-execution:atlas-execution-service:check \
  :server:libs:modules:task-dispatchers:subflow:check \
  :server:libs:modules:components:workflow:check \
  :server:libs:platform:platform-component:platform-component-api:check \
  :server:libs:platform:platform-workflow:platform-workflow-test:platform-workflow-test-service:check \
  :server:libs:platform:platform-webhook:platform-webhook-impl:check \
  --continue > $S/final-check.log 2>&1
echo "CHECK EXIT=$?"
grep -E "^> Task .* FAILED" $S/final-check.log
```

Expected: both exits `0`, no `FAILED` task lines. The last two modules are included because they scan `com.bytechef.task.dispatcher` and would surface an accidental change to the dispatcher set.

- [ ] **Step 2: Run the atlas-execution integration tests**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:atlas:atlas-execution:atlas-execution-service:testIntegration > $S/final-inttest.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|tests completed" $S/final-inttest.log
```

Expected: `EXIT=0`.

- [ ] **Step 3: Client check**

```bash
cd client && timeout 600 npm run check 2>&1 | tail -15
```

Expected: lint, typecheck and tests all pass.

- [ ] **Step 4: If `spotlessApply` changed any file, commit that file alone**

```bash
git status --porcelain -- server/libs/atlas server/libs/modules/task-dispatchers/subflow server/libs/modules/components/workflow server/libs/platform/platform-component
```

If any path in the plan's File Structure is listed, commit only those paths with subject `Apply formatter to resumable-subflows changes` and the standard trailer. Anything listed outside the File Structure belongs to someone else's work in progress and must not be touched.

---

## Spec coverage check

| Spec section | Task |
|---|---|
| Design §1 Drop the assertion | Task 1 |
| Design §2 Discriminate suspend vs stop | Task 2 (with Deviation 1) |
| Design §3 Remove the guard | Task 4 |
| Design §4 Fix the second-call park | Task 3 (with Deviation 3) |
| Design §5 Retire documentation | Task 5 |
| Testing — failing IntTest for the parked subflow | Task 1 (assertion, real DB) + Task 2 (`SubflowSuspendIntTest`) |
| Testing — listener unit tests, three branches | Task 2 |
| Testing — approval inside a subflow through `JobFacade.resumeApproval` | **Not implemented as a separate test.** `resumeApproval` calls `resumeToStatusStarted` directly (`JobFacadeImpl:201`); Task 1 covers that call for a subflow child. A full approval round-trip needs the resume route the in-process rig lacks (Deviation 2). |
| Testing — bridge second call | Task 3 |
| Testing — existing suites stay green | Tasks 2–4 run their whole modules; Task 6 runs the config-test modules |
| Non-goal — no depth cap, no bridge relink, no orphan auto-resume widening | No task touches these |
