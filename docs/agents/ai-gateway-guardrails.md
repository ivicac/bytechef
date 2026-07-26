<!-- Extracted from CLAUDE.md so the agent-facing reference does not sit in every prompt.
     Load this when working on AI-gateway guardrails, redaction, or the classifier SPIs. -->

# AI Gateway content guardrails (EE)

- `AiGatewayGuardrails` runs in `AiGatewayFacadeImpl` on chat sync + streaming paths (via
  `apply`) and the embeddings path (via `applyToInputs`) after prompt resolution. Effective
  policy per request = global properties (`bytechef.ai.gateway.guardrails.*`) OR'd/unioned with
  the per-workspace `AiGatewayWorkspaceSettings` fields. Everything is off by default. Five
  request-direction controls:
  - PII redaction — `pii-redaction-enabled` / `redactPii` (email, SSN, CC, phone, IPv4 →
    `[REDACTED_*]`). The workspace `redactPii` also drives trace-payload digesting.
  - Secret redaction — `secret-redaction-enabled` / `redactSecrets` (AWS/GitHub/Slack/OpenAI/
    Stripe/Google keys, JWTs, PEM private keys → `[REDACTED_SECRET]`). High-signal curated
    regex subset; entropy detection stays in the workflow-layer `SecretKeyDetectorUtils`.
  - Blocked terms — `blocked-terms` / `blockedTerms` (case-insensitive substring block).
  - Moderation — `moderation-enabled` / `moderationEnabled`, needs an
    `AiGatewayModerationClassifier` bean.
  - Injection detection — `injection-detection-enabled` / `injectionDetectionEnabled`, needs an
    `AiGatewayInjectionClassifier` bean.
- Dual-directional: response scanning (`response-scan-enabled` / `scanResponses`) redacts
  PII+secrets from the completion via `redactResponse` before it is traced/returned. Redaction
  only, never blocks. Streaming responses are also covered (opt-in): when the operator flag
  `response-scan-streaming-enabled` is set AND response scanning is effective for the workspace,
  `newStreamingResponseRedactor` returns a `StreamingResponseRedactor` that masks SSE deltas
  across chunk boundaries (safe-cut / lookahead-window algorithm over
  `sensitiveMatchRanges`) and defers the terminal `finish_reason` onto its flush chunk. Null
  redactor → the streaming path is byte-for-byte unchanged. A value still incomplete and longer
  than the window (default 512) may have a prefix emitted before its pattern matches — documented
  trade-off of not buffering the whole stream.
- Order (request): redact PII → redact secrets → blocked terms → moderation → injection; every
  check sees the redacted text. Embeddings run the same minus moderation. Response path is
  redaction only.
- Per-project overlay: `AiGatewayProjectSettings` (PROJECT-scoped `Property` row
  `ai_gateway_project_settings`, guardrail fields only) layers on top of global+workspace with the
  same **additive union** semantics (a project can enable a guardrail / add blocked terms, never
  turn one off; null = inherit). `resolvePolicy(workspaceId, projectId)` unions all three levels;
  the four guardrail methods have `projectId` overloads (originals delegate with null).
  `AiGatewayFacadeImpl.resolveProjectId` maps the `project_id` request tag (a per-workspace slug)
  to the numeric project id. Admin-only GraphQL: `aiGatewayProjectSettings` /
  `updateAiGatewayProjectSettings`. The project settings service is a Spring-optional `@Nullable`
  dep — absent bean → project layer skipped. Per-API-key scoping is NOT implemented (no api-key
  `Property` scope).
- Violations throw `AiGatewayGuardrailException` (lives in `platform-ai-gateway-api` so the
  public-rest `AiGatewayExceptionHandler` can map it) → HTTP 422 `guardrail_violation`; the
  wire message never echoes the offending content or matched term.
- Both classifier SPIs (`AiGatewayModerationClassifier`, `AiGatewayInjectionClassifier`) are
  Spring-optional `@Nullable` constructor deps; their `PromptBased*` impls register only when
  `moderation-model` / `injection-model` name a catalog model identifier and fail open on any
  error. Regexes must stay free of nested optional quantifiers (SpotBugs ReDoS). Spec:
  `docs/superpowers/specs/2026-07-22-ai-gateway-guardrail-hardening-design.md`.
- Metrics: `AiGatewayGuardrailMetrics` emits the `bytechef_ai_gateway_guardrail` counter tagged by
  `event` (`pii_redacted` / `secret_redacted` / `response_redacted` / `blocked_term` /
  `moderation_flagged` / `injection_flagged`) — low-cardinality (no workspace/project tag), wired
  via `ObjectProvider<MeterRegistry>` so it no-ops without a registry. `AiGatewayGuardrails` takes
  it as a `@Nullable` dep and records at each redact/block/flag point.
