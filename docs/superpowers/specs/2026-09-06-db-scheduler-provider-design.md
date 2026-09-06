# db-scheduler as a second scheduler provider

**Date:** 2026-09-06
**Status:** Design approved in conversation; awaiting written review
**Related:** [#5650](https://github.com/bytechefhq/bytechef/issues/5650) (Quartz never clustered),
[#5651](https://github.com/bytechefhq/bytechef/issues/5651) (dynamic webhook refresh broken)

## 1. Goal

Add [db-scheduler](https://github.com/kagkarlsson/db-scheduler) as a selectable scheduler provider
next to Quartz and AWS EventBridge, with [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)
for inspection, and a one-way importer that copies live Quartz jobs into db-scheduler so an
environment can be switched without losing schedules.

Quartz is **not** removed. Both providers coexist behind one switch so db-scheduler can be evaluated
per environment. Removing Quartz is a separate, later decision.

### Why

- Quartz's JDBC job store is configured but never clustered (#5650). db-scheduler has no
  non-clustered mode; single-execution across nodes is the only way it operates.
- Quartz has no crash recovery for a mid-flight execution; db-scheduler heartbeats and hands dead
  executions to a `DeadExecutionHandler`.
- Quartz's eleven tables and Java-serialized `JOB_DATA` are opaque; db-scheduler is one table with
  JSON task data that `db-scheduler-ui` renders directly.

### Non-goals

- Deleting Quartz, its module, or the `QRTZ_*` tables.
- A reverse exporter (db-scheduler → Quartz). See §2.4 for the resulting asymmetry.
- Fixing #5651 inside the Quartz provider. The new provider does not have the defect; the Quartz
  fix is triaged separately.
- Any client (React) work. `db-scheduler-ui` is a self-contained server-served bundle.

## 2. Providers and the switch

### 2.1 Property

`bytechef.scheduler.provider = quartz | db-scheduler | aws`. `ApplicationProperties.Scheduler.Provider`
gains `DB_SCHEDULER`. The default **stays `quartz`**; `db-scheduler` is opted into per environment.

### 2.2 Module layout

A new sibling module, `server/libs/platform/platform-scheduler/platform-scheduler-db/`, next to the
untouched Quartz module `platform-scheduler-impl`. Every bean in it is gated on
`bytechef.scheduler.provider=db-scheduler`, mirroring `QuartzSchedulerConfiguration`'s
`@ConditionalOnProperty(... havingValue = "quartz")` and the AWS module.

| Unit | Responsibility |
| --- | --- |
| `DbTriggerScheduler implements TriggerScheduler` | the 7 schedule/cancel methods → `SchedulerClient` |
| `DbConnectionRefreshScheduler implements ConnectionRefreshScheduler` | the 2 refresh methods |
| one `Task` bean per job kind (§3) | `schedule-trigger`, `polling-trigger`, `dynamic-webhook-refresh`, `oauth2-token-refresh`, `one-time-resume`, `stripe-usage-report` |
| `ContextBindingExecutionHandler` | binds `TenantContext` + system `SecurityContext` around every execution |
| `QuartzImportTask`, `QuartzJobReader` | the importer (§4) |
| `config/liquibase/changelog/db-scheduler/` | `scheduled_tasks`, `scheduled_execution_logs` |

Callers are untouched: `TriggerLifecycleFacadeImpl`, `TriggerCoordinator`,
`ConnectionLifecycleFacadeImpl`, `SuspendTaskExecutionPostOutputProcessor`, and the `schedule`
component all speak `TriggerScheduler` / `ConnectionRefreshScheduler`. The EE remote-client and
remote-rest modules are untouched for the same reason. `runtime-job-app` keeps `NoOpTriggerScheduler`.

Billing: `BillingSchedulingConfiguration` (Quartz `JobDetail` + `CronTrigger`) gets a db-scheduler
twin that registers a static recurring `stripe-usage-report` task, conditioned on `billing.enabled`
and the new provider. The Quartz configuration's existing provider gate keeps it inert under
`db-scheduler`.

### 2.3 Quartz in standby under `db-scheduler`

Spring Boot's Quartz autoconfiguration still builds the `Scheduler` bean — it is not gated on our
property — and `spring.quartz.auto-startup: false` is already set. The only call to
`scheduler.start()` is `QuartzDelayer` inside the gated `QuartzSchedulerConfiguration`. Therefore,
under `db-scheduler`, the Quartz scheduler exists, can be read by the importer, and never fires.
Under `quartz`, the db-scheduler module contributes no beans at all. Nothing fires twice in either
position.

EE's `MultiTenantQuartzTriggerSchedulerConfiguration` is a `SchedulerFactoryBeanCustomizer`; it keeps
customizing the standby factory harmlessly.

### 2.4 Flipping the switch

| Direction | Behaviour |
| --- | --- |
| `quartz → db-scheduler` | The importer runs **once per database** (§4): the surviving `quartz-import` row in `scheduled_tasks` is the marker, so jobs created during a later spell on Quartz are picked up only if that row is deleted first. Quartz rows are never modified. |
| `db-scheduler → quartz` | Quartz resumes from its own rows. Anything scheduled while on db-scheduler — new deployments, refreshed OAuth expiries, new suspended-task timers — is **not** exported back. Quartz's default misfire handling fires any past-due rows once on start. |

The asymmetry is accepted for evaluation on dev/staging and documented in the operator docs. A
symmetric exporter is explicitly out of scope and would be its own design.

## 3. Task model

One db-scheduler `Task` per job kind. Instance ids reuse the exact strings Quartz uses as job names
today, so the importer renames rather than re-keys.

| Task | Kind | Instance id | Task data | Schedule |
| --- | --- | --- | --- | --- |
| `schedule-trigger` | recurring, persistent schedule (`Tasks.recurringWithPersistentSchedule`) | `workflowExecutionId` | `{cron, zoneId, output}` | `CronSchedule(cron, zoneId, CronStyle.QUARTZ)` |
| `polling-trigger` | recurring, persistent schedule | `workflowExecutionId` | `{checkPeriodMinutes}` | `FixedDelay.ofMinutes(checkPeriodMinutes)` |
| `dynamic-webhook-refresh` | one-time, self-rescheduling | `workflowExecutionId` | `{connectionId}` | `scheduledTo(webhookExpirationDate)`; reschedules itself to the expiry the refresh returns |
| `oauth2-token-refresh` | one-time, self-rescheduling | `tenantId + connectionId` | `{connectionId, tenantId}` | `scheduledTo(expiry − 5 min)`; reschedules to `now + expiresIn − 5 min` |
| `one-time-resume` | one-time | `jobId` | `{jobId, continueParameters?}` | `scheduledTo(expiresAt)` |
| `stripe-usage-report` | recurring, static | fixed | `{}` | `cron("0 0 * * * ?", UTC, QUARTZ)` |

**Cron dialect.** The `schedule` component produces `"0 " + expression` — a Quartz six-field pattern.
db-scheduler's `CronSchedule` accepts `CronStyle.QUARTZ`, so the pattern is stored and evaluated
verbatim. No translation layer.

**Serialization.** Task data is JSON via db-scheduler's `JacksonSerializer` bound to the platform
`ObjectMapper`, not the library's default Java serialization. `output` and `continueParameters` are
stored as JSON strings inside the data record, exactly as they are `JsonUtils.write`-ed into Quartz
today, so downstream readers (`JsonUtils.readMap` in the schedule handler) are unchanged.

**Execution context.** `ContextBindingExecutionHandler` decorates every task: it sets
`TenantContext` from the data record (`tenantId` field for OAuth refresh; parsed from
`workflowExecutionId`, which embeds the tenant, for the rest), installs the system
`SecurityContext`, runs the handler, and resets both in `finally`. This is the contract
`SystemSecurityContextJob` provides today plus the tenant binding Quartz currently inherits from
the scheduling caller's thread.

**Self-rescheduling one-time tasks.** `dynamic-webhook-refresh` and `oauth2-token-refresh` are
declared with `Tasks.custom(...)` so the handler returns a `CompletionHandler`:
`OnCompleteReschedule(nextInstant)` when the refresh yields a new expiry, `OnCompleteRemove` when it
does not. The handler never calls `SchedulerClient` on its own instance; db-scheduler owns the
row's lifecycle.

**Handler bodies are moved, not rewritten.** `PollingTriggerJob`, `ScheduleTriggerJob`, and
`OneTimeSchedulerJob` are event publishers (`TriggerPollEvent`, `TriggerListenerEvent` with
`fireTime` + `dateTime`, `ResumeJobEvent`); the two refresh jobs call a facade and reschedule. Each
becomes the handler lambda of its task. The Quartz `Job` classes stay in the Quartz module untouched.

**Timing.** `db-scheduler.polling-interval: 1s`, `polling-strategy: lock-and-fetch`, `threads: 10`.
db-scheduler's precision is its polling interval; at 1 s the visible difference from Quartz for a
`0 * * * * ?` trigger is sub-second, and the poll is one indexed query per node. `heartbeat-interval`
and `missed-heartbeats-limit` stay at library defaults (~30 min to reclaim a dead execution; Quartz
today never reclaims).

**Failure semantics.**

- Recurring tasks: an exception is logged and the next occurrence is still computed (Quartz parity).
- `oauth2-token-refresh`, `dynamic-webhook-refresh`: retry with a 5-minute backoff, at most 3 times,
  then give up with an ERROR log. Quartz drops these on first failure today, which is part of why
  #5651 was silent.
- `one-time-resume`: no retry. A resume is not idempotent from the scheduler's point of view; the
  coordinator owns that.

**Defects that do not carry over.** The new `dynamic-webhook-refresh` task uses one constant for its
task name and one for its data key, so the three mismatches in #5651 cannot recur here. #5650 is
moot for this provider.

## 4. Importer

### 4.1 Trigger

Under `db-scheduler` only (the importer is a bean of the gated module), `quartz-import` is a
*recurring* task on a ~10-year `FixedDelay` schedule, not a one-time task. db-scheduler schedules
every recurring `Task` bean on startup with schedule-if-not-exists semantics: the first startup ever
creates the `scheduled_tasks` row and runs the import; every later startup finds that row already
present and leaves it alone, so the import runs exactly once per database. In a multi-node rollout
every node attempts the initial schedule, one row wins, one node executes. The surviving
`quartz-import` row is the completion marker — no separate marker table, and it is visible and
deletable in db-scheduler-ui, so forcing a re-import means deleting that row.
`bytechef.scheduler.db-scheduler.importer.enabled` (default `true`) disables it.

### 4.2 Reading Quartz

Through the standby `Scheduler` API, never SQL: `getJobGroupNames()` → `getJobKeys()` →
`getJobDetail()` + `getTriggersOfJob()`. Quartz deserializes `JOB_DATA`; `CronTrigger`,
`SimpleTrigger`, and `Trigger.getNextFireTime()` are typed accessors. If the `QRTZ_*` tables do not
exist (a fresh install), the reader logs at INFO and returns.

**Dispatch is by `JobDetail.getJobClass()`, not by group.** #5651 shows the group name is unreliable
on one path; the job class is the one thing every writer got right.

| Quartz job class | → task | Notes |
| --- | --- | --- |
| `ScheduleTriggerJob` | `schedule-trigger` | cron + zone from the `CronTrigger`; `output` from data |
| `PollingTriggerJob` | `polling-trigger` | period comes from config, not the row |
| `DynamicWebhookTriggerRefreshJob` | `dynamic-webhook-refresh` | reads `connectionId` — the key the writer used — so today's broken rows import correctly |
| `ConnectionOAuth2TokenRefreshJob` | `oauth2-token-refresh` | instance id = job name, unchanged |
| `OneTimeSchedulerJob` | `one-time-resume` | `continueParameters` may be absent |
| `StripeUsageReportingJob` | skipped | the static task registers itself |
| anything else | skipped, counted, logged with key | |

### 4.3 Next execution time

Always Quartz's `getNextFireTime()`, including for recurring tasks, where `scheduledTo(nextFireTime)`
is used deliberately instead of `scheduledAccordingToData()`. This prevents a double fire at cutover
and preserves the OAuth-refresh and resume instants that exist nowhere else in the database
(`ConnectionLifecycleFacadeImpl` computes `now + expires_in` at schedule time;
`SuspendTaskExecutionPostOutputProcessor` passes `suspend.expiresAt()` straight through).

- `nextFireTime` in the past → due now, fires once (same outcome as Quartz's default misfire policy
  for both simple and cron triggers).
- `nextFireTime == null` (trigger complete) → skipped.

### 4.4 Guarantees

- Every write is `scheduleIfNotExists` keyed by `(task, instanceId)`.
- The importer never deletes, pauses, or modifies a Quartz row.
- One row failing to map is logged with its key and skipped; the importer never fails startup.
- It ends with one summary line: scanned, imported, already-present, skipped-unknown,
  skipped-complete, per task. That line is the acceptance check for a test flip.

### 4.5 Multi-tenant

Quartz keeps exactly one table set, in `public`: `MultiTenantDriverDelegate.execute()` forces
`search_path` to `public` for every Quartz operation and only restores the tenant schema afterward,
and the per-tenant Liquibase run uses context `multitenant`, which does not match the quartz
changelog's `mono or scheduler`. db-scheduler follows the same layout — one `scheduled_tasks` in
`public`, tenant carried in task data. The reader runs under the default tenant and needs nothing
tenant-specific.

## 5. UI, security, configuration, schema

### 5.1 UI

Pinned versions, verified against Maven Central metadata on 2026-09-06:
`com.github.kagkarlsson:db-scheduler-spring-boot-4-starter` **16.12.0** (newest release, and the
UI's minimum) and `no.bekk.db-scheduler-ui:db-scheduler-ui-spring-boot-4-starter` **5.0.0**. The
UI serves at `/db-scheduler` with its API under
`/db-scheduler-api/**`, in `server-app` (monolith) only. Defaults: `read-only: true`,
`task-data: true`, `db-scheduler-ui.log.enabled: false`. `scheduler-app` (EE) has
`spring-boot-starter-web` but no Spring Security stack (`security-config` is not a dependency), so
`db-scheduler-ui` cannot be gated there safely; it keeps `db-scheduler-ui.enabled=false` until it
gains a security chain, tracked in §7.

### 5.2 Security

A new filter chain in `SecurityConfiguration` with
`securityMatcher("/db-scheduler/**", "/db-scheduler-api/**")` requiring
`AuthorityConstants.SYSTEM_ADMIN` — the gate `/actuator/**` already has. Registered only when the
UI is enabled; under `quartz` the paths 404.

### 5.3 Configuration

- `bytechef.scheduler.provider` comment in `application-bytechef.yml` lists `db-scheduler`.
- New fields on `ApplicationProperties.Scheduler` (binding is strict, so they must be fields):
  `dbScheduler.import.enabled` and `dbScheduler.ui.enabled`, both default `true`.
- `db-scheduler.*` tuning in `server-app` `application.yml` and EE `scheduler-app.yml`.
- The starters are gated by `DbSchedulerEnvironmentPostProcessor` (registered in `META-INF/spring.factories`), which adds a lowest-precedence property source setting `db-scheduler.enabled` and `db-scheduler-ui.enabled` from `bytechef.scheduler.provider`. Importing the autoconfiguration from a regular `@Configuration` would evaluate its `@ConditionalOnBean(DataSource.class)` before the DataSource autoconfiguration runs; the post-processor keeps autoconfiguration ordering intact. `application-liquibase.yml` sets both flags to `false` explicitly, which wins over the post-processor.
- `spring.quartz.*` is untouched.

### 5.4 Schema

`platform-scheduler-db/src/main/resources/config/liquibase/changelog/db-scheduler/` with two
changeSets: `scheduled_tasks` (db-scheduler's PostgreSQL DDL, including `priority`) and
`scheduled_execution_logs` (the UI history table, created up front so `history` is a property flip).
`master.xml` gets an `includeAll` for the directory with `contextFilter="mono or scheduler"`,
identical to Quartz's, so it lands in the same apps and in `public` only under multi-tenant.

### 5.5 Docs

`distributed.mdx` "Scheduler backends" gains a `DB_SCHEDULER` entry and its "run exactly one
scheduler-app" callout becomes provider-specific; `environment-variables.md` gains the new
variables. Docs publish from the released branch; these ride with the release.

## 6. Testing

### 6.1 Unit (no database)

- `DbTriggerSchedulerTest`, `DbConnectionRefreshSchedulerTest` — mocked `SchedulerClient`; task
  name, instance id, data record, scheduled instant for each of the 9 methods, the −5 min OAuth
  offset, and that `cancel*` tolerates "not found" without throwing.
- `QuartzJobReaderTest` — mocked `Scheduler`/`JobDetail`/`Trigger`; one case per job class; unknown
  class skipped and counted; `nextFireTime == null` skipped; past `nextFireTime` → now; missing
  `continueParameters` tolerated; a row whose data map throws is skipped without aborting the batch.
- `ContextBindingExecutionHandlerTest` — tenant and security context set during execution and
  reset in `finally`, including when the task throws.

### 6.2 Integration (Testcontainers PostgreSQL, `IntTest` suffix)

1. `DbTriggerSchedulerIntTest` — real db-scheduler, real table; schedule each kind, await
   execution, assert the exact Spring events `QuartzTriggerSchedulerIntTest` asserts today. The two
   providers are held to one contract.
2. `QuartzImportIntTest` — seeds through the **real Quartz API** into Liquibase-created `QRTZ_*`
   tables (standby scheduler), one job per kind, including a dynamic-webhook row written exactly as
   the current code writes it. Runs the importer; asserts one `scheduled_tasks` row per job, instance
   ids equal Quartz job names, data JSON, and `execution_time == Quartz nextFireTime` (the assertion
   that goes red if the importer falls back to "now"). Runs it again; asserts zero new rows. Asserts
   Stripe/unknown/complete rows were skipped and the Quartz rows are unchanged.
3. `SchedulerProviderGatingIntTest` — under `quartz`: no db-scheduler beans, no importer, no
   `/db-scheduler` chain. Under `db-scheduler`: Quartz `Scheduler` present with
   `isStarted() == false`, importer present, db-scheduler running.
4. `DbSchedulerUiSecurityIntTest` — MockMvc: anonymous → 401, regular user → 403,
   `SYSTEM_ADMIN` → 200 on `/db-scheduler-api/**`.

Both `./gradlew test` and `./gradlew testIntegration` must run; `test` excludes `*IntTest*`.

### 6.3 Manual flip check (acceptance script)

On `quartz`: create a cron workflow, a polling workflow, an OAuth connection, and a suspended task
with an expiry. Flip to `db-scheduler`, restart, read the importer summary line, open
`/db-scheduler`, watch each fire at its expected time. Flip back; confirm Quartz resumes. This makes
the §2.4 asymmetry visible on purpose.

### 6.4 Deliberately untested

Nothing in the Quartz module changes, so its tests stay as they are — including the two that pin
#5651.

## 7. Open follow-ups (not blocking)

- Wire `db-scheduler-ui` into `scheduler-app` once it has a Spring Security chain.
- Reverse exporter if production round-tripping is ever wanted.
- Fix #5651 in the Quartz provider, or retire the path with Quartz.
- Once db-scheduler is the default in every environment: retire Quartz, drop `QRTZ_*`, remove the
  importer.
