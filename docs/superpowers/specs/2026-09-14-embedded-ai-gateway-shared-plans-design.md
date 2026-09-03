# Embedded AI Gateway — Shared Plans Design (routing policies assigned, credentials owned)

- **Date:** 2026-09-14
- **Branch:** `claude/embedded-ai-gateway-scope-aware`
- **Status:** Draft — awaiting review.
- **Ticket:** none filed yet.
- **Related:** `2026-08-25-embedded-ai-gateway-design.md` (phase 1 — its §1 premise),
  `2026-08-25-embedded-ai-gateway-phase-2-design.md` (phase 2 — its §5 and ⚑4 are what this revises),
  `2026-09-03-embedded-ai-gateway-remaining-work.md` (register — this resolves §2.1, §2.2 and §2.3, and
  gives §1.2's per-customer cap a home).

## 1. Summary

Phase 1 sold the embedded gateway on "different customers on different plans routed to different model
tiers". Phase 2 built the opposite. The binding is a column on the routing policy, so a policy belongs to
exactly one customer, and the binding facade refuses a policy another customer already holds. A vendor with
a hundred customers on a "premium" plan needs a hundred copies of that policy, each with its own model
deployments, and a hundred edits to change what premium means. The register's §2.1 — two customers cannot
both hold a policy called `premium` — is that same fact seen from the naming side.

This spec separates two relationships that phase 2 modelled as one:

- **A routing policy is a plan, and customers are assigned to it.** Many customers, one policy. The binding
  moves off the policy row into a per-customer settings row, which also carries a per-customer spend cap.
- **Provider credentials are owned.** A customer's own API key is theirs alone and must never become usable
  by anyone else. That relationship stays on the provider row, and its management gains the checks policy
  binding already had.

Writing it turned up one fact that changes what the resolution chain must allow (§3.5): the embedded
endpoint accepts end-user JWTs, not only the vendor's secret key, and the authenticated principal does not
record which one was used.

## 2. Goals / non-goals

**Goals**

- One routing policy assignable to any number of customers, with policy names left unique per tenant.
- A per-customer spend cap that overrides the embedded default cap.
- Provider credentials that belong to one customer for their whole life, with management that verifies the
  customer, refuses conflicts with a domain error, and never releases a key to tenant-wide use.
- An embedded caller unable to route itself onto a plan it is not assigned.

**Non-goals**

- **Management surfaces.** No REST or GraphQL here; the register's §1.2 decides those. This spec defines the
  service and API-facade layer they will call.
- **Per-request plan overrides for embedded traffic.** Supporting them safely needs the principal to record
  whether a vendor secret key or an end-user JWT authenticated the request (§3.5). Deferred, not rejected.
- **Plan tiers, plan catalogs, or plan metadata.** A plan is an existing routing policy; nothing new names it.
- **Changing automation.** Workspace-scoped and tenant-level policy behaviour for automation callers is
  untouched.
- **Distributed EE.** The feature stays monolith-only, as phase 2 §11 declared.

## 3. What is already true

**3.1 The binding is a column on the policy, limited to one policy per customer.**
`ai_gateway_routing_policy.connected_user_id`, with the partial unique index
`uk_ai_gateway_routing_policy_connected_user_id` and the check constraint
`ck_ai_gateway_routing_policy_workspace_connected_user_not_both`. `ConnectedUserAiGatewayRoutingPolicyFacadeImpl.bind`
rejects a policy already bound to a different customer and silently replaces the customer's own previous
binding.

**3.2 Phase 2 never weighed shared plans.** Its ⚑4 rejected *many policies per customer* ("a resolution
puzzle nothing has asked for"). *Many customers per policy* does not appear in either spec.

**3.3 The schema is unreleased.** The gateway's Liquibase changelog is in neither `v0.31.4` nor
`origin/master`, and the phase-2 commit that added the connected-user columns (`f6c40057ee9`) is not an
ancestor of `origin/master`. The init changelog may be edited in place. Local dev databases that already ran
it need `scripts/dev/sync-local-schema-after-collapse.sh`.

**3.4 Credential binding has none of policy binding's checks.** `AiGatewayProviderServiceImpl.updateConnectedUserId`
is a service setter with one guard (a workspace-scoped provider is refused). It does not verify the customer,
does not refuse a provider held by another customer, surfaces a second provider of the same type as an
unmapped `uk_ai_gateway_provider_connected_user_id_type` violation, and accepts `null` — which turns a
customer's private key into a tenant provider every customer can route through.

**3.5 End users can call the gateway directly.** `EmbeddedApiKeySecurityConfigurer` matches
`^/api/embedded/v[0-9]+/.+`, and `EmbeddedApiKeyAuthenticationConverter` accepts either the vendor's secret key
(external user id from the path) or a signed JWT whose subject is the external user id — the token a vendor
hands an end user's browser. The authenticated `EmbeddedApiKeyAuthenticationToken` carries neither the secret
key nor a marker of which credential was used. Today any embedded caller can therefore name any routing
policy in the request body that is not bound to another customer (register §2.3). Under shared plans that
would be a self-service plan upgrade.

**3.6 The spend cap is one value for every customer.** `AiGatewayEmbeddedSettings.defaultConnectedUserBudgetCap`,
per environment. No per-customer value exists anywhere.

**3.7 Deleting a policy ignores what references it.** `AiGatewayRoutingPolicyServiceImpl.delete` removes the
policy's deployments, then the row.

## 4. The model

| | routing policy (plan) | provider (credential) |
|---|---|---|
| relationship | assigned, many customers → one policy | owned, one customer → its providers |
| where it lives | `ai_gateway_connected_user_settings.routing_policy_id` | `ai_gateway_provider.connected_user_id` |
| reassigning | replaces the customer's previous plan | not a thing: delete and create |
| releasing | clears the assignment; the policy is untouched | never: a credential cannot become tenant-wide |
| customer deleted | settings row deleted | providers disabled, `connected_user_id` kept |
| policy/provider deleted while in use | refused with a domain error | the customer falls back to the tenant provider |

The two columns differ on purpose. Register §2.2 asked for "one semantic for both"; the answer is that
they model different relationships, and forcing one semantic onto both is how phase 2 got plans wrong.

## 5. Schema

Edited in place in `00000000000001_ai_gateway_init.xml` (§3.3).

**Removed from `ai_gateway_routing_policy`:** the `connected_user_id` column, the check constraint
`ck_ai_gateway_routing_policy_workspace_connected_user_not_both`, and the index
`uk_ai_gateway_routing_policy_connected_user_id`. Name stays `unique="true"` — per tenant, which is correct
for a plan (register §2.1: no change).

**Added:**

```xml
<createTable tableName="ai_gateway_connected_user_settings">
    <column name="id" type="BIGINT" autoIncrement="true" startWith="1050">
        <constraints primaryKey="true" nullable="false"/>
    </column>
    <column name="connected_user_id" type="BIGINT">
        <constraints nullable="false" unique="true"
                     uniqueConstraintName="uk_ai_gateway_connected_user_settings_connected_user_id"/>
    </column>
    <column name="routing_policy_id" type="BIGINT">
        <constraints foreignKeyName="fk_ai_gateway_connected_user_settings_policy"
                     references="ai_gateway_routing_policy(id)"/>
    </column>
    <column name="budget_cap" type="NUMERIC(19, 6)"/>
    <column name="created_date" type="TIMESTAMP"><constraints nullable="false"/></column>
    <column name="last_modified_date" type="TIMESTAMP"><constraints nullable="false"/></column>
    <column name="version" type="BIGINT"><constraints nullable="false"/></column>
</createTable>

<createIndex tableName="ai_gateway_connected_user_settings"
             indexName="idx_ai_gateway_connected_user_settings_routing_policy_id">
    <column name="routing_policy_id"/>
</createIndex>
```

- **No foreign key to `connected_user`.** The platform tier does not reference the embedded tier's tables
  (the same reasoning `ConnectedUserBeforeDeleteEventListener` documents); deletion is handled by that
  listener (§9).
- **No `environment` column.** The connected user id is already per environment (phase 2 ⚑8).
- **No `workspace_id` column.** The repo rule gives new platform entities a nullable `workspace_id`; this one
  is embedded-only and a workspace can never own it, so the column would be permanently null. A deliberate
  exception, recorded here and in ⚑5.
- **The foreign key to the policy is a backstop, not the behaviour.** §9 refuses deleting an assigned policy
  with a domain error before the constraint would fire.
- `NUMERIC(19, 6)` matches the six-decimal scale `AiGatewayCostCalculatorImpl` produces. The cap is USD, as
  every gateway money column is today.

## 6. Components

**Platform tier (`platform-ai-gateway`)**

- `AiGatewayConnectedUserSettings` — Spring Data JDBC entity for the table above, in `-api`'s `domain`.
- `AiGatewayConnectedUserSettingsRepository` — `findByConnectedUserId`, `countByRoutingPolicyId`,
  `deleteByConnectedUserId`.
- `AiGatewayConnectedUserSettingsService` / `Impl` — unguarded, as every gateway service is:
  `fetchByConnectedUserId`, `assignRoutingPolicy(connectedUserId, routingPolicyId)`,
  `unassignRoutingPolicy(connectedUserId)`, `updateBudgetCap(connectedUserId, @Nullable BigDecimal)`,
  `countByRoutingPolicyId`, `deleteByConnectedUserId`. Assigning or setting a cap creates the row when absent.
  A row whose policy and cap are both null is kept; an empty row is harmless and avoids delete-then-recreate
  races between two admin calls.
- `AiGatewayRoutingPolicy` loses `connectedUserId`; `AiGatewayRoutingPolicyService` loses
  `fetchRoutingPolicyByConnectedUserId` and `updateConnectedUserId`; the repository loses
  `findByConnectedUserId`.
- `AiGatewayProviderService` loses `updateConnectedUserId` and gains
  `createConnectedUserProvider(AiGatewayProvider, long connectedUserId)` and `disableByConnectedUserId(long)`.
  A credential is bound at creation and never rebound.

**Embedded tier (`embedded-connected-user`)**

`ConnectedUserAiGatewayRoutingPolicyFacade` becomes `ConnectedUserAiGatewayFacade`, the API facade that owns
authorization (`@PreAuthorize(ADMIN)` on every method, per the repo's API-facade rule):

| method | rules |
|---|---|
| `assignRoutingPolicy(connectedUserId, routingPolicyId)` | customer verified within scope; policy must exist and be tenant-level (null `workspace_id`); replaces any previous assignment |
| `unassignRoutingPolicy(connectedUserId)` | idempotent |
| `updateBudgetCap(connectedUserId, cap)` | customer verified; `null` clears to the default; negative refused |
| `createProvider(connectedUserId, provider)` | customer verified; provider built with a null `workspace_id`; a second provider of the same type — enabled or not, since `uk_ai_gateway_provider_connected_user_id_type` ignores `enabled` — refused with a domain error naming the existing one and saying to delete it first |
| `deleteProvider(connectedUserId, providerId)` | provider must belong to this customer; a foreign or missing id fails identically |

"Customer verified within scope" keeps phase 2's rule: `ConnectedUserService.fetchConnectedUser(id)` under the
caller's tenant schema, so a foreign id is indistinguishable from a missing one.

## 7. Resolution

The chain keeps its four levels — request-specified → customer → model default → embedded default — and its
existing rule that a disabled policy falls through to the next level.

- **Level 2 (customer)** reads `AiGatewayConnectedUserSettingsService.fetchByConnectedUserId(...)` instead of
  a policy row's column. The same settings row feeds the budget cap (§8), so the facade fetches it once per
  request, as it already fetches the embedded settings row once.
- **Level 1 (request-specified), embedded traffic only:** the named policy must be the customer's assigned
  plan. Any other name — another plan, a workspace-scoped policy, a policy that does not exist — fails with
  the existing `Routing policy not found: <name>` message, so the caller cannot enumerate plans. Automation
  traffic is unchanged. This closes register §2.3 and the upgrade path in §3.5.
- **Levels 3 and 4, embedded traffic only:** a model or embedded default pointing at a workspace-scoped
  policy falls back to direct routing with a WARN, as a deleted default does today. The old check — a default
  bound to *another customer* — has nothing left to check and is removed.

`requireRoutingPolicyUsableByConnectedUser` is rewritten for the rule above; `applyResolvedRoutingPolicy`'s
cross-customer branch becomes a workspace-scope branch.

## 8. Spend cap

`checkConnectedUserBudget` uses the customer's `budget_cap` when set, otherwise the embedded default cap,
otherwise no cap. The window (calendar month, UTC), the spend query and the rejection
(`BudgetExceededException`, before routing) are unchanged.

## 9. Deletion

- **Customer deleted.** `ConnectedUserBeforeDeleteEventListener` deletes the settings row and calls
  `disableByConnectedUserId`. Providers are disabled, not unbound: clearing `connected_user_id` would hand the
  customer's key to every other customer. An admin deletes them explicitly.
- **Assigned policy deleted.** `AiGatewayRoutingPolicyServiceImpl.delete` refuses with
  `IllegalArgumentException("Routing policy <id> is assigned to <n> connected users; reassign them first")`
  when `countByRoutingPolicyId > 0`. Silently unassigning would move bystanders onto the embedded default
  with no signal — the same reasoning phase 2's `bind` gave for refusing a policy held by someone else.
- **Customer provider deleted.** The customer falls back to the tenant provider of that type, as a customer
  who never had one already does.

## 10. Testing

- **Unit:** the settings service; the facade's rules table (§6), including identical failures for foreign and
  missing ids; the resolution rules (§7), including a JWT-shaped embedded caller naming an unassigned plan;
  cap precedence (§8); both deletion paths (§9).
- **Integration (Testcontainers):** extend `EmbeddedAiGatewayPhase2IntTest` so two customers assigned one
  plan both route through it, one customer's cap override rejects while the other's default does not, and
  deleting an assigned policy is refused. `ConnectedUserAiGatewayRoutingPolicyBindingIntTest` is rewritten
  against the new facade.
- **Existing tests to rewrite:** about 47 references across 9 test files use policy binding today
  (`ConnectedUserAiGatewayRoutingPolicyFacadeTest`, `…AuthorizationTest`, `AiGatewayFacadeTest`,
  `AiGatewayRoutingPolicyServiceTest`, `EmbeddedAiGatewayPhase2IntTest`,
  `ConnectedUserAiGatewayRoutingPolicyBindingIntTest`, `ConnectedUserBeforeDeleteEventListenerTest`,
  `AiGatewayProviderServiceTest`, `AiGatewayIntTestConfiguration`).

## 11. Decisions

- **⚑1 Plans are assigned, credentials are owned (§4).** The alternative — one semantic for both, as register
  §2.2 asked — is how phase 2 made a plan a per-customer copy.
- **⚑2 A per-customer settings row, not a policy column or a relation table (§5).** A customer has at most one
  plan and one cap, so the relationship is many-to-one with the customer as owner. A relation table would model
  a many-to-many that does not exist.
- **⚑3 Embedded callers may name only their assigned plan (§7).** The alternative — any tenant-level plan —
  is a self-service upgrade for any end user holding a JWT (§3.5). Per-request overrides return when the
  principal records its credential kind.
- **⚑4 Deleting an assigned plan is refused, not cascaded (§9).**
- **⚑5 The settings table carries no `workspace_id` (§5).** An exception to the repo rule, because no
  workspace can own an embedded customer's settings.
- **⚑6 Customer deletion disables credentials rather than unbinding or deleting them (§9).** Unbinding leaks
  the key tenant-wide; deleting automatically destroys a credential an admin might need to audit or rotate.
- **⚑7 Policy names stay unique per tenant (§5).** Register §2.1 resolves to "no change": with shared plans a
  name identifies a plan, and a plan is tenant-wide.

## 12. Left in the register

§1.2 (management surfaces) now has a complete service and facade layer to expose. §2.4 (BYOK scope filter on
`getEnabledProviders`), §2.5 (response cache tenant dimension), and §3's gaps — including embedded requests
writing no traces — are untouched by this spec.
