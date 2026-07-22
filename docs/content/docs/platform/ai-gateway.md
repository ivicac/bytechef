---
title: AI Gateway
description: Route, observe, and govern LLM traffic — tracing, rate limiting, and scoring for every model call.
---

# AI Gateway

<EEBadge />

> **Coming soon.** The AI Gateway is on the upcoming release track and is not yet available in the latest released version of ByteChef.

The **AI Gateway** is an Enterprise Edition service that sits between your applications and LLM providers, giving platform teams one place to observe and govern model traffic:

- **Tracing** — LLM calls are captured as spans (OTLP ingestion supported), so you can follow a request across prompts, tool calls, and retrievals.
- **Rate limiting** — cap traffic per tenant or client before it reaches a provider.
- **Scoring** — attach quality scores to traces (including batched external scores) to evaluate model behavior over time.

The gateway surface lives under **AI → Gateway** in the automation workspace, where a **Gateway** sidebar group appears once the feature is enabled on an Enterprise Edition instance. It is enabled and tuned with the [`BYTECHEF_AI_GATEWAY_*`](/self-hosting/configuration/environment-variables#ai-gateway-configuration) environment variables.

<!-- TODO screenshot: AI → Gateway with the Gateway sidebar group expanded and the Monitoring dashboard open — request-volume, error-rate, latency, and cost-breakdown charts -->

## What's in the Gateway

The Gateway sidebar groups its sections into the data plane that routes traffic and the control plane that observes and governs it:

| Section | What it does |
|---|---|
| **Providers** | Register upstream LLM providers with encrypted keys and custom base URLs. |
| **Models** | The model catalog, each model with its default routing-policy override. |
| **Projects** | Gateway projects and their per-project API keys. |
| **Routing Policies** | Routing strategies — round-robin, weighted, least-cost, least-latency, priority/failover, tag-based, model-affinity, sticky-session, and canary. |
| **Prompts** | Version-controlled prompt registry with environment deployment and rollback. |
| **Settings** | Workspace gateway settings — caching, log retention, and content guardrails (PII/secret redaction, blocked terms, moderation, injection detection, response scanning). |
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

## Content guardrails

Inline guardrails run on every chat-completion and embeddings request — sync and streaming — after prompt resolution
and before the request is routed upstream. Everything is off by default and can be enabled globally (properties) or per
workspace (**Settings**). Policy is **additive** across levels: global, workspace, and project settings union together —
a level can enable a guardrail or add blocked terms, but never turn one off.

**Request-direction guardrails**

- **PII redaction** — masks emails, US SSNs, credit-card numbers, phone numbers, and IPv4 addresses with
  `[REDACTED_*]` placeholders before the prompt leaves ByteChef. Active when
  `bytechef.ai.gateway.guardrails.pii-redaction-enabled` is set globally or the workspace's **Redact PII** setting is
  on (the same setting also makes traces store SHA-256 digests instead of payloads).
- **Secret redaction** — masks developer secrets (AWS / GitHub / Slack / OpenAI / Stripe / Google keys, JWTs, and PEM
  private-key blocks) with a `[REDACTED_SECRET]` placeholder. Enable with
  `bytechef.ai.gateway.guardrails.secret-redaction-enabled` or the workspace's **Redact secrets** setting. This is a
  high-signal, ReDoS-safe subset; broader entropy-based detection lives in the workflow-layer guardrails.
- **Blocked terms** — the union of the global `bytechef.ai.gateway.guardrails.blocked-terms` list and the workspace's
  **Blocked terms** setting (both comma-separated, case-insensitive). A request containing a term is rejected.
- **Model-based moderation** — set `bytechef.ai.gateway.guardrails.moderation-model` to the identifier of a model in
  the gateway catalog, then enable moderation globally (`bytechef.ai.gateway.guardrails.moderation-enabled`) or per
  workspace. Each message is classified SAFE/UNSAFE through the gateway's own provider wiring; flagged content is
  rejected. The classifier **fails open** — a moderation-model outage never blocks traffic.
- **Prompt-injection detection** — set `bytechef.ai.gateway.guardrails.injection-model` to a catalog model, then enable
  detection globally (`bytechef.ai.gateway.guardrails.injection-detection-enabled`) or per workspace. Each message is
  classified INJECTION/CLEAN for jailbreak / instruction-override / exfiltration attempts (including instructions hidden
  in quoted content); flagged content is rejected. Also **fails open**.

Order: redact PII → redact secrets → blocked terms → moderation → injection (every check sees the redacted text).
Embeddings run the same set minus moderation.

**Response-direction guardrails (dual-directional DLP)**

- **Response scanning** — when `bytechef.ai.gateway.guardrails.response-scan-enabled` (or the workspace's **Scan
  responses** setting) is on, the model's completion is redacted for PII and secrets before it is returned or traced, so
  internal data doesn't leak back through the output. This is redaction only — it never blocks. It applies to
  **non-streaming** completions by default.
- **Streaming responses** — set the operator flag `bytechef.ai.gateway.guardrails.response-scan-streaming-enabled` (in
  addition to response scanning) to also mask streamed output. A bounded lookahead window catches values that straddle
  SSE chunk boundaries, at the cost of a small streaming delay — hence the separate operator-level flag.

**Per-project overrides**

Guardrails can be tightened for a single project on top of its workspace policy via the `aiGatewayProjectSettings`
GraphQL query / `updateAiGatewayProjectSettings` mutation (admin only). Project overrides carry the same guardrail
fields and union additively — a project can turn a guardrail on or add blocked terms, never turn one off.

**Rejections and metrics**

A rejected request returns **HTTP 422** with a `guardrail_violation` error body that names neither the offending
content nor the matched term — the client should revise the prompt, not retry. Guardrail activity is counted in the
`bytechef_ai_gateway_guardrail` meter, tagged by `event`
(`pii_redacted` / `secret_redacted` / `response_redacted` / `blocked_term` / `moderation_flagged` /
`injection_flagged`), so you can dashboard what the DLP layer is catching.

**Configuration reference**

All properties are under `bytechef.ai.gateway.guardrails.*` and default to off. Each may be enabled globally (property)
or, except where noted, per workspace (**Settings**) or per project (GraphQL) — levels union additively.

| Property | Default | Workspace setting | Effect |
|---|---|---|---|
| `pii-redaction-enabled` | `false` | Redact PII | Mask PII in requests. |
| `secret-redaction-enabled` | `false` | Redact secrets | Mask developer secrets in requests. |
| `blocked-terms` | _(empty)_ | Blocked terms | Comma-separated deny-list; a match rejects the request. |
| `moderation-enabled` | `false` | Moderation enabled | Reject unsafe prompts (needs `moderation-model`). |
| `moderation-model` | _(unset)_ | — | Catalog model id used for moderation; unset disables the classifier. |
| `injection-detection-enabled` | `false` | Injection detection | Reject prompt-injection attempts (needs `injection-model`). |
| `injection-model` | _(unset)_ | — | Catalog model id used for injection detection; unset disables the classifier. |
| `response-scan-enabled` | `false` | Scan responses | Redact PII/secrets from non-streaming completions. |
| `response-scan-streaming-enabled` | `false` | _(operator only)_ | Also redact streamed completions (adds a lookahead delay). |
