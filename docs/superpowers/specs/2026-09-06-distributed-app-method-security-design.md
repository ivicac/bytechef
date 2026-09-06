# Method security in the distributed apps — design

**Status:** **PARKED by the maintainer, 2026-09-06.** Proposed, not approved; D1 and D2 are the maintainer's and the rest depend on them. Do not re-raise as open work — it is a pre-GA defect on a topology nothing deploys yet (§1), and it becomes live only the day that topology ships.

**Correction to D4, from work done after this spec was written.** D4 names three unenforced sites. Ticket 732's `EnvironmentScopeFilter` (commit `5858ad4b6f5`) overtook part of one: `getWorkflowExecutions`' unfiltered branch now calls through to `RemotePermissionServiceClient`, which returns an empty set, so on `execution-app` that branch fails closed and returns an empty page. That holds **only when `environmentId` is null** — a named environment skips the filter. What remains unenforced there is therefore the two by-id methods (`getWorkflowExecution`, `getWorkflowExecutionTaskExecution`, which carry `@PreAuthorize` and nothing else) plus the named-environment listing path. The gate itself was re-pointed in `3cbf6b9cae5`, which changes nothing on `execution-app` and closes the monolith.
**Ticket:** wants its own ticket. Not part of 732's environment-scoped authorization work, which surfaced it.
**Date:** 2026-09-06
**Relates to:** `2026-09-06-environment-scoped-authorization-remaining-families-design.md` §7 (D5), whose fact-finding produced every number here.

## 1. The finding

**No EE distributed app evaluates `@PreAuthorize`.** Not execution-app, and not configuration-app either — which is worth stating plainly, because configuration-app looked like the working example to copy from and is not one.

The chain, each link verified:

- `@EnableMethodSecurity` is declared in exactly **one** production class: `SecurityConfiguration` in `server/libs/config/security-config`.
- Exactly five modules depend on that project: `tenant-multi-security-config`, `security-sso-config`, `tenant-single-security-config`, `platform-user-rest`, and `server-app`.
- **None of the eight EE apps carries any of the five.** Checked individually: configuration-app, execution-app, coordinator-app, worker-app, webhook-app, connection-app, scheduler-app, api-gateway-app — zero each.
- Resolving `configuration-app`'s full `runtimeClasspath` confirms it: the bytechef `security-config` **project appears zero times**.

The Spring library `spring-security-config` *does* reach those apps, but transitively through `platform-security-web-api → mcp-server-security`. That is an incidental dependency of an MCP feature, not security wiring, and it enables nothing on its own — Spring Boot does not auto-activate method security.

So every `@PreAuthorize` annotation on a class loaded by a distributed app is **inert**. Not denied. Not evaluated.

### Why this has not caused an incident

**Nothing deploys the topology.** Verified: `kubernetes/helm/bytechef/` contains zero references to `execution-app` or `api-gateway`, and no docker-compose file mentions execution-app. The distributed-deployment doc is `comingSoon: true`. The monolith `server-app` **does** carry `security-config` and is unaffected.

This is therefore a pre-GA defect, not a live vulnerability. It becomes live the day the topology ships.

## 2. Blast radius

Small today, and that is the argument for fixing it now rather than later.

`execution-app`, resolved runtime classpath, production sources: **7 `@PreAuthorize` sites across 3 modules.** Three are the ones that matter — `ProjectWorkflowExecutionFacadeImpl`'s `getWorkflowExecution`, `getWorkflowExecutionTaskExecution` and `getWorkflowExecutions`. Three more sit on `automation-configuration-api` interfaces whose implementations are not on this app. One is on `RemotePermissionServiceClient` itself.

The other apps have not been counted; §6 step 1 does that. `configuration-app` will be much larger — it carries the real `automation-configuration-service`, `platform-security-service` and the EE configuration service.

## 3. The trap: enabling method security first would break the apps, not secure them

On a distributed app, `PermissionService` resolves to `RemotePermissionServiceClient` (`automation-configuration-remote-client`). **Every one of its methods returns `false`** — `isTenantAdmin`, `hasWorkspaceScope`, `hasResourceScope`, `isResourceOwner`, all of them. Its javadoc records this as deliberate: *"Design choice: fail closed rather than throw."*

That is right for a stub whose gates never fire, and wrong the moment they do. Switch on `@EnableMethodSecurity` today and every gated method denies every caller, including legitimate ones.

**So the two defects currently cancel out.** The annotations are inert, which makes the always-deny stub harmless; the stub is always-deny, which would make the annotations catastrophic. Fixing either alone exposes the other. That is the single most important constraint on any plan from this spec.

## 4. The harder problem: the remote channel authenticates the service, not the caller

The obvious repair — implement `RemotePermissionServiceClient` for real — runs into a boundary the existing remote clients do not cross.

The pattern exists and works. `RemoteProjectServiceClient`, `RemoteProjectDeploymentServiceClient` and `RemoteProjectDeploymentWorkflowServiceClient` all make genuine calls through `LoadBalancedRestClient`, e.g. `.host(CONFIGURATION_APP).path(PROJECT_SERVICE + "/get-project/...")`. So a real `RemotePermissionServiceClient` is precedented, not novel.

But those calls answer questions about **things**. A permission check asks a question about **a person**: *may this caller do this?* And `/remote/**` is authenticated by a shared secret — `RemoteServiceAuthenticationFilter` checks `TenantConstants.INTERNAL_SERVICE_TOKEN`. That authenticates the *calling service*. Nothing carries the end user's identity across the boundary.

Worse, on execution-app there is no end-user identity to carry: it has no security filter chain at all. Its only two filters, `RemoteServiceAuthenticationFilter` and `RemoteMultiTenantFilter`, both skip everything except `/remote/**`, and every one of its controllers lives under `/api/…`.

**So authentication is upstream of authorization here, and neither exists.** That ordering is the spine of D1.

## 5. Decisions

### D1 — where does authentication happen? **Maintainer decision. Everything else depends on it.**

**(a) At the gateway, with propagation.** `api-gateway-app` authenticates, then forwards the principal to downstream apps (a signed header, or a token the app validates). Downstream apps trust the gateway and stay thin. Matches the topology's intent — the gateway is the only front door. But `api-gateway-app` today carries **no** security dependencies and its only code is `DiscoveryClientConfiguration`; it is a bare `lb://` proxy, so this is new work there. And "trust the gateway" is only sound if nothing else can reach the apps directly, which is a network guarantee, not a code one.

**(b) In each app, independently.** Every distributed app gets the same security chain the monolith has — `security-config` plus a tenant strategy. Heavier per app and duplicates session/token handling, but each app is then correct on its own terms and does not depend on a network boundary holding.

**(c) Gateway authenticates and authorizes; apps stay unauthenticated.** Simplest downstream, but the gateway would need resource-level permission knowledge it has no way to obtain — it would have to call back for every request. Listed for completeness; not recommended.

**Recommendation: (a), with (b) as the fallback if the gateway cannot be trusted as a boundary.** (a) matches the deployment shape and puts session handling in one place. The decision is genuinely the maintainer's because it is as much an infrastructure question as a code one.

### D2 — how does a distributed app answer a permission question? **Maintainer decision, and it follows from D1.**

**(a) Implement `RemotePermissionServiceClient` for real**, calling back to configuration-app over `/remote/**` with the propagated principal. Keeps permission logic in one place — configuration-app already owns `PermissionServiceImpl`, the scope cache and the membership tables. Costs a network round trip per gate, which for a `@PreAuthorize` on a hot path is not free; the existing `WORKSPACE_SCOPES_CACHE` is per-app, so caching would need thought.

**(b) Give each app the real `PermissionService`** by carrying `automation-configuration-service` and its data access. No round trip, no identity-propagation problem for the check itself. But it puts workspace/membership DB access into apps that were deliberately kept thin, and the remote-client architecture exists precisely to avoid that.

**Recommendation: (a).** The remote-client pattern is the established one and three clients in that very module already do it. (b) inverts an architectural decision that was made deliberately.

### D3 — the ordering, which is not negotiable

Whatever D1 and D2 decide, the sequence is forced by §3:

1. **Authentication first.** Until a distributed app has an authenticated principal, no permission check can mean anything.
2. **A working `PermissionService` second.** Until it can answer, enabling method security denies everyone.
3. **`@EnableMethodSecurity` last.** It is the switch that makes the other two matter.

Any other order leaves the apps either unprotected or unusable. A plan from this spec must verify at each step that the apps still function, not only that they compile.

### D4 — what to do about `ProjectWorkflowExecutionFacadeImpl`'s three sites meanwhile

They are unenforced today and will stay unenforced until D1–D3 land, which is a substantial piece of work.

`getWorkflowExecutions` already calls `permissionService.hasResourceScope(...)` directly for three of its four branches — those deny, because the stub denies. The **unfiltered branch** and the **two by-id methods** make no such call and are wide open.

**Recommendation: patch those three with the same direct-DI ownership check** the codebase already uses in `IntegrationWorkflowExecutionFacadeImpl#getConnectedUserWorkflowExecution`. That is independent of D1–D3, works whether or not method security is ever enabled there, and closes the only concrete exposure this spec has identified. It is a stopgap and should be labelled one in the code.

## 6. What a plan from this spec must establish first

1. **Per-app `@PreAuthorize` counts**, resolved from each app's `runtimeClasspath` rather than its declared dependencies. `execution-app` is 7; the others are unknown and `configuration-app` will be much larger.
2. **Whether any distributed app has a security filter chain at all.** execution-app does not. The rest are unchecked.
3. **What the gateway currently does with credentials** — it has no security dependencies, so presumably it forwards whatever arrives untouched. Confirm.
4. **Whether `WORKSPACE_SCOPES_CACHE` can be made correct across apps** under D2(a), or whether each app caches independently and for how long.

## 7. Out of scope

- **The monolith.** `server-app` carries `security-config` and evaluates `@PreAuthorize` correctly. Nothing here changes it.
- **The environment-scoped authorization work** (ticket 732). It fixes *which* environment a gate consults; this fixes *whether the gate runs at all* on one deployment shape. Independent, and both are needed.
- **Overlapping gateway route predicates.** `apigateway-app.yml` gives `connection-app`, `configuration-app` and `execution-app` overlapping predicates, and the distributed doc itself admits "route order matters". Found alongside this; its own ticket.
