# AI Gateway guardrail hardening — implementation plan

Spec: `docs/superpowers/specs/2026-07-22-ai-gateway-guardrail-hardening-design.md`

Phase 1 closes gateway-boundary gaps 1, 2, 3, 5. Ordered so each step compiles on its own.

## Step 1 — Secret redaction (gap 2)

- `AiGatewayGuardrails`: add secret-key `Pattern`s + `redactSecrets(String)`; add
  `globalSecretRedactionEnabled` ctor arg (`secret-redaction-enabled`); compute effective
  `redactSecrets` = global OR workspace `redactSecrets()`; run it after PII in `apply`.
- Files: `AiGatewayGuardrails.java`.

## Step 2 — Injection classifier (gap 3)

- New SPI `AiGatewayInjectionClassifier` in `platform-ai-gateway-api/.../guardrail`.
- New impl `PromptBasedInjectionClassifier` in `automation-ai-gateway-service/.../guardrail`,
  `@ConditionalOnProperty(... name = "injection-model")`, fail-open — mirror
  `PromptBasedModerationClassifier`.
- `AiGatewayGuardrails`: take `@Nullable AiGatewayInjectionClassifier`, add
  `globalInjectionDetectionEnabled` (`injection-detection-enabled`); run the check last in
  `apply`; block with `AiGatewayGuardrailException`.

## Step 3 — Response scanning (gap 1)

- `AiGatewayGuardrails`: add `redactResponseContent(String, workspaceId)` and
  `globalResponseScanEnabled` (`response-scan-enabled`); redaction-only (PII + secrets).
- `AiGatewayFacadeImpl.chatCompletion`: after the response is produced and before
  `processTracingHeaders`, rewrite each choice's message content via a new
  `redactResponse(response, workspaceId)` helper (non-streaming only). Document the streaming
  limitation on the streaming method.

## Step 4 — Embeddings coverage (gap 5)

- `AiGatewayGuardrails`: add `applyToInputs(List<String>, workspaceId)` — redact PII+secrets,
  block on blocked-terms/injection (no moderation).
- `AiGatewayFacadeImpl.embedding`: replace `request.input()` with the guarded list before
  building `EmbeddingRequest`.

## Step 5 — Workspace settings + GraphQL + client (all four toggles)

- `AiGatewayWorkspaceSettings` record: add `redactSecrets`, `injectionDetectionEnabled`,
  `scanResponses`.
- `AiGatewayWorkspaceSettingsServiceImpl`: new keys in `toMap`/`toSettings`.
- Update the two other `new AiGatewayWorkspaceSettings(...)` call sites (GraphQL controller,
  test helper).
- `ai-gateway-workspace-settings.graphqls`: add the three booleans to type + input.
- `AiGatewayWorkspaceSettingsGraphQlController`: add to input record + upsert mapping.
- Client: `AiGatewaySettings.tsx` form + `aiGatewayWorkspaceSettings.graphql` operation;
  regenerate `graphql.ts` via `npx graphql-codegen`.

## Step 6 — Tests + docs + format

- Extend `AiGatewayGuardrailsTest`; add `PromptBasedInjectionClassifierTest`.
- Update CLAUDE.md "AI Gateway content guardrails (EE)" and module README.
- `./gradlew spotlessApply` on the touched modules; compile where the toolchain allows.
- Commit (`732 …` server, `732 client - …` client) and push to
  `claude/bytechef-branch-0-732-i2mjs0`.

## Verification checklist

- [ ] Secret patterns redact and are ReDoS-safe (no nested optional quantifiers).
- [ ] Injection classifier fails open; registered only with `injection-model` set.
- [ ] Response redaction non-streaming only; streaming limitation documented.
- [ ] Embeddings inputs redacted/blocked before upstream call.
- [ ] New toggles union global OR workspace, off by default.
- [ ] All `new AiGatewayWorkspaceSettings(...)` call sites updated.
- [ ] Tests cover each new path.
