<!-- Source: https://agentkey.us/ (prior art for approval-as-outcome, observe mode, fail-closed rules, risk levels) -->
# Component Rules — Tool Governance, Approval, Observe, Strict, Risk

**Date:** 2026-09-02
**Status:** Proposed
**Amended by:** `2026-09-03-component-rules-workspace-scoping-design.md`, which makes rules workspace-scoped.
This spec inherited "tenant-wide only" from its own predecessor by silence rather than by decision.
**Predecessor:** `2026-08-12-component-rules-design.md` (Component Rules v1: BLOCK/TAG rules evaluated on
workflow action executions). This spec **re-scopes** v1 and extends it. Both the v1 module
(`server/ee/libs/platform/platform-component-rule/`) and this spec live on the unmerged `0_732` branch, so the
v1 schema is unreleased and is edited in place rather than migrated.

## Problem

Component Rules v1 governs action calls that go through `ActionDefinitionFacade.executePerform` — workflow
tasks, code workflows, the editor's test-node run, the embedded execute-action API. The one caller it does
**not** govern is the AI agent: a component action attached to an agent as a TOOLS cluster element runs through
`ClusterElementDefinitionServiceImpl.doExecuteTool`, which calls the action's `perform` directly and never
reaches the rules chokepoint. That path also skips the per-operation visibility check, so per-action deny-list
policies do not apply to agent tools either.

That is backwards. A workflow task is a call a human designed; the risky calls are the ones the model chooses
at runtime. AgentKey's product — an authorization layer between an agent and its tools that allows, blocks,
or requires human approval per call, with an observe mode, fail-closed policies and per-action risk levels —
is the right frame, and ByteChef already owns every building block it needs: the rule engine, the agent's
tool-suspend protocol, the approval gate's delivery fan-out, the Approval Tasks page, the audit log.

## Goals

- Rules govern **agent tool calls only**: every `ToolCallback` the AI agent action executes, whether it wraps a
  component action, a native tool cluster element, or a tool produced dynamically by a provider element.
- Workflow action executions are **no longer** governed. The v1 chokepoint in `ActionDefinitionServiceImpl` is
  removed, not kept beside the new one.
- Three enforcement outcomes: `BLOCK`, `TAG`, and new `REQUIRE_APPROVAL`, which suspends the agent turn through
  the approval gate's own protocol and resumes through the gate's existing resume branch.
- A tenant-wide **observe mode** that evaluates every rule and enforces none, auditing what would have happened.
- A per-rule **strict** flag that makes an unevaluable condition count as a match (fail closed).
- A **risk level** on tools, declared in the SDK or inferred from the name, surfaced where rules are authored.
- Close the visibility gap on the tool path as a side effect: per-operation policies apply to tools too.
- Zero behaviour change for CE and for tenants with no rules, as in v1.

## Non-Goals (future)

- **Per-rule approval channels.** A rule approval is delivered through the agent node's gate channels when it
  has any, otherwise the chat channel, and always as an Approval Task. A channel picker on the rule is a
  follow-up.
- **Rules on non-tool cluster elements** (chat memory, RAG, guardrails, approval channels). They are not calls
  the model makes on the outside world.
- **Governing tools executed outside the ByteChef agent action** — for example a component tool invoked by the
  copilot's own test surface. Rules are agent governance; other surfaces stay as they are.
- **Trigger governance**, **structured condition editing**, **rule priority beyond the fixed precedence**, a
  **flagged-executions UI**, an **editor badge** for risk, and a **risk coverage report** — all unchanged from v1's
  non-goals or explicitly deferred here.
- **Tamper-evident audit** (signed hash chain). Separate spec if wanted.

## Design

### 1. Scope pivot and data model

`component_rule` keeps its shape with one rename and one addition:

| column | change |
| --- | --- |
| `action_name` → `tool_name` | VARCHAR(256), nullable. `null` means every tool on the component. For an action wrapped by `ComponentDsl.tool()` the tool name equals the action name, so an existing rule reads the same. |
| `strict` | BOOLEAN NOT NULL DEFAULT false — see §5. |
| `rule_action` | unchanged column; `RuleAction` gains `REQUIRE_APPROVAL = 2`, appended (ordinal storage). |

The v1 init changelog `20260831000001_component_rule_init.xml` is edited in place: the module exists only on
`0_732`, which has never been released (`git ls-tree` of the latest tag shows no `platform-component-rule`).
The rename runs through the domain (`ComponentRule.toolName`), the service (`appliesToTool`), GraphQL
(`toolName`), the client operations and the copilot tools (§10). `ComponentRuleEnumOrdinalTest` pins the new
ordinal.

Save-time validation adds: `REQUIRE_APPROVAL` + `AFTER` is rejected like `BLOCK` + `AFTER` (the call has already
run), with a new `ComponentRuleErrorType.APPROVAL_AFTER_UNSUPPORTED`.

A `ComponentRuleSettings` record (`observeMode: boolean`, `approvalExpiresInHours: int`, default `1440` = 60
days, matching the gate and the Approval action) is stored as one `Property` row per tenant under key
`component_rule_settings`, `Scope.PLATFORM` with a null `scopeId` — the shape `AiGuardrailsWorkspaceSettings`
uses for its tenant-wide row. No new table.

### 2. Enforcement point and SPI

**Where.** `ClusterElementToolCallbacks.build` (module `ai/llm`) is the one place every agent tool callback is
produced, including the children the approval gate wraps. It wraps each callback it returns in a
`RuleEnforcingToolCallback` before handing it back. The wrapper:

- reports the delegate's `ToolDefinition` as its own, so lookups by name still find the tool;
- is deliberately **not** a `DelegatingToolCallback`. `DelegatingToolCallback.unwrap` is recursive and the
  resume branch uses it to bypass the gate; the rule layer must survive that unwrap so a human-approved resume
  still passes through rule evaluation (see §3). Because the gate builds its children through `build`, the
  gate always sits outside the rule layer.

**SPI.** `ComponentRuleEnforcer` (CE, `platform-component-api`) is reshaped:

```java
record ToolCall(
    String componentName, String toolName, String toolCallName, Map<String, ?> inputParameters,
    @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId,
    @Nullable String approvedBy) {}

sealed interface Decision permits Allow, Block, RequireApproval {}
record Allow() implements Decision {}
record Block(String reason) implements Decision {}
record RequireApproval(List<Long> ruleIds, String title, String description, Instant expiresAt)
    implements Decision {}

Decision checkBeforeCall(ToolCall toolCall);
void recordAfterCall(ToolCall toolCall, @Nullable Object output);
void recordApprovalResolution(List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy);
```

- `toolName` is the cluster element name — the rule key. `toolCallName` is the Spring AI tool name the model
  invoked; for a provider element's dynamic tools the two differ, and a rule on the provider element governs
  all of them. Both are in the evaluation context, alongside `inputParameters`, `componentName`,
  `connectionId` and, for AFTER rules, `output`.
- `inputParameters` is the parsed tool-input JSON merged with the element's configured parameters, the same
  map `AiAgentToolFacade` sends to `executeTool`.
- `approvedBy` is non-null only on a human-approved re-execution (§3). With it set, a matching
  `REQUIRE_APPROVAL` rule is satisfied and audited `RULE_APPROVED`; `BLOCK` and `TAG` rules evaluate as usual.
- `checkBeforeCall` never throws. Precedence when several rules match: any `BLOCK` wins; else any
  `REQUIRE_APPROVAL` wins and the decision carries **every** matching approval rule's id, so one human decision
  satisfies all of them; else `TAG` rules are recorded and the call is allowed. Tag matches are not separately
  audited when a block or approval wins.
- `recordAfterCall` must never throw, as in v1.

**Wrapper behaviour.**

| decision | wrapper does |
| --- | --- |
| `Allow` | delegate, then `recordAfterCall` with the result |
| `Block` | return a denial JSON to the model (`{"blocked": true, "reason": …}`), mirroring the gate's `{"denied": true, "reason": …}` rejection, so the agent can replan instead of the run failing; record `RULE_BLOCKED` on the `ToolExecutionRecorder` when one is present |
| `RequireApproval` | raise an approval request (§3) and return the suspend sentinel |

**Cache.** The `ComponentRuleEnforcerImpl` cache stays keyed by `(tenantId, componentName)` with a 10 s TTL and
now holds the tenant's `ComponentRuleSettings` next to the rule list, so observe mode costs no extra query.

**Removal.** `ActionDefinitionServiceImpl` loses `checkRulesBeforePerform`, `recordRulesAfterPerform`, the
`ComponentRuleEnforcer` list and `toActionCall`; `ActionDefinitionErrorType.RULE_BLOCKED` is removed;
`ActionDefinitionServiceImplRuleTest` is deleted and replaced by wrapper tests (§Testing). The polyglot seam is
untouched by rules from now on.

**Visibility.** `ClusterElementDefinitionServiceImpl.doExecuteTool` (both overloads) gains the per-operation
visibility check the action chokepoint already runs, via `ComponentVisibilityProvider`, keyed by the element
name. Unrelated to rules in code, but it is the same hole and the same fix location.

### 3. Approval on tools

The gate already implements "pause the agent turn until a human decides". The rule wrapper reuses it rather
than adding a second mechanism.

**Shared raise helper.** `ApprovalGateToolCallback`'s raise logic — resume-URL check, form-URL derivation,
expiry, editor SSE card via the tool context emitter, production channel fan-out with best-effort per-channel
delivery, `actionContext.suspend(...)` with `GATED_TOOL_NAME` / `GATED_TOOL_INPUT` / `formUrl`, and the
`SUSPENDED_SENTINEL` return — moves into a `ToolApprovalRequests` helper in `ai/llm` (which the gate module
already depends on). The gate and the rule wrapper both call it. Behaviour of the gate is unchanged.

**Raising.** On `RequireApproval` the wrapper calls the helper with the rule's title and description (rule
description plus the tool name and the model's arguments, in the gate's wording), the settings expiry, and
adds `__bytechef_rule_ids__` (a list of ids) to the continue parameters. The single-suspend-per-round rule
holds: if the context already carries a suspend, the wrapper returns the gate's "deferred" response.

**Delivery.** Production: the agent node's gate channels when any gate is configured on the node (the Agents
feature derives those from the conversation's inbound channel, so the request goes back where the conversation
lives), otherwise the chat channel; and **always** the `approvalTask` channel, so the request is on the
Approval Tasks page regardless of transport. Per-channel best-effort, failing only when every channel fails.
Editor test runs: the SSE card only, exactly like the gate. `AbstractAiAgentChatAction` passes the node's
gate channels to `ClusterElementToolCallbacks.build` for this purpose.

**Resuming.** `buildPatchedRequestSpec` already routes any suspend carrying `GATED_TOOL_NAME` to
`resolveGatedToolResumeData`; a rule suspend is such a suspend, so the branch is reused as-is except for two
additions:

- the `ToolContext` it builds for the approved re-execution gains `AiAgentToolContextKey.APPROVED_BY` (the
  server-verified reviewer, or `"anonymous"` for the hosted form). `AiAgentToolFacade` does not read it; the
  rule wrapper does, and passes it to the enforcer as `approvedBy`;
- when the continue parameters carry `__bytechef_rule_ids__`, the branch calls
  `clusterElementToolCallbacks.recordRuleApprovalResolution(...)` on both outcomes, which fans out to
  `recordApprovalResolution` on every enforcer. The agent action never imports the rule module.

**Composition with the gate.** A tool that is both under a gate and matched by an approval rule raises **one**
request: the gate fires first (it is the outer layer), the human approves, the resume path unwraps the gate,
and the rule layer — now innermost, still wrapping — sees `approvedBy` and treats its approval rule as
satisfied. A gate rejection never reaches the rule layer.

**Hosted form.** `ApprovalFormFacadeImpl` renders the suspended task execution's parameters, which for an
agent suspend are the agent node's parameters. It gains an overlay: when the stored `Suspend`'s continue
parameters carry `formTitle` / `formDescription`, those win. The shared helper writes both keys, so the hosted
form for a gate or rule approval shows the tool and arguments instead of the agent's prompt configuration.

### 4. Observe mode

`ComponentRuleSettings.observeMode` (default `false`). When on, `checkBeforeCall` returns `Allow` for every
call and audits each rule that **would** have fired as `RULE_OBSERVED` with `wouldHave: BLOCK |
REQUIRE_APPROVAL | TAG`. Strict fallbacks are observed the same way. AFTER-phase tags still record as
`RULE_TAGGED` — they never enforced anything. GraphQL: `componentRuleSettings` query,
`updateComponentRuleSettings(observeMode, approvalExpiresInHours)` mutation, admin-gated. Client: a switch in
the Rules tab header, with a persistent banner while observe mode is on.

### 5. Strict rules

`ComponentRule.strict` (default `false`). Evaluation of a condition has three results: `TRUE`, `FALSE`, or
**unevaluable** — the lenient evaluator returned a non-boolean (an unresolved reference echoes the source
text) or threw. Non-strict: unevaluable is not a match (v1 behaviour, fail open). Strict: unevaluable is a
match, and the audit payload carries `strictFallback: true` so a reviewer can tell a genuine match from a
fallback. Allowed on every rule action; it only changes outcomes for `BLOCK` and `REQUIRE_APPROVAL`, and a
strict `TAG` is a cheap way to find conditions that fail to resolve. The dialog labels it "Fail closed: fire
when the condition cannot be evaluated".

### 6. Risk level

SDK (`sdks/backend/java/component-api`): `enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }` in
`com.bytechef.component.definition`; `Optional<RiskLevel> getRiskLevel()` on `ActionDefinition` and on
`ClusterElementDefinition`, with matching builder methods on the `Modifiable*` classes; `ComponentDsl.tool()`
copies the action's level onto the tool.

Platform: `ActionRiskLevelResolver` (CE, `platform-component-api`) returns the declared level or infers one
from the name, tokenised on camel case and underscores, first match in this order:

| level | name tokens |
| --- | --- |
| CRITICAL | delete, remove, destroy, purge, drop, truncate, wipe, pay, charge, refund, transfer, payout, withdraw, revoke |
| HIGH | send, post, publish, email, message, reply, notify, invite, execute, run, deploy, cancel, void, archive, approve, reject, grant, share, submit, upload |
| LOW | get, list, search, find, read, fetch, retrieve, query, count, download, export, check, lookup, describe, exists |
| MEDIUM | everything else (create, update, add, set… and unknown names) |

The resolved level is a field on the platform `ClusterElementDefinition` DTO, on the cluster element REST
model and GraphQL type. The component index is not touched: the Rules dialog loads one component's full
definition, never a stub. Shown as a badge in the Rules dialog's tool picker and on each row of the rule
list (the GraphQL `ComponentRule` type gains a derived `toolRiskLevel` beside `componentTitle` /
`componentIcon`), and returned by the copilot's describe tool.

### 7. Audit and tool invocations

`ComponentRuleAuditEvent` gains four non-strict events beside `RULE_BLOCKED` and `RULE_TAGGED`:
`RULE_APPROVAL_REQUESTED`, `RULE_APPROVED`, `RULE_REJECTED`, `RULE_OBSERVED`. The payload contract becomes
`ruleId`, `componentName`, `toolName`, `toolCallName`, `phase`, `jobId`, `taskExecutionId`, plus
`approvedBy` on the approval events, `wouldHave` on observed, `strictFallback` where applicable. Tool
arguments stay out of the audit trail, as with the gate. `ToolExecutionOutcome` gains `RULE_BLOCKED`,
appended, so the Tool Invocations page shows blocks next to `APPROVAL_REQUIRED` / `APPROVAL_DENIED`.

### 8. GraphQL

```graphql
enum ComponentRuleActionType { BLOCK TAG REQUIRE_APPROVAL }

type ComponentRule {
    id: ID!
    componentName: String!
    componentTitle: String
    componentIcon: String
    toolName: String
    toolRiskLevel: RiskLevel
    phase: ComponentRulePhase!
    ruleAction: ComponentRuleActionType!
    condition: String!
    description: String
    enabled: Boolean!
    strict: Boolean!
}

type ComponentRuleSettings { observeMode: Boolean!, approvalExpiresInHours: Int! }

componentRules(componentName: String): [ComponentRule!]!
componentRuleSettings: ComponentRuleSettings!
saveComponentRule(id: ID, componentName: String!, toolName: String, phase: …, ruleAction: …,
                  condition: String!, description: String, enabled: Boolean!, strict: Boolean): ComponentRule!
updateComponentRuleSettings(observeMode: Boolean!, approvalExpiresInHours: Int!): ComponentRuleSettings!
deleteComponentRule(id: ID!): Boolean!
```

`APPROVAL_AFTER_UNSUPPORTED` surfaces as a typed error like the existing two.

### 9. Client

`client/src/ee/pages/settings/platform/component-rules/`:

- **Dialog.** The second picker lists the chosen component's TOOLS cluster elements (not its actions),
  through the existing cluster-element definition API — adding a by-component-and-type query only if none
  fits — each with a risk badge; "all tools" stays the default. Action radio gains "Require approval",
  disabled in the AFTER phase like Block. New "Fail closed" checkbox. Copy changes from action to tool.
- **List.** Column rename to Tool, risk badge, strict indicator, `REQUIRE_APPROVAL` badge.
- **Tab header.** Observe-mode switch and banner.
- Conventions as in v1: sort-keys, `Icon`-suffixed lucide imports, `twMerge`, hook ordering, optimistic
  toggles with the global error toast.

### 10. Copilot

`ComponentRuleToolCallbacksFactory` renames `describeComponentActionParameters` to
`describeComponentToolParameters`; it lists a component's TOOLS elements with their risk level and describes
one element's properties. `createComponentRule` and `proposeComponentRuleCondition` take `toolName`, `strict`
and accept `REQUIRE_APPROVAL`. The shared prompt tells the model that rules govern agent tool calls, that
`REQUIRE_APPROVAL` pauses the agent for a human, and to prefer approval over block on HIGH and CRITICAL tools.

### 11. Distributed EE

The rule wrapper runs where the agent runs — worker-app — which already carries the component modules and the
`RemoteApprovalTaskFacadeClient` the approval-task channel uses, so approval delivery works there. The rule
module's service beans must be on worker-app's classpath: `platform-component-rule-service` (and the
`PropertyService` remote client it needs for the settings row) is added to worker-app, and the GraphQL module
stays on configuration-app. The plan checks how `platform-component-policy-service` reaches worker-app today
and follows the same wiring.

## Testing

- **Wrapper** (`RuleEnforcingToolCallbackTest`): Allow delegates and records after; Block returns the denial
  and never delegates; RequireApproval suspends with the gate's continue parameters plus rule ids and returns
  the sentinel; deferred when a suspend already exists; an `approvedBy` tool context satisfies approval, still
  evaluates block; AFTER output reaches `recordAfterCall`.
- **Enforcer** (`ComponentRuleEnforcerTest`): precedence block > approval > tag; all matching approval ids in
  one decision; observe mode allows and emits `RULE_OBSERVED` per would-be firing; strict vs non-strict on an
  unresolved reference and on a throwing condition, with `strictFallback`; settings cached per tenant with the
  rules; cache still keyed by tenant and component.
- **Agent resume** (`AbstractAiAgentChatAction` tests): a rule suspend resumes through the gate branch;
  approve re-executes with `APPROVED_BY` in the tool context; reject feeds the denial and records
  `RULE_REJECTED`; gate + rule on one tool yields one request.
- **Service**: `REQUIRE_APPROVAL` + `AFTER` rejected; settings round-trip.
- **Ordinal/enum**: `RuleAction.REQUIRE_APPROVAL = 2`, `ToolExecutionOutcome.RULE_BLOCKED` appended.
- **Risk**: `ActionRiskLevelResolverTest` for declared-over-inferred and each token class; `tool()` copies the
  level.
- **Visibility**: `doExecuteTool` refuses an operation-hidden tool.
- **Removal**: `ActionDefinitionServiceImpl` tests pass with the enforcer gone; no rule code remains under
  `platform-component-service`.
- **Client**: tool picker renders tools with badges; Require approval disabled in AFTER; strict checkbox
  round-trips; observe switch optimistic toggle.

## Rejected alternatives

- **Enforce in `ClusterElementDefinitionServiceImpl.doExecuteTool` instead of on the callback.** Cheaper to
  reach, but a provider element returns its dynamic tools as callbacks that execute later without passing
  through `doExecuteTool` again, so MCP-style tools would escape. The callback layer sees every call.
- **Keep the workflow-action chokepoint and add the tool one.** Two enforcement points for one feature, two
  suspend mechanisms for approval, and a rule that means different things on the canvas and in an agent. The
  user's direction is explicit: rules are tool governance.
- **A rule-specific suspend protocol.** The gate's protocol already carries tool name, input and form URL and
  already has a tested resume branch; adding rule ids to it is one key.
- **Migrating `action_name` to `tool_name` with a new changeset.** The table has never shipped; a rename
  changeset would be ceremony for no customer.
- **Storing observe mode as a column on every rule.** It is one tenant-wide switch; a per-rule flag would have
  to be flipped in bulk and would drift.
- **Inferring risk on the client.** The inference belongs where the copilot and the GraphQL type can share it.
