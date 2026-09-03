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

### 1.1 An embedded principal cannot pass the budget check

`AiGatewayFacadeImpl.checkBudget` requires a `workspace_id` tag on every chat completion, and
`validateWorkspaceAccess` then demands tenant-admin or workspace membership. A connected-user principal is
a *vendor's customer*, not a workspace member, and has neither. `EmbeddedAiGatewayPhase2IntTest.setUp`
stubs `permissionService.isTenantAdmin()` to `true` to get a request through at all.

This is phase 1's premise not fully landing — the endpoint resolves identity correctly, but the guard
underneath still speaks only workspace. Phase 2 inherited it.

**Decide:** exempt embedded traffic from the workspace budget, give connected users a synthetic workspace,
or make the check scope-aware. **Done** = an embedded request completes end to end with no test-only stub.

### 1.2 Three features have no write surface

Each has a working service layer and no way in. Spec §2 lists vendor-side binding management as a **Goal**.

| Feature | What exists | What is missing |
|---|---|---|
| Per-customer policy binding | `ConnectedUserAiGatewayRoutingPolicyFacade.bind/unbind`, guarded, tested | zero production callers — no REST, no GraphQL |
| Per-customer spend cap | `AiGatewayEmbeddedSettings.defaultConnectedUserBudgetCap`, enforced before routing | `upsert` has zero production callers |
| BYOK provider creation | `AiGatewayProviderService.updateConnectedUserId`, guarded against workspace-scoped rows | no facade, no surface |

Building an authorization surface is a design decision, which is why the phase-2 run deliberately stopped
at the service layer rather than inventing one.

**Decide:** whether this is one vendor-admin GraphQL surface or three; whether it reuses the `ROLE_ADMIN`
guard its neighbours use; whether it is per-environment. **Done** = an operator can bind a policy, set a
cap and register a customer credential without raw SQL.

## 2. Design decisions — cheap now, expensive once a surface bakes them in

### 2.1 Policy names are unique per tenant, not per customer

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

### 2.2 Policy and provider binding disagree

| | policy | provider |
|---|---|---|
| entry point | facade, `@PreAuthorize(ADMIN)` | bare service setter |
| workspace-scoped target | rejected with a clear message | rejected with a clear message (added late) |
| connected user verified | yes | no |
| already held by another customer | rejected | not checked |
| second binding for the same customer | replaces silently | unique-index violation, unmapped |

**Decide:** one semantic for both, before a surface bakes in two.

### 2.3 May an embedded caller name a workspace-scoped policy?

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

## 4. Fixed along the way — automation-facing, worth knowing

These were found by embedded work but are automation bugs:

- `chatCompletionStream` ignored a model's `defaultRoutingPolicyId` entirely; sync honoured it. Both paths
  now run the same chain.
- `ai_gateway_model_deployment` had no `version` column despite `@Version`, so every insert crashed —
  routing via deployments could not be exercised at all.
- `AiModel.defaultRoutingPolicyId` was accepted by its GraphQL mutation and silently dropped on update.
- `AiGatewayRoutingPolicy.tags` was missing `@MappedCollection`, breaking tag persistence.

## 5. Suggested order

1. §1.1, the budget-check blocker. Everything else is unobservable until it is fixed.
2. §2.1 and §2.2 — decide before §1.2 builds a surface around them.
3. §1.2, the write surfaces. Largest piece; unblocks every phase-2 goal.
4. §2.4, the scope filter, before BYOK rows exist in any real environment.
5. §2.3, §2.5 and §3 as their own tickets.
