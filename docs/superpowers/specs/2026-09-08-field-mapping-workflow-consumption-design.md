# Consuming Field Mappings in Workflows — Design

**Date:** 2026-09-08
**Status:** Approved design (pre-plan)
**Scope:** Embedded (EE) only
**Follows:** `2026-06-04-embedded-field-mapping-design.md`

## 1. Summary

The 2026-06-04 design brought [Paragon-style field mapping](https://docs.useparagon.com/connect-portal/field-mapping)
to the embedded Connect Portal: an end user picks a remote object type and maps their application's
fields to the connected integration's fields, and the result is persisted as a workflow-input value.
That design deliberately stopped at authoring. Its Section 5 states it plainly — "There is no
server-side transform that emits `mappedIntegrationObject` / `mappedApplicationObject`. A built-in
'apply field mapping' transform may be a later, separate spec." Its Section 8 lists that transform
as the first item of future work.

This is that spec. It makes a saved mapping consumable inside a workflow, in both directions.

Three changes, layered:

1. **Per-connected-user workflow inputs reach job runtime.** Today they do not, which is the
   blocking defect — the saved mapping is unreachable from a running workflow.
2. **A new EE component, `field-mapping`, with two actions** — `mapToIntegration` and
   `mapToApplication` — that resolve the current connected user's mapping by object name and apply
   it to a payload.
3. **The design-time data pills stop describing a shape that never exists at runtime.**

## 2. Background: what exists, and the three gaps

### What already exists

- **The Connect Portal UI.** `FieldMappingField.tsx`
  (`sdks/frontend/embedded/library/src/components/connect-dialog/`) renders the object-type selector
  and the mapping rows, and emits the descriptor.
- **Persistence.** The descriptor is saved as a workflow-input value on
  `IntegrationInstanceWorkflow.inputs` — per connected user — via
  `ConnectedUserIntegrationInstanceFacadeImpl.updateIntegrationInstanceWorkflow`, which verifies
  instance ownership before writing.
- **The input type.** `FIELD_MAPPING` is a workflow-input type carrying an `objectName` extension,
  already exposed on the domain as `WorkflowInput.getObjectName()` and through the embedded public
  REST model (`InputTypeModel.FIELD_MAPPING`, `InputModel.objectName`).
- **A transform, in the wrong shape.** `FieldMapperItemProcessor` (data-stream) already performs
  source-to-destination field renaming with dotted-path support and per-mapping default values. It
  is a `PROCESSOR` cluster element usable only inside a data-stream pipeline, driven by
  author-configured mappings rather than an end user's saved one. It is not reusable here, but its
  semantics are the precedent this design follows.

### Gap 1 — the mapping never reaches workflow runtime (blocking)

A job's initial context comes from `JobPrincipalAccessor.getInputMap(...)`. The embedded
implementation returns configuration-level inputs only:

```java
// IntegrationJobPrincipalAccessor.java
public Map<String, ?> getInputMap(long jobPrincipalId, String workflowUuid) {
    // ...resolve instance -> configuration -> configuration workflow...
    return integrationInstanceConfigurationWorkflow.getInputs();
}
```

A sweep of every caller of `IntegrationInstanceWorkflow.getInputs()` in the EE tree found exactly
two runtime consumers — `enableWorkflowTriggers` and `disableWorkflowTriggers` in
`IntegrationInstanceFacadeImpl` — and both do
`MapUtils.concat(configurationWorkflowInputs, instanceWorkflowInputs)`. Every other reference is a
REST DTO or the setter.

So the three paths disagree today: trigger registration sees the per-user inputs, and a running job
does not. `${input.contactMapping}` resolves to nothing at execution time. This is not specific to
field mapping — it is true of every per-user input the Connect Portal collects.

### Gap 2 — the design-time pills describe a runtime shape that does not exist

`getFieldMappingPillProperties.ts` synthesizes one child pill per *application field*, so the editor
offers `${input.contactMapping.title}` as though it were a value. At runtime the input's value is
`{objectType, mappings: [...]}` — a descriptor — and that path does not exist. The panel is
currently promising the output of a transform that was never built.

### Gap 3 — no transform, in either direction

Nothing maps an application object into integration keys, or an integration record back into
application keys.

## 3. Decisions taken, and what was rejected

Recording these because several are load-bearing and the rejected options are the ones a later
reader is most likely to re-propose.

### Embedded only; nothing for automation

Field mapping earns its indirection only when **a different person than the workflow author supplies
the mapping at a later time**. In automation the author is the configurer: both schemas are visible
as data pills at authoring time, and `data-mapper` already ships `renameKeys` and
`mapObjectsToObject` for renaming. A `FIELD_MAPPING` input in automation would carry only its
design-time test value — a static sample the author typed — because nothing in automation ever
collects a real one. Applying it would be a strictly worse way to spell a rename the author already
knew.

### No evaluator functions

An earlier iteration of this design included CE evaluator functions `mapToApplication()` /
`mapToIntegration()`. They are rejected, for two reasons.

**CE cannot produce a descriptor at all** — no Connect Portal, no connected users, no `FIELD_MAPPING`
input anyone fills. A CE function would be permanently inert surface that nonetheless appears in the
`=` autocomplete and in the Copilot prompt, and would have to be maintained as a public contract
encoding an EE-defined vocabulary (`applicationField` / `integrationField`). That inverts the
dependency direction.

**Making them EE-only costs new plumbing, and inherits a known drift.** Executable functions are
hardcoded in `SpelEvaluator`'s constructor (`evaluator-impl`, CE); the `Builder.methodExecutor(...)`
seam exists and is already used once — `EvaluatorConfiguration` injects `fromAi` from `platform-ai` —
but by hardcoding a single exception rather than collecting contributors. An EE-only function would
first require converting that config into a contributor-collecting one. And the seam is already
leaky: `RuntimeConfiguration` in `runtime-job-app` and the static `WorkflowUtils.EVALUATOR` in the
validator both build evaluators without it, so `fromAi` is *already* unavailable in `runtime-job-app`.
Any contributed function inherits that.

The strongest argument for the functions was inline use inside a Code step. Section 6 shows that is
already covered, better, by the component proxy.

### No trigger-level auto-apply

Paragon offers an "Apply field mapping" toggle on triggers, whose output becomes
`{originalPayload, mappedApplicationObject}` or `{originalPayload, mappedIntegrationObject}`. It is
out of scope here. It would touch `TriggerCompletionHandler`, both webhook executors
(`WebhookWorkflowExecutorImpl`, `WebhookWorkflowSyncExecutor`), the trigger node UI, and every
trigger's declared output schema. It is a coherent later addition layered on the component's
transform, and should be its own spec if wanted.

### The component is embedded-aware, not generic

The actions take an **object name** and resolve the current connected user's descriptor themselves,
rather than taking a descriptor passed in as a pill. The author does not need to know which workflow
input holds the mapping. Section 5 shows this needs no new platform plumbing.

Note the interaction with Section 4: once per-user inputs are merged into `input.*`, the descriptor
*is* also reachable as `${input.<name>}`. That is deliberate and useful — it is what makes the
descriptor inspectable and what a Code step can read directly — but it is not the action's lookup
path.

## 4. Surface 1 — Runtime plumbing

`IntegrationJobPrincipalAccessor.getInputMap` merges both tiers, per-user winning:

```java
@Override
public Map<String, ?> getInputMap(long jobPrincipalId, String workflowUuid) {
    IntegrationInstance integrationInstance = integrationInstanceService.getIntegrationInstance(jobPrincipalId);

    String workflowId = getWorkflowId(jobPrincipalId, workflowUuid);

    IntegrationInstanceConfigurationWorkflow integrationInstanceConfigurationWorkflow =
        integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflow(
            integrationInstance.getIntegrationInstanceConfigurationId(), workflowId);

    Map<String, Object> instanceInputs = integrationInstanceWorkflowService
        .fetchIntegrationInstanceWorkflow(jobPrincipalId, workflowId)
        .map(integrationInstanceWorkflow -> (Map<String, Object>) integrationInstanceWorkflow.getInputs())
        .orElse(Map.of());

    return MapUtils.concat(
        (Map<String, Object>) integrationInstanceConfigurationWorkflow.getInputs(), instanceInputs);
}
```

`fetchIntegrationInstanceWorkflow(long, String)` returns an `Optional` and is used deliberately over
the throwing `getIntegrationInstanceWorkflow(long, String)`: a connected user who has not yet opened
the Connect Portal for a workflow has no per-user row, and that must degrade to the configuration
inputs rather than fail the job.

The precedence — per-user overrides configuration — is the precedence
`IntegrationInstanceFacadeImpl` already uses on both the enable and disable paths, so this brings the
third path into line rather than inventing a rule.

This fixes the whole per-user input channel, not only mappings: everything the Connect Portal
collects becomes readable as `${input.<name>}` at runtime.

Where a per-user row does not exist for a workflow, the merge degrades to the configuration inputs
alone — the behavior today.

## 5. Surface 2 — The `field-mapping` EE component

Location: `server/ee/libs/modules/components/field-mapping`, alongside `app-event`,
`api-platform`, `code-workflow`, `context-store` and `request`. Registered in `settings.gradle.kts`
next to those; `server-app` picks EE components up by path prefix, so nothing else is wired by hand.

### Discovery

Spring-registered, not `@AutoService`, because the actions need constructor injection. This follows
`app-event` and `request` exactly, including the EE gate:

```java
@Component(FieldMappingComponentDefinition.FIELD_MAPPING + "_v1_ComponentHandler")
@ConditionalOnEEVersion
public class FieldMappingComponentHandler implements ComponentHandler {

    public FieldMappingComponentHandler(
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService,
        WorkflowService workflowService,
        WorkflowTestConfigurationService workflowTestConfigurationService) {
        // ...
    }
}
```

Dependencies: `platform-component-api` (for `ActionContextAware`), `atlas-configuration-api`
(`WorkflowService`), `platform-configuration-api` (`WorkflowTestConfigurationService`,
`WorkflowInput`), `embedded-configuration-api` (`IntegrationInstanceWorkflowService`) and
`commons-util` (`MapUtils`).

A Spring-registered handler is **not** subject to the build-time component index:
`ComponentDefinitionRegistry.loadComponentDefinitionsByName` iterates the injected
`componentHandlers` list *before* it consults index entries, so the component resolves by name
whether or not an index is present. That is also what makes `context.component.fieldMapping` work
from a script (Section 6).

### Actions

Named for their output, mirroring Paragon's `mappedIntegrationObject` / `mappedApplicationObject`:

| Action | Reads keys | Writes keys | Use |
|---|---|---|---|
| `mapToIntegration` | `applicationField.value` | `integrationField` | your application object, written into their CRM |
| `mapToApplication` | `integrationField` | `applicationField.value` | their CRM record, handed back to your application |

Parameters, identical on both:

- `objectName` — required. Matches the `objectName` on a `FIELD_MAPPING` workflow input.
- `inputType` — required, `OBJECT` or `ARRAY`. Selects which `data` property is shown, exactly as
  `data-mapper`'s `mapObjectsToObject` does with its own `inputType` and `displayCondition`s. The
  component DSL has no single property that accepts either an object or a list, and this is the
  established way around that.
- `data` — required. Declared twice, `object(DATA)` and `array(DATA)`, each gated on `inputType`.
- `includeUnmapped` — optional, default `false`.

Output: an object when `inputType` is `OBJECT`, an array when it is `ARRAY`. The actions declare
`.output()` with no output function — the output schema comes from the editor test run, see
Section 7.

### Resolution

Entirely inside the action, using the existing context-aware seam. `ActionContextAware extends
JobContextAware`, and `JobContextAware.getJobPrincipalId()` is documented as "a project-deployment
id under `PlatformType.AUTOMATION`, an integration-instance id under `PlatformType.EMBEDDED`".
Casting an action context to a platform-side `*ContextAware` interface is an established pattern —
`AssetFileContextResolver`, `InfobipMakeCallAction` and `DataStreamStreamActionDefinition` all do
it. **No new platform plumbing is required.**

The resolver has two branches, chosen by `isEditorEnvironment()`, and both end in the same
descriptor shape:

```
(ActionContextAware) context
  -> getWorkflowId()      -> WorkflowInput.of(workflow), find the input whose getObjectName()
                             equals objectName                                    [both branches]

  runtime  (isEditorEnvironment() == false)
    -> getPlatformType()   must be EMBEDDED, else fail
    -> getJobPrincipalId() = the integration instance id
    -> integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(instanceId, workflowId)
         .inputs[inputName]                                     = the descriptor

  editor   (isEditorEnvironment() == true; jobPrincipalId is null here)
    -> getEnvironmentId()
    -> workflowTestConfigurationService.getWorkflowTestConfigurationInputs(workflowId, environmentId)
         [inputName] -> parse -> <objectName>.sampleMapping     = the descriptor
```

The editor branch exists because the workflow editor's Test button runs the action with no
connected user: `TestWorkflowExecutorImpl` stamps `EDITOR_ENVIRONMENT=true` onto the job metadata,
`AbstractTaskHandler` passes it through to `executePerform`, and `JOB_PRINCIPAL_ID` is absent. The
input's design-time test value therefore gains an optional `sampleMapping` key (Section 8) — a
descriptor the author writes once so the action can be tested and its output pills produced.

Failure modes, all explicit rather than silent:

- `getWorkflowId()` is null → fail: the action needs a workflow execution context.
- No workflow input declares that `objectName` → fail, listing the object names the workflow does
  declare.
- Runtime: platform type is not `EMBEDDED` → fail, naming the component as embedded-only.
- Runtime: no per-user row, no value under the input name, or a descriptor whose `mappings` is
  empty → fail, saying the connected user has not completed the mapping. (Not "return the payload
  unchanged" — that would write unmapped keys into a third-party system.)
- Editor: the test value has no `sampleMapping` → fail, telling the author to add one to the
  input's test value.

Because the runtime branch reads through the instance resolved from `getJobPrincipalId()`, a job
can only ever see its own connected user's mapping.

### Transform semantics

Following `FieldMapperItemProcessor` where the descriptor allows, so the platform's two mappers
behave alike:

- **Unmapped source keys are dropped** by default. A source key is "unmapped" when no mapping's
  source path starts with it. `includeUnmapped: true` copies every unmapped top-level key through
  untouched.
- **A missing source key omits the destination key entirely** — not `null`. A null written into a
  CRM field is a destructive update, and must not be the default for an absent key. (The descriptor
  carries no per-mapping default value, so unlike `FieldMapperItemProcessor` there is no default
  to fall back to.)
- **Dotted paths** on either side resolve nested structures. The component carries a small split-on-dot path helper (`FieldMappingPaths`) for both sides, mirroring
  `ClusterElementContextImpl`'s nested implementation. (`Context.nested(...)` is declared only on
  `ClusterElementContext`, so it is not available to an action, and `MapUtils`' JsonPath-backed readers
  would add a second path dialect.)
- **Lists map element-wise** and return a list. This is Paragon's
  `mapIntegrationObjects(fieldMapping, records)` case.
- **`mapToApplication` is not required to be the exact inverse of `mapToIntegration`.** The Connect
  Portal permits two application fields to target one integration field. Mapping back, the last
  declared mapping wins — deterministic by declaration order, and stated here because it would
  otherwise be discovered in production.

The transform itself lives inside this component module. It has one caller, so it needs no home in
core; if a second consumer ever appears (a trigger-level auto-apply, say), extracting it is the
first step of that work.

## 6. Surface 3 — Consumption from a Code step

No new mechanism. The Script component's `context` already exposes a component proxy,
`context.component.<componentName>.<actionName>(input, connectionName?)` — implemented by
`ContextProxyObject` -> `ComponentProxyObject` -> `ActionProxyObject`, dispatched through
`PolyglotComponentActionInvoker`, and shown in every script action's default template as
`context.component.httpClient.get(...)`.

So the new actions are callable from inside code:

```js
function perform(input, context) {
    const mapped = context.component.fieldMapping.mapToApplication({
        objectName: "Contacts",
        data: input.records
    });

    return mapped.filter((record) => record.email);
}
```

This is ByteChef's equivalent of Paragon's `paragonUtils.mapIntegrationObjects(fieldMapping, records)`,
with two advantages: it works in JavaScript, Python, Ruby and Java rather than JavaScript alone, and
it is the *same* action the canvas node runs, so the two cannot drift.

The descriptor itself is also readable in code as `${input.<name>}` once Section 4 lands, for a
script that wants to inspect or filter the mapping rather than apply it.

## 7. Surface 4 — Design-time data pills

A `FIELD_MAPPING` input renders as **one opaque pill** representing the descriptor. The synthetic
per-application-field children produced today by `getFieldMappingPillProperties.ts` are removed,
together with that utility, because the path they advertise does not exist at runtime.

Those per-field pills move to where they are true: the **action node's output**, through the
ordinary editor flow. The author presses Test; the action runs in the editor environment, resolves
the input's `sampleMapping` (Section 5) and produces a real mapped object; that sample output is
what the data-pill panel expands into one pill per mapped key. No dynamic output function is
declared, for a concrete reason: `ActionDefinitionServiceImpl.executeOutput` builds its context with
`createActionContext(..., null, null, null, null, null, connection, null, null, true)` — every
identity field null — so an output function could not find the workflow input to derive anything
from.

Because the embedded workflow builder reuses the shared `DataPillPanel`, this lands in the embedded
builder automatically.

## 8. Data shapes (reference)

**The descriptor**, as `FieldMappingField` already emits it and as it is stored in
`IntegrationInstanceWorkflow.inputs[<inputName>]`:

```json
{
  "objectType": "contacts",
  "mappings": [
    {"applicationField": {"label": "Title", "value": "title", "custom": false}, "integrationField": "first_name"},
    {"applicationField": {"label": "Priority", "value": "priority", "custom": true}, "integrationField": "hs_priority"}
  ]
}
```

**The input's design-time test value**, unchanged from the 2026-06-04 design except for the new,
optional `sampleMapping` — a descriptor of exactly the shape above:

```json
{
  "Contacts": {
    "objectTypes": [{"label": "Contacts", "value": "contacts"}],
    "integrationFields": [{"label": "First Name", "value": "first_name"}, {"label": "Priority", "value": "hs_priority"}],
    "applicationFields": {"fields": [{"label": "Title", "value": "title"}, {"label": "Priority", "value": "priority"}]},
    "sampleMapping": {
      "objectType": "contacts",
      "mappings": [
        {"applicationField": {"label": "Title", "value": "title", "custom": false}, "integrationField": "first_name"},
        {"applicationField": {"label": "Priority", "value": "priority", "custom": false}, "integrationField": "hs_priority"}
      ]
    }
  }
}
```

The test value is stored as a string; `deriveObjectName.ts` and the server-side resolver both
accept it with or without the `mapObjectFields` envelope, as before.

**`mapToIntegration`** — `{title: "Dr", priority: "high"}` becomes
`{first_name: "Dr", hs_priority: "high"}`.

**`mapToApplication`** — `{first_name: "Dr", hs_priority: "high", lifecyclestage: "lead"}` becomes
`{title: "Dr", priority: "high"}` by default, or additionally `lifecyclestage: "lead"` with
`includeUnmapped: true`.

**Input definition attribute:** `type = "field_mapping"` (the REST layer upper-cases it to
`InputTypeModel.FIELD_MAPPING`), `objectName` (derived from the test-value top-level key at save
time — unchanged from the 2026-06-04 design).

## 9. Testing strategy

**JUnit — transform:** both directions; `includeUnmapped` on and off; a missing source key
(asserting the destination key is omitted, not nulled); dotted paths on each side; an object payload
and a list payload; two application fields targeting one integration field, asserting
last-declared-wins on the way back.

**JUnit — resolution:** an unknown `objectName` fails and names the available object names; a null
workflow id fails; at runtime, a non-`EMBEDDED` platform type fails, a missing per-user row fails,
and an empty `mappings` list fails rather than passing the payload through; in the editor, a test
value without `sampleMapping` fails with guidance and one with it resolves; a mapping saved by
connected user A is never visible to a job running for user B.

**JUnit — definition snapshot:** `definition/field-mapping_v1.json` through `JsonFileAssert`, the
way `AppEventComponentHandlerTest` does it (constructing the handler with null collaborators).

**JUnit — accessor:** `getInputMap` merges configuration and per-user inputs with per-user winning;
a workflow with no per-user row still returns the configuration inputs. The existing
`testGetInputMapUsesConfigurationIdNotInstanceId` keeps its assertion once the new collaborator is
stubbed to `Optional.empty()`.

**Vitest:** a `FIELD_MAPPING` input renders a single pill and no synthetic children; other input
types are unaffected. `getFieldMappingPillProperties.test.ts` goes with its subject.

EE files carry the ByteChef Enterprise license header and the `@version ee` Javadoc tag.

## 10. Risks

- **The `input.*` merge changes existing behavior** for any embedded workflow where a configuration
  input and a per-user input share a name. The per-user value now wins where the configuration value
  used to. Scan existing embedded workflows for name collisions before landing.
- **Distributed EE.** The merge lives in `embedded-configuration-instance-impl`. Confirm which
  distributed apps carry that module, given the established pattern where an app assembles a
  different classpath than the monolith and a bean simply never registers.
- **Constructor collaborator.** `IntegrationJobPrincipalAccessor` gains
  `IntegrationInstanceWorkflowService`. Its only hand-constructed site is its own unit test — no
  `*IntTestConfiguration` assembles it — so the usual missing-bean trap does not apply here, but the
  sweep was done and should be redone if the constructor changes again.
- **The build-time component index** was considered and is *not* a risk for this component: Spring
  handlers are resolved ahead of the index (Section 5). Recorded so nobody re-derives it.

## 11. Open questions and future work

- **Trigger-level auto-apply** (Paragon's `originalPayload` + `mappedApplicationObject` on the
  trigger) — deferred, own spec, layered on this component's transform.
- **Whether `mapToApplication` should offer a strict mode** that fails on an unmapped required
  application field, rather than omitting it.
- **Docs.** `docs/content/docs/platform/embedded/build/workflows/field-mapping.mdx` currently says
  "ByteChef stores the mapping but does **not** transform any payload with it" and, in its Scope callout,
  excludes "any built-in runtime transform". Both become false with this design and are updated as part
  of it. The same page's warning that the Edit Input dialog "does not carry a Field Mapping type" is
  already contradicted by `WorkflowInputsEditDialog.tsx`, which renders a field-mapping JSON editor;
  it is corrected in the same pass.
- **Paginated / searchable dropdowns** for large object and field lists — still deferred from the
  2026-06-04 design, and unchanged by this one.
