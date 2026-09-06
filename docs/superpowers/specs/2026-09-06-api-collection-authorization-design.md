# API collection authorization — design

**Status:** proposed, not approved. D1 is the maintainer's; D2–D4 follow from it.
**Ticket:** wants its own ticket. Not part of 732, whose environment sweep surfaced it.
**Date:** 2026-09-06
**Relates to:** `2026-09-03-environment-scoped-authorization-design.md` and `2026-09-06-environment-scoped-authorization-remaining-families-design.md` — this facade's one gated listing came from that sweep, and the sweep is how the rest was found.

## 1. The finding

`ApiCollectionFacadeImpl` (`server/ee/libs/automation/automation-api-platform/…/facade/`) carries `@PreAuthorize` on **2 of its 11 public methods**. Its three REST controllers carry **none at all**.

| Method | Gate today |
|---|---|
| `getApiCollections(workspaceId, environmentId, projectId, tagId)` | `hasWorkspaceScopeInEnvironmentId(#workspaceId, 'WORKSPACE_VIEW', #environmentId)` |
| `getApiCollectionTags(workspaceId)` | `hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_VIEW')` |
| `createApiCollection(ApiCollectionDTO)` | **none** |
| `createApiCollectionEndpoint(ApiCollectionEndpointDTO)` | **none** |
| `updateApiCollection(ApiCollectionDTO)` | **none** |
| `updateApiCollectionEndpoint(ApiCollectionEndpointDTO)` | **none** |
| `updateApiCollectionTags(long id, List<Tag>)` | **none** |
| `deleteApiCollection(long id)` | **none** |
| `getApiCollection(long id)` | **none** |
| `getOpenApiSpecification(long id)` | **none** |
| `getWorkspaceProjects(long workspaceId)` | **none** |

Controllers: `ApiCollectionApiController` (5 endpoints), `ApiCollectionEndpointApiController` (3), `ApiCollectionTagApiController` (2) — zero `@PreAuthorize` between them, so nothing above the facade compensates.

**Every mutation on the surface is ungated, and so is every by-id read.** A by-id method takes a bare `long` and resolves the row itself, so any authenticated user of any workspace can read, modify or delete any API collection in the tenant by id. That is a cross-workspace IDOR across create/read/update/delete, not a single missing gate.

`getOpenApiSpecification(long)` deserves separate mention: it returns the collection's full published API description, which is exactly the document an attacker would want first.

### Why the two gated methods are gated and the rest are not

Not a deliberate shared-facade exemption. CLAUDE.md's "API facade vs shared facade" convention explains an unguarded facade when a guarded API facade sits above it — **there is no `ApiCollectionApiFacade`**; grep finds none. One facade serves both the REST controllers and the AI tool callbacks.

The two gates are simply the two this year's environment sweep happened to touch: `getApiCollections` had no gate at all until commit `19b8d00d90e` and became environment-aware in `7de79e0843d`. The sweep keyed on methods taking an environment argument, and only that one does. The rest were never in its scan's scope.

**This is the generalisable lesson, and it is why the finding was missed twice.** A coverage scan that keys on a *parameter shape* cannot see a method that lacks the parameter. `EnvironmentAwareGateCoverageTest` looks for environment-taking methods whose gate is environment-unaware; a method with no gate and no environment argument is invisible to it — as are all nine here. Neither can it see a controller. §5 proposes the complementary check.

## 2. What makes this tractable: every collection has a backing deployment

`ApiCollection` holds a `projectDeploymentId`, and `ApiCollectionPromotionHandler` documents the arrangement: each collection is backed by a synthetic `__API_COLLECTION__` `ProjectDeployment`. `ProjectDeployment` is one of the three types that register a `ResourceEnvironmentResolver`.

That matters because a by-id gate routed through the deployment is **environment-aware for free** — `hasResourceScope` resolves the environment off the deployment row itself, with no new resolver and no environment argument to thread. It is the difference between this being a large change and a small one.

Note what is absent today: no `ResourceOwnershipResolver`, `ResourceVisibilityProvider` or `ResourceEnvironmentResolver` is registered for `"ApiCollection"`. The only occurrences of that string in the codebase are OpenAPI schema names.

## 3. The template already in the tree

`WorkspaceMcpServerFacadeImpl` is the closest analogue — workspace-scoped, environment-bearing, resolver-backed — and it is fully gated:

```java
getWorkspaceMcpServers   → hasPermission(#workspaceId, 'Workspace', 'MCP_VIEW')
createWorkspaceMcpServer → hasWorkspaceScopeInEnvironment(#workspaceId, 'MCP_CREATE', #environment)
updateWorkspaceMcpServer → hasPermission(#mcpServerId, 'McpServer', 'MCP_EDIT')
deleteWorkspaceMcpServer → hasPermission(#mcpServerId, 'McpServer', 'MCP_EDIT')
```

Three shapes: workspace-scoped listings, an environment-aware create, and by-id mutations through a resolver-backed resource type. API collections need the same three.

## 4. Decisions

### D1 — its own scope family, or reuse? **Maintainer decision. D2–D4 follow.**

Thirteen `*PermissionScope` families exist (`Mcp`, `Project`, `Deployment`, `Connection`, `DataTable`, `Workflow`, `Agent`, `KnowledgeBase`, `ApiKey`, `Execution`, `Variable`, `AiGateway`, `Workspace`). **None covers API Platform.**

**(a) A new `ApiPlatformPermissionScope` — `API_PLATFORM_VIEW`/`_CREATE`/`_EDIT`** with an `ApiPlatformPermissionScopeProvider` mapping VIEW→VIEWER and CREATE/EDIT→EDITOR, mirroring `McpPermissionScopeProvider` exactly. Consistent with every sibling feature, and it lets an operator grant API-platform access without granting the rest of the workspace. Costs a new scope family, which every existing custom role must then be considered against — an existing role gains nothing automatically.

**(b) Reuse `WORKSPACE_VIEW` / a workspace-level scope**, extending what the one gated listing already does. Nothing new to define and no role migration. But it makes API collections ungrantable independently, and it is a coarser grant than any comparable feature has — publishing an API is at least as consequential as creating an MCP server, which has its own family.

**(c) Reuse `DeploymentPermissionScope`**, on the argument that a collection *is* a deployment underneath (§2). Honest about the data model, but the synthetic deployment is an implementation detail the UI never shows; a user granted deployment rights would silently gain API-publishing rights.

**Recommendation: (a).** It is what every comparable feature does, and (c)'s coupling to a deliberately-hidden implementation detail is the kind that surprises people later. The role-migration cost is real and is the reason this is a decision rather than an assumption.

### D2 — where do the gates live? **Follows from D1.**

**The agent-tool question is settled, and it was the largest risk here.** This facade is also called by `CreateApiCollectionToolCallback` and `ListApiCollectionsToolCallback`, so facade gates apply to agent tool calls too — which breaks those tools if they run without a principal. They do not: `ApiCollectionAgentConfiguration#buildToolCallbacks` wraps every callback from `ApiCollectionToolCallbacksFactory` in `RehydrateContextToolCallback`, which restores the security context a worker thread would otherwise lack. Agent tools therefore run as a real principal and are gated like any other caller.

This also retires a live question: the `getApiCollections` gate added in `19b8d00d90e` already put `ListApiCollectionsToolCallback` behind a gate, and that was load-bearing and unverified until now. It is fine.

**(a) On the existing facade.** Matches `WorkspaceMcpServerFacadeImpl` and every other gated facade in this tree, and the finding above removes its one objection. A gated shared facade does mean an agent tool is refused work its invoking user could not do themselves — which is the intended behaviour, not a limitation.

**(b) Introduce an `ApiCollectionApiFacade`** per CLAUDE.md's convention: controllers through the guarded API facade, agent tools on the unguarded shared one. Structurally the documented pattern, but it costs a new interface, an implementation and a re-point of three controllers — and it would deliberately leave the agent-tool path ungated, which is a step backwards now that we know rehydration works. Note also the convention's own warning: wiring a controller to the shared facade compiles fine and silently removes the check.

**Recommendation: (a).** The convention in CLAUDE.md exists for facades whose runtime callers have no security context. These do have one, so the exemption that motivates (b) does not apply.

### D3 — the per-method assignment, given D1 and D2

Proposed, using (a)'s names:

| Method | Proposed gate | Note |
|---|---|---|
| `getApiCollections` | `hasWorkspaceScopeInEnvironmentId(#workspaceId, 'API_PLATFORM_VIEW', #environmentId)` | keeps its environment-awareness |
| `getApiCollectionTags`, `getWorkspaceProjects` | `hasPermission(#workspaceId, 'Workspace', 'API_PLATFORM_VIEW')` | workspace-keyed listings |
| `getApiCollection`, `getOpenApiSpecification` | `hasPermission(#id, 'ApiCollection', 'API_PLATFORM_VIEW')` | needs D4 |
| `createApiCollection` | `hasWorkspaceScopeInEnvironment(...'API_PLATFORM_CREATE'...)` | the DTO carries the workspace and environment; verify SpEL can reach both off the argument object |
| `updateApiCollection`, `updateApiCollectionTags`, `deleteApiCollection` | `hasPermission(#id, 'ApiCollection', 'API_PLATFORM_EDIT')` | needs D4 |
| `createApiCollectionEndpoint`, `updateApiCollectionEndpoint` | `hasPermission(#…apiCollectionId, 'ApiCollection', 'API_PLATFORM_EDIT')` | keyed on the parent collection |

### D4 — how does `'ApiCollection'` resolve?

The by-id rows above need a `ResourceOwnershipResolver` for `"ApiCollection"`, and none exists (§2).

**Recommendation: register one that resolves through the backing `ProjectDeployment`** — collection id → `projectDeploymentId` → the deployment's owning workspace. Reuses the resolver chain already registered for `ProjectDeployment`, and because that type also has a `ResourceEnvironmentResolver`, every by-id gate above becomes environment-aware with no further work and no environment argument. This is the payoff from §2 and the reason not to invent a parallel ownership path.

## 5. The coverage gap that hid this

`EnvironmentAwareGateCoverageTest` scans for methods that *take an environment* and gate without it. Nine ungated methods here take no environment, so the scan is blind to them by construction — and it does not look at controllers at all.

The complement should key on **reachability** rather than on a parameter shape. But the naive form of that — "every public method on a `*FacadeImpl` reachable from a controller carries `@PreAuthorize`" — does not survive contact with this codebase. A probe of it was run against the whole tree before writing this section, and it produced 37 controller/facade pairs of which **every single one spot-checked was a false positive**. Six distinct legitimate patterns defeat it — the sixth found only by re-running the probe after this section was first drafted — and a test that needs an exemption register larger than its findings is noise nobody reads:

| # | Pattern | Example |
|---|---|---|
| 1 | Authorization **in the method body**, not by annotation | `AiSkillApiFacadeImpl` — `checkOwnerOrAdmin(id)` in every method; `KnowledgeBaseDocumentApiFacadeImpl` — `permissionService.hasResourceRole(...)` |
| 2 | **Class-level** `@PreAuthorize` | `AutomationWorkflowProjectAdminFacadeImpl` — `@PreAuthorize("isTenantAdmin()")` on the type, so the controller deliberately carries none |
| 3 | Shared facade with a **guarded twin** | `ConnectionFacade` is unguarded by design; `WorkspaceConnectionFacadeImpl` is the guarded one — CLAUDE.md's own convention |
| 4 | Deliberately **public** reads | `ProjectFacadeImpl#getProjectTemplate`, `#getPreBuiltProjectTemplates`, `#getSharedProject` |
| 5 | The controller **does not call the ungated methods** | `AiProviderApiController` calls only the four admin-gated methods; `getApiKey` and the default-model accessors are internal, reachable only from model resolution |
| 6 | In-body authorization under a **helper name the scan does not know** | `AssetFileFacadeImpl` — `checkMembership(workspaceId)` / `checkMembershipOfOwner(id)` in every method |

Pattern 5 is the one that matters most, because it is not an exemption at all — it is the scan asking the wrong question. Pairing a controller with a *facade* over-reports; the check must pair a controller with the *facade methods it actually invokes*.

**Pattern 6 is the one that limits what this check can ever be.** It was found by re-running the probe after this spec was first written and noticing that its top entry, `AssetFileFacade` at 18 of 18 ungated, has a spec of its own reading *"Status: implemented"*. Both were true: the split that spec describes did happen, neither half carries an annotation, and every method opens with `checkMembership(...)`. The probe already subtracted in-body authorization — but by matching a **list of known helper names**, assembled from the two facades that had been inspected by then. `checkMembership` was not on it.

That is not a missing entry to add. Every facade family is free to name its own helper, so a name list only ever covers the patterns someone already looked at, and the ones it misses are reported as findings — the most expensive possible failure for a security check, because a false alarm on a correctly-guarded facade is what teaches people to stop reading the output.

**Proposed shape, corrected for all six:** for each controller method, resolve the facade methods it calls, and assert each carries an authorization signal — an annotation on the method, an annotation on its class, or an in-body check. Exempt only pattern 4, a short list and a genuine product decision per entry.

**Detect the in-body case structurally, not by name.** Options worth weighing when this is built: a method whose body can reach a call that throws `AccessDeniedException`; a call into `PermissionService` or the SpEL expression root by *type*, resolved from imports rather than from an identifier; or, if neither proves tractable, an explicit per-facade allowlist of authorization helpers, which is honest about being a list and fails loudly when a new one appears rather than silently reporting the facade as open. What must not ship is a substring list presented as detection.

**What the probe says about scope, which is the reason to trust this spec's framing.** After the six patterns are subtracted, `ApiCollectionFacade` is the residue: its controllers *do* call its ungated methods, no guarded twin exists, no class-level annotation is present, and no method authorizes in-body under any name. It is a real and, on the evidence available, isolated finding — not the first of thirty. Six patterns, five of them found by checking a candidate rather than by reasoning about the scan, is also the measure of how far a probe of this kind is from being a test.

Note also the existing register's documented limitation, which any new one inherits: nothing detects an exemption whose site is no longer unaware, so entries must be removed by hand when fixed.

## 6. Scope note

Automation API collections only. The embedded API-platform surface, if any, is not examined here. Nothing in this spec changes the environment sweep's completed work; it extends the same gating model to a facade that sweep could not see.
