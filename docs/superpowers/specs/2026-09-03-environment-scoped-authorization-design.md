# Environment-scoped authorization — design

**Status:** **Premise demonstrated, design decided and amended, 2026-09-06 — plan written and reworked;
ready to execute.** D1 was amended after a pre-execution review of this spec and its plan found the
"mirror `hasWorkflowScopeInEnvironment` exactly" instruction wrong in four ways, two of which would have
shipped a worse forgery than the one being fixed — see "D1 amended". The reproduction this spec made a precondition has been performed and the gap is real
(see "Demonstrated"). D1 is decided **A**, but not in the shape §Design originally proposed: grounding
found the mechanism already built twice in this repo, so this is application and reconciliation rather
than new design — see "D1 decided". The `WorkspaceVariableGraphQlController` two-door question is decided
**defer**, with reasons, in D2.
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

## Demonstrated, 2026-09-06 — the gap is real

> This section was titled "Not yet demonstrated" and made reproduction a precondition of any plan: *"If
> that returns rows, the rest of the work is justified; if it returns none, this spec is wrong and
> should be withdrawn."* It returns rows. Committed in `807d4c10fc3`, test files only.

A member holding `DEPLOYMENT_VIEW` in DEVELOPMENT only, calling
`getWorkspaceProjectDeployments(workspaceId, PRODUCTION, ...)`, **receives the PRODUCTION rows.**

Two tests, both in
`server/ee/libs/automation/automation-configuration/automation-configuration-service/src/test/java/com/bytechef/ee/automation/configuration/service/`:

- `ProjectDeploymentCrossEnvironmentReadReproductionIntTest` — the reproduction proper. Real
  `@EnableMethodSecurity` evaluating the real `@PreAuthorize` on the real `ProjectDeploymentFacadeImpl`,
  backed by the real `PermissionServiceImpl` and `WorkspaceScopeCacheService`. Nothing in the
  authorization chain is mocked.
- `PermissionServiceCrossEnvironmentUnionGapTest` — the unit-level divergence:
  `hasWorkspaceScope(workspaceId, scope)` is `true` while `hasWorkspaceScope(workspaceId, scope,
  PRODUCTION)` is `false`, for the same member. A characterization test, not a regression pin: it will
  keep passing after any fix here, because the fix changes which overload the annotation routes to, not
  the overloads themselves.

No pre-existing test covered this. `PermissionServiceEnvironmentTest`, `PermissionServiceTest` and
`WorkspaceScopeCacheServiceTest` all mock `WorkspaceScopeCacheService` itself, so the divergence between
its two overloads was never exercised.

### The root cause is narrower than "the wrong overload was used"

`hasResourceScope` *does* have an environment-aware branch, and it is unreachable for this token:
`"Workspace"` registers an ownership resolver but **no `ResourceEnvironmentResolver`**. Exactly three
types register one — `ProjectDeployment`, `Connection` and `McpServer` — so for every other type the
environment-aware branch is architecturally present and silently dead.

That reframes §"What is already correct, and why". By-id operations are fine *for the three types that
registered a resolver*. For any other type, a by-id check falls through to the union overload just as a
listing does. A workspace genuinely has no environment to resolve — the environment is an argument, not
a property — so no resolver could fix this case, which is the spec's original point. But the general
shape is worse than stated: **a future resource type reproduces this gap simply by not registering an
environment resolver, and nothing today catches the omission.**

A coverage test in the shape this repo already uses (`GuardrailSurfaceCoverageTest`,
`McpOutboundGuardrailsCoverageTest`) — failing when a resource type reachable through `hasResourceScope`
has no environment resolver and is not named with a reason — belongs in whatever plan follows this spec.

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

## D1 decided, 2026-09-06 — A, and the mechanism already exists

**A.** B is rejected for the reason above and needs no further argument.

But A as written above is wrong about the cost, and the correction matters more than the choice.
`hasPermission` cannot take a fourth argument: Spring's SpEL forms are fixed at
`hasPermission(target, permission)` and `hasPermission(targetId, targetType, permission)`. A fourth
argument means a custom expression regardless — and this repo already has one.

`AutomationMethodSecurityExpressionRoot` (`automation-configuration-service`, CE) carries three
environment-aware expressions today:

| Expression | Environment argument | Production uses |
|---|---|---|
| `hasWorkspaceScopeInEveryEnvironment(workspaceId, scope)` | none — requires the scope everywhere | 3 `WorkspaceUserService` mutations |
| `hasWorkspaceScopeInEnvironment(workspaceId, scope, environment)` | an already-resolved `Environment` | 2 `WorkspaceUserService` per-environment writes |
| `hasWorkflowScopeInEnvironment(workflowId, scope, environmentId)` | the caller's raw `Long` | `WorkflowTestApiController`, `AiAgentTestApiController` |

The third is this spec's problem, already solved for one resource type. Its own javadoc states the
argument this document spent its Problem section making:

> A workflow has no environment of its own, so no `ResourceEnvironmentResolver` can supply one and
> `hasPermission(#id, 'Workflow', ...)` necessarily unions the environments the caller can reach — a
> member who is editor in Development would pass and could then act in Production. **Use this wherever
> the caller supplies the environment to run in.**

So the design is not "invent a mechanism". It is: **add the missing overload
`hasWorkspaceScopeInEnvironment(long workspaceId, String scope, Long environmentId)`, mirroring
`hasWorkflowScopeInEnvironment`'s trust model exactly, and re-point the 19 annotations at it.** The
existing three-argument `Environment` overload stays for the two call sites that already hold a resolved
value.

### The trust model to mirror, which this spec had not reached

`hasWorkflowScopeInEnvironment` does not trust its `environmentId`, and the reasons are all load-bearing:

- **A confined principal's own environment wins.** For an api-key caller (embedded connected user,
  embedded MCP) the requested ordinal is ignored outright in favour of the principal's, via
  `PrincipalEnvironment#resolveEffectiveEnvironmentId`. A session principal genuinely chooses per
  request, so theirs is honoured.
- **Absent or out of range denies**, rather than falling back to a default, "because an environment that
  cannot be identified cannot be authorised".
- **`ResourceMembershipDecider` is consulted before the skip short-circuit** for a governed principal
  (ticket 1051): a built-in that reads the skip state itself never reaches `hasResourceScope`, so wiring
  the decider there does not cover it.

The second bullet settles what §Out of scope left open — see the amendment there.

### The hazard A creates, and the one thing this design must add

Both existing call sites resolve the environment **twice**: once in the annotation, once again in the
method body, from the same untrusted argument — kept in agreement by a hand-written comment.
`WorkflowTestApiController:241` says "resolved the same way `hasWorkflowScopeInEnvironment` above";
`AiAgentTestApiController:89` says "Must match what `hasWorkflowScopeInEnvironment` authorised above, or
the gate and the run disagree."

Authorising one environment and acting in another is the same bug class as this spec's, one layer down.
Applying A to 19 more sites replicates that hazard 19 times, enforced only by comments.

**So the resolved value must become structural, not conventional.** A guarded method takes the effective
environment rather than re-deriving it from the raw ordinal, so the gate and the body cannot disagree.
Where a signature genuinely cannot change, the divergence must be pinned by a test rather than a comment.

## D1 amended, 2026-09-06 — four corrections from the pre-execution review

A review of this spec together with its plan, before any code was written, found the instruction above
— "mirror `hasWorkflowScopeInEnvironment`'s trust model **exactly**" — wrong in four specific ways. All
four are corrections to this design, not merely to a plan.

### 1. The mirror collides. The new expression needs a distinct name.

`hasWorkspaceScopeInEnvironment(long, String, Environment)` already exists. Adding a same-name,
same-arity `(long, String, Long)` is ambiguous in **both** languages that resolve it:

- **Java:** `hasWorkspaceScopeInEnvironment(1L, "X", null)` does not compile — `Environment` and `Long`
  are unrelated, so neither overload is more specific for a `null` literal. Reproduced on this project's
  JDK.
- **SpEL:** with a null argument, `ReflectionHelper` treats null as matching any reference type, so both
  are exact matches and **reflection order** picks the winner. Reproduced against this repo's
  `spring-expression-7.0.9`: a null argument selects the `Environment` overload, which then reaches
  `WorkspaceScopeCacheService#fetchWorkspaceUser` → `environment.ordinal()` → **NPE, HTTP 500** for any
  non-tenant-admin.

The new expression is therefore named **`hasWorkspaceScopeInEnvironmentId`**. `hasWorkflowScopeInEnvironment`
got away with a shared name only because it has no sibling to collide with.

### 2. Do NOT substitute the principal's environment. That substitution IS the divergence.

The trust model above says a confined principal's own environment replaces the requested one, via
`PrincipalEnvironment#resolveEffectiveEnvironmentId`. Mirroring that here would **create** the hazard
this spec warned about rather than avoid it: the gate would authorise the substituted environment while
the method body — which reads the raw argument — lists the requested one. A confined principal asking
for PRODUCTION would be authorised against DEVELOPMENT and served PRODUCTION. That is a worse forgery
than the one this spec exists to close.

The substitution is correct where it lives, and for a reason that does not generalise: a workflow **run**
happens in the principal's own environment whatever the request said, so the gate must authorise the
environment the run will actually use. A **listing** returns what the argument names. So:

> **`hasWorkspaceScopeInEnvironmentId` checks the requested ordinal directly.** Gate and body read the
> same value, and the divergence cannot exist — structurally, not by convention.

Nothing is lost by not substituting. A confined principal (an embedded connected user) has no `user` row,
so `hasWorkspaceScope` fails closed for them regardless of which environment is checked.

This also discharges what §"The hazard A creates" demanded. It asked for the resolved value to become
structural rather than conventional; not resolving it twice is the strongest available form of that.

### 3. Consult `ResourceMembershipDecider` — the opposite of the plan's first ruling.

The plan first ruled the decider should not be copied, on the grounds that no `ResourceMembershipResolver`
claims `"Workspace"`. The fact is right and the conclusion is backwards. `ResourceMembershipDecider`'s
rule for a **governed** principal reaching a type no resolver claims is `NOT_APPLICABLE` → **DENY**. So
today a `'Workspace'` gate denies an embedded connected user *precisely because* nothing claims that type.
Moving 19 gates off `hasPermission` onto an expression that does not consult the decider would silently
drop that denial from all of them.

`hasWorkspaceScopeInEnvironmentId` therefore consults the decider exactly as `hasWorkflowScopeInEnvironment`
does — not to add governance, but to keep the denial that already exists.

### 4. A null ordinal keeps today's union check. It does not require every environment.

The plan first ruled that a null `environmentId` should require the scope in **every** environment. The
premise was right — the three nullable sites do treat null as "no environment filter" — and the conclusion
would have broken live pages. The clients routinely send nothing: the Projects list and Deploy button
(`ProjectListItem.tsx`, `DeployButton.tsx`), the chat connection picker (`SelectConnectionMessage.tsx`),
and the AI Hub's `ListProjectDeploymentsToolCallback`. A member in **explicit** per-environment mode —
exactly the population this change protects — would have received 403s on ordinary pages.

The principled reading is narrower than either extreme. **This design closes forgery: naming an
environment you hold no role in.** A null names nothing, forges nothing, and asks for the union — which
is what the union gate already authorises. Gate breadth and request breadth agree.

> **Null routes to the environment-unaware `hasWorkspaceScope(workspaceId, scope)`, preserving today's
> behaviour exactly.** An environment named is an environment checked; no environment named is no change.

**Stated plainly, because it is a limitation and not a fix:** the null path still returns rows from every
environment in the workspace, including ones the caller holds no role in. That is a pre-existing union
leak, it is not closed here, and closing it needs either per-environment result filtering in each body or
a client contract that always sends an environment. Both are larger than this design and neither is
started. **Its own ticket.**

## D2 decided, 2026-09-06 — the variable controller keeps its checks; the two-door split is deferred

§Scope raised whether `WorkspaceVariableGraphQlController`'s three gates should move to the two-door
facade shape the asset-file work adopted. **Not now, and not as part of this.**

The two-door split exists to give an *unauthenticated runtime caller* somewhere checked to go.
`VariableService`'s runtime caller is `WorkflowVariablesResolverImpl`, which is fail-open by design and
only ever **reads**; the asset-file split was solving ownership checks on by-id operations, a different
problem. Splitting the variable domain buys uniformity, not safety, and doing it later costs nothing
that doing it now would save.

The three gates therefore get the environment-aware expression **at the controller**, which is where
their access control already lives. Two of the three are writes, where a forged environment places data
in an environment the caller may hold no role in, so they go first.

The two-door question is real and wants its own ticket; it is recorded here so the deferral is a
decision rather than an omission.

## What this design adds beyond the gated sites

Both come from the reproduction, and both are the durable half — the site fixes close today's holes,
these two stop the next one.

> **The count is 20, not 19.** The pre-execution review found one the enumerating scan had missed, and
> it is the worst of them: `WorkspaceApiKeyFacadeImpl#create(long workspaceId, ApiKey apiKey)` carries
> the union gate, and the environment arrives **inside the object** —
> `WorkspaceApiKeyGraphQlController` sets it from an `@Argument Long environmentId`. A DEVELOPMENT-only
> member can mint a **PRODUCTION API key**. The scan missed it because it keys on the word "environment"
> appearing in the signature, and here it does not. A sweep of the other object-taking `'Workspace'`
> gates found no further instances, but the coverage test below must not inherit the same blind spot.

1. **A coverage test for the gates.** `PreAuthorizeAnnotationTest` already pins annotation expressions by
   exact string. Generalise that idea: fail when a method taking an `environmentId` or `Environment`
   argument carries an environment-unaware gate and is not named with a reason. Same shape as
   `GuardrailSurfaceCoverageTest` and `McpOutboundGuardrailsCoverageTest`, both of which exist because a
   set of call sites that must move together had drifted apart.
2. **A coverage test for the resolver registry.** Exactly three resource types register a
   `ResourceEnvironmentResolver` — `ProjectDeployment`, `Connection`, `McpServer`. Every other type's
   by-id check silently falls through to the union overload. Fail when a type reachable through
   `hasResourceScope` has no environment resolver and is not named with a reason.

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
- ~~**Whether a listing should return an empty page or refuse** when the caller lacks the role in the
  named environment.~~ **Decided 2026-09-06 by existing precedent, not by fresh argument: refuse.**
  `hasWorkflowScopeInEnvironment` already denies on an environment it cannot identify, "because an
  environment that cannot be identified cannot be authorised", and a `@PreAuthorize` that returns false
  denies by construction — an empty page would require the gate to pass and the body to filter, which is
  the shape this design exists to remove. This also matches the error-shape convention the original
  wording appealed to: an explicit argument the caller asserts gets a denial, a by-id lookup gets
  not-found.
- **The two-door split for the variable domain.** Deferred with reasons in D2; wants its own ticket.
- **The union leak on an unfiltered listing.** With no environment named, the three nullable listings
  still return rows from every environment in the workspace, including ones the caller holds no role in.
  D1's fourth amendment explains why closing it by denial is not available: the clients routinely send
  nothing, so denial would 403 ordinary pages for exactly the members this work protects. Closing it
  properly needs per-environment result filtering in each body, or a client contract that always sends an
  environment. Neither is started. **Its own ticket**, and the larger of the two.
