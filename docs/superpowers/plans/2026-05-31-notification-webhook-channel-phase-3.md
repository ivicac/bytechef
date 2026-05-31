# Notification Channel — Phase 3 Implementation Plan (Extensibility Groundwork — Docs)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.
>
> **Prerequisite:** Phases 1 and 2 merged. This phase ships **no production code** — it documents how the notification system is extended to new destination types and event sources, so future work (Slack/Syslog/Sentry destinations, an audit event source) follows a known pattern instead of re-deriving it.

**Goal:** Add a `platform-notification` module README that documents (a) the registry/handler/sender architecture, (b) the exact steps to add a new destination *type*, with a worked Slack example, and (c) the steps to add a new event *source*, with the audit-source case flagged as a separate EE spec.

**Architecture:** Documentation only. The README is the durable artifact; it captures the conventions established in Phases 1–2 (enum-ordinal stability, `@NotificationEventType` discovery, registry auto-wiring, envelope contract).

**Spec:** `docs/superpowers/specs/2026-05-31-notification-webhook-channel-design.md` §5.8 + non-goals.

---

## File Structure

- Create: `server/libs/platform/platform-notification/README.md`

---

## Task 1: Author the notification extensibility README

**Files:**
- Create: `server/libs/platform/platform-notification/README.md`

- [ ] **Step 1: Write the README**

Create `server/libs/platform/platform-notification/README.md` with this content:

````markdown
# platform-notification

Event-driven notifications: internal events (job and task status) are fanned out to user-configured
destinations (email, webhook). This is ByteChef's analog to log-streaming/alerting integrations.

## Architecture

```
atlas event ──> *ApplicationEventListener (platform-coordinator)
                     │  builds NotificationHandlerContext
                     │  notificationService.getNotifications(eventType)
                     ▼
            for each Notification:
              sender  = NotificationSenderRegistry.get(notification.type)   // EMAIL | WEBHOOK | ...
              handler = NotificationHandlerRegistry.get(eventType, type)    // concrete content/payload
              sender.send(notification, handler, context)
```

- **`Notification.Type`** (enum, INT ordinal) — the destination channel.
- **`NotificationEvent.Type`** (enum, INT ordinal) — what happened (`JOB_*`, `TASK_*`). Each value has a
  `Source` (`JOB`/`TASK`) and a value string; `Type.of(source, value)` resolves it.
- **`NotificationSender<T extends NotificationHandler>`** — one bean per `Notification.Type`; performs
  delivery. Discovered by `NotificationSenderRegistry` via `getType()`.
- **`NotificationHandler`** — produces channel-specific content. `EmailNotificationHandler`
  (`getSubject`/`getContent`), `WebhookNotificationHandler` (`getPayload`). Concrete handlers are
  annotated `@NotificationEventType({...})` and auto-registered by `NotificationHandlerRegistry` per
  event type.
- **`NotificationHandlerContext`** — the per-event data passed to handlers (event type, job/task ids,
  name, status, timestamp, error).

Enum ordinals are persisted as `INT`; **append new values at the end** and seed matching
`notification_event` rows in a Liquibase changeset.

## Webhook delivery envelope (Notification.Type.WEBHOOK — EE only)

Webhook delivery is an **Enterprise feature**: the `WebhookNotificationSender` and concrete webhook
handlers live in `server/ee/libs/platform/platform-notification/platform-notification-webhook` and are
gated by `@ConditionalOnEEVersion`; the client exposes the type only behind `<EEVersion>`. The
`WebhookNotificationHandler` interface and the `WEBHOOK` enum value remain in CE (inert without a sender).

`WebhookNotificationSender` POSTs a versioned JSON envelope; `data` is the handler's `getPayload`:

```json
{ "schemaVersion": "1", "id": "<uuid>", "event": "JOB_FAILED", "source": "JOB",
  "timestamp": "2026-05-31T12:34:56Z", "data": { "jobId": 123, "jobName": "...", "status": "FAILED" } }
```

If a `secret` is configured, the body is signed: header `X-ByteChef-Signature: sha256=<hex>`
(HMAC-SHA256 over the raw body). Other headers: `X-ByteChef-Event`, `X-ByteChef-Delivery`.

## Adding a new destination type

Example: a dedicated **Slack** type (Slack incoming webhooks expect `{"text": "..."}`, not the generic
envelope — hence a distinct type rather than reusing `WEBHOOK`).

1. **Enum:** append `SLACK` to `Notification.Type` (last position).
2. **Handler interface:** add `SlackNotificationHandler extends NotificationHandler` with the method(s)
   the sender needs (e.g. `String getText(NotificationHandlerContext ctx)`).
3. **Registry support:** extend `NotificationHandlerRegistry` to route `SlackNotificationHandler` beans
   into a `slackNotificationHandlerMap` and return them when `notificationType == SLACK`.
4. **Sender:** add `SlackNotificationSender implements NotificationSender<SlackNotificationHandler>`,
   `getType()` returns `SLACK`, `send(...)` POSTs `{"text": handler.getText(ctx)}` to the Slack URL
   (reuse `WebhookUrlValidator`, the async `taskExecutor`, and the retry/timeout pattern from
   `WebhookNotificationSender`).
5. **Concrete handlers:** add `JobStatusSlackNotificationHandler` / `TaskStatusSlackNotificationHandler`
   (`@NotificationEventType({...})`) in `platform-coordinator`.
6. **Client:** add the `SLACK` option to the type dropdown and its settings fields (URL) in
   `NotificationDialog.tsx`; extend the Zod schema.
7. **Tests:** sender unit test against a local HTTP stub; handler payload tests.

Syslog/Sentry follow the same shape (the sender talks the destination's protocol instead of HTTP).

## Adding a new event source

Example: a `Source.AUDIT` family (security/user-action events).

1. Append `AUDIT` to `NotificationEvent.Source` and the relevant `AUDIT_*` values to
   `NotificationEvent.Type` (last position); seed the rows.
2. Publish the source events as `ApplicationEvent`s (see `TaskCompletedApplicationEvent` for the
   pattern) or add a listener on the existing audit stream.
3. Add an `*ApplicationEventListener` that maps to the new `Type` values and runs the dispatch loop.
4. Add per-source handlers for each destination type.

> **Audit source is EE.** The audit subsystem lives under `server/ee/libs/platform/platform-audit`.
> A notification audit-source needs its own design spec (license header, `@version ee`,
> `@ConditionalOnEEVersion` wiring) — out of scope for the CE notification module. Track separately.
````

- [ ] **Step 2: Verify the README renders and links are accurate**

Run: `grep -n "NotificationSender\|NotificationHandlerRegistry\|WebhookNotificationSender\|Notification.Type\|NotificationEvent.Source" server/libs/platform/platform-notification/README.md`
Expected: the class/enum names in the README match real symbols (cross-check against the source under `platform-notification-api`). Fix any drift.

- [ ] **Step 3: Commit**

```bash
git add server/libs/platform/platform-notification/README.md
git commit -m "732 Document notification destination/source extensibility"
```

---

## Out of scope

- No new `Notification.Type` (Slack/Syslog/Sentry) is implemented here — the README is the deliverable.
- No audit-source code; it requires a separate EE spec (noted in the README).
- If a worked Slack implementation is later desired, it becomes its own spec+plan following the README's
  "Adding a new destination type" steps.
