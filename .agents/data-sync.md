<!-- Load this when working on Data Sync (server/libs/automation/automation-data-sync/*,
     client/src/pages/automation/data-syncs + data-sync-deployments). -->

# Data Sync (automation)

One source, one destination, an optional field mapping and a trigger, edited through a copy of the Data
Stream wizard and backed by domain rows. Spec: `docs/superpowers/specs/2026-09-05-data-sync-design.md`.
Plan: `docs/superpowers/plans/2026-09-05-data-sync.md`.

## Entity model

- `data_sync` — `name` slug (`^[a-z0-9_-]{1,64}$`, enforced both in `DataSync.setName` and by the DB check
  constraint `ck_data_sync_name_slug`), `title`, `description`, `workspace_id`, `project_id` (hidden
  `__DATA_SYNC__<uuid>` project — `SystemProjects.DATA_SYNC_NAME_PREFIX`, same lifecycle as
  `AI_AGENT_NAME_PREFIX`: the project exists only to hold the generated draft workflow and its versions), `uuid`,
  `trigger_type` INT ordinal (`MANUAL`=0, `SCHEDULE`=1), `trigger_parameters` JSON in the agent schedule
  channel's shape (`frequencyKind`, `timeOfDay`, …, reused from `agentScheduleCron.ts`) plus `expression` and
  `timezone`, all written by the client.
- `data_sync_element` — one row per `kind` (`SOURCE`=0, `DESTINATION`=1, `PROCESSOR`=2), unique on
  `(data_sync_id, kind)`; identity in columns (`component_name`, `component_version`, `operation_name`), not
  in `parameters`. The processor is always `dataStreamProcessor/v1/fieldMapper` — `setDataSyncElement` refuses
  anything else (`PROCESSOR_NOT_FIELD_MAPPER`). Picking a new component, version, or operation for the source or
  destination (`setDataSyncElement` with `kind != PROCESSOR`) deletes the existing processor row if one exists —
  a mapping over an operation that no longer exists is meaningless. There is no separate delete-mapping call;
  this side effect is the only thing that ever removes a processor row. Editing an already-picked element's
  parameters or connection (`updateDataSyncElement`) never touches the processor.
- `data_sync_tag` — the `ai_agent_tag`/`project_tag` join shape: composite PK on `(data_sync_id, tag_id)`,
  cascade-deletes with the sync.

## Generated workflow

`DataSyncWorkflowGenerator.generate(dataSync, elements)`: `trigger_1` (`manual/v1/trigger` or
`schedule/v1/cron` reading `expression` + `timezone`, default timezone `UTC`) → `dataStream_1`
(`dataStream/v1/stream`) with `clusterElements.source` / `destination` / `processor` named `source_1` /
`destination_1` / `processor_1` (`DataSyncWorkflowGenerator.nodeName(Kind)`). The cluster-element map key is
the `Kind` name **lowercased** (`source`, `destination`, `processor`) — this is a different key from the one the
component-picker list filters on, see the warning below. Every facade mutation (`updateDataSyncTrigger`,
`setDataSyncElement`, `updateDataSyncElement`, `publishDataSync`) regenerates the draft and re-syncs each
element's `connection_id` into the draft's `WorkflowTestConfiguration` for every `Environment`, keyed
`(dataStream_1, <element node name>)` — the same two-part key the generator emits under `connections`. The
client addresses options and the Test step with `draftWorkflowId` + those fixed names, never by discovery.

**`elementKindKey` vs `clusterElementsCount` — do not conflate them.** The client's own
`elementKindKey(kind)` (in `dataSyncElements.ts`) returns the same lowercase slot name the generator uses
(`source` / `destination` / `processor`), used to build node names like `source_1`. That is NOT the key the
component-definitions list is filtered by when populating the Source/Destination picker
(`useDataSyncElementStep.ts`): `clusterElementsCount` on `ComponentDefinitionBasic` is keyed by the cluster
element type's **UPPERCASE** name (`SOURCE` / `DESTINATION`), reached via
`convertNameToSnakeCase(elementKindKey(kind))`. A filter written against `elementKindKey`'s own lowercase
output silently matches nothing — no error, just an empty picker — which is exactly the bug an earlier draft
of this feature shipped and a review round caught. `clusterElementsCount` is also only populated when the
component-definitions query passes `clusterElementDefinitions: true` alongside `actionDefinitions: true`
(`useGetComponentDefinitionsQuery` in `useDataSyncElementStep.ts`) — the same two flags `useWorkflowLayout`
passes for the workflow editor's own node browser; `actionDefinitions` alone drops any component that offers
a source/destination cluster element but no regular action.

## Run now

`runDataSyncDeployment(id, projectDeploymentId)` serves both trigger types by delegating to
`ProjectDeploymentFacade.createProjectDeploymentWorkflowJob`. The method is deliberately NOT
`@Transactional`: that facade method is declared `Propagation.NEVER`, so wrapping the call in an enclosing
transaction here would make it throw. It refuses a deployment that does not belong to this sync's project
(`DEPLOYMENT_NOT_OWNED`) and a disabled deployment (`DEPLOYMENT_DISABLED`).

## Client

`data-syncs/` (list, detail with the five-step wizard: Trigger, Source, Destination, Mapping, Test) and
`data-sync-deployments/`. Source and Destination (`DataSyncElementStep` + `useDataSyncElementStep`) render
the operation's real property form — the same `Properties` renderer + `ClusterElementProvider` +
`WorkflowMockProvider` arrangement `ComponentConfigDialog` uses to host a component's dynamically-resolved
inputs outside the workflow editor — with `useDebouncedSave` sending the WHOLE `parameters` map through
`updateDataSyncElement` on every field or connection edit. Picking a component only resets local picker state;
picking an *operation* is what actually replaces the element row on the server, via
`useSetDataSyncElementMutation` (which is also what triggers the processor-row deletion above when the
source/destination side changes). The two mutations are kept strictly apart so a routine field edit never
triggers a mapping drop.

The Mapping step (`useDataSyncMapping`) is a purpose-built two-column table, not a rendering of the
processor's property tree — `sourceField`/`destinationField` options come from the sibling source/destination
nodes, resolvable only through `WorkflowNodeOptionApi.getClusterElementNodeOptions` (draft workflow id + the
root task node name `dataStream_1` + the processor's own node name `processor_1`), the same call the original
Data Stream editor's auto-map uses. The processor row itself is created lazily — only once both a source and
a destination element exist — and exactly once, guarded by a ref so a re-render or slow in-flight mutation
can't double-create it against the one-row-per-kind unique constraint. The dialog's Data Stream wizard is
untouched — this is a copy.
