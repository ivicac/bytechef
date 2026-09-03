# Embedded AI Gateway — remaining work after Phase 2

**Status as of 2026-09-03.** Phases 1 and 2 are implemented and reviewed. Neither is *usable*: the
resolution engine works and is well tested, and almost nothing that configures it can be reached by an
operator. This document is the register of what is left, why each item matters, and what "done" looks
like.

Specs: `2026-08-25-embedded-ai-gateway-design.md` (phase 1),
`2026-08-25-embedded-ai-gateway-phase-2-design.md` (phase 2).
Plans: `2026-08-25-embedded-ai-gateway-phase-1.md`, `2026-08-25-embedded-ai-gateway-phase-2.md`,
`2026-09-02-embedded-ai-gateway-separate-endpoint-rework.md`.

## What works today — do not rebuild it

- `POST /api/embedded/v1/{externalUserId}/ai-gateway/chat/completions`, JSON and SSE. Identity comes from
  the authenticated principal and is checked against the path parameter; an unknown or disabled connected
  user is rejected with 403 and never auto-created.
- A four-level routing chain, identical on the sync and streaming paths:
  request-specified → connected-user → model default → embedded default.
- Spend attributed per connected user, guarded so only gateway-originated usage rows contribute.
- Per-customer provider credentials (BYOK) resolved customer-then-tenant, through the same factory path as
  tenant providers, so the SSRF check and credential decryption cannot be bypassed.
- Cross-customer guards on policies, providers and the response cache.
- `openapi.yaml` for the embedded endpoint, with models generated from it.

## 1. Blockers — nothing embedded reaches the gateway until these are decided

### 1.1 ~~An embedded principal cannot pass the budget check~~ — RESOLVED 2026-09-14

**Decided: scope-aware.** Exempting only the guard was not viable: `AiLlmUsageServiceImpl.create` rejects a
null workspace, the spend rollup iterates `findDistinctWorkspaceIds()` (`WHERE workspace_id IS NOT NULL`),
and retention runs per workspace — so the per-connected-user cap would have read $0 forever and the rows
would never have been deleted. A synthetic workspace contradicts `AiGuardrailsSettingsTarget`, which
deliberately ignores workspaces for embedded runs.

What landed, keyed on `connectedUserId != null` in `AiGatewayFacadeImpl`:

- `checkRequestScope` skips the workspace budget, rate limits and membership check, and rejects an
  embedded request carrying a `workspace_id` or `project_id` tag with a 400. Post-request workspace
  enforcement is skipped too; `checkConnectedUserBudget` governs embedded spend.
- Usage rows go through `AiLlmUsageService.createForConnectedUser` (null workspace, connected user in
  `userId`); `create(usage, workspaceId)` keeps its required workspace for every other writer.
- `AiGatewaySpendRollupJob` gained a workspace-less pass writing workspace-less summaries through
  `AiGatewaySpendService.create`, applying the same gateway-only `connectedUserIdOf` guard.
- `AiObservabilityDataCleanupService` deletes workspace-less usage at the longest embedded
  `logRetentionDays` across environments (default 30 for an environment that sets none).

**Done** as defined: `EmbeddedAiGatewayPhase2IntTest` runs every embedded call as an API key principal in
STAGING with no tag and no admin stub, and
`testEmbeddedSpendIsRolledUpAndEnforcedByTheConnectedUserCapWithoutAnyWorkspace` proves request → usage
row → rollup → cap rejection against real Postgres.

Two bugs on the same path, found while designing this and fixed with it:

- **Wrong environment.** The facade matched only `AiGatewayApiKeyAuthenticationToken`; the embedded
  endpoint authenticates with `EmbeddedApiKeyAuthenticationToken`, a sibling under
  `AbstractApiKeyAuthenticationToken`. Every embedded request read environment `0` (DEVELOPMENT) embedded
  settings. It now uses `PrincipalEnvironment` and refuses a principal carrying no environment. The old
  tests passed only because their fixtures used `DEVELOPMENT.ordinal()`, which is also 0.
- **Wrong guardrail settings.** `AiGatewayGuardrails` resolved `AiGuardrailsSettingsTarget.resolve(null,
  workspaceId)`, so embedded traffic read the automation PLATFORM row — including moderation, which read
  `fetchSettings(null)`. `apply`, `scanResponse` and `newStreamingResponseRedactor` now take a
  `PlatformType`; embedded reads `fetchEmbeddedSettings()`.

### 1.2 Three features have no write surface

Each has a working service layer and no way in. Spec §2 lists vendor-side binding management as a **Goal**.

| Feature | What exists | What is missing |
|---|---|---|
| Per-customer policy binding | `ConnectedUserAiGatewayFacade.assignRoutingPolicy/unassignRoutingPolicy`, guarded, tested | service and ADMIN facade exist (`ConnectedUserAiGatewayFacade`); no REST or GraphQL surface |
| Per-customer spend cap | `ConnectedUserAiGatewayFacade.updateBudgetCap`, enforced before routing | service and ADMIN facade exist (`ConnectedUserAiGatewayFacade`); no REST or GraphQL surface |
| BYOK provider creation | `ConnectedUserAiGatewayFacade.createProvider` (`AiGatewayProviderService.createConnectedUserProvider`), guarded against workspace-scoped rows | service and ADMIN facade exist (`ConnectedUserAiGatewayFacade`); no REST or GraphQL surface |

Building an authorization surface is a design decision, which is why the phase-2 run deliberately stopped
at the service layer rather than inventing one.

**Decide:** whether this is one vendor-admin GraphQL surface or three; whether it reuses the `ROLE_ADMIN`
guard its neighbours use; whether it is per-environment. **Done** = an operator can bind a policy, set a
cap and register a customer credential without raw SQL.

## 2. Design decisions — cheap now, expensive once a surface bakes them in

### 2.1 ~~Policy names are unique per tenant, not per customer~~ — RESOLVED 2026-09-14

Resolved by shared plans (`2026-09-14-embedded-ai-gateway-shared-plans-design.md`): a routing policy is a
plan many customers are assigned to, so a tenant-unique name identifies a plan. No schema change to names.

**Corrected 2026-09-03.** An earlier revision of this document claimed `name` had no uniqueness constraint
and that collisions would surface as an unmapped 500. That is wrong: `ai_gateway_routing_policy.name` is
declared `<constraints nullable="false" unique="true"/>` inline on the column, so it is unique within the
tenant schema. `findByName` can never return two rows, and the chain's id → name → id round-trip is safe.

The real consequence runs the other way. Because names are unique **per tenant**, two customers of the same
vendor cannot both hold a policy called `premium` — the vendor must invent globally distinct names
(`premium-customer-42`). For a feature whose premise is per-customer configuration, the natural model would
scope name uniqueness **per connected user**, as `uk_ai_gateway_routing_policy_connected_user_id` already
scopes the binding itself.

**Decide:** leave names tenant-unique and let the admin surface generate distinct ones, or narrow the
constraint to `(connected_user_id, name)` so customers can reuse a shared vocabulary. The second is a
schema change and is cheapest while the changelog is still unreleased.

### 2.2 ~~Policy and provider binding disagree~~ — RESOLVED 2026-09-14

Resolved by modelling two relationships: plans are assigned through `ai_gateway_connected_user_settings`;
credentials are owned, bound at creation, disabled rather than released when their customer is deleted, and
managed through `ConnectedUserAiGatewayFacade`.

| | policy | provider |
|---|---|---|
| entry point | facade, `@PreAuthorize(ADMIN)` | bare service setter |
| workspace-scoped target | rejected with a clear message | rejected with a clear message (added late) |
| connected user verified | yes | no |
| already held by another customer | rejected | not checked |
| second binding for the same customer | replaces silently | unique-index violation, unmapped |

**Decide:** one semantic for both, before a surface bakes in two.

### 2.3 ~~May an embedded caller name a workspace-scoped policy?~~ — RESOLVED 2026-09-14

No. An embedded caller may name only its own assigned plan; any other name fails as a missing policy. The
endpoint accepts end-user JWTs, so a looser rule would be a self-service plan upgrade.

Today yes: a workspace-scoped policy carries a null `connectedUserId` and passes the cross-customer guard
unchanged. The guard's Javadoc records this as open rather than claiming it is handled.

### 2.4 Where the BYOK scope filter belongs

`AiGatewayProviderResolverImpl.resolveTenantProvider` excludes other customers' providers. Five sibling
lookups do not, and all become live the moment `ai_gateway_provider.connected_user_id` rows exist:
`AiModelCatalogReconcilerImpl` (BYOK providers acquire catalog models), `AiGatewayModelApiController`
(`/v1/models`), `PromptBasedModerationClassifier`, `PromptBasedInjectionClassifier`, and
`OtlpCostResolver`. The two classifiers fail open, so a guardrail call spending a customer's key would be
silent.

**Decide:** filter inside `getEnabledProviders` once, or in each caller. The former is the only option that
also covers the next caller someone adds.

### 2.5 The response cache has no tenant dimension

`AiGatewayResponseCacheImpl` declares an explicit SpEL `key`, which bypasses `TenantKeyGenerator`, so
tenant X's customer 5 and tenant Y's customer 5 share a key in one process-global store. The same applies
to `AiGatewayChatModelFactoryImpl` caching a built `ChatModel` — holding a decrypted API key — by
`provider.id`. Pre-existing and not worsened by phase 2, but the connected-user key added in phase 2 was
justified by data residency, and that justification does not hold across tenants.

## 3. Known gaps, recorded rather than fixed

- **Embedded requests write no traces or spans.** Observability traces are workspace-scoped
  (`createInWorkspace`), and `processTracingHeaders` returns early for a null workspace. Before §1.1 this
  was invisible — no embedded request got that far in production. Needs its own scope decision, the same
  shape as §1.1's.

- **Distributed EE.** `configuration-app` carries `embedded-connected-user-service` but not
  `platform-ai-gateway-service`. Both new beans are gated on `bytechef.ai.gateway.enabled`, which it does
  not set, so it wires — but setting that flag there would fail the context, and the unbind-on-delete
  listener is inert, leaving a dangling `connected_user_id` a re-used id could inherit. Spec §11 declares
  the feature monolith-only.
- **Cap lag.** The cap reads hourly-rolled summaries, so a customer can overshoot by up to an hour. Same
  behaviour as the workspace budget.
- **SSE error frames** echo raw exception messages, bypassing the non-leaking REST body.
- **BYOK cannot stand alone.** `resolveModel` resolves a tenant provider and a catalog model *before*
  consulting the resolver, so a customer holding a working key still fails if the vendor has no provider
  of that type, and a model existing only under the customer's provider is unreachable.
- **Embedding has no connected-user entry point,** so BYOK cannot apply there — chat only was ever threaded.
- **Local dev.** Several tasks edited the unreleased init changelog in place, so an existing dev database
  fails Liquibase validation until `scripts/dev/sync-local-schema-after-collapse.sh` runs. It was not run
  during the phase-2 work because the dev Postgres is shared across worktrees.
- **An explicitly named disabled assigned plan is not rejected.** `resolveAssignedRoutingPolicyId` (the
  implicit chain) skips a disabled plan and falls through to the next precedence level, but
  `requireRequestedRoutingPolicyIsAssignedPlan` (the explicit `routingPolicy` field on the request) only
  checks that the named policy matches the assignment by name — it never checks `isEnabled()`. A connected
  user can still route through their own plan by naming it after an operator disables it. Must be closed
  before a public REST/GraphQL surface ships.

## 4. Fixed along the way — automation-facing, worth knowing

These were found by embedded work but are automation bugs:

- `chatCompletionStream` ignored a model's `defaultRoutingPolicyId` entirely; sync honoured it. Both paths
  now run the same chain.
- `ai_gateway_model_deployment` had no `version` column despite `@Version`, so every insert crashed —
  routing via deployments could not be exercised at all.
- `AiModel.defaultRoutingPolicyId` was accepted by its GraphQL mutation and silently dropped on update.
- `AiGatewayRoutingPolicy.tags` was missing `@MappedCollection`, breaking tag persistence.

## 5. Suggested order

1. ~~§1.1, the budget-check blocker.~~ Done 2026-09-14.
2. §2.1 and §2.2 — decide before §1.2 builds a surface around them.
3. §1.2, the write surfaces. Largest piece; unblocks every phase-2 goal.
4. §2.4, the scope filter, before BYOK rows exist in any real environment.
5. §2.3, §2.5 and §3 as their own tickets.
