# Notification Channel — Phase 2 Implementation Plan (Task-Level Events)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Prerequisite:** Phase 1 (`2026-05-31-notification-webhook-channel-phase-1.md`) must be merged first — this plan reuses the enriched `NotificationHandlerContext`, the `WebhookNotificationHandler.getPayload` contract, and the implemented `WebhookNotificationSender`.

**Goal:** Broaden the notification event model from job-level to task-level by emitting `TaskCompletedApplicationEvent` / `TaskFailedApplicationEvent` from the atlas worker, appending `TASK_STARTED/COMPLETED/FAILED` to the notification event enum, and adding a task-status listener plus task email + webhook handlers — so users can be notified (by email or signed webhook) when individual workflow steps start, complete, or fail.

**Architecture:** Two new self-contained `ApplicationEvent`s are published from `TaskWorker` alongside the existing `TaskStartedApplicationEvent`. A new `NotificationTaskApplicationEventListener` (platform-coordinator) maps all three task events to `NotificationEvent.Type` values, builds an enriched context, and runs the same resolve-sender/resolve-handler dispatch as the job listener. Task-specific email and webhook handlers produce the per-event content/payload.

**Tech Stack:** Java 25, Spring Boot 4, atlas event bus (`ApplicationEventPublisher` → `TaskCoordinator` → `ApplicationEventListener` beans), Spring `MessageSource` (email templates), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-31-notification-webhook-channel-design.md` §5.7 (including the 2026-05-31 atlas-constraint note).

**Design decisions baked in:**
- New `TaskCompletedApplicationEvent` / `TaskFailedApplicationEvent` are **self-contained** (carry `jobId`, `taskExecutionId`, `taskName`, and — failed only — `errorMessage`) → listener needs no DB lookup, no persistence-ordering dependency.
- TASK_STARTED reuses the existing `TaskStartedApplicationEvent` (ids only); the listener resolves the task name via `TaskExecutionService.getTaskExecution(id)`.
- TASK_FAILED covers **task-execution** failures via `TaskWorker.handleException` only. Coordinator-side dispatch/completion-handler failures are out of scope (documented limitation).
- Enum values are **appended** (ordinals 6/7/8) to preserve INT ordinal stability.
- **Webhook is EE-only** (decision D1): the task **email** handler stays in CE (`platform-coordinator`);
  the task **webhook** handler lands in the EE module from Phase 1
  (`server/ee/libs/platform/platform-notification/platform-notification-webhook`, package
  `com.bytechef.ee.platform.notification.webhook`, Enterprise license header + `@version ee` +
  `@ConditionalOnEEVersion`). The task listener (CE) skips notifications whose sender bean is absent.

---

## File Structure

**`atlas-coordinator-api`** (new application events):
- Create `event/TaskCompletedApplicationEvent.java`.
- Create `event/TaskFailedApplicationEvent.java`.

**`atlas-worker-impl`** (emit the events):
- Modify `TaskWorker.java` — publish the two new events at the existing complete/error sites.

**`platform-notification-api`** (enum):
- Modify `domain/NotificationEvent.java` — append TASK_* types.

**`platform-notification-service`** (enum seed migration):
- Create Liquibase changeset seeding the three TASK rows.
- Modify the master changelog include list.

**`platform-coordinator`** (CE — listener + email handler):
- Create `event/listener/NotificationTaskApplicationEventListener.java` (with null-sender guard).
- Create `notification/TaskStatusEmailNotificationHandler.java`.
- Modify `config/PlatformCoordinatorConfiguration.java` — register the listener bean.

**`platform-notification-webhook`** (EE module from Phase 1):
- Create `TaskStatusWebhookNotificationHandler.java` (`@ConditionalOnEEVersion`, Enterprise header).

**`messages-config`**:
- Modify `messages.properties` — add `email.TASK_*` subject/content.

---

## Task 1: New `TaskCompletedApplicationEvent`

**Files:**
- Create: `server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/main/java/com/bytechef/atlas/coordinator/event/TaskCompletedApplicationEvent.java`
- Test: `server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/test/java/com/bytechef/atlas/coordinator/event/TaskCompletedApplicationEventTest.java`

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

package com.bytechef.atlas.coordinator.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.atlas.coordinator.message.route.TaskCoordinatorMessageRoute;
import org.junit.jupiter.api.Test;

class TaskCompletedApplicationEventTest {

    @Test
    void testEventCarriesTaskData() {
        TaskCompletedApplicationEvent event = new TaskCompletedApplicationEvent(1L, 2L, "httpRequest");

        assertThat(event.getJobId()).isEqualTo(1L);
        assertThat(event.getTaskExecutionId()).isEqualTo(2L);
        assertThat(event.getTaskName()).isEqualTo("httpRequest");
        assertThat(event.getRoute()).isEqualTo(TaskCoordinatorMessageRoute.APPLICATION_EVENTS);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:atlas:atlas-coordinator:atlas-coordinator-api:test --tests "com.bytechef.atlas.coordinator.event.TaskCompletedApplicationEventTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the event (mirrors `TaskStartedApplicationEvent`)**

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

package com.bytechef.atlas.coordinator.event;

import com.bytechef.atlas.coordinator.message.route.TaskCoordinatorMessageRoute;
import org.jspecify.annotations.Nullable;

/**
 * @author Ivica Cardic
 */
public class TaskCompletedApplicationEvent extends AbstractEvent implements ApplicationEvent {

    public static final String TASK_COMPLETED = "task.completed";

    private long jobId;
    private long taskExecutionId;
    private @Nullable String taskName;

    private TaskCompletedApplicationEvent() {
    }

    public TaskCompletedApplicationEvent(long jobId, long taskExecutionId, @Nullable String taskName) {
        super(TaskCoordinatorMessageRoute.APPLICATION_EVENTS);

        this.jobId = jobId;
        this.taskExecutionId = taskExecutionId;
        this.taskName = taskName;
    }

    public long getJobId() {
        return jobId;
    }

    public long getTaskExecutionId() {
        return taskExecutionId;
    }

    public @Nullable String getTaskName() {
        return taskName;
    }

    @Override
    public String toString() {
        return "TaskCompletedApplicationEvent{" +
            "jobId=" + jobId +
            ", taskExecutionId=" + taskExecutionId +
            ", taskName='" + taskName + '\'' +
            ", createdDate=" + createDate +
            ", route=" + route +
            "} ";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:libs:atlas:atlas-coordinator:atlas-coordinator-api:test --tests "com.bytechef.atlas.coordinator.event.TaskCompletedApplicationEventTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/main/java/com/bytechef/atlas/coordinator/event/TaskCompletedApplicationEvent.java server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/test/java/com/bytechef/atlas/coordinator/event/TaskCompletedApplicationEventTest.java
git commit -m "732 Add TaskCompletedApplicationEvent"
```

---

## Task 2: New `TaskFailedApplicationEvent`

**Files:**
- Create: `server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/main/java/com/bytechef/atlas/coordinator/event/TaskFailedApplicationEvent.java`
- Test: `server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/test/java/com/bytechef/atlas/coordinator/event/TaskFailedApplicationEventTest.java`

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

package com.bytechef.atlas.coordinator.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.atlas.coordinator.message.route.TaskCoordinatorMessageRoute;
import org.junit.jupiter.api.Test;

class TaskFailedApplicationEventTest {

    @Test
    void testEventCarriesTaskDataAndError() {
        TaskFailedApplicationEvent event = new TaskFailedApplicationEvent(1L, 2L, "httpRequest", "boom");

        assertThat(event.getJobId()).isEqualTo(1L);
        assertThat(event.getTaskExecutionId()).isEqualTo(2L);
        assertThat(event.getTaskName()).isEqualTo("httpRequest");
        assertThat(event.getErrorMessage()).isEqualTo("boom");
        assertThat(event.getRoute()).isEqualTo(TaskCoordinatorMessageRoute.APPLICATION_EVENTS);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:atlas:atlas-coordinator:atlas-coordinator-api:test --tests "com.bytechef.atlas.coordinator.event.TaskFailedApplicationEventTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the event**

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

package com.bytechef.atlas.coordinator.event;

import com.bytechef.atlas.coordinator.message.route.TaskCoordinatorMessageRoute;
import org.jspecify.annotations.Nullable;

/**
 * @author Ivica Cardic
 */
public class TaskFailedApplicationEvent extends AbstractEvent implements ApplicationEvent {

    public static final String TASK_FAILED = "task.failed";

    private long jobId;
    private long taskExecutionId;
    private @Nullable String taskName;
    private @Nullable String errorMessage;

    private TaskFailedApplicationEvent() {
    }

    public TaskFailedApplicationEvent(
        long jobId, long taskExecutionId, @Nullable String taskName, @Nullable String errorMessage) {

        super(TaskCoordinatorMessageRoute.APPLICATION_EVENTS);

        this.jobId = jobId;
        this.taskExecutionId = taskExecutionId;
        this.taskName = taskName;
        this.errorMessage = errorMessage;
    }

    public long getJobId() {
        return jobId;
    }

    public long getTaskExecutionId() {
        return taskExecutionId;
    }

    public @Nullable String getTaskName() {
        return taskName;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "TaskFailedApplicationEvent{" +
            "jobId=" + jobId +
            ", taskExecutionId=" + taskExecutionId +
            ", taskName='" + taskName + '\'' +
            ", errorMessage='" + errorMessage + '\'' +
            ", createdDate=" + createDate +
            ", route=" + route +
            "} ";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:libs:atlas:atlas-coordinator:atlas-coordinator-api:test --tests "com.bytechef.atlas.coordinator.event.TaskFailedApplicationEventTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/main/java/com/bytechef/atlas/coordinator/event/TaskFailedApplicationEvent.java server/libs/atlas/atlas-coordinator/atlas-coordinator-api/src/test/java/com/bytechef/atlas/coordinator/event/TaskFailedApplicationEventTest.java
git commit -m "732 Add TaskFailedApplicationEvent"
```

---

## Task 3: Emit the new events from `TaskWorker`

**Files:**
- Modify: `server/libs/atlas/atlas-worker/atlas-worker-impl/src/main/java/com/bytechef/atlas/worker/TaskWorker.java`

- [ ] **Step 1: Add the completed event next to `TaskExecutionCompleteEvent`**

Find this block inside `onTaskExecutionEvent`:

```java
                TaskExecution completedTaskExecution = doExecuteTask(taskExecution);

                eventPublisher.publishEvent(new TaskExecutionCompleteEvent(completedTaskExecution));
```

Replace with:

```java
                TaskExecution completedTaskExecution = doExecuteTask(taskExecution);

                eventPublisher.publishEvent(new TaskExecutionCompleteEvent(completedTaskExecution));

                eventPublisher.publishEvent(
                    new TaskCompletedApplicationEvent(
                        Validate.notNull(completedTaskExecution.getJobId(), "jobId"),
                        Validate.notNull(completedTaskExecution.getId(), "id"),
                        completedTaskExecution.getName()));
```

- [ ] **Step 2: Add the failed event in `handleException`**

Find:

```java
        taskExecution.setStatus(Status.FAILED);

        eventPublisher.publishEvent(new TaskExecutionErrorEvent(taskExecution));
    }
```

Replace with:

```java
        taskExecution.setStatus(Status.FAILED);

        eventPublisher.publishEvent(new TaskExecutionErrorEvent(taskExecution));

        eventPublisher.publishEvent(
            new TaskFailedApplicationEvent(
                Validate.notNull(taskExecution.getJobId(), "jobId"),
                Validate.notNull(taskExecution.getId(), "id"), taskExecution.getName(),
                taskExecution.getError() == null ? null : taskExecution.getError()
                    .getMessage()));
    }
```

- [ ] **Step 3: Add the imports**

Add to the import block:

```java
import com.bytechef.atlas.coordinator.event.TaskCompletedApplicationEvent;
import com.bytechef.atlas.coordinator.event.TaskFailedApplicationEvent;
```

(`Validate`, `Status`, `TaskStartedApplicationEvent` are already imported.)

- [ ] **Step 4: Compile the worker module**

Run: `./gradlew :server:libs:atlas:atlas-worker:atlas-worker-impl:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add server/libs/atlas/atlas-worker/atlas-worker-impl/src/main/java/com/bytechef/atlas/worker/TaskWorker.java
git commit -m "732 Emit TaskCompleted and TaskFailed application events from TaskWorker"
```

---

## Task 4: Append TASK_* values to `NotificationEvent.Type`

**Files:**
- Modify: `server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/domain/NotificationEvent.java`
- Test: `server/libs/platform/platform-notification/platform-notification-api/src/test/java/com/bytechef/platform/notification/domain/NotificationEventTypeTest.java`

- [ ] **Step 1: Write the failing test (pins ordinals + Source.TASK resolution)**

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

package com.bytechef.platform.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.notification.domain.NotificationEvent.Source;
import com.bytechef.platform.notification.domain.NotificationEvent.Type;
import org.junit.jupiter.api.Test;

class NotificationEventTypeTest {

    @Test
    void testOrdinalsAreStable() {
        assertThat(Type.JOB_CANCELLED.ordinal()).isEqualTo(0);
        assertThat(Type.JOB_CREATED.ordinal()).isEqualTo(1);
        assertThat(Type.JOB_COMPLETED.ordinal()).isEqualTo(2);
        assertThat(Type.JOB_FAILED.ordinal()).isEqualTo(3);
        assertThat(Type.JOB_STARTED.ordinal()).isEqualTo(4);
        assertThat(Type.JOB_STOPPED.ordinal()).isEqualTo(5);
        assertThat(Type.TASK_STARTED.ordinal()).isEqualTo(6);
        assertThat(Type.TASK_COMPLETED.ordinal()).isEqualTo(7);
        assertThat(Type.TASK_FAILED.ordinal()).isEqualTo(8);
    }

    @Test
    void testOfResolvesTaskSource() {
        assertThat(Type.of(Source.TASK, "STARTED")).isEqualTo(Type.TASK_STARTED);
        assertThat(Type.of(Source.TASK, "COMPLETED")).isEqualTo(Type.TASK_COMPLETED);
        assertThat(Type.of(Source.TASK, "FAILED")).isEqualTo(Type.TASK_FAILED);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-api:test --tests "com.bytechef.platform.notification.domain.NotificationEventTypeTest"`
Expected: FAIL — `TASK_STARTED` etc. do not exist.

- [ ] **Step 3: Append the TASK values to the enum**

In `NotificationEvent.java`, replace the `Type` enum constant list:

```java
        JOB_CANCELLED(Source.JOB, "CANCELLED"), JOB_CREATED(Source.JOB, "CREATED"),
        JOB_COMPLETED(Source.JOB, "COMPLETED"), JOB_FAILED(Source.JOB, "FAILED"),
        JOB_STARTED(Source.JOB, "STARTED"), JOB_STOPPED(Source.JOB, "STOPPED");
```

with (note: comma after JOB_STOPPED, new values appended last):

```java
        JOB_CANCELLED(Source.JOB, "CANCELLED"), JOB_CREATED(Source.JOB, "CREATED"),
        JOB_COMPLETED(Source.JOB, "COMPLETED"), JOB_FAILED(Source.JOB, "FAILED"),
        JOB_STARTED(Source.JOB, "STARTED"), JOB_STOPPED(Source.JOB, "STOPPED"),
        TASK_STARTED(Source.TASK, "STARTED"), TASK_COMPLETED(Source.TASK, "COMPLETED"),
        TASK_FAILED(Source.TASK, "FAILED");
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-api:test --tests "com.bytechef.platform.notification.domain.NotificationEventTypeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/domain/NotificationEvent.java server/libs/platform/platform-notification/platform-notification-api/src/test/java/com/bytechef/platform/notification/domain/NotificationEventTypeTest.java
git commit -m "732 Append TASK event types to NotificationEvent.Type"
```

---

## Task 5: Seed the TASK notification_event rows

**Files:**
- Create: `server/libs/platform/platform-notification/platform-notification-service/src/main/resources/config/liquibase/changelog/platform/notification/1769100000_insert_into_notification_event_task.xml`
- Modify: the changelog that `<include>`s the notification changelogs (find in Step 1)

- [ ] **Step 1: Find the aggregating changelog and the existing include order**

Run: `grep -rln "insert_into_notification_event\|platform/notification" server/libs --include="*.xml" | grep -v /build/`
Expected: lists the init file, the existing `1764674356_insert_into_notification_event.xml`, and the master changelog that includes them. Note the master file path and the order entries.

- [ ] **Step 2: Create the seed changeset (ids continue after the existing seeds; types = new ordinals 6,7,8)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="1769100000" author="Ivica Cardic">
        <insert tableName="notification_event">
            <column name="id" valueNumeric="6" />
            <column name="type" valueNumeric="6" />
        </insert>
        <insert tableName="notification_event">
            <column name="id" valueNumeric="7" />
            <column name="type" valueNumeric="7" />
        </insert>
        <insert tableName="notification_event">
            <column name="id" valueNumeric="8" />
            <column name="type" valueNumeric="8" />
        </insert>
    </changeSet>
</databaseChangeLog>
```

> `id=6/type=6` = TASK_STARTED, `7/7` = TASK_COMPLETED, `8/8` = TASK_FAILED. (JOB_STOPPED ordinal 5
> remains unseeded — consistent with Phase 1; seed it separately if product wants it selectable.)

- [ ] **Step 3: Register the changeset in the aggregating changelog**

Add an `<include file="config/liquibase/changelog/platform/notification/1769100000_insert_into_notification_event_task.xml" relativeToChangelogFile="false"/>` entry to the master changelog found in Step 1, immediately after the existing `1764674356_insert_into_notification_event.xml` include (match the exact `file=`/`relativeToChangelogFile` attribute style used by neighboring entries).

- [ ] **Step 4: Verify the migration applies in the module integration test**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-service:test --tests "com.bytechef.platform.notification.service.NotificationServiceIntTest"`
Expected: PASS (Liquibase applies the new changeset against the Testcontainers Postgres). If it complains about an already-applied/duplicate changeset, delete `build/resources/test/config/liquibase/...` stale copies per CLAUDE.md and rerun.

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-notification/platform-notification-service/src/main/resources/config/liquibase/changelog/platform/notification/
git add <master-changelog-path-from-step-1>
git commit -m "732 Seed TASK notification_event rows"
```

---

## Task 6: Task email notification handler

**Files:**
- Create: `server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/notification/TaskStatusEmailNotificationHandler.java`
- Modify: `server/libs/config/messages-config/src/main/resources/messages.properties`
- Test: `server/libs/platform/platform-coordinator/src/test/java/com/bytechef/platform/coordinator/notification/TaskStatusEmailNotificationHandlerTest.java`

- [ ] **Step 1: Add the email templates**

In `messages.properties`, after the `email.JOB_STARTED.content` line, add:

```properties
email.TASK_STARTED.subject=Task Execution Status - Task {0} (Job ID: {1}) started
email.TASK_STARTED.content= Task {0} (Job ID: {1}) execution has started.
email.TASK_COMPLETED.subject=Task Execution Status - Task {0} (Job ID: {1}) completed
email.TASK_COMPLETED.content= Task {0} (Job ID: {1}) execution completed.
email.TASK_FAILED.subject=Task Execution Status - Task {0} (Job ID: {1}) failed
email.TASK_FAILED.content= Task {0} (Job ID: {1}) execution failed.
```

- [ ] **Step 2: Write the failing test**

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

package com.bytechef.platform.coordinator.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

class TaskStatusEmailNotificationHandlerTest {

    @Test
    void testGetSubjectAndContentUseTaskTemplates() {
        StaticMessageSource messageSource = new StaticMessageSource();

        messageSource.addMessage("email.TASK_FAILED.subject", java.util.Locale.getDefault(),
            "Task {0} (Job ID: {1}) failed");
        messageSource.addMessage("email.TASK_FAILED.content", java.util.Locale.getDefault(),
            "Task {0} (Job ID: {1}) execution failed.");

        TaskStatusEmailNotificationHandler handler = new TaskStatusEmailNotificationHandler(messageSource);

        NotificationHandlerContext context = new NotificationHandlerContext.Builder()
            .eventType(NotificationEvent.Type.TASK_FAILED)
            .jobId(99L)
            .taskName("httpRequest")
            .status("FAILED")
            .build();

        assertThat(handler.getSubject(context)).isEqualTo("Task httpRequest (Job ID: 99) failed");
        assertThat(handler.getContent(context)).isEqualTo("Task httpRequest (Job ID: 99) execution failed.");
        assertThat(handler.isHtml()).isFalse();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-coordinator:test --tests "com.bytechef.platform.coordinator.notification.TaskStatusEmailNotificationHandlerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 4: Implement the handler (mirrors `JobStatusEmailNotificationHandler`, uses taskName + jobId)**

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

package com.bytechef.platform.coordinator.notification;

import com.bytechef.platform.notification.domain.NotificationEvent.Type;
import com.bytechef.platform.notification.handler.EmailNotificationHandler;
import com.bytechef.platform.notification.handler.NotificationEventType;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
@NotificationEventType({
    Type.TASK_STARTED, Type.TASK_COMPLETED, Type.TASK_FAILED
})
public class TaskStatusEmailNotificationHandler implements EmailNotificationHandler {

    private final MessageSource messageSource;

    public TaskStatusEmailNotificationHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String getContent(NotificationHandlerContext notificationHandlerContext) {
        return messageSource.getMessage(
            "email." + notificationHandlerContext.getEventType() + ".content",
            new Object[] {
                notificationHandlerContext.getTaskName(), notificationHandlerContext.getJobId()
            },
            Locale.getDefault());
    }

    @Override
    public String getSubject(NotificationHandlerContext notificationHandlerContext) {
        return messageSource.getMessage(
            "email." + notificationHandlerContext.getEventType() + ".subject",
            new Object[] {
                notificationHandlerContext.getTaskName(), notificationHandlerContext.getJobId()
            },
            Locale.getDefault());
    }

    @Override
    public boolean isHtml() {
        return false;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-coordinator:test --tests "com.bytechef.platform.coordinator.notification.TaskStatusEmailNotificationHandlerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/notification/TaskStatusEmailNotificationHandler.java server/libs/config/messages-config/src/main/resources/messages.properties server/libs/platform/platform-coordinator/src/test/java/com/bytechef/platform/coordinator/notification/TaskStatusEmailNotificationHandlerTest.java
git commit -m "732 Add TaskStatusEmailNotificationHandler and task email templates"
```

---

## Task 7: Task webhook notification handler (EE, `@ConditionalOnEEVersion`)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/TaskStatusWebhookNotificationHandler.java`
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/TaskStatusWebhookNotificationHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class TaskStatusWebhookNotificationHandlerTest {

    private final TaskStatusWebhookNotificationHandler handler = new TaskStatusWebhookNotificationHandler();

    @Test
    void testGetPayloadContainsTaskFieldsAndError() {
        NotificationHandlerContext context = new NotificationHandlerContext.Builder()
            .eventType(NotificationEvent.Type.TASK_FAILED)
            .jobId(99L)
            .taskExecutionId(5L)
            .taskName("httpRequest")
            .status("FAILED")
            .error("boom")
            .build();

        Map<String, Object> payload = handler.getPayload(context);

        assertThat(payload)
            .containsEntry("jobId", 99L)
            .containsEntry("taskExecutionId", 5L)
            .containsEntry("taskName", "httpRequest")
            .containsEntry("status", "FAILED")
            .containsEntry("error", "boom");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.TaskStatusWebhookNotificationHandlerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the handler**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.notification.domain.NotificationEvent.Type;
import com.bytechef.platform.notification.handler.NotificationEventType;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.WebhookNotificationHandler;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@NotificationEventType({
    Type.TASK_STARTED, Type.TASK_COMPLETED, Type.TASK_FAILED
})
public class TaskStatusWebhookNotificationHandler implements WebhookNotificationHandler {

    @Override
    public Map<String, Object> getPayload(NotificationHandlerContext notificationHandlerContext) {
        Map<String, Object> payload = new HashMap<>();

        payload.put("jobId", notificationHandlerContext.getJobId());
        payload.put("taskExecutionId", notificationHandlerContext.getTaskExecutionId());
        payload.put("taskName", notificationHandlerContext.getTaskName());

        if (notificationHandlerContext.getStatus() != null) {
            payload.put("status", notificationHandlerContext.getStatus());
        }

        if (notificationHandlerContext.getError() != null) {
            payload.put("error", notificationHandlerContext.getError());
        }

        return payload;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.TaskStatusWebhookNotificationHandlerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/TaskStatusWebhookNotificationHandler.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/TaskStatusWebhookNotificationHandlerTest.java
git commit -m "732 Add EE TaskStatusWebhookNotificationHandler"
```

---

## Task 8: `NotificationTaskApplicationEventListener`

**Files:**
- Create: `server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/event/listener/NotificationTaskApplicationEventListener.java`
- Test: `server/libs/platform/platform-coordinator/src/test/java/com/bytechef/platform/coordinator/event/listener/NotificationTaskApplicationEventListenerTest.java`

- [ ] **Step 1: Write the failing test (verifies a FAILED task event resolves and dispatches)**

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

package com.bytechef.platform.coordinator.event.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.coordinator.event.TaskFailedApplicationEvent;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandler;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.NotificationHandlerRegistry;
import com.bytechef.platform.notification.handler.NotificationSender;
import com.bytechef.platform.notification.handler.NotificationSenderRegistry;
import com.bytechef.platform.notification.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationTaskApplicationEventListenerTest {

    private final TaskExecutionService taskExecutionService = mock(TaskExecutionService.class);
    private final NotificationHandlerRegistry notificationHandlerRegistry = mock(NotificationHandlerRegistry.class);
    private final NotificationSenderRegistry notificationSenderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    @Test
    @SuppressWarnings({
        "rawtypes", "unchecked"
    })
    void testTaskFailedEventDispatchesNotification() {
        NotificationTaskApplicationEventListener listener = new NotificationTaskApplicationEventListener(
            taskExecutionService, notificationHandlerRegistry, notificationSenderRegistry, notificationService);

        Notification notification = mock(Notification.class);

        when(notification.getType()).thenReturn(Notification.Type.WEBHOOK);
        when(notificationService.getNotifications(NotificationEvent.Type.TASK_FAILED))
            .thenReturn(List.of(notification));

        NotificationSender notificationSender = mock(NotificationSender.class);
        NotificationHandler notificationHandler = mock(NotificationHandler.class);

        when(notificationSenderRegistry.getNotificationSender(Notification.Type.WEBHOOK))
            .thenReturn(notificationSender);
        when(notificationHandlerRegistry.getNotificationHandler(
            eq(NotificationEvent.Type.TASK_FAILED), eq(Notification.Type.WEBHOOK)))
                .thenReturn(notificationHandler);

        listener.onApplicationEvent(new TaskFailedApplicationEvent(1L, 2L, "httpRequest", "boom"));

        verify(notificationSender).send(eq(notification), eq(notificationHandler), any(NotificationHandlerContext.class));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-coordinator:test --tests "com.bytechef.platform.coordinator.event.listener.NotificationTaskApplicationEventListenerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the listener**

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

package com.bytechef.platform.coordinator.event.listener;

import com.bytechef.atlas.coordinator.event.ApplicationEvent;
import com.bytechef.atlas.coordinator.event.TaskCompletedApplicationEvent;
import com.bytechef.atlas.coordinator.event.TaskFailedApplicationEvent;
import com.bytechef.atlas.coordinator.event.TaskStartedApplicationEvent;
import com.bytechef.atlas.coordinator.event.listener.ApplicationEventListener;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandler;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.NotificationHandlerRegistry;
import com.bytechef.platform.notification.handler.NotificationSender;
import com.bytechef.platform.notification.handler.NotificationSenderRegistry;
import com.bytechef.platform.notification.service.NotificationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @author Ivica Cardic
 */
public class NotificationTaskApplicationEventListener implements ApplicationEventListener {

    private final TaskExecutionService taskExecutionService;
    private final NotificationHandlerRegistry notificationHandlerRegistry;
    private final NotificationSenderRegistry notificationSenderRegistry;
    private final NotificationService notificationService;

    @SuppressFBWarnings("EI")
    public NotificationTaskApplicationEventListener(
        TaskExecutionService taskExecutionService, NotificationHandlerRegistry notificationHandlerRegistry,
        NotificationSenderRegistry notificationSenderRegistry, NotificationService notificationService) {

        this.taskExecutionService = taskExecutionService;
        this.notificationHandlerRegistry = notificationHandlerRegistry;
        this.notificationSenderRegistry = notificationSenderRegistry;
        this.notificationService = notificationService;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent applicationEvent) {
        NotificationHandlerContext context = toContext(applicationEvent);

        if (context == null) {
            return;
        }

        dispatch(context);
    }

    private @Nullable NotificationHandlerContext toContext(ApplicationEvent applicationEvent) {
        if (applicationEvent instanceof TaskStartedApplicationEvent event) {
            TaskExecution taskExecution = taskExecutionService.getTaskExecution(event.getTaskExecutionId());

            return new NotificationHandlerContext.Builder()
                .eventType(NotificationEvent.Type.TASK_STARTED)
                .jobId(event.getJobId())
                .taskExecutionId(event.getTaskExecutionId())
                .taskName(taskExecution.getName())
                .status("STARTED")
                .timestamp(Instant.now())
                .build();
        }

        if (applicationEvent instanceof TaskCompletedApplicationEvent event) {
            return new NotificationHandlerContext.Builder()
                .eventType(NotificationEvent.Type.TASK_COMPLETED)
                .jobId(event.getJobId())
                .taskExecutionId(event.getTaskExecutionId())
                .taskName(event.getTaskName())
                .status("COMPLETED")
                .timestamp(Instant.now())
                .build();
        }

        if (applicationEvent instanceof TaskFailedApplicationEvent event) {
            return new NotificationHandlerContext.Builder()
                .eventType(NotificationEvent.Type.TASK_FAILED)
                .jobId(event.getJobId())
                .taskExecutionId(event.getTaskExecutionId())
                .taskName(event.getTaskName())
                .status("FAILED")
                .error(event.getErrorMessage())
                .timestamp(Instant.now())
                .build();
        }

        return null;
    }

    @SuppressWarnings({
        "rawtypes", "unchecked"
    })
    private void dispatch(NotificationHandlerContext context) {
        NotificationEvent.Type eventType = context.getEventType();

        List<Notification> notifications = notificationService.getNotifications(eventType);

        for (Notification notification : notifications) {
            NotificationSender notificationSender = notificationSenderRegistry.getNotificationSender(
                notification.getType());

            if (notificationSender == null) {
                continue; // EE-only channel (e.g. WEBHOOK) not registered in this edition
            }

            NotificationHandler notificationHandler = notificationHandlerRegistry.getNotificationHandler(
                eventType, notification.getType());

            notificationSender.send(notification, notificationHandler, context);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-coordinator:test --tests "com.bytechef.platform.coordinator.event.listener.NotificationTaskApplicationEventListenerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/event/listener/NotificationTaskApplicationEventListener.java server/libs/platform/platform-coordinator/src/test/java/com/bytechef/platform/coordinator/event/listener/NotificationTaskApplicationEventListenerTest.java
git commit -m "732 Add NotificationTaskApplicationEventListener"
```

---

## Task 9: Register the task listener bean

**Files:**
- Modify: `server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/config/PlatformCoordinatorConfiguration.java`

- [ ] **Step 1: Add a `TaskExecutionService` constructor dependency and the listener bean**

Add the import:

```java
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.platform.coordinator.event.listener.NotificationTaskApplicationEventListener;
```

Add a `TaskExecutionService` field, set it in the constructor (extend the existing constructor parameter list and assignment), then add this bean method:

```java
    @Bean
    NotificationTaskApplicationEventListener notificationTaskApplicationEventListener() {
        return new NotificationTaskApplicationEventListener(
            taskExecutionService, notificationHandlerRegistry, notificationSenderRegistry, notificationService);
    }
```

The updated constructor (full, replacing the existing one):

```java
    @SuppressFBWarnings("EI")
    public PlatformCoordinatorConfiguration(
        JobService jobService, TaskExecutionService taskExecutionService,
        NotificationHandlerRegistry notificationHandlerRegistry,
        NotificationSenderRegistry notificationSenderRegistry, NotificationService notificationService) {

        this.jobService = jobService;
        this.taskExecutionService = taskExecutionService;
        this.notificationHandlerRegistry = notificationHandlerRegistry;
        this.notificationSenderRegistry = notificationSenderRegistry;
        this.notificationService = notificationService;
    }
```

Add the field near the other fields:

```java
    private final TaskExecutionService taskExecutionService;
```

- [ ] **Step 2: Compile the module**

Run: `./gradlew :server:libs:platform:platform-coordinator:compileJava`
Expected: BUILD SUCCESSFUL. (`platform-coordinator` already depends on `atlas-execution` — `JobService` is used here — so `TaskExecutionService` resolves.)

- [ ] **Step 3: Commit**

```bash
git add server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/config/PlatformCoordinatorConfiguration.java
git commit -m "732 Register NotificationTaskApplicationEventListener bean"
```

---

## Task 10: Client — surface TASK events (no code change expected)

The events dropdown is populated from `useGetNotificationEventsQuery()` (the seeded `notification_event`
rows). Once Task 5's migration runs, TASK_STARTED/COMPLETED/FAILED appear automatically with their
enum-name labels — no client change required.

- [ ] **Step 1: Verify the events appear**

Run the stack, open Settings → Notifications → New Notification, confirm the Events multiselect lists
the three new TASK_* entries alongside the JOB_* ones.

- [ ] **Step 2 (optional): Prettify labels**

If raw `TASK_FAILED` labels look unfriendly, map enum names to display strings in
`NotificationDialog.tsx`'s `options` mapping. This is cosmetic; skip unless requested.

---

## Final verification

- [ ] **Run checks for all touched modules**

```bash
./gradlew spotlessApply
./gradlew :server:libs:atlas:atlas-coordinator:atlas-coordinator-api:check \
          :server:libs:atlas:atlas-worker:atlas-worker-impl:check \
          :server:libs:platform:platform-notification:platform-notification-api:check \
          :server:libs:platform:platform-notification:platform-notification-service:check \
          :server:libs:platform:platform-coordinator:check \
          :server:ee:libs:platform:platform-notification:platform-notification-webhook:check
```
Expected: BUILD SUCCESSFUL. Fix any Checkstyle/PMD/SpotBugs findings per CLAUDE.md conventions.

- [ ] **Manual smoke (requires running stack):** create a WEBHOOK notification subscribed to TASK_FAILED,
  run a workflow whose step fails, confirm the webhook receives a `TASK_FAILED` envelope with
  `data.taskName` and `data.error`.

---

## Out of scope (documented limitations / follow-ups)

- **TASK_FAILED covers task-execution failures only** (`TaskWorker.handleException`). Dispatch failures
  (`ErrorHandlingTaskDispatcher`) and completion-handler failures (`TaskCoordinator.onTaskExecutionCompleteEvent`)
  do not emit `TaskFailedApplicationEvent`. Add emission at those sites in a follow-up if needed.
- **High-volume TASK_STARTED** can be noisy for large workflows; consider a per-notification rate cap in a
  later iteration.
- **Shared dispatch loop:** `NotificationTaskApplicationEventListener.dispatch` duplicates the loop in
  `NotificationJobStatusApplicationEventListener`. A future cleanup can extract a shared
  `NotificationDispatcher` helper; kept separate here to avoid refactoring Phase 1 code.
