# Embedded Automation — Referenced Visual Templates Design

- **Date:** 2026-09-16 (revised 2026-09-17)
- **Branch:** `0_732`
- **Status:** Draft — awaiting review.
- **Ticket:** none filed yet.
- **Related:** `.agents/embedded-bridge.md` (the automation bridge that introduced code workflow references).

## 1. Summary

A connected user activates an embedded automation catalog template in one of two ways:

- **copy** (`/copy`) — the template becomes an editable workflow in the connected user's own project, deployed
  there, frozen at the version copied;
- **reference** (`/provision`) — the connected user gets a per-user `ProjectDeployment` of the vendor's catalog
  project that runs the vendor's published workflow.

Today references are only exercised for code workflows. A vendor that activates flows programmatically for each
connected user, without embedding the Automation Hub, wants to reference **visual** templates too, so every
connected user runs the vendor's latest published version.

The choice stays with the caller, per activation: the vendor's backend already knows which flows it references and
calls `/provision` for those. Nothing about copy-vs-reference is stored per project. What this spec adds:

1. The reference path works for visual templates and follows every republish (§4–§6). It is currently broken in
   ways that also affect code workflows (§2).
2. A per-project **Show in Automation Hub** flag, so reference-only flows can live in catalog projects the hub never
   lists, while the backend can still provision them (§7).
3. The hub's catalog listing honours permission expressions, which it silently ignores today (§7, D10).

Schema impact: one column added (`project.automation_hub_visible`), one table dropped
(`connected_user_project_workflow_connection`). No new tables.

## 2. Current state and defects

`ConnectedUserCodeWorkflowReferenceFacadeImpl.getOrCreateReference` accepts any template visible in the
permission-filtered catalog; nothing checks that it is a code workflow. Reading the reference path against a
multi-template, multi-version visual project shows:

| # | Defect | Where | Effect |
|---|---|---|---|
| D1 | The per-user deployment is looked up by `(catalogProjectId, name)` and, when found, reused **without adding** the newly referenced workflow. | `getOrCreateProjectDeployment` | A second template from the same project is never deployed; enabling it fails. |
| D2 | The deployment is always created with `projectVersion = 1` while its workflow is the **latest** published one. | `getOrCreateProjectDeployment` | Version/workflow mismatch once a project has been published more than once. |
| D3 | The deployment keeps the workflow id from activation; `enableReference` looks up the **current** last-published id. | `enableReference` → `rewireConnections` | After a republish, re-enabling a reference fails. References never follow a republish. |
| D4 | The resolver scans only top-level `triggers`/`tasks` and takes the first connection per component. | `ConnectedUserWorkflowConnectionResolver` | Nested tasks and cluster elements are never wired; a user cannot choose between two connections of one component; the hub wizard's selections are ignored for references. |
| D5 | `updateProjectWorkflowInputs` resolves the workflow only in the connected user's own project; the reference listing always reports empty input values. | `ConnectedUserProjectFacadeImpl` | Inputs cannot be set or seen on a reference. |
| D6 | The resolver lists candidates with `connectionService.getConnections(PlatformType.EMBEDDED)`, which returns **every** embedded connection in the tenant. | resolver | A reference can be wired to **another connected user's** connection. Security defect. |
| D7 | Deployment connections are built as `new ProjectDeploymentWorkflowConnection(connectionId, nodeName, nodeName)`; the constructor is `(connectionId, workflowConnectionKey, workflowNodeName)` and the platform key is the component name (or the cluster element's node name). | `getOrCreateProjectDeployment`, `rewireConnections` | Trigger connections never resolve; saving an enabled row fails key validation. |
| D8 | `deleteReference` removes only bookkeeping rows. | reference facade | The deployment row and its triggers stay live after removal. |
| D9 | `ProjectDeploymentService.getProjectDeploymentId(projectId, environment)` assumes one deployment per project and environment. | any caller on a catalog project | Throws once two connected users reference the same project. |
| D10 | The frontend catalog endpoint (`AutomationWorkflowProjectApiController.getFrontendProjects`) calls the unfiltered `getPublishedProjects()`. | public REST | The hub lists templates the connected user may not activate; activating them then 404s. |
| D11 | `connected_user_project_workflow_connection` duplicates the deployment row's connections and is never read. | reference facade | Dead writes that silently diverge (it carried D7's wrong key). |

## 3. Decisions

| Question | Decision |
|---|---|
| Who chooses copy vs reference | The **caller, per activation**: `/copy` or `/provision`. No stored per-project mode. |
| Which templates can be referenced | Any published template the connected user may see — visual or code. |
| Which templates can be copied | Visual templates only; `/copy` of a code-workflow template is rejected. |
| Republish | References **auto-follow** the latest published version. |
| Connections | Explicit per-component choice in the provision request; afterwards the choice is whatever the deployment row has wired; auto-match fills the rest. |
| Deployment shape | **One `ProjectDeployment` per (connected user, catalog project, environment)** holding one `ProjectDeploymentWorkflow` row per referenced template, moved between versions through `ProjectDeploymentFacade.updateProjectDeployment`. |
| Attention state | **Derived on read**, never stored. |
| Hiding from the hub | Per catalog project: **Show in Automation Hub** (`project.automation_hub_visible`, default true). |

Rejected: a stored per-project activation mode (the caller already knows; storing it duplicates that); one
deployment per (user, template) (multiplies deployments and rollout fan-out); running the catalog project's own
deployment with per-user context injected at job time (triggers are registered per deployment); a stored
component→connection selection table and a stored attention reason (both duplicate facts the deployment row holds).

## 4. Activation

### 4.1 API

No new endpoints: visual-template and code-workflow references share the existing ones — `/provision` (create, or
change connections), `DELETE …/provision`, `…/workflows/{uuid}/enable`, `…/workflows/{uuid}/inputs`,
`…/workflows` and `DELETE …/workflows/{uuid}`. The server tells the two apart from stored data, never from the
caller.

Both provision endpoints (`/{externalUserId}/automation/workflow-templates/{uuid}/provision` with the API key and
`/automation/workflow-templates/{uuid}/provision` with the connected-user JWT) accept an optional body:

```json
{"connections": {"slack": 123, "google-sheets": 456}}
```

The OpenAPI descriptions stop calling the endpoint code-workflow-only. Both `/copy` endpoints reject a code-workflow
template with 409 `{"reason": "CODE_WORKFLOW_NOT_COPYABLE"}`. The enable endpoints gain a second 409 body,
`MissingInputError {"missingInputName": "channel"}`, beside the existing `MissingConnectionError`. The remote
facade endpoint used by the distributed apps carries the same optional body and maps the same errors.

The hub itself only ever references code-workflow projects: its `kind` is still derived from the project type, so a
visual template activated through the hub is copied. References to visual templates are created through the API.

### 4.2 `getOrCreateReference(externalUserId, templateUuid, environment, requestedConnectionIds)`

1. Validate visibility against the permission-filtered catalog (unchanged; unknown and forbidden both 404).
2. **Existing reference:** if its deployment is behind the last published version, roll it out first (§6). When the
   request carries connections, re-resolve the reference with them (§4.3) and rewrite its row. Return it. Repeating
   the call is safe and is how a reference's connections are changed.
3. Find or create the user's deployment for `(catalogProjectId, externalUserId, environment)` at the catalog project's
   last **published** version (fixes D2).
4. Resolve connections (§4.3) and write this template's row into the deployment through `updateProjectDeployment`,
   keeping every other row (fixes D1, D9).
5. Save the reference row. A missing required connection ⇒ row and reference disabled, 409 `MissingConnectionError`
   (the existing `noRollbackFor` contract is kept). A missing required **input** also leaves the row and reference
   disabled, but the call still succeeds (204): inputs are written after provisioning in both the hub and the API
   flow, and `ProjectDeploymentFacadeImpl` refuses to save an enabled row without them. Otherwise enabled.

### 4.3 Resolving connections

Slots come from `ComponentConnectionFacade` over every trigger (`WorkflowTrigger.of`) and every task including nested
ones (`Workflow.getTasks(true)`); cluster elements are included by `ClusterRootComponentConnectionFactory`. A slot is
`(workflowNodeName, key, componentName, required)`.

Candidates come only from `ConnectedUserConnectionFacade.getConnections(connectedUserId, componentName, List.of())` —
never the tenant-wide list (fixes D6). For each slot, the first that applies:

1. the connection **requested** for its component — if not entitled, the request fails with 400;
2. the connection the reference's **current deployment row** has wired for that component, if still entitled;
3. the user's first entitled connection for the component;
4. none — the component is missing if the slot is required.

Rule 2 is what keeps a user's choice across republishes, and a deleted or un-shared connection simply falls through
to rule 3. Each resolved slot becomes `new ProjectDeploymentWorkflowConnection(connectionId, slot.key(),
slot.workflowNodeName())` (fixes D7).

### 4.4 Inputs

`updateProjectWorkflowInputs` gains the copy/reference branch `enableProjectWorkflow` already has: for a reference
it rewrites that template's deployment row with the new inputs (so an enabled row's triggers re-register with them).
The reference listing reads input values from the row (fixes D5).

### 4.5 Enable

`enableReference` rolls the deployment out first when it is behind (§6), then re-resolves connections (§4.3) and
enables the row. A missing connection keeps it disabled and returns the existing 409; a missing required input keeps
it disabled and returns 409 `MissingInputError`. A dangling reference cannot be enabled.

### 4.6 Delete

`deleteReference` rewrites the deployment without this template's row — `updateProjectDeployment` disables its
triggers and stops running jobs before deleting it — deletes the whole deployment when it was the last row, then
deletes the reference row (fixes D8). A dangling reference has no row; only the reference row is deleted.

### 4.7 Copy guard

`copyWorkflowTemplate` rejects a template whose catalog project is a code-workflow project
(`AutomationWorkflowProjectDTO.codeWorkflowProject()`), with a dedicated exception mapped to the 409 in §4.1.

## 5. Data model

- **Dropped:** `connected_user_project_workflow_connection`, its domain class `ConnectedUserProjectWorkflowConnection`
  and `ConnectedUserProjectWorkflowConnectionRepository` (D11). The deployment row
  (`project_deployment_workflow_connection`) is the only record of what is wired.
- **Added:** `project.automation_hub_visible BOOLEAN NOT NULL DEFAULT TRUE` (§7).
- **Unchanged:** `connected_user_project` (owns copies; anchors every automation row of a user),
  `connected_user_project_workflow` (a row's kind stays `catalog_workflow_uuid` xor `project_workflow_id`,
  enforced by `ck_cupw_project_or_catalog_not_both`; `dangling`/`dangling_reason` stay — a removed template cannot be
  derived after the fact).

Both schema changes ship as new Liquibase changesets; no existing changelog is edited.

## 6. Republish rollout

### 6.1 Trigger

`AutomationWorkflowProjectFacadeImpl.publishProject` and `AutomationWorkflowProjectCodeWorkflowFacadeImpl.save`
publish `CatalogProjectPublishedEvent(projectId)`. A `@TransactionalEventListener(phase = AFTER_COMMIT,
fallbackExecution = true)` runs the rollout on `workerExecutor` (trigger re-registration runs component code and may
call external APIs). The rollout service carries `@SkipAutomationAuthorization`; the bypass is applied by an aspect
on the calling thread and does not follow `@Async`.

### 6.2 Per-deployment unit

Each reference deployment of the project behind the last published version is rolled out in its own
`REQUIRES_NEW` transaction:

1. References whose template is no longer published are marked `dangling` and disabled; their rows are dropped.
   References already dangling from an earlier publish are treated the same way, so a deployment whose every
   reference dangles is emptied and then deleted rather than left running its old triggers.
2. Every other reference is resolved (§4.3) against the new version's workflow. Its row stays enabled only if it was
   enabled, every required connection resolved and every required input has a value.
3. One `updateProjectDeployment` call writes all rows at the new version — the facade carries inputs forward by
   workflow uuid, drops rows not passed, and re-registers triggers of enabled rows.

A failure is logged and leaves that deployment on its old version; the rollout continues with the next deployment.
Rollout never enables a reference that was disabled.

### 6.3 Convergence

The target is "deployment at the last published version", so rollout is idempotent. A deployment left behind — by a
failure or a crash — converges on the next publish or lazily on that user's next `provision` or `enable` (§4.2,
§4.5). No scheduler or job table.

## 7. Automation Hub

### 7.1 Show in Automation Hub

`project.automation_hub_visible` (default true) is set through catalog project create/update
(`AutomationWorkflowProjectFacade`, admin facade, GraphQL, admin dialog) and exposed on the admin
`AutomationWorkflowProject` type. The hub's catalog endpoint (`getFrontendProjects`) omits projects with it false.
The API-key catalog endpoint (`getProjects`) still lists them — the vendor's backend needs them — and `/provision`
and `/copy` ignore the flag entirely.

A reference to a template in a hidden project still appears under the user's automations in the hub (unmatched to
any card), with its toggle and remove action, if the vendor embeds the hub for that user.

### 7.2 Permission expressions in the hub (D10)

`getFrontendProjects` uses the permission-filtered `getPublishedProjects(externalUserId, environment)` for the
authenticated connected user, so the hub only offers what the user may activate.

### 7.3 Attention reason

`ConnectedUserProjectWorkflow` (public model) gains `attentionReason`, computed when references are listed:

| Value | Derived from |
|---|---|
| `MISSING_CONNECTION:<component>` | a required slot (§4.3) of the row's workflow with no connection on the row |
| `INPUT_REQUIRED:<name>` | a required workflow input with no value in the row's inputs |
| `UPDATE_PENDING` | the deployment's version is below the catalog project's last published version |

Null when none applies, and null when the catalog project can no longer be resolved — one broken reference must not
fail the whole listing. The hub shows **Needs attention** with a readable reason alongside the existing dangling
badge; the card's enable switch re-resolves and clears it. Slots are not cached initially; if listing proves slow,
cache them per published workflow id, which is immutable.

### 7.4 Wizard

For a `REFERENCE` template, `useActivationFlow` sends the connect step's selections with `/provision`; inputs are
written after provisioning and before enabling, as for `COPY`.

## 8. Testing

Every new IntTest is first shown to fail against the pre-change code.

| Level | Coverage |
|---|---|
| Unit — `ConnectedUserWorkflowConnectionResolverTest` (new) | requested → currently wired → first entitled → missing; requested connection not entitled ⇒ 400; wired connection no longer entitled falls through; candidates only from `ConnectedUserConnectionFacade`; key is `ComponentConnection.key()`; nested-task and cluster-element slots |
| Unit — `ConnectedUserCodeWorkflowReferenceFacadeTest` | provision/enable/delete call sequences; `/copy` of a code-workflow template ⇒ `CodeWorkflowNotCopyableException` |
| IntTest — `AutomationCodeWorkflowBridgeIntTest` (existing) | two users never share a connection (D6); the wired key is the component name (D7) |
| IntTest — `ConnectedUserReferenceRolloutIntTest` (new, Testcontainers) | two templates from one visual project ⇒ one deployment, two rows (D1) at the published version (D2) with component-name keys (D7); enable after republish works (D3); inputs set and listed (D5); provision with a missing required input succeeds and leaves the reference disabled, enable then answers `MissingInputError` until the input is set; delete removes the row and the empty deployment (D8); republish ⇒ rows move, inputs kept; republish adding a connection the user lacks ⇒ only that reference disabled, reason derived; template removed ⇒ dangling; every template removed ⇒ the deployment is deleted; enable catches up a deployment left behind; the two-template + republish scenario for a code-workflow project |
| IntTest — `AutomationWorkflowProjectFacadeIntTest` | `automation_hub_visible` round-trip and default |
| IntTest — public REST | hub catalog omits hidden projects and forbidden templates (D10); API-key catalog keeps hidden projects; provision body passed through; `attentionReason` serialized |
| Client — Vitest | admin dialog toggle; wizard sends selections for `REFERENCE`; attention badge renders |

## 9. Out of scope

- A stored per-project copy-vs-reference mode.
- Automatically re-enabling a reference when its missing connection is later created.
- A vendor-facing rollout summary or status endpoint.
- Per-node connection selection (choices are per component).
- `/copy` answering a repeat call with 500 via `uk_cupw_connected_user_project_id_copied_from_workflow_uuid` — a
  separate, small fix.
