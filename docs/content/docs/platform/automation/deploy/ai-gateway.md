---
title: AI Gateway
description: Route, observe, and govern LLM traffic — tracing, rate limiting, and scoring for every model call.
ee: true
comingSoon: true
---

# AI Gateway


The **AI Gateway** is an Enterprise Edition service that sits between your applications and LLM providers, giving platform teams one place to observe and govern model traffic:

- **Tracing** — LLM calls are captured as spans (OTLP ingestion supported), so you can follow a request across prompts, tool calls, and retrievals.
- **Rate limiting** — cap traffic per tenant or client before it reaches a provider.
- **Scoring** — attach quality scores to traces (including batched external scores) to evaluate model behavior over time.

**AI Gateway** appears in the automation workspace sidebar, in the **Deploy** group, once the gateway is enabled on an Enterprise Edition instance; the page's own left sidebar then lists its sections. It is enabled and tuned with the [`BYTECHEF_AI_GATEWAY_*`](/platform/use-bytechef/self-hosted/configuration/environment-variables) environment variables.

<!-- TODO screenshot: the AI Gateway page with its section sidebar and the Monitoring dashboard open — request-volume, error-rate, latency, and cost-breakdown charts -->

## What's in the Gateway

The page's sections cover both the data plane that routes traffic and the control plane that observes and governs it:

| Section | What it does |
|---|---|
| **Providers** | Register upstream LLM providers with encrypted keys and custom base URLs. |
| **Models** | The model catalog, each model with its default routing-policy override. |
| **Projects** | Gateway projects and their per-project API keys. |
| **Routing Policies** | Routing strategies — simple, weighted-random, cost-optimized, latency-optimized, priority/failover, tag-based, and three LLM-judged "intelligent" variants. |
| **Prompts** | Version-controlled prompt registry with environment deployment and rollback. |
| **Settings** | Workspace gateway settings — response caching (with a TTL) and log retention. Workspace-level content guardrails moved to Workspace Settings → AI Agents → Guardrails (linked from this page); per-project guardrail overlays are still configured on each project. |
| **Budget** | Hard (block) and soft (warn) spend limits per project, provider, or policy. |
| **Rate Limits** | Per-tenant / per-client request caps. |
| **Monitoring** | The real-time metrics dashboard — request volume, error rate, latency percentiles, and cost. |
| **Playground** | Interactive prompt testing with side-by-side model comparison. |
| **Datasets** | Versioned evaluation datasets. |
| **Experiments** | Experiment runs over datasets, with cross-experiment comparison. |
| **Traces** | Hierarchical span traces with full request/response payloads. |
| **Sessions** | Traces grouped into sessions. |
| **Scores** | LLM-as-judge and manual quality scores with analytics. |
| **Alerts** | Threshold-based alert rules and notification channels. |
| **Exports** | On-demand data exports and webhook subscriptions. |

## Providers, models, and projects

Under **Providers**, administrators register upstream LLM providers — Anthropic, Azure OpenAI, Cohere, DeepSeek, Google Gemini, Groq, Mistral, and OpenAI — each with a Name, an encrypted API key, and an optional custom Base URL for self-hosted or proxied endpoints. In edit mode, a **Test Connection** button reports latency or surfaces a connection error before the provider carries any traffic.

The adjacent **Models** tab holds the model catalog: every entry names its Provider and carries a Name, an Alias, a Context Window, per-million-token Input and Output costs, a free-text Capabilities field (for example `CHAT,EMBEDDINGS`), and a Default Routing Policy that can inherit the workspace/system default or override it per model.

**Projects** groups gateway traffic into named projects — Name, Slug, Description — each with its own Compression, Caching (with a Cache TTL), Retry/Timeout, and Log Retention settings, plus per-project API keys. A project's edit view also embeds a Guardrails panel that layers PII/secret redaction, response scanning, moderation, injection detection, and blocked terms on top of the workspace policy — see [Content guardrails](#content-guardrails) below, including how PII tokenization interacts with Caching.

<!-- TODO screenshot: Providers list with the Add Provider dialog open — Type, API Key, and Base URL fields and the Test Connection button -->

## Routing Policies

Each routing policy has a Name, a Strategy, and a Fallback Model. The Strategy dropdown offers `Simple`, `WeightedRandom`, `CostOptimized`, `LatencyOptimized`, `PriorityFallback`, `IntelligentBalanced`, `IntelligentCost`, `IntelligentQuality`, and `TagBased` — covering everything from plain round-robin/failover selection to cost- and latency-aware and LLM-judged routing. The policy list shows how many model deployments each policy currently covers; a model is attached to a policy from the **Models** tab rather than from the policy editor itself, and a policy can be set as the workspace default or overridden per model or per gateway project.

<!-- TODO screenshot: Routing Policies list with the Strategy dropdown open on the create dialog -->

## Budgets & Rate Limits

**Budget** is a single record per workspace rather than a list: a Budget Amount, a Period (`Daily`, `Weekly`, `Monthly`, `Quarterly`, `Yearly`), an Enforcement Mode (`Soft` warns past the threshold, `Hard` blocks further requests once it's crossed), and an Alert Threshold percentage — summarized on four cards alongside an Active/Disabled badge.

**Rate Limits** is a full list: each rule has a Name, a Scope (`Global`, `Per User`, or `Per Property` — which reveals a Property Key such as `customer_id`), a Limit Type (`Requests`, `Tokens`, or `Cost`), a Limit Value, a Window (seconds, shown formatted as s/m/h/d), and an Enabled toggle. Rate-limit counters live in Redis/memory; a per-rule rejection-history view is planned but not built yet.

<!-- TODO screenshot: Rate Limits list with a Per Property scope rule and the create-rule form -->

## Prompts

The **Prompts** registry lists prompts with their description, version count, and which environments (`production`/`staging`/`development`) currently have an active version. Opening a prompt shows one card per environment (the active version number, or "No active version") plus a version-history table with Version, Environment, Status, Type, Commit Message, Created By/date, a content preview, and per-version metrics — invocation count, average latency, average cost, and error rate.

A **New Version** dialog takes the prompt Content (a textarea that recognizes `{{variable}}` placeholders and lists them as chips), a Type (`TEXT` or `CHAT`), the target Environment, a Commit Message, and an optional "set as active for this environment" flag. From the version table, a rocket-icon **Deploy** action promotes a version into an environment, and selecting two versions and clicking **Compare** opens a line-by-line diff of their content.

<!-- TODO screenshot: A prompt's detail view with the three environment cards and the version-history table -->

## Playground

The **Playground** is a single interactive page for trying prompts against the configured providers. A Text/Chat mode toggle switches the input between a plain textarea and a chat-style message list, and a **Compare** toggle runs the same prompt against two models side by side (Model A / Model B selectors, each labeled with its provider). Responses stream over SSE when the Stream checkbox is on (disabled while comparing); a parameters panel exposes Temperature, Max Tokens, and Top P sliders. Each response panel reports the model name, latency, prompt/completion/total token counts, cost, finish reason, and a link back to the resulting trace in **Traces**.

<!-- TODO screenshot: Playground in Compare mode with two model responses side by side -->

## Traces & Sessions

**Traces** lists gateway requests with time-range shortcuts (1h/6h/24h/7d/30d) and filters for Status, Source, User ID, and Model, showing Time, Name, User, Status, Latency, Tokens In/Out, and Cost per row. A trace's detail view adds a Spans section — toggleable between a nested **Tree** view and a **Waterfall** view — where each span carries a type badge (`Event`/`Generation`/`Span`/`ToolCall`), model, latency, cost, and status, and expands to show its provider, token counts, timestamps, and Input/Output/Metadata JSON payloads. Thumbs-up/down buttons record a quick boolean quality score at the trace or span level, and traces produced by an experiment replay carry a banner linking back to the originating experiment run and dataset item.

**Sessions** groups related traces: a session's detail view lists its User, trace count, and the constituent traces with their own status, latency, and cost.

<!-- TODO screenshot: A trace detail view with the Tree span view expanded, showing a Generation span's Input/Output JSON -->

## Scores

**Scores** is a tabbed surface. **Score Configs** define the score types available — Name, Data Type (`Numeric`, `Boolean`, `Categorical`), a Min/Max range or a JSON list of Categories, and a Description. **Eval Rules** attach an LLM-as-judge to a score config: Name, Score Config, judge Model, a Prompt Template (with `{{input}}`, `{{output}}`, `{{metadata}}` placeholders), a Sampling Rate, a Delay before scoring, and an "enable immediately" flag; a **Run on History** action re-scores past traces over a chosen date range. **Analytics** shows a time-range selector and one card per score — a trend chart for numeric scores, a pie chart for boolean scores, and a bar chart for categorical scores. Manual scores (the trace/span thumbs-up/down buttons) feed the same data as the automated eval rules.

<!-- TODO screenshot: Scores → Analytics with a numeric score's trend chart and a boolean score's distribution pie chart -->

## Alerts

**Alerts** has **Rules** and **History** tabs. A rule has a Name, a Metric (`ErrorRate`, `LatencyP95`, `Cost`, `TokenUsage`, `RequestVolume`), a Condition (`Greater Than`, `Less Than`, `Equals`), a Threshold, a Window, a Cooldown, an Enabled toggle, and a checklist of workspace notification channels (managed under Settings → Notifications) to fire against; an edit-mode **Test** button reports the metric's current value against the rule. Rules can be snoozed for 1h/4h/24h/7 days. The **History** tab lists a chosen rule's fired events — Time, Triggered Value, Message, and Status (`TRIGGERED`/`ACKNOWLEDGED`/`RESOLVED`) — with an Acknowledge action.

<!-- TODO screenshot: Alerts → Rules with the rule dialog open, showing the Metric/Condition/Threshold fields and the notification-channel checklist -->

## Exports

**Exports** has **Export History** and **Webhooks** tabs. A new export job picks a Scope (`Traces`, `Request Logs`, `Sessions`, `Prompts`) and a Format (`CSV`, `JSON`, `JSONL`); the history table tracks Created, Scope, Format, Status, Record count, Created By, and a download link or cancel action while it runs. Webhook subscriptions carry a Name, a target URL, an HMAC-SHA256 Secret, and an Events checklist limited to `alert.triggered`, `budget.exceeded`, and `trace.completed`; each subscription has Test, View Deliveries, Edit, and Delete actions, and a deliveries dialog shows its per-attempt history.

<!-- TODO screenshot: Exports → Webhooks with a subscription's delivery history open -->

## Datasets & Experiments

**Datasets** and **Experiments** are read-only in this UI — both are built and populated through the AI Gateway REST API (`POST /api/ai-gateway/v1/datasets`, `POST /api/ai-gateway/v1/experiments`) rather than a create dialog in the client. Datasets drill down from a Name/Description/Status list into Versions (a Label, an item count, and a frozen/open lock state) and then into individual Items, each showing Input, Expected Output, and Metadata as JSON — items captured from a trace carry a "from trace #N" badge.

Experiments list Status, Model, a completed/total run count (failed runs called out in red), and Created/Completed dates. Selecting two or more and clicking **Compare** opens summary cards (runs, success rate, total cost, average latency per experiment), an aggregate score-delta table, and a per-item runs table showing status, latency, cost, and scores across the compared experiments.

<!-- TODO screenshot: Experiments comparison view with the aggregate score-delta table -->

## Content guardrails

Content guardrails (PII/secret redaction, blocked terms, moderation, injection detection, response/streaming
redaction) are workspace-level policy shared across every LLM-calling surface — the AI Gateway, the canvas AI Agent
component, and AI Hub — not just gateway traffic. The workspace-level configuration lives under **Workspace
Settings → AI Agents → Guardrails**, reached from a link on the Gateway's own **Settings** page; the **Guardrails** panel on
each **Projects** entry (see [Providers, models, and projects](#providers-models-and-projects)) still layers a
per-project overlay on top, gateway-traffic only.

Inline guardrails run on every chat-completion and embeddings request — sync and streaming — after prompt resolution
and before the request is routed upstream. Everything is off by default and can be enabled globally (properties) or per
workspace (**Workspace Settings → AI Agents → Guardrails**). Policy is **additive** across levels: global, workspace, and
(gateway-only) project settings union together — a level can enable a guardrail or add blocked terms, but never turn
one off.

**Request-direction guardrails**

- **PII redaction is now reversible (tokenization) wherever it's supported — read this before assuming your data is
  destroyed.** The model provider still never sees the real value: each detected PII value (an email, SSN,
  credit-card number, phone number, or IPv4 address) is replaced before the prompt leaves ByteChef with a one-time
  placeholder token like `[PII_EMAIL_1_k3n9]` instead of the old `[REDACTED_EMAIL]`. The difference from a plain
  redaction placeholder: a token is unique per *value*, so two different email addresses in the same prompt get two
  different tokens instead of collapsing into one indistinguishable `[REDACTED_EMAIL]` — and, once the model's
  response comes back, ByteChef substitutes the real value back in before you see it. **Your data now round-trips.**
  If your workspace previously relied on PII redaction as a way to permanently destroy sensitive values in what
  ByteChef stores and returns, that guarantee no longer holds for chat-completion responses on this path — the real
  value is restored into the response text you receive. Active when
  `bytechef.ai.gateway.guardrails.pii-redaction-enabled` is set globally or the workspace's **Redact PII** setting is
  on (the same setting also makes the trace row's `input`/`output` store SHA-256 digests instead of payloads).
  **Gateway tracing itself never persists the restored value, independent of that digest setting**: both the trace
  row and its per-generation span record the response the way the model actually produced it — after response
  scanning, but *before* this request's own tokens are substituted back to real values, which happens only in the
  payload handed back to you, after tracing has already run. So the trace row you see on the Traces page shows this
  request's `[PII_EMAIL_1_k3n9]`-style token by default, or that token's digest once **Redact PII** is on; the
  per-generation span nested underneath it always shows the token itself — the digest setting doesn't reach spans —
  but never the real value either way. Without this ordering, the span row (which has no digest option of its own)
  would be the one place your real PII quietly persisted regardless of the **Redact PII** setting.
  **Tokens are scoped to one request, not to a conversation** — a value tokenized in one chat-completion call gets a
  different token if it recurs in a later, separate call, and there is currently no cross-request memory of "this
  is the same value as before." This matters concretely for any surface that replays prior turns as context for a
  later one (most conversational chat UIs do): a token minted in an earlier turn is already a dead reference the
  moment that turn's session closes, so if a later turn resends that turn's text as history, the model can echo
  the earlier turn's token back with nothing left alive to resolve it — you may see the literal
  `[PII_EMAIL_1_k3n9]`-shaped string surface in a later turn's response instead of a value. Treat that as expected
  behavior on any surface that retains and replays history, not as a bug, until cross-request token coherence
  (a later phase) lands.
  **Coverage differs by surface and path.** The canvas AI Agent and AI Hub tokenize on every completion, streaming
  included. On the AI Gateway specifically, only non-streaming (`stream: false`) chat completions tokenize today —
  the Gateway's own streaming chat completions (`stream: true`) and its embeddings endpoint still use the previous,
  irreversible redaction, so a value masked on those two paths is genuinely destroyed and does not come back.
  **A tokenized request is never served from — or written to — the Gateway's response cache**, regardless of your
  project's Caching setting: since each request's tokens are unique to that request, a cached response could never
  legitimately be reused for a later one anyway, and reusing one across requests would risk one request's restored
  PII being returned to a different caller. If you rely on caching for cost/latency and also enable PII redaction,
  expect cache hit rate to drop for prompts that contain PII specifically — everything else still caches normally.
- **Secret redaction is unaffected by any of the above and stays irreversible.** Developer secrets (AWS / GitHub /
  Slack / OpenAI / Stripe / Google keys, JWTs, and PEM private-key blocks) are masked with a `[REDACTED_SECRET]`
  placeholder and destroyed — never tokenized, never restored, on any path. Enable with
  `bytechef.ai.gateway.guardrails.secret-redaction-enabled` or the workspace's **Redact secrets** setting. This is a
  high-signal, ReDoS-safe subset; broader entropy-based detection lives in the workflow-layer guardrails.
- **Blocked terms** — the union of the global `bytechef.ai.gateway.guardrails.blocked-terms` list and the workspace's
  **Blocked terms** setting (both comma-separated, case-insensitive). A request containing a term is rejected (or, on
  the agent surfaces, masked and allowed through when the workspace's blocking mode is set to
  **Redact and continue** — see [Blocking mode](#blocking-mode) below; the gateway always rejects).
- **Model-based moderation** — set `bytechef.ai.gateway.guardrails.moderation-model` to the identifier of a model in
  the gateway catalog, then enable moderation globally (`bytechef.ai.gateway.guardrails.moderation-enabled`) or per
  workspace. Each message is classified SAFE/UNSAFE through the gateway's own provider wiring; flagged content is
  rejected (or, on the agent surfaces, replaced wholesale with `[REDACTED_MODERATED]` when the workspace's blocking
  mode is **Redact and continue** — a moderation verdict has no locatable span to mask). The classifier **fails
  open** — a moderation-model outage never blocks traffic. The `moderation-model` property must be set for the toggle
  to take effect.
- **Prompt-injection detection** — set `bytechef.ai.gateway.guardrails.injection-model` to a catalog model, then enable
  detection globally (`bytechef.ai.gateway.guardrails.injection-detection-enabled`) or per workspace. Each message is
  classified INJECTION/CLEAN for jailbreak / instruction-override / exfiltration attempts (including instructions hidden
  in quoted content); flagged content is rejected (or masked, per blocking mode, on the agent surfaces). Also **fails
  open**.

Order: redact PII → redact secrets → blocked terms → moderation → injection (every check sees the redacted text).
Embeddings run the same set minus moderation.

**Response-direction guardrails (dual-directional DLP)**

- **Response scanning** — when `bytechef.ai.gateway.guardrails.response-scan-enabled` (or the workspace's **Scan
  responses** setting) is on, the model's completion is scanned for PII and secrets before it is returned or traced, so
  internal data doesn't leak back through the output. This is a scan, not only a mask: any genuinely new PII or
  secret the model produced is redacted, and — on the paths where request-direction PII tokenization is active (see
  above) — any of this request's own tokens the model echoed back are then restored to their real values, in that
  order. Secrets are never restored, on any path. This never blocks. It applies to **non-streaming** completions by
  default.
- **Streaming responses** — set the operator flag `bytechef.ai.gateway.guardrails.response-scan-streaming-enabled` (in
  addition to response scanning) to also mask *new* PII/secrets the model produces in streamed output. A bounded
  lookahead window catches values that straddle SSE chunk boundaries, at the cost of a small streaming delay — hence
  the separate operator-level flag for that scanning behavior specifically.
  **That flag does not gate PII token restoration — read this if you're tuning for streaming latency.** On the
  canvas AI Agent and AI Hub, whenever this request's own PII was actually tokenized, the streamed response goes
  through that same bounded lookahead buffering to restore this request's tokens back to real values, *regardless of
  whether `response-scan-streaming-enabled` is on*. So turning on PII redaction/tokenization alone adds the
  streaming-buffering delay to every PII-bearing stream on those two surfaces, even with the streaming-scan flag left
  off — the flag only controls whether genuinely *new* PII the model produces also gets masked along the way; it was
  never a way to opt out of the delay once your own request had something in it worth restoring. The AI Gateway's
  own streaming path does not tokenize yet (see above), so it has nothing to restore and is unaffected by this.

**Blocking mode**

The **Guardrails** settings page also has a **Blocking mode** control that applies only to the canvas AI Agent and
AI Hub surfaces — the AI Gateway is unaffected by it and always rejects outright:

- **Block** (default) — a blocked-term match, a moderation flag, or a flagged injection aborts the call. On the canvas AI Agent this fails
  the step (the normal error-workflow / `on-error` handling then applies); on AI Hub it produces a blocked-message
  turn.
- **Redact and continue** — the offending text is masked and the call proceeds instead of failing. A blocked term is
  masked in place; a moderation flag has no locatable span, so the whole message is replaced with
  `[REDACTED_MODERATED]`. PII and secret redaction always behave this way regardless of the blocking-mode setting;
  only blocked terms, moderation, and injection detection are affected by the toggle.

**Per-project overrides (Gateway only)**

Guardrails can be tightened for a single Gateway project on top of its workspace policy via the
`aiGatewayProjectSettings` GraphQL query / `updateAiGatewayProjectSettings` mutation (admin only), or the
Guardrails panel on the project's edit view. Project overrides carry the same guardrail fields and union
additively — a project can turn a guardrail on or add blocked terms, never turn one off. This overlay is
Gateway-specific; the canvas AI Agent and AI Hub surfaces have no project concept and see only the global +
workspace policy.

**Rejections and metrics**

A Gateway request rejected by a guardrail returns **HTTP 422** with a `guardrail_violation` error body that names
neither the offending content nor the matched term — the client should revise the prompt, not retry. Guardrail
activity across all three surfaces is counted in the `bytechef_ai_guardrail` meter, tagged by `event`
(`pii_redacted` / `pii_tokenized` / `secret_redacted` / `response_redacted` / `pii_restored` / `token_unresolved` /
`blocked_term` / `moderation_flagged` / `injection_flagged` / `blocking_downgraded`) and `surface`
(`gateway` / `ai_agent` / `ai_hub`), so you can dashboard what the DLP layer is catching per surface.
`pii_tokenized` fires instead of `pii_redacted` wherever tokenization is active (see above); `pii_restored` fires
when a request's own token came back in the model's response and was substituted with its real value;
`token_unresolved` fires when a token-shaped string in the response couldn't be matched to any value this request
tokenized — worth alerting on, since it usually means the model altered or fabricated a token.

**Configuration reference**

All properties are under `bytechef.ai.gateway.guardrails.*` and default to off (property names were kept as-is when
the guardrail engine was extracted out of the Gateway, since the Gateway remains the sole reader/writer of these
particular keys). Each may be enabled globally (property) or, except where noted, in **Workspace Settings → AI Agents →
Guardrails** or per Gateway project (GraphQL) — levels union additively.

| Property | Default | Workspace setting | Effect |
|---|---|---|---|
| `pii-redaction-enabled` | `false` | Redact PII | Mask PII in requests (tokenized and restored on the paths that support it — see above). |
| `secret-redaction-enabled` | `false` | Redact secrets | Mask developer secrets in requests. |
| `blocked-terms` | _(empty)_ | Blocked terms | Comma-separated deny-list; a match rejects (or, per blocking mode, masks) the request. |
| `moderation-enabled` | `false` | Moderation enabled | Reject unsafe prompts (needs `moderation-model`). |
| `moderation-model` | _(unset)_ | — | Catalog model id used for moderation; unset disables the classifier. |
| `injection-detection-enabled` | `false` | Injection detection | Reject (or mask) prompt-injection attempts (needs `injection-model`). |
| `injection-model` | _(unset)_ | — | Catalog model id used for injection detection; unset disables the classifier. |
| `response-scan-enabled` | `false` | Scan responses | Redact PII/secrets from non-streaming completions. |
| `response-scan-streaming-enabled` | `false` | _(operator only)_ | Also redact streamed completions (adds a lookahead delay). |
