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
| **Settings** | Workspace gateway settings — caching, log retention, and PII redaction. |
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
