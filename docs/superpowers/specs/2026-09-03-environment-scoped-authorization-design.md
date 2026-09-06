# Environment-scoped authorization — design

**Status:** proposed, not approved
**Ticket:** 732 follow-on; wants its own ticket
**Date:** 2026-09-03

## Problem

An operation that takes an `environmentId` from the caller and uses it to filter or place data is
authorized by a check that ignores it. The caller names the environment; the gate does not look.

Traced end to end on project deployments:

| Step | Code |
|---|---|
| Caller supplies the environment | `ProjectDeploymentApiController:99-104` takes `Long environmentId` as a REST parameter and passes it through |
| The gate ignores it | `ProjectDeploymentFacadeImpl:608` — `@PreAuthorize("hasPermission(#id, 'Workspace', 'DEPLOYMENT_VIEW')")`, the **environment-unaware** overload |
| The value is used as a filter | `:612` resolves it to an `Environment` and hands it to `projectDeploymentService.getProjectDeployments(...)`. Nothing else in the body checks it |

`PermissionServiceImpl`'s own comment describes the consequence: the environment-unaware check "unions
the environments they can reach". So a member holding `DEPLOYMENT_VIEW` in DEVELOPMENT alone satisfies
the gate, and then names PRODUCTION as the filter.

This is **not** a Copilot defect, though that is where it was found. The Copilot surface reaches the
same facade with the same client-supplied environment (`CopilotToolContextUtils:65` →
`ListProjectDeploymentsToolCallback:117`), and `CopilotChatFacadeImpl` verifies the workspace and never
the environment. But `ProjectDeploymentApiController` does exactly the same thing over REST. Copilot is
not a weaker door; it is the same door. Hardening only Copilot would leave the hole open and make the
Copilot check read as an unexplained exception.

### Scope

Of 68 `hasPermission(#id|#workspaceId, 'Workspace', …)` gates in the tree, **19 sit on methods that
take an environment**. They span `ProjectDeploymentFacadeImpl` (4), `WorkspaceMcpServerFacadeImpl` (2),
`WorkspaceKnowledgeBaseFacadeImpl` (2), `WorkspaceDataTableFacadeImpl` (2), `AiAgentFacadeImpl` (2) and
`WorkspaceVariableGraphQlController` (3), among others.

Two of those facts deserve emphasis:

- **Some are writes, not listings.** `WorkspaceVariableGraphQlController:64,73` take
  `(workspaceId, environmentId, VariableInput)`. A forged environment on a write places data in an
  environment the caller may hold no role in, which is worse than reading from one.
- **Three sit on a GraphQL controller, not a facade — deliberately, and the reason is this branch's own
  subject.** `WorkspaceVariableGraphQlController`'s javadoc states it: `VariableService` "is deliberately
  not authorization-aware -- it is also called by the runtime resolver, which has no security context --
  so the `@PreAuthorize` expressions here ARE the access control for this admin surface." That is
  precisely the problem the asset-file work solved, and it solved it the other way: by splitting the
  facade into a membership-checked door and an ownership-checked one, so the unauthenticated runtime
  caller has somewhere to go that is still checked. Two reasoned answers to one problem now coexist.
  Whatever this design decides must be applied at the controller for those three, since there is no
  authorization-aware facade beneath them to put it in — and it is worth deciding separately whether the
  variable domain should adopt the two-door shape instead.

### What is already correct, and why

**By-id operations are fine and must stay as they are.** `PermissionServiceImpl.hasResourceScope`
resolves the resource's environment through a `ResourceEnvironmentResolver` and then calls
`hasWorkspaceScope(workspaceId, scope, environment)`. The environment there is derived from the row,
not supplied by the caller, so there is nothing to forge. The comment at that call site already states
the rule this design generalises: *"a resource that lives in an environment is checked against the role
the caller holds THERE."*

The gap is precisely the case that resolver cannot cover: **the environment is an argument, not a
property of a resolved resource.** A listing has no row to resolve from, and a create names the
environment the row will live in.

## Not yet demonstrated

No exploit has been reproduced. What is established is the mechanism — the gate is the
environment-unaware overload, the value is caller-supplied, and the body applies it as a filter with no
further check. What is **not** established is that a real member holding a role in one environment and
not another actually receives the other environment's rows.

That gap matters because per-environment roles could be enforced somewhere this trace did not reach.
**The first task of any plan from this spec is to reproduce it** with a member holding
`DEPLOYMENT_VIEW` in DEVELOPMENT only, asking for PRODUCTION. If that returns rows, the rest of the
work is justified; if it returns none, this spec is wrong and should be withdrawn rather than
implemented.

## Design

**Where the environment is a caller-supplied argument, the gate consults it.**

`PermissionService` already has the primitive — `hasWorkspaceScope(long workspaceId, String scope,
Environment environment)`, which differs from its sibling "only in resolving the member's role for
`environment`". Nothing new is needed at the evaluation layer.

What is needed is a way to say it in an annotation. Today `hasPermission(#workspaceId, 'Workspace',
'DEPLOYMENT_VIEW')` routes to `hasResourceScope`, which reaches the environment-aware overload only via
a `ResourceEnvironmentResolver`. An argument-supplied environment has no resolver to go through.

Two options, and the choice is the substance of this design:

**A. A second permission form that names the environment.** For example
`hasPermission(#workspaceId, 'Workspace', 'DEPLOYMENT_VIEW', #environmentId)` — a new evaluator overload
carrying the environment explicitly, delegating to the existing three-argument `hasWorkspaceScope`.
Keeps every check on `hasPermission`, which CLAUDE.md records as deliberate so `isSkipChecks()` stays a
single chokepoint. Costs a new evaluator method and touches 19 annotations.

**B. Verify at the boundary instead.** Each surface validates the environment against the caller before
calling the facade, the way `CopilotChatFacadeImpl` now verifies the workspace. Smaller per-site change,
but it re-creates the pattern this branch has spent its length removing: authorization the caller can
route around by reaching the facade another way. Three cross-workspace vulnerabilities on this branch
all had that shape.

**Recommendation: A.** The check belongs where the data is reached, not at each door. B leaves 19
facades trusting an argument, and the whole argument of the asset-file work was that a facade must not
trust a caller-supplied scope value.

## Consequences

**This will deny access that works today**, which is the point but must be said plainly: a member with
a role in one environment and not another currently sees both and afterwards sees one. That is a
behaviour change on a live surface and belongs in release notes.

In CE nothing changes — `hasWorkspaceScope` short-circuits on `isTenantAdmin()` and CE is
admin-only.

## Out of scope

- **Per-environment roles themselves.** The model exists; this design only makes the argument-supplied
  case consult it.
- **The `assetFileTags` and asset-file work** on this branch, which is workspace-scoped and complete.
- **Whether a listing should return an empty page or refuse** when the caller lacks the role in the
  named environment. Both are defensible and the choice should follow the existing error-shape
  convention: an explicit argument the caller asserts gets a denial, a by-id lookup gets not-found.
