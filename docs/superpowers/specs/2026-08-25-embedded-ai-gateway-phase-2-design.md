# Embedded AI Gateway Phase 2 — Design (per-connected-user policies, budgets, credentials, enforcement)

- **Date:** 2026-08-25
- **Branch:** `claude/opennlp-smart-routing-gateway-82112a`
- **Status:** Draft — awaiting review.
- **Ticket:** none filed yet.
- **Related:** `2026-08-25-embedded-ai-gateway-design.md` (Phase 1; its §10 defines this phase's contents,
  and its ⚑3 and ⚑5 are the decisions that put enforcement and BYOK here).

## 1. Summary

Phase 1 makes the gateway reachable on behalf of a connected user and gives every connected user of a
tenant the same policy. Phase 2 makes the customer the unit: their own routing policy, their own budget,
optionally their own model credentials, and a request that is *required* to say whose behalf it is on.

The four additions look independent and are not. Each needs the same thing Phase 1 deliberately does not
have — **a per-customer identity that is mandatory rather than optional**. Once a request must name its
connected user, binding a policy to that user, billing it, and resolving its credentials are the same
lookup against the same id. Building them separately would mean threading that identity through the same
resolution path three times.

Writing this spec turned up two facts that change *how* Phase 1's decisions get implemented, without
changing what they decided:

1. **`PlatformType` is a two-value platform-wide enum** (`AUTOMATION, EMBEDDED`), persisted as an INT
   ordinal, not an API-key-specific type list. "Mint a distinct embedded-gateway key type" — the phrasing
   Phase 1's ⚑3 was chosen under — is the wrong mechanism (§3.2). The right one is simpler and needs no
   new enum value.
2. **`AiGatewaySpendSummary` has no connected-user column** but already has `apiKeyId` (§3.3). Per-customer
   billing needs a column, and the existing `apiKeyId` is a tempting shortcut that would quietly encode a
   different product model.

## 2. Goals / non-goals

**Goals**

- A routing policy bound to one connected user, winning over the embedded default.
- Vendor-side management of those bindings.
- Spend attributed per connected user, with an enforceable cap.
- Per-connected-user provider credentials (BYOK), optional and falling back to tenant credentials.
- The external-user-id header made mandatory for embedded callers.

**Non-goals**

- **Rate limiting.** Budgets and caps are in scope; request-rate shaping is not, as in Phase 1.
- **An end-customer console.** Bindings are vendor-owned; ByteChef ships no UI for the vendor's customers.
- **Per-connected-user model catalogs.** A customer gets a policy and optionally credentials, not a
  curated model list. That is a larger product surface and nothing here needs it.
- **Changing automation.** Every automation caller of the gateway behaves exactly as before, including
  after enforcement lands (§6).
- **Retroactive spend attribution.** Rows written before this phase have a null connected user and stay
  that way; no backfill.

## 3. What is already true

**3.1 Phase 1's seam carries the identity.** `ConnectedUserResolver` already turns
`(externalId, environmentId)` into a connected user id, and the facade already resolves it per request.
Phase 2 consumes that id rather than re-deriving it, and adds no second identity path.

**3.2 `ApiKey.type` is a `PlatformType`, and `PlatformType` is `AUTOMATION, EMBEDDED` — nothing else.**
It is a platform-wide constant persisted as an INT ordinal, switched on throughout the codebase, not an
API-key-scoped list. Adding a third value to distinguish gateway keys would ripple into every consumer of
that enum for the sake of one filter.

It is also unnecessary. Embedded API keys already carry `PlatformType.EMBEDDED`, and the gateway's
authentication resolves the `ApiKey` before the request reaches the facade — so the discriminator Phase 1's
⚑3 asked for **already exists**, at exactly the granularity needed. §6 uses it directly.

**3.3 Spend is summarised without a connected user, but with an API key.**
`AiGatewaySpendSummary` carries `apiKeyId`, `projectId`, `model`, `provider`, `periodStart`/`periodEnd`,
token counts and `totalCost`; the repository queries by workspace and period. There is no connected-user
column.

The `apiKeyId` column is a trap worth naming: a vendor *could* provision one API key per customer and get
per-customer attribution for free, and that is a real product model — the competitor's documentation
describes provisioning a key per customer. It is not the model Phase 1 chose. Attributing spend by API key
here would silently make the key the unit of customership while the policy resolution uses the connected
user, and the two would diverge the first time a vendor reused a key across customers. §7 adds an explicit
column instead.

**3.4 `AiGatewayProvider` is already scope-capable and credential-bearing.** It carries an encrypted
`apiKey`, a `baseUrl`, a `type`, and a `@Nullable Long workspaceId`. BYOK is therefore a scoping change on
an existing entity, not a new credential store (§8).

**3.5 Phase 1's null-scope read path exists.** `getDefaultRoutingPolicies()` was built in Phase 1, so the
final link of the resolution chain is already there and Phase 2 only prepends to it.

## 4. Per-connected-user policies

`ai_gateway_routing_policy` gains `connected_user_id BIGINT NULL`, indexed on `connected_user_id`, with
the check constraint Phase 1's §4 specified and could not yet apply:

```
workspace_id IS NULL OR connected_user_id IS NULL
```

**Why `connected_user_id` alone is the key, with no environment column.** The gateway schema has no
`environment` column on any table, but `ConnectedUser` *does* carry one, and
`fetchConnectedUser(externalId, environmentId)` means the same external id in two environments is two
different connected user rows with two different ids. Environment scoping therefore arrives through the
id itself: customer X in DEV and customer X in PROD are distinct connected users and get distinct
policies, for free, with no column and no composite key. An earlier draft specified
`(connected_user_id, environment)` indexes; that was wrong on a column that does not exist, and the
correction is recorded here rather than silently applied.

A policy is workspace-scoped (automation), connected-user-scoped (embedded), or unscoped (the default
tier) — never two at once. This is expressible as a check constraint because both columns live on the same
row, unlike the API-collection name uniqueness that environment promotion had to leave as an app-level
race.

Resolution becomes the full three-step chain Phase 1's §4 described:

1. the enabled policy bound to this connected user;
2. else the embedded default (`defaultRoutingPolicyId` from the Phase 1 settings record);
3. else the system default (`getDefaultRoutingPolicies()`).

A **disabled** policy at any level falls through to the next, as in Phase 1. Falling through is never an
error and never routes to the most expensive model by itself.

## 5. Binding management

Binding and unbinding a policy to a connected user is a vendor-admin operation, exposed on the API facade
that owns authorization, per the repo's API-facade rule — the shared facade stays unguarded because
runtime resolution calls it with no security context.

Three rules the implementation must hold:

- **Ids are resolved within scope, never trusted.** Binding a policy id to a connected user id verifies
  both belong to the authenticated tenant and environment first. A policy id from another tenant is
  indistinguishable from a missing one.
- **One policy per connected user.** The binding is a column on the policy, so a connected user with two
  policies is a data error, not a precedence puzzle. Enforced by a partial unique index on
  `connected_user_id WHERE connected_user_id IS NOT NULL`.
- **Deleting a connected user unbinds rather than cascades.** The policy is a vendor-owned object that may
  be re-bound; it must not vanish because a customer was removed. This follows the project's
  `*BeforeDeleteEventListener` pattern rather than an FK cascade.

## 6. Header enforcement

**Enforce when the authenticated `ApiKey` has `PlatformType.EMBEDDED`.** A gateway request made with an
embedded key and no `X-ByteChef-External-User-Id` header is rejected with 400. Automation keys are
untouched, so no existing caller breaks — which is the property Phase 1's ⚑3 was chosen for.

This is a refinement of that decision's *mechanism*, not a reversal of it. The decision was to use the
existing `ApiKey` type discriminator rather than warn forever; §3.2 shows the discriminator is already at
the right granularity and no new `PlatformType` value is needed. The spec Phase 1 wrote says "mints a
distinct embedded-gateway key type"; that sentence should be read as satisfied by this section.

Two consequences to accept deliberately:

- **A tenant cannot make embedded gateway calls that are not on any customer's behalf.** With an embedded
  key, the header is always required. This is the intended rule — an embedded deployment's traffic is by
  definition on behalf of someone — but it means a vendor wanting tenant-level calls uses an automation
  key for them.
- **It is a behaviour change for anyone already using an embedded key against the gateway after Phase 1.**
  Phase 1 ships permissive, so a window exists in which such calls succeed and then start failing. §13
  handles this with a setting rather than a flag day.

## 7. Per-customer budgets

`ai_gateway_spend_summary` gains `connected_user_id BIGINT NULL`, populated by the rollup from the
resolved identity — **not** derived from `apiKeyId` (§3.3). Rows predating this phase keep a null value.

A cap lives beside the existing soft-budget warning percentage: the Phase 1 settings record gains a
per-connected-user default cap, and a binding may override it. Exceeding the cap rejects the request with
a typed error rather than silently downgrading the model tier — a customer over budget should be told, not
quietly served a worse answer.

The existing `softBudgetWarningPct` keeps its meaning and now evaluates per connected user.

## 8. Per-customer credentials (BYOK)

`ai_gateway_provider` gains `connected_user_id BIGINT NULL` with the same mutual-exclusivity check
constraint as §4, making a provider tenant-scoped, connected-user-scoped, or unscoped.

Resolution mirrors policies: a connected user's own provider for a given provider type wins; otherwise the
tenant's. A customer supplying their own OpenAI key is billed by OpenAI directly and their usage still
appears in spend summaries with `totalCost` computed from catalog pricing, which is now an *estimate* of
their cost rather than a record of ours — §11 records that ambiguity rather than hiding it.

Two constraints inherited from the existing code:

- **Credentials are already encrypted at rest** via `AiGatewayProvider.apiKey`'s `EncryptedStringWrapper`;
  BYOK adds no new encryption surface.
- **The SSRF guard on `baseUrl` must apply identically.** `AiGatewayEmbeddingModelFactoryImpl` and the chat
  model factory both call `AiObservabilityUrlValidator.validateExternalUrl` before building a client. A
  customer-supplied `baseUrl` is strictly more hostile than a tenant-supplied one, so a BYOK path that
  skips that validation is a vulnerability, not an omission.

**Anthropic has no embeddings API** and its branch of the embedding factory throws. A connected user whose
only BYOK provider is Anthropic therefore cannot serve an embedding-based complexity scorer, and must fall
back to tenant credentials for scoring. This interacts with the scorer bake-off: it is an argument for
candidate C or a locally-hosted B over candidate D, and is recorded here so the bake-off's outcome is read
with it in mind.

## 9. Authorization and isolation

- Every by-id operation re-resolves within `(tenantId, environment)`; a connected user id, policy id, or
  provider id from another tenant is indistinguishable from missing.
- A connected user can never enumerate or select another's policy, budget, or credentials. §12 pins each.
- Management operations are vendor-admin only and guarded on the API facade, never the shared facade.
- BYOK credentials are never returned in any read path — the same write-only property connections already
  have.

## 10. Data model summary

| Table | Change |
|---|---|
| `ai_gateway_routing_policy` | `+ connected_user_id BIGINT NULL`, partial unique index on `connected_user_id` where not null, check constraint with `workspace_id` |
| `ai_gateway_provider` | `+ connected_user_id BIGINT NULL`, same check constraint |
| `ai_gateway_spend_summary` | `+ connected_user_id BIGINT NULL`, index for period queries |
| settings (`Property` row) | `+ per-connected-user default cap`, `+ enforcement grace setting` (§13) |

All new columns are nullable with no default, so existing rows are untouched and no backfill runs. No enum
is reordered; no new `PlatformType` value is added (§3.2).

## 11. Error handling and known gaps

- **Embedded key, no header:** 400 (§6).
- **Unknown or disabled external id:** 403, as Phase 1.
- **Over budget:** typed error, not a silent tier downgrade (§7).
- **BYOK provider missing for a needed type:** falls back to tenant credentials; only an error if the
  tenant has none either.
- **Known gap — cost figures under BYOK become estimates.** `totalCost` is computed from catalog pricing,
  which is what *we* would have been charged. Under BYOK the customer is billed directly, so the number is
  an approximation of their spend. Budgets built on it are therefore approximate. Making them exact would
  require reading the customer's own provider invoices, which is out of scope.
- **Known gap — Anthropic BYOK cannot serve embedding-based scoring** (§8).
- **Known gap — distributed EE.** Monolith only, as Phase 1.

## 12. Testing

- Full three-step resolution, and fall-through at each level for a disabled policy.
- The check constraint rejects a policy carrying both scopes — asserted against a real database, since it
  is a constraint and not application code.
- The partial unique index rejects a second policy for the same connected user and environment.
- Isolation: policy, provider and spend ids from another tenant are indistinguishable from missing.
- Deleting a connected user unbinds its policy and leaves the policy row present.
- Enforcement: an embedded-type key without the header is rejected; an automation-type key without the
  header still succeeds. Both asserted, because the second is the promise that nothing broke.
- BYOK: a connected user's provider wins over the tenant's; a customer-supplied `baseUrl` goes through
  `validateExternalUrl`; credentials never appear in a read response.
- Budget: a request over cap is rejected with the typed error and not silently downgraded.

## 13. Rollout

Every column is additive and nullable, so the schema change is safe on its own.

Enforcement (§6) is the one behaviour change, and it is a breaking one for any embedded caller that
adopted the gateway during Phase 1's permissive window. It ships **off**, controlled by a setting on the
embedded settings record, so a vendor turns it on when their callers are ready. The setting defaults to
permissive on upgrade and to enforcing for tenants that configure embedded gateway afterwards — new
deployments get the safe behaviour, existing ones get a migration path rather than a flag day. ⚑3.

## 14. Decisions (⚑ = to be confirmed by the user)

- **⚑1 Enforcement keys off `PlatformType.EMBEDDED` rather than a new enum value (§6).** Phase 1's
  decision stands; only its mechanism changes, because `PlatformType` is a platform-wide two-value enum
  and the discriminator already exists at the right granularity.
- **⚑2 Enforcement ships off, with a per-tenant setting rather than a flag day (§13).** The alternative —
  enforcing immediately — is cleaner but breaks anyone who adopted the gateway during Phase 1.
- **⚑3 Spend attributed by an explicit connected-user column, not by `apiKeyId` (§3.3, §7).** The key-per-
  customer model would work and is what the competitor documents, but mixing it with connected-user policy
  resolution would give two different definitions of "customer" that diverge silently.
- **⚑4 One policy per connected user, enforced by a unique index (§5).** The alternative — many policies
  with precedence — adds a resolution puzzle nothing has asked for.
- **⚑5 Over-budget rejects rather than downgrades (§7).** A customer over budget is told. The alternative
  quietly serves worse answers, which is harder to diagnose and easier to miss.
- **⚑6 BYOK cost figures are estimates and are documented as such (§11).** The alternative is to hide the
  distinction, which would make budgets look more precise than they are.
- **⚑7 Deleting a connected user unbinds rather than deletes the policy (§5).**
- **⚑8 No `environment` column is added to the gateway schema (§4).** Environment scoping is inherited
  through the connected user id, which is already per-environment. Adding one would duplicate a dimension
  the identity already carries and create two sources of truth that can disagree.
