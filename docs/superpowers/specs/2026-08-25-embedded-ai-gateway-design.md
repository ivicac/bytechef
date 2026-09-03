# Embedded AI Gateway — Design (per-connected-user routing policies)

- **Date:** 2026-08-25
- **Branch:** `claude/opennlp-smart-routing-gateway-82112a`
- **Status:** Accepted. Scope model (option A, connected-user-scoped) chosen by the user from three
  presented alternatives; all ⚑ decisions in §14 were put to the user on 2026-08-25 and answered, two of
  them against the recommendation. §14 records which.
- **Ticket:** none filed yet.
- **Related:** `2026-08-24-prompt-complexity-scorer-bakeoff-design.md` (the routing scorer this surface
  inherits unchanged), `CLAUDE.md` § "Variables (workspace / embedded organization, EE)" (the
  `Scope.EMBEDDED` precedent §6 reuses).

## 1. Summary

The AI Gateway does not exist for embedded. There is no gateway module under `server/ee/libs/embedded/`,
and no file there references `AiGateway` at all. Every gateway consumer sits inside
`automation-ai/*`, and every gateway resource is scoped by `workspaceId` — a concept embedded does not
have.

That is a product gap rather than an oversight. A SaaS vendor embedding ByteChef wants to run model
traffic for *their* customers: different customers on different plans routed to different model tiers,
spend attributed per customer, and a sane default when a customer has no policy of their own. The
competitor this codebase already tracks markets exactly that — per-customer routing policies falling back
to organization defaults, per-customer budgets, and per-tenant spend reporting.

This spec adds that surface. A gateway request made on behalf of a connected user resolves its routing
policy in three steps: the policy bound to that connected user, else the embedded default policy, else
the system default. The routing engine, the strategies, the tier model and the complexity scorer are
untouched — they live in `platform-ai-gateway` and are inherited whole.

Two things make this more than plumbing, and are why it needs a spec:

1. **The fallback slot exists but is unreachable.** `AiGatewayRoutingPolicy.workspaceId` is nullable per
   platform convention, but no read path selects null-scoped rows (§3.3). The "organization default" is
   half-built, and building its read path is a prerequisite for either phase.
2. **Identifying the connected user cuts across two authentication stacks** that mint different tokens
   from different sources (§3.5), and the obvious way to bridge them steps directly onto a documented
   trap that has already produced one phantom-user bug (§3.6).

## 2. Goals / non-goals

**Goals**

- A gateway endpoint usable on behalf of a connected user, authenticated by the vendor's credentials.
- Routing policy resolution: connected user → embedded default → system default.
- Spend and observability attributed to the connected user.
- An embedded settings record equivalent to the workspace one, including a default policy pointer.
- No change to routing behaviour for existing automation traffic.

**Non-goals**

- **Per-customer provider credentials (BYOK) in Phase 1.** A connected user supplying their own model
  API key touches the connection model and credential encryption. It is **in scope for Phase 2** (§10),
  designed alongside per-customer policies so credentials and policies land together rather than in two
  passes over the same resolution code. ⚑5.
- **A customer-facing UI.** Policies are assigned by the vendor through their own product or the
  management API; ByteChef ships no end-customer console.
- **Changing the automation gateway.** Its route, its auth, and its workspace scoping stay exactly as
  they are.
- **Per-customer rate limiting.** Budgets and spend caps are in scope; request-rate shaping is not.
- **Self-service policy creation by connected users.** Policies are vendor-owned objects.

## 3. What is already true

Established by reading the code. Each of these removes work or forbids an approach.

**3.1 There is no embedded gateway of any kind.** `server/ee/libs/embedded/embedded-ai/` contains MCP
modules, copilot and tool — no gateway. A grep for `AiGateway` across all of `server/ee/libs/embedded/`
returns nothing. This is greenfield, not a migration.

**3.2 The routing engine is already edition-agnostic.** `PromptComplexityScorer`, the strategies,
`AiGatewayModelTier` and `AiGatewayRoutingContext` live in `platform-ai-gateway`; only the facade,
GraphQL and public REST surfaces are automation-scoped. An embedded surface inherits the engine without
touching it — including whichever scorer the bake-off selects.

**3.3 The null-scope fallback is a dead branch.** `AiGatewayProvider` and `AiGatewayRoutingPolicy` both
carry `@Nullable Long workspaceId`. But `AiGatewayRoutingPolicyServiceImpl.getRoutingPoliciesByWorkspaceId`
takes a **primitive** `long`, and the module contains no `findAllByWorkspaceIdIsNull` or equivalent. A
null-scoped policy can be written and is then invisible to every read. The fallback slot is present in
the schema and absent from the code.

**3.4 Gateway settings already use the platform property store, not a table.**
`AiGatewayWorkspaceSettings` is a record persisted as one `Property` row with `scope = WORKSPACE` and key
`ai_gateway_workspace_settings`. Every field is nullable and null means "inherit from the system
default", and it already carries `defaultRoutingPolicyId` — the pointer this design needs for its middle
resolution step. Because the property store is scope-generic, an embedded twin needs no new table (§6).

**3.5 The two authentication stacks mint different tokens.**
`AiGatewayApiKeyAuthenticationToken` is constructed as `(environmentId, secretKey, tenantId)` and resolves
to a `User`; it carries **no** customer identity. `EmbeddedApiKeyAuthenticationConverter` mints
`(environment, externalUserId, authToken, tenantId)`, taking `externalUserId` either from a JWT `sub`
claim or from the first path segment after `/api/embedded/v<n>/`. Connected-user identity therefore
exists on the embedded side and is missing on the gateway side.

**3.6 The embedded path namespace carries a trap with scar tissue.** Any route under
`/api/embedded/v<n>/` that does **not** have an `{externalUserId}` first segment must be hand-registered
in `ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS`, or the converter reads that route's literal
first segment as a user id and mints a phantom connected user. The set contains a retired `external`
literal precisely because that bug already happened once with the MCP integration-instance controllers.
The list is explicitly a hand-maintained allowlist, not a derivation.

**3.7 The gateway already selects scope with an `X-ByteChef-*` header.**
`AiGatewayScoreApiController` accepts an optional `X-ByteChef-Workspace-Id` header. Scope-by-header is an
established pattern inside this module, not a new invention (§5).

**3.8 The gateway's route namespace is its own.** The controller is mapped at `/api/ai-gateway/v1` and
its security configurer matches `^/api/ai-gateway/v[0-9]+/.+`. It does not overlap
`/api/embedded/v<n>/`.

**3.9 `ConnectedUser` is the end-customer primitive and already carries environment.** Its fields are
`externalId`, `email`, `name`, `enabled`, `environment` and a metadata map. It has no `workspaceId` —
embedded tenancy is tenant plus environment plus connected user. `IntegrationInstance` binds
`connectedUserId` to an `integrationInstanceConfigurationId` and a `connectionId`.

## 4. Scope model

A routing policy gains an optional **connected user** binding alongside its existing optional workspace
binding. The two are mutually exclusive: a policy belongs to a workspace (automation), to a connected
user (embedded), or to neither (the default tier).

Exclusivity is enforced by a database check constraint (`workspace_id IS NULL OR connected_user_id IS
NULL`), not only by service code. A check constraint can express this one — unlike the API-collection
name uniqueness that environment promotion had to leave as an app-level race, because that predicate
reaches through a join and this one does not. There is no reason to accept a weaker guarantee here.

Resolution for an embedded gateway request, in order:

1. **The policy bound to this connected user**, if one exists and is enabled.
2. **The embedded default policy** — `defaultRoutingPolicyId` from the embedded settings record (§6).
3. **The system default** — existing behaviour when no policy resolves.

Step 2 is what §3.3 makes impossible today and what phase 1 builds.

Two properties this ordering must hold, and which the tests in §12 pin:

- **A connected user never reaches another tenant's or another connected user's policy.** Resolution is
  always performed from the authenticated `(tenantId, environment, connectedUserId)` triple, never from
  a policy id supplied by the caller. A policy id in a request body selects among *that* connected
  user's resolvable policies or is rejected — it is never a lookup key on its own. This mirrors the rule
  `VariableServiceImpl` follows, where every by-id operation re-lists the scope rather than trusting the
  id.
- **Falling back is not the same as having none.** A connected user with no policy of their own inherits
  the embedded default; a connected user whose policy is explicitly *disabled* also falls back. Neither
  case is an error, and neither silently routes to the most expensive model — the existing
  most-capable-tier fallback in `AiGatewayFacadeImpl` applies only to scorer failure, not to policy
  absence.

## 5. Identifying the connected user

**Decision, revised 2026-09-02: a separate embedded endpoint, with identity from authentication.**

```
POST /api/embedded/v1/{externalUserId}/ai-gateway/chat/completions
```

The automation gateway keeps its own route, `/api/ai-gateway/v1/chat/completions`, **unchanged**. The two
surfaces are disjoint at every level — path, security configurer, and the identity each mints.

**How identity arrives.** The route falls under `EmbeddedApiKeySecurityConfigurer`'s existing pattern
`^/api/embedded/v[0-9]+/.+`, so `EmbeddedApiKeyAuthenticationConverter` already mints
`(environment, externalUserId, tenantId)` — from a JWT `sub` claim, or from the first path segment after
`/api/embedded/v<n>/`. The controller then calls `SecurityUtils.checkCurrentUserLogin(externalUserId)`,
exactly as `ActionApiController` in `embedded-execution-public-rest` does, so a caller cannot address
another customer's identity.

This is the material improvement over the previous design: the connected user comes from
**authentication**, not from a header any caller could set at will.

**What this retires.** Three things built in the first pass are removed rather than reshaped:

- **The `X-ByteChef-External-User-Id` header**, and with it `resolveExternalUserIdHeader()`'s raw
  servlet-request read. The automation endpoint reverts to exactly what it was.
- **The `ConnectedUserResolver` SPI** in `platform-ai-gateway-api`, and the
  `embedded-ai-gateway-connected-user` module implementing it. That seam existed only because identity
  arrived at an *automation*-module controller, which must not depend on embedded. A controller living in
  an embedded module may depend on `embedded-connected-user-api` directly and resolve the connected user
  itself.
- **The `externalUserId` parameter on the facade overloads.** They now take a resolved
  `@Nullable Long connectedUserId`; resolution happens at the edge, in the embedded controller, where the
  identity already is.

**Why the previous design chose a header, and why that reasoning no longer holds.** It was chosen to
preserve the OpenAI drop-in property on a *shared* endpoint — a path segment would have forced a distinct
base URL per customer. With a separate embedded endpoint that objection evaporates: the embedded surface
is not the OpenAI-compatible automation surface and does not have to look like it. The per-customer base
URL is also the shape the competitor's documented key-per-customer model produces in practice.

**The trap this now avoids rather than sidesteps.** `ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS`
catches routes under `/api/embedded/v<n>/` that lack an `{externalUserId}` first segment — the converter
would otherwise read a literal path segment as a user id and mint a phantom connected user. This route
**has** that segment, so it is on the safe side and needs no allowlist entry. §3.6 records the retired
`external` literal that exists because this bug shipped once.

**A rejected alternative, recorded because the competitor uses it.** Merge provisions an API key per
customer, so identity comes from the credential and every customer shares one base URL. That was
considered and set aside: it leaves both kinds of traffic on one surface, so every control — rate limits,
auth policy, metrics, enforcement — has to be conditional rather than architectural. Separate endpoints
were chosen deliberately for that control. The two are not exclusive; per-customer keys could be added
later for provisioning convenience without changing the route.

## 6. Settings and the embedded default

An embedded settings record mirrors `AiGatewayWorkspaceSettings`, persisted the same way: one `Property`
row, key `ai_gateway_embedded_settings`, `scope = Property.Scope.EMBEDDED` (the nested enum, whose values
are `PLATFORM, AUTOMATION, EMBEDDED, WORKSPACE, PROJECT, INTEGRATION`), `scopeId = null`, `environment`
always set.
Fields match the workspace record — retry count, timeout, cache, log retention, `defaultRoutingPolicyId`,
soft budget warning percentage — with the same "null means inherit the system default" semantics, plus an
`environmentId`.

**The row is per environment, and that is not cosmetic.** The partial unique index is on
`(key, scope, environment) WHERE scope_id IS NULL`, so uniqueness is per environment and one settings row
exists per environment. Reads and writes must therefore use `PropertyService`'s four-argument
`fetchProperty`/`save` overloads. `AiGatewayWorkspaceSettingsServiceImpl` uses the three-argument
overloads and is not per-environment; its shape is the model for everything here *except* that.

This follows the precedent `CLAUDE.md` documents for Variables, which introduced `Scope.EMBEDDED` with a
null `scopeId` for exactly this shape. **That precedent also carries a warning this design must heed:**
the existing unique constraint on `property (key, scope, scope_id, environment)` never fires for null
`scope_id`, because Postgres treats every null as distinct, and two concurrent creates both inserted
until a partial unique index was added. Variables' changelog
(`20260825000001_platform_configuration_property_unique_null_scope_id.xml`) added
`uk_property_key_scope_environment_null_scope_id` on `property (key, scope, environment) WHERE scope_id
IS NULL`. That changelog and index were confirmed present in this repo while writing this spec, and they
cover this record too — no new changelog is needed for the settings row, but the service must still
translate the resulting `DataIntegrityViolationException` rather than surfacing it raw.

## 7. Data model

- **`ai_gateway_routing_policy` gains `connected_user_id BIGINT NULL`** (Phase 2), with a partial unique
  index on `connected_user_id` and the check constraint from §4. **Not** a composite key with
  environment: the gateway schema has no environment column, and `ConnectedUser` already carries one, so
  environment scoping is inherited through the id. See the Phase 2 spec §4 and ⚑8. Nullable `Long` on the domain
  object, never primitive; null is a real state meaning "not connected-user-scoped".
- **A read path for null-scoped policies.** `AiGatewayRoutingPolicyService` gains an explicit method for
  the default tier, and `getRoutingPoliciesByWorkspaceId`'s primitive parameter is left alone rather than
  widened — a separate method is clearer than overloading null onto a scope lookup, and it keeps the
  automation path's signature honest.
- **No new table for settings** (§6).
- **No change to `ai_gateway_provider` in Phase 1.** Per-customer providers are BYOK, which Phase 2
  owns (⚑5); its scoping column lands with the policy one, not before.
- Enum ordinals are persisted as INT throughout the gateway; nothing here reorders an existing enum.

## 8. Authorization and isolation

- Gateway authentication continues to resolve a vendor `ApiKey` scoped to `(tenantId, environment)`. The
  connected user is resolved *within* that scope — an `externalId` from another tenant does not resolve,
  and the failure is indistinguishable from an unknown id.
- Policy management (create, update, delete, bind to a connected user) is a vendor-admin operation and
  goes through an API facade that owns the authorization check, per the repo's API-facade rule. The
  shared facade stays unguarded for runtime use.
- A connected user cannot enumerate, read, or select policies belonging to another connected user. §12
  pins this with a test that asserts a policy id from connected user B is indistinguishable from a
  missing one when resolved as connected user A.

## 9. Spend, observability and budgets

- Spend rollup keys gain the connected user, so per-customer attribution and pass-through billing data
  fall out of the existing rollup rather than needing a parallel mechanism.
- Observability spans already persist `input`, `output`, `model`, `cost` and `latencyMs`; they gain the
  connected user as an attribute, not a new span type.
- The soft budget warning percentage in the settings record applies per connected user once policies are
  connected-user-scoped. Hard spend caps per connected user are in scope for phase 2; request-rate
  shaping is not (§2).

## 10. Phasing

**Phase 1 — reachability and the default tier.** The embedded settings record, the null-scope read path,
`defaultRoutingPolicyId` resolution, and a dedicated embedded endpoint (§5) resolving the connected user
from authentication, with spend and observability attribution. *(Revised 2026-09-02: an earlier version of
this paragraph described a header on the shared endpoint. See ⚑8.)* At the end of phase 1 every
connected user of a tenant shares one policy, and that is a coherent, shippable product.

**Phase 2 — per-connected-user policies, credentials, and header enforcement.** The `connected_user_id`
column, binding management, resolution step 1, per-customer budgets, per-customer provider credentials
(BYOK, ⚑5), and the embedded-gateway `ApiKey` type that makes the external-id header mandatory (⚑3).

The three additions travel together deliberately. All of them need the same thing Phase 1 does not have:
a per-customer identity that is *required* rather than optional. Once a request must name its connected
user, binding a policy to that user, billing it, and resolving its credentials are the same lookup.

The phasing is chosen so phase 1 is independently useful and phase 2 is additive. Phase 1 builds the
fallback the whole resolution chain terminates in; doing it second would mean writing step 1 against a
step 2 that does not exist.

## 11. Error handling and known gaps

- **An `externalUserId` that does not match the authenticated principal:** rejected by
  `SecurityUtils.checkCurrentUserLogin`, before any gateway logic runs.
- **Unknown or disabled connected user:** 403 from the embedded controller. Never auto-created (§5).
- **The fail-open resolver gap is gone.** The previous design carried an `ObjectProvider`-resolved SPI
  that silently resolved nothing when absent, needing a WARN to make the misconfiguration visible. With
  the embedded controller depending on `embedded-connected-user-api` directly, an absent dependency is a
  compile error rather than a runtime silence.
- **No header to be absent.** The two surfaces are separate routes (§5), so an embedded request either
  reaches the embedded endpoint carrying an authenticated identity or it does not reach it at all. The
  misconfiguration this bullet used to describe — a vendor client silently getting tenant-default routing
  — is not expressible. *(Revised 2026-09-02; supersedes the header-absent handling and ⚑3.)*
- **Phase 2 needs no header enforcement.** ⚑3 planned a distinct embedded-gateway `ApiKey` type to make
  a header mandatory. With a dedicated endpoint the identity is structural, so that mechanism is
  unnecessary and Phase 2 drops it. *(Revised 2026-09-02.)*
- **Policy resolves to a disabled policy:** falls through to the next step, not an error (§4).
- **Scorer failure:** unchanged — the existing catch in `AiGatewayFacadeImpl` substitutes the
  most-capable tier.
- **Known gap:** a vendor cannot express "this customer may not use the gateway at all" except by
  disabling the connected user entirely. A per-customer gateway-enabled flag is not in this design.
- **Known gap:** distributed EE deployments. Only `server-app` is in scope here; whether
  `ai-gateway-app` carries the embedded modules is a deployment question this spec does not answer, and
  the same trap the Variables feature hit — a module absent from an app's classpath failing open and
  silently — applies. ⚑4.

## 12. Testing

- Resolution order: connected-user policy wins over embedded default wins over system default; a
  disabled policy at any level falls through.
- Isolation: a policy id belonging to connected user B is indistinguishable from missing when resolved as
  A; an `externalId` from another tenant does not resolve. The endpoint additionally verifies the path's
  `externalUserId` against the authenticated principal via `SecurityUtils.checkCurrentUserLogin`, on both
  the JSON and streaming mappings.
- No phantom users: an unknown `externalId` yields **403** (not 401 — the caller's credentials
  authenticated fine; the identity they named did not resolve) and creates no `ConnectedUser` row. This is a
  regression test for the §3.6 failure mode, written even though this design avoids that namespace.
- Header absent: automation traffic routes exactly as before — an integration test asserting the existing
  behaviour is unchanged.
- Settings: a null field inherits the system default; the null-`scope_id` uniqueness violation is
  translated, not surfaced raw.
- Null-scope read path: a policy written with a null scope is returned by the new default-tier method and
  is *not* returned by `getRoutingPoliciesByWorkspaceId`.

## 13. Rollout

Phase 1 is additive and off by default in effect: with no embedded settings row and no header, behaviour
is byte-for-byte what it is today. No migration backfills anything. The `connected_user_id` column in
phase 2 is nullable with no default, so existing rows are untouched.

## 14. Decisions (⚑ = put to the user on 2026-08-25 and answered)

- **⚑1 Connected-user scoping (option A), chosen by the user** over integration-instance scoping and
  tenant-only scoping. Integration-instance scoping cannot express "this customer is on a cheaper plan",
  which is the main reason to want the feature; tenant-only is phase 1 of this design rather than an
  alternative to it.
- **⚑2 Header rather than path segment (§5).** Preserves the OpenAI drop-in property and avoids the
  §3.6 namespace entirely, at the cost of teaching the gateway's auth token about connected users. The
  rejected alternative would have reused the embedded converter wholesale.
- **⚑3 SUPERSEDED by ⚑8.** With a dedicated embedded endpoint carrying identity in its path, there is no
  header to be absent and nothing to enforce. Retained below for the record only.
  Original: **Header-absent is permissive in Phase 1 and enforced in Phase 2 — the user chose enforcement
  over the recommendation.** The recommended option was to keep it permissive indefinitely with a warning log.
  The user instead chose to require the header on embedded-type API keys, using the existing `ApiKey.type`
  discriminator, so automation callers are unaffected and embedded misconfiguration becomes an error. The
  cost is a new key type and provisioning path, which is why enforcement lands in Phase 2 rather than
  Phase 1.
- **⚑4 Monolith only.** Same posture as environment promotion and orphaned-job recovery. Distributed EE
  wiring is explicitly unanswered, and §11 records why that is a real risk rather than a formality.
- **⚑5 BYOK folded into Phase 2 — the user chose this over the recommendation.** The recommendation was
  to defer it to its own later spec, on the grounds that no customer has asked. The user chose to design
  it alongside per-customer policies instead, which avoids a second pass over the same resolution code at
  the cost of a larger Phase 2. Phase 1 is unaffected either way.
- **⚑6 No end-customer UI.** Policies are vendor-owned; ByteChef ships no console for the vendor's
  customers. If the vendor wants to expose policy choice to their users, they do it through their own
  product against the management API.
- **⚑8 Separate embedded endpoint, decided 2026-09-02 — reverses ⚑2.** The first pass put both surfaces
  on one route and distinguished them by an `X-ByteChef-External-User-Id` header. The user asked for
  separate endpoints "so we can more easily control it", and that is the better design: identity moves
  from a caller-settable header to authentication, the SPI seam and its module are retired, the
  reserved-segment trap is avoided structurally rather than sidestepped, and Phase 2's enforcement
  mechanism (⚑3) becomes unnecessary. Cost: Phase 1 is already built, so this reworks its Task 5 and
  deletes its Task 3. The competitor's key-per-customer alternative was considered and set aside (§5).
- **⚑9 The header is removed outright, not deprecated, decided 2026-09-02.** Nothing depends on it — it
  was added in this same unlanded phase — so a deprecation path would preserve a caller-settable identity
  channel for no benefit.
