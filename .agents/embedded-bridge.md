# Embedded automation code workflow bridge

Catalog projects, reference vs copy mode, and the sync/async dispatch fallbacks.

Extracted from `CLAUDE.md` to keep that file within its size budget;
read this before working in the areas below.

### Embedded automation code workflow bridge

`POST /api/embedded/internal/automation/projects/deploy` (`ADMIN`-only via `@PreAuthorize` on
`AutomationWorkflowProjectCodeWorkflowFacadeImpl#save`, not the controller — same posture as
`/integrations/deploy`) deploys a plain automation code workflow (`ProjectHandler`/`project-api`)
behind `AutomationWorkflowProjectFacade`'s `__EMBEDDED_AUTOMATION__` marker via
`AutomationWorkflowProjectCodeWorkflowFacadeImpl` -- the SAME artifact deployed through the plain
`/api/automation/v1/projects/deploy` endpoint creates an unmarked, unrelated project; the marker is
what makes it embedded-servable. `ConnectedUserProjectWorkflow` gained a nullable
`catalog_workflow_uuid` discriminator (XOR with `project_workflow_id`, never both): non-null means
the row is a reference to a shared catalog workflow (never a per-user copy, never editable) instead
of a copy-mode row. A catalog project's client-facing `kind` (`COPY`/`REFERENCE`,
`AutomationWorkflowProjectMapper#mapKind`) mirrors that split at the project level, though the hub itself still
only ever produces code-workflow references -- references to visual templates are API-driven (see "Referenced
catalog templates" below). Per-user connection wiring for a reference lives on the deployment row itself
(`project_deployment_workflow_connection` / `ProjectDeploymentWorkflowConnection`), NOT `WorkflowTestConfiguration`
(that table is keyed by `workflowId` alone, and a shared catalog workflow has exactly one `workflowId` across every
referencing user -- reusing it would leak one user's connection into another's run;
`ConnectedUserWorkflowConnectionResolver` is a deliberately separate node-scanning class rather than
a refactor of that path). A short-lived dedicated `connected_user_project_workflow_connection` table duplicated
those connections, was never read back, and has been dropped -- the deployment row is now the only record of what
is wired. Each reference gets its own `ProjectDeployment` scoped to (catalog project,
external user id, **environment** -- the name is `__EMBEDDED__<externalUserId>__<ENVIRONMENT>`, since
one external user can be connected in more than one environment), looked up by name via
`ProjectDeploymentService.fetchProjectDeploymentByName` (new; the existing
`fetchProjectDeployment(projectId, environment)` assumes one deployment per project+environment,
which only holds because copy-mode gives each user their own private project).
`RequestTriggerApiController#executeWorkflow` (sync `POST /workflows/{workflowUuid}`) and
`AppEventTriggerApiController#executeWorkflows` (async `POST /app-events`) both gained an
automation-bridge fallback branch that only runs once the existing integration-workflow lookup comes
back empty (regression-pinned unchanged); dispatch reuses `AbstractWebhookTriggerController
#doProcessTrigger` unmodified with `PlatformType.AUTOMATION` and the reference's (or copy's)
`ProjectDeploymentId` in place of an `IntegrationInstance` id. Both branches now resolve every shape
the bridge can produce, sharing copy-mode resolution through a package-private
`ConnectedUserCopyModeWorkflowResolver` (`embedded-webhook-public-rest`) instead of forking it: (1) a
connected user's own copy uuid dispatches directly; (2) a catalog uuid whose project is a code
catalog (`kind = REFERENCE`) goes through the pre-existing `getOrCreateReference` path; (3) a catalog
uuid whose project is a visual catalog (`kind = COPY`, `AutomationWorkflowProjectDTO
#codeWorkflowProject() == false`) is resolved via implicit copy-then-run on the SYNC endpoint only --
no existing copy provisions one through `ConnectedUserProjectFacade#copyWorkflowTemplate` (the same
copy the explicit `POST /automation/workflow-templates/{uuid}/copy` endpoint performs) and dispatches
it, an existing copy is reused. Dedup for (3) is a new nullable `copied_from_workflow_uuid` column on
`connected_user_project_workflow` (partial unique index alongside it, mirroring
`uk_cupw_connected_user_project_id_catalog_workflow_uuid`), set only when `copyWorkflowTemplate`
provisions the row -- the explicit copy endpoint's own contract is unchanged, it still always creates
a new copy. The async fan-out iterates every `ConnectedUserProjectWorkflow` row for the connected
user and dispatches both reference-mode and copy-mode rows; it has no implicit-provisioning case
(nothing to iterate before a row exists), so shape (3) is sync-only by construction. A redeploy that
drops a workflow flips existing
references to a disabled `dangling` state (`ConnectedUserCodeWorkflowReferenceFacade
#markDanglingReferences`, comparing one catalog project's previous-vs-current published uuid sets)
instead of deleting them; nothing ever clears `dangling` back to false, and since uuid carry-forward
(`AutomationWorkflowProjectCodeWorkflowFacadeImpl#fetchPreviousWorkflowUuidsByName`) only looks one
deploy back, restoring a same-named workflow after an intervening deploy that dropped it mints a
**new** uuid -- a dangling reference never self-heals; recovery is de-provision the dangling row,
then provision fresh against the new uuid. `getOrCreateReference` is not fully self-healing on repeat
calls: once a disabled row exists (missing-connection case, `MissingConnectionException` -> HTTP
409 `{"missingConnectionComponentName": ...}`), a bare repeat call -- invocation or the explicit
`POST .../automation/workflow-templates/{workflowUuid}/provision` with no connections in the body --
rolls the deployment out first if it is behind (see "Referenced catalog templates" below) and then
returns the existing row unchanged, without re-resolving connections. Passing connections in the
provision body on a repeat call DOES re-resolve and rewrite the row, though -- that is the supported
way to change a reference's wiring; de-provision (`DELETE` on the same path) + a fresh provision
remains the only way to force full auto-wiring from scratch. That method's `@Transactional(noRollbackFor =
MissingConnectionException.class)` is required for the "still create the row, just disabled"
contract to hold at all -- without it Spring's default rollback rule would erase the row the method's
own Javadoc promises to keep. There is a SECOND, unrelated `noRollbackFor` site in this feature area:
`ProjectCodeWorkflowServiceImpl#getProjectCodeWorkflow` is
`@Transactional(noRollbackFor = IllegalArgumentException.class)`, needed because
`AutomationWorkflowProjectCodeWorkflowFacadeImpl#fetchPreviousWorkflowUuidsByName` catches that
exception as normal control flow for "no previous deploy yet" and would otherwise poison the
caller's participating transaction on every project's first deploy. Distributed webhook-app invocation
now works: `RemoteAutomationWorkflowProjectFacadeClient#getPublishedProjects`,
`RemoteConnectedUserProjectFacadeClient#copyWorkflowTemplate`, and
`RemoteConnectedUserCodeWorkflowReferenceFacadeClient#getOrCreateReference`/`getConnectedUserWorkflows`
(all in `embedded-configuration-remote-client`) make real REST calls to configuration-app's
`/remote/*-facade` controllers, covering every method the sync/async bridge dispatch paths need. The
remaining methods on those three remote clients (project/workflow CRUD, `enableReference`,
`deleteReference`, `markDanglingReferences`, etc. -- admin-console and per-user-mutation operations,
not invocation) still throw `UnsupportedOperationException`; both `RequestTriggerApiController` and
`AppEventTriggerApiController` catch that and degrade to the same `404`/empty-list an absent bridge
would produce (WARN-logged once via a per-instance `AtomicBoolean`), so an unimplemented remote method
never surfaces as a 500. Spec:
`docs/superpowers/specs/2026-07-27-embedded-automation-code-workflows-design.md`.

### Referenced catalog templates

Copy vs reference is the caller's per-activation choice through the same endpoints for both visual and code
templates -- `POST .../workflow-templates/{uuid}/copy` vs `.../provision`, no per-project stored mode. A
code-workflow template cannot be copied (`/copy` answers 409 `{"reason": "CODE_WORKFLOW_NOT_COPYABLE"}` via
`CodeWorkflowNotCopyableException`); a visual template can be either copied or referenced. The hub still derives
`kind` from the project type, so it only ever produces code-workflow references -- references to visual templates
are created through the API, never the hub.

One `ProjectDeployment` exists per (connected user, catalog project, environment), holding one
`ProjectDeploymentWorkflow` row per referenced template from that project; it is created at the catalog project's
last **published** version and enabled right after creation (`ProjectDeploymentServiceImpl.create` otherwise
forces `enabled=false`). Catalog code never resolves a deployment by `(project, environment)` alone --
`ProjectDeploymentService.getProjectDeploymentId(projectId, environment)` assumes one deployment per project and
throws the moment a second connected user references the same catalog project; every reference path goes through
the by-name lookup instead.

The deployment row is the only record of what is wired: `project_deployment_workflow_connection`
(`ProjectDeploymentWorkflowConnection`), not a separate per-user bookkeeping table (the earlier
`connected_user_project_workflow_connection` duplicated this and has been dropped -- see above).
`ConnectedUserReferenceDeploymentManager.putWorkflows(deploymentId, version, rowSpecs)` touches only the targeted
row at the deployment's current version (create/update/delete that one row, toggle only its triggers, no-op when
nothing changed), because `ProjectDeploymentFacadeImpl.updateProjectDeployment` disables (stopping running jobs)
and re-enables every enabled row it is passed -- sending the full row list on every provision or enable would kill
sibling templates' running jobs. Only a version change (rollout, below) sends the full row list through
`updateProjectDeployment`. Connection candidates come only from
`ConnectedUserConnectionFacade.getConnections(connectedUserId, componentName, List.of())`, never the tenant-wide
`ConnectionService.getConnections(PlatformType.EMBEDDED)` -- the tenant-wide list would let one connected user's
reference wire to another's connection. Slots come from `WorkflowConnectionSlots` over every trigger and every
task including nested ones (`Workflow.getTasks(true)`, cluster elements included via
`ComponentConnectionFacade`). For each slot, in order: the connection requested in the provision body (not
entitled, or named without an id ⇒ 400) → the connection currently wired on the row for that component, if still entitled → the user's
first entitled connection for the component → missing if required. `ProjectDeploymentWorkflowConnection` is
`(connectionId, workflowConnectionKey, workflowNodeName)`; the key is `ComponentConnection.key()` -- the component
name, or a cluster element's node name under its root node -- never the plain node name.

Provisioning with a missing required connection leaves the reference and its row disabled and answers 409
`MissingConnectionError`; with a missing required input it still succeeds (204) but leaves them disabled;
enabling with a missing input answers 409 `MissingInputError`; updating inputs on an enabled reference so a
required value goes missing is refused with `MissingInputException` (409). Both `getOrCreateReference` overloads
and `enableReference` are `@Transactional(noRollbackFor = {MissingConnectionException.class,
MissingInputException.class})` -- there is deliberately no interface `default` overload, since a default method
bypasses the transaction proxy. Every reference write of one connected user -- provision, enable, delete and
`updateReferenceInputs`, which `updateProjectWorkflowInputs` routes a reference to -- starts by locking that user's
`connected_user_project` row (`SELECT … FOR UPDATE`), so two concurrent first provisions cannot both create a
deployment. Plain `FOR UPDATE` rather than `FOR NO KEY UPDATE`, which H2 does not support.

Republish rollout: `AutomationWorkflowProjectFacadeImpl.publishProject` and
`AutomationWorkflowProjectCodeWorkflowFacadeImpl.save` publish `CatalogProjectPublishedEvent`;
`CatalogProjectPublishedEventListener` is `@Async("workerExecutor")` +
`@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` (trigger re-registration runs
component code and may call external APIs, so it cannot run on the publishing request's own thread).
`ConnectedUserReferenceRolloutService` carries `@SkipAutomationAuthorization` because the authorization-bypass
aspect applies on the calling thread and does not follow `@Async` -- without it the rollout would run with no
security context. Each deployment behind the last published version is rolled out in its own `REQUIRES_NEW`
transaction, loading that deployment's references inside it; a failure is logged (optimistic-lock failures at
WARN, everything else at ERROR) and leaves that one deployment on its old version, while rollout continues with
the next deployment. Removed templates and already-dangling references are marked dangling and their rows
dropped; a deployment left with no rows is deleted. Rollout never enables a reference that was disabled. Lazy
catch-up covers the gap between publishes: provisioning (an existing reference, or a new reference on an existing
deployment) and enabling both roll out that one deployment first, inside the facade's own transaction, before
doing anything else.

`AutomationWorkflowProjectFacadeImpl.deleteProjectWorkflow(uuid)` resolves the template against the **draft**
version only, and is idempotent -- a repeated delete must never reach a published version's rows.

Attention reason is derived on read, never stored (`ConnectedUserReferenceAttentionResolver`): `UPDATE_PENDING`
(the deployment is behind the last published version), `MISSING_CONNECTION:<component>` (a required slot with no
connection on the row), `INPUT_REQUIRED:<name>` (a required input with no value on the row); null when healthy or
when resolution fails for any reason (one WARN line, the stack trace at DEBUG, since every listing repeats it) --
one broken reference must never fail the whole listing. The resolver returns the row's inputs with the reason, both
from one lookup of the row.
Exposed as `attentionReason` on the public `ConnectedUserProjectWorkflow` model; the hub shows "Needs attention"
and the enable switch clears it.

`project.automation_hub_visible` (default true, set through the embedded catalog project facades, GraphQL and the
admin "Show in Automation Hub" toggle) hides a project from the hub's own catalog endpoint
(`getFrontendProjects`) only -- the API-key catalog endpoint still lists it, and `/provision`/`/copy` ignore the
flag entirely, since the vendor's backend needs to keep activating flows it has deliberately kept off the hub. The
hub's catalog endpoint now calls the permission-filtered `getPublishedProjects(externalUserId, environment)`; it
previously called the unfiltered listing and silently ignored permission expressions.

Two known limits: a reference whose catalog template first appears in a version newer than the user's deployment
is caught up (rolled out) before the new row is written, so provisioning it is not instantaneous; and
per-reference listing still does a few lookups per row -- fine for one user's small list, but batch by deployment if a
listing ever needs to scale past that. Spec:
`docs/superpowers/specs/2026-09-16-embedded-referenced-visual-templates-design.md`.
