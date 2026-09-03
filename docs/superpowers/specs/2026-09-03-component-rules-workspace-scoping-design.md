# Component Rules — Workspace Scoping and Relocation to AI Agents Settings

**Date:** 2026-09-03
**Status:** Proposed
**Predecessor:** `2026-09-02-component-rules-tool-governance-design.md`, which re-scoped rules from workflow
actions to AI agent tool calls and added approval, observe mode, fail-closed evaluation and risk levels. That
work is complete and on branch `component-rules-approval`. This spec extends it; it does not revise it.

## Problem

Component Rules are tenant-wide. The `component_rule` table has no workspace column, the enforcement cache is
keyed by `(tenantId, componentName)`, and the tenant settings row is stored at `Scope.PLATFORM` with a null
`scopeId`. One rule set therefore governs every workspace in a tenant.

That was inherited rather than decided. The predecessor spec never mentioned workspace scoping in either
direction; the v1 spec before it listed "Per-workspace scoping — tenant-wide only, consistent with the rest of
the Policies page" as an explicit non-goal, and the re-scope carried it forward by silence.

It is now the wrong default for two reasons:

- **Product.** A tenant running a finance workspace and a sandbox workspace cannot govern them differently. The
  observe-mode rollout story — turn rules on, watch what would have fired, then enforce — is only useful if it
  can be done one workspace at a time. Today it is all or nothing for the tenant.
- **Placement.** Rules live under Settings → Components, a leftover from when they governed component actions.
  They now govern AI agent tool calls, which is the same thing Guardrails governs, and Guardrails already lives
  under Settings → AI → Agents and is already workspace-scoped. Rules are in the wrong place beside the wrong
  neighbours.

## Goals

- A rule is scoped to one workspace, or to every workspace in the tenant.
- Observe mode and the approval expiry become per-workspace, with a tenant default, matching Guardrails.
- Enforcement resolves the workspace of the running agent and applies that workspace's rules plus the
  tenant-wide ones.
- Rules move to Settings → AI → Agents as a third tab beside Guardrails and System Prompt, and the Components
  tab is removed.
- No behaviour change for a tenant that never sets a workspace on a rule.

## Non-goals

- **Per-project or per-deployment scoping.** Workspace is the granularity the rest of this settings area uses.
- **Per-workspace risk levels.** Risk is a property of the tool, not of who governs it.
- **Changing enforcement semantics.** Precedence stays block > approval > tag; fail-open stays the non-strict
  default; the approval protocol is untouched.
- **Governing the Embabel `agentic-ai` runtime**, still ungoverned and documented as such.
- **Closing the distributed-worker gap.** A worker still finds no rules; workspace scoping does not change that
  and is not blocked by it.

## Design

### 1. Data model

`component_rule` gains one column:

| column | type | notes |
| --- | --- | --- |
| `workspace_id` | BIGINT, nullable | `null` means every workspace in the tenant. A value scopes the rule to that workspace. |

The field is `Long`, never primitive — null is a real state, per the project convention that new
platform-package entities take a nullable `workspace_id` rather than a relation table.

**Migration.** The `component_rule` schema is still unreleased (the module exists only on the `0_732` line and
has never shipped), so this is added to the init changelog in place, exactly as `tool_name` and `strict` were.
Existing rows in a developer database read as `null` and therefore stay tenant-wide, which is their current
behaviour.

`ComponentRuleService` gains a workspace-aware read used by enforcement:

```java
List<ComponentRule> getEnabledComponentRules(String componentName, @Nullable Long workspaceId);
```

returning rules where `workspace_id IS NULL OR workspace_id = :workspaceId`. When `workspaceId` is null it
returns only the tenant-wide rules. The existing single-argument overload is removed; the authoring reads
(`getComponentRules()`, `getComponentRules(componentName)`) gain an optional workspace filter for the page.

### 2. Resolving the workspace at enforcement time

The rule wrapper `RuleEnforcingToolCallback` lives in `ai/llm`, a CE module, and must not reach EE services. So
it passes facts and the EE enforcer decides — the shape the SPI already has.

`ComponentRuleEnforcer.ToolCall` gains two components, both already available on the `ActionContextAware` the
wrapper holds (`JobContextAware.getJobPrincipalId()`, `getPlatformType()`):

```java
record ToolCall(
    String componentName, String toolName, String toolCallName, Map<String, ?> inputParameters,
    @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId,
    @Nullable String approvedBy, @Nullable Long jobPrincipalId, @Nullable PlatformType platformType) {}
```

`ComponentRuleEnforcerImpl` resolves the workspace from those and uses it for both the rule query and the cache
key.

**Resolution rules**, copied from the Guardrails precedent because they are already correct:

- Only `PlatformType.AUTOMATION` with a non-null `jobPrincipalId` resolves a workspace. Everything else is
  `null`.
- The path is `jobPrincipalId → ProjectDeployment → Project → workspaceId`.
- The lookup is cached, keyed by `(platformType, jobPrincipalId)`, because it runs on every tool call in an
  agent loop.
- **Any failure resolves to `null`, not to "no rules".** A deleted deployment, a missing service, a transient
  lookup error — all degrade to the tenant-wide rule set. Only the workspace *scope* is affected, never whether
  rules run at all. This mirrors the guardrails comment verbatim and matches the feature's fail-open posture.

**Shared resolver.** `AiGuardrailsAdvisorProviderImpl` already implements exactly this, including the cache and
the degradation. Rather than a second copy, extract it into a shared component that both use — proposed as
`JobPrincipalWorkspaceResolver` in an EE module both can depend on, taking `ProjectDeploymentService` and
`ProjectService` through `ObjectProvider` as guardrails does. Two independent copies of a cached, fail-open
resolution will drift, and the one that does not get a fix is the one nobody notices.

*If the reviewer of this spec prefers not to touch a shipped feature, the fallback is a private copy in
`ComponentRuleEnforcerImpl` with a comment naming the original. Say so at review; the plan will follow whichever
is chosen.*

### 3. Cache

`ComponentRuleEnforcerImpl`'s rule cache key becomes `(tenantId, componentName, workspaceId)`. The tenant is
already part of the key because component names are global; the workspace joins it for the same reason — one
workspace's rules must never be served to another. TTL is unchanged.

The settings cache added in the last task is keyed by tenant today; it becomes `(tenantId, workspaceId)`.

### 4. Settings

`ComponentRuleSettings` (`observeMode`, `approvalExpiresInHours`) becomes per-workspace with a tenant default,
stored exactly the way `AiGuardrailsWorkspaceSettings` is:

```java
private static Property.Scope scopeOf(@Nullable Long workspaceId) {
    return workspaceId == null ? Property.Scope.PLATFORM : Property.Scope.WORKSPACE;
}
```

`Scope.PLATFORM` with a null `scopeId` for the tenant default, `Scope.WORKSPACE` with the workspace id for an
override — not `Scope.WORKSPACE` with a sentinel `0L`, which would alias a real workspace. The service takes a
`@Nullable Long workspaceId` on both `getSettings` and `saveSettings`, and `getSettings(workspaceId)` falls
back to the tenant default row, then to `ComponentRuleSettings.DEFAULT`.

Resolution at enforcement time uses the same resolved workspace as the rules, so a workspace in observe mode
observes and its neighbour enforces.

### 5. Enforcement semantics

Unchanged except for which rules are in scope. A tool call in workspace W is evaluated against the union of W's
rules and the tenant-wide ones, with the existing precedence: any matching block wins, else any matching
approval wins (carrying every matching approval rule's id), else tags are recorded. `strict` and observe mode
are unchanged in meaning.

An unresolvable workspace evaluates only the tenant-wide rules. It never evaluates none.

### 6. GraphQL

`ComponentRule` gains `workspaceId: ID`. `saveComponentRule` gains a `workspaceId: ID` argument — null meaning
all workspaces. `componentRules` gains an optional `workspaceId` filter.

`componentRuleSettings` and `updateComponentRuleSettings` gain a `workspaceId: ID` argument, null meaning the
tenant default.

**Authorization.** Setting or clearing a rule's workspace to null — making it tenant-wide — requires tenant
admin. A workspace-scoped mutation requires the caller to have management scope in that workspace. The existing
mutations are `ROLE_ADMIN`-gated wholesale; that gate stays as the floor, and the workspace check is added
beside it rather than replacing it.

**Amendment (implementation, 2026-09-03):** the tenant-admin check for a tenant-wide rule is implemented, on
the resolved *target* workspace so it also catches clearing an existing workspace-scoped rule's workspace to
widen it — not only creating a new tenant-wide row, which is the direction this paragraph originally named. It
is currently unreachable in practice: every mutation this section describes already carries `@PreAuthorize` for
`ROLE_ADMIN` at the method level, so any caller that reaches the body has already passed a tenant-admin check
by construction. It is kept as defence-in-depth for the day this surface admits a caller who is not a tenant
admin.

The second check — "a workspace-scoped mutation requires the caller to have management scope in that
workspace" — is **deliberately not implemented**. It presumed a surface reachable by non-tenant-admins; the
product owner subsequently ruled the Rules page admin-only (see `.agents/component-rules.md`), which predates
this spec revision. With a `ROLE_ADMIN` floor, only tenant admins can reach the mutation at all, and a tenant
admin manages every workspace, so per-workspace management scope has nothing to add today. Revisit this the day
the floor loosens to admit non-tenant-admin callers.

### 7. Client

**Relocation.** `ComponentRulesTab` moves from `ee/pages/settings/platform/component-rules` to a third tab
under `ee/pages/settings/automation/ai/agents`, beside Guardrails and System Prompt, at
`/automation/settings/ai/agents/rules`. `AiAgents.tsx` gains the trigger. The Components settings page loses
its Rules tab and its route redirects to the new one.

**Workspace binding.** The page reads the current workspace the way `Variables.tsx` does, and passes it to the
list query, the save mutation and the settings mutation.

**Tenant-wide authoring.** The rule dialog gains an "Apply to all workspaces" checkbox, rendered only for a
tenant admin. Checked, it saves a null workspace. The list marks such rows so a workspace admin can see that a
rule they cannot edit is governing them, and the row's edit control is disabled for them.

**Settings control.** The observe switch and expiry act on the current workspace. Where no override exists the
control shows the inherited tenant value and says so, so "off" is distinguishable from "inherited off".

## Rejected alternatives

- **Every rule belongs to exactly one workspace.** Simpler page and simpler query, but it makes an org-wide
  guarantee inexpressible — a tenant admin could not write "no agent in this tenant may delete records" — and
  it forces every existing row to be assigned a workspace on migration.
- **Workspace rules override tenant rules rather than union with them.** Override semantics for a *collection*
  are ambiguous in a way they are not for a settings record: it is unclear whether a workspace rule replaces
  all tenant rules, only those on the same tool, or only those with the same action. A union has one reading.
- **Keeping both the Components tab and the AI Agents tab.** Two places to look and two surfaces to maintain,
  for a cross-workspace view that the tenant-wide "all workspaces" filter on the new page already gives.
- **Resolving the workspace in the CE wrapper.** Would drag EE project services into a CE module. Passing the
  job principal and letting the EE enforcer resolve keeps the existing CE/EE seam.
- **Scoping by project or deployment.** Finer than the rest of this settings area, and no evidence anyone wants
  per-project tool governance.

## Testing

- Enforcement: a workspace rule fires for its workspace and not for another; a tenant-wide rule fires for both;
  the union applies when both match; an unresolvable workspace still evaluates tenant-wide rules; the cache
  does not serve one workspace's rules to another (the tenant-cache test, extended).
- Settings: a workspace override wins over the tenant default; absent an override the default applies; absent
  both, `DEFAULT`; one workspace in observe mode does not suppress enforcement in another.
- Resolution: non-AUTOMATION and null job principal resolve to null; a deleted deployment resolves to null
  rather than throwing; the lookup is cached per job principal.
- Authorization: a non-tenant-admin cannot create or clear a tenant-wide rule; a workspace admin cannot edit
  another workspace's rule.
- Client: the tab renders under AI Agents; the picker and dialog carry the workspace; the "apply to all
  workspaces" control appears only for a tenant admin; an inherited settings value is labelled as inherited.
- Migration: an existing rule with no workspace still fires everywhere.
