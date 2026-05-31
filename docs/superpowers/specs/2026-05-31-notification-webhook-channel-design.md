# Notification WEBHOOK Channel — Design Spec

- **Date:** 2026-05-31
- **Status:** Draft (pending review)
- **Author:** Ivica Cardic (with Claude Code)
- **Module:** `server/libs/platform/platform-notification` (+ `platform-coordinator`, client settings UI)

## 1. Background & Motivation

ByteChef's **Notifications** feature (`platform-notification`, Community Edition) is the closest
in-product analog to [n8n's log streaming](https://docs.n8n.io/log-streaming/): an internal
event → configurable-destination fan-out. Today it ships a clean architecture — an event listener
(`NotificationJobStatusApplicationEventListener`) resolves, per subscribed `Notification`, a
`NotificationSender` (by `Notification.Type`) and a `NotificationHandler` (by event type), then calls
`sender.send(...)`.

Two destination types are declared: `EMAIL` and `WEBHOOK`. **Only EMAIL actually works.** The
WEBHOOK channel is unfinished:

- `WebhookNotificationSender.send(...)` has an **empty body** — it makes no HTTP call.
- There is **no concrete `WebhookNotificationHandler`** (only `JobStatusEmailNotificationHandler`
  exists), so `NotificationHandlerRegistry.getNotificationHandler(eventType, WEBHOOK)` always returns
  `null`. The empty sender doesn't dereference it, so this is latent rather than an NPE.
- The client hides the WEBHOOK type behind feature flag `ff-1132` (`NotificationDialog.tsx:150`).

Events are also **job-level only**: `NotificationEvent.Type` defines six `Source.JOB` values
(`JOB_CANCELLED/CREATED/COMPLETED/FAILED/STARTED/STOPPED`). The `Source` enum already declares a
`TASK` member with no `Type` values behind it.

### Goal

Finish the WEBHOOK channel as a real, production-grade outbound delivery path, and take the first
concrete step toward log-streaming parity by broadening the event model to task-level events — in a
single, phased spec.

### Non-goals (explicitly deferred)

- **Audit / security event source.** Audit logging lives in EE (`platform-audit`). A
  `Source.AUDIT` event family is out of scope here and gets its own follow-up spec.
- **Additional destination *types* (Slack, Syslog, Sentry).** The architecture must not *block*
  them, and Phase 3 documents how they plug in, but no new `Notification.Type` beyond the existing
  `WEBHOOK` is implemented here. (Slack/MS-Teams incoming webhooks are reachable through the generic
  WEBHOOK type already.)
- **Per-delivery audit history table.** We persist only the *latest* delivery status on the
  notification (see §5.4), not a full delivery log.

## 2. Open Decisions (defaults chosen; flag to override)

| # | Decision | Value | Note |
|---|----------|-------|------|
| D1 | **Edition gating of WEBHOOK** | **EE-only** (decided 2026-05-31). Webhook delivery is an Enterprise feature. The CE `WebhookNotificationHandler` interface, the `WEBHOOK` enum value, and context enrichment remain in CE (inert without a sender), but the **sender + concrete webhook handlers + webhook utilities live in an EE module** gated by `@ConditionalOnEEVersion`, and the client exposes the WEBHOOK type only behind `<EEVersion>`. See §5.9. | (Superseded earlier draft default of "CE".) |
| D2 | **Task events in this spec** | **In scope (Phase 2).** This is what makes the work "toward log-streaming" rather than stub-filling. | Split Phase 2 into its own spec if you want Phase 1 to ship first. |
| D3 | **Signing secret storage** | Stored inside the existing `settings` JSON, **encrypted** via the platform encryption service (consistent with connection credentials), not plaintext. | If plaintext-in-`settings` is acceptable short-term, drop the encryption step (faster, less safe). |

## 3. Current Architecture (as-is)

```
JobStatusApplicationEvent (atlas)
   │
   ▼
NotificationJobStatusApplicationEventListener            [platform-coordinator]
   │  status → NotificationEvent.Type.of(JOB, status)
   │  build NotificationHandlerContext{eventType, jobId, jobName}
   │  notificationService.getNotifications(eventType)
   │
   ├─ for each Notification:
   │     sender  = NotificationSenderRegistry.get(notification.type)     // EMAIL | WEBHOOK
   │     handler = NotificationHandlerRegistry.get(eventType, type)      // resolves concrete handler
   │     sender.send(notification, handler, context)
   ▼
EmailNotificationSender ─ uses MailService ─ getSubject()/getContent() from JobStatusEmailNotificationHandler  ✅
WebhookNotificationSender ─ empty                                                                              ❌
```

Key contracts:

- `NotificationSender<T extends NotificationHandler>` — `getType()`, `send(notification, handler, ctx)`.
- `EmailNotificationHandler` — `getSubject(ctx)`, `getContent(ctx)`, `isHtml()`.
- `WebhookNotificationHandler` — **empty marker interface** (the gap).
- `NotificationHandlerContext` — carries only `eventType`, `jobId`, `jobName` (Builder).
- `Notification` — `type` (INT ordinal), `settings` (TEXT JSON via `MapWrapper`), subscribed events.
- Seed: `notification_event` rows seeded for ordinals 0–4 only (JOB_STOPPED ord. 5 is **not** seeded).

## 4. Target Architecture (to-be)

Same skeleton, gaps filled and the event source broadened:

```
JobStatusApplicationEvent ─────────┐
TaskStarted/Complete/ErrorEvent ───┤   (atlas events)
                                   ▼
NotificationJobStatusApplicationEventListener   (job events, enriched context)
NotificationTaskApplicationEventListener  ★NEW  (task events → NotificationEvent.Type.of(TASK, …))
                                   │
                                   ▼  (shared dispatch helper)
                         resolve sender + handler, sender.send(...)
                                   │
   ┌───────────────────────────────┼────────────────────────────────┐
   ▼                               ▼                                 ▼
EmailNotificationSender   WebhookNotificationSender ★IMPLEMENTED   (future: Slack/Syslog senders)
                          - builds envelope from handler.getPayload(ctx)
                          - async on taskExecutor
                          - RetryTemplate + ExponentialBackOff
                          - timeout, optional HMAC signature
                          - persists last delivery status
```

## 5. Detailed Design

### 5.1 Payload contract — `WebhookNotificationHandler` gains `getPayload`

Extend the marker interface with a single payload-producing method (mirrors how
`EmailNotificationHandler` produces `getSubject`/`getContent`):

```java
public interface WebhookNotificationHandler extends NotificationHandler {
    Map<String, Object> getPayload(NotificationHandlerContext context);
}
```

The **sender** wraps the handler's `data` payload in a stable, versioned envelope before POSTing:

```jsonc
{
  "schemaVersion": "1",
  "id": "<uuid>",                 // unique per delivery; echoed in X-ByteChef-Delivery header
  "event": "JOB_FAILED",          // NotificationEvent.Type name
  "source": "JOB",                // NotificationEvent.Source name
  "timestamp": "2026-05-31T12:34:56Z",
  "data": {                       // handler.getPayload(ctx)
    "jobId": 123,
    "jobName": "Sync customers",
    "status": "FAILED"
    // task events additionally: "taskExecutionId", "taskName", "error"
  }
}
```

Rationale: a versioned envelope with a flat `data` block is what SIEMs/log sinks expect and matches
n8n's per-event JSON. `schemaVersion` lets us evolve `data` without breaking consumers.

Concrete handlers (Phase 1 & 2):

- `JobStatusWebhookNotificationHandler` — `@NotificationEventType({JOB_CANCELLED, JOB_CREATED,
  JOB_COMPLETED, JOB_FAILED, JOB_STARTED})` (mirror the email handler's set; JOB_STOPPED stays
  unseeded for parity with current behavior — see §6 note).
- `TaskStatusWebhookNotificationHandler` (Phase 2) — `@NotificationEventType({TASK_STARTED,
  TASK_COMPLETED, TASK_FAILED})`.

Both live in `platform-coordinator` alongside the existing `JobStatusEmailNotificationHandler`.

### 5.2 `NotificationHandlerContext` enrichment

The context is the only payload-input the handler receives, and today it lacks status/timestamp/task
fields. Extend the Builder (all additive, existing callers keep compiling):

- `status` (String) — job/task status.
- `timestamp` (Instant) — event time.
- `taskExecutionId` (Long, nullable) — task events only.
- `taskName` (String, nullable) — task events only.
- `error` (String, nullable) — failure events.

`NotificationJobStatusApplicationEventListener.getNotificationHandlerContext(...)` is updated to
populate `status` and `timestamp`. The new task listener populates the task fields.

### 5.3 `WebhookNotificationSender` implementation

Fill the empty `send(...)`:

1. Read `url` (required) and `secret` (optional) from `notification.getSettings()`.
2. Validate `url` is an external/allowed host (reuse the AI-observability URL-validation approach —
   `AiObservabilityUrlValidator.validateExternalUrl` pattern — to block SSRF to internal IPs).
3. Build the envelope (§5.1), serialize with the platform `ObjectMapper`/`JsonUtils`.
4. **Dispatch asynchronously** on the shared `@Qualifier("taskExecutor")` `TaskExecutor`
   (`TenantThreadPoolTaskExecutor`, virtual-thread + context-propagating) so delivery never blocks
   the atlas event thread.
5. Inside the async task, use a **`RetryTemplate` with `ExponentialBackOff`** (same idiom as
   `WebhookJobStatusApplicationEventListener`: initial 2s, multiplier 2.0, max 5 attempts —
   configurable via properties).
6. POST via Java `HttpClient` (idiomatic for new code, per `AiObservabilityNotificationDispatcher`)
   with an explicit connect/request **timeout** (default 10s, configurable).
7. Headers: `Content-Type: application/json`, `X-ByteChef-Event: <type>`,
   `X-ByteChef-Delivery: <id>`, and — if `secret` present — `X-ByteChef-Signature: sha256=<hex>`.
8. On terminal failure (retries exhausted), record the error (§5.4); do **not** propagate so one bad
   webhook can't break sibling deliveries.

**HMAC signature:** `HMAC-SHA256(secret, rawRequestBody)`, lowercase hex, `sha256=` prefix — the
de-facto webhook convention (GitHub-style). Reuse the `FileEntryTokensImpl` HMAC approach
(`Mac.getInstance("HmacSHA256")` + `SecretKeySpec`). The receiver verifies by recomputing over the
raw body.

### 5.4 Delivery status (lightweight)

Add two nullable columns to `notification` so the UI can surface a misconfigured webhook (matches how
the EE observability dispatcher records `lastError`):

- `last_delivery_date TIMESTAMP NULL`
- `last_error TEXT NULL`

New Liquibase changeset under `.../changelog/platform/notification/`. Updated on each delivery
attempt outcome via a small service method. (Full per-delivery history is a non-goal.)

> **Scope toggle:** if you'd rather keep Phase 1 schema-free, this sub-section can be dropped — the
> sender still works, only the UI loses delivery feedback. Marked low-priority in the plan.

### 5.5 Configuration properties

Under a `bytechef.notification.webhook.*` prefix (with sensible defaults so it works out of the box):

- `connect-timeout` / `request-timeout` (default 10s)
- `retry.initial-interval` (2s), `retry.multiplier` (2.0), `retry.max-attempts` (5)

### 5.6 Client changes

- **Gate the WEBHOOK `SelectItem` behind `<EEVersion hidden>`** (replacing the `ff-1132` feature flag
  in `NotificationDialog.tsx:150` / `useNotifications.tsx`) — **per decision D1, EE-only**. `<EEVersion>`
  reads `useApplicationInfoStore().application.edition === 'EE'`, so the option is invisible in CE.
- Add an optional **Secret** input (`settings.secret`) shown when type === WEBHOOK, beneath the
  existing Webhook URL field. Mask as a password input.
- Zod schema: `settings.webhook` required + URL-validated when type === WEBHOOK; `settings.secret`
  optional string.
- Optionally surface `last_error` / `last_delivery_date` in `NotificationsTable` (low priority).

### 5.9 EE placement (per decision D1)

Webhook delivery is an Enterprise feature. Physical layout follows the EE conventions (license header,
`@version ee`, code under `server/ee/`):

- **Stays in CE** (`platform-notification-api`): the `WebhookNotificationHandler` interface (with
  `getPayload`), the `WEBHOOK` value in `Notification.Type`, and `NotificationHandlerContext`
  enrichment. These are inert in CE because no `WebhookNotificationSender` bean registers.
- **Moves to a new EE module** under `server/ee/libs/platform/platform-notification/` (sibling to the
  existing `platform-notification-remote-client`): `WebhookNotificationSender`, `WebhookSignatures`,
  `WebhookUrlValidator`, `WebhookEnvelope`, `WebhookNotificationProperties`, and the concrete
  `JobStatusWebhookNotificationHandler` (Phase 1) / `TaskStatusWebhookNotificationHandler` (Phase 2).
  The sender and handler bean classes carry `@ConditionalOnEEVersion` so they register only when
  `bytechef.edition=ee`. EE files use the ByteChef Enterprise license header and `@version ee`.
- **Safety guard (CE):** because the WEBHOOK sender bean is absent in CE, the dispatch loop in
  `NotificationJobStatusApplicationEventListener` / `NotificationTaskApplicationEventListener` must
  **skip and log when `notificationSenderRegistry.getNotificationSender(type)` returns null** rather
  than NPE on a stray WEBHOOK notification. (Optionally, `NotificationFacadeImpl` can also reject
  WEBHOOK creation when not EE; the client `<EEVersion>` gate is the primary UX control.)

### 5.7 Broadening events to TASK (Phase 2)

> **Implementation constraint discovered during planning (2026-05-31):** Of the atlas task events,
> only `TaskStartedApplicationEvent` (and `TaskProgressedApplicationEvent`) implement
> `ApplicationEvent` and flow through the `ApplicationEventListener` bus. `TaskExecutionCompleteEvent`
> and `TaskExecutionErrorEvent` are **not** `ApplicationEvent`s — they are consumed internally by the
> coordinator (`DefaultTaskCompletionHandler`, `ErrorHandlingTaskDispatcher`, `TaskCoordinator`) on
> dedicated message routes. Subscribing to TASK_COMPLETED / TASK_FAILED therefore requires emitting
> **new** application events from the atlas engine core — a deeper change than originally assumed.
> Consequently Phase 2 is split into its own spec + plan; Phase 1 (this plan) ships the WEBHOOK
> channel for the existing job events with no atlas changes. TASK_STARTED is reachable today and can
> be folded into the Phase 2 plan first, with TASK_COMPLETED/FAILED following once the atlas events
> exist.

1. **Enum:** append `TASK_STARTED, TASK_COMPLETED, TASK_FAILED` to `NotificationEvent.Type`
   (`Source.TASK`, values `"STARTED"/"COMPLETED"/"FAILED"`). **Append at the end** to preserve INT
   ordinal stability (project convention; cf. `EnumOrdinalStabilityTest`). New ordinals are 6, 7, 8.
2. **Seed:** new Liquibase changeset inserting `notification_event` rows for the new ordinals (also
   backfill ordinal 5 = JOB_STOPPED if we want it selectable — currently unseeded; call this out
   rather than silently changing behavior).
3. **Listener:** `NotificationTaskApplicationEventListener` (`platform-coordinator`) implements
   `ApplicationEventListener`, handles `TaskStartedApplicationEvent`,
   `TaskExecutionCompleteEvent`, `TaskExecutionErrorEvent`, maps to the TASK event types, builds an
   enriched context, and runs the same resolve-sender/handler dispatch. Extract the shared dispatch
   loop (currently inline in the job listener) into a small reusable helper to avoid duplication.
4. **Handlers:** `TaskStatusEmailNotificationHandler` + `TaskStatusWebhookNotificationHandler`, with
   message-source templates for the email subject/content (`email.TASK_*.subject/.content`).

### 5.8 Destination-type extensibility (Phase 3 — documentation only)

Document (in the module README) that a new destination type requires: a `Notification.Type` enum
value (appended), a `NotificationSender<T>` bean returning that type, and a handler interface +
concrete handlers per event source. The registries pick them up automatically. No code in this spec.

## 6. Files Touched (estimate)

**Server — `platform-notification-api`:**
- `handler/WebhookNotificationHandler.java` — add `getPayload(ctx)`.
- `handler/NotificationHandlerContext.java` — add status/timestamp/taskExecutionId/taskName/error.
- `domain/NotificationEvent.java` — append TASK_* types (Phase 2).

**Server — `platform-notification-service`:**
- `handler/WebhookNotificationSender.java` — full implementation (HTTP, async, retry, HMAC).
- new `WebhookEnvelope` (record) + small HMAC helper (or reuse existing crypto util).
- `service/NotificationServiceImpl` — `updateDeliveryStatus(...)` (if §5.4 kept).
- new Liquibase changesets: delivery-status columns; TASK event seed rows.

**Server — `platform-coordinator`:**
- new `JobStatusWebhookNotificationHandler`, `TaskStatusWebhookNotificationHandler`,
  `TaskStatusEmailNotificationHandler`.
- new `NotificationTaskApplicationEventListener` + registration in
  `PlatformCoordinatorConfiguration`.
- enrich context construction in `NotificationJobStatusApplicationEventListener`; extract shared
  dispatch helper.
- message-source templates for TASK_* email content.

**Client:**
- `NotificationDialog.tsx`, `useNotifications.tsx` — ungate WEBHOOK, add Secret field, schema.

**Tests:**
- `WebhookNotificationSenderTest` (envelope shape, HMAC, retry/backoff, timeout, URL validation,
  failure isolation) — unit with a stub HTTP endpoint.
- enum ordinal stability assertions for the new TASK_* values.
- listener test: task events resolve + dispatch.

> **JOB_STOPPED note:** ordinal 5 is defined in the enum but not seeded, so it's not user-selectable
> today. This spec does not change that unless we explicitly seed it; raised here so the omission is a
> conscious choice, not an accident.

## 7. Testing Strategy

- **Unit:** `WebhookNotificationSender` against a local stub server (or mocked `HttpClient`):
  envelope correctness, signature correctness (recompute and compare), header presence, retry on
  5xx/IO error with backoff, give-up after max attempts, timeout honored, SSRF URL rejected, failure
  does not propagate.
- **Unit:** handlers produce the expected `data` map per event type.
- **Ordinal stability:** assert TASK_* ordinals are 6/7/8 and existing ones unchanged.
- **Integration (`...IntTest`):** persist a WEBHOOK notification subscribed to JOB_FAILED, fire a
  `JobStatusApplicationEvent`, assert the stub endpoint receives a signed, well-formed POST.
- Follow project test conventions (drop `Impl` from names; camelCase methods; `@ExtendWith(
  ObjectMapperSetupExtension.class)` where `JsonUtils` is used).

## 8. Risks & Mitigations

- **SSRF** — webhook URLs are user-supplied. Mitigate with external-host validation (§5.3).
- **Blocking the atlas event thread** — mitigated by async dispatch on `taskExecutor`.
- **Secret at rest** — encrypt within `settings` (D3) rather than plaintext.
- **Retry storms / slow endpoints** — bounded attempts + timeouts; failures isolated per notification.
- **Enum ordinal drift** — append-only + stability test.

## 9. Phasing Summary

- **Phase 1 — Webhook delivery (CE):** payload contract, context enrichment, `WebhookNotificationSender`
  (async/retry/timeout/HMAC), `JobStatusWebhookNotificationHandler`, delivery-status columns, client
  ungate + secret field. Ships a working WEBHOOK channel for job events.
- **Phase 2 — Task-level events:** enum + seed, task listener, task handlers (email + webhook),
  shared dispatch helper. This is the log-streaming-parity step.
- **Phase 3 — Extensibility groundwork (docs only):** how to add Slack/Syslog/Sentry destination
  types and an audit-event source later.
