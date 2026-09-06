# API gateway route predicates — design

**Status:** **PARKED by the maintainer, 2026-09-06.** Proposed, not approved; D1 is the maintainer's and D2/D3 follow from it. Do not re-raise as open work — like the method-security spec it pairs with, this is a pre-GA defect on a topology nothing deploys yet (§2), and it becomes live only the day that topology ships.
**Ticket:** wants its own ticket. Not part of 732, which surfaced it.
**Date:** 2026-09-06
**Relates to:** `2026-09-06-distributed-app-method-security-design.md` (PARKED) — same topology, same pre-GA status, and the two want deciding together if either is picked up.

## 1. The finding

Three of the five gateway routes claim overlapping path predicates, and Spring Cloud Gateway matches routes **in declaration order**. The first match wins and the rest are unreachable.

`server/ee/apps/config-server-app/src/main/resources/config/apps/apigateway-app.yml`:

```yaml
- id: configuration-service   uri: lb://configuration-app   Path=/api/automation/**,/api/embedded/**,/api/platform/**
- id: connection-service      uri: lb://connection-app      Path=/api/automation/**,/api/embedded/**
- id: execution-service       uri: lb://execution-app       Path=/api/automation/**,/api/embedded/**
- id: ai-gateway-service      uri: lb://ai-gateway-app      Path=/api/ai-gateway/**
- id: webhook-service         uri: lb://webhook-app         Path=/webhooks/**
```

`configuration-app` is declared first and its predicate is a superset of the next two. **Every** `/api/automation/**` and `/api/embedded/**` request therefore goes to `configuration-app`; `connection-app` and `execution-app` receive nothing under those prefixes. Only `ai-gateway` and `webhook` have disjoint paths and work as written.

This cannot be fixed by reordering. Reordering only moves which app is starved.

### Two distinct defects, not one

**(a) `connection-service`'s predicate is wrong on its face, independently of the shadowing.** `connection-app`'s `build.gradle.kts` pulls exactly one REST module — `platform-connection-remote-rest`. It serves the internal `/remote/**` surface and **nothing under `/api/**` at all**. Its route would be wrong even if it were declared first: it claims a prefix the app does not implement.

**(b) `execution-service` is shadowed, and the shadowing hides real endpoints.** `execution-app` carries `automation-workflow-execution-rest`, `embedded-workflow-execution-rest` and `platform-workflow-execution-rest`, which do serve `/api/automation/**`. Those endpoints are unreachable through the gateway today.

### Why a path-prefix split does not fall out cleanly

Both apps publish under the **same** OpenAPI base path, `/api/automation/internal`, and both own routes beneath `/workspaces/{id}/`:

| App | Sample paths under `/api/automation/internal` |
|---|---|
| configuration-app | `/workspaces/{id}/projects`, `/workspaces/{id}/connections`, `/workspaces/{id}/project-deployments`, `/workspaces/{id}/project-tags` |
| execution-app | `/workflow-executions/{id}`, `/trigger-form/{id}`, **`/workspaces/{id}/workflow-executions`** |

The last row is the problem. `/api/automation/internal/workspaces/{id}/…` is served by **both** apps, discriminated only by the segment *after* the path variable. So the routing key is the resource segment, not the API-family prefix, and one of those resource segments is nested behind a variable both apps share.

## 2. Why this has not caused an incident

**Nothing deploys this topology.** `kubernetes/helm/bytechef/` contains no reference to `api-gateway` or `execution-app`, no compose file mentions them, and the distributed-deployment doc is `comingSoon: true`. The monolith `server-app` does not use the gateway. This is a pre-GA defect that becomes live the day the topology ships — the same standing as the parked method-security spec, which is why the two belong to one decision.

## 3. Decisions

### D1 — what is the routing key? **Maintainer decision.**

**(a) Enumerate resource segments per app.** Replace the family prefixes with explicit per-resource predicates — `configuration-app` keeps `/api/automation/internal/workspaces/{id}/projects/**` and its siblings; `execution-app` takes `/api/automation/internal/workflow-executions/**`, `/trigger-form/**` and `/workspaces/{id}/workflow-executions`. Precise, and it makes the gateway config an accurate map of who serves what. But it is a list that must be maintained in lockstep with every new endpoint, in a file that lives in a different app from the code it describes — and the failure mode of forgetting is a 404 at the gateway, discovered at runtime.

**(b) Give each app a distinct path prefix and rewrite at the gateway.** e.g. `/api/automation/execution/**` → `execution-app`, stripped back to the app's own base path by a `RewritePath` filter. The predicate then never needs updating as endpoints are added. Costs a client-visible URL change, or a rewrite that has to stay consistent with the OpenAPI `servers:` entries the generated clients use.

**(c) Drop the static routes and rely on `discovery.locator`.** It is already `enabled: true` in this file. Routes become `/{serviceId}/**`, which is uniform and self-maintaining, but exposes service ids in the public URL and changes every client path.

**No recommendation offered.** (a) is least disruptive to clients and most laborious to maintain; (b) is the reverse. That trade — client URL stability against config that rots silently — is a product decision, not a technical one.

### D2 — what happens to `connection-service`? **Follows from D1.**

Its route should be **deleted**, not narrowed: the app serves no `/api/**` surface at all (§1a). If `connection-app` is later given a public surface, the route returns under whatever D1 decides. Deleting it is safe today under any D1 option and is the one change with no trade-off.

### D3 — a test that fails when this rots again

Whatever D1 decides, the config is a list of claims about which app serves which path, and nothing checks those claims. A test should assert that every route predicate corresponds to a path some module on that app's classpath actually publishes, driven from the `openapi.yaml` `servers:` + `paths:` of each app's REST modules. Without it, (a) rots on the next endpoint added and (b) rots on the next base-path change — and both rot silently, because the symptom is a 404 in a deployment nobody runs yet.

## 4. Scope note

This spec covers routing only. It deliberately does **not** address authentication or authorization at the gateway — that is D1 of `2026-09-06-distributed-app-method-security-design.md`, and the two decisions interact: option (b) above introduces a gateway-owned URL space, which is also where a gateway-authenticating design would put its filter.
