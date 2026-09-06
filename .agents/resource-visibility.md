# Workspace scoping, resource visibility & sharing

The workspace_id column convention, the PRIVATE/WORKSPACE/ORGANIZATION model with grants, and per-environment workspace roles.

Extracted from `CLAUDE.md` to keep that file within its size budget;
read this before working in the areas below.

### Workspace scoping for platform entities

**New platform-package entities get a nullable `workspace_id BIGINT` column — not a
`workspace_<entity>` relation table.**

- The column is **nullable**, and the entity field is `Long` (never primitive `long`). Null is a real
  state: embedded has no workspace concept, and some entities use it for "global" scope (a
  `notification` with no workspace applies everywhere).
- If the entity later needs to be shared beyond its owning workspace, add a `visibility` column typed
  `ResourceVisibility` (`PRIVATE < WORKSPACE < ORGANIZATION`, in `platform-api`) and contribute a
  `ResourceVisibilityPolicy` declaring which rungs the resource supports and which one it is created
  with. Reach is **additive to a column** — it does not need a relation table, and it expresses
  ownership + reach, which membership rows cannot.
- **Exception — named-user grants.** A column expresses *reach* and is the right shape for it. It
  cannot express "these three specific people" without an unbounded array in a cell. Grants to
  individual users therefore live in the polymorphic `resource_grant` table (EE, see
  `platform-resource-grant`). That is the one sanctioned relation table for sharing; the rule above
  still governs reach. See `docs/superpowers/specs/2026-08-10-resource-visibility-design.md`.
- Create a `workspace_<entity>` relation table **only** for a genuinely many-to-many relationship with
  no owner concept — the `workspace_user` shape. The bar: *can the same row legitimately belong to two
  workspaces with equal standing, today, with an API that does it?*

**Six relation tables deliberately remain** (they are in release `v0.31.2`, so collapsing them would
mean data migrations against customer data for no user-facing benefit): `workspace_api_key`,
`workspace_connection`, `workspace_data_table`, `workspace_knowledge_base`, `workspace_mcp_server`, and
`workspace_user` (which is genuinely many-to-many and correct as-is). The resulting mixed state is
intentional, not drift.

Background and evidence: `docs/superpowers/specs/2026-07-25-workspace-relation-table-convention-revision.md`.
This revises the earlier `2026-05-06-workspace-relation-tables-design.md` for new work only.

### Resource Visibility & Sharing

A resource wired to this model carries a `visibility` column typed `ResourceVisibility` (`PRIVATE <
WORKSPACE < ORGANIZATION`, in `platform-api`, ordinals pinned by `ResourceVisibilityTest`). Every
resource is created **WORKSPACE-visible** — shared with its workspace unless its owner withholds it.
The model is resource-agnostic. Wired so far: **connections** (`PRIVATE`/`WORKSPACE`/`ORGANIZATION`),
**projects** (`PRIVATE`/`WORKSPACE`; `ProjectVisibilityPolicy`, column `project.visibility`), and
**AI Hub chats** (`PRIVATE`/`WORKSPACE`; `AiHubChatVisibilityPolicy`, column `ai_hub_chat.visibility`
— the one type created **`PRIVATE`**, not `WORKSPACE`, because a chat is a person's working
conversation rather than shared infrastructure; see `.agents/ai-hub.md`'s "Shared chats and
presence" for the full departure and its channel-born exception).
Workflows, project workflows, project deployments and jobs have **no column** — they inherit the
project's reach at check time via `ResourceVisibilityProvider`s whose `visibilityResourceType()`
returns `"Project"` and whose record id is the project id, so grants resolve against
`("Project", projectId)`.

**"Specific people"** is not a fourth stored value — it is `PRIVATE` plus rows in `resource_grant`
(EE, `platform-resource-grant`). A grant conveys visibility only; what the recipient may then do is
decided by the usual `PermissionScope`/`WorkspaceRole` machinery. Grants survive promotion so demoting
restores the previous audience, and are deleted with the resource because `resource_id` is
polymorphic and has no foreign key (connections in `WorkspaceConnectionFacadeImpl.delete`, projects in
EE `ProjectBeforeDeleteEventListener`).

**Visibility is a precondition of `hasResourceScope`**, in both editions — not a filter running beside
it. Without that, a member holding `CONNECTION_EDIT` would pass the by-id check for a connection the
list correctly hides. In CE this replaces owner-isolation *only* for resource types that registered a
`ResourceVisibilityProvider`; API keys and other user-owned resources keep it.
EE is pinned by `PermissionServiceVisibilityTest` (an EE-only class) and CE by the CE
`PermissionServiceResourceTest`; both are the regression guard, one per edition. Four entry points carry
the precondition: `hasResourceScope`, both `hasWorkspaceScopeForProject` overloads and
`hasWorkflowScope`. The whole `PermissionService` surface is enumerated and classified in
`docs/superpowers/specs/2026-08-17-project-visibility-design.md` §17 — extend that table when adding a
method rather than reasoning about it case by case; three of the four were closed by the project-
visibility plan (`hasResourceScope` came from phase 1) and two of those three were found after the
design rather than by it. The one deliberate omission is `hasResourceRole`, which is the owner-or-admin
sharing-management posture (an admin must be able to repair the sharing of a resource they cannot
themselves see). **That table covers the SPI, not the facades that reach it** — a facade method with no
annotation at all cannot appear in a table of what annotations route to, which is how
`ProjectWorkflowFacadeImpl`'s `getProjectWorkflow(long)` and no-argument `getProjectWorkflows()` stayed
unguarded until the whole-branch review.

#### Connections

- **CE**: `ConnectionFacadeImpl.create()` force-writes `WORKSPACE`. No picker, no grants — CE has no
  authorization boundary between workspace members, so everything is workspace-public.
- **EE**: the picker offers Shared with workspace / Private / Specific people. No `ROLE_ADMIN` gate on
  `WORKSPACE` — it is the default, so gating it would fail every ordinary create. `ORGANIZATION` is
  **not** offered here: it is reached through `createOrganizationConnection`, and
  `setConnectionVisibility` rejects it. (`ConnectionVisibilityPicker` can render an Organization
  option behind `showOrganizationOption`, but no caller passes it today.)
- **Embedded**: force-written `PRIVATE`, unchanged. An embedded connection belongs to a connected user,
  not a workspace member, so workspace reach would be wrong in a way that crosses customers.

**What sharing exposes.** `WORKSPACE` grants *use plus existence*, not *read plus write*: both REST
controllers obfuscate `authorizationParameters` and null `parameters`, so a member can never extract a
credential. Credentials *can* be replaced after creation —
`ConnectionFacade.replaceAuthorizationParameters`, reached through
`WorkspaceConnectionFacade.updateConnectionCredentials` — but only by the connection's **owner or an
admin** (`isResourceOwner || hasResourceRole(…, 'ADMIN')`, the sharing-management posture; the same check
`ConnectionServiceImpl.validateOwnerOrAdmin` already applies to every parameter write). `CONNECTION_EDIT`
alone renames and retags, and still cannot repoint a shared connection. A member can run a workflow
against a colleague's account; they can neither extract nor repoint the credential.

**GraphQL mutations** (owner-or-admin, annotated on the facade so they protect every caller):
- `setConnectionVisibility(workspaceId, connectionId, visibility)` — rejects `ORGANIZATION` (set
  through `createOrganizationConnection`) and refuses to narrow to `PRIVATE` while an active
  deployment uses the connection.
- `grantConnectionAccess` / `revokeConnectionAccess(workspaceId, connectionId, userId)` — grantee must
  be a member of the owning workspace; rejection reuses the unknown-connection error so user ids
  cannot be enumerated. Grant is idempotent via `ON CONFLICT DO NOTHING`, not a caught
  `DuplicateKeyException` — PostgreSQL aborts the transaction on a constraint violation, so catching it
  still fails at commit.
- `connectionGrants(workspaceId, connectionId)` — owner-or-admin; a plain viewer must not learn who
  else a connection was handed to.

**Audit**: `CONNECTION_VISIBILITY_CHANGED`, `CONNECTION_ACCESS_GRANTED`, `CONNECTION_ACCESS_REVOKED`.
The first and last are `strictAudit` — both can remove access.

**Metrics**: `bytechef_connection_create` (Counter), tagged `visibility`, wired via
`ObjectProvider<MeterRegistry>` so lightweight app variants without actuator start cleanly. Only
`PRIVATE` and `WORKSPACE` are ever emitted: the counter lives in
`WorkspaceConnectionFacadeImpl.incrementCreateCounter` — its own description reads "Connections
created via the workspace facade" — and `createOrganizationConnection` is a separate EE GraphQL
controller that never reaches it, while `setConnectionVisibility` rejects `ORGANIZATION` outright.

#### Projects (resource visibility phase 2)

- **CE**: `ProjectFacadeImpl.applyCreateVisibility` force-writes `WORKSPACE` and logs a requested
  `PRIVATE` rather than honouring it. **EE**: takes the request, defaulting to the policy default and
  rejecting an unsupported rung against `ProjectVisibilityPolicy.supportedVisibilities()`, which omits
  `ORGANIZATION` because a project belongs to exactly one workspace and the model has no way to express
  one that reaches outside it. `ORGANIZATION` is therefore never persisted in either edition, but by
  different means — EE consults the policy, CE never reaches it (the force-write returns first), and the
  REST enum cannot express the request in the first place. Only two paths write the column — create, and
  EE's `setProjectVisibility`; `updateProject` deliberately cannot change it.
- **Inheritance is a management-surface control only.** A `PRIVATE` project's deployments, webhooks,
  triggers and schedules keep serving traffic exactly as before. Making a project private hides it,
  its workflows, its deployments and its executions from the workspace's *lists and by-id reads* — it is
  not a way to undeploy or pause anything.
- **`ProjectVisibilityFilter` (`automation-configuration-api`) is the single `Project → VisibilityRecord`
  mapping** every list surface uses (projects, workflows, deployments, executions, search, GraphQL). It
  sits in `-api`, not `-service`, because `automation-configuration-graphql` depends only on `-api`; it
  resolves its `ResourceVisibilityResolver` through an `ObjectProvider` and fails closed, since six
  distributed EE apps carry `-api` without `-service` (ai-copilot, connection, coordinator, execution,
  webhook, worker — configuration-app is the one that carries both).
- **`hasWorkspaceScopeForProject` delegates to `hasResourceScope(projectId, "Project", scope)`** in both
  editions (the 3-arg EE overload inlines the same precondition so it can keep the explicit
  `Environment`). `hasWorkflowScope` routes through `hasResourceScope(workflowId, "Workflow", …)`, where
  `WorkflowVisibilityProvider` redirects the lookup to the owning project.
- **`ProjectOwnershipResolver` returns `ownerUserId` (from `created_by`) ONLY because
  `ProjectVisibilityProvider` is registered** — CE `hasResourceScope` then takes the visibility branch
  instead of owner-isolating. The two must never be split across commits: a resolver with an owner but
  no provider hides every project from everyone but its creator in CE.
- **"Hiding a project hides its EXECUTIONS" is monolith-only — the rest of the family is not.** Four of
  the five providers (`ProjectVisibilityProvider`, `ProjectWorkflowVisibilityProvider`,
  `ProjectDeploymentVisibilityProvider`, `WorkflowVisibilityProvider`) are unconditional `@Component`s in
  CE `automation-configuration-service`, which `configuration-app` **does** carry — so in distributed EE
  the project, project-workflow, deployment and workflow types are enforced there exactly as in the
  monolith. Only `JobVisibilityProvider` is missing: it ships in `automation-workflow-execution-service`,
  which `configuration-app` does not carry, so `"Job"` has no registered provider in that app and is
  unrestricted by visibility there. This is narrower than an earlier draft of this bullet (and of the
  design spec's §15.1) claimed — do not restate it as "configuration-app carries no providers".
  `execution-app`, which serves the automation executions REST surface, does not leak in compensation:
  it carries `automation-configuration-remote-client` rather than `-service`, so the unfiltered
  executions list throws (`RemoteProjectFacadeClient.getWorkspaceProjectWorkflows` is an
  `UnsupportedOperationException` stub) and every explicit project/workflow/deployment filter is denied
  (`RemotePermissionServiceClient` returns `false` for every check).
- **No "not while deployed" rule** when narrowing a project to `PRIVATE` (connections have one) —
  deployments inherit, so narrowing takes them along and nothing dangles.
- **Duplicating or importing a project produces a `WORKSPACE` copy carrying NO grants** — a copy is a new
  resource by a new actor, so it starts with standard permissions rather than the source's audience.
- **EE sharing** lives on the separate `ProjectSharingFacade` (owner-or-admin, annotated on the impl),
  GraphQL `project-sharing.graphqls`: `setProjectVisibility` / `grantProjectAccess` /
  `revokeProjectAccess` / `projectGrants`. The three enumeration-relevant failures — unknown project,
  project not in the named workspace, grantee not a member of it — all collapse to the same
  `ProjectErrorType.INVALID_PROJECT` so user ids and project ids cannot be probed. An unsupported rung is
  a different, non-enumerating failure and keeps its own `UNSUPPORTED_VISIBILITY`.
- **Audit**: `PROJECT_VISIBILITY_CHANGED`, `PROJECT_ACCESS_GRANTED`, `PROJECT_ACCESS_REVOKED` (via
  `ProjectAuditPublisher`). No create metric — spec decision ⚑9.
- **Client**: three surfaces, all EE-gated through `useVisibilityFeatureEnabled` /
  `useIsVisibilityEditionEnabled` — the create dialog's picker (reach only; a project that does not exist
  yet has no id to grant against), the project list item's visibility badge dropdown, and the project
  header settings menu's **`Visibility`** item. That menu item is labelled Visibility, not "Share": it
  already hosts two outward-publishing "Share" items (Share project, Share with Community) that mean
  something else entirely. `ResourceVisibilityPicker`/`ResourceVisibilityBadge` live in
  `client/src/shared/components/visibility/` and are shared with connections.


### Asset files: the two-door model

`AssetFileFacadeImpl` carried **no** authorization of any kind and was the common sink of three
cross-workspace vulnerabilities, each closed at its own surface and none at the facade. Of its
twenty methods, eleven took a bare `id` and no workspace at all, so any caller holding an id
operated on it. It is now **two facades performing two different checks** — neither door is
unchecked:

- **`AssetFileFacade` — membership.** Is the current user a member of the workspace that owns this
  file? Six methods take an explicit `workspaceId` and throw `AccessDeniedException`; eleven take a
  bare `id`, resolve the owner from the row, and throw `AssetFileNotFoundException` — deliberately
  404-shaped, so a caller cannot probe which ids exist in other workspaces. The distinction is not
  cosmetic: a caller that *named* a workspace asserted membership and deserves to be told the
  assertion is false; a caller that only held an id gets told nothing.
- **`AssetFileSystemFacade` — ownership.** Does this file belong to the server-derived workspace the
  caller was handed? Eight methods, every id-taking one self-gating through `findByIdInWorkspace`,
  which refuses a null `workspaceId` rather than degrading to unscoped, refuses a file whose own
  `workspaceId` is null rather than treating it as a wildcard, and returns the identical message for
  a cross-workspace id and an unknown one.

**Two implementation classes, not one implementing both interfaces**, so a caller holding the
guarded bean cannot cast across to the system one. The guarded impl delegates to the system impl
after its membership check, so the three methods on both interfaces give a guarded caller both
checks and a system caller ownership only — which is the whole distinction.

**The system facade's contract is load-bearing and stated on the interface:** *the workspace must be
server-derived, and the caller's access to it must have been verified upstream on a thread that had
a principal.* It substitutes an authorization appropriate to a caller with no user; it does not skip
one. Three caller families qualify, and each qualifies for its own reason:

- the `asset-file` **workflow component actions**, which run on execution workers with no user
  `SecurityContext` and whose workspace comes from job → project → workspace, never from caller input;
- the **anonymous public-link and signed download**, where the token is the authorization by design;
- the **AG-UI agent-turn threads** `WebhookBridgeAgent` and `AiHubRoutingAgent`. `LocalAgent.runAgent`
  hands the agent body to a bare `CompletableFuture.runAsync`, so it runs on a
  `ForkJoinPool.commonPool()` worker where `AiHubAgentTenantBinder` binds the **tenant only** and never
  an `Authentication`. A membership check there **denies** — `AssetFileFacadeImpl` resolves the user
  through `fetchCurrentUser`, so a missing principal is a non-member, not an exception. That is not a
  milder outcome: a denial fails every workflow-chat attachment upload just as completely as a throw
  would, so the family still belongs on the system facade. Their upstream verification is
  `AiHubApiController.enforceWorkspaceAccess` plus `enforceThreadOwnership`, on the request thread.

Copilot and AI Hub **tool callbacks are not in this set**: they are wrapped in
`RehydrateContextToolCallback`, which runs them under `SecurityUtils.runAs`, so they carry a principal
and use the guarded facade.

**`enablePublicLink`, `disablePublicLink` and `createSignedDownloadToken` are membership-only and
deliberately absent from the system facade.** They mint *anonymous* access — converting "this job may
touch this file" into "anyone with the URL may" — which no server-derived workspace alone should
authorize.

**`AssetFileSystemFacadeCallerScanTest` pins both sides**: the permitted caller set (16 classes, wider
than the 12 true callers because a simple-name token match also hits the interface, its impl, the EE
remote-client stub and the `@Configuration` class that passes it through) and the interface's method
set (exactly 8, so the link-minting trio cannot be added later without a deliberate change). It reads files outside its own
module, so it runs from a never-up-to-date Gradle task wired into `check` with its own `@Timeout`,
mirroring `toolContextWorkspaceScan`. The threat it guards is not a typo — it is someone with a
perfectly good principal reaching for the system facade because it is less trouble than getting the
workspace right.

**In EE this is a real behaviour change on an existing surface, and belongs in release notes.**
Callers that today reach any asset file by id are refused unless they are members of its workspace.
**In CE nothing changes**, because `WorkspaceFacade#getUserWorkspaces` returns every workspace — the
same way `WorkspaceAccessGuard` is already permissive there.

**Two authorization idioms now coexist in this module, and that is not drift.**
`AssetFileTagServiceImpl:50` uses `@PreAuthorize`/`hasPermission`; these facades deliberately do not.
A scope token is the right primitive for a coarse capability check; it is *not* workspace isolation,
because CE `PermissionServiceImpl.hasWorkspaceScope` returns `SecurityUtils.isAuthenticated()`, so in
CE the token buys authentication only. Real isolation comes from the `getUserWorkspaces` membership
test, which is why the facades do it directly. Use the annotation for a capability, the membership
test for isolation.

**Actual cost.** The design budgeted one extra owner-resolving query per bare-id call; the
implementation costs more than that. The guard loads the row to resolve its owner and the method body
loads it again, so six bare-id methods issue two loads and the four that delegate to a
`*InWorkspace` operation (`delete`, `rename`, `updateContent`, `downloadContent`) issue three, since
`findByIdInWorkspace` loads it a third time. `getVersions` is the exception at one. Nothing caches
across the two, and no measurement has established this matters — it is recorded so the next reader
does not inherit the spec's estimate as fact.

**Known gaps, unfixed.**
- `assetFileTags(workspaceId)` has **never** had a membership check — not before this plan, and not in
  the guard it deleted. It relies on the scope-token idiom above, which in CE is authentication only,
  so a caller-supplied workspace is accepted. Out of scope here, separately ticketed.
- `AssetFileUpdateContentAction` opens the input stream before calling `updateContentInWorkspace`, so
  a denied call leaks a stream that previously was never opened. Own-input only, not cross-workspace.
- `enablePublicLink` and `createSignedDownloadToken` check operator configuration before the
  membership check, so under a kill-switch-off configuration a non-member sees `IllegalStateException`
  rather than not-found. Judged benign: the configuration is global and id-independent, so the
  response is identical for a caller's own files and for ids that do not exist — it is not an oracle.

Spec: `docs/superpowers/specs/2026-09-02-asset-file-authorization-design.md`.

#### A facade-thrown `AccessDeniedException` surfaces as 500, not 403

This is repo-wide and sits directly in the path of a convention CLAUDE.md actively encourages —
moving authorization out of controllers and down into facades. `GlobalResponseEntityExceptionHandler`
declares `@ExceptionHandler(Throwable.class)`, which resolves an `AccessDeniedException` leaving a
controller method **before Spring Security's `ExceptionTranslationFilter` ever sees it**. The filter
translates the exception only if it propagates out of the filter chain; the `@ControllerAdvice` runs
first and turns it into a 500.

So a `@PreAuthorize` moved from a controller down to a facade keeps working and silently changes its
own status code. The controller must carry its own handler — `AssetFileRestController:170` does, and
that is why. It was found here only because a retargeted test failed; nothing in the framework warns
about it. Check the response status, not just the refusal, whenever authorization moves down a layer.

### Per-environment workspace roles (EE)

A workspace member holds either **one implicit role** (a `workspace_user` row with
`environment IS NULL`, applying everywhere — what every pre-existing member has) or **one row per
environment**, where an absent row is a denial. Never both: `setEnvironmentRole` deletes the implicit
row in the same transaction, and two partial unique indexes (`uk_workspace_user_implicit`,
`uk_workspace_user_explicit`) enforce it — the older `uk_workspace_user_workspace_user` constraint was
dropped because it forbade the shape outright. Removing a member's last environment row removes them
from the workspace; it does NOT restore an implicit row, which would turn "revoke their last
environment" into "grant them every environment".

**The environment is always an explicit argument, never read from `EnvironmentContext`.** That
thread-local holds the *source* environment during a promotion and is lost on worker threads and in
agent tool calls, so an implicit read fails open in exactly the case the feature exists for.

**Three checks, and the safety of the first depends on the other two.** Breaking any one silently
re-opens an escalation:

1. `hasWorkspaceScope(workspaceId, scope)` — environment-unaware — returns the implicit row's scopes,
   or for an explicit-mode member the **union** across the environments they can reach. Most guarded
   operations name no environment, so without the union an explicit-mode member holds nothing anywhere.
2. Operations taking effect in **every** environment at once use
   `hasWorkspaceScopeInEveryEnvironment` (`addWorkspaceUser`, `inviteWorkspaceUser`,
   `updateWorkspaceUserRole`, `assignCustomRole`, `removeWorkspaceUser`). Without this, (1) lets a
   member who administers only Development grant themselves Production.
3. Operations acting **in one** environment check that environment:
   `hasWorkspaceScopeInEnvironment(..., #environment)` for the per-environment writes, the
   `ResourceEnvironmentResolver` SPI for by-id guards, and `ProjectDeploymentDTO`'s own environment
   for promotion.

Both new expressions are SpEL functions on `AutomationMethodSecurityExpressionRoot`, not
`hasPermission` overloads — Spring fixes `hasPermission`'s two shapes and neither carries an
environment. Promotion's guard is `hasPermission(#projectDeploymentDTO, 'WORKFLOW_EDIT')`, the
**two**-argument form: the three-argument form casts its first argument to `Serializable`, which that
record is not, and routes to a method that never reaches the promotion branch.
`PromotionGuardExpressionRoutingTest` pins the whole path by evaluating the real annotation.

**`ResourceEnvironmentResolver`** (`automation-configuration-api`) is opt-in per resource type. Exactly
four contribute one — `ProjectDeployment`, `Connection`, `McpServer`, `ApiCollection` — and `ResourceEnvironmentResolverCoverageTest`
fails if that set changes without a recorded reason. **`ApiKey` does not have one**, contrary to what
this paragraph claimed until 2026-09-06. A resolver returning empty falls back rather than denying.

A type with no resolver keeps the environment-unaware check, and that is correct **only where the
resource genuinely has no environment of its own**. It holds for `Project`: environments belong to a
project's deployments, not to the project, and `ResourceEnvironmentResolverProjectGuardTest` exists to
fail if anyone adds a Project resolver. It does **not** hold as a general rule, and reading it that way
is what let a family of gates go unexamined:

- A **data table**'s rows are addressed per environment — `DataTableRef.unowned(baseName, environmentId, …)`
  in `WorkspaceDataTableFacadeImpl`. The same table id resolves to a different row set in each one.
- A **workflow definition** is shared across environments, but its test configurations, node outputs and
  test runs are not, which is why `hasWorkflowScopeInEnvironment` exists and says to use it "wherever
  the caller supplies the environment to run in".

For both, the environment arrives as a method argument rather than as a property of the resource, so no
resolver could supply it and the **gate** must carry it. The absence of a resolver is therefore not
evidence that a type is environment-free; it only says `hasResourceScope` cannot answer the question by
itself. See the environment-scoped authorization note below for what that left open.

**The scope cache key includes the environment.** Without it the first environment checked warms the
entry and every later one is served those scopes — silent privilege escalation that any
single-environment test passes. Eviction loops all three enum values and builds keys through
`TenantCacheKeyUtils.getKey`, never `SimpleKey`, or it no-ops against the tenant-prefixed read key.

**Admin protection is per environment.** `validateNotLastAdmin` counts workspace-wide and cannot see
the new failure: the sole admin moves themselves to Development-only and the other environments are
stranded while the count still reads one. The per-environment writes refuse to strand an environment,
counting a null-environment row as administering every one. Tenant admins bypass, as they do
workspace-wide.

CE is unaffected — every new overload returns `isAuthenticated()`, since CE has no authorization
boundary between workspace members. Spec:
`docs/superpowers/specs/2026-08-19-per-environment-workspace-roles-design.md` (see its As built
section).

#### 2026-09-06: closing the by-id union leak (partial)

The per-environment model above was correct on paper but had a gap in how `hasResourceScope` reached
it: it resolves a resource's environment through a `ResourceEnvironmentResolver` and only then calls
the environment-**aware** check above. Where no resolver is registered for the resource type — or one
is registered but returns empty — it falls through to `hasWorkspaceScope`, the environment-**unaware**
overload, which unions the caller's scopes across every environment in the workspace. A member holding
a role in Development only therefore passed the by-id gate for a resource whose id happened to name
Production as an ordinary method argument, and the method body then acted on that argument unchecked.

**This denies access that works today, and that is the point of the change.** A member with a role in
one environment and not another currently sees, edits and promotes resources in both; after this sweep
they see one, wherever the environment is named as an argument rather than left unfiltered. Nineteen
call sites across `AiAgentFacadeImpl`, `WorkspaceMcpServerFacadeImpl`, `WorkspaceApiKeyFacadeImpl`,
`WorkspaceConnectionFacadeImpl`, `ProjectDeploymentFacadeImpl`, `WorkspaceKnowledgeBaseFacadeImpl`,
`McpServerPromotionHandler` and their GraphQL controllers were re-pointed at
`hasWorkspaceScopeInEnvironment`/`hasWorkspaceScopeInEnvironmentId` or a resolver-backed
`hasPermission`, one deferred with cause (below), and two coverage tests
(`EnvironmentAwareGateCoverageTest`, `ResourceEnvironmentResolverCoverageTest`) now scan production
source for the next one, multi-line signatures included.

**An unfiltered listing is still allowed by its gate — but no longer answered with every environment's
rows.** A null `environmentId` still routes to the environment-unaware union check, and must: the
clients routinely call these facades with no environment at all (`ProjectListItem.tsx`,
`DeployButton.tsx`, `SelectConnectionMessage.tsx`, and an AI Hub tool callback all do), and denying an
unfiltered call would 403 ordinary pages for exactly the members per-environment roles exist to
protect. The gate is right to allow it — there is no environment in the request for a gate to check.
What was wrong was the answer, and that is the **body's** job:
`EnvironmentScopeFilter` (`automation-configuration-api`, sibling of `ProjectVisibilityFilter`) narrows
the rows to the environments the caller holds the scope in, on the three nullable listings —
`ProjectDeploymentFacadeImpl#getWorkspaceProjectDeployments` (5-arg),
`WorkspaceConnectionFacadeImpl#getConnections`, and
`ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions`.

Three things about that filter are load-bearing:

- **It asks `hasWorkspaceScope(workspaceId, scope, environment)` per environment, never the member's
  rows.** That check resolves an environment row if there is one and otherwise falls back to the
  member's implicit row, so a member in **implicit mode — the default — holds their scopes in every
  environment and is not narrowed at all**. The filter therefore cannot regress ordinary members; it
  bites only in explicit mode, the population these roles exist for. Reading the rows directly would
  see only the explicit ones and narrow an implicit member to nothing — every listing blank.
- **Once per listing, never per row.** `Environment` has three values and the scope lookup is cached
  per user/workspace/environment, so the whole question costs at most three cache reads. Inside a loop
  it would be an N+1 authorization storm on surfaces built to avoid one.
- **On the executions page the narrowing runs before the query, not after.** That listing returns a
  `Page`; filtering the page would yield short pages and, once a page emptied, something
  indistinguishable from the end of the results. Deployments carry the environment and are loaded
  first, so restricting them restricts the page itself.

`PermissionServiceCrossEnvironmentUnionGapTest` pins what remains: the gate's union check on a null
environment, which is deliberate.

**CE is unaffected.** `hasWorkspaceScope` short-circuits on `isTenantAdmin()`, and CE ships admin-only,
so there is no non-admin workspace member for either the closed or the still-open half to touch.

**What is still open**, recorded here rather than implied closed:

- `ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions` (site 17) is **closed** — both halves.
  Its body filters per environment like its two siblings, and its gate is now
  `hasWorkspaceScopeInEnvironmentId(#workspaceId, 'EXECUTION_VIEW', #environmentId)`.
  It is worth recording why it was deferred and why the deferral did not hold, because the reasoning
  is reusable. `SecurityConfiguration` in `server/libs/config/security-config` is the only production
  `@EnableMethodSecurity` in this codebase, and `execution-app` depends on neither it nor
  `automation-configuration-service` — only on `automation-configuration-remote-client`. So
  `@PreAuthorize` is never evaluated on *that* deployment and re-pointing it changes nothing *there*.
  All true — and about one deployment only. `server-app` carries both
  `automation-workflow-execution-service` and `security-config`, so the annotation **is** evaluated in
  the monolith, where it was still unioning every environment the caller could reach. A deployment-
  topology argument scopes to the deployment it names; it is not a statement about the annotation.
  The two halves also protect different deployments, which is why they were never one decision: the
  gate protects the monolith, and the body filter — which runs wherever the code runs — is the only
  protection on `execution-app`.
- Four `'Project'`-keyed promotion sites (`ProjectDeploymentPromotionHandler` and
  `ApiCollectionPromotionHandler`, `preview` and `promote` on each), the `WorkspaceDataTableFacadeImpl`
  by-id family, and an eight-class `Workflow` family (plus
  `WebhookTriggerTestApiFacadeImpl#enableTrigger`/`disableTrigger`) carry the identical defect on
  resource types this plan's `'Workspace'`-only scan never looked at. Own ticket.
- `ApiCollectionFacadeImpl#getApiCollections` had **no gate at all** until commit `19b8d00d90e` ("732
  Gate the workspace API collection listing"); the caller-supplied `environmentId` it still takes is
  unchecked.

`EnvironmentAwareGateCoverageTest.KNOWN_EXEMPT` and `ResourceEnvironmentResolverCoverageTest` are the
detailed, current register for all three bullets above — read them for the live site list rather than
treating this paragraph as exhaustive; they are what a future sweep diffs against.
