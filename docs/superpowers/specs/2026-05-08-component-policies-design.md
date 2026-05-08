# Component Policies — Design Spec

**Date:** 2026-05-08
**Status:** Draft
**Edition:** EE

**Summary:** Admin governance over which components, actions, and triggers may be used inside workflows. A workspace admin defines an *allow/block* policy; ByteChef enforces it in the workflow editor (what users can drag in), at workflow save/validation (what can be persisted), and at task dispatch (what can actually run). Policy decisions and changes are emitted to the existing audit log.

---

## 1. Why

ByteChef ships with 189 components covering CRM, communication, code execution, file storage, AI/ML, and databases. In larger deployments, organizations need to constrain that surface area:

- **Compliance** — block components whose vendors are outside a permitted data-residency or processor list.
- **Security** — block code-execution and unrestricted-HTTP components for non-engineering workspaces; restrict file-egress connectors.
- **Cost / fit** — block components that overlap with sanctioned vendors (e.g. force "Slack" instead of two competing chat connectors).
- **Risk hygiene** — block specific *actions* within an otherwise allowed component (e.g. allow Gmail read but not delete; allow GitHub issue ops but not repo delete).

There is currently no policy primitive in ByteChef. Connection visibility (PRIVATE/WORKSPACE/PROJECT/ORGANIZATION) controls *who can use a configured connection*, not *which components are admissible at all*. This spec adds that missing primitive.

Out of scope: connection-auth-method restrictions ("OAuth only, no API key"), per-input-field data-mask policies, marketplace-style approval queues. Forward compatibility for these is called out where relevant.

---

## 2. Scope

### Phase 1 (this spec)
- Workspace-scoped policy with a default decision (`ALLOW_ALL` or `DENY_ALL`) and a list of override rules.
- Rule granularity: component, component+action, component+trigger.
- Enforcement at three points: workflow editor component picker, workflow save validation, workflow execution dispatch.
- Settings page UI for managing the policy.
- Audit events on policy CRUD and on enforcement decisions that result in BLOCK.
- Metrics counter for policy decisions.

### Phase 2 (later)
- Organization-scoped policy that pre-empts workspace policies (cross-workspace baseline).
- Project-scoped overrides for narrower exemptions.
- `REQUIRE_APPROVAL` decision with an approval queue.
- Component risk classification (LOW/MEDIUM/HIGH) + bulk policy templates ("block all HIGH risk").

### Phase 3 (later)
- Connection auth-method restrictions (`OAUTH2_REQUIRED`, `NO_API_KEY`, etc).
- Per-input-field policies (e.g. block specific Slack channels by literal value, block PII fields without masking).
- Custom-component allow/block (separate object surface today).

---

## 3. Data Model

### 3.1 `ComponentPolicy` (`@Table("component_policy")`)
```
Long       id                  // @Id
Long       workspaceId         // direct column (automation package — keeps workspace_id directly)
String     name                // human label, e.g. "Acme baseline"
String     description         // nullable
short      defaultDecision     // ComponentPolicyDecision ordinal (ALLOW=0, BLOCK=1)
boolean    enabled             // master switch; false = bypass enforcement
Set<ComponentPolicyRule> rules // @MappedCollection

Instant    createdDate
String     createdBy
Instant    lastModifiedDate
String     lastModifiedBy
int        version
```
Unique index on `workspace_id` — Phase 1 is one-policy-per-workspace. Phase 2 lifts this when org/project scopes are added.

### 3.2 `ComponentPolicyRule` (`@Table("component_policy_rule")`)
```
Long       id                  // @Id
Long       componentPolicyId   // parent FK
String     componentName       // e.g. "googleSheets"
Integer    componentVersion    // nullable = "any version"
short      ruleScope           // ComponentPolicyRuleScope ordinal
                               //   COMPONENT(0) — entire component
                               //   ACTION(1)    — single action within the component
                               //   TRIGGER(2)   — single trigger within the component
String     operationName       // nullable when ruleScope=COMPONENT;
                               // required for ACTION/TRIGGER, e.g. "sendEmail"
short      decision            // ComponentPolicyDecision ordinal
String     reason              // nullable; surfaced to users on block

Instant    createdDate, lastModifiedDate, ...
```
Composite unique index on `(component_policy_id, component_name, component_version, rule_scope, operation_name)` — one rule per (component, version, scope, op) tuple.

### 3.3 Enums (INT ordinal storage per project convention; append-only)
- `ComponentPolicyDecision`: `ALLOW(0)`, `BLOCK(1)`. (Phase 2 appends `REQUIRE_APPROVAL(2)`.)
- `ComponentPolicyRuleScope`: `COMPONENT(0)`, `ACTION(1)`, `TRIGGER(2)`. (Phase 3 appends `CONNECTION_AUTH_METHOD(3)`.)

Pinned by `EnumOrdinalStabilityTest` per established pattern.

### 3.4 Liquibase changelog `component_policy.xml`
- `component_policy` table, all columns above.
- `component_policy_rule` table with FK to parent.
- Indexes:
  - Unique `component_policy(workspace_id)` (Phase 1 only).
  - Unique `component_policy_rule(component_policy_id, component_name, component_version, rule_scope, operation_name)`.
  - Lookup `component_policy_rule(component_policy_id)`.
- Init migration only — no follow-up rename migrations (per the workspace-refactor lessons).

---

## 4. Module Wiring

### 4.1 New EE modules under `server/ee/libs/automation/automation-component-policy/`
```
automation-component-policy-api          // ComponentPolicyService, DTOs, enums
automation-component-policy-service      // JDBC repo, service impl, audit emit
automation-component-policy-graphql      // GraphQL schema + resolvers
automation-component-policy-evaluator    // PolicyEvaluator — pure decision logic
```
Placement rationale: governance feature, EE-only (mirrors connection visibility, audit). Rule-evaluator split out so editor / save / execute paths share one decision implementation without dragging in JDBC.

### 4.2 Service surface (`automation-component-policy-api`)
```java
ComponentPolicyService
  fetchByWorkspaceId(Long workspaceId): Optional<ComponentPolicy>
  create(ComponentPolicy policy): ComponentPolicy
  update(ComponentPolicy policy): ComponentPolicy
  delete(Long id): void

ComponentPolicyEvaluator
  // Pure: takes a policy snapshot + operation reference, returns a decision.
  evaluate(
      ComponentPolicy policy,
      String componentName,
      Integer componentVersion,
      ComponentPolicyRuleScope scope,
      String operationName
  ): ComponentPolicyDecisionResult

ComponentPolicyDecisionResult
  decision: ComponentPolicyDecision
  matchedRuleId: Long?      // null when default decision applies
  reason: String?           // from matched rule, null on default
```

### 4.3 Enforcement points

**Editor (component picker filter).** New GraphQL field on the existing component-discovery query: each `Component`/`Action`/`Trigger` returned to the editor carries a `policyDecision { decision, reason }` object. The frontend hides BLOCKed components/operations from the picker drawer and shows BLOCKed-as-disabled rows in the search results with a tooltip reason. (Hide-by-default, surface-on-search keeps drawer compact while staying discoverable.)

**Save (workflow validation).** `WorkflowFacade` validation hook: walk every workflow task and trigger reference, evaluate the policy, reject save with a structured error listing each violation. Wired in alongside the existing schema/connection validation in workflow save flow.

**Execute (task dispatch).** `TaskDispatcher` precheck (or a `PreSendProcessor` per the task-dispatchers convention): re-evaluate the policy at dispatch time so policy changes after save are honored. On BLOCK, fail the job with `JOB_FAILED` and a structured `errorCode=COMPONENT_POLICY_VIOLATION`, surfacing the rule reason.

The three enforcement points share `ComponentPolicyEvaluator` so a rule edit produces consistent decisions across editor / save / execute.

### 4.4 GraphQL schema (`component-policy.graphqls`)
```graphql
extend type Query {
  componentPolicy(workspaceId: ID!): ComponentPolicy
}

extend type Mutation {
  createComponentPolicy(workspaceId: ID!, input: ComponentPolicyInput!): ComponentPolicy
  updateComponentPolicy(id: ID!, input: ComponentPolicyInput!): ComponentPolicy
  deleteComponentPolicy(id: ID!): Boolean

  addComponentPolicyRule(componentPolicyId: ID!, input: ComponentPolicyRuleInput!): ComponentPolicyRule
  updateComponentPolicyRule(id: ID!, input: ComponentPolicyRuleInput!): ComponentPolicyRule
  removeComponentPolicyRule(id: ID!): Boolean
}

type ComponentPolicy {
  id: ID!
  workspaceId: ID!
  name: String!
  description: String
  defaultDecision: ComponentPolicyDecision!
  enabled: Boolean!
  rules: [ComponentPolicyRule!]!
  createdDate: Long
  lastModifiedDate: Long
  version: Int
}

type ComponentPolicyRule {
  id: ID!
  componentName: String!
  componentVersion: Int
  ruleScope: ComponentPolicyRuleScope!
  operationName: String
  decision: ComponentPolicyDecision!
  reason: String
}

enum ComponentPolicyDecision { ALLOW BLOCK }
enum ComponentPolicyRuleScope { COMPONENT ACTION TRIGGER }
```

GraphQL conventions: enum values SCREAMING_SNAKE_CASE, mutations `@PreAuthorize("hasRole('ROLE_ADMIN')")` at workspace scope.

---

## 5. Decision Algorithm

Given an operation reference `(componentName, componentVersion, scope, operationName)`:

1. Fetch policy by workspace. If absent or `enabled=false` → `ALLOW`, `matchedRuleId=null`, default-of-no-policy reason.
2. Iterate rules in this priority order, returning the first match:
   1. Exact `(component, version, scope=ACTION|TRIGGER, operation)` match.
   2. Exact `(component, version, scope=COMPONENT)` match — covers all operations of that version.
   3. Wildcard-version `(component, version=null, scope=ACTION|TRIGGER, operation)` match.
   4. Wildcard-version `(component, version=null, scope=COMPONENT)` match.
3. No rule matched → fall back to `policy.defaultDecision`.

`reason` propagates from the matched rule. On default-decision fallback, `reason` is null and the UI shows a generic "blocked by workspace default" or "permitted by workspace default".

The order is deliberate: more specific (operation-level) wins over component-level; exact version wins over wildcard. This lets an admin block "GitHub v3 deleteRepo" specifically while still allowing the rest of GitHub v3, or block all of GitHub v2 while allowing v3.

---

## 6. Settings UI

New panel under workspace settings → **Component policies**. Sections:

1. **Status** — toggle for `enabled`, dropdown for `defaultDecision`. Banner explains current effective behavior in plain English ("All components allowed unless explicitly blocked below").
2. **Rules table** — columns: Component, Version, Scope, Operation, Decision, Reason, Actions. Filter by component name and decision. Inline edit & delete. Bulk add.
3. **Add-rule dialog** — component picker (uses existing component definition catalog), version select (with "any" option), scope radio (component / action / trigger), operation picker filtered to the chosen component+scope, decision radio, optional reason.
4. **Effects preview** — count and list of currently saved workflows that would fail validation under the proposed policy *before* the admin saves changes (read-only static check, scoped to the workspace).

CE behavior: panel is hidden in CE builds. EE-only feature, parallel to connection-visibility EE gating.

---

## 7. Audit Events

Reuse `platform-audit` (EE). New event types appended to the existing enum:

- `COMPONENT_POLICY_CREATED`, `COMPONENT_POLICY_UPDATED`, `COMPONENT_POLICY_DELETED`
- `COMPONENT_POLICY_RULE_ADDED`, `COMPONENT_POLICY_RULE_UPDATED`, `COMPONENT_POLICY_RULE_REMOVED`
- `COMPONENT_POLICY_VIOLATION_BLOCKED` — emitted at save and execute enforcement points when a BLOCK decision is reached. Payload: `{ workspaceId, workflowId?, jobId?, componentName, componentVersion, scope, operationName, matchedRuleId, enforcementPoint }`.

Editor-time decisions are NOT audited individually (high cardinality, no actionable state change). Only save and execute decisions persist to audit.

---

## 8. Metrics

- `bytechef_component_policy_decision` (Counter)
  - Tags: `decision` (`ALLOW`/`BLOCK`), `enforcement_point` (`EDITOR`/`SAVE`/`EXECUTE`), `scope` (`COMPONENT`/`ACTION`/`TRIGGER`).
  - Workspace tag deliberately omitted in the global counter; a sibling `bytechef_component_policy_decision_by_workspace` counter with the workspace tag is opt-in for deployments with bounded workspace counts (mirrors the workflow-chat metric pattern).
- `bytechef_component_policy_rule_count` (Gauge per workspace) — tracks growth, surfaces rule-set bloat.

Wired via `ObjectProvider<MeterRegistry>` so lightweight EE app variants without actuator continue to start.

---

## 9. Migration & Backfill

- No automatic backfill. Existing workspaces start with **no policy** = `ALLOW_ALL`, preserving current behavior.
- Add a "Recommended baseline" template button in the settings UI that pre-fills a sensible default rule set (block code-execution, file-system, and unrestricted-HTTP components). Admin reviews and saves; nothing is enforced until they do.

---

## 10. Edge Cases

- **Policy disabled mid-execution.** Editor and save use a fresh policy fetch each call. Execute precheck reads at dispatch; in-flight tasks already past the dispatch gate complete normally. Documented behavior, not an error.
- **Component installed/upgraded after rule write.** Wildcard-version rules continue matching new versions. Exact-version rules don't — admin sees an "outdated rule" hint when the targeted version no longer exists.
- **Custom components.** Out of Phase 1; treated as ALLOW until Phase 3. Settings UI shows "Custom components: not yet covered by policy" so admins aren't misled about scope.
- **Triggered by webhook / schedule from outside the editor.** Same execution-point check applies; the trigger task is rejected with the same error code.
- **Embedded edition.** Per-tenant policies are out of scope here — embedded already has its own per-integration enablement at the integration level. Phase 1 is automation-only; no `automation-component-policy` modules are wired into embedded apps.
- **Concurrent edits.** Standard Spring Data JDBC optimistic locking via `version` column on both tables; conflicting saves return a structured "policy was modified" error and the UI refetches.

---

## 11. Forward Compatibility

- **REQUIRE_APPROVAL.** Append to `ComponentPolicyDecision` enum. New `component_policy_approval_request` table holds requests. Save-time enforcement creates a request and parks the workflow as "draft pending approval".
- **Org and project scopes.** Add `organization_id` and `project_id` columns (nullable) to `component_policy`; drop the `unique(workspace_id)` constraint and replace with three partial unique indexes by scope. Evaluator gains a "merge in priority" step (project > workspace > organization > built-in default).
- **Risk classification.** Add `risk_level` to component definitions; expose in GraphQL. Settings UI gains "Block all HIGH risk" bulk action that materializes individual COMPONENT-scope rules.
- **Connection auth-method scope.** Append to `ComponentPolicyRuleScope`. Evaluator gets an extra parameter `connectionAuthType`; enforcement adds a hook in connection-creation flow.

---

## 12. Test Plan

- **Unit (`automation-component-policy-evaluator`)** — covers the priority order in §5: exact-op vs component, exact-version vs wildcard, default fallback when policy disabled, default fallback when policy absent. Targets ≥95% line coverage on the evaluator since it's pure and load-bearing.
- **Service** — JDBC repo + service via Testcontainers (`@SpringBootTest` + `IntTest` suffix). Covers CRUD, optimistic locking, cascade delete of rules.
- **GraphQL** — admin-role enforcement (mutations rejected for non-admin), input validation, version conflict surfacing.
- **Editor integration** — frontend Vitest snapshot of the picker drawer with a mocked policy that blocks a specific action; verify hidden-by-default, surfaced-on-search-disabled.
- **Save validation** — integration test saving a workflow that references a blocked action; assert structured error and that no row is written.
- **Execute enforcement** — integration test where policy changes between save and dispatch; assert job fails with `COMPONENT_POLICY_VIOLATION` errorCode and audit event is emitted.
- **EnumOrdinalStabilityTest** — extended to pin `ComponentPolicyDecision` and `ComponentPolicyRuleScope`.

---

## 13. Open Questions

1. **Default-deny + empty rule list.** Should the UI prevent saving a `DENY_ALL` policy with zero ALLOW rules (which would block every workflow), or allow it with a strong confirmation? Proposal: allow but require typed confirmation, since it's a legitimate "lockdown until I add exceptions" workflow.
2. **Effects-preview cost.** Walking every workflow in the workspace on policy edit could be expensive in workspaces with thousands of workflows. Proposal: cap preview at 200 workflows, expose count of "additional workflows not shown" — defer full enumeration to the Phase 2 approval-queue work.
3. **Rule reason maximum length.** UI shows reason on block in the workflow editor — bound at 280 chars to keep tooltips readable.
4. **Cross-edition publish path.** When a workflow saved in EE (with policy enforcement) is later loaded in a CE deployment, how should references to currently-blocked components render? Proposal: CE ignores policy entirely (it's an EE table); workflows continue to load. Document as "policies don't travel".

---

## 14. Authoring Policies via Copilot

Admins should be able to read, write, and preview the policy through the existing Copilot, not only through the Settings UI. This piggybacks on ByteChef's Spring AI tool-callback pattern (same shape as `CreateWorkspaceFileToolCallback` and the personal-agent overlay) so Copilot-driven changes flow through the same service, role check, and audit pipeline as UI/GraphQL edits.

### 14.1 Module placement
New EE module: `server/ee/libs/automation/automation-component-policy/automation-component-policy-ai-service`. Houses the tool callbacks. The agent registration itself stays in `ai-copilot-app` alongside the other agents (Files, Workflow, Personal). CE builds omit the AI module entirely — Copilot-authored policies are EE-only.

### 14.2 Tool surface

| Tool | Kind | Purpose |
|---|---|---|
| `ListComponentsForPolicyTool` | read | Search components by name/category — grounds the LLM with valid `componentName` values before any write. |
| `ListComponentOperationsTool` | read | Enumerate actions/triggers for a component+version, filtered by `scope`. |
| `GetComponentPolicyTool` | read | Return current policy + rules for the active workspace. |
| `AddComponentPolicyRuleTool` | write | Append a rule. |
| `UpdateComponentPolicyRuleTool` | write | Change `decision`/`reason` of an existing rule. |
| `RemoveComponentPolicyRuleTool` | write | Delete a rule. |
| `SetComponentPolicyDefaultTool` | write | Flip `defaultDecision` or toggle `enabled`. |
| `PreviewComponentPolicyEffectsTool` | read | Run §6 effects-preview against current workflows; returns the same capped result the UI shows (≤200 entries + overflow count). |

All tools delegate to `ComponentPolicyService`/`ComponentPolicyEvaluator` from §4 — no parallel write path. The admin `@PreAuthorize` check fires in the service; tool callbacks do not bypass it. Optimistic locking via `version` is honored; conflicts surface as a structured tool-result error the LLM can describe.

### 14.3 Routing & visibility

**Option chosen:** dynamic toolset inclusion in `CommandCenterRoutingAgent` based on caller role. The policy tools are registered to the routing agent but the agent's tool-resolution step omits them when the caller lacks `ROLE_ADMIN`. This keeps non-admins from seeing or attempting policy writes (they get an LLM "I can't do that here" response rather than an exception trace), while admins can author policies from the standard Copilot panel without switching to a dedicated agent.

Rejected alternative: a built-in "Policies" personal agent. Cleaner isolation, but forces admins through an extra navigation step and complicates the workspace-overlay surface.

### 14.4 Confirmation pattern for writes
Write tools (`Add`/`Update`/`Remove`/`SetDefault`) follow the same human-in-the-loop pattern guardrails already use: the LLM is instructed (in the routing-agent system prompt addition) to **always** call `PreviewComponentPolicyEffectsTool` first when the proposed change could affect saved workflows, summarize the impact, and explicitly ask the admin to confirm before invoking the write tool. The tools themselves don't enforce this (the LLM does), but the audit log + metric `source=COPILOT` tag makes any "did Copilot skip the confirm" question forensically answerable.

### 14.5 Audit & metrics
- Reuse the §7 audit events. Copilot-driven writes emit identical `COMPONENT_POLICY_*` events; the actor is the human admin (the agent runs under their session), so `created_by` / `last_modified_by` reflect that admin.
- Extend §8 metrics with a `source` tag on `bytechef_component_policy_decision_change` (a new sibling counter incremented on rule mutations): `source=UI|GRAPHQL|COPILOT`. Lets ops compare adoption of the Copilot path over time without leaking it into the high-volume per-decision counter.

### 14.6 Test plan additions
- **Tool unit** — each tool callback with a mocked `ComponentPolicyService`. Verifies role check propagation, optimistic-locking error mapping, structured result schema.
- **Routing-agent integration** — `@SpringBootTest` with a stub `LLM` that programmatically calls each tool; verifies admin-only inclusion (non-admin caller does not see the tools in the prompt's tool list) by snapshotting the resolved toolset.
- **Audit equivalence** — same workflow change applied via UI, GraphQL, and Copilot tool path; assert the resulting `audit_event` rows are identical except for `source` and `actor_session_id`.
- **EE-only wiring** — `@ConditionalOnEEVersion` on the AI module; CE integration test asserts the tool beans are absent and routing agent's toolset omits them.

### 14.7 Edge cases specific to this surface
- **Unknown componentName from LLM hallucination.** All write tools validate `componentName` against the live component definition catalog before persisting; unknown names produce a structured error the LLM can recover from rather than writing a rule that will silently never match.
- **Stale rule ID.** `UpdateComponentPolicyRuleTool` and `RemoveComponentPolicyRuleTool` accept rule IDs the LLM read in a prior tool call. If the rule was deleted concurrently, the service returns 404 → tool returns a structured "rule no longer exists" error.
- **Bulk requests.** "Block all HIGH-risk components" type requests defer to Phase 2 (risk classification doesn't exist yet). Until then, the LLM expands such requests into individual `AddComponentPolicyRuleTool` calls with admin confirmation before each batch.
- **Cross-workspace requests.** Tools resolve the workspace from the active session — they do not accept a `workspaceId` argument. An admin asking "do this for workspace X" is told to switch sessions; prevents an admin from accidentally editing the wrong workspace because the LLM misinterpreted scope.

### 14.8 Open question
Auto-confirm threshold for trivially-safe writes — e.g. `SetComponentPolicyDefaultTool` to *match the current value* (no-op), or removing a rule that affects zero saved workflows according to the preview. Proposal: never auto-confirm writes in Phase 1; revisit once Copilot usage data shows admins are routinely re-confirming obviously-safe operations. Aligns with "do not let speed of authoring erode the audit trail's value".
