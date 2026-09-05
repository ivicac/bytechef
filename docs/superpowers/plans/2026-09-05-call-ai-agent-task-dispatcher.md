# Call AI Agent Task Dispatcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `callAiAgent/v1` task dispatcher — a Flows-tab node that sends a message to a published AI Agent and yields its reply, deterministically, with real child-job semantics and concurrent fan-out.

**Architecture:** A new Gradle module `task-dispatchers/call-ai-agent` holds four classes mirroring `task-dispatchers/subflow`: dispatcher, definition factory, constants, Spring config. The dispatcher resolves `agentUuid` → agent workflow through `CallableAiAgentDataSource` and `SubflowResolver`, then launches a real child job with `parentTaskExecutionId` through a helper extracted from `SubflowTaskDispatcher` so both dispatchers share one launch path. No completion handler is needed: `SubflowJobStatusEventListener` gates only on `parentTaskExecutionId != null` and already propagates COMPLETED/FAILED/STOPPED/CANCELLED to any parent task. Registration is four-site (own `@Bean`, two literal `List.of(...)` lists, Gradle), and the client enumerates the new name in three files.

**Tech Stack:** Java 25 / Spring Boot 4, `TaskDispatcherDsl`, Gradle Kotlin DSL, in-memory Atlas repositories + a hand-wired `TaskCoordinator` for the integration rig (modelled on `SubflowResumeIntTest`), React 19 + TypeScript + Vitest for the client.

**Spec:** [docs/superpowers/specs/2026-09-05-call-ai-agent-task-dispatcher-design.md](../specs/2026-09-05-call-ai-agent-task-dispatcher-design.md)

## Deviations from the spec (read before starting)

1. **The shared launch helper is `public`, not package-visible.** The spec says "package-visible helper in the `subflow` module", but `call-ai-agent` is a separate module and package, so `SubflowChildJobLauncher` must be public to be callable from it.
2. **`CallableAiAgentDataSource` is optional at boot and checked at dispatch, not required at boot.** The spec wants the factory to fail at boot when the bean is absent. That is not workable: `WorkflowTestConfiguration` (`platform-workflow-test-service`) and `WebhookConfiguration` (`platform-webhook-impl`) build their dispatcher chains from literal `List.of(...)` lists, their tests fail if a dispatcher on the classpath is missing from those lists, and those modules ship in apps that do not assemble agents (`webhook-app` carries `platform-workflow-execution-remote-client`, not `automation-ai-agent-service`). Requiring the bean there would break those apps' Spring contexts. So every construction site injects `ObjectProvider<CallableAiAgentDataSource>` and passes `getIfAvailable()`; the dispatcher throws a clear `IllegalStateException` at dispatch when it is null, and the definition factory's agent picker returns an empty list. This mirrors how `WorkflowComponentHandler` already treats the same bean.
3. **The output-schema lookup resolves the agent with `editorEnvironment = true`.** `TaskDispatcherDefinition.OutputFunction` receives only input parameters, and output schema is an editor-time concern; the draft-permissive resolution is the right one there.
4. **One integration test covers both the spec's "fan-out" and its "agent whose `aiAgent` node suspends once and replies" scenarios.** Both children in the fan-out test carry a `suspend/v1` step, so they stand in for a sub-agent call: both are STOPPED at the same moment (which is what proves concurrency — serial execution never has two alive), both are genuinely resumed through `TaskCoordinator.onResumeJobEvent`, and the parent completes with both replies. Writing this as the third task, before registration and client work, honours the spec's instruction to settle the fan-out claim early.
5. **`components/workflow` does not get a dependency on the new module** although it depends on `task-dispatchers:subflow`. It needs `PendingSubflowRequest`/`SubflowRequestConstants`/`SubflowResolver.Subflow` from that module for the agent tools; nothing in it touches the new dispatcher. The seven sites the spec lists are the complete set.

## Global Constraints

- **Java blank-line rules:** exactly one blank line before `if`/`for`/`while`/`try`/`switch`, except immediately after an opening `{`; one blank line between a variable modification and the statement that uses it; no blank line between the last member and a class's closing `}`.
- **No new inline comments in production or test code.** Rationale goes in the commit message body. Pre-existing comments in touched files stay unless the code they describe is deleted.
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule fails the build.
- **No `_`-prefixed methods. No short or cryptic names**, including lambda parameters.
- **Test naming:** unit tests end in `Test`; only Spring-context tests end in `IntTest`. Method names camelCase without underscores, including private helpers. This module routes `*IntTest` to `testIntegration`; `:test` excludes them; a module's `check` runs both.
- **Snapshot tests regenerate in two runs.** `JsonFileAssert` writes a missing snapshot to `src/test/resources/…` and reads it back off the classpath (`build/resources/test/…`); the first run therefore throws `NullPointerException: url`. Run again. That NPE is the expected midpoint, not a bug.
- **Client:** object keys alphabetical (`sort-keys`, not auto-fixable); named imports sorted within `{}`; Lucide icons with the `Icon` suffix; `twMerge` not `cn()`.
- **Commit by path only.** Shared checkout with unrelated user work in progress (notably under `client/src/ee/pages/embedded/automation-hub/`). `git add <new paths>` then `git commit <all paths> -m …`. Never `git add -A`, `git stash`, `git checkout`, `git restore`, `--amend`.
- **Commit message shape:** imperative subject, no ticket prefix; body carries the rationale; last line `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Client-only commits prefix the subject `client - `.
- **Gradle verification:** redirect to a file, `echo "EXIT=$?"` on its own line, then grep. Never judge a run piped into `tail`/`grep`. Use `--continue`. Pass `timeout: 600000` on Gradle Bash calls. Scratch dir: `/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad/`.
- `.claude/worktrees/*` holds stale copies. Not built. Do not edit.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncher.java` | The one place a child job is built and launched for a parent task | **New** |
| `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcher.java` | `subflow/v1` dispatch | Delegate its launch tail to the helper |
| `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncherTest.java` | Helper unit tests | **New** |
| `server/libs/modules/task-dispatchers/call-ai-agent/build.gradle.kts` | Module deps | **New** |
| `settings.gradle.kts` | Module registry | Add `include(...)` |
| `.../call-ai-agent/src/main/java/com/bytechef/task/dispatcher/callaiagent/constant/CallAiAgentTaskDispatcherConstants.java` | Names | **New** |
| `.../call-ai-agent/src/main/java/com/bytechef/task/dispatcher/callaiagent/CallAiAgentTaskDispatcher.java` | `callAiAgent/v1` dispatch + resolver | **New** |
| `.../call-ai-agent/src/main/java/com/bytechef/task/dispatcher/callaiagent/CallAiAgentTaskDispatcherDefinitionFactory.java` | Definition (properties, picker, output) | **New** |
| `.../call-ai-agent/src/main/java/com/bytechef/task/dispatcher/callaiagent/config/CallAiAgentTaskDispatcherConfiguration.java` | Coordinator `@Bean` | **New** |
| `.../call-ai-agent/src/main/resources/assets/callAiAgent.svg` | Node icon | **New** (copy of `subflow.svg`) |
| `.../call-ai-agent/src/test/java/.../CallAiAgentTaskDispatcherTest.java`, `CallAiAgentTaskDispatcherDefinitionFactoryTest.java`, `CallAiAgentFanOutIntTest.java` + `src/test/resources/workflows/*.yaml` + `src/test/resources/definition/callAiAgent_v1.json` | Tests | **New** |
| `server/libs/platform/platform-workflow/platform-workflow-test/platform-workflow-test-service/src/main/java/com/bytechef/platform/workflow/test/config/WorkflowTestConfiguration.java` | Editor Test-button chain | Add the dispatcher to the literal list |
| `server/libs/platform/platform-webhook/platform-webhook-impl/src/main/java/com/bytechef/platform/webhook/executor/config/WebhookConfiguration.java` | Webhook chain | Add the dispatcher to the literal list |
| Seven `build.gradle.kts` files (listed in Task 4) | App/module classpaths | Add the dependency beside `task-dispatchers:subflow` |
| `client/src/shared/types.ts`, `client/src/shared/constants.tsx`, `client/src/pages/platform/workflow-editor/utils/taskDispatcherConfig.tsx` (+ `.test.ts`) | Client dispatcher enumeration | Add `callAiAgent` |

---

### Task 1: Extract the child-job launch from `SubflowTaskDispatcher`

**Files:**
- Create: `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncher.java`
- Modify: `server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcher.java:80-100`
- Create: `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncherTest.java`

**Interfaces:**
- Consumes: `ChildJobPrincipalFactory.createChildJob(long parentJobId, JobParametersDTO)`, `SubflowResolver.Subflow(String workflowId, String inputsName)`, `JobParametersDTO(String workflowId, Long parentTaskExecutionId, Map inputs, String label, Integer priority, List<Webhook> webhooks, Map metadata)`, `JobInputConstants.TRIGGER_NAME_INPUT`.
- Produces: `public static long SubflowChildJobLauncher.launch(ChildJobPrincipalFactory childJobPrincipalFactory, Job job, TaskExecution taskExecution, Subflow subflow, Map<String, ?> inputValues)` — returns the child job id. Task 2's dispatcher calls it.

- [ ] **Step 1: Write the failing test**

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.platform.workflow.JobInputConstants;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver.Subflow;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Map;
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
class SubflowChildJobLauncherTest {

    @Mock
    private ChildJobPrincipalFactory childJobPrincipalFactory;

    @Test
    void testLaunchWrapsInputsUnderTheTriggerNameAndLinksTheParentTask() {
        Job job = new Job();

        job.setId(1L);
        job.setMetadata(Map.of("tenant", "acme"));

        TaskExecution taskExecution = TaskExecution.builder()
            .workflowTask(new WorkflowTask(Map.of("name", "callChild", "type", "subflow/v1")))
            .build();

        taskExecution.setId(10L);
        taskExecution.setJobId(1L);

        when(childJobPrincipalFactory.createChildJob(eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(200L);

        long childJobId = SubflowChildJobLauncher.launch(
            childJobPrincipalFactory, job, taskExecution, new Subflow("child-workflow-id", "newWorkflowCall"),
            Map.of("message", "hello"));

        assertThat(childJobId).isEqualTo(200L);

        ArgumentCaptor<JobParametersDTO> jobParametersDTOArgumentCaptor = ArgumentCaptor.forClass(JobParametersDTO.class);

        verify(childJobPrincipalFactory).createChildJob(eq(1L), jobParametersDTOArgumentCaptor.capture());

        JobParametersDTO jobParametersDTO = jobParametersDTOArgumentCaptor.getValue();

        assertThat(jobParametersDTO.getWorkflowId()).isEqualTo("child-workflow-id");
        assertThat(jobParametersDTO.getParentTaskExecutionId()).isEqualTo(10L);
        assertThat(jobParametersDTO.getInputs())
            .containsEntry("newWorkflowCall", Map.of("message", "hello"))
            .containsEntry(JobInputConstants.TRIGGER_NAME_INPUT, "newWorkflowCall");
        assertThat(jobParametersDTO.getMetadata()).containsEntry("tenant", "acme");
    }
}
```

Replace `org.mockito.ArgumentMatchers.any()` with a static import `import static org.mockito.ArgumentMatchers.any;` placed alphabetically above `eq` — the inline form is only here so the snippet is unambiguous.

- [ ] **Step 2: Run it to verify it fails to compile**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:compileTestJava > $S/t1-red.log 2>&1
echo "EXIT=$?"
grep -E "cannot find symbol|SubflowChildJobLauncher" $S/t1-red.log | head -3
```

Expected: `EXIT=1`, `cannot find symbol ... SubflowChildJobLauncher`.

- [ ] **Step 3: Write the helper**

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

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.platform.workflow.JobInputConstants;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver.Subflow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds and launches the child job a parent task waits on. The child carries the parent task execution id, inherits
 * the parent job's metadata, and receives the caller-supplied input values under the resolved trigger's input name
 * alongside the reserved {@code __triggerName} input.
 *
 * @author Ivica Cardic
 */
public final class SubflowChildJobLauncher {

    private SubflowChildJobLauncher() {
    }

    public static long launch(
        ChildJobPrincipalFactory childJobPrincipalFactory, Job job, TaskExecution taskExecution, Subflow subflow,
        Map<String, ?> inputValues) {

        Map<String, Object> inputs = new HashMap<>();

        inputs.put(subflow.inputsName(), inputValues);
        inputs.put(JobInputConstants.TRIGGER_NAME_INPUT, subflow.inputsName());

        Map<String, Object> childMetadata = new HashMap<>(job.getMetadata());

        JobParametersDTO jobParametersDTO = new JobParametersDTO(
            subflow.workflowId(), taskExecution.getId(), inputs, null, null, List.of(), childMetadata);

        return childJobPrincipalFactory.createChildJob(Objects.requireNonNull(job.getId()), jobParametersDTO);
    }
}
```

- [ ] **Step 4: Make `SubflowTaskDispatcher` delegate**

In `SubflowTaskDispatcher.dispatch`, replace everything after the empty-`workflowId` check — from `Map<String, Object> inputs = new HashMap<>();` through `childJobPrincipalFactory.createChildJob(...)` — with:

```java
        SubflowChildJobLauncher.launch(
            childJobPrincipalFactory, job, taskExecution, subflow,
            MapUtils.getMap(taskExecution.getParameters(), WorkflowConstants.INPUTS, Collections.emptyMap()));
```

Then remove imports that are now unused in that file: `com.bytechef.atlas.execution.dto.JobParametersDTO`, `com.bytechef.platform.workflow.JobInputConstants`, `java.util.HashMap`, `java.util.List`. Keep `java.util.Collections`, `java.util.Map`, `java.util.Objects` (still used) and `com.bytechef.atlas.configuration.constant.WorkflowConstants`.

- [ ] **Step 5: Run the module and confirm green**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:subflow:spotlessApply :server:libs:modules:task-dispatchers:subflow:check --continue > $S/t1-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/t1-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`. `SubflowTaskDispatcherTest`, `SubflowSuspendIntTest` and `SubflowResumeIntTest` must all still pass — they are the proof the extraction changed nothing.

- [ ] **Step 6: Commit**

```bash
git add server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncher.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncherTest.java
git commit \
  server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncher.java \
  server/libs/modules/task-dispatchers/subflow/src/main/java/com/bytechef/task/dispatcher/subflow/SubflowTaskDispatcher.java \
  server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowChildJobLauncherTest.java \
  -m "Extract the child-job launch from SubflowTaskDispatcher" \
  -m "A second dispatcher is about to launch child jobs for a parent task with a different input
shape. The tail of SubflowTaskDispatcher.dispatch - wrap the input values under the resolved
trigger's name, add __triggerName, copy the parent's metadata, build the JobParametersDTO with the
parent task execution id, create the child - moves into SubflowChildJobLauncher so both call one
path. Behaviour is unchanged; the existing dispatcher, suspend and resume tests are the proof.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The `call-ai-agent` module — dispatcher, definition, config, unit tests, snapshot

**Files:**
- Modify: `settings.gradle.kts:599` (insert after `include("server:libs:modules:task-dispatchers:branch")`)
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/build.gradle.kts`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/main/resources/assets/callAiAgent.svg`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/main/java/com/bytechef/task/dispatcher/callaiagent/constant/CallAiAgentTaskDispatcherConstants.java`
- Create: `.../callaiagent/CallAiAgentTaskDispatcher.java`
- Create: `.../callaiagent/CallAiAgentTaskDispatcherDefinitionFactory.java`
- Create: `.../callaiagent/config/CallAiAgentTaskDispatcherConfiguration.java`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/java/com/bytechef/task/dispatcher/callaiagent/CallAiAgentTaskDispatcherTest.java`
- Create: `.../callaiagent/CallAiAgentTaskDispatcherDefinitionFactoryTest.java`
- Generated by the test: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/resources/definition/callAiAgent_v1.json`

**Interfaces:**
- Consumes: `SubflowChildJobLauncher.launch(...)` from Task 1; `CallableAiAgentDataSource.resolveAgent(String agentUuid, boolean editorEnvironment) -> ResolvedAiAgent(workflowUuid, name, description)`, `.getCallableAgents(String search) -> List<CallableAiAgentEntry(agentUuid, title, description)>`; `SubflowResolver.resolveSubflow(String workflowUuid, String triggerName, boolean editorEnvironment)`; `SubflowDataSource.getSubWorkflowOutputSchema(String workflowUuid)`; `WorkflowConstants.NEW_WORKFLOW_CALL` from `com.bytechef.platform.component.constant`.
- Produces: `CallAiAgentTaskDispatcher(ChildJobPrincipalFactory, @Nullable CallableAiAgentDataSource, JobService, SubflowResolver)` implementing `TaskDispatcher<TaskExecution>` and `TaskDispatcherResolver`, matching type `callAiAgent/v1`; `CallAiAgentTaskDispatcherDefinitionFactory(@Nullable CallableAiAgentDataSource, SubflowDataSource)`; constants `CALL_AI_AGENT = "callAiAgent"`, `AGENT_UUID = "agentUuid"`, `MESSAGE = "message"`, `CONVERSATION_ID = "conversationId"`. Tasks 3 and 4 construct the dispatcher with exactly that constructor.

- [ ] **Step 1: Register the module and write its build file**

In `settings.gradle.kts`, after `include("server:libs:modules:task-dispatchers:branch")` add:

```kotlin
include("server:libs:modules:task-dispatchers:call-ai-agent")
```

Create `server/libs/modules/task-dispatchers/call-ai-agent/build.gradle.kts`:

```kotlin
version="1.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:modules:task-dispatchers:subflow"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))

    testImplementation(project(":server:libs:atlas:atlas-coordinator:atlas-coordinator-impl"))
    testImplementation(project(":server:libs:atlas:atlas-execution:atlas-execution-repository:atlas-execution-repository-memory"))
    testImplementation(project(":server:libs:atlas:atlas-execution:atlas-execution-service"))
    testImplementation(project(":server:libs:atlas:atlas-worker:atlas-worker-impl"))
    testImplementation(project(":server:libs:core:message:message-broker:message-broker-memory"))
    testImplementation(project(":server:libs:core:tenant:tenant-api"))
    testImplementation(project(":server:libs:modules:task-dispatchers:parallel"))
    testImplementation(project(":server:libs:modules:task-dispatchers:suspend"))
    testImplementation(project(":server:libs:platform:platform-job-sync"))
    testImplementation(project(":server:libs:platform:platform-worker"))
    testImplementation(project(":server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-api"))
    testImplementation(project(":server:libs:test:test-support"))
}
```

The parent `server/libs/modules/task-dispatchers/build.gradle.kts` already adds the shared dispatcher deps (atlas APIs, `platform-workflow-task-dispatcher-api`, `commons-util`, `test-int-support`, `test-support`) to every subproject — do not repeat them.

Copy the icon: `cp server/libs/modules/task-dispatchers/subflow/src/main/resources/assets/subflow.svg server/libs/modules/task-dispatchers/call-ai-agent/src/main/resources/assets/callAiAgent.svg`.

- [ ] **Step 2: Constants**

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

package com.bytechef.task.dispatcher.callaiagent.constant;

/**
 * @author Ivica Cardic
 */
public class CallAiAgentTaskDispatcherConstants {

    public static final String CALL_AI_AGENT = "callAiAgent";
    public static final String AGENT_UUID = "agentUuid";
    public static final String MESSAGE = "message";
    public static final String CONVERSATION_ID = "conversationId";
}
```

- [ ] **Step 3: Write the failing dispatcher test**

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

package com.bytechef.task.dispatcher.callaiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Task;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.constant.WorkflowConstants;
import com.bytechef.platform.workflow.JobInputConstants;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource.ResolvedAiAgent;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver.Subflow;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Map;
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
class CallAiAgentTaskDispatcherTest {

    private static final String AGENT_UUID = "agent-uuid-1";
    private static final String AGENT_WORKFLOW_UUID = "agent-workflow-uuid";
    private static final String RESOLVED_WORKFLOW_ID = "resolved-workflow-id";

    @Mock
    private CallableAiAgentDataSource callableAiAgentDataSource;

    @Mock
    private ChildJobPrincipalFactory childJobPrincipalFactory;

    @Mock
    private JobService jobService;

    @Mock
    private SubflowResolver subflowResolver;

    @Test
    void testDispatchResolvesTheAgentThenItsWorkflowAndLaunchesWithMessageAndConversationId() {
        TaskExecution taskExecution = createTaskExecution(
            Map.of("agentUuid", AGENT_UUID, "message", "hello", "conversationId", "thread-7"));

        stubParentJob(Map.of());

        when(callableAiAgentDataSource.resolveAgent(AGENT_UUID, false))
            .thenReturn(new ResolvedAiAgent(AGENT_WORKFLOW_UUID, "Triage Agent", null));
        when(subflowResolver.resolveSubflow(AGENT_WORKFLOW_UUID, WorkflowConstants.NEW_WORKFLOW_CALL, false))
            .thenReturn(new Subflow(RESOLVED_WORKFLOW_ID, "workflowCall_1"));

        newDispatcher(callableAiAgentDataSource).dispatch(taskExecution);

        ArgumentCaptor<JobParametersDTO> jobParametersDTOArgumentCaptor = ArgumentCaptor.forClass(JobParametersDTO.class);

        verify(childJobPrincipalFactory).createChildJob(anyLong(), jobParametersDTOArgumentCaptor.capture());

        JobParametersDTO jobParametersDTO = jobParametersDTOArgumentCaptor.getValue();

        assertThat(jobParametersDTO.getWorkflowId()).isEqualTo(RESOLVED_WORKFLOW_ID);
        assertThat(jobParametersDTO.getParentTaskExecutionId()).isEqualTo(10L);
        assertThat(jobParametersDTO.getInputs())
            .containsEntry("workflowCall_1", Map.of("message", "hello", "conversationId", "thread-7"))
            .containsEntry(JobInputConstants.TRIGGER_NAME_INPUT, "workflowCall_1");
    }

    @Test
    void testDispatchOmitsAnAbsentConversationId() {
        TaskExecution taskExecution = createTaskExecution(Map.of("agentUuid", AGENT_UUID, "message", "hello"));

        stubParentJob(Map.of());

        when(callableAiAgentDataSource.resolveAgent(AGENT_UUID, false))
            .thenReturn(new ResolvedAiAgent(AGENT_WORKFLOW_UUID, "Triage Agent", null));
        when(subflowResolver.resolveSubflow(AGENT_WORKFLOW_UUID, WorkflowConstants.NEW_WORKFLOW_CALL, false))
            .thenReturn(new Subflow(RESOLVED_WORKFLOW_ID, "workflowCall_1"));

        newDispatcher(callableAiAgentDataSource).dispatch(taskExecution);

        ArgumentCaptor<JobParametersDTO> jobParametersDTOArgumentCaptor = ArgumentCaptor.forClass(JobParametersDTO.class);

        verify(childJobPrincipalFactory).createChildJob(anyLong(), jobParametersDTOArgumentCaptor.capture());

        assertThat(jobParametersDTOArgumentCaptor.getValue()
            .getInputs()).containsEntry("workflowCall_1", Map.of("message", "hello"));
    }

    @Test
    void testDispatchPassesTheEditorEnvironmentFlagThroughBothResolutions() {
        TaskExecution taskExecution = createTaskExecution(Map.of("agentUuid", AGENT_UUID, "message", "hello"));

        stubParentJob(Map.of(MetadataConstants.EDITOR_ENVIRONMENT, true));

        when(callableAiAgentDataSource.resolveAgent(AGENT_UUID, true))
            .thenReturn(new ResolvedAiAgent(AGENT_WORKFLOW_UUID, "Triage Agent", null));
        when(subflowResolver.resolveSubflow(AGENT_WORKFLOW_UUID, WorkflowConstants.NEW_WORKFLOW_CALL, true))
            .thenReturn(new Subflow(RESOLVED_WORKFLOW_ID, "workflowCall_1"));

        newDispatcher(callableAiAgentDataSource).dispatch(taskExecution);

        verify(callableAiAgentDataSource).resolveAgent(AGENT_UUID, true);
        verify(subflowResolver).resolveSubflow(AGENT_WORKFLOW_UUID, WorkflowConstants.NEW_WORKFLOW_CALL, true);
    }

    @Test
    void testDispatchFailsTheTaskWhenTheAgentCannotBeResolved() {
        TaskExecution taskExecution = createTaskExecution(Map.of("agentUuid", AGENT_UUID, "message", "hello"));

        stubParentJob(Map.of());

        when(callableAiAgentDataSource.resolveAgent(AGENT_UUID, false))
            .thenThrow(new IllegalArgumentException("Agent agent-uuid-1 has no published version"));

        assertThatThrownBy(() -> newDispatcher(callableAiAgentDataSource).dispatch(taskExecution))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no published version");

        verify(childJobPrincipalFactory, never()).createChildJob(anyLong(), any());
    }

    @Test
    void testDispatchFailsClearlyWhenAgentsAreNotAvailableInThisDeployment() {
        TaskExecution taskExecution = createTaskExecution(Map.of("agentUuid", AGENT_UUID, "message", "hello"));

        stubParentJob(Map.of());

        assertThatThrownBy(() -> newDispatcher(null).dispatch(taskExecution))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not available");

        verify(childJobPrincipalFactory, never()).createChildJob(anyLong(), any());
    }

    @Test
    void testResolveMatchesOnlyItsOwnType() {
        CallAiAgentTaskDispatcher dispatcher = newDispatcher(callableAiAgentDataSource);

        assertThat(dispatcher.resolve(taskOfType("callAiAgent/v1"))).isSameAs(dispatcher);
        assertThat(dispatcher.resolve(taskOfType("subflow/v1"))).isNull();
    }

    private CallAiAgentTaskDispatcher newDispatcher(CallableAiAgentDataSource dataSource) {
        return new CallAiAgentTaskDispatcher(childJobPrincipalFactory, dataSource, jobService, subflowResolver);
    }

    private void stubParentJob(Map<String, ?> metadata) {
        Job job = new Job();

        job.setId(1L);
        job.setMetadata(metadata);

        when(jobService.getJob(1L)).thenReturn(job);
    }

    private static TaskExecution createTaskExecution(Map<String, ?> parameters) {
        TaskExecution taskExecution = TaskExecution.builder()
            .workflowTask(
                new WorkflowTask(
                    Map.of("name", "callAgent", "type", "callAiAgent/v1", "parameters", parameters)))
            .build();

        taskExecution.setId(10L);
        taskExecution.setJobId(1L);

        return taskExecution;
    }

    private static Task taskOfType(String type) {
        return new WorkflowTask(Map.of("name", "task", "type", type));
    }
}
```

- [ ] **Step 4: Write the dispatcher**

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

package com.bytechef.task.dispatcher.callaiagent;

import static com.bytechef.platform.component.constant.WorkflowConstants.NEW_WORKFLOW_CALL;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.AGENT_UUID;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.CALL_AI_AGENT;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.CONVERSATION_ID;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.MESSAGE;

import com.bytechef.atlas.configuration.domain.Task;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcher;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcherResolver;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource.ResolvedAiAgent;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver.Subflow;
import com.bytechef.task.dispatcher.subflow.SubflowChildJobLauncher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Dispatches the {@code callAiAgent/v1} task: resolves the picked agent to its published workflow and runs it as a
 * child job of the calling task, so the reply flows back into the parent through the ordinary subflow completion
 * path.
 *
 * @author Ivica Cardic
 */
public class CallAiAgentTaskDispatcher implements TaskDispatcher<TaskExecution>, TaskDispatcherResolver {

    private final @Nullable CallableAiAgentDataSource callableAiAgentDataSource;
    private final ChildJobPrincipalFactory childJobPrincipalFactory;
    private final JobService jobService;
    private final SubflowResolver subflowResolver;

    @SuppressFBWarnings("EI")
    public CallAiAgentTaskDispatcher(
        ChildJobPrincipalFactory childJobPrincipalFactory, @Nullable CallableAiAgentDataSource callableAiAgentDataSource,
        JobService jobService, SubflowResolver subflowResolver) {

        this.callableAiAgentDataSource = callableAiAgentDataSource;
        this.childJobPrincipalFactory = childJobPrincipalFactory;
        this.jobService = jobService;
        this.subflowResolver = subflowResolver;
    }

    @Override
    public void dispatch(TaskExecution taskExecution) {
        if (callableAiAgentDataSource == null) {
            throw new IllegalStateException("Call AI Agent is not available in this deployment");
        }

        Job job = jobService.getJob(Objects.requireNonNull(taskExecution.getJobId()));

        boolean editorEnvironment = MapUtils.getBoolean(job.getMetadata(), MetadataConstants.EDITOR_ENVIRONMENT, false);
        String agentUuid = MapUtils.getRequiredString(taskExecution.getParameters(), AGENT_UUID);

        ResolvedAiAgent resolvedAiAgent = callableAiAgentDataSource.resolveAgent(agentUuid, editorEnvironment);

        Subflow subflow = subflowResolver.resolveSubflow(
            resolvedAiAgent.workflowUuid(), NEW_WORKFLOW_CALL, editorEnvironment);

        Map<String, Object> inputValues = new HashMap<>();

        inputValues.put(MESSAGE, MapUtils.getRequiredString(taskExecution.getParameters(), MESSAGE));

        String conversationId = MapUtils.getString(taskExecution.getParameters(), CONVERSATION_ID);

        if (conversationId != null && !conversationId.isBlank()) {
            inputValues.put(CONVERSATION_ID, conversationId);
        }

        SubflowChildJobLauncher.launch(childJobPrincipalFactory, job, taskExecution, subflow, inputValues);
    }

    @Override
    public TaskDispatcher<? extends Task> resolve(Task task) {
        if (Objects.equals(task.getType(), CALL_AI_AGENT + "/v1")) {
            return this;
        }

        return null;
    }
}
```

- [ ] **Step 5: Write the failing definition-factory test**

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

package com.bytechef.task.dispatcher.callaiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bytechef.definition.BaseOutputDefinition.OutputResponse;
import com.bytechef.definition.BaseProperty;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.task.dispatcher.definition.Option;
import com.bytechef.platform.workflow.task.dispatcher.definition.OutputDefinition;
import com.bytechef.platform.workflow.task.dispatcher.definition.Property;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDefinition.OptionsFunction;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDefinition.OutputFunction;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource.CallableAiAgentEntry;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource.ResolvedAiAgent;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.domain.SubflowEntry;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.bytechef.test.jsonasssert.JsonFileAssert;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class CallAiAgentTaskDispatcherDefinitionFactoryTest {

    @Mock
    private CallableAiAgentDataSource callableAiAgentDataSource;

    @Mock
    private SubflowDataSource subflowDataSource;

    @Test
    void testGetTaskDispatcherDefinition() {
        SubflowDataSource stubSubflowDataSource = new SubflowDataSource() {

            @Override
            public BaseProperty.BaseValueProperty<?> getSubWorkflowInputSchema(String workflowUuid) {
                return null;
            }

            @Override
            public BaseProperty.BaseValueProperty<?> getSubWorkflowOutputSchema(String workflowUuid) {
                return null;
            }

            @Override
            public List<SubflowEntry> getSubWorkflows(PlatformType platformType, String triggerName, String search) {
                return List.of();
            }
        };

        JsonFileAssert.assertEquals(
            "definition/callAiAgent_v1.json",
            new CallAiAgentTaskDispatcherDefinitionFactory(null, stubSubflowDataSource).getDefinition());
    }

    @Test
    void testAgentOptionsListPublishedAgents() throws Exception {
        when(callableAiAgentDataSource.getCallableAgents("tri"))
            .thenReturn(List.of(new CallableAiAgentEntry("agent-uuid-1", "Triage Agent", null)));

        List<? extends Option<?>> options = agentOptionsFunction(
            new CallAiAgentTaskDispatcherDefinitionFactory(callableAiAgentDataSource, subflowDataSource))
                .apply("tri");

        assertThat(options).hasSize(1);
        assertThat(options.getFirst()
            .getLabel()).isEqualTo("Triage Agent");
        assertThat(options.getFirst()
            .getValue()).isEqualTo("agent-uuid-1");
    }

    @Test
    void testAgentOptionsAreEmptyWhenAgentsAreNotAvailable() throws Exception {
        List<? extends Option<?>> options = agentOptionsFunction(
            new CallAiAgentTaskDispatcherDefinitionFactory(null, subflowDataSource)).apply(null);

        assertThat(options).isEmpty();
    }

    @Test
    void testOutputResolvesTheAgentWorkflowSchema() throws Exception {
        when(callableAiAgentDataSource.resolveAgent("agent-uuid-1", true))
            .thenReturn(new ResolvedAiAgent("agent-workflow-uuid", "Triage Agent", null));
        when(subflowDataSource.getSubWorkflowOutputSchema("agent-workflow-uuid"))
            .thenReturn(TaskDispatcherDsl.object("result"));

        OutputResponse outputResponse = outputFunction(
            new CallAiAgentTaskDispatcherDefinitionFactory(callableAiAgentDataSource, subflowDataSource))
                .apply(Map.of("agentUuid", "agent-uuid-1"));

        assertThat(outputResponse).isNotNull();
        assertThat(outputResponse.outputSchema()).isInstanceOf(Property.ObjectProperty.class);
    }

    @Test
    void testOutputIsNullWithoutAnAgentOrWhenAgentsAreNotAvailable() throws Exception {
        assertThat(
            outputFunction(new CallAiAgentTaskDispatcherDefinitionFactory(callableAiAgentDataSource, subflowDataSource))
                .apply(Map.of())).isNull();
        assertThat(
            outputFunction(new CallAiAgentTaskDispatcherDefinitionFactory(null, subflowDataSource))
                .apply(Map.of("agentUuid", "agent-uuid-1"))).isNull();
    }

    private static OptionsFunction agentOptionsFunction(CallAiAgentTaskDispatcherDefinitionFactory factory) {
        Property.StringProperty agentProperty = (Property.StringProperty) factory.getDefinition()
            .getProperties()
            .orElseThrow()
            .getFirst();

        return agentProperty.getOptionsFunction()
            .orElseThrow();
    }

    private static OutputFunction outputFunction(CallAiAgentTaskDispatcherDefinitionFactory factory) {
        return factory.getDefinition()
            .getOutputDefinition()
            .flatMap(OutputDefinition::getOutput)
            .map(function -> (OutputFunction) function)
            .orElseThrow();
    }
}
```

`Property.StringProperty.getOptionsFunction()` returns `Optional<TaskDispatcherDefinition.OptionsFunction>`; the output chain is the one `SubflowTaskDispatcherDefinitionFactoryTest.getOutputFunction` uses. `TaskDispatcherDefinition` is then only needed for the `OptionsFunction`/`OutputFunction` nested-type imports — drop the bare `TaskDispatcherDefinition` import if the compiler reports it unused.

- [ ] **Step 6: Write the definition factory**

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

package com.bytechef.task.dispatcher.callaiagent;

import static com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl.option;
import static com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl.string;
import static com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl.taskDispatcher;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.AGENT_UUID;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.CALL_AI_AGENT;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.CONVERSATION_ID;
import static com.bytechef.task.dispatcher.callaiagent.constant.CallAiAgentTaskDispatcherConstants.MESSAGE;

import com.bytechef.commons.util.MapUtils;
import com.bytechef.definition.BaseOutputDefinition.OutputResponse;
import com.bytechef.definition.BaseProperty;
import com.bytechef.platform.workflow.task.dispatcher.TaskDispatcherDefinitionFactory;
import com.bytechef.platform.workflow.task.dispatcher.definition.Property;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource.ResolvedAiAgent;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowDataSource;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
public class CallAiAgentTaskDispatcherDefinitionFactory implements TaskDispatcherDefinitionFactory {

    private final TaskDispatcherDefinition taskDispatcherDefinition;

    public CallAiAgentTaskDispatcherDefinitionFactory(
        @Nullable CallableAiAgentDataSource callableAiAgentDataSource, SubflowDataSource subflowDataSource) {

        this.taskDispatcherDefinition = taskDispatcher(CALL_AI_AGENT)
            .title("Call AI Agent")
            .description("Sends a message to a published AI agent and continues with its reply.")
            .icon("path:assets/callAiAgent.svg")
            .properties(
                string(AGENT_UUID)
                    .label("Agent")
                    .description("The published agent to call.")
                    .optionsFunction(search -> getAgentOptions(callableAiAgentDataSource, search))
                    .required(true),
                string(MESSAGE)
                    .label("Message")
                    .description("The message sent to the agent.")
                    .controlType(Property.ControlType.TEXT_AREA)
                    .required(true),
                string(CONVERSATION_ID)
                    .label("Conversation ID")
                    .description(
                        "Memory-thread key passed to the agent. Left blank, the agent starts or continues whatever "
                            + "thread its own conversationId input resolves to."))
            .output(inputParameters -> output(inputParameters, callableAiAgentDataSource, subflowDataSource));
    }

    @Override
    public TaskDispatcherDefinition getDefinition() {
        return taskDispatcherDefinition;
    }

    private static List<? extends com.bytechef.platform.workflow.task.dispatcher.definition.Option<String>>
        getAgentOptions(@Nullable CallableAiAgentDataSource callableAiAgentDataSource, String search) {

        if (callableAiAgentDataSource == null) {
            return List.of();
        }

        return callableAiAgentDataSource.getCallableAgents(search)
            .stream()
            .map(entry -> option(entry.title(), entry.agentUuid()))
            .toList();
    }

    private static @Nullable OutputResponse output(
        Map<String, ?> inputParameters, @Nullable CallableAiAgentDataSource callableAiAgentDataSource,
        SubflowDataSource subflowDataSource) {

        String agentUuid = MapUtils.getString(inputParameters, AGENT_UUID);

        if (callableAiAgentDataSource == null || agentUuid == null || agentUuid.isEmpty()) {
            return null;
        }

        ResolvedAiAgent resolvedAiAgent = callableAiAgentDataSource.resolveAgent(agentUuid, true);

        BaseProperty.BaseValueProperty<?> outputSchema =
            subflowDataSource.getSubWorkflowOutputSchema(resolvedAiAgent.workflowUuid());

        if (outputSchema == null) {
            return null;
        }

        return OutputResponse.of(outputSchema);
    }
}
```

Replace the fully-qualified `com.bytechef.platform.workflow.task.dispatcher.definition.Option` in `getAgentOptions`'s return type with an import (`import com.bytechef.platform.workflow.task.dispatcher.definition.Option;`) — the qualified form is only here to make the snippet unambiguous, and PMD's `UnnecessaryFullyQualifiedName` will flag it otherwise. Match the exact return type `SubflowTaskDispatcherDefinitionFactory.getWorkflowOptionsFunction` produces.

- [ ] **Step 7: Spring configuration**

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

package com.bytechef.task.dispatcher.callaiagent.config;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.atlas.coordinator.task.dispatcher.TaskDispatcherResolverFactory;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource;
import com.bytechef.platform.workflow.task.dispatcher.subflow.ChildJobPrincipalFactory;
import com.bytechef.platform.workflow.task.dispatcher.subflow.SubflowResolver;
import com.bytechef.task.dispatcher.callaiagent.CallAiAgentTaskDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnCoordinator
public class CallAiAgentTaskDispatcherConfiguration {

    @Bean("callAiAgentTaskDispatcherResolverFactory_v1")
    TaskDispatcherResolverFactory callAiAgentTaskDispatcherResolverFactory(
        ChildJobPrincipalFactory childJobPrincipalFactory,
        ObjectProvider<CallableAiAgentDataSource> callableAiAgentDataSourceProvider, JobService jobService,
        SubflowResolver subflowResolver) {

        return (taskDispatcher) -> new CallAiAgentTaskDispatcher(
            childJobPrincipalFactory, callableAiAgentDataSourceProvider.getIfAvailable(), jobService,
            subflowResolver);
    }
}
```

The definition factory is a plain `@Component`, so it also needs `CallableAiAgentDataSource` to be optional. Change its constructor parameter to `ObjectProvider<CallableAiAgentDataSource> callableAiAgentDataSourceProvider` and call `callableAiAgentDataSourceProvider.getIfAvailable()` once at the top of the constructor into a local `CallableAiAgentDataSource callableAiAgentDataSource`, keeping the two tests' direct-construction shape working by adding a second, package-private constructor `CallAiAgentTaskDispatcherDefinitionFactory(@Nullable CallableAiAgentDataSource, SubflowDataSource)` that the public one delegates to. The tests use the package-private one.

- [ ] **Step 8: Run the module — expect the two-run snapshot regeneration**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:call-ai-agent:spotlessApply :server:libs:modules:task-dispatchers:call-ai-agent:test > $S/t2-run1.log 2>&1
echo "EXIT=$?"
grep -E "NullPointerException: url|FAILED|tests completed" $S/t2-run1.log | head -5
ls server/libs/modules/task-dispatchers/call-ai-agent/src/test/resources/definition/
```

Expected first run: `EXIT=1`, `testGetTaskDispatcherDefinition` FAILED with `NullPointerException: url`, and `callAiAgent_v1.json` now exists under `src/test/resources/definition/`. Every other test passes. If any other test fails, fix it before the second run.

```bash
./gradlew :server:libs:modules:task-dispatchers:call-ai-agent:check --continue > $S/t2-run2.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/t2-run2.log
```

Expected second run: `EXIT=0`, `BUILD SUCCESSFUL`. Open `callAiAgent_v1.json` and confirm it names three properties in order `agentUuid`, `message`, `conversationId`, with `agentUuid` and `message` required.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts server/libs/modules/task-dispatchers/call-ai-agent
git commit settings.gradle.kts server/libs/modules/task-dispatchers/call-ai-agent \
  -m "Add the callAiAgent task dispatcher" \
  -m "callAiAgent/v1 is the deterministic counterpart of the workflow/v1/callAiAgent tool: a Flows-tab
node that sends a message to a published agent and continues with its reply. It resolves the picked
agentUuid to the agent's published workflow and launches it as a real child job through the launch
path shared with subflow/v1, so completion, failure and cancellation propagate through
SubflowJobStatusEventListener like any other nested dispatcher and no completion handler is needed.

The inputs are the fixed {message, conversationId} contract every agent-generated workflow pins on
its workflowCall channel, so there is nothing to look up dynamically per agent.

CallableAiAgentDataSource is injected through ObjectProvider and checked at dispatch rather than
required at boot. The two literal dispatcher lists in WorkflowTestConfiguration and
WebhookConfiguration must carry every dispatcher on the classpath, and their modules ship in apps
that do not assemble agents; requiring the bean there would break those contexts.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Fan-out integration test — two agents in parallel, both suspend, both resume

**Files:**
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/java/com/bytechef/task/dispatcher/callaiagent/CallAiAgentFanOutIntTest.java`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/resources/workflows/call-ai-agent-fan-out-parent.yaml`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/resources/workflows/call-ai-agent-child-alpha.yaml`
- Create: `server/libs/modules/task-dispatchers/call-ai-agent/src/test/resources/workflows/call-ai-agent-child-beta.yaml`

**Interfaces:**
- Consumes: `CallAiAgentTaskDispatcher(ChildJobPrincipalFactory, CallableAiAgentDataSource, JobService, SubflowResolver)` from Task 2; `ParallelTaskDispatcher(ContextService, CounterService, ApplicationEventPublisher, TaskDispatcher<? super Task>, TaskExecutionService, TaskFileStorage)`; `ParallelTaskCompletionHandler(CounterService, TaskCompletionHandler, TaskExecutionService)`; `CounterServiceImpl(CounterRepository)` with `InMemoryCounterRepository`; `SubflowJobStatusEventListener`; `SuspendTaskCompletionHandler`; `SuspendTaskDispatcherPreSendProcessor`; and the hand-wired coordinator shape from `SubflowResumeIntTest` in the `subflow` module.
- Produces: nothing downstream; this is the proof.

- [ ] **Step 1: Fixtures**

`call-ai-agent-child-alpha.yaml`:

```yaml
---
label: "Agent alpha"
outputs:
- name: "reply"
  value: "${afterResume}"
tasks:
- name: "think"
  type: "var/v1/set"
  parameters:
    value: "alpha is thinking"
- name: "askSubAgent"
  type: "suspend/v1"
- name: "afterResume"
  type: "var/v1/set"
  parameters:
    value: "alpha replied"
```

`call-ai-agent-child-beta.yaml`: identical with `alpha` → `beta` in the label and both values.

`call-ai-agent-fan-out-parent.yaml`:

```yaml
---
label: "Fan out to two agents"
outputs:
- name: "alphaReply"
  value: "${callAlpha.reply}"
- name: "betaReply"
  value: "${callBeta.reply}"
tasks:
- name: "fanOut"
  type: "parallel/v1"
  parameters:
    tasks:
    - name: "callAlpha"
      type: "callAiAgent/v1"
      parameters:
        agentUuid: "alpha-agent"
        message: "go"
    - name: "callBeta"
      type: "callAiAgent/v1"
      parameters:
        agentUuid: "beta-agent"
        message: "go"
```

Check `ParallelTaskDispatcher.doDispatch` for the parameter key it reads (`TASKS`) and match the fixture to it; if the parallel dispatcher publishes its children's outputs under a different path than `${callAlpha.reply}`, read `ParallelTaskCompletionHandler` and adjust the two `outputs` expressions to match how a parallel child's output is exposed to the parent context.

- [ ] **Step 2: Write the test**

Model the rig on `SubflowResumeIntTest.Rig` in `server/libs/modules/task-dispatchers/subflow/src/test/java/com/bytechef/task/dispatcher/subflow/SubflowResumeIntTest.java` — read that file first and copy its coordinator wiring (task handler resolver chain, `TaskWorker` with `SuspendTaskExecutionPostOutputProcessor`, `TaskDispatcherChain`, `TaskCompletionHandlerChain`, `JobExecutor`, `TaskCoordinator`, the `JOB_RESUME_EVENTS` route, its repository override that clears `handled` on save, and its `awaitJobStatus`/`awaitJobOutputs` pollers). Then apply these deltas:

1. Add `CounterService counterService = new CounterServiceImpl(new InMemoryCounterRepository());`.
2. Register two dispatcher resolver factories instead of one: `taskDispatcher -> new ParallelTaskDispatcher(contextService, counterService, eventPublisher, taskDispatcher, taskExecutionService, taskFileStorage)` and `taskDispatcher -> new CallAiAgentTaskDispatcher(childJobPrincipalFactory, callableAiAgentDataSource, jobService, subflowResolver)`.
3. Register two completion handler factories: the `SuspendTaskCompletionHandler` one, plus `(taskCompletionHandler, taskDispatcher) -> new ParallelTaskCompletionHandler(counterService, taskCompletionHandler, taskExecutionService)`.
4. Stub the two data sources as lambdas / small anonymous classes:

```java
CallableAiAgentDataSource callableAiAgentDataSource = new CallableAiAgentDataSource() {

    @Override
    public List<CallableAiAgentEntry> getCallableAgents(String search) {
        return List.of();
    }

    @Override
    public ResolvedAiAgent resolveAgent(String agentUuid, boolean editorEnvironment) {
        return new ResolvedAiAgent(agentUuid + "-workflow", agentUuid, null);
    }
};

SubflowResolver subflowResolver = (workflowUuid, triggerName, editorEnvironment) -> new SubflowResolver.Subflow(
    encodeWorkflowId(workflowUuid.equals("alpha-agent-workflow") ? "call-ai-agent-child-alpha" : "call-ai-agent-child-beta"),
    "workflowCall");
```

5. Record every child job id the `ChildJobPrincipalFactory` creates in a `CopyOnWriteArrayList<Long>` instead of a single `AtomicReference`, so the test can wait for two.

The test method:

```java
    @Test
    void testTwoAgentsRunConcurrentlyAndBothRepliesReachTheParent() throws InterruptedException {
        Rig rig = new Rig();

        long parentJobId = rig.startParent();

        List<Long> childJobIds = rig.awaitChildJobsLaunched(2);

        for (long childJobId : childJobIds) {
            rig.awaitJobStatus(childJobId, Job.Status.STOPPED);
        }

        for (long childJobId : childJobIds) {
            assertEquals(Job.Status.STOPPED, rig.jobStatus(childJobId));
            assertNotNull(rig.parentTaskExecutionId(childJobId));
        }

        assertEquals(Job.Status.STARTED, rig.jobStatus(parentJobId));

        for (long childJobId : childJobIds) {
            rig.resume(childJobId);
        }

        for (long childJobId : childJobIds) {
            rig.awaitJobStatus(childJobId, Job.Status.COMPLETED);
        }

        rig.awaitJobStatus(parentJobId, Job.Status.COMPLETED);

        Map<String, ?> parentOutputs = rig.awaitJobOutputs(parentJobId);

        assertEquals("alpha replied", parentOutputs.get("alphaReply"));
        assertEquals("beta replied", parentOutputs.get("betaReply"));
    }
```

The second `for` loop is the concurrency assertion and must read all children's statuses *after* all have been observed STOPPED, without resuming any in between — that is the moment both are alive at once, which a serialised execution can never produce (the first child would have to complete before the second was even launched). Do not weaken it to "both eventually completed".

`rig.resume(childJobId)` publishes `new ResumeJobEvent(childJobId, taskExecutionResumeId, Map.of("approved", true))` onto the rig's broker, exactly as `SubflowResumeIntTest`'s facade-driven test does, reading `taskExecutionResumeId` from the child job's `MetadataConstants.TASK_EXECUTION_RESUME_ID`. `rig.parentTaskExecutionId(childJobId)` returns `jobService.getJob(childJobId).getParentTaskExecutionId()`.

- [ ] **Step 3: Run it; make the intended red before trusting green**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:call-ai-agent:testIntegration --tests '*CallAiAgentFanOutIntTest*' > $S/t3-run.log 2>&1
echo "EXIT=$?"
grep -E "FAILED|PASSED|tests completed|BUILD" $S/t3-run.log | head
```

This test is written after its production code exists, so a first green proves little on its own. Before accepting it, force the intended red: in `CallAiAgentTaskDispatcher.dispatch`, temporarily replace the `SubflowChildJobLauncher.launch(...)` call with `throw new IllegalStateException("neutered");`, rerun, and confirm the test fails at `awaitChildJobsLaunched` (no child ever appears). Restore the line with `cp` from a copy you took first — never `git checkout`/`git restore`. Then, separately, temporarily change the parent fixture's `parallel/v1` to run the two calls sequentially (a plain two-task list at the top level) and confirm the concurrency assertion fails: the first child completes before the second is launched, so the "both STOPPED at once" loop can never be satisfied. Restore the fixture. Only after both reds pass is the green meaningful.

If the fan-out does NOT hold — both children never coexist as STOPPED — **stop and report** before doing any further task. The spec's *Fan-out* section is its strongest argument for this node and says this outcome means the design should be revisited before the rest is built.

- [ ] **Step 4: Module check and commit**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:modules:task-dispatchers:call-ai-agent:spotlessApply :server:libs:modules:task-dispatchers:call-ai-agent:check --continue > $S/t3-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/t3-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`.

```bash
git add server/libs/modules/task-dispatchers/call-ai-agent/src/test
git commit server/libs/modules/task-dispatchers/call-ai-agent/src/test \
  -m "Prove callAiAgent fans out to concurrent agents and resumes them" \
  -m "Two callAiAgent/v1 nodes under parallel/v1 target two agents whose workflows each suspend
before replying. Both children are observed STOPPED at the same moment - the moment that cannot
exist under serial execution - each carrying its own parentTaskExecutionId, while the parent stays
STARTED. Both are then genuinely resumed through the coordinator's JOB_RESUME_EVENTS route, run to
completion on their own, and both replies reach the parent's outputs.

The rig hand-wires the coordinator graph the way SubflowResumeIntTest does, because
JobSyncExecutor does not subscribe JOB_RESUME_EVENTS and so cannot drive a resume. The children's
suspend step stands in for a sub-agent tool call, so this single test also covers the spec's
'agent that suspends once and replies' scenario. Verified red twice before trusting green: with the
launch neutered no child appears, and with the calls serialised the two are never alive together.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Register the dispatcher everywhere the chain is assembled

**Files:**
- Modify: `server/libs/platform/platform-workflow/platform-workflow-test/platform-workflow-test-service/src/main/java/com/bytechef/platform/workflow/test/config/WorkflowTestConfiguration.java` (the `List.of(...)` ending with `WaitForApprovalTaskDispatcher`, ~line 318, plus its enclosing `@Bean` method's parameters and imports)
- Modify: `server/libs/platform/platform-webhook/platform-webhook-impl/src/main/java/com/bytechef/platform/webhook/executor/config/WebhookConfiguration.java` (same, ~line 256)
- Modify: `server/libs/platform/platform-workflow/platform-workflow-test/platform-workflow-test-service/build.gradle.kts:35`, `server/libs/platform/platform-webhook/platform-webhook-impl/build.gradle.kts:30`, `server/apps/server-app/build.gradle.kts:272`, `server/ee/apps/coordinator-app/build.gradle.kts:76`, `server/ee/apps/runtime-job-app/build.gradle.kts:169`, `server/libs/automation/automation-ai/automation-ai-mcp-server/build.gradle.kts:36`, `server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/build.gradle.kts:34`

**Interfaces:**
- Consumes: `CallAiAgentTaskDispatcher(ChildJobPrincipalFactory, @Nullable CallableAiAgentDataSource, JobService, SubflowResolver)`.
- Produces: `WorkflowTestConfigurationTest` and `WebhookConfigurationTest` pass with the new type on the classpath.

- [ ] **Step 1: Gradle — add the dependency beside `subflow` in all seven files**

In each of the seven `build.gradle.kts` files, directly after the line `implementation(project(":server:libs:modules:task-dispatchers:subflow"))`, add:

```kotlin
    implementation(project(":server:libs:modules:task-dispatchers:call-ai-agent"))
```

Keep the surrounding alphabetical order if the file sorts its `task-dispatchers:*` lines (most do — `call-ai-agent` then belongs between `branch` and `condition`, not after `subflow`; place it where the file's own ordering puts it).

- [ ] **Step 2: Run the two config tests to see them fail**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:platform:platform-workflow:platform-workflow-test:platform-workflow-test-service:test --tests '*WorkflowTestConfigurationTest*' \
  :server:libs:platform:platform-webhook:platform-webhook-impl:test --tests '*WebhookConfigurationTest*' --continue > $S/t4-red.log 2>&1
echo "EXIT=$?"
grep -E "FAILED|callAiAgent|CallAiAgent" $S/t4-red.log | head -6
```

Expected: `EXIT=1`, both tests FAILED naming `CallAiAgentTaskDispatcher` as a dispatcher on the classpath missing from the list. This is the three-place trap firing on purpose.

- [ ] **Step 3: Add the dispatcher to both literal lists**

In both files, the `@Bean` method that returns the `List.of(...)` of resolver factories already takes `childJobPrincipalFactory`, `jobService` and `subflowResolver`. Add a parameter `ObjectProvider<CallableAiAgentDataSource> callableAiAgentDataSourceProvider` to that method (imports: `org.springframework.beans.factory.ObjectProvider`, `com.bytechef.platform.workflow.task.dispatcher.subflow.CallableAiAgentDataSource`, `com.bytechef.task.dispatcher.callaiagent.CallAiAgentTaskDispatcher`), and insert this entry in the list directly after the `BranchTaskDispatcher` entry so the list stays alphabetical:

```java
            (taskDispatcher) -> new CallAiAgentTaskDispatcher(
                childJobPrincipalFactory, callableAiAgentDataSourceProvider.getIfAvailable(), jobService,
                subflowResolver),
```

- [ ] **Step 4: Run the config tests and both modules' checks**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:libs:platform:platform-workflow:platform-workflow-test:platform-workflow-test-service:spotlessApply \
  :server:libs:platform:platform-webhook:platform-webhook-impl:spotlessApply \
  :server:libs:platform:platform-workflow:platform-workflow-test:platform-workflow-test-service:check \
  :server:libs:platform:platform-webhook:platform-webhook-impl:check --continue > $S/t4-green.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|BUILD" $S/t4-green.log
```

Expected: `EXIT=0`, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Boot the apps that gained the dependency**

Each Spring app has a `testContextLoads`-style IntTest that is the only thing that proves its context still assembles. Run them for the apps touched:

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew :server:apps:server-app:testIntegration :server:ee:apps:coordinator-app:testIntegration :server:ee:apps:runtime-job-app:testIntegration --continue > $S/t4-apps.log 2>&1
echo "EXIT=$?"
grep -E "^> Task .* FAILED|No qualifying bean|BUILD" $S/t4-apps.log | head
```

Expected: `EXIT=0`. A `No qualifying bean of type CallableAiAgentDataSource` here would mean the `ObjectProvider` wiring was not applied at some site — find it with `grep -rn "new CallAiAgentTaskDispatcher(" server/ --include='*.java' | grep -v worktrees | grep -v /src/test/` and fix it; do not make the bean required.

- [ ] **Step 6: Commit**

```bash
git commit \
  server/libs/platform/platform-workflow/platform-workflow-test/platform-workflow-test-service/src/main/java/com/bytechef/platform/workflow/test/config/WorkflowTestConfiguration.java \
  server/libs/platform/platform-webhook/platform-webhook-impl/src/main/java/com/bytechef/platform/webhook/executor/config/WebhookConfiguration.java \
  server/libs/platform/platform-workflow/platform-workflow-test/platform-workflow-test-service/build.gradle.kts \
  server/libs/platform/platform-webhook/platform-webhook-impl/build.gradle.kts \
  server/apps/server-app/build.gradle.kts \
  server/ee/apps/coordinator-app/build.gradle.kts \
  server/ee/apps/runtime-job-app/build.gradle.kts \
  server/libs/automation/automation-ai/automation-ai-mcp-server/build.gradle.kts \
  server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/build.gradle.kts \
  -m "Register the callAiAgent dispatcher at every chain-assembly site" \
  -m "A task dispatcher's own @Bean covers only the production coordinator. The editor's Test button
(WorkflowTestConfiguration) and the webhook executor (WebhookConfiguration) each build their
resolver chain from a literal List.of, and an unregistered type there falls through to the worker
and surfaces as 'Component definition with name callAiAgent not found'. Both lists gain the entry,
guarded by the same classpath-scanning tests that fail when a dispatcher is missing from them.

The dependency is added wherever task-dispatchers:subflow is already declared, and the two lists
take CallableAiAgentDataSource through ObjectProvider so apps that do not assemble agents still
boot.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Client — enumerate the new dispatcher

**Files:**
- Modify: `client/src/shared/types.ts:120-122` (beside `SubflowDataType`), `:292` (beside `subflowData?:`), `:470` (beside `subflowId?:`)
- Modify: `client/src/shared/constants.tsx:174-186` (`TASK_DISPATCHER_NAMES`), `:188` (`CHILDLESS_TASK_DISPATCHER_NAMES`), `:245-258` (`TASK_DISPATCHER_DATA_KEY_MAP`)
- Modify: `client/src/pages/platform/workflow-editor/utils/taskDispatcherConfig.tsx:63-66` (the `type === 'subflow'` branch) and `:673-693` (the `subflow:` config entry)
- Modify: `client/src/pages/platform/workflow-editor/utils/taskDispatcherConfig.test.ts`

**Interfaces:**
- Consumes: nothing from the server tasks; the node type string `callAiAgent`.
- Produces: the editor can place, position and persist a `callAiAgent` node as a childless dispatcher.

- [ ] **Step 1: Write the failing test**

Append to `taskDispatcherConfig.test.ts`, following the file's existing `describe('TASK_DISPATCHER_CONFIG.graph', …)` shape:

```ts
describe('TASK_DISPATCHER_CONFIG.callAiAgent', () => {
    it('should read callAiAgentId off the context', () => {
        expect(TASK_DISPATCHER_CONFIG.callAiAgent.getDispatcherId({callAiAgentId: 'agent-1', taskDispatcherId: 'agent-1'})).toBe(
            'agent-1'
        );
    });

    it('should have no subtasks', () => {
        expect(TASK_DISPATCHER_CONFIG.callAiAgent.getSubtasks({} as never)).toEqual([]);
    });

    it('should parse the dispatcher id from a placeholder', () => {
        expect(TASK_DISPATCHER_CONFIG.callAiAgent.extractContextFromPlaceholder('agent-1-0')).toEqual({
            index: 0,
            taskDispatcherId: 'agent-1',
        });
    });
});
```

Match the import name the file already uses for the config object and the exact `getSubtasks` argument shape the `graph` tests pass; if `getSubtasks` takes a task object, pass `{} as WorkflowTask` with the type the file imports.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/taskDispatcherConfig.test.ts 2>&1 | tail -12
```

Expected: the three new tests fail (`callAiAgent` is undefined on the config object).

- [ ] **Step 3: Types**

In `client/src/shared/types.ts`, beside `SubflowDataType` add (keeping the surrounding type declarations in their existing alphabetical order):

```ts
type CallAiAgentDataType = {
    callAiAgentId: string;
};
```

In the node-data type where `subflowData?: SubflowDataType;` lives, add `callAiAgentData?: CallAiAgentDataType;` in alphabetical position among its siblings. In the context type where `subflowId?: string;` lives, add `callAiAgentId?: string;` in alphabetical position.

- [ ] **Step 4: Constants**

In `TASK_DISPATCHER_NAMES`, insert `'callAiAgent',` between `'branch',` and `'condition',`. Change `CHILDLESS_TASK_DISPATCHER_NAMES` to `['callAiAgent', 'loopBreak', 'subflow', 'terminate']`. In `TASK_DISPATCHER_DATA_KEY_MAP`, insert `callAiAgent: 'callAiAgentData',` between the `branch` and `condition` entries.

- [ ] **Step 5: Config**

In `buildGenericNodeData`, before the `} else if (type === 'condition') {` branch (or wherever keeps the chain alphabetical), add:

```ts
            } else if (type === 'callAiAgent') {
                newNodeData.callAiAgentData = {
                    callAiAgentId: taskDispatcherId,
                };
```

In the config object, after the `branch:` entry and before `condition:`, add an entry shaped exactly like `subflow:`'s with the names swapped:

```ts
    callAiAgent: {
        buildNodeData: ({baseNodeData, taskDispatcherContext, taskDispatcherId}: BuildNodeDataType): NodeDataType =>
            buildGenericNodeData(baseNodeData, taskDispatcherContext, taskDispatcherId, 'callAiAgent'),

        contextIdentifier: 'callAiAgentId',
        dataKey: 'callAiAgentData',
        extractContextFromPlaceholder: (placeholderId: string): TaskDispatcherContextType => {
            const parts = placeholderId.split('-');
            const index = parseInt(parts[parts.length - 1] || '-1');

            return {index, taskDispatcherId: parts[0]};
        },
        getDispatcherId: (context: TaskDispatcherContextType) => context.callAiAgentId,
        getInitialParameters: (properties: Array<PropertyAllType>) => ({
            ...getParametersWithDefaultValues({properties}),
        }),
        getSubtasks: () => [],
        getTask: getTaskDispatcherTask,
        initializeParameters: () => ({}),
        updateTaskParameters: ({task}: UpdateTaskParametersType): WorkflowTask => task,
    },
```

Copy the `subflow:` entry's exact body from the file at the time you edit it rather than from this snippet if they differ — the two must stay identical apart from the names.

- [ ] **Step 6: Verify the client**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/taskDispatcherConfig.test.ts 2>&1 | tail -6 && npx eslint src/shared/types.ts src/shared/constants.tsx src/pages/platform/workflow-editor/utils/taskDispatcherConfig.tsx src/pages/platform/workflow-editor/utils/taskDispatcherConfig.test.ts && npm run typecheck && echo "CLIENT OK"
```

Expected: tests pass, ESLint clean (watch `sort-keys` — it is not auto-fixable), typecheck clean, `CLIENT OK`. Node here is v24.15.0, which this repo needs (Node 26 fake-fails on jsdom).

- [ ] **Step 7: Commit**

```bash
git commit \
  client/src/shared/types.ts \
  client/src/shared/constants.tsx \
  client/src/pages/platform/workflow-editor/utils/taskDispatcherConfig.tsx \
  client/src/pages/platform/workflow-editor/utils/taskDispatcherConfig.test.ts \
  -m "client - Enumerate the callAiAgent task dispatcher" \
  -m "The editor lists task dispatchers by name in three places - the names list, the childless list
and the data-key map - and builds each node's data through a per-dispatcher config entry. callAiAgent
joins all four shaped exactly like subflow: a childless leaf node. projectDeploymentDialog-utils is
deliberately untouched; it folds a sub-workflow's connections into the parent deployment, and an
agent's connections belong to the agent's own deployment.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Whole-branch verification

**Files:** none modified unless a formatter changes something.

- [ ] **Step 1: Server**

```bash
S=/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f511ab9a-d314-454b-9e49-c062f392423f/scratchpad
./gradlew spotlessApply > $S/final-spotless.log 2>&1; echo "SPOTLESS EXIT=$?"
./gradlew \
  :server:libs:modules:task-dispatchers:subflow:check \
  :server:libs:modules:task-dispatchers:call-ai-agent:check \
  :server:libs:platform:platform-workflow:platform-workflow-test:platform-workflow-test-service:check \
  :server:libs:platform:platform-webhook:platform-webhook-impl:check \
  :server:apps:server-app:testIntegration \
  --continue > $S/final-check.log 2>&1
echo "CHECK EXIT=$?"
grep -E "^> Task .* FAILED" $S/final-check.log
```

Expected: both exits `0`, no `FAILED` lines.

- [ ] **Step 2: Client**

```bash
cd client && npm run check 2>&1 | tail -15
```

Expected: lint, typecheck and tests pass. Pass `timeout: 600000`.

- [ ] **Step 3: If `spotlessApply` changed a file in this plan's File Structure, commit only that file**

```bash
git status --porcelain -- server/libs/modules/task-dispatchers server/libs/platform/platform-workflow/platform-workflow-test server/libs/platform/platform-webhook client/src/shared client/src/pages/platform/workflow-editor/utils
```

Anything listed outside the File Structure table is the user's own in-progress work — do not touch it.

---

## Spec coverage check

| Spec section | Task |
|---|---|
| Module: four classes, `settings.gradle.kts`, icon | Task 2 |
| Definition: three inputs, agent picker, output from the agent workflow's schema | Task 2 (Deviation 3 for the `editorEnvironment` flag) |
| Dispatch: resolve agent → subflow → real child job; resolution failures fail the task | Task 2 (`testDispatchFailsTheTaskWhenTheAgentCannotBeResolved`) |
| Shared child-job launch extracted from `SubflowTaskDispatcher` | Task 1 (Deviation 1: public) |
| Caller-principal connection semantics kept | Inherited by using `createChildJob`; no task changes it |
| Registration — four sites | Task 4 (Deviation 2: `ObjectProvider`) |
| Client — three files, deployment-dialog untouched | Task 5 |
| Testing — dispatcher unit test, snapshot, fan-out with simultaneous-alive assertion, config tests | Tasks 2, 3, 4 |
| Testing — "agent whose `aiAgent` node suspends once and replies" | Task 3 (Deviation 4: the fan-out children are that agent) |
| Risk — fan-out claim is reasoned, write the test early | Task 3 runs before registration and client; its Step 3 says to stop if concurrency does not hold |
| Non-goals — tool unchanged, no depth cap, no streaming | No task touches them |
