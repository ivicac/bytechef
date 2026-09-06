# Environment-scoped authorization, the remaining families — design

**Status:** **Approved 2026-09-06.** D3 decided **target only**; D4 decided **filter results per
environment**; D5 stays out of the classpath question — the maintainer approved proceeding as framed, so
this work settles the fact that decides its severity (whether `execution-app`'s routes are externally
reachable) and does not change any distributed app's dependencies. D1, D2 and the sequencing stand as
written. Plan at `docs/superpowers/plans/2026-09-06-environment-scoped-authorization-remaining-families.md`.
**Ticket:** 732 follow-on; wants its own ticket
**Date:** 2026-09-06
**Predecessor:** `2026-09-03-environment-scoped-authorization-design.md` — same defect, same mechanism, wrong enumeration. Read its **D1 amended** first; every constraint there binds here.

## 1. Why there is a second spec

The predecessor closed nineteen sites and shipped two coverage tests. Those tests then found that its enumeration had been wrong twice, in two independent ways:

- The scan keyed on the **method signature**, so it could not see an environment carried inside an argument object — `WorkspaceApiKeyFacadeImpl#create`, where a DEVELOPMENT-only member could mint a PRODUCTION API key.
- It then filtered on annotations containing **`'Workspace'`**, so it could not see the same defect on any other resource type.

Neither filter was unreasonable when written; both encoded an incidental feature of the reproduced bug rather than its cause. The tell, in hindsight: the defect states in one sentence — *a gate that does not consult a caller-supplied environment* — and neither filter mentioned either half of it.

**The cause is not the resource type.** `PermissionServiceImpl#hasResourceScope` resolves a resource's environment through a `ResourceEnvironmentResolver`, and when none is registered for that type it falls through to `hasWorkspaceScope(workspaceId, scope)` — which unions the caller's scopes across **every** environment. Exactly three types register a resolver: `Connection`, `ProjectDeployment`, `McpServer`. Every other type takes the fall-through, `'Workspace'` among them.

## 2. What remains

| Family | Sites | Gate | Environment | Mitigated? |
|---|---|---|---|---|
| `'DataTable'` by-id | 27 (19 EDIT, 8 VIEW) | `hasPermission(#dataTableId, 'DataTable', …)` | `long environmentId` | **No.** Nothing in `WorkspaceDataTableFacadeImpl` consults `PrincipalEnvironment` |
| `'Project'` promotion | 4 | `hasPermission(@promotionAuthorizer.projectIdOf…(#sourceId), 'Project', 'DEPLOYMENT_PUSH')` | `Environment targetEnvironment` | **No.** Both bodies write into it |
| `'Workflow'` | ~30 across 8 platform-configuration facades, plus 2 in `WebhookTriggerTestApiFacadeImpl` | `hasPermission(#workflowId, 'Workflow', …)` | `long environmentId` | **Partially, and not for this actor** — see §4 |

Also open, each its own decision rather than part of a family:

- **Site 17**, `ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions` — D5.
- **The unfiltered-listing union leak** — D4.
- **`ApiCollectionFacadeImpl#getApiCollections`'s environment half** — its workspace gate landed in `19b8d00d90e`; `environmentId` is still unchecked. Falls out of D1 with no separate decision.

`WorkspaceDataTableFacadeImpl#getDataTableTags` and `#getTagsByTableId` carry `'Workspace'` gates and take **no** environment. Correctly out of scope; noted so a later reader does not re-flag them.

## 3. D1 — one general expression, not one per family

`hasResourceScope` already does five things before it reaches the environment question, and every one of them must survive:

1. `ResourceMembershipDecider` — a governed principal is answered from its membership **ahead of** the skip and tenant-admin checks.
2. the skip-checks bypass;
3. the tenant-admin bypass;
4. **`isResourceVisible` as a precondition**, not a filter beside the scope check — holding `CONNECTION_EDIT` in a workspace does not entitle a member to a colleague's PRIVATE connection;
5. ownership resolution to a workspace id.

Only step 6 differs. So:

```java
// PermissionService (EE)
boolean hasResourceScopeInEnvironment(Serializable id, String resourceType, String scope, Environment environment)
```

— `hasResourceScope`'s body with the caller's environment substituted for the resolver's lookup. And on `AutomationMethodSecurityExpressionRoot`:

```java
boolean hasResourceScopeInEnvironmentId(Serializable id, String resourceType, String scope, @Nullable Long environmentId)
```

**One expression serves `'DataTable'`, `'Project'` and every future type**, because the defect was never per-type. A per-family expression would be three chances to drop one of the five preconditions — and `isResourceVisible` is the one a bespoke implementation loses most easily, because nothing fails when it is missing.

`hasResourceScope`'s own comment already anticipates this: *"A new Environment-taking overload must do the same"* — consult the decider on its own account, because delegating to `hasResourceScope` would discard the explicit environment. That instruction is binding.

### D1a — the null ordinal keeps the union check

Same rule the predecessor settled (its R1), same reason: this closes **forgery**, and a null names nothing to forge. Denying would 403 ordinary pages for exactly the members per-environment roles protect.

### D1b — do not substitute the principal's environment

Same rule, same reason: substituting would authorise one environment while the body acts on another. The predecessor's D1-amended §2 has the full argument; it applies unchanged.

## 4. D2 — `'Workflow'` re-points to the expression that already exists

`hasWorkflowScopeInEnvironment(String workflowId, String scope, Long environmentId)` was built for exactly this and says so: *"Use this wherever the caller supplies the environment to run in."* The ~32 sites re-point to it. No new mechanism.

**But it substitutes the principal's environment, and D1b says not to.** The two are consistent, and the distinction is worth stating because it is the only place in this work where substitution is right:

- A workflow **run** happens in the principal's own environment whatever the request said, so the gate must authorise the environment the run will actually use. `WorkflowTestApiController` and `AiAgentTestApiController` resolve the same way for the execution that follows.
- A **read** — a test configuration, a node output, a description — returns what the argument names. Substituting there would authorise one environment and read another.

**Open sub-question, and the reason D2 is not simply mechanical:** most of the ~32 sites are reads, not runs. Are they safe under an expression built for runs? The substitution only fires for an api-key principal, so for the ordinary session member this work protects the behaviour is identical either way — but "identical for the actor we care about" is not the same as "correct". Task 1 of any plan from this spec must trace one read site end to end and record the answer. If reads need the non-substituting form, they get `hasResourceScopeInEnvironmentId(#workflowId, 'Workflow', …)` from D1 instead, and `hasWorkflowScopeInEnvironment` stays for the run sites alone.

## 5. D3 — promotion: target only, or source too? **Maintainer decision.**

The predecessor's R3 gated the **target** environment for the MCP promotion handlers and deliberately left the source-side question open. The same question arrives with the four `'Project'` sites, and it should be answered once for all six rather than twice.

A promotion reads from a source environment and writes into a target one. Gating the target is not in question. Whether a caller must **also** hold the scope in the source is:

- **Target only** (status quo, and what shipped for MCP). A caller who can read the source only via today's union can still promote out of it. Consistent across all six handlers, no behaviour change beyond what has landed.
- **Both.** Stricter and arguably right — reading production config to copy it elsewhere is a read of production. But it is a widening beyond "make the argument-supplied case consult the environment", it will deny promotions that work today, and the source environment is a property of the resolved resource rather than an argument, so it needs a different mechanism (`ProjectDeployment` and `McpServer` both **have** environment resolvers, so `hasResourceScope` would already answer the source half if the gate keyed on the deployment rather than the project).

**Recommendation: target only, now.** It is what shipped for MCP, it keeps all six consistent, and the source question deserves its own ticket rather than riding along on a fix for a different defect.

> **DECIDED by the maintainer, 2026-09-06: target only.** All six promotion handlers gate the target
> environment and nothing else. The source-side question is not rejected, only unscheduled — it wants
> its own ticket, and the mechanism it would need is different (both `ProjectDeployment` and
> `McpServer` already have environment resolvers, so keying the gate on the deployment rather than the
> project would answer the source half through `hasResourceScope` without any new expression).

## 6. D4 — the unfiltered-listing union leak. **Maintainer decision.**

With no environment named, the three nullable listings still return rows from every environment in the workspace, including ones the caller holds no role in. D1a keeps that deliberately, because the clients routinely send nothing.

Closing it needs one of:

- **Filter results per environment** in each body — the caller sees the union of environments they actually hold, rather than the workspace's. Correct, and the only option that needs no client change. Costs a per-environment scope lookup in each listing, and each body must learn which environments the caller can reach.
- **Require an environment** — the clients always send one, and a null denies. Simpler server-side, but it is a client contract change across at least four call sites including an AI Hub tool callback, and it removes a legitimate "show me everything I can see" view.

**Recommendation: filter results.** It preserves the view and puts the rule where the data is read, which is the same argument that chose option A over option B in the predecessor. But this is a behaviour change on live listings and is the maintainer's call.

> **DECIDED by the maintainer, 2026-09-06: filter results per environment.** No client contract change;
> the "show me everything I can see" view survives and starts telling the truth about what "I can see"
> means. Sequenced last (§8 step 6) because it is the only item here that changes what a live listing
> returns rather than who may call it, and it should land on top of a tree where every gate is already
> correct.

## 7. D5 — site 17 and `execution-app`. **Maintainer decision, and larger than this spec.**

`ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions` is deferred because `execution-app` depends on neither `server/libs/config/security-config` — which hosts the **only** production `@EnableMethodSecurity` — nor `automation-configuration-service`. Verified from the build file and from the resolved `runtimeClasspath`.

So on that deployment `@PreAuthorize` is not denied; it is **never evaluated**. Re-pointing the annotation would change nothing at runtime, which is why it was not re-pointed.

That is a bigger question than one site: **every `@PreAuthorize` reachable through modules loaded on `execution-app` is equally unenforced there.** What is *not* established is whether those routes are externally reachable or sit behind the api-gateway with its own enforcement. The answer decides whether this is a deployment-topology footnote or a serious gap, and it is not a code question.

**No recommendation.** This spec should not decide a distributed deployment's classpath.

> **The maintainer approved proceeding as framed, 2026-09-06.** So this work does **not** add
> `security-config` or `automation-configuration-service` to `execution-app`, and does not re-point site
> 17. What it does do is settle the fact that decides the severity: **are `execution-app`'s REST routes
> reachable by an ordinary authenticated caller, or do they sit behind the api-gateway with its own
> enforcement?** That is fact-finding, not a deployment change, and until it is answered nobody can say
> whether this is a topology footnote or a live hole. The answer, with evidence, goes back to the
> maintainer with a recommendation attached — see §8 step 7.

## 8. Sequencing

Ordered by exposure, not by size:

1. **D1's mechanism** — the `PermissionService` method, the expression, their tests. Everything else consumes it.
2. **`'DataTable'`** — the only fully unmitigated family, and the largest.
3. **The four `'Project'` promotion sites** — writes.
4. **`'Workflow'`** — partially mitigated, so lowest exposure of the three, and gated on D2's open sub-question.
5. **`ApiCollectionFacadeImpl#getApiCollections`'s environment half** — one site, falls out of D1.
6. **D4** — per-environment result filtering on the three nullable listings. Last because it is the
   only item that changes what a live listing *returns* rather than who may call it, so it should land
   on a tree where every gate is already correct.
7. **D5's fact-finding** — establish whether `execution-app`'s REST routes are externally reachable.
   Read-only; no classpath change, no annotation change. Independent of steps 1-6 and may run at any
   point.

Each step removes its sites from `EnvironmentAwareGateCoverageTest`'s exempt list. **The exempt list shrinking is the acceptance criterion** — a family is done when its entries are gone and the test still passes.

## 9. Non-goals

- **Adding a `ResourceEnvironmentResolver` for these types.** For `Project` it is forbidden — `ResourceEnvironmentResolverProjectGuardTest` fails if anyone tries, because environments belong to a project's deployments. For `DataTable` and `Workflow` the environment is an argument, not a property of the row, so no resolver could supply it. The gate carries it or nothing does.
- **Per-environment roles themselves.** The model exists; this only makes the argument-supplied case consult it.
- **The `@EnableMethodSecurity` audit on `execution-app`** — named in D5, scoped elsewhere.
