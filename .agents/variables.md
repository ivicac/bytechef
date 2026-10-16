# Variables (workspace / embedded organization, EE)

`server/ee/libs/platform/platform-variable/` (`-api`/`-service`/`-graphql`). One `Property` row per variable:
key `variable.<NAME>`, value `{"value": …}`, `Scope.WORKSPACE`/workspaceId (automation) or `Scope.EMBEDDED`/null
(embedded — its first use), `environment` always set; listed via `PropertyService.getPropertiesByKeyPrefix`. No
new table — variables reuse the existing `property` table. One changelog exists:
`20260825000001_platform_configuration_property_unique_null_scope_id.xml` adds a partial unique index on
`property (key, scope, environment) WHERE scope_id IS NULL`, because `uk_property_key_scope_scope_id_environment`
(non-partial, includes `scope_id`) never fires for `Scope.EMBEDDED` rows — Postgres treats every NULL `scope_id`
as distinct, so two concurrent embedded creates of the same name both inserted before this index existed.
`VariableServiceImpl.create`/`update` catch the resulting `DataIntegrityViolationException` and translate it to
`VARIABLE_NAME_ALREADY_EXISTS`. `VariableServiceImpl` re-lists the scope on every by-id operation rather than
trusting the id alone, so a variable id from another scope is indistinguishable from a missing one — ids never
leak across scopes.
Name/value validation (`^[A-Za-z_][A-Za-z0-9_]{0,49}$`, 4096-char value cap) is a static, unconditional
`VariableNameValidator`, deliberately not a Spring bean so it can't be silently disabled by a conditional.

Runtime: `PrincipalJobFacadeImpl` (all four create methods) and `TestWorkflowExecutorImpl` seed the resolved map
into `Job.inputs` under `JobInputConstants.VARIABLES_INPUT` (`"vars"`) through the CE `WorkflowVariablesResolver`
seam (`platform-api`, resolved via `ObjectProvider#getIfAvailable`), implemented by `WorkflowVariablesResolverImpl`
— fail-open, WARN once per JVM on a genuine resolution failure. That WARN is real defence-in-depth (it fires if the
resolver bean exists and the store it reaches misbehaves), but it does **not** cover the actual distributed-EE gap:
only `server-app` and `configuration-app` carry `platform-variable-*`, and of the distributed apps only
`execution-app` runs `PrincipalJobFacadeImpl` at all (`coordinator-app`/`webhook-app`/`connection-app`/
`scheduler-app` depend on `platform-workflow-execution-remote-client`, not the `-service` module that hosts the
facade). `execution-app` has no `platform-variable-*` on its classpath, so `WorkflowVariablesResolverImpl` never
registers there; `getIfAvailable()` simply returns null, `Job.inputs` is left untouched, and nothing is ever
attempted — so no WARN is ever logged for this case. Because `Job.inputs` IS the initial workflow context, this
gives snapshot semantics for free at the top level: a top-level run resolves `vars` once at job creation and keeps
that same map for its whole lifetime, so editing a variable mid-run never affects an already-created job's own
inputs. `createChildJob` re-runs the seeding for subflows, though, so a subflow started mid-run resolves its own
`vars` snapshot afresh and can observe a value edited after the parent run started. Scope comes from per-`PlatformType`
`VariableScopeProvider`s (`ProjectVariableScopeProvider` in EE automation-configuration-service,
`IntegrationVariableScopeProvider` in embedded-configuration-instance-impl).
Editor previews get `vars` through `WorkflowEvaluationInputsFacade` — the ONE place that merges test-configuration
inputs with `vars`; never call `getWorkflowTestConfigurationInputs` directly from a preview facade. `vars` is
reserved as a workflow input name and a node name (`WorkflowValidatorFacade` + client) — this guards the top-level
`vars.*` namespace in the flat execution context, not individual variable names; a variable literally named `vars`
is fine and resolves as `${vars.vars}`.

Variables are **not secrets**: values are shown in clear on the settings page, in the editor's data-pill panel, and
(via `Job.inputs`) on the execution detail page of any run that used them. Storage is encrypted at rest only because
`Property.value` always is — that's incidental, not a security boundary. There is no `set` action; variables are
read-only from workflows.

Client: CE editor reads variables through `shared/edition/variables/variablesApi.ts`; pages
`ee/pages/settings/{automation,embedded}/variables` (routes `/automation/settings/variables` under the "Current
Workspace" nav group, member-or-admin-reachable but mutation controls gated on the `VARIABLE_MANAGE` scope; and
`/embedded/settings/variables`, admin-only), shared `ee/shared/components/variables`. The workflow editor surfaces
variables as their own **Variables** section in the Data Pill Panel (`DataPillPanelBodyVariablesItem`), fed by
`getWorkflowInputAndVariableDataPills`/`useWorkflowVariables`.

Spec: `docs/superpowers/specs/2026-08-17-custom-variables-design.md`.
