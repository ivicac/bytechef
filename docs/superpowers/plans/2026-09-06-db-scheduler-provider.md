# db-scheduler Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add db-scheduler as a selectable scheduler provider next to Quartz, with db-scheduler-ui and a one-way importer that copies live Quartz jobs into db-scheduler when db-scheduler is the active provider.

**Architecture:** A new sibling module `platform-scheduler-db` implements the existing `TriggerScheduler` and `ConnectionRefreshScheduler` interfaces on top of db-scheduler's `SchedulerClient`; every bean is gated on `bytechef.scheduler.provider=db-scheduler`. The db-scheduler and UI Spring Boot starters are switched on/off from that same property by an `EnvironmentPostProcessor`. Under `db-scheduler`, the Quartz `Scheduler` bean still exists but never starts; a one-time db-scheduler task reads it through the Quartz API and copies jobs with `scheduleIfNotExists`.

**Tech Stack:** Java 25, Spring Boot 4.0.7, db-scheduler 16.12.0 (`db-scheduler-spring-boot-4-starter`), db-scheduler-ui 5.0.0 (`db-scheduler-ui-spring-boot-4-starter`), Jackson 3 (`tools.jackson`), Liquibase, Testcontainers PostgreSQL, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-06-db-scheduler-provider-design.md`

## Global Constraints

- `bytechef.scheduler.provider` values: `quartz` (default, unchanged) | `db-scheduler` | `aws`. Enum constant `DB_SCHEDULER`.
- Quartz module `server/libs/platform/platform-scheduler/platform-scheduler-impl` and its tests are **not modified** by this plan.
- Pinned versions: `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0`, `com.github.kagkarlsson:db-scheduler:16.12.0`, `no.bekk.db-scheduler-ui:db-scheduler-ui-spring-boot-4-starter:5.0.0`.
- Task data is JSON via `com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer` (ByteChef is on `tools.jackson`, see `JsonUtils`).
- Task names: `schedule-trigger`, `polling-trigger`, `dynamic-webhook-refresh`, `oauth2-token-refresh`, `one-time-resume`, `stripe-usage-report`, `quartz-import`.
- Instance ids equal today's Quartz job names: `workflowExecutionId.toString()`, `tenantId + connectionId`, `String.valueOf(jobId)`.
- Timing defaults: `db-scheduler.polling-interval=1s`, `polling-strategy=lock-and-fetch`, `threads=10`.
- Importer: reads through the Quartz `Scheduler` API only, dispatches on `JobDetail.getJobClass().getSimpleName()`, writes only with `scheduleIfNotExists`, never modifies Quartz rows, never fails startup, runs on every startup while `db-scheduler` is active.
- Security: `/db-scheduler/**` and `/db-scheduler-api/**` require `AuthorityConstants.SYSTEM_ADMIN`.
- Java style from `CLAUDE.md`: descriptive names, blank line before control statements and after a variable modification that is then used, no `_` prefixes, no `TODO` comments, test method names camelCase without underscores, unit test classes end in `Test`, integration tests end in `IntTest`.
- Commit convention: `5650 <description>` (server) — the spec was filed under #5650. End every commit with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Run `./gradlew spotlessApply` before every commit. `./gradlew test` excludes `*IntTest*`; run `./gradlew :<module>:testIntegration` for integration tests (Docker must be running).
- Never run two Gradle builds concurrently in this worktree.

---

## File structure

New module `server/libs/platform/platform-scheduler/platform-scheduler-db/` (Gradle path `:server:libs:platform:platform-scheduler:platform-scheduler-db`), root package `com.bytechef.platform.scheduler.db`:

| File | Responsibility |
| --- | --- |
| `build.gradle.kts` | dependencies |
| `src/main/resources/META-INF/spring.factories` | registers the `EnvironmentPostProcessor` |
| `src/main/resources/config/liquibase/changelog/db-scheduler/db_scheduler_postgres_init.sql` | `scheduled_tasks` + `scheduled_execution_logs` |
| `db/package-info.java` (and one per sub-package) | `@NullMarked` |
| `db/config/DbSchedulerEnvironmentPostProcessor.java` | maps provider → `db-scheduler.enabled`, `db-scheduler-ui.enabled` |
| `db/config/DbSchedulerConfiguration.java` | provider-gated beans: serializer customizer, tasks, schedulers, importer |
| `db/task/DbSchedulerTaskDescriptors.java` | task names + `TaskDescriptor` constants |
| `db/task/ScheduleTriggerData.java`, `PollingTriggerData.java`, `DynamicWebhookRefreshData.java`, `OAuth2TokenRefreshData.java`, `OneTimeResumeData.java` | JSON task-data records |
| `db/task/ContextBinding.java` | tenant + system security context around execution |
| `db/task/RescheduleAt.java` | `CompletionHandler` that reschedules to an `Instant` |
| `db/task/ScheduleTriggerTaskFactory.java`, `PollingTriggerTaskFactory.java`, `OneTimeResumeTaskFactory.java`, `OAuth2TokenRefreshTaskFactory.java`, `DynamicWebhookRefreshTaskFactory.java` | build the five `Task` objects |
| `db/task/DynamicWebhookRefresher.java` | refresh logic moved from `DynamicWebhookTriggerRefreshJob` |
| `db/DbTriggerScheduler.java`, `db/DbConnectionRefreshScheduler.java` | the two interface implementations |
| `db/importer/ImportedJob.java` | sealed model of a Quartz job to import |
| `db/importer/QuartzJobReader.java` | Quartz API → `ImportedJob`s |
| `db/importer/QuartzImporter.java` | `ImportedJob`s → `scheduleIfNotExists`, summary |
| `db/importer/ImportSummary.java` | counters |
| `db/importer/QuartzImportStarter.java` | schedules the `quartz-import` task on `ApplicationReadyEvent` |

Modified files: `settings.gradle.kts`, `server/apps/server-app/build.gradle.kts`, `server/ee/apps/scheduler-app/build.gradle.kts`, `server/libs/config/app-config/.../ApplicationProperties.java`, `server/libs/config/liquibase-config/.../master.xml`, `server/apps/server-app/src/main/resources/config/{application.yml,application-bytechef.yml,application-liquibase.yml}`, `server/ee/apps/config-server-app/src/main/resources/config/apps/scheduler-app.yml`, `server/libs/config/security-config/.../SecurityConfiguration.java`, `server/libs/platform/platform-billing/platform-billing-service/{build.gradle.kts, config/BillingDbSchedulingConfiguration.java (new)}`, docs.

---

### Task 0: Spec amendments discovered while planning

**Files:**
- Modify: `docs/superpowers/specs/2026-09-06-db-scheduler-provider-design.md`

Three facts learned after the spec was approved change small details; record them so spec and plan agree.

- [ ] **Step 1: Amend §3 polling-trigger row** — task data is `{checkPeriodMinutes}` (captured from `bytechef.coordinator.trigger.polling.check-period` at schedule time, visible in the UI), not `{}`. Edit the table row to:

```
| `polling-trigger` | recurring, persistent schedule | `workflowExecutionId` | `{checkPeriodMinutes}` | `FixedDelay.ofMinutes(checkPeriodMinutes)` |
```

- [ ] **Step 2: Amend §5.1 — UI ships in `server-app` only in this iteration.** Append to §5.1: "`scheduler-app` has `spring-boot-starter-web` but no Spring Security stack (`security-config` is not a dependency), so `db-scheduler-ui` is wired into `server-app` only. `scheduler-app` keeps `db-scheduler-ui.enabled=false` until it gains a security chain; tracked in §7."
  Also replace `history: false` with `db-scheduler-ui.log.enabled: false` (the Boot-4 starter's actual property; `history` is the legacy name).

- [ ] **Step 3: Amend §5.3 — gate the starters with an `EnvironmentPostProcessor`, not exclude + `@Import`.** Replace the bullet starting "The starter's own `db-scheduler.enabled` flag..." with: "The starters are gated by `DbSchedulerEnvironmentPostProcessor` (registered in `META-INF/spring.factories`), which adds a lowest-precedence property source setting `db-scheduler.enabled` and `db-scheduler-ui.enabled` from `bytechef.scheduler.provider`. Importing the autoconfiguration from a regular `@Configuration` would evaluate its `@ConditionalOnBean(DataSource.class)` before the DataSource autoconfiguration runs; the post-processor keeps autoconfiguration ordering intact. `application-liquibase.yml` sets both flags to `false` explicitly, which wins over the post-processor."

- [ ] **Step 4: Add to §7:** "- Wire `db-scheduler-ui` into `scheduler-app` once it has a Spring Security chain."

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-09-06-db-scheduler-provider-design.md
git commit -m "5650 Amend db-scheduler spec with planning findings

Polling data carries checkPeriodMinutes; UI ships in server-app only because
scheduler-app has no security stack; starters are gated by an
EnvironmentPostProcessor to keep autoconfiguration ordering intact.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 1: Module scaffold, Liquibase changelog, app wiring

**Files:**
- Create: `server/libs/platform/platform-scheduler/platform-scheduler-db/build.gradle.kts`
- Create: `server/libs/platform/platform-scheduler/platform-scheduler-db/src/main/java/com/bytechef/platform/scheduler/db/package-info.java`
- Create: `server/libs/platform/platform-scheduler/platform-scheduler-db/src/main/resources/config/liquibase/changelog/db-scheduler/db_scheduler_postgres_init.sql`
- Modify: `settings.gradle.kts:210` (after the `platform-scheduler-impl` include)
- Modify: `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml:58` (after the quartz include)
- Modify: `server/apps/server-app/build.gradle.kts:207`, `server/ee/apps/scheduler-app/build.gradle.kts:29`

**Interfaces:**
- Produces: Gradle project `:server:libs:platform:platform-scheduler:platform-scheduler-db`; tables `scheduled_tasks`, `scheduled_execution_logs` under Liquibase context `mono or scheduler`.

- [ ] **Step 1: Create `build.gradle.kts`.** The dependency block mirrors the Quartz module's (it compiles the same imports the moved handler bodies need) plus db-scheduler and Quartz-for-reading.

```kotlin
dependencies {
    implementation("com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0")
    implementation("org.quartz-scheduler:quartz")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.security:spring-security-core")
    implementation("tools.jackson.core:jackson-databind")
    implementation(project(":server:libs:atlas:atlas-coordinator:atlas-coordinator-api"))
    implementation(project(":server:libs:config:app-config"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:tenant:tenant-api"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-connection:platform-connection-api"))
    implementation(project(":server:libs:platform:platform-scheduler:platform-scheduler-api"))
    implementation(project(":server:libs:platform:platform-workflow:platform-workflow-coordinator:platform-workflow-coordinator-api"))

    testImplementation("org.awaitility:awaitility")
    testImplementation("org.springframework.boot:spring-boot-quartz")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation(project(":server:libs:test:test-int-support"))
    testImplementation(project(":server:libs:test:test-support"))
}
```

- [ ] **Step 2: Create `package-info.java`** (repeat this file, with the matching package line, in `db.config`, `db.task`, and `db.importer` when those packages are created):

```java
@NullMarked
package com.bytechef.platform.scheduler.db;

import org.jspecify.annotations.NullMarked;
```

Every Java file created by this plan starts with the Apache 2.0 header used by `QuartzTriggerScheduler.java` (copy its first 15 lines verbatim) — this module is CE, not EE.

- [ ] **Step 3: Create the Liquibase SQL.** Plain SQL works with the existing `includeAll` (the quartz file is plain SQL too). `IF NOT EXISTS` keeps it idempotent against a database where someone created the tables by hand.

```sql
-- db-scheduler 16.12.0 PostgreSQL schema (db-scheduler/src/test/resources/postgresql_tables.sql)
CREATE TABLE IF NOT EXISTS scheduled_tasks
(
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX IF NOT EXISTS execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX IF NOT EXISTS last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX IF NOT EXISTS priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

-- db-scheduler-ui 5.0.0 execution log (sql/log-table/postgresql.sql); used only when db-scheduler-ui.log.enabled=true
CREATE TABLE IF NOT EXISTS scheduled_execution_logs
(
    id                   BIGINT                   NOT NULL PRIMARY KEY,
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    picked_by            TEXT,
    time_started         TIMESTAMP WITH TIME ZONE NOT NULL,
    time_finished        TIMESTAMP WITH TIME ZONE NOT NULL,
    succeeded            BOOLEAN                  NOT NULL,
    duration_ms          BIGINT                   NOT NULL,
    exception_class      TEXT,
    exception_message    TEXT,
    exception_stacktrace TEXT
);

CREATE INDEX IF NOT EXISTS stl_started_idx ON scheduled_execution_logs (time_started);
CREATE INDEX IF NOT EXISTS stl_task_name_idx ON scheduled_execution_logs (task_name);
CREATE INDEX IF NOT EXISTS stl_exception_class_idx ON scheduled_execution_logs (exception_class);
```

- [ ] **Step 4: Register the changelog in `master.xml`** — insert after the quartz `includeAll` (line 58):

```xml
    <!-- db-scheduler -->
    <includeAll path="classpath:config/liquibase/changelog/db-scheduler/" relativeToChangelogFile="false" errorIfMissingOrEmpty="false" contextFilter="mono or scheduler" />
```

- [ ] **Step 5: Include the module and add it to both apps.** In `settings.gradle.kts` after line 210:

```kotlin
include("server:libs:platform:platform-scheduler:platform-scheduler-db")
```

In `server/apps/server-app/build.gradle.kts` after line 207 and in `server/ee/apps/scheduler-app/build.gradle.kts` after line 29:

```kotlin
    implementation(project(":server:libs:platform:platform-scheduler:platform-scheduler-db"))
```

- [ ] **Step 6: Compile**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:compileJava :server:apps:server-app:compileJava > /tmp/t1.log 2>&1; tr '\r' '\n' < /tmp/t1.log | grep -E "BUILD|FAILED" | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add settings.gradle.kts server/apps/server-app/build.gradle.kts server/ee/apps/scheduler-app/build.gradle.kts server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Add platform-scheduler-db module scaffold and db-scheduler schema

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Provider enum, properties, and the starter gate

**Files:**
- Modify: `server/libs/config/app-config/src/main/java/com/bytechef/config/ApplicationProperties.java:4125-4150`
- Create: `.../platform-scheduler-db/src/main/java/com/bytechef/platform/scheduler/db/config/DbSchedulerEnvironmentPostProcessor.java`
- Create: `.../platform-scheduler-db/src/main/resources/META-INF/spring.factories`
- Test: `.../platform-scheduler-db/src/test/java/com/bytechef/platform/scheduler/db/config/DbSchedulerEnvironmentPostProcessorTest.java`
- Modify: `server/apps/server-app/src/main/resources/config/application-bytechef.yml:116-118`, `application.yml` (after the `quartz:` block at line 272), `application-liquibase.yml` (end of `spring:`), `server/ee/apps/config-server-app/src/main/resources/config/apps/scheduler-app.yml` (after the `quartz:` block at line 18)

**Interfaces:**
- Produces: `ApplicationProperties.Scheduler.Provider.DB_SCHEDULER`; `ApplicationProperties.Scheduler.getDbScheduler().getImporter().isEnabled()` and `.getUi().isEnabled()`; environment properties `db-scheduler.enabled` and `db-scheduler-ui.enabled` derived from the provider.

- [ ] **Step 1: Write the failing test for the post-processor**

```java
package com.bytechef.platform.scheduler.db.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DbSchedulerEnvironmentPostProcessorTest {

    private final DbSchedulerEnvironmentPostProcessor postProcessor = new DbSchedulerEnvironmentPostProcessor();

    @Test
    void testQuartzProviderDisablesBothStarters() {
        MockEnvironment environment = new MockEnvironment().withProperty("bytechef.scheduler.provider", "quartz");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        Assertions.assertThat(environment.getProperty("db-scheduler.enabled", Boolean.class))
            .isFalse();
        Assertions.assertThat(environment.getProperty("db-scheduler-ui.enabled", Boolean.class))
            .isFalse();
    }

    @Test
    void testMissingProviderDisablesBothStarters() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        Assertions.assertThat(environment.getProperty("db-scheduler.enabled", Boolean.class))
            .isFalse();
    }

    @Test
    void testDbSchedulerProviderEnablesBothStarters() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("bytechef.scheduler.provider", "db-scheduler");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        Assertions.assertThat(environment.getProperty("db-scheduler.enabled", Boolean.class))
            .isTrue();
        Assertions.assertThat(environment.getProperty("db-scheduler-ui.enabled", Boolean.class))
            .isTrue();
    }

    @Test
    void testUiCanBeDisabledSeparately() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("bytechef.scheduler.provider", "db-scheduler")
            .withProperty("bytechef.scheduler.db-scheduler.ui.enabled", "false");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        Assertions.assertThat(environment.getProperty("db-scheduler.enabled", Boolean.class))
            .isTrue();
        Assertions.assertThat(environment.getProperty("db-scheduler-ui.enabled", Boolean.class))
            .isFalse();
    }

    @Test
    void testExplicitPropertyWinsOverDerivedValue() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("bytechef.scheduler.provider", "db-scheduler")
            .withProperty("db-scheduler.enabled", "false");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        Assertions.assertThat(environment.getProperty("db-scheduler.enabled", Boolean.class))
            .isFalse();
    }
}
```

Add `testImplementation("org.assertj:assertj-core")`, `testImplementation("org.junit.jupiter:junit-jupiter")`, `testImplementation("org.mockito:mockito-core")`, `testImplementation("org.mockito:mockito-junit-jupiter")`, `testImplementation("org.springframework:spring-test")` to the module's `build.gradle.kts` test block.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:test --tests "*DbSchedulerEnvironmentPostProcessorTest" > /tmp/t2.log 2>&1; tr '\r' '\n' < /tmp/t2.log | grep -E "BUILD|error:|FAILED" | head -5`
Expected: compilation error — `DbSchedulerEnvironmentPostProcessor` does not exist.

- [ ] **Step 3: Implement the post-processor.** `addLast` is deliberate: an explicit `db-scheduler.enabled` in any config file outranks the derived value (last test above).

```java
package com.bytechef.platform.scheduler.db.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Switches the db-scheduler and db-scheduler-ui starters on only when db-scheduler is the active
 * scheduler provider. Runs as a lowest-precedence property source so explicit configuration wins.
 *
 * @author Ivica Cardic
 */
public class DbSchedulerEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "dbSchedulerProvider";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String provider = environment.getProperty("bytechef.scheduler.provider", "quartz");

        boolean active = Objects.equals(provider.toLowerCase(), "db-scheduler") ||
            Objects.equals(provider.toLowerCase(), "db_scheduler");
        boolean uiEnabled = environment.getProperty("bytechef.scheduler.db-scheduler.ui.enabled", Boolean.class, true);

        Map<String, Object> source = new HashMap<>();

        source.put("db-scheduler.enabled", active);
        source.put("db-scheduler-ui.enabled", active && uiEnabled);

        MutablePropertySources propertySources = environment.getPropertySources();

        propertySources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, source));
    }
}
```

`META-INF/spring.factories`:

```
org.springframework.boot.EnvironmentPostProcessor=\
com.bytechef.platform.scheduler.db.config.DbSchedulerEnvironmentPostProcessor
```

- [ ] **Step 4: Run the test to verify it passes**

Run: same command as Step 2. Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Extend `ApplicationProperties.Scheduler`.** Replace the `Scheduler` class body (lines 4125–4150) with:

```java
    public static class Scheduler {

        /**
         * Available scheduler providers.
         */
        public enum Provider {
            /**
             * AWS EventBridge Scheduler
             */
            AWS,
            /**
             * db-scheduler (single-table, cluster-safe JDBC scheduler)
             */
            DB_SCHEDULER,
            /**
             * Quartz Scheduler
             */
            QUARTZ
        }

        /**
         * db-scheduler provider settings.
         */
        public static class DbScheduler {

            /**
             * One-way Quartz import that runs on every startup while db-scheduler is the active provider.
             */
            public static class Importer {

                private boolean enabled = true;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }
            }

            /**
             * db-scheduler-ui, served at /db-scheduler for SYSTEM_ADMIN.
             */
            public static class Ui {

                private boolean enabled = true;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }
            }

            private Importer importer = new Importer();
            private Ui ui = new Ui();

            public Importer getImporter() {
                return importer;
            }

            public Ui getUi() {
                return ui;
            }

            public void setImporter(Importer importer) {
                this.importer = importer;
            }

            public void setUi(Ui ui) {
                this.ui = ui;
            }
        }

        private DbScheduler dbScheduler = new DbScheduler();

        /**
         * Scheduler provider
         */
        private Provider provider = Provider.QUARTZ;

        public DbScheduler getDbScheduler() {
            return dbScheduler;
        }

        public Provider getProvider() {
            return provider;
        }

        public void setDbScheduler(DbScheduler dbScheduler) {
            this.dbScheduler = dbScheduler;
        }

        public void setProvider(Provider provider) {
            this.provider = provider;
        }
    }
```

Binding is strict (`reference_application_properties_strict_binding`): a YAML key without a matching field fails startup, which is why `importer`/`ui` are fields. Note the property name is `importer`, not `import` (`import` is a Java keyword).

- [ ] **Step 6: Configuration files.**

`application-bytechef.yml` lines 116–118 become:

```yaml
  scheduler:
    # Trigger scheduler provider (aws(ee) | db-scheduler | quartz) default: quartz
    provider: quartz
    db-scheduler:
      importer:
        # Copy live Quartz jobs into db-scheduler on every startup while provider is db-scheduler
        enabled: true
      ui:
        # Serve db-scheduler-ui at /db-scheduler (SYSTEM_ADMIN only)
        enabled: true
```

`application.yml`, after the `quartz:` block (line 278), at the same indentation as `quartz:` (two spaces, under `spring:` — but `db-scheduler` is a **top-level** prefix, so add it at column 0 after the `spring:` tree ends; place it immediately before the `server:` key):

```yaml
db-scheduler:
  polling-interval: 1s
  polling-strategy: lock-and-fetch
  threads: 10

db-scheduler-ui:
  read-only: true
  task-data: true
  log:
    enabled: false
```

`application-liquibase.yml`, at column 0 after the `spring:` tree:

```yaml
db-scheduler:
  enabled: false

db-scheduler-ui:
  enabled: false
```

`scheduler-app.yml` (EE), at column 0:

```yaml
db-scheduler:
  polling-interval: 1s
  polling-strategy: lock-and-fetch
  threads: 10

db-scheduler-ui:
  enabled: false
```

- [ ] **Step 7: Compile the app and run the module tests**

Run: `./gradlew :server:apps:server-app:compileJava :server:libs:platform:platform-scheduler:platform-scheduler-db:test > /tmp/t2b.log 2>&1; tr '\r' '\n' < /tmp/t2b.log | grep -E "BUILD|FAILED" | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/config/app-config server/libs/platform/platform-scheduler/platform-scheduler-db server/apps/server-app/src/main/resources/config server/ee/apps/config-server-app/src/main/resources/config/apps/scheduler-app.yml
git commit -m "5650 Add db-scheduler provider switch and starter gate

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Task descriptors, data records, context binding, reschedule handler

**Files:**
- Create under `.../platform-scheduler-db/src/main/java/com/bytechef/platform/scheduler/db/task/`: `DbSchedulerTaskDescriptors.java`, `ScheduleTriggerData.java`, `PollingTriggerData.java`, `DynamicWebhookRefreshData.java`, `OAuth2TokenRefreshData.java`, `OneTimeResumeData.java`, `ContextBinding.java`, `RescheduleAt.java`, `package-info.java`
- Test: `.../src/test/java/com/bytechef/platform/scheduler/db/task/ContextBindingTest.java`, `RescheduleAtTest.java`, `ScheduleTriggerDataTest.java`

**Interfaces:**
- Produces:
  - `DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER : TaskDescriptor<ScheduleTriggerData>`, `POLLING_TRIGGER : TaskDescriptor<PollingTriggerData>`, `DYNAMIC_WEBHOOK_REFRESH : TaskDescriptor<DynamicWebhookRefreshData>`, `OAUTH2_TOKEN_REFRESH : TaskDescriptor<OAuth2TokenRefreshData>`, `ONE_TIME_RESUME : TaskDescriptor<OneTimeResumeData>`, `STRIPE_USAGE_REPORT : TaskDescriptor<Void>`, `QUARTZ_IMPORT : TaskDescriptor<Void>`; name constants `*_NAME`.
  - `record ScheduleTriggerData(String cronPattern, String zoneId, String output) implements ScheduleAndData`
  - `record PollingTriggerData(int checkPeriodMinutes) implements ScheduleAndData`
  - `record DynamicWebhookRefreshData(long connectionId)`
  - `record OAuth2TokenRefreshData(long connectionId, String tenantId)`
  - `record OneTimeResumeData(long jobId, @Nullable String continueParameters)`
  - `ContextBinding.run(String tenantId, Runnable)`, `ContextBinding.call(String tenantId, Supplier<T>)`, `ContextBinding.runAsSystem(Runnable)`
  - `record RescheduleAt<T>(Instant executionTime) implements CompletionHandler<T>`

- [ ] **Step 1: Write the failing tests**

`ContextBindingTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ContextBindingTest {

    @AfterEach
    void tearDown() {
        TenantContext.resetCurrentTenantId();
        SecurityContextHolder.clearContext();
    }

    @Test
    void testRunBindsTenantAndSystemAuthentication() {
        AtomicReference<String> observedTenantId = new AtomicReference<>();
        AtomicReference<Object> observedAuthentication = new AtomicReference<>();

        ContextBinding.run("000042", () -> {
            observedTenantId.set(TenantContext.getCurrentTenantId());
            observedAuthentication.set(SecurityContextHolder.getContext()
                .getAuthentication());
        });

        Assertions.assertThat(observedTenantId.get())
            .isEqualTo("000042");
        Assertions.assertThat(observedAuthentication.get())
            .isNotNull();
    }

    @Test
    void testRunRestoresTenantAfterCompletion() {
        ContextBinding.run("000042", () -> {});

        Assertions.assertThat(TenantContext.getCurrentTenantId())
            .isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }

    @Test
    void testRunRestoresTenantWhenRunnableThrows() {
        Assertions.assertThatThrownBy(() -> ContextBinding.run("000042", () -> {
            throw new IllegalStateException("boom");
        }))
            .hasRootCauseInstanceOf(IllegalStateException.class);

        Assertions.assertThat(TenantContext.getCurrentTenantId())
            .isEqualTo(TenantContext.DEFAULT_TENANT_ID);
        Assertions.assertThat(SecurityContextHolder.getContext()
            .getAuthentication())
            .isNull();
    }

    @Test
    void testCallReturnsSupplierValue() {
        Integer result = ContextBinding.call("000042", () -> 7);

        Assertions.assertThat(result)
            .isEqualTo(7);
    }
}
```

`RescheduleAtTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RescheduleAtTest {

    @Test
    void testCompleteReschedulesToGivenInstant() {
        Instant executionTime = Instant.parse("2030-01-01T00:00:00Z");
        ExecutionComplete executionComplete = Mockito.mock(ExecutionComplete.class);

        @SuppressWarnings("unchecked")
        ExecutionOperations<Object> executionOperations = Mockito.mock(ExecutionOperations.class);

        new RescheduleAt<>(executionTime).complete(executionComplete, executionOperations);

        Mockito.verify(executionOperations)
            .reschedule(executionComplete, executionTime);
    }
}
```

`ScheduleTriggerDataTest.java` — proves the schedule is Quartz-dialect and zone-aware, and that Jackson 3 round-trips the record without touching `getSchedule()`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScheduleTriggerDataTest {

    @Test
    void testScheduleUsesQuartzCronDialectInGivenZone() {
        ScheduleTriggerData data = new ScheduleTriggerData("0 0 9 * * ?", "Europe/Zagreb", "{}");

        Schedule schedule = data.getSchedule();

        Instant now = ZonedDateTime.of(2030, 6, 1, 8, 0, 0, 0, ZoneId.of("Europe/Zagreb"))
            .toInstant();
        Instant next = schedule.getInitialExecutionTime(now);

        Assertions.assertThat(next)
            .isEqualTo(ZonedDateTime.of(2030, 6, 1, 9, 0, 0, 0, ZoneId.of("Europe/Zagreb"))
                .toInstant());
    }

    @Test
    void testJacksonRoundTripIgnoresDerivedSchedule() {
        Jackson3Serializer serializer = new Jackson3Serializer(JsonMapper.builder()
            .build());
        ScheduleTriggerData data = new ScheduleTriggerData("0 * * * * ?", "UTC", "{\"expression\":\"* * * * *\"}");

        byte[] bytes = serializer.serialize(data);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        Assertions.assertThat(json)
            .contains("cronPattern")
            .doesNotContain("schedule");
        Assertions.assertThat(serializer.deserialize(ScheduleTriggerData.class, bytes))
            .isEqualTo(data);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:test --tests "*ContextBindingTest" --tests "*RescheduleAtTest" --tests "*ScheduleTriggerDataTest" > /tmp/t3.log 2>&1; tr '\r' '\n' < /tmp/t3.log | grep -E "BUILD|error:" | head -5`
Expected: compilation errors for the missing classes.

- [ ] **Step 3: Implement**

`DbSchedulerTaskDescriptors.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.github.kagkarlsson.scheduler.task.TaskDescriptor;

/**
 * Task names and descriptors shared by the schedulers, the task factories, and the Quartz importer.
 *
 * @author Ivica Cardic
 */
public final class DbSchedulerTaskDescriptors {

    public static final String SCHEDULE_TRIGGER_NAME = "schedule-trigger";
    public static final String POLLING_TRIGGER_NAME = "polling-trigger";
    public static final String DYNAMIC_WEBHOOK_REFRESH_NAME = "dynamic-webhook-refresh";
    public static final String OAUTH2_TOKEN_REFRESH_NAME = "oauth2-token-refresh";
    public static final String ONE_TIME_RESUME_NAME = "one-time-resume";
    public static final String STRIPE_USAGE_REPORT_NAME = "stripe-usage-report";
    public static final String QUARTZ_IMPORT_NAME = "quartz-import";

    public static final TaskDescriptor<ScheduleTriggerData> SCHEDULE_TRIGGER =
        TaskDescriptor.of(SCHEDULE_TRIGGER_NAME, ScheduleTriggerData.class);
    public static final TaskDescriptor<PollingTriggerData> POLLING_TRIGGER =
        TaskDescriptor.of(POLLING_TRIGGER_NAME, PollingTriggerData.class);
    public static final TaskDescriptor<DynamicWebhookRefreshData> DYNAMIC_WEBHOOK_REFRESH =
        TaskDescriptor.of(DYNAMIC_WEBHOOK_REFRESH_NAME, DynamicWebhookRefreshData.class);
    public static final TaskDescriptor<OAuth2TokenRefreshData> OAUTH2_TOKEN_REFRESH =
        TaskDescriptor.of(OAUTH2_TOKEN_REFRESH_NAME, OAuth2TokenRefreshData.class);
    public static final TaskDescriptor<OneTimeResumeData> ONE_TIME_RESUME =
        TaskDescriptor.of(ONE_TIME_RESUME_NAME, OneTimeResumeData.class);
    public static final TaskDescriptor<Void> STRIPE_USAGE_REPORT = TaskDescriptor.of(STRIPE_USAGE_REPORT_NAME);
    public static final TaskDescriptor<Void> QUARTZ_IMPORT = TaskDescriptor.of(QUARTZ_IMPORT_NAME);

    private DbSchedulerTaskDescriptors() {
    }
}
```

`ScheduleTriggerData.java` (`output` is the JSON string produced by `JsonUtils.write(Map)`, exactly what Quartz stores):

```java
package com.bytechef.platform.scheduler.db.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.time.ZoneId;

/**
 * @author Ivica Cardic
 */
public record ScheduleTriggerData(String cronPattern, String zoneId, String output) implements ScheduleAndData {

    @JsonIgnore
    @Override
    public Schedule getSchedule() {
        return new CronSchedule(cronPattern, ZoneId.of(zoneId), CronStyle.QUARTZ);
    }

    @JsonIgnore
    @Override
    public Object getData() {
        return output;
    }
}
```

`PollingTriggerData.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;

/**
 * @author Ivica Cardic
 */
public record PollingTriggerData(int checkPeriodMinutes) implements ScheduleAndData {

    @JsonIgnore
    @Override
    public Schedule getSchedule() {
        return FixedDelay.ofMinutes(checkPeriodMinutes);
    }

    @JsonIgnore
    @Override
    public Object getData() {
        return null;
    }
}
```

`DynamicWebhookRefreshData.java`, `OAuth2TokenRefreshData.java`, `OneTimeResumeData.java`:

```java
public record DynamicWebhookRefreshData(long connectionId) {
}
```

```java
public record OAuth2TokenRefreshData(long connectionId, String tenantId) {
}
```

```java
import org.jspecify.annotations.Nullable;

public record OneTimeResumeData(long jobId, @Nullable String continueParameters) {
}
```

`ContextBinding.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.tenant.TenantContext;
import java.util.function.Supplier;

/**
 * Runs task handlers the way Quartz jobs run today: as the system principal, and — because db-scheduler threads
 * carry no tenant — inside the tenant the task belongs to. Both bindings are undone in finally blocks.
 *
 * @author Ivica Cardic
 */
public final class ContextBinding {

    private ContextBinding() {
    }

    public static void run(String tenantId, Runnable runnable) {
        TenantContext.runWithTenantId(tenantId, () -> runAsSystem(runnable));
    }

    public static <T> T call(String tenantId, Supplier<T> supplier) {
        return TenantContext.callWithTenantId(tenantId, () -> SecurityUtils.runAsSystem(supplier));
    }

    public static void runAsSystem(Runnable runnable) {
        SecurityUtils.runAsSystem(() -> {
            runnable.run();

            return null;
        });
    }
}
```

`RescheduleAt.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import java.time.Instant;

/**
 * Completion handler for self-rescheduling one-time tasks: keeps the same instance and moves its execution time.
 *
 * @author Ivica Cardic
 */
public record RescheduleAt<T>(Instant executionTime) implements CompletionHandler<T> {

    @Override
    public void complete(ExecutionComplete executionComplete, ExecutionOperations<T> executionOperations) {
        executionOperations.reschedule(executionComplete, executionTime);
    }
}
```

`TenantContext.callWithTenantId(String, Callable<V>)` exists at `TenantContext.java:40`; `SecurityUtils.runAsSystem(Supplier<T>)` at `SecurityUtils.java:152`. `@JsonIgnore` is `com.fasterxml.jackson.annotation.JsonIgnore` — Jackson 3 still uses the 2.x annotations artifact.

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2. Expected: `BUILD SUCCESSFUL`, 7 tests passed. If `testJacksonRoundTripIgnoresDerivedSchedule` fails on deserialization of the record, add `@JsonCreator` to a canonical constructor declared explicitly in `ScheduleTriggerData` — and then do the same for the other records.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Add db-scheduler task descriptors, data records, and context binding

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: The five task factories and the provider configuration

**Files:**
- Create under `.../db/task/`: `ScheduleTriggerTaskFactory.java`, `PollingTriggerTaskFactory.java`, `OneTimeResumeTaskFactory.java`, `OAuth2TokenRefreshTaskFactory.java`, `DynamicWebhookRefreshTaskFactory.java`, `DynamicWebhookRefresher.java`
- Create: `.../db/config/DbSchedulerConfiguration.java`
- Test: `.../src/test/java/com/bytechef/platform/scheduler/db/task/ScheduleTriggerTaskFactoryTest.java`, `PollingTriggerTaskFactoryTest.java`, `OneTimeResumeTaskFactoryTest.java`, `OAuth2TokenRefreshTaskFactoryTest.java`, `DynamicWebhookRefreshTaskFactoryTest.java`

**Interfaces:**
- Consumes: Task 3 descriptors, data records, `ContextBinding`, `RescheduleAt`.
- Produces:
  - `ScheduleTriggerTaskFactory.create(ApplicationEventPublisher) : Task<ScheduleTriggerData>`
  - `PollingTriggerTaskFactory.create(ApplicationEventPublisher) : Task<PollingTriggerData>`
  - `OneTimeResumeTaskFactory.create(ApplicationEventPublisher) : Task<OneTimeResumeData>`
  - `OAuth2TokenRefreshTaskFactory.create(ConnectionFacade) : Task<OAuth2TokenRefreshData>`
  - `DynamicWebhookRefreshTaskFactory.create(DynamicWebhookRefresher) : Task<DynamicWebhookRefreshData>`
  - `DynamicWebhookRefresher.refresh(WorkflowExecutionId, long connectionId) : @Nullable Instant`
  - `DbSchedulerConfiguration` — `@Configuration`, gated on `bytechef.scheduler.provider=db-scheduler`, exposes each `Task` as a bean and a `DbSchedulerCustomizer` providing the `Jackson3Serializer`.

How a handler is exercised in a unit test: build the task, then call `task.execute(taskInstance, executionContext)`. For `CustomTask`, `execute` returns the `CompletionHandler` you assert on; for `VoidExecutionHandler`-based tasks it returns `OnCompleteRemove`/reschedule internally and you assert the side effect. `ExecutionContext` is a plain class — construct with `new ExecutionContext(schedulerState, execution, schedulerClient, currentlyExecuting)`; use Mockito mocks for the first, third and fourth, and build `Execution` with `new Execution(executionTime, taskInstance)`.

- [ ] **Step 1: Write the failing tests.** Shared helper, put in `src/test/java/com/bytechef/platform/scheduler/db/task/TaskTestSupport.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import org.mockito.Mockito;

final class TaskTestSupport {

    private TaskTestSupport() {
    }

    static <T> ExecutionContext executionContext(TaskInstance<T> taskInstance, Instant executionTime) {
        Execution execution = new Execution(executionTime, taskInstance);

        return new ExecutionContext(
            Mockito.mock(SchedulerState.class), execution, Mockito.mock(SchedulerClient.class),
            Mockito.mock(CurrentlyExecuting.class));
    }
}
```

`ScheduleTriggerTaskFactoryTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.event.TriggerListenerEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class ScheduleTriggerTaskFactoryTest {

    private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);

    @Test
    void testExecutePublishesTriggerListenerEventWithFireTimeAndLocalDateTime() {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 123L, "workflow-uuid", "trigger_1");
        Instant fireTime = Instant.parse("2030-06-01T07:00:00Z");
        Task<ScheduleTriggerData> task = ScheduleTriggerTaskFactory.create(eventPublisher);
        TaskInstance<ScheduleTriggerData> taskInstance = DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER
            .instance(workflowExecutionId.toString())
            .data(new ScheduleTriggerData(
                "0 0 9 * * ?", "Europe/Zagreb", "{\"expression\":\"0 9 * * *\",\"timezone\":\"Europe/Zagreb\"}"))
            .build();

        task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, fireTime));

        ArgumentCaptor<TriggerListenerEvent> captor = ArgumentCaptor.forClass(TriggerListenerEvent.class);

        Mockito.verify(eventPublisher)
            .publishEvent(captor.capture());

        TriggerListenerEvent.ListenerParameters parameters = captor.getValue()
            .getListenerParameters();

        Assertions.assertThat(parameters.workflowExecutionId())
            .isEqualTo(workflowExecutionId);
        Assertions.assertThat(parameters.executionDate())
            .isEqualTo(fireTime);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) parameters.output();

        Assertions.assertThat(output)
            .containsEntry("fireTime", Date.from(fireTime))
            .containsEntry("dateTime", LocalDateTime.of(2030, 6, 1, 9, 0))
            .containsEntry("expression", "0 9 * * *")
            .containsEntry("timezone", "Europe/Zagreb");
    }

    @Test
    void testExecuteFallsBackToSystemZoneWhenTimezoneMissing() {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 123L, "workflow-uuid", "trigger_1");
        Instant fireTime = Instant.parse("2030-06-01T07:00:00Z");
        Task<ScheduleTriggerData> task = ScheduleTriggerTaskFactory.create(eventPublisher);
        TaskInstance<ScheduleTriggerData> taskInstance = DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER
            .instance(workflowExecutionId.toString())
            .data(new ScheduleTriggerData("0 0 9 * * ?", "UTC", "{}"))
            .build();

        task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, fireTime));

        ArgumentCaptor<TriggerListenerEvent> captor = ArgumentCaptor.forClass(TriggerListenerEvent.class);

        Mockito.verify(eventPublisher)
            .publishEvent(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) captor.getValue()
            .getListenerParameters()
            .output();

        Assertions.assertThat(output)
            .containsEntry("dateTime", LocalDateTime.ofInstant(fireTime, ZoneId.systemDefault()));
    }
}
```

`TriggerListenerEvent` exposes `getListenerParameters()` — check `TriggerListenerEvent.java:35-64`; if the accessor has a different name, use that name in the test (do not add one to the event class).

`PollingTriggerTaskFactoryTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.event.TriggerPollEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class PollingTriggerTaskFactoryTest {

    @Test
    void testExecutePublishesTriggerPollEvent() {
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 5L, "workflow-uuid", "trigger_1");
        Task<PollingTriggerData> task = PollingTriggerTaskFactory.create(eventPublisher);
        TaskInstance<PollingTriggerData> taskInstance = DbSchedulerTaskDescriptors.POLLING_TRIGGER
            .instance(workflowExecutionId.toString())
            .data(new PollingTriggerData(5))
            .build();

        task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, Instant.now()));

        ArgumentCaptor<TriggerPollEvent> captor = ArgumentCaptor.forClass(TriggerPollEvent.class);

        Mockito.verify(eventPublisher)
            .publishEvent(captor.capture());
        Assertions.assertThat(captor.getValue()
            .getWorkflowExecutionId())
            .isEqualTo(workflowExecutionId);
    }
}
```

`OneTimeResumeTaskFactoryTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.atlas.coordinator.event.ResumeJobEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class OneTimeResumeTaskFactoryTest {

    @Test
    void testExecutePublishesResumeJobEvent() {
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        Task<OneTimeResumeData> task = OneTimeResumeTaskFactory.create(eventPublisher);
        TaskInstance<OneTimeResumeData> taskInstance = DbSchedulerTaskDescriptors.ONE_TIME_RESUME
            .instance("42")
            .data(new OneTimeResumeData(42L, null))
            .build();

        task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, Instant.now()));

        ArgumentCaptor<ResumeJobEvent> captor = ArgumentCaptor.forClass(ResumeJobEvent.class);

        Mockito.verify(eventPublisher)
            .publishEvent(captor.capture());
        Assertions.assertThat(captor.getValue()
            .getJobId())
            .isEqualTo(42L);
    }
}
```

`OAuth2TokenRefreshTaskFactoryTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.CustomTask;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OAuth2TokenRefreshTaskFactoryTest {

    @Test
    void testExecuteRefreshesAndReschedulesFiveMinutesBeforeExpiry() {
        ConnectionFacade connectionFacade = Mockito.mock(ConnectionFacade.class);

        Mockito.when(connectionFacade.executeConnectionRefresh(77L))
            .thenReturn(3600);

        CustomTask<OAuth2TokenRefreshData> task = OAuth2TokenRefreshTaskFactory.create(connectionFacade);
        TaskInstance<OAuth2TokenRefreshData> taskInstance = DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH
            .instance("00000177")
            .data(new OAuth2TokenRefreshData(77L, "000001"))
            .build();

        Instant before = Instant.now();
        CompletionHandler<OAuth2TokenRefreshData> completionHandler =
            task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, before));

        Assertions.assertThat(completionHandler)
            .isInstanceOf(RescheduleAt.class);

        Instant rescheduledTo = ((RescheduleAt<OAuth2TokenRefreshData>) completionHandler).executionTime();

        Assertions.assertThat(rescheduledTo)
            .isBetween(
                before.plusSeconds(3600)
                    .minus(Duration.ofMinutes(5)),
                Instant.now()
                    .plusSeconds(3600)
                    .minus(Duration.ofMinutes(5)));
    }
}
```

`DynamicWebhookRefreshTaskFactoryTest.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.CustomTask;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DynamicWebhookRefreshTaskFactoryTest {

    private final DynamicWebhookRefresher refresher = Mockito.mock(DynamicWebhookRefresher.class);
    private final WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
        PlatformType.AUTOMATION, 9L, "workflow-uuid", "trigger_1");

    @Test
    void testExecuteReschedulesToReturnedExpiry() {
        Instant newExpiry = Instant.parse("2031-01-01T00:00:00Z");

        Mockito.when(refresher.refresh(workflowExecutionId, 11L))
            .thenReturn(newExpiry);

        CompletionHandler<DynamicWebhookRefreshData> completionHandler = execute();

        Assertions.assertThat(completionHandler)
            .isEqualTo(new RescheduleAt<DynamicWebhookRefreshData>(newExpiry));
    }

    @Test
    void testExecuteRemovesWhenRefreshReturnsNoExpiry() {
        Mockito.when(refresher.refresh(workflowExecutionId, 11L))
            .thenReturn(null);

        CompletionHandler<DynamicWebhookRefreshData> completionHandler = execute();

        Assertions.assertThat(completionHandler)
            .isInstanceOf(CompletionHandler.OnCompleteRemove.class);
    }

    private CompletionHandler<DynamicWebhookRefreshData> execute() {
        CustomTask<DynamicWebhookRefreshData> task = DynamicWebhookRefreshTaskFactory.create(refresher);
        TaskInstance<DynamicWebhookRefreshData> taskInstance = DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH
            .instance(workflowExecutionId.toString())
            .data(new DynamicWebhookRefreshData(11L))
            .build();

        return task.execute(taskInstance, TaskTestSupport.executionContext(taskInstance, Instant.now()));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:test --tests "*TaskFactoryTest" > /tmp/t4.log 2>&1; tr '\r' '\n' < /tmp/t4.log | grep -E "BUILD|error:" | head -5`
Expected: compilation errors for the missing factories.

- [ ] **Step 3: Implement the factories.** Each factory is a final class with a private constructor and one static `create`.

`ScheduleTriggerTaskFactory.java` — `getFireLocalDateTime` is moved verbatim from `ScheduleTriggerJob` (its javadoc explains the default-zone fallback; keep it):

```java
package com.bytechef.platform.scheduler.db.task;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.event.TriggerListenerEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
public final class ScheduleTriggerTaskFactory {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTriggerTaskFactory.class);

    private ScheduleTriggerTaskFactory() {
    }

    public static Task<ScheduleTriggerData> create(ApplicationEventPublisher eventPublisher) {
        return Tasks.recurringWithPersistentSchedule(SCHEDULE_TRIGGER)
            .execute((taskInstance, executionContext) -> {
                ScheduleTriggerData data = taskInstance.getData();
                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(taskInstance.getId());
                Instant fireTime = executionContext.getExecution().executionTime;
                Map<String, Object> output = JsonUtils.readMap(data.output(), Object.class);
                Date fireDate = Date.from(fireTime);

                ContextBinding.run(
                    workflowExecutionId.getTenantId(),
                    () -> eventPublisher.publishEvent(
                        new TriggerListenerEvent(
                            new TriggerListenerEvent.ListenerParameters(
                                workflowExecutionId, fireTime,
                                MapUtils.concat(
                                    Map.of("fireTime", fireDate, "dateTime", getFireLocalDateTime(output, fireDate)),
                                    output)))));
            });
    }

    /**
     * This method returns the fire time in the desired local time zone. Default ZoneId is used if the key or value
     * misses. Usage of default time zone mitigates NullPointerException in the cases when we upgrade action with the
     * new zoneId required parameter which old definitions present in production don't have. This method can be removed
     * once you confirm there are no workflow definitions that miss this value.
     */
    private static LocalDateTime getFireLocalDateTime(Map<String, ?> map, Date fireTime) {
        ZoneId zoneId = ZoneId.systemDefault();

        if (map.containsKey("timezone")) {
            Object value = map.get("timezone");

            if (value instanceof String stringValue) {
                zoneId = ZoneId.of(stringValue);
            }
        }

        if (Objects.equals(zoneId, ZoneId.systemDefault())) {
            log.info("Default ZoneId is used. Workflow definition parameters miss zone id - check/update db values");
        }

        return fireTime.toInstant()
            .atZone(zoneId)
            .toLocalDateTime();
    }
}
```

`PollingTriggerTaskFactory.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.POLLING_TRIGGER;

import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.event.TriggerPollEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
public final class PollingTriggerTaskFactory {

    private PollingTriggerTaskFactory() {
    }

    public static Task<PollingTriggerData> create(ApplicationEventPublisher eventPublisher) {
        return Tasks.recurringWithPersistentSchedule(POLLING_TRIGGER)
            .execute((taskInstance, executionContext) -> {
                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(taskInstance.getId());

                ContextBinding.run(
                    workflowExecutionId.getTenantId(),
                    () -> eventPublisher.publishEvent(new TriggerPollEvent(workflowExecutionId)));
            });
    }
}
```

`OneTimeResumeTaskFactory.java` — Quartz's `OneTimeSchedulerJob` publishes `new ResumeJobEvent(jobId)` and ignores `continueParameters`; keep that parity (the data is stored for inspection only):

```java
package com.bytechef.platform.scheduler.db.task;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.ONE_TIME_RESUME;

import com.bytechef.atlas.coordinator.event.ResumeJobEvent;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.context.ApplicationEventPublisher;

/**
 * @author Ivica Cardic
 */
public final class OneTimeResumeTaskFactory {

    private OneTimeResumeTaskFactory() {
    }

    public static Task<OneTimeResumeData> create(ApplicationEventPublisher eventPublisher) {
        return Tasks.oneTime(ONE_TIME_RESUME)
            .execute((taskInstance, executionContext) -> {
                OneTimeResumeData data = taskInstance.getData();

                ContextBinding.runAsSystem(() -> eventPublisher.publishEvent(new ResumeJobEvent(data.jobId())));
            });
    }
}
```

`OAuth2TokenRefreshTaskFactory.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH;

import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.github.kagkarlsson.scheduler.task.CustomTask;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import java.time.Instant;

/**
 * @author Ivica Cardic
 */
public final class OAuth2TokenRefreshTaskFactory {

    static final Duration TOKEN_REFRESH_OFFSET = Duration.ofMinutes(5);
    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    static final int MAX_RETRIES = 3;

    private OAuth2TokenRefreshTaskFactory() {
    }

    public static CustomTask<OAuth2TokenRefreshData> create(ConnectionFacade connectionFacade) {
        return Tasks.custom(OAUTH2_TOKEN_REFRESH)
            .onFailure(new FailureHandler.MaxRetriesFailureHandler<>(
                MAX_RETRIES, new FailureHandler.OnFailureRetryLater<>(RETRY_DELAY)))
            .execute((taskInstance, executionContext) -> {
                OAuth2TokenRefreshData data = taskInstance.getData();

                Integer expiresIn = ContextBinding.call(
                    data.tenantId(), () -> connectionFacade.executeConnectionRefresh(data.connectionId()));

                Instant nextRefresh = Instant.now()
                    .plusSeconds(expiresIn)
                    .minus(TOKEN_REFRESH_OFFSET);

                return new RescheduleAt<>(nextRefresh);
            });
    }
}
```

`DynamicWebhookRefresher.java` — the body of `DynamicWebhookTriggerRefreshJob.refreshDynamicWebhookTrigger` and `getComponentOperation`, as a Spring-free class (constructor injection, so it is mockable):

```java
package com.bytechef.platform.scheduler.db.task;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableOutput;
import com.bytechef.platform.component.facade.TriggerDefinitionFacade;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Renews a dynamic webhook subscription and stores the new trigger state. Returns the new expiration date, or null when
 * the component reported no further expiry.
 *
 * @author Ivica Cardic
 */
public class DynamicWebhookRefresher {

    private final JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry;
    private final TriggerDefinitionFacade triggerDefinitionFacade;
    private final TriggerStateService triggerStateService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public DynamicWebhookRefresher(
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, TriggerDefinitionFacade triggerDefinitionFacade,
        TriggerStateService triggerStateService, WorkflowService workflowService) {

        this.jobPrincipalAccessorRegistry = jobPrincipalAccessorRegistry;
        this.triggerDefinitionFacade = triggerDefinitionFacade;
        this.triggerStateService = triggerStateService;
        this.workflowService = workflowService;
    }

    @Nullable
    public Instant refresh(WorkflowExecutionId workflowExecutionId, long connectionId) {
        WorkflowNodeType workflowNodeType = getWorkflowNodeType(workflowExecutionId);
        WebhookEnableOutput output = OptionalUtils.get(triggerStateService.fetchValue(workflowExecutionId));

        output = triggerDefinitionFacade.executeDynamicWebhookRefresh(
            workflowNodeType.name(), workflowNodeType.version(), workflowNodeType.operation(), output.parameters(),
            connectionId);

        if (output == null) {
            return null;
        }

        triggerStateService.save(workflowExecutionId, output);

        return output.webhookExpirationDate();
    }

    private WorkflowNodeType getWorkflowNodeType(WorkflowExecutionId workflowExecutionId) {
        JobPrincipalAccessor jobPrincipalAccessor = jobPrincipalAccessorRegistry.getJobPrincipalAccessor(
            workflowExecutionId.getType());

        String workflowId = jobPrincipalAccessor.getWorkflowId(
            workflowExecutionId.getJobPrincipalId(), workflowExecutionId.getWorkflowUuid());

        Workflow workflow = workflowService.getWorkflow(workflowId);

        WorkflowTrigger workflowTrigger = WorkflowTrigger.of(workflowExecutionId.getTriggerName(), workflow);

        return WorkflowNodeType.ofType(workflowTrigger.getType());
    }
}
```

If any of those imports fails to resolve, add the owning project to `build.gradle.kts` — the same imports compile in `platform-scheduler-impl`, whose dependency block Task 1 copied, so this should not be needed.

`DynamicWebhookRefreshTaskFactory.java`:

```java
package com.bytechef.platform.scheduler.db.task;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH;

import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.CustomTask;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import java.time.Instant;

/**
 * @author Ivica Cardic
 */
public final class DynamicWebhookRefreshTaskFactory {

    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    static final int MAX_RETRIES = 3;

    private DynamicWebhookRefreshTaskFactory() {
    }

    public static CustomTask<DynamicWebhookRefreshData> create(DynamicWebhookRefresher refresher) {
        return Tasks.custom(DYNAMIC_WEBHOOK_REFRESH)
            .onFailure(new FailureHandler.MaxRetriesFailureHandler<>(
                MAX_RETRIES, new FailureHandler.OnFailureRetryLater<>(RETRY_DELAY)))
            .execute((taskInstance, executionContext) -> {
                DynamicWebhookRefreshData data = taskInstance.getData();
                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(taskInstance.getId());

                Instant newExpiry = ContextBinding.call(
                    workflowExecutionId.getTenantId(),
                    () -> refresher.refresh(workflowExecutionId, data.connectionId()));

                if (newExpiry == null) {
                    return new CompletionHandler.OnCompleteRemove<>();
                }

                return new RescheduleAt<>(newExpiry);
            });
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2. Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Create `DbSchedulerConfiguration`** (tasks are registered here; the schedulers and importer beans are added in Tasks 5 and 6):

```java
package com.bytechef.platform.scheduler.db.config;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.platform.component.facade.TriggerDefinitionFacade;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshData;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshTaskFactory;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefresher;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshTaskFactory;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeData;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeTaskFactory;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerTaskFactory;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerTaskFactory;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the db-scheduler provider. Every bean here exists only when bytechef.scheduler.provider=db-scheduler.
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef", name = "scheduler.provider", havingValue = "db-scheduler")
public class DbSchedulerConfiguration {

    @Bean
    DbSchedulerCustomizer dbSchedulerCustomizer(ObjectProvider<JsonMapper> jsonMapperProvider) {
        JsonMapper jsonMapper = jsonMapperProvider.getIfAvailable(() -> JsonMapper.builder()
            .build());

        return new DbSchedulerCustomizer() {

            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(new Jackson3Serializer(jsonMapper));
            }
        };
    }

    @Bean
    DynamicWebhookRefresher dynamicWebhookRefresher(
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry, TriggerDefinitionFacade triggerDefinitionFacade,
        TriggerStateService triggerStateService, WorkflowService workflowService) {

        return new DynamicWebhookRefresher(
            jobPrincipalAccessorRegistry, triggerDefinitionFacade, triggerStateService, workflowService);
    }

    @Bean
    Task<DynamicWebhookRefreshData> dynamicWebhookRefreshTask(DynamicWebhookRefresher dynamicWebhookRefresher) {
        return DynamicWebhookRefreshTaskFactory.create(dynamicWebhookRefresher);
    }

    @Bean
    Task<OAuth2TokenRefreshData> oauth2TokenRefreshTask(ConnectionFacade connectionFacade) {
        return OAuth2TokenRefreshTaskFactory.create(connectionFacade);
    }

    @Bean
    Task<OneTimeResumeData> oneTimeResumeTask(ApplicationEventPublisher eventPublisher) {
        return OneTimeResumeTaskFactory.create(eventPublisher);
    }

    @Bean
    Task<PollingTriggerData> pollingTriggerTask(ApplicationEventPublisher eventPublisher) {
        return PollingTriggerTaskFactory.create(eventPublisher);
    }

    @Bean
    Task<ScheduleTriggerData> scheduleTriggerTask(ApplicationEventPublisher eventPublisher) {
        return ScheduleTriggerTaskFactory.create(eventPublisher);
    }
}
```

The starter's `DbSchedulerAutoConfiguration` collects every `Task<?>` bean into the scheduler, and both it and `UiApiAutoConfiguration` read `DbSchedulerCustomizer.serializer()`, so the UI decodes the same JSON.

- [ ] **Step 6: Compile the app**

Run: `./gradlew :server:apps:server-app:compileJava > /tmp/t4b.log 2>&1; tr '\r' '\n' < /tmp/t4b.log | grep -E "BUILD|error:" | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Add db-scheduler task factories and provider configuration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `DbTriggerScheduler` and `DbConnectionRefreshScheduler`

**Files:**
- Create: `.../db/DbTriggerScheduler.java`, `.../db/DbConnectionRefreshScheduler.java`
- Modify: `.../db/config/DbSchedulerConfiguration.java` (two beans)
- Test: `.../src/test/java/com/bytechef/platform/scheduler/db/DbTriggerSchedulerTest.java`, `DbConnectionRefreshSchedulerTest.java`

**Interfaces:**
- Consumes: `SchedulerClient` (the starter's `com.github.kagkarlsson.scheduler.Scheduler` bean implements it), Task 3 descriptors/data.
- Produces: `TriggerScheduler` bean `dbTriggerScheduler`, `ConnectionRefreshScheduler` bean `dbConnectionRefreshScheduler`.

Quartz's `schedule(...)` deletes an existing job with the same key and re-creates it. The db-scheduler equivalent used here: `reschedule(SchedulableInstance)` (moves time and replaces data if the row exists) and, when that returns `false`, `scheduleIfNotExists`. Cancel of a missing instance throws `TaskInstanceException`; it is caught and logged, matching Quartz's log-and-continue.

- [ ] **Step 1: Write the failing tests**

`DbTriggerSchedulerTest.java`:

```java
package com.bytechef.platform.scheduler.db;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshData;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import java.time.Instant;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@ExtendWith(ObjectMapperSetupExtension.class)
class DbTriggerSchedulerTest {

    private final SchedulerClient schedulerClient = Mockito.mock(SchedulerClient.class);
    private final DbTriggerScheduler triggerScheduler = new DbTriggerScheduler(schedulerClient, 5);
    private final WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
        PlatformType.AUTOMATION, 1L, "workflow-uuid", "trigger_1");

    @Test
    void testScheduleScheduleTriggerStoresCronZoneAndOutputAccordingToData() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);

        triggerScheduler.scheduleScheduleTrigger(
            "0 0 9 * * ?", "Europe/Zagreb", Map.of("expression", "0 9 * * *"), workflowExecutionId);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER_NAME);
        Assertions.assertThat(instance.getId())
            .isEqualTo(workflowExecutionId.toString());
        Assertions.assertThat((ScheduleTriggerData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new ScheduleTriggerData("0 0 9 * * ?", "Europe/Zagreb", "{\"expression\":\"0 9 * * *\"}"));
    }

    @Test
    void testSchedulePollingTriggerIsDueNowWithConfiguredPeriod() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant before = Instant.now();

        triggerScheduler.schedulePollingTrigger(workflowExecutionId);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.POLLING_TRIGGER_NAME);
        Assertions.assertThat((PollingTriggerData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new PollingTriggerData(5));
        Assertions.assertThat(instance.getNextExecutionTime(before))
            .isBetween(before, Instant.now());
    }

    @Test
    void testScheduleDynamicWebhookTriggerRefreshUsesExpiryAndConnectionId() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant expiry = Instant.parse("2031-01-01T00:00:00Z");

        triggerScheduler.scheduleDynamicWebhookTriggerRefresh(expiry, "component", 1, workflowExecutionId, 77L);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH_NAME);
        Assertions.assertThat((DynamicWebhookRefreshData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new DynamicWebhookRefreshData(77L));
        Assertions.assertThat(instance.getNextExecutionTime(Instant.now()))
            .isEqualTo(expiry);
    }

    @Test
    void testScheduleOneTimeTaskStoresContinueParametersOnlyWhenPresent() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant executeAt = Instant.parse("2031-01-01T00:00:00Z");

        triggerScheduler.scheduleOneTimeTask(executeAt, Map.of("k", "v"), 42L);
        triggerScheduler.scheduleOneTimeTask(executeAt, Map.of(), 43L);

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient, Mockito.times(2))
            .scheduleIfNotExists(captor.capture());

        Assertions.assertThat((OneTimeResumeData) captor.getAllValues()
            .get(0)
            .getTaskInstance()
            .getData())
            .isEqualTo(new OneTimeResumeData(42L, "{\"k\":\"v\"}"));
        Assertions.assertThat((OneTimeResumeData) captor.getAllValues()
            .get(1)
            .getTaskInstance()
            .getData())
            .isEqualTo(new OneTimeResumeData(43L, null));
    }

    @Test
    void testScheduleReplacesExistingInstanceViaReschedule() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(true);

        triggerScheduler.schedulePollingTrigger(workflowExecutionId);

        Mockito.verify(schedulerClient, Mockito.never())
            .scheduleIfNotExists(Mockito.any(SchedulableInstance.class));
    }

    @Test
    void testCancelPollingTriggerCancelsByTaskAndInstanceId() {
        triggerScheduler.cancelPollingTrigger(workflowExecutionId.toString());

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.POLLING_TRIGGER.instanceId(workflowExecutionId.toString()));
    }

    @Test
    void testCancelDynamicWebhookTriggerRefreshTargetsItsOwnTask() {
        triggerScheduler.cancelDynamicWebhookTriggerRefresh(workflowExecutionId.toString());

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH.instanceId(workflowExecutionId.toString()));
    }

    @Test
    void testCancelOfMissingInstanceDoesNotThrow() {
        Mockito.doThrow(new TaskInstanceException("missing", "polling-trigger", "id"))
            .when(schedulerClient)
            .cancel(Mockito.any());

        Assertions.assertThatCode(() -> triggerScheduler.cancelScheduleTrigger(workflowExecutionId.toString()))
            .doesNotThrowAnyException();
    }

    private SchedulableInstance<?> capturedScheduleIfNotExists() {
        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient)
            .scheduleIfNotExists(captor.capture());

        return captor.getValue();
    }
}
```

If `TaskInstanceException`'s constructor signature differs from `(String message, String taskName, String instanceId)`, open `com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException` in the IDE and use its real one; the assertion is on the scheduler swallowing it.

`DbConnectionRefreshSchedulerTest.java`:

```java
package com.bytechef.platform.scheduler.db;

import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DbConnectionRefreshSchedulerTest {

    private final SchedulerClient schedulerClient = Mockito.mock(SchedulerClient.class);
    private final DbConnectionRefreshScheduler connectionRefreshScheduler =
        new DbConnectionRefreshScheduler(schedulerClient);

    @Test
    void testScheduleConnectionRefreshFiresFiveMinutesBeforeExpiryUnderTenantPrefixedId() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant expiry = Instant.parse("2031-01-01T00:00:00Z");

        connectionRefreshScheduler.scheduleConnectionRefresh(77L, expiry, "000001");

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient)
            .scheduleIfNotExists(captor.capture());

        SchedulableInstance<?> instance = captor.getValue();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH_NAME);
        Assertions.assertThat(instance.getId())
            .isEqualTo("00000177");
        Assertions.assertThat((OAuth2TokenRefreshData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new OAuth2TokenRefreshData(77L, "000001"));
        Assertions.assertThat(instance.getNextExecutionTime(Instant.now()))
            .isEqualTo(expiry.minus(Duration.ofMinutes(5)));
    }

    @Test
    void testCancelConnectionRefreshCancelsTenantPrefixedId() {
        connectionRefreshScheduler.cancelConnectionRefresh(77L, "000001");

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH.instanceId("00000177"));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:test --tests "*DbTriggerSchedulerTest" --tests "*DbConnectionRefreshSchedulerTest" > /tmp/t5.log 2>&1; tr '\r' '\n' < /tmp/t5.log | grep -E "BUILD|error:" | head -5`
Expected: compilation errors for the missing classes.

- [ ] **Step 3: Implement**

`DbTriggerScheduler.java`:

```java
package com.bytechef.platform.scheduler.db;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.ONE_TIME_RESUME;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.POLLING_TRIGGER;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.platform.scheduler.TriggerScheduler;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshData;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Ivica Cardic
 */
public class DbTriggerScheduler implements TriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(DbTriggerScheduler.class);

    private final int pollingTriggerCheckPeriod;
    private final SchedulerClient schedulerClient;

    @SuppressFBWarnings("EI")
    public DbTriggerScheduler(SchedulerClient schedulerClient, int pollingTriggerCheckPeriod) {
        this.schedulerClient = schedulerClient;
        this.pollingTriggerCheckPeriod = pollingTriggerCheckPeriod;
    }

    @Override
    public void cancelDynamicWebhookTriggerRefresh(String workflowExecutionId) {
        cancel(DYNAMIC_WEBHOOK_REFRESH, workflowExecutionId);
    }

    @Override
    public void cancelScheduleTrigger(String workflowExecutionId) {
        cancel(SCHEDULE_TRIGGER, workflowExecutionId);
    }

    @Override
    public void cancelPollingTrigger(String workflowExecutionId) {
        cancel(POLLING_TRIGGER, workflowExecutionId);
    }

    @Override
    public void scheduleDynamicWebhookTriggerRefresh(
        Instant webhookExpirationDate, String componentName, int componentVersion,
        WorkflowExecutionId workflowExecutionId, Long connectionId) {

        replace(
            DYNAMIC_WEBHOOK_REFRESH.instance(workflowExecutionId.toString())
                .data(new DynamicWebhookRefreshData(connectionId))
                .scheduledTo(webhookExpirationDate));
    }

    @Override
    public void scheduleScheduleTrigger(
        String pattern, String zoneId, Map<String, Object> output, WorkflowExecutionId workflowExecutionId) {

        replace(
            SCHEDULE_TRIGGER.instance(workflowExecutionId.toString())
                .data(new ScheduleTriggerData(pattern, zoneId, JsonUtils.write(output)))
                .scheduledAccordingToData());
    }

    @Override
    public void schedulePollingTrigger(WorkflowExecutionId workflowExecutionId) {
        replace(
            POLLING_TRIGGER.instance(workflowExecutionId.toString())
                .data(new PollingTriggerData(pollingTriggerCheckPeriod))
                .scheduledTo(Instant.now()));
    }

    @Override
    public void scheduleOneTimeTask(Instant executeAt, Map<String, ?> output, long jobId) {
        String continueParameters = output == null || output.isEmpty() ? null : JsonUtils.write(output);

        replace(
            ONE_TIME_RESUME.instance(String.valueOf(jobId))
                .data(new OneTimeResumeData(jobId, continueParameters))
                .scheduledTo(executeAt));
    }

    private void cancel(TaskDescriptor<?> taskDescriptor, String instanceId) {
        TaskInstanceId taskInstanceId = taskDescriptor.instanceId(instanceId);

        try {
            schedulerClient.cancel(taskInstanceId);

            log.trace("Cancelled task {} instance {}", taskDescriptor.getTaskName(), instanceId);
        } catch (TaskInstanceException e) {
            log.error("Task {} instance {} not found for cancellation", taskDescriptor.getTaskName(), instanceId);
        }
    }

    private <T> void replace(SchedulableInstance<T> schedulableInstance) {
        if (schedulerClient.reschedule(schedulableInstance)) {
            log.trace("Rescheduled task {} instance {}", schedulableInstance.getTaskName(), schedulableInstance.getId());

            return;
        }

        if (!schedulerClient.scheduleIfNotExists(schedulableInstance)) {
            log.warn(
                "Task {} instance {} was neither rescheduled nor created; it is probably executing right now",
                schedulableInstance.getTaskName(), schedulableInstance.getId());
        }
    }
}
```

`DbConnectionRefreshScheduler.java`:

```java
package com.bytechef.platform.scheduler.db;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH;

import com.bytechef.platform.scheduler.ConnectionRefreshScheduler;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Ivica Cardic
 */
public class DbConnectionRefreshScheduler implements ConnectionRefreshScheduler {

    static final Duration TOKEN_REFRESH_OFFSET = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(DbConnectionRefreshScheduler.class);

    private final SchedulerClient schedulerClient;

    @SuppressFBWarnings("EI")
    public DbConnectionRefreshScheduler(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @Override
    public void cancelConnectionRefresh(Long connectionId, String tenantId) {
        String instanceId = tenantId + connectionId;

        try {
            schedulerClient.cancel(OAUTH2_TOKEN_REFRESH.instanceId(instanceId));
        } catch (TaskInstanceException e) {
            log.error("Refresh token task not found for connectionId: {}, tenantId: {}", connectionId, tenantId);
        }
    }

    @Override
    public void scheduleConnectionRefresh(Long connectionId, Instant tokenExpirationTime, String tenantId) {
        SchedulableInstance<OAuth2TokenRefreshData> instance = OAUTH2_TOKEN_REFRESH.instance(tenantId + connectionId)
            .data(new OAuth2TokenRefreshData(connectionId, tenantId))
            .scheduledTo(tokenExpirationTime.minus(TOKEN_REFRESH_OFFSET));

        if (schedulerClient.reschedule(instance)) {
            return;
        }

        if (!schedulerClient.scheduleIfNotExists(instance)) {
            log.warn("Refresh token task for connectionId: {}, tenantId: {} could not be scheduled", connectionId,
                tenantId);
        }
    }
}
```

Add to `DbSchedulerConfiguration`:

```java
    @Bean
    ConnectionRefreshScheduler dbConnectionRefreshScheduler(SchedulerClient schedulerClient) {
        return new DbConnectionRefreshScheduler(schedulerClient);
    }

    @Bean
    TriggerScheduler dbTriggerScheduler(ApplicationProperties applicationProperties, SchedulerClient schedulerClient) {
        ApplicationProperties.Coordinator.Trigger.Polling polling = applicationProperties.getCoordinator()
            .getTrigger()
            .getPolling();

        return new DbTriggerScheduler(schedulerClient, polling.getCheckPeriod());
    }
```

with imports `com.bytechef.config.ApplicationProperties`, `com.bytechef.platform.scheduler.ConnectionRefreshScheduler`, `com.bytechef.platform.scheduler.TriggerScheduler`, `com.bytechef.platform.scheduler.db.DbConnectionRefreshScheduler`, `com.bytechef.platform.scheduler.db.DbTriggerScheduler`, `com.github.kagkarlsson.scheduler.SchedulerClient`. Mark both with `@Lazy` on the `SchedulerClient` parameter (as `QuartzSchedulerConfiguration` does for `Scheduler`) to avoid a startup cycle through the starter.

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2. Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Implement TriggerScheduler and ConnectionRefreshScheduler on db-scheduler

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: The Quartz importer

**Files:**
- Create under `.../db/importer/`: `ImportedJob.java`, `ImportSummary.java`, `QuartzJobReader.java`, `QuartzImporter.java`, `QuartzImportStarter.java`, `package-info.java`
- Modify: `.../db/config/DbSchedulerConfiguration.java` (importer beans)
- Test: `.../src/test/java/com/bytechef/platform/scheduler/db/importer/QuartzJobReaderTest.java`, `QuartzImporterTest.java`

**Interfaces:**
- Consumes: Quartz `org.quartz.Scheduler` (standby bean), `SchedulerClient`, Task 3 descriptors.
- Produces:
  - `sealed interface ImportedJob` with records `ScheduleTrigger(String instanceId, String cronPattern, String zoneId, String output, Instant nextFireTime)`, `PollingTrigger(String instanceId, Instant nextFireTime)`, `DynamicWebhookRefresh(String instanceId, long connectionId, Instant nextFireTime)`, `OAuth2TokenRefresh(String instanceId, long connectionId, String tenantId, Instant nextFireTime)`, `OneTimeResume(String instanceId, long jobId, @Nullable String continueParameters, Instant nextFireTime)`
  - `QuartzJobReader.read() : ReadResult` where `record ReadResult(List<ImportedJob> jobs, int skippedStatic, int skippedUnknown, int skippedComplete, int failed, boolean quartzReadable)`
  - `QuartzImporter.importJobs() : ImportSummary` where `record ImportSummary(int scanned, int imported, int alreadyPresent, int skippedStatic, int skippedUnknown, int skippedComplete, int failed, boolean quartzReadable)`
  - `QuartzImportStarter` — `@EventListener(ApplicationReadyEvent)` that calls `scheduleIfNotExists` for `QUARTZ_IMPORT.instance("startup").scheduledTo(now)`; `Task<Void>` bean `quartzImportTask`.

- [ ] **Step 1: Write the failing tests**

`QuartzJobReaderTest.java` — Quartz's builders produce real `JobDetail`/`Trigger` objects, so only the `Scheduler` is mocked:

```java
package com.bytechef.platform.scheduler.db.importer;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;

class QuartzJobReaderTest {

    // Job classes are matched by simple name, so these stand-ins reproduce the production names without a dependency
    // on the Quartz module.
    public static class ScheduleTriggerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class PollingTriggerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class DynamicWebhookTriggerRefreshJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class ConnectionOAuth2TokenRefreshJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class OneTimeSchedulerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class StripeUsageReportingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class SomethingElseJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    private final Scheduler scheduler = Mockito.mock(Scheduler.class);
    private final QuartzJobReader reader = new QuartzJobReader(scheduler);
    private final Date nextFireTime = Date.from(Instant.parse("2031-01-01T00:00:00Z"));

    @Test
    void testReadsScheduleTriggerWithCronZoneAndOutput() throws SchedulerException {
        JobDetail jobDetail = JobBuilder.newJob(ScheduleTriggerJob.class)
            .withIdentity("wfe-1", "ScheduleTrigger")
            .usingJobData("workflowExecutionId", "wfe-1")
            .usingJobData("output", "{\"expression\":\"0 9 * * *\"}")
            .build();
        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("wfe-1", "ScheduleTrigger")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 9 * * ?")
                .inTimeZone(TimeZone.getTimeZone("Europe/Zagreb")))
            .startAt(nextFireTime)
            .build();

        stub(jobDetail, trigger);

        QuartzJobReader.ReadResult result = reader.read();

        Assertions.assertThat(result.jobs())
            .containsExactly(new ImportedJob.ScheduleTrigger(
                "wfe-1", "0 0 9 * * ?", "Europe/Zagreb", "{\"expression\":\"0 9 * * *\"}",
                trigger.getNextFireTime()
                    .toInstant()));
    }

    @Test
    void testReadsDynamicWebhookRefreshUsingTheKeyTheWriterUsed() throws SchedulerException {
        JobDetail jobDetail = JobBuilder.newJob(DynamicWebhookTriggerRefreshJob.class)
            .withIdentity("wfe-2", "ScheduleTrigger")
            .usingJobData("workflowExecutionId", "wfe-2")
            .usingJobData("connectionId", 77L)
            .build();

        stub(jobDetail, oneShot("wfe-2", "ScheduleTrigger"));

        Assertions.assertThat(reader.read()
            .jobs())
            .containsExactly(new ImportedJob.DynamicWebhookRefresh("wfe-2", 77L, nextFireTime.toInstant()));
    }

    @Test
    void testReadsOAuth2RefreshPollingAndOneTimeResume() throws SchedulerException {
        JobDetail oauth = JobBuilder.newJob(ConnectionOAuth2TokenRefreshJob.class)
            .withIdentity("00000177", "ConnectionOauth2TokenRefresh")
            .usingJobData("connectionId", 77L)
            .usingJobData("tenantId", "000001")
            .build();
        JobDetail polling = JobBuilder.newJob(PollingTriggerJob.class)
            .withIdentity("wfe-3", "PollingTrigger")
            .usingJobData("workflowExecutionId", "wfe-3")
            .build();
        JobDetail resume = JobBuilder.newJob(OneTimeSchedulerJob.class)
            .withIdentity("42", "OneTimeTask")
            .usingJobData("jobId", 42L)
            .build();
        Trigger pollingTrigger = TriggerBuilder.newTrigger()
            .withIdentity("wfe-3", "PollingTrigger")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(5))
            .startAt(nextFireTime)
            .build();

        stub(List.of(oauth, polling, resume),
            List.of(oneShot("00000177", "ConnectionOauth2TokenRefresh"), pollingTrigger, oneShot("42", "OneTimeTask")));

        Assertions.assertThat(reader.read()
            .jobs())
            .containsExactlyInAnyOrder(
                new ImportedJob.OAuth2TokenRefresh("00000177", 77L, "000001", nextFireTime.toInstant()),
                new ImportedJob.PollingTrigger("wfe-3", nextFireTime.toInstant()),
                new ImportedJob.OneTimeResume("42", 42L, null, nextFireTime.toInstant()));
    }

    @Test
    void testSkipsStaticUnknownAndCompleteJobsAndCounts() throws SchedulerException {
        JobDetail stripe = JobBuilder.newJob(StripeUsageReportingJob.class)
            .withIdentity("stripeUsageReportingJob")
            .build();
        JobDetail unknown = JobBuilder.newJob(SomethingElseJob.class)
            .withIdentity("x", "y")
            .build();
        JobDetail complete = JobBuilder.newJob(PollingTriggerJob.class)
            .withIdentity("wfe-4", "PollingTrigger")
            .usingJobData("workflowExecutionId", "wfe-4")
            .build();

        Mockito.when(scheduler.getJobGroupNames())
            .thenReturn(List.of("DEFAULT", "y", "PollingTrigger"));
        Mockito.when(scheduler.getJobKeys(Mockito.any(GroupMatcher.class)))
            .thenAnswer(invocation -> Set.of(stripe.getKey(), unknown.getKey(), complete.getKey()));
        Mockito.when(scheduler.getJobDetail(stripe.getKey()))
            .thenReturn(stripe);
        Mockito.when(scheduler.getJobDetail(unknown.getKey()))
            .thenReturn(unknown);
        Mockito.when(scheduler.getJobDetail(complete.getKey()))
            .thenReturn(complete);
        Mockito.when(scheduler.getTriggersOfJob(Mockito.any(JobKey.class)))
            .thenReturn(List.of());

        QuartzJobReader.ReadResult result = reader.read();

        Assertions.assertThat(result.jobs())
            .isEmpty();
        Assertions.assertThat(result.skippedStatic())
            .isEqualTo(3);
        Assertions.assertThat(result.skippedUnknown())
            .isEqualTo(3);
        Assertions.assertThat(result.skippedComplete())
            .isEqualTo(3);
    }

    @Test
    void testMappingFailureIsCountedAndDoesNotAbort() throws SchedulerException {
        JobDetail broken = JobBuilder.newJob(OneTimeSchedulerJob.class)
            .withIdentity("broken", "OneTimeTask")
            .build();
        JobDetail good = JobBuilder.newJob(OneTimeSchedulerJob.class)
            .withIdentity("43", "OneTimeTask")
            .usingJobData("jobId", 43L)
            .build();

        stub(List.of(broken, good), List.of(oneShot("broken", "OneTimeTask"), oneShot("43", "OneTimeTask")));

        QuartzJobReader.ReadResult result = reader.read();

        Assertions.assertThat(result.failed())
            .isEqualTo(1);
        Assertions.assertThat(result.jobs())
            .containsExactly(new ImportedJob.OneTimeResume("43", 43L, null, nextFireTime.toInstant()));
    }

    @Test
    void testUnreadableJobStoreYieldsEmptyResult() throws SchedulerException {
        Mockito.when(scheduler.getJobGroupNames())
            .thenThrow(new SchedulerException("relation qrtz_job_details does not exist"));

        QuartzJobReader.ReadResult result = reader.read();

        Assertions.assertThat(result.quartzReadable())
            .isFalse();
        Assertions.assertThat(result.jobs())
            .isEmpty();
    }

    private Trigger oneShot(String name, String group) {
        return TriggerBuilder.newTrigger()
            .withIdentity(name, group)
            .startAt(nextFireTime)
            .build();
    }

    private void stub(JobDetail jobDetail, Trigger trigger) throws SchedulerException {
        stub(List.of(jobDetail), List.of(trigger));
    }

    private void stub(List<JobDetail> jobDetails, List<Trigger> triggers) throws SchedulerException {
        Mockito.when(scheduler.getJobGroupNames())
            .thenReturn(List.of("any"));
        Mockito.when(scheduler.getJobKeys(Mockito.any(GroupMatcher.class)))
            .thenReturn(Set.copyOf(jobDetails.stream()
                .map(JobDetail::getKey)
                .toList()));

        for (int index = 0; index < jobDetails.size(); index++) {
            JobDetail jobDetail = jobDetails.get(index);
            Trigger trigger = triggers.get(index);

            Mockito.when(scheduler.getJobDetail(jobDetail.getKey()))
                .thenReturn(jobDetail);
            Mockito.doReturn(List.of(trigger))
                .when(scheduler)
                .getTriggersOfJob(jobDetail.getKey());
        }
    }
}
```

The reader iterates groups and, for each, `getJobKeys(GroupMatcher.jobGroupEquals(group))`; the tests return the same key set for every group, so `testSkipsStaticUnknownAndCompleteJobsAndCounts` sees each job three times (three groups) — hence the counts of 3. Production dedupes on `JobKey`, which the last assertion in that test exercises once you add the `Set<JobKey>` seen-guard shown below.

`QuartzImporterTest.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class QuartzImporterTest {

    private final QuartzJobReader reader = Mockito.mock(QuartzJobReader.class);
    private final SchedulerClient schedulerClient = Mockito.mock(SchedulerClient.class);
    private final QuartzImporter importer = new QuartzImporter(reader, schedulerClient, 5);
    private final Instant future = Instant.parse("2031-01-01T00:00:00Z");

    @Test
    void testImportsEachKindPreservingNextFireTime() {
        Mockito.when(reader.read())
            .thenReturn(new QuartzJobReader.ReadResult(
                List.of(
                    new ImportedJob.ScheduleTrigger("wfe-1", "0 0 9 * * ?", "UTC", "{}", future),
                    new ImportedJob.PollingTrigger("wfe-2", future),
                    new ImportedJob.OAuth2TokenRefresh("00000177", 77L, "000001", future)),
                0, 0, 0, 0, true));
        Mockito.when(schedulerClient.scheduleIfNotExists(Mockito.any(SchedulableInstance.class)))
            .thenReturn(true);

        ImportSummary summary = importer.importJobs();

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient, Mockito.times(3))
            .scheduleIfNotExists(captor.capture());

        List<SchedulableInstance<?>> instances = captor.getAllValues();

        Assertions.assertThat(instances)
            .allSatisfy(instance -> Assertions.assertThat(instance.getNextExecutionTime(Instant.now()))
                .isEqualTo(future));
        Assertions.assertThat(instances.get(0)
            .getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER_NAME);
        Assertions.assertThat((ScheduleTriggerData) instances.get(0)
            .getTaskInstance()
            .getData())
            .isEqualTo(new ScheduleTriggerData("0 0 9 * * ?", "UTC", "{}"));
        Assertions.assertThat((PollingTriggerData) instances.get(1)
            .getTaskInstance()
            .getData())
            .isEqualTo(new PollingTriggerData(5));
        Assertions.assertThat((OAuth2TokenRefreshData) instances.get(2)
            .getTaskInstance()
            .getData())
            .isEqualTo(new OAuth2TokenRefreshData(77L, "000001"));
        Assertions.assertThat(summary.imported())
            .isEqualTo(3);
        Assertions.assertThat(summary.scanned())
            .isEqualTo(3);
    }

    @Test
    void testPastNextFireTimeBecomesDueNow() {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");

        Mockito.when(reader.read())
            .thenReturn(new QuartzJobReader.ReadResult(
                List.of(new ImportedJob.OneTimeResume("42", 42L, null, past)), 0, 0, 0, 0, true));
        Mockito.when(schedulerClient.scheduleIfNotExists(Mockito.any(SchedulableInstance.class)))
            .thenReturn(true);
        Instant before = Instant.now();

        importer.importJobs();

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient)
            .scheduleIfNotExists(captor.capture());
        Assertions.assertThat(captor.getValue()
            .getNextExecutionTime(before))
            .isBetween(before, Instant.now());
    }

    @Test
    void testAlreadyPresentInstancesAreCountedNotDuplicated() {
        Mockito.when(reader.read())
            .thenReturn(new QuartzJobReader.ReadResult(
                List.of(new ImportedJob.PollingTrigger("wfe-2", future)), 1, 2, 3, 4, true));
        Mockito.when(schedulerClient.scheduleIfNotExists(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);

        ImportSummary summary = importer.importJobs();

        Assertions.assertThat(summary)
            .isEqualTo(new ImportSummary(1, 0, 1, 1, 2, 3, 4, true));
    }

    @Test
    void testSchedulerClientFailureIsCountedAndDoesNotAbort() {
        Mockito.when(reader.read())
            .thenReturn(new QuartzJobReader.ReadResult(
                List.of(
                    new ImportedJob.PollingTrigger("wfe-a", future),
                    new ImportedJob.PollingTrigger("wfe-b", future)),
                0, 0, 0, 0, true));
        Mockito.when(schedulerClient.scheduleIfNotExists(Mockito.any(SchedulableInstance.class)))
            .thenThrow(new IllegalStateException("db down"))
            .thenReturn(true);

        ImportSummary summary = importer.importJobs();

        Assertions.assertThat(summary.failed())
            .isEqualTo(1);
        Assertions.assertThat(summary.imported())
            .isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:test --tests "*QuartzJobReaderTest" --tests "*QuartzImporterTest" > /tmp/t6.log 2>&1; tr '\r' '\n' < /tmp/t6.log | grep -E "BUILD|error:" | head -5`
Expected: compilation errors for the missing classes.

- [ ] **Step 3: Implement**

`ImportedJob.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A Quartz job read from the standby scheduler, already reduced to what the matching db-scheduler task needs.
 *
 * @author Ivica Cardic
 */
public sealed interface ImportedJob {

    String instanceId();

    Instant nextFireTime();

    record ScheduleTrigger(String instanceId, String cronPattern, String zoneId, String output, Instant nextFireTime)
        implements ImportedJob {
    }

    record PollingTrigger(String instanceId, Instant nextFireTime) implements ImportedJob {
    }

    record DynamicWebhookRefresh(String instanceId, long connectionId, Instant nextFireTime) implements ImportedJob {
    }

    record OAuth2TokenRefresh(String instanceId, long connectionId, String tenantId, Instant nextFireTime)
        implements ImportedJob {
    }

    record OneTimeResume(String instanceId, long jobId, @Nullable String continueParameters, Instant nextFireTime)
        implements ImportedJob {
    }
}
```

`ImportSummary.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

/**
 * @author Ivica Cardic
 */
public record ImportSummary(
    int scanned, int imported, int alreadyPresent, int skippedStatic, int skippedUnknown, int skippedComplete,
    int failed, boolean quartzReadable) {
}
```

`QuartzJobReader.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads every job from the standby Quartz scheduler through the Quartz API, so Quartz performs the JOB_DATA
 * deserialization. Jobs are recognised by job class name, never by group, because one writer used the wrong group
 * (bytechefhq/bytechef#5651).
 *
 * @author Ivica Cardic
 */
public class QuartzJobReader {

    public record ReadResult(
        List<ImportedJob> jobs, int skippedStatic, int skippedUnknown, int skippedComplete, int failed,
        boolean quartzReadable) {
    }

    private static final Logger log = LoggerFactory.getLogger(QuartzJobReader.class);

    private final Scheduler scheduler;

    @SuppressFBWarnings("EI")
    public QuartzJobReader(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public ReadResult read() {
        List<String> groupNames;

        try {
            groupNames = scheduler.getJobGroupNames();
        } catch (SchedulerException e) {
            log.info("Quartz job store is not readable, nothing to import: {}", e.getMessage());

            return new ReadResult(List.of(), 0, 0, 0, 0, false);
        }

        List<ImportedJob> jobs = new ArrayList<>();
        Set<JobKey> seenJobKeys = new HashSet<>();
        int skippedStatic = 0;
        int skippedUnknown = 0;
        int skippedComplete = 0;
        int failed = 0;

        for (String groupName : groupNames) {
            Set<JobKey> jobKeys;

            try {
                jobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName));
            } catch (SchedulerException e) {
                log.warn("Unable to list Quartz jobs in group {}", groupName, e);

                failed++;

                continue;
            }

            for (JobKey jobKey : jobKeys) {
                if (!seenJobKeys.add(jobKey)) {
                    continue;
                }

                try {
                    JobDetail jobDetail = scheduler.getJobDetail(jobKey);
                    Class<?> jobClass = jobDetail.getJobClass();
                    String jobClassName = jobClass.getSimpleName();

                    if ("StripeUsageReportingJob".equals(jobClassName)) {
                        skippedStatic++;

                        continue;
                    }

                    if (!isImportable(jobClassName)) {
                        log.warn("Skipping Quartz job {} of unknown class {}", jobKey, jobClassName);

                        skippedUnknown++;

                        continue;
                    }

                    Instant nextFireTime = getNextFireTime(jobKey);

                    if (nextFireTime == null) {
                        skippedComplete++;

                        continue;
                    }

                    jobs.add(toImportedJob(jobClassName, jobKey, jobDetail.getJobDataMap(), nextFireTime));
                } catch (Exception e) {
                    log.warn("Unable to read Quartz job {}", jobKey, e);

                    failed++;
                }
            }
        }

        return new ReadResult(jobs, skippedStatic, skippedUnknown, skippedComplete, failed, true);
    }

    private static boolean isImportable(String jobClassName) {
        return switch (jobClassName) {
            case "ScheduleTriggerJob", "PollingTriggerJob", "DynamicWebhookTriggerRefreshJob",
                "ConnectionOAuth2TokenRefreshJob", "OneTimeSchedulerJob" -> true;
            default -> false;
        };
    }

    @Nullable
    private Instant getNextFireTime(JobKey jobKey) throws SchedulerException {
        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

        if (triggers.isEmpty()) {
            return null;
        }

        Trigger trigger = triggers.getFirst();
        Date nextFireTime = trigger.getNextFireTime();

        return nextFireTime == null ? null : nextFireTime.toInstant();
    }

    private ImportedJob toImportedJob(
        String jobClassName, JobKey jobKey, JobDataMap jobDataMap, Instant nextFireTime) throws SchedulerException {

        String instanceId = jobKey.getName();

        return switch (jobClassName) {
            case "ScheduleTriggerJob" -> {
                CronTrigger cronTrigger = (CronTrigger) scheduler.getTriggersOfJob(jobKey)
                    .getFirst();

                yield new ImportedJob.ScheduleTrigger(
                    instanceId, cronTrigger.getCronExpression(),
                    cronTrigger.getTimeZone()
                        .getID(),
                    jobDataMap.getString("output"), nextFireTime);
            }
            case "PollingTriggerJob" -> new ImportedJob.PollingTrigger(instanceId, nextFireTime);
            case "DynamicWebhookTriggerRefreshJob" -> new ImportedJob.DynamicWebhookRefresh(
                instanceId, jobDataMap.getLong("connectionId"), nextFireTime);
            case "ConnectionOAuth2TokenRefreshJob" -> new ImportedJob.OAuth2TokenRefresh(
                instanceId, jobDataMap.getLong("connectionId"), jobDataMap.getString("tenantId"), nextFireTime);
            case "OneTimeSchedulerJob" -> new ImportedJob.OneTimeResume(
                instanceId, jobDataMap.getLong("jobId"),
                jobDataMap.containsKey("continueParameters") ? jobDataMap.getString("continueParameters") : null,
                nextFireTime);
            default -> throw new IllegalStateException("Unexpected job class " + jobClassName);
        };
    }
}
```

`QuartzImporter.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.ONE_TIME_RESUME;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.POLLING_TRIGGER;
import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER;

import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshData;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies Quartz jobs into db-scheduler with scheduleIfNotExists. Idempotent; never touches Quartz rows.
 *
 * @author Ivica Cardic
 */
public class QuartzImporter {

    private static final Logger log = LoggerFactory.getLogger(QuartzImporter.class);

    private final int pollingTriggerCheckPeriod;
    private final QuartzJobReader quartzJobReader;
    private final SchedulerClient schedulerClient;

    @SuppressFBWarnings("EI")
    public QuartzImporter(QuartzJobReader quartzJobReader, SchedulerClient schedulerClient, int pollingTriggerCheckPeriod) {
        this.quartzJobReader = quartzJobReader;
        this.schedulerClient = schedulerClient;
        this.pollingTriggerCheckPeriod = pollingTriggerCheckPeriod;
    }

    public ImportSummary importJobs() {
        QuartzJobReader.ReadResult readResult = quartzJobReader.read();

        int imported = 0;
        int alreadyPresent = 0;
        int failed = readResult.failed();

        for (ImportedJob importedJob : readResult.jobs()) {
            try {
                if (schedulerClient.scheduleIfNotExists(toSchedulableInstance(importedJob))) {
                    imported++;
                } else {
                    alreadyPresent++;
                }
            } catch (RuntimeException e) {
                log.warn("Unable to import Quartz job {}", importedJob.instanceId(), e);

                failed++;
            }
        }

        return new ImportSummary(
            readResult.jobs()
                .size(),
            imported, alreadyPresent, readResult.skippedStatic(), readResult.skippedUnknown(),
            readResult.skippedComplete(), failed, readResult.quartzReadable());
    }

    private SchedulableInstance<?> toSchedulableInstance(ImportedJob importedJob) {
        Instant executionTime = dueNoEarlierThanNow(importedJob.nextFireTime());

        return switch (importedJob) {
            case ImportedJob.ScheduleTrigger job -> SCHEDULE_TRIGGER.instance(job.instanceId())
                .data(new ScheduleTriggerData(job.cronPattern(), job.zoneId(), job.output()))
                .scheduledTo(executionTime);
            case ImportedJob.PollingTrigger job -> POLLING_TRIGGER.instance(job.instanceId())
                .data(new PollingTriggerData(pollingTriggerCheckPeriod))
                .scheduledTo(executionTime);
            case ImportedJob.DynamicWebhookRefresh job -> DYNAMIC_WEBHOOK_REFRESH.instance(job.instanceId())
                .data(new DynamicWebhookRefreshData(job.connectionId()))
                .scheduledTo(executionTime);
            case ImportedJob.OAuth2TokenRefresh job -> OAUTH2_TOKEN_REFRESH.instance(job.instanceId())
                .data(new OAuth2TokenRefreshData(job.connectionId(), job.tenantId()))
                .scheduledTo(executionTime);
            case ImportedJob.OneTimeResume job -> ONE_TIME_RESUME.instance(job.instanceId())
                .data(new OneTimeResumeData(job.jobId(), job.continueParameters()))
                .scheduledTo(executionTime);
        };
    }

    private static Instant dueNoEarlierThanNow(Instant nextFireTime) {
        Instant now = Instant.now();

        return nextFireTime.isBefore(now) ? now : nextFireTime;
    }
}
```

`QuartzImportStarter.java`:

```java
package com.bytechef.platform.scheduler.db.importer;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.QUARTZ_IMPORT;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Queues the one-time quartz-import task on every startup. In a cluster every node calls scheduleIfNotExists; one row
 * wins, one node runs the import.
 *
 * @author Ivica Cardic
 */
public class QuartzImportStarter {

    static final String STARTUP_INSTANCE_ID = "startup";

    private static final Logger log = LoggerFactory.getLogger(QuartzImportStarter.class);

    private final SchedulerClient schedulerClient;

    @SuppressFBWarnings("EI")
    public QuartzImportStarter(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean queued = schedulerClient.scheduleIfNotExists(
            QUARTZ_IMPORT.instance(STARTUP_INSTANCE_ID)
                .scheduledTo(Instant.now()));

        if (log.isDebugEnabled()) {
            log.debug("Quartz import task {}", queued ? "queued" : "already queued by another node");
        }
    }
}
```

Add to `DbSchedulerConfiguration` (imports: `com.bytechef.platform.scheduler.db.importer.*`, `com.github.kagkarlsson.scheduler.task.helper.Tasks`, `org.quartz.Scheduler` as `QuartzScheduler` via `import org.quartz.Scheduler;` — there is no db-scheduler `Scheduler` import in this class, so no clash; `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` already imported):

```java
    @Bean
    @ConditionalOnProperty(
        prefix = "bytechef", name = "scheduler.db-scheduler.importer.enabled", havingValue = "true",
        matchIfMissing = true)
    QuartzImportStarter quartzImportStarter(@Lazy SchedulerClient schedulerClient) {
        return new QuartzImportStarter(schedulerClient);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "bytechef", name = "scheduler.db-scheduler.importer.enabled", havingValue = "true",
        matchIfMissing = true)
    Task<Void> quartzImportTask(
        ApplicationProperties applicationProperties, ObjectProvider<Scheduler> quartzSchedulerProvider,
        @Lazy SchedulerClient schedulerClient) {

        ApplicationProperties.Coordinator.Trigger.Polling polling = applicationProperties.getCoordinator()
            .getTrigger()
            .getPolling();

        return Tasks.oneTime(QUARTZ_IMPORT)
            .execute((taskInstance, executionContext) -> {
                Scheduler quartzScheduler = quartzSchedulerProvider.getIfAvailable();

                if (quartzScheduler == null) {
                    log.info("No Quartz scheduler bean present, nothing to import");

                    return;
                }

                QuartzImporter quartzImporter = new QuartzImporter(
                    new QuartzJobReader(quartzScheduler), schedulerClient, polling.getCheckPeriod());

                ImportSummary summary = quartzImporter.importJobs();

                log.info(
                    "Quartz import: scanned={}, imported={}, alreadyPresent={}, skippedStatic={}, skippedUnknown={}, " +
                        "skippedComplete={}, failed={}, quartzReadable={}",
                    summary.scanned(), summary.imported(), summary.alreadyPresent(), summary.skippedStatic(),
                    summary.skippedUnknown(), summary.skippedComplete(), summary.failed(), summary.quartzReadable());
            });
    }
```

Add `private static final Logger log = LoggerFactory.getLogger(DbSchedulerConfiguration.class);` and the `QUARTZ_IMPORT` static import to the configuration class. Reading Quartz through `ObjectProvider` keeps the module bootable in tests and apps that have no Quartz bean.

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2. Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 5: Compile the app and commit**

Run: `./gradlew :server:apps:server-app:compileJava > /tmp/t6b.log 2>&1; tr '\r' '\n' < /tmp/t6b.log | grep -E "BUILD|error:" | tail -3` — expected `BUILD SUCCESSFUL`.

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Add one-way Quartz to db-scheduler importer

Reads through the standby Quartz Scheduler API, dispatches on job class name,
preserves nextFireTime, writes with scheduleIfNotExists only, and is queued as
a one-time db-scheduler task on every startup so exactly one node runs it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Billing's hourly Stripe report on db-scheduler

**Files:**
- Modify: `server/libs/platform/platform-billing/platform-billing-service/build.gradle.kts`
- Create: `server/libs/platform/platform-billing/platform-billing-service/src/main/java/com/bytechef/platform/billing/config/BillingDbSchedulingConfiguration.java`
- Test: `server/libs/platform/platform-billing/platform-billing-service/src/test/java/com/bytechef/platform/billing/config/BillingDbSchedulingConfigurationTest.java`

**Interfaces:**
- Consumes: `BillingUsageService.reportUsage(Instant)` (same call `StripeUsageReportingJob` makes), `DbSchedulerTaskDescriptors.STRIPE_USAGE_REPORT`.
- Produces: `Task<Void>` bean `stripeUsageReportTask` — a static recurring task; the starter schedules it on startup by itself.

The existing `BillingSchedulingConfiguration` is gated on `'${bytechef.coordinator.trigger.scheduler.provider:quartz}'.equals('quartz')`. That property key does not exist (the real key is `bytechef.scheduler.provider`), so today it always evaluates to the default `quartz`. Leave it alone — under `db-scheduler` its Quartz `JobDetail`/`CronTrigger` beans are created but the Quartz scheduler never starts, so they are inert. Do not "fix" that condition in this plan; it is noted in §7 follow-ups of the spec by this task.

- [ ] **Step 1: Add dependencies** to billing's `build.gradle.kts`:

```kotlin
    implementation("com.github.kagkarlsson:db-scheduler:16.12.0")
    implementation(project(":server:libs:platform:platform-scheduler:platform-scheduler-db"))
```

- [ ] **Step 2: Write the failing test**

```java
package com.bytechef.platform.billing.config;

import com.bytechef.platform.billing.service.BillingUsageService;
import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BillingDbSchedulingConfigurationTest {

    @Test
    void testStripeUsageReportTaskReportsScheduledExecutionTime() {
        BillingUsageService billingUsageService = Mockito.mock(BillingUsageService.class);
        Task<Void> task = new BillingDbSchedulingConfiguration().stripeUsageReportTask(billingUsageService);
        Instant executionTime = Instant.parse("2031-01-01T10:00:00Z");
        TaskInstance<Void> taskInstance = DbSchedulerTaskDescriptors.STRIPE_USAGE_REPORT.instance("recurring")
            .build();
        ExecutionContext executionContext = new ExecutionContext(
            Mockito.mock(SchedulerState.class), new Execution(executionTime, taskInstance),
            Mockito.mock(SchedulerClient.class), Mockito.mock(CurrentlyExecuting.class));

        task.execute(taskInstance, executionContext);

        Mockito.verify(billingUsageService)
            .reportUsage(executionTime);
        Assertions.assertThat(task.getName())
            .isEqualTo(DbSchedulerTaskDescriptors.STRIPE_USAGE_REPORT_NAME);
    }
}
```

Check the exact package of `BillingUsageService` in `StripeUsageReportingJob.java`'s imports and use it.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :server:libs:platform:platform-billing:platform-billing-service:test --tests "*BillingDbSchedulingConfigurationTest" > /tmp/t7.log 2>&1; tr '\r' '\n' < /tmp/t7.log | grep -E "BUILD|error:" | head -5`
Expected: compilation error, class missing.

- [ ] **Step 4: Implement**

```java
package com.bytechef.platform.billing.config;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.STRIPE_USAGE_REPORT;

import com.bytechef.platform.billing.exception.PaymentClientException;
import com.bytechef.platform.billing.service.BillingUsageService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hourly Stripe usage report as a db-scheduler recurring task; the db-scheduler twin of BillingSchedulingConfiguration.
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnExpression("${billing.enabled:false} " +
    "and '${bytechef.scheduler.provider:quartz}'.equals('db-scheduler')")
public class BillingDbSchedulingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BillingDbSchedulingConfiguration.class);

    @Bean
    public Task<Void> stripeUsageReportTask(BillingUsageService billingUsageService) {
        return Tasks.recurring(STRIPE_USAGE_REPORT, new CronSchedule("0 0 * * * ?", ZoneOffset.UTC, CronStyle.QUARTZ))
            .execute((taskInstance, executionContext) -> {
                log.info("Start Stripe usage report");

                try {
                    billingUsageService.reportUsage(executionContext.getExecution().executionTime);
                } catch (PaymentClientException paymentClientException) {
                    log.error("Failed to report Stripe usage", paymentClientException);
                }
            });
    }
}
```

Use the same `PaymentClientException` import `StripeUsageReportingJob` uses.

- [ ] **Step 5: Run the test to verify it passes**, then commit

Run: same command as Step 3. Expected: `BUILD SUCCESSFUL`.

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-billing/platform-billing-service
git commit -m "5650 Add db-scheduler variant of the hourly Stripe usage report

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Integration tests — provider contract, importer round-trip, gating

**Files:**
- Create under `.../platform-scheduler-db/src/test/java/com/bytechef/platform/scheduler/db/`: `config/DbSchedulerTestConfiguration.java`, `DbTriggerSchedulerIntTest.java`, `importer/QuartzImportIntTest.java`, `config/SchedulerProviderGatingIntTest.java`
- Create: `.../platform-scheduler-db/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: everything from Tasks 1–6, `com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration` (from `test-int-support`), `com.bytechef.test.extension.ObjectMapperSetupExtension` (from `test-support`).

The Quartz module's `QuartzTriggerSchedulerIntTest` is the template: `@SpringBootTest(classes = <test configuration>, properties = "spring.profiles.active=test")` + `@Import(PostgreSQLContainerConfiguration.class)`. Liquibase runs `master.xml` from `liquibase-config` — add `testImplementation(project(":server:libs:config:liquibase-config"))` to the module's `build.gradle.kts` so both the Quartz and db-scheduler changelogs are applied to the container.

- [ ] **Step 1: Test resources and configuration**

`src/test/resources/application-test.yml`:

```yaml
bytechef:
  coordinator:
    trigger:
      polling:
        check-period: 1
  scheduler:
    provider: db-scheduler
  tenant:
    mode: SINGLE

db-scheduler:
  polling-interval: 250ms
  polling-strategy: lock-and-fetch
  threads: 2
  delay-startup-until-context-ready: true

spring:
  liquibase:
    contexts: mono
  quartz:
    auto-startup: false
    job-store-type: jdbc
    jdbc:
      initialize-schema: never
    properties:
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
```

`config/DbSchedulerTestConfiguration.java` — the collaborators the task factories inject are mocked; the events the tasks publish are captured:

```java
package com.bytechef.platform.scheduler.db.config;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.event.ResumeJobEvent;
import com.bytechef.platform.component.facade.TriggerDefinitionFacade;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.workflow.coordinator.event.TriggerListenerEvent;
import com.bytechef.platform.workflow.coordinator.event.TriggerPollEvent;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

@SpringBootConfiguration
@EnableAutoConfiguration
public class DbSchedulerTestConfiguration {

    public static class CapturedEvents {

        public final List<TriggerListenerEvent> listenerEvents = new CopyOnWriteArrayList<>();
        public final List<TriggerPollEvent> pollEvents = new CopyOnWriteArrayList<>();
        public final List<ResumeJobEvent> resumeEvents = new CopyOnWriteArrayList<>();

        @EventListener
        public void onListener(TriggerListenerEvent event) {
            listenerEvents.add(event);
        }

        @EventListener
        public void onPoll(TriggerPollEvent event) {
            pollEvents.add(event);
        }

        @EventListener
        public void onResume(ResumeJobEvent event) {
            resumeEvents.add(event);
        }
    }

    @Bean
    CapturedEvents capturedEvents() {
        return new CapturedEvents();
    }

    @Bean
    ConnectionFacade connectionFacade() {
        return Mockito.mock(ConnectionFacade.class);
    }

    @Bean
    JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry() {
        return Mockito.mock(JobPrincipalAccessorRegistry.class);
    }

    @Bean
    TriggerDefinitionFacade triggerDefinitionFacade() {
        return Mockito.mock(TriggerDefinitionFacade.class);
    }

    @Bean
    TriggerStateService triggerStateService() {
        return Mockito.mock(TriggerStateService.class);
    }

    @Bean
    WorkflowService workflowService() {
        return Mockito.mock(WorkflowService.class);
    }
}
```

`@EnableAutoConfiguration` pulls in `DataSourceAutoConfiguration`, `LiquibaseAutoConfiguration`, `QuartzAutoConfiguration` (standby, `auto-startup: false`) and — because the `EnvironmentPostProcessor` sees `provider=db-scheduler` — `DbSchedulerAutoConfiguration`. `DbSchedulerConfiguration` is picked up by component scanning from this configuration's package (`com.bytechef.platform.scheduler.db.config`).

- [ ] **Step 2: Write `DbTriggerSchedulerIntTest`**

```java
package com.bytechef.platform.scheduler.db;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.scheduler.ConnectionRefreshScheduler;
import com.bytechef.platform.scheduler.TriggerScheduler;
import com.bytechef.platform.scheduler.db.config.DbSchedulerTestConfiguration;
import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(ObjectMapperSetupExtension.class)
@SpringBootTest(classes = DbSchedulerTestConfiguration.class, properties = "spring.profiles.active=test")
@Import(PostgreSQLContainerConfiguration.class)
class DbTriggerSchedulerIntTest {

    @Autowired
    private DbSchedulerTestConfiguration.CapturedEvents capturedEvents;

    @Autowired
    private ConnectionRefreshScheduler connectionRefreshScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SchedulerClient schedulerClient;

    @Autowired
    private TriggerScheduler triggerScheduler;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM scheduled_tasks");
        capturedEvents.listenerEvents.clear();
        capturedEvents.pollEvents.clear();
        capturedEvents.resumeEvents.clear();
    }

    @Test
    void testScheduleTriggerFiresTriggerListenerEventWithFireTimeAndDateTime() {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 1L, "schedule-workflow", "trigger_1");

        triggerScheduler.scheduleScheduleTrigger(
            "0/1 * * * * ?", "UTC", Map.of("expression", "* * * * *", "timezone", "UTC"), workflowExecutionId);

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .until(() -> !capturedEvents.listenerEvents.isEmpty());

        Object output = capturedEvents.listenerEvents.getFirst()
            .getListenerParameters()
            .output();

        Assertions.assertThat((Map<?, ?>) output)
            .containsKeys("fireTime", "dateTime", "expression", "timezone");
        Assertions.assertThat(((Map<?, ?>) output).get("dateTime"))
            .isInstanceOf(LocalDateTime.class);
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER.instanceId(workflowExecutionId.toString())))
            .as("recurring instance still scheduled after a fire")
            .isPresent();
    }

    @Test
    void testPollingTriggerFiresTriggerPollEventImmediatelyAndRepeats() {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 2L, "polling-workflow", "trigger_1");

        triggerScheduler.schedulePollingTrigger(workflowExecutionId);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> !capturedEvents.pollEvents.isEmpty());

        Assertions.assertThat(capturedEvents.pollEvents.getFirst()
            .getWorkflowExecutionId())
            .isEqualTo(workflowExecutionId);
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.POLLING_TRIGGER.instanceId(workflowExecutionId.toString())))
            .isPresent();
    }

    @Test
    void testOneTimeTaskFiresResumeJobEventOnceAndDisappears() {
        triggerScheduler.scheduleOneTimeTask(Instant.now()
            .plusSeconds(1), Map.of("k", "v"), 42L);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> !capturedEvents.resumeEvents.isEmpty());

        Assertions.assertThat(capturedEvents.resumeEvents.getFirst()
            .getJobId())
            .isEqualTo(42L);
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> schedulerClient.getScheduledExecution(
                DbSchedulerTaskDescriptors.ONE_TIME_RESUME.instanceId("42"))
                .isEmpty());
    }

    @Test
    void testCancelRemovesScheduledInstance() {
        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 3L, "cancel-workflow", "trigger_1");

        triggerScheduler.scheduleScheduleTrigger("0 0 9 * * ?", "UTC", Map.of(), workflowExecutionId);
        triggerScheduler.cancelScheduleTrigger(workflowExecutionId.toString());

        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER.instanceId(workflowExecutionId.toString())))
            .isEmpty();
    }

    @Test
    void testConnectionRefreshIsScheduledFiveMinutesBeforeExpiry() {
        Instant expiry = Instant.now()
            .plus(Duration.ofHours(1));

        connectionRefreshScheduler.scheduleConnectionRefresh(77L, expiry, "public");

        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH.instanceId("public77")))
            .isPresent()
            .get()
            .satisfies(execution -> Assertions.assertThat(execution.getExecutionTime())
                .isCloseTo(expiry.minus(Duration.ofMinutes(5)), Assertions.within(Duration.ofSeconds(1))));
    }
}
```

`ScheduledExecution.getExecutionTime()` returns `Instant`. Delete `TaskTestSupport` if a later reviewer prefers; it is test-only.

- [ ] **Step 3: Write `QuartzImportIntTest`** — seeds through the **real** Quartz API into the Liquibase-created `QRTZ_*` tables (the standby Quartz `Scheduler` bean is autowired from `QuartzAutoConfiguration`), runs the importer twice, and asserts on both stores. The dynamic-webhook row is written exactly as `QuartzTriggerScheduler.scheduleDynamicWebhookTriggerRefresh` writes it today.

```java
package com.bytechef.platform.scheduler.db.importer;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.scheduler.db.config.DbSchedulerTestConfiguration;
import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(ObjectMapperSetupExtension.class)
@SpringBootTest(
    classes = DbSchedulerTestConfiguration.class,
    properties = {
        "spring.profiles.active=test", "bytechef.scheduler.db-scheduler.importer.enabled=false"
    })
@Import(PostgreSQLContainerConfiguration.class)
class QuartzImportIntTest {

    // Same simple names as the production Quartz jobs; the reader dispatches on simple name.
    public static class ScheduleTriggerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class PollingTriggerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class DynamicWebhookTriggerRefreshJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class ConnectionOAuth2TokenRefreshJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class OneTimeSchedulerJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    public static class StripeUsageReportingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Scheduler quartzScheduler;

    @Autowired
    private SchedulerClient schedulerClient;

    private final Instant future = Instant.now()
        .plus(Duration.ofDays(1))
        .truncatedTo(ChronoUnit.MILLIS);

    @AfterEach
    void tearDown() throws SchedulerException {
        quartzScheduler.clear();
        jdbcTemplate.update("DELETE FROM scheduled_tasks");
    }

    @Test
    void testImportsEveryKindPreservesNextFireTimeIsIdempotentAndLeavesQuartzUntouched() throws SchedulerException {
        WorkflowExecutionId scheduleId = WorkflowExecutionId.of(PlatformType.AUTOMATION, 1L, "s", "t");
        WorkflowExecutionId pollingId = WorkflowExecutionId.of(PlatformType.AUTOMATION, 2L, "p", "t");
        WorkflowExecutionId webhookId = WorkflowExecutionId.of(PlatformType.AUTOMATION, 3L, "w", "t");

        quartzScheduler.scheduleJob(
            JobBuilder.newJob(ScheduleTriggerJob.class)
                .withIdentity(scheduleId.toString(), "ScheduleTrigger")
                .usingJobData("output", "{\"expression\":\"0 9 * * *\",\"timezone\":\"Europe/Zagreb\"}")
                .usingJobData("workflowExecutionId", scheduleId.toString())
                .build(),
            TriggerBuilder.newTrigger()
                .withIdentity(scheduleId.toString(), "ScheduleTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 9 * * ?")
                    .inTimeZone(TimeZone.getTimeZone("Europe/Zagreb")))
                .startNow()
                .build());
        quartzScheduler.scheduleJob(
            JobBuilder.newJob(PollingTriggerJob.class)
                .withIdentity(pollingId.toString(), "PollingTrigger")
                .usingJobData("workflowExecutionId", pollingId.toString())
                .build(),
            TriggerBuilder.newTrigger()
                .withIdentity(pollingId.toString(), "PollingTrigger")
                .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(5))
                .startAt(Date.from(future))
                .build());
        // written exactly as QuartzTriggerScheduler.scheduleDynamicWebhookTriggerRefresh writes it today (#5651)
        quartzScheduler.scheduleJob(
            JobBuilder.newJob(DynamicWebhookTriggerRefreshJob.class)
                .withIdentity(webhookId.toString(), "ScheduleTrigger")
                .usingJobData("workflowExecutionId", webhookId.toString())
                .usingJobData("connectionId", 77L)
                .build(),
            TriggerBuilder.newTrigger()
                .withIdentity(webhookId.toString(), "ScheduleTrigger")
                .startAt(Date.from(future))
                .build());
        quartzScheduler.scheduleJob(
            JobBuilder.newJob(ConnectionOAuth2TokenRefreshJob.class)
                .withIdentity("public77", "ConnectionOauth2TokenRefresh")
                .usingJobData("connectionId", 77L)
                .usingJobData("tenantId", "public")
                .build(),
            TriggerBuilder.newTrigger()
                .withIdentity("public77", "ConnectionOauth2TokenRefresh")
                .startAt(Date.from(future))
                .build());
        quartzScheduler.scheduleJob(
            JobBuilder.newJob(OneTimeSchedulerJob.class)
                .withIdentity("42", "OneTimeTask")
                .usingJobData("jobId", 42L)
                .usingJobData("continueParameters", "{\"k\":\"v\"}")
                .build(),
            TriggerBuilder.newTrigger()
                .withIdentity("42", "OneTimeTask")
                .startAt(Date.from(future))
                .build());
        quartzScheduler.addJob(
            JobBuilder.newJob(StripeUsageReportingJob.class)
                .withIdentity("stripeUsageReportingJob")
                .storeDurably()
                .build(),
            true);

        Trigger scheduleTrigger = quartzScheduler.getTrigger(
            org.quartz.TriggerKey.triggerKey(scheduleId.toString(), "ScheduleTrigger"));
        Instant scheduleNextFireTime = scheduleTrigger.getNextFireTime()
            .toInstant();
        List<Map<String, Object>> quartzRowsBefore = jdbcTemplate.queryForList(
            "SELECT job_name, job_group, job_class_name FROM qrtz_job_details ORDER BY job_name");

        QuartzImporter importer = new QuartzImporter(new QuartzJobReader(quartzScheduler), schedulerClient, 5);

        ImportSummary first = importer.importJobs();
        ImportSummary second = importer.importJobs();

        Assertions.assertThat(first)
            .isEqualTo(new ImportSummary(5, 5, 0, 1, 0, 0, 0, true));
        Assertions.assertThat(second)
            .isEqualTo(new ImportSummary(5, 0, 5, 1, 0, 0, 0, true));

        Assertions.assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scheduled_tasks", Integer.class))
            .isEqualTo(5);

        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER.instanceId(scheduleId.toString())))
            .get()
            .satisfies(execution -> {
                Assertions.assertThat(execution.getExecutionTime())
                    .as("cron instance keeps Quartz's next fire time instead of recomputing from now")
                    .isEqualTo(scheduleNextFireTime);
                Assertions.assertThat((ScheduleTriggerData) execution.getData())
                    .isEqualTo(new ScheduleTriggerData(
                        "0 0 9 * * ?", "Europe/Zagreb", "{\"expression\":\"0 9 * * *\",\"timezone\":\"Europe/Zagreb\"}"));
            });
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH.instanceId(webhookId.toString())))
            .as("broken-group dynamic webhook row imports into its own task")
            .get()
            .satisfies(execution -> Assertions.assertThat(execution.getExecutionTime())
                .isEqualTo(future));
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH.instanceId("public77")))
            .get()
            .satisfies(execution -> Assertions.assertThat(execution.getExecutionTime())
                .isEqualTo(future));
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.ONE_TIME_RESUME.instanceId("42")))
            .isPresent();
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.POLLING_TRIGGER.instanceId(pollingId.toString())))
            .isPresent();

        Assertions.assertThat(jdbcTemplate.queryForList(
            "SELECT job_name, job_group, job_class_name FROM qrtz_job_details ORDER BY job_name"))
            .as("Quartz rows are untouched")
            .isEqualTo(quartzRowsBefore);
        Assertions.assertThat(quartzScheduler.checkExists(JobKey.jobKey("42", "OneTimeTask")))
            .isTrue();
    }

    @Test
    void testCompletedQuartzTriggerIsSkipped() throws SchedulerException {
        JobDetail jobDetail = JobBuilder.newJob(OneTimeSchedulerJob.class)
            .withIdentity("99", "OneTimeTask")
            .usingJobData("jobId", 99L)
            .storeDurably()
            .build();

        quartzScheduler.addJob(jobDetail, true);

        QuartzImporter importer = new QuartzImporter(new QuartzJobReader(quartzScheduler), schedulerClient, 5);

        Assertions.assertThat(importer.importJobs()
            .skippedComplete())
            .isEqualTo(1);
        Assertions.assertThat(schedulerClient.getScheduledExecution(
            DbSchedulerTaskDescriptors.ONE_TIME_RESUME.instanceId("99")))
            .isEmpty();
    }
}
```

`ScheduledExecution.getData()` returns the deserialized data object. The "Stripe skipped" count is 1 in both summaries because the durable job has no trigger and is recognised by class before the trigger check.

- [ ] **Step 4: Write `SchedulerProviderGatingIntTest`** — two nested contexts, one per provider:

```java
package com.bytechef.platform.scheduler.db.config;

import com.bytechef.platform.scheduler.db.DbTriggerScheduler;
import com.bytechef.platform.scheduler.db.importer.QuartzImportStarter;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@ExtendWith(ObjectMapperSetupExtension.class)
class SchedulerProviderGatingIntTest {

    @Nested
    @SpringBootTest(
        classes = DbSchedulerTestConfiguration.class,
        properties = {
            "spring.profiles.active=test", "bytechef.scheduler.provider=quartz"
        })
    @Import(PostgreSQLContainerConfiguration.class)
    class QuartzProvider {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void testNoDbSchedulerBeansUnderQuartz() {
            Assertions.assertThat(applicationContext.getBeanNamesForType(DbTriggerScheduler.class))
                .isEmpty();
            Assertions.assertThat(applicationContext.getBeanNamesForType(QuartzImportStarter.class))
                .isEmpty();
            Assertions.assertThat(
                applicationContext.getBeanNamesForType(com.github.kagkarlsson.scheduler.Scheduler.class))
                .as("db-scheduler.enabled=false keeps the starter off")
                .isEmpty();
        }
    }

    @Nested
    @SpringBootTest(classes = DbSchedulerTestConfiguration.class, properties = "spring.profiles.active=test")
    @Import(PostgreSQLContainerConfiguration.class)
    class DbSchedulerProvider {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private Scheduler quartzScheduler;

        @Test
        void testQuartzIsPresentButNeverStartedUnderDbScheduler() throws SchedulerException {
            Assertions.assertThat(quartzScheduler.isStarted())
                .isFalse();
            Assertions.assertThat(applicationContext.getBeanNamesForType(DbTriggerScheduler.class))
                .hasSize(1);
            Assertions.assertThat(applicationContext.getBeanNamesForType(QuartzImportStarter.class))
                .hasSize(1);
            Assertions.assertThat(applicationContext.getBean(com.github.kagkarlsson.scheduler.Scheduler.class)
                .getSchedulerState()
                .isStarted())
                .isTrue();
        }
    }
}
```

`DbSchedulerTestConfiguration` component-scans `com.bytechef.platform.scheduler.db.config`, which is where `DbSchedulerConfiguration` lives; under `provider=quartz` its `@ConditionalOnProperty` excludes it, so no `TriggerScheduler` bean exists in that context — fine, because nothing there needs one. The `provider=quartz` context has no Quartz `TriggerScheduler` either (the Quartz module is not on this module's classpath); the test asserts only absence.

- [ ] **Step 5: Run the integration tests**

Run: `./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:testIntegration > /tmp/t8.log 2>&1; tr '\r' '\n' < /tmp/t8.log | grep -E "BUILD|FAILED|tests completed" | tail -5`
Expected: `BUILD SUCCESSFUL`. Docker must be running. If Liquibase reports a checksum clash against a shared dev database, that is not this test — Testcontainers uses a fresh container.

Prove the key assertion is load-bearing once: temporarily change `dueNoEarlierThanNow` in `QuartzImporter` to `return Instant.now();`, re-run `QuartzImportIntTest`, watch `testImportsEveryKind…` fail on "cron instance keeps Quartz's next fire time", then revert. Do not commit the sabotage.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-scheduler/platform-scheduler-db
git commit -m "5650 Add db-scheduler provider, importer, and gating integration tests

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: db-scheduler-ui in `server-app`, behind `SYSTEM_ADMIN`

**Files:**
- Modify: `server/apps/server-app/build.gradle.kts` (next to the line added in Task 1)
- Modify: `server/libs/config/security-config/src/main/java/com/bytechef/security/config/SecurityConfiguration.java` (new chain, placed directly before `actuatorFilterChain` at line 121)
- Test: `server/libs/config/security-config/src/test/java/com/bytechef/security/config/DbSchedulerUiSecurityIntTest.java`

**Interfaces:**
- Consumes: `db-scheduler-ui.enabled` (derived by Task 2's post-processor), `AuthorityConstants.SYSTEM_ADMIN`, `getSystemAuthenticationProvider(security.getSystem())` and `UnauthorizedBasicAuthenticationEntryPoint` already used by `actuatorFilterChain`.
- Produces: `SecurityFilterChain` bean `dbSchedulerUiFilterChain` matching `/db-scheduler/**` and `/db-scheduler-api/**`.

The UI starter registers its controllers under `/db-scheduler-api/**` and serves the SPA under `/db-scheduler/**`. Both fall through to the SPA/catch-all chain today, which would either serve `index.html` or reject them; a dedicated chain ordered before everything else makes the rule explicit. `@Order(0)` is used because 1–4 are taken and the chain is matcher-scoped, so it only needs to precede the catch-all.

- [ ] **Step 1: Add the UI starter to `server-app`**

```kotlin
    implementation("no.bekk.db-scheduler-ui:db-scheduler-ui-spring-boot-4-starter:5.0.0")
```

- [ ] **Step 2: Write the failing test.** The existing `SpaWebFilterIntTest` in the same module shows how `SecurityConfiguration` is bootstrapped in a slice test; mirror its class-level annotations (copy them verbatim from `server/libs/config/security-config/src/test/java/com/bytechef/security/web/rest/filter/SpaWebFilterIntTest.java`) and add a stub controller so the paths exist:

```java
package com.bytechef.security.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.platform.security.constant.AuthorityConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// copy the class-level Spring Boot test annotations from SpaWebFilterIntTest here
@Import(DbSchedulerUiSecurityIntTest.StubUiController.class)
@TestPropertySource(properties = "db-scheduler-ui.enabled=true")
class DbSchedulerUiSecurityIntTest {

    @TestConfiguration
    static class StubUiController {

        @RestController
        static class Controller {

            @GetMapping("/db-scheduler-api/tasks")
            public String tasks() {
                return "[]";
            }
        }

        @Bean
        Controller dbSchedulerUiStubController() {
            return new Controller();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/db-scheduler-api/tasks"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void testRegularUserIsForbidden() throws Exception {
        mockMvc.perform(get("/db-scheduler-api/tasks").with(user("user")
            .authorities(() -> "ROLE_USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void testSystemAdminIsAllowed() throws Exception {
        mockMvc.perform(get("/db-scheduler-api/tasks").with(user("admin")
            .authorities(() -> AuthorityConstants.SYSTEM_ADMIN)))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :server:libs:config:security-config:testIntegration --tests "*DbSchedulerUiSecurityIntTest" > /tmp/t9.log 2>&1; tr '\r' '\n' < /tmp/t9.log | grep -E "BUILD|FAILED|expected" | head -8`
Expected: `testAnonymousIsUnauthorized` and `testRegularUserIsForbidden` fail (the paths currently fall into another chain), proving the new chain changes behaviour.

- [ ] **Step 4: Add the chain** to `SecurityConfiguration`, immediately before `actuatorFilterChain`:

```java
    /**
     * Security for db-scheduler-ui. Registered only when the UI is enabled (derived from
     * bytechef.scheduler.provider=db-scheduler); the whole surface requires SYSTEM_ADMIN.
     */
    @Bean
    @ConditionalOnProperty(value = "db-scheduler-ui.enabled", havingValue = "true")
    @Order(0)
    public SecurityFilterChain dbSchedulerUiFilterChain(
        HttpSecurity http, PathPatternRequestMatcher.Builder mvc) throws Exception {

        http
            .securityMatcher("/db-scheduler/**", "/db-scheduler-api/**")
            .cors(withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(mvc.matcher("/db-scheduler/**"), mvc.matcher("/db-scheduler-api/**"))
                .hasAuthority(AuthorityConstants.SYSTEM_ADMIN))
            .httpBasic(withDefaults())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new UnauthorizedBasicAuthenticationEntryPoint()));

        AuthenticationProvider authenticationProvider = getSystemAuthenticationProvider(security.getSystem());

        if (authenticationProvider != null) {
            http.authenticationProvider(authenticationProvider);
        }

        return http.build();
    }
```

Add `import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;` (the module already depends on `spring-boot-autoconfigure`).

- [ ] **Step 5: Run the test to verify it passes**

Run: same command as Step 3. Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Boot check.** Start the infra and the app with the provider switched on, then hit the UI:

```bash
cd server && docker compose -f docker-compose.dev.infra.yml up -d && cd ..
BYTECHEF_SCHEDULER_PROVIDER=db-scheduler ./gradlew -p server/apps/server-app bootRun > /tmp/boot.log 2>&1 &
```

Wait for `Started ServerApplication`, then:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9555/db-scheduler-api/tasks
curl -s -o /dev/null -w "%{http_code}\n" -u admin@localhost.com:admin http://localhost:9555/db-scheduler-api/tasks
grep -E "Quartz import:" /tmp/boot.log
```

Expected: `401`, then `200` (admin@localhost.com carries `SYSTEM_ADMIN`; if it returns `403`, use the `bytechef.security.system.username/password` basic credentials the actuator chain uses), and one `Quartz import: scanned=…` line. Stop the app afterwards.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/apps/server-app/build.gradle.kts server/libs/config/security-config
git commit -m "5650 Serve db-scheduler-ui in server-app behind SYSTEM_ADMIN

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Docs, acceptance script, full verification

**Files:**
- Modify: `docs/content/docs/platform/use-bytechef/self-hosted/installation/distributed.mdx` (rows at lines 36, 149; "Scheduler backends" section at 268–278)
- Modify: `docs/content/docs/platform/use-bytechef/self-hosted/configuration/environment-variables.md`
- Modify: `docs/content/docs/developer-guide/architecture.mdx` (the sentence that names Quartz)
- Modify: spec `docs/superpowers/specs/2026-09-06-db-scheduler-provider-design.md` §7 (billing condition note)

- [ ] **Step 1: `distributed.mdx`.** Line 36 row: change "Quartz-backed (JDBC job store) schedule and polling-trigger service." to "Schedule and polling-trigger service; Quartz (JDBC job store) or db-scheduler, selected by `bytechef.scheduler.provider`." Line 149 row: `| \`bytechef.scheduler.provider\` | \`quartz\` (also \`db-scheduler\`, \`aws\`) |`. After the `### QUARTZ (default)` section (ends at the callout around line 278), add:

````markdown
### `DB_SCHEDULER`

`bytechef.scheduler.provider: db-scheduler` runs [db-scheduler](https://github.com/kagkarlsson/db-scheduler)
against the shared database: one `scheduled_tasks` table, created by the same `scheduler` Liquibase
context as the Quartz tables. db-scheduler has no non-clustered mode — every node polls the table and
row-level locking guarantees a task instance runs on exactly one node — so `scheduler-app` may be
scaled horizontally under this provider.

Tuning lives under the `db-scheduler.*` prefix (`polling-interval`, `polling-strategy`, `threads`);
ByteChef ships `1s`, `lock-and-fetch`, `10`.

**Switching from Quartz.** While `db-scheduler` is the active provider, a one-time `quartz-import`
task runs on every startup and copies live Quartz jobs (schedule and polling triggers, OAuth2 token
refreshes, dynamic-webhook refreshes, suspended-task resumes) into `scheduled_tasks`, preserving each
job's next fire time. The import is idempotent and never modifies Quartz rows. It logs one summary
line, `Quartz import: scanned=…`. Disable it with `bytechef.scheduler.db-scheduler.importer.enabled: false`.

<Callout type="warn">
  The import is one-way. Switching back to `quartz` resumes Quartz from its own rows; anything
  scheduled while `db-scheduler` was active is not copied back.
</Callout>

**Inspecting schedules.** `server-app` serves
[db-scheduler-ui](https://github.com/bekk/db-scheduler-ui) at `/db-scheduler` for users with the
`SYSTEM_ADMIN` authority (read-only by default; `db-scheduler-ui.read-only: false` enables re-run and
delete). Turn it off with `bytechef.scheduler.db-scheduler.ui.enabled: false`.
````

- [ ] **Step 2: `environment-variables.md`.** Find the row for `BYTECHEF_SCHEDULER_PROVIDER` (or the `bytechef.scheduler.provider` entry) and add `db-scheduler` to its value list, then add rows for `BYTECHEF_SCHEDULER_DB_SCHEDULER_IMPORTER_ENABLED` (`true`) and `BYTECHEF_SCHEDULER_DB_SCHEDULER_UI_ENABLED` (`true`), following the table's existing column layout.

- [ ] **Step 3: `architecture.mdx`.** Where it says the scheduler is Quartz, say "Quartz or db-scheduler, selected by `bytechef.scheduler.provider`".

- [ ] **Step 4: Spec §7.** Append: "- `BillingSchedulingConfiguration` gates on the non-existent key `bytechef.coordinator.trigger.scheduler.provider`; harmless today because the default resolves to `quartz`, but it should read `bytechef.scheduler.provider`."

- [ ] **Step 5: Full verification** (sequentially, never overlapping):

```bash
./gradlew spotlessApply > /tmp/fmt.log 2>&1; tail -1 /tmp/fmt.log
./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:check :server:libs:platform:platform-billing:platform-billing-service:check :server:libs:config:security-config:check :server:libs:config:app-config:check > /tmp/check.log 2>&1; tr '\r' '\n' < /tmp/check.log | grep -E "^> Task .* FAILED|BUILD" | tail -5
./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-db:testIntegration :server:libs:config:security-config:testIntegration > /tmp/int.log 2>&1; tr '\r' '\n' < /tmp/int.log | grep -E "^> Task .* FAILED|BUILD" | tail -5
./gradlew :server:libs:platform:platform-scheduler:platform-scheduler-impl:test > /tmp/quartz.log 2>&1; tr '\r' '\n' < /tmp/quartz.log | grep -E "BUILD" | tail -1
```

Expected: every command ends `BUILD SUCCESSFUL`. The last one proves the untouched Quartz module still passes. If SpotBugs fails, read `build/reports/spotbugs/main.html` in the failing module (the XML is not rewritten) and fix the finding — the usual ones here are `EI_EXPOSE_REP2` on constructor-stored collaborators (add `@SuppressFBWarnings("EI")` as the Quartz classes do).

- [ ] **Step 6: Manual acceptance script** (dev, both flips):

1. `provider=quartz`: create and enable a workflow with a Schedule trigger (every minute), one with a polling trigger, one OAuth2 connection, and one workflow that suspends with an expiry (e.g. an approval task with a timeout). Confirm in `qrtz_triggers` that four rows exist.
2. Stop the app. Set `BYTECHEF_SCHEDULER_PROVIDER=db-scheduler`. Start. Read the `Quartz import:` line — expect `imported=4`. Open `/db-scheduler` as admin and see the four instances with the expected execution times.
3. Watch the schedule trigger fire on the minute and the polling trigger fire at its period. Wait for the suspended task to resume at its expiry.
4. Stop. Set `provider=quartz`. Start. Confirm Quartz fires the schedule and polling triggers again from its own rows. Note that any new schedules created in step 3 are not present — that is the documented asymmetry.

- [ ] **Step 7: Commit**

```bash
git add docs/content docs/superpowers/specs/2026-09-06-db-scheduler-provider-design.md
git commit -m "5650 Document the db-scheduler provider and its one-way Quartz import

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Plan self-review

**Spec coverage.** §2.1 provider/enum → Task 2. §2.2 module/units → Tasks 1, 3–6; billing twin → Task 7. §2.3 Quartz standby → Task 2 (post-processor keeps `QuartzAutoConfiguration` running, `QuartzDelayer` stays gated) and verified in Task 8's gating test. §2.4 flip behaviour → importer every startup (Task 6 `QuartzImportStarter`), asymmetry documented in Task 10. §3 task model → Tasks 3–4 (records, Quartz cron style, Jackson3, `ContextBinding`, `RescheduleAt`, retry policies, no retry on resume). §4 importer (trigger, Quartz-API reading, class-name dispatch, next-fire-time preservation, clamping to now, `scheduleIfNotExists`, summary line, unreadable store) → Task 6; multi-tenant single schema needs nothing (§4.5). §5.1 UI → Task 9 (server-app only per Task 0 amendment). §5.2 security chain → Task 9. §5.3 configuration → Task 2. §5.4 schema → Task 1. §5.5 docs → Task 10. §6.1 unit tests → Tasks 2–7. §6.2 integration tests 1–4 → Tasks 8 and 9. §6.3 acceptance script → Task 10 Step 6. §6.4 Quartz module untouched → verified by Task 10 Step 5.

**Deviations recorded in Task 0:** polling data carries `checkPeriodMinutes`; UI in `server-app` only; starter gating via `EnvironmentPostProcessor`; UI history property is `db-scheduler-ui.log.enabled`. One more, recorded in Task 10 Step 4: the billing condition-key drift.

**Type consistency check:** `DbTriggerScheduler(SchedulerClient, int)` — Task 5 tests and configuration agree. `QuartzImporter(QuartzJobReader, SchedulerClient, int)` — Task 6 tests, `quartzImportTask` bean, and Task 8 IntTest agree. `QuartzJobReader.ReadResult(List<ImportedJob>, int skippedStatic, int skippedUnknown, int skippedComplete, int failed, boolean quartzReadable)` and `ImportSummary(scanned, imported, alreadyPresent, skippedStatic, skippedUnknown, skippedComplete, failed, quartzReadable)` — argument order is identical in every constructor call in Tasks 6 and 8. `RescheduleAt<T>(Instant)` record accessor is `executionTime()` — used that way in Task 4 tests. Descriptor names are the `*_NAME` constants throughout.

**Known soft spots an executor should expect:** (1) the exact `TaskInstanceException` constructor in the Task 5 test; (2) whether `TriggerListenerEvent`'s accessor is `getListenerParameters()`; (3) `Jackson3Serializer` record round-trip — Task 3 Step 4 says what to do if it fails. Each is a one-line adjustment at a named location, not a design change.
