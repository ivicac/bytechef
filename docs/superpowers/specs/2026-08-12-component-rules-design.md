# Component Rules — Design

**Date:** 2026-08-12
**Status:** Proposed
**Predecessor:** `2026-06-20-component-policies-visibility-design.md` (component-level visibility) and
`2026-08-04-per-action-component-policies-design.md` (per-action/trigger deny-list), which deferred
"Rules / Restrictions / Claims" as future work on the Policies page. **Neither predecessor spec nor the
`platform-component-policy` module it describes exists on `master` yet — both are unmerged, in-flight
work on the `0_732` branch.** This spec is written against `master` per the project's design-spec branch
convention, but the implementation plan that follows it depends on that module landing first (or being
brought in as a merge-base for the same PR series). Concretely: `component_rule` reuses
`ComponentVisibilityProvider`'s SPI/cache pattern and sits in a sibling module to
`platform-component-policy`; if that module's shape changes before it merges, this design's module
references need a pass to match.

Naming context: "Rules" is one of three future Policies tabs named after Gumloop's App Policies
(`docs.gumloop.com/enterprise-features/app-policies/app-rules`) as prior art. Gumloop's own Rules model
— natural language compiled to a CEL condition, evaluated per tool call with block/tag actions — maps
closely enough onto ByteChef's action-execution model that this spec follows it directly, substituting
ByteChef's existing SpEL evaluator for CEL and ByteChef's copilot/AI Hub infrastructure for Gumloop's
unspecified "AI rule builder."

## Problem

Component Policies today only gates whole components or whole actions/triggers on or off. There is no
way to conditionally govern a single action call based on its actual input — e.g. block a CRM
component's `deleteRecord` action only when the target record has an active owner, or flag (without
blocking) any Slack `postMessage` call targeting a specific channel. Per-action policy is a blunt
instrument for these cases: disabling `deleteRecord` entirely removes a capability teams need in the
common case, just not in the risky one.

## Goals

- Admin-authored conditional policies ("rules") scoped to a component, optionally narrowed to one
  action, evaluated against the action's actual input parameters (and, for post-execution rules, its
  output) at execution time.
- Two enforcement actions: **block** (stop the call before it reaches the component's `perform`) and
  **tag** (let it run, record that the rule matched, for audit review).
- Two evaluation phases: **before** (pre-execution, block or tag) and **after** (post-execution,
  tag only — see Non-Goals).
- AI-assisted authoring: a plain-English description compiles to a SpEL condition via a dedicated
  copilot specialist, reachable both from the Rules page's own "Generate with AI" trigger and from AI
  Hub chat, matching the existing domain-copilot-slice pattern (Context Store, Knowledge Base, Data
  Table).
- Admin can hand-edit the generated (or self-written) condition directly — the raw SpEL text is the
  saved artifact, not a black box the AI owns.
- Zero behavior change for CE, for tenants with no rules configured, and for actions/components with no
  matching rule.

## Non-Goals (future)

- **Block on `AFTER`-phase rules.** A block only has meaning before an action runs; blocking after
  output already exists can't undo side effects the action already performed. `AFTER` rules are
  `TAG`-only; saving a `BLOCK` + `AFTER` combination is rejected with a validation error.
- **Trigger governance.** Gumloop's model governs "tool calls," which map to ByteChef actions, not
  triggers (event sources, not calls an agent makes). Extending rules to triggers is a distinct problem
  (what does "block a trigger" mean — refuse to register it? drop the event post-hoc?) left for a later
  spec if there's demand.
- **Structured (non-SpEL) condition editing.** The condition is stored and edited as raw SpEL text (see
  Decisions in the prior brainstorm). A friendlier structured builder is a possible future UI
  improvement that would compile to the same stored SpEL, not a data-model change.
- **Per-workspace scoping.** Tenant-wide only, consistent with the rest of the Policies page.
- **Rule ordering/priority beyond block-wins-over-tag.** If multiple rules match the same call, the only
  documented precedence is "any firing BLOCK rule wins, regardless of TAG rules." Two firing BLOCK rules
  with contradictory intent (impossible here, since BLOCK has only one effect) or a need for
  admin-defined evaluation order is out of scope.
- **A "flagged executions" list UI.** Tag matches are recorded as Audit Events (existing page), not a
  new dedicated review surface. If usage shows the Audit Events page is a poor fit for high-volume rule
  tagging, revisit as its own follow-up.

## Design

### 1. Data model

New table `component_rule`, in a new module `platform-component-rule` (mirroring
`platform-component-policy`'s `-api`/`-service`/`-graphql` split), EE-gated the same way:

| column               | type      | notes                                                        |
| -------------------- | --------- | ------------------------------------------------------------- |
| `id`                 | BIGINT    | surrogate PK                                                   |
| `component_name`     | VARCHAR   | required                                                        |
| `action_name`        | VARCHAR   | nullable — null means "every action on this component"         |
| `phase`               | INT       | `RulePhase` enum ordinal: `BEFORE`=0, `AFTER`=1, append-only    |
| `rule_action`         | INT       | `RuleAction` enum ordinal: `BLOCK`=0, `TAG`=1, append-only      |
| `condition`           | TEXT      | the SpEL expression evaluated at enforcement time               |
| `description`         | TEXT      | the plain-English source text (kept for re-editing/regenerating context, never itself evaluated) |
| `enabled`             | BOOLEAN   | default true — unlike per-action policy, presence isn't the signal here; a rule's content (condition/phase/action) must persist across a temporary disable |
| `created_by/date`, `last_modified_by/date` | audit columns | same convention as `component_policy` |
| `version`             | INT       | optimistic locking |

No composite unique key: a component+action pair may have multiple rules (e.g. one `BLOCK` and one
unrelated `TAG`). `OperationType`-style ordinal-stability testing applies to both new enums.

**Why not reuse `component_operation_policy`?** That table is a pure deny-list with no payload beyond
its own existence — adding `condition`/`phase`/`rule_action`/`enabled` columns to it would make an
unconditional per-action disable and a conditional rule share a schema for two semantically different
features (unconditional vs. conditional gating), which the per-action-policy spec's own "Rejected
alternatives" section already argued against for a smaller version of this same question.

Domain class `ComponentRule`, Spring Data JDBC repository, and a new `ComponentRuleService`:

- `List<ComponentRule> getComponentRules(String componentName)` — for GraphQL and enforcement.
- `List<ComponentRule> getEnabledComponentRules(String componentName, String actionName, RulePhase phase)`
  — the enforcement-path query, returning rules scoped to the action name OR with a null `action_name`.
- `ComponentRule saveComponentRule(ComponentRule rule)` — validates the `BLOCK`+`AFTER` combination is
  rejected and that `condition` parses under `SpelEvaluator` before persisting (a syntactically invalid
  rule is refused at save time, not discovered at the next execution).
- `void deleteComponentRule(Long id)`.

### 2. Enforcement

Same chokepoint as per-action policy: `ActionDefinitionServiceImpl.executePerform` and
`executePerformForPolyglot`.

**Before invoking the action's `perform`:**

1. Fetch (cached — see below) enabled `BEFORE`-phase rules for `(componentName, actionName)`.
2. Evaluate each rule's `condition` via `SpelEvaluator`, with an evaluation context exposing
   `inputParameters` (the resolved input map), `componentName`, `actionName`, and `connectionId`.
3. If any matching rule has `rule_action = BLOCK`, throw `ConfigurationException` with a new
   `ActionDefinitionErrorType.RULE_BLOCKED` (component/action/ruleId in the exception payload) —
   `perform` never runs. Block wins over tag: if both a matching `BLOCK` and a matching `TAG` rule exist
   for the same call, only the block's effect applies (the tag match is not separately audited in that
   case, since the call never happened).
4. Else, for each matching `TAG` rule, emit a `RULE_TAGGED` audit event (see section 3) and proceed to
   `perform`.

**After `perform` returns**, fetch enabled `AFTER`-phase rules the same way, with `output` added to the
evaluation context. Any matching rule (necessarily `TAG`, per the `BLOCK`+`AFTER` rejection) emits
`RULE_TAGGED`. A failure evaluating an `AFTER` rule never fails the already-completed execution — it's
logged and skipped, matching the audit system's general fail-open posture for non-strict events.

**Caching.** Mirrors `ComponentPolicyVisibilityProvider`: a Caffeine cache keyed by `componentName`,
10s TTL, holding both `BEFORE` and `AFTER` rule lists for that component. Loaded from
`getEnabledComponentRules`-equivalent bulk fetch (one query per component, not per action) so a
component with many actions and many rules costs one cache miss, not many.

### 3. Audit events

New `ComponentRuleAuditEvent` enum (own file, following `ConnectionAuditEvent`'s documented-contract
style) in `platform-component-rule-service`:

- `RULE_BLOCKED(false)` — payload: `ruleId`, `componentName`, `actionName`, `jobId`/`taskExecutionId`.
  Emitted alongside the thrown `ConfigurationException`, best-effort (afterCommit is meaningless here
  since the call is refused, not committed — publish happens synchronously in the same guard, and a
  publish failure does not additionally fail the already-failing call).
- `RULE_TAGGED(false)` — same payload shape, emitted for both `BEFORE`-tag and `AFTER`-tag matches (a
  field distinguishes phase).

Both are `strictAudit = false`: this fires on live workflow executions, potentially per-call, and making
audit capture failures fail the underlying job would turn an observability feature into an availability
risk. This is a deliberate departure from `ConnectionAuditEvent`'s strict events, which are low-frequency
admin actions where a lost trail is a compliance problem, not a per-execution one.

These surface on the existing Audit Events page/query with no new UI — the "tag" enforcement mode is
fully served by infrastructure that already exists.

### 4. GraphQL

In `platform-component-rule-graphql`, `ROLE_ADMIN`-gated like the existing Policies mutations:

```graphql
enum ComponentRulePhase {
    BEFORE
    AFTER
}

enum ComponentRuleActionType {
    BLOCK
    TAG
}

type ComponentRule {
    id: ID!
    componentName: String!
    actionName: String
    phase: ComponentRulePhase!
    ruleAction: ComponentRuleActionType!
    condition: String!
    description: String
    enabled: Boolean!
}

componentRules(componentName: String!): [ComponentRule!]!

saveComponentRule(
    id: ID
    componentName: String!
    actionName: String
    phase: ComponentRulePhase!
    ruleAction: ComponentRuleActionType!
    condition: String!
    description: String
    enabled: Boolean!
): ComponentRule!

deleteComponentRule(id: ID!): Boolean!
```

`saveComponentRule` takes an optional `id` (update-by-id when present, matching the API connector
edit-by-id convention rather than a separate create/update pair) and surfaces the `BLOCK`+`AFTER`
rejection and SpEL-parse-failure as typed GraphQL errors, not generic 500s.

### 5. AI-assisted authoring (domain copilot slice)

Follows the established pattern (Context Store, Knowledge Base, Data Table) exactly:

- **Shared tool callbacks** in `automation-ai-tool`: a read list (`listComponentRules`, feeding AI Hub
  ASK) and a write list (`createComponentRule` — wraps `saveComponentRule` with `id` absent — feeding AI
  Hub BUILD). The write tool takes the plain-English `description` plus target `componentName`/
  `actionName` and internally calls the same generation step the panel button uses, so "AI Hub chat" and
  "panel button" produce identical output through one code path, not two.
- **`ComponentRuleAgentConfiguration`** (EE, `automation-ai-copilot` alongside `ContextStoreAgentConfiguration`
  since Component Policies is EE-only): defines the panel agent (triggered from the Rules page) and the
  ask/build subagent `ChatClient`s (delegated to from AI Hub). One prompt file, shared by both, per the
  "one prompt file per domain and mode" convention.
- **`component_rule_agent`** delegate callback registered in `ai-copilot-tool`, wired through
  `AiHubConfiguration.wrapDelegate` like every other delegate (guardrails + session-memory contributors
  apply automatically).
- **`Source` enum entry** (`COMPONENT_RULE` or similar) on both the client `Source` enum and the
  MCP/tool-registration surfaces that key off it.
- **Prompt contract**: takes the admin's plain-English description plus the target action's parameter
  schema (names/types from the component definition, not just the description) — this is what lets the
  model reference real field names the way Gumloop's example does ("Channel ID: C05QG7RF30A" implies the
  model needs to know the parameter is literally named something like `channel`). Returns a single SpEL
  string. The tool response is inserted into the condition editor for review; **nothing is saved without
  an explicit admin Save**, whether the request came from the panel or from AI Hub chat (AI Hub chat's
  write tool still returns the proposed rule for confirmation before commit, using the same
  interactive-question mechanism other write-capable specialists use for anything non-trivial).

### 6. Client (Component Policies page)

A new "Rules" sub-tab. (Note: this spec is being written the same session that hid the Policies tab
entirely, since it had only one child — Component Visibility, now promoted to a top-level tab. Once
Rules ships, Policies has two children — Rules and, if Restrictions/Claims also ship as sub-tabs of
Policies rather than their own top-level tabs, more — at which point re-showing the Policies tab or
re-evaluating the whole page's tab structure is a UI decision for whoever implements this, not fixed by
this spec.)

- Flat list of configured rules across all components (component icon/name, action name or "all
  actions", phase, action badge, enabled toggle, condition preview truncated with a tooltip for the
  full text).
- "Add Rule" dialog: component picker → action picker (optional, defaults to "all actions") → phase
  radio (Before/After, After disables the Block option) → action radio (Block/Tag) → condition textarea
  (monospace, raw SpEL) with a "Generate with AI" button that opens the copilot panel inline, scoped to
  this dialog's component/action selection.
- Same conventions as the rest of the page: sort-keys, `Icon`-suffixed lucide imports, `twMerge`, hook
  ordering, optimistic toggle for `enabled` with the global fetch-interceptor error toast on failure.

### 7. Testing

- Ordinal-stability tests for `RulePhase` and `RuleAction`.
- `ComponentRuleServiceTest`: save rejects `BLOCK`+`AFTER`; save rejects unparseable SpEL; update-by-id;
  delete.
- Enforcement test (`ActionDefinitionServiceImplRuleTest` or similar): a matching `BEFORE`+`BLOCK` rule
  throws `RULE_BLOCKED` and `perform` is never invoked (mock verify); a matching `BEFORE`+`TAG` rule lets
  `perform` run and emits `RULE_TAGGED`; a matching `BEFORE` block and a matching `BEFORE` tag on the
  same call only blocks (tag not separately emitted); a matching `AFTER`+`TAG` rule sees `output` in its
  evaluation context; no matching rule is a no-op with no audit event and no evaluator call overhead
  beyond the cached lookup.
- Cache test: one cache miss loads both phases for a component; TTL expiry re-fetches.
- GraphQL controller test: query/mutations, admin gating, typed errors for the two rejection cases.
- Copilot subagent test: prompt receives the target action's parameter schema, not just the raw
  description; a generated condition is returned for review, never auto-saved via the write tool either.
- Client Vitest for the Add Rule dialog's phase/action radio interaction (After disables Block) and the
  optimistic enabled-toggle flow, per the repo's hoisted-mock convention for store-touching tests.

## Rejected alternatives

- **Extending `component_operation_policy` with condition columns.** Rejected for the same reason the
  per-action-policy spec rejected merging its own table with `component_policy`: an unconditional
  deny-list row and a conditional rule are different things, and a shared schema optimizes for "fewer
  tables" over "each table has one clear meaning."
- **CEL instead of SpEL.** Gumloop uses CEL; ByteChef already has a live, integrated SpEL evaluator used
  throughout the workflow engine. Introducing CEL would mean a second expression language, a second
  dependency, and no reuse of the evaluator infrastructure (custom functions, existing test coverage,
  familiarity) that SpEL already provides. Nothing about CEL's design offers ByteChef a capability SpEL
  lacks for this use case.
- **AI generation as a one-shot backend call instead of a copilot specialist.** Simpler and would have
  shipped faster, but explicitly rejected in favor of the domain-copilot-slice pattern so Rules
  authoring is reachable from AI Hub chat as well as the settings page, consistent with how every other
  AI-assisted domain on this page's neighboring surfaces works.
- **A dedicated "flagged executions" review list.** Would give tag-mode a purpose-built UI, but the
  existing Audit Events page already does structured, filterable event history — building a parallel
  list before there's evidence the general one doesn't work is scope the Goals section explicitly
  avoids.
