<!-- Extracted from CLAUDE.md so the agent-facing reference does not sit in every prompt.
     Load this when working on component rules, agent tool governance, or the rule-enforcing tool
     wrapper. The CLAUDE.md section this replaced described an earlier, action-scoped design that
     is no longer how the feature works — see "History" at the bottom. -->

# Component Rules (EE): agent tool governance

`server/ee/libs/platform/platform-component-rule/` (`-api`/`-service`/`-graphql`), sibling to
`platform-component-policy`. Spec: `docs/superpowers/specs/2026-09-02-component-rules-tool-governance-design.md`,
amended by `docs/superpowers/specs/2026-09-03-component-rules-workspace-scoping-design.md` (workspace scoping,
below). Plan: `docs/superpowers/plans/2026-09-02-component-rules-tool-governance.md`, extended by
`docs/superpowers/plans/2026-09-03-component-rules-workspace-scoping.md`. The earlier
`2026-08-31-component-rules-plan.md` describes the superseded action-scoped design and is historical only.

The page lives at **Settings → AI → Agents → Rules** (`/automation/settings/ai/agents/rules`,
`client/src/ee/pages/settings/automation/ai/rules/`), a third tab beside Guardrails and System Prompt — not
under Components settings, where it lived before workspace scoping. The whole `AiAgents` route, all three tabs
included, is admin-only (`hasAnyAuthorities={[AUTHORITIES.ADMIN]}` in `client/src/routes.tsx`); there is no
read-only workspace-member view.

## What a rule governs

A `component_rule` row governs one **AI agent tool call**, not a workflow action execution. It is
keyed on `(componentName, toolName, workspaceId)` where `toolName` is the name of a TOOLS cluster element — a
`null` `toolName` means every tool of that component, and a `null` `workspaceId` means every workspace in the
tenant (see "Workspace scoping" below). This is the thing to get right first: the
sibling `platform-component-policy` and the pre-September-2026 version of this feature both governed
*workflow* action executions via `ActionDefinitionServiceImpl`. This feature does not touch that
path at all. It governs only tools an agent calls — `ClusterElementToolCallbacks.build()` wraps
every callback it produces (component actions attached as tools, and tools a provider element
contributes) in a `RuleEnforcingToolCallback`
(`server/libs/modules/components/ai/llm/.../tool/RuleEnforcingToolCallback.java`), so a workflow
node that isn't inside an agent's TOOLS list is never evaluated against any rule.

Each row also carries `phase` (`BEFORE`/`AFTER`, relative to the tool actually running),
`ruleAction` (`BLOCK`/`TAG`/`REQUIRE_APPROVAL`), a `condition`, `enabled`, and `strict`. A
`BLOCK`+`AFTER` or `REQUIRE_APPROVAL`+`AFTER` combination is rejected at save time
(`ComponentRuleServiceImpl.saveComponentRule`) — the tool has already run by the `AFTER` phase, so
neither makes sense there.

## Three enforcement points, all fed from one injected list

`ComponentRuleEnforcer` (`platform-component-api`, no CE implementation) is a Spring bean
collection — `List<ComponentRuleEnforcer>` — constructor-injected into three `@Component(...
_v1_ComponentHandler)` classes, each of which builds its own `ClusterElementToolCallbacks` and
passes the list straight through:

- `AbstractAiAgentChatAction` (the agent action itself — chat, streaming chat, realtime chat).
- `AiAgentUtilsComponentHandler`, which builds the callback for the **approval gate** tool
  (`AiAgentUtilsApprovalGateTool` / `ApprovalGateToolCallback`).
- `AiAgentUtilsTaskTool`, the **subagent task tool** — a subagent's own tools go through the exact
  same wrapping, so a rule governs a subagent's tool calls identically to the parent agent's.

**A fourth agent runtime is deliberately NOT governed: the Embabel `agentic-ai` component.**
`AgenticAiToolFacade` builds its own `FunctionToolCallback`s and calls
`ClusterElementDefinitionService.executeTool(...)` directly, never passing through
`ClusterElementToolCallbacks`. So its agent's tool calls get no block, no tag and no approval, and
nothing anywhere says so — the failure is silent and looks exactly like "no rules configured" from
every surface an admin can see: a rule authored against a component that agent calls simply never
fires, with no audit event and no warning. (It does still get the per-operation visibility check,
which lives inside `executeTool`.) Closing this means routing that facade's callbacks through
`ClusterElementToolCallbacks.build()` like the three above — a second agent runtime's worth of work,
not a wiring change, and deliberately out of scope for the tool-governance design.

All three governed points matter for the same reason the worker-app wiring in this file's companion
task mattered: if any one of them resolves an empty `List<ComponentRuleEnforcer>` (module missing
from that app's classpath, or the enforcer bean failing to construct there), that surface's calls go
completely unchecked with no error — `RuleEnforcingToolCallback.call()` short-circuits to
`delegate.call(...)` the moment the list is empty. There is no other signal. Whenever you touch
how any of these three classes gets wired (DI changes, new distributed app, new EE microservice
hosting the agent action), re-verify the list is non-empty there — an assertion like
`WorkerApplicationIntTest.testComponentRuleEnforcerIsReachable()` (`ApplicationContext.getBeansOfType
(ComponentRuleEnforcer.class)` not empty) is cheap insurance and does not require a real rule to
exist.

## The wrapper is deliberately NOT a `DelegatingToolCallback`

`RuleEnforcingToolCallback` reports its delegate's `ToolDefinition` (so it looks like the tool it
wraps), but does not implement `DelegatingToolCallback`. This matters at exactly one call site: the
approved-tool re-execution branch in `AbstractAiAgentChatAction`, which rebuilds the tool callback
stack and does `.map(DelegatingToolCallback::unwrap)` to strip the *approval gate's* wrapper before
re-invoking the human-approved call. `DelegatingToolCallback.unwrap` is recursive — if
`RuleEnforcingToolCallback` were one, that same unwrap would also strip the rule layer, and a
human-approved re-execution would run with no rule check at all. Because it isn't, unwrap stops
after the gate and the rule layer stays innermost: the re-execution still goes through
`checkBeforeCall`, which is exactly how `approvedBy` gets a chance to matter (next section). Do not
make this class a `DelegatingToolCallback` to pick up some other unwrap-based convenience — that
would silently remove rule enforcement from every approved-and-resumed tool call.

The flip side is why `ClusterElementToolCallbacks.build()` refuses to wrap a callback that is
already governed (`DelegatingToolCallback.unwrap(toolCallback) instanceof
RuleEnforcingToolCallback`). `AiAgentUtilsApprovalGateTool` declares the **gate itself** as a TOOLS
cluster element, so the gate's element passes through `build()` a second time — once from inside the
gate, producing `ApprovalGateToolCallback(RuleEnforcingToolCallback(tool))`, and again from the
agent's own TOOLS loop. Wrapping that second time produced
`RuleEnforcingToolCallback(ApprovalGateToolCallback(RuleEnforcingToolCallback(tool)))`, whose
outermost layer is not delegating, so the resume branch's `unwrap` was a no-op, the gate survived,
and the approved re-execution raised a second approval — an approval loop with no exit, in EE only
(in CE the enforcer list is empty and `build` short-circuits before wrapping). It also
double-evaluated every rule on a gated call. The skip fixes both; pinned by
`ClusterElementToolCallbacksTest.testAnAlreadyGovernedCallbackIsNotWrappedASecondTime` and
`AbstractAiAgentChatActionResumeGateTest.testApprovedResumeOfAGatedToolUnderRulesExecutesOnceWithoutSuspendingAgain`.

## Precedence and the approval protocol

Within `ComponentRuleEnforcerImpl.checkBeforeCall`, matching rules are checked in this order:
**BLOCK wins outright** (denial returned immediately); otherwise **REQUIRE_APPROVAL wins over
TAG** (approval is raised, TAG rules are not even published as observed until the call actually
proceeds); TAG rules are recorded as `RULE_TAGGED` only once nothing blocked or required approval.
A `BLOCK` decision returns a JSON denial (`{"blocked": true, "reason": ...}`) to the model rather
than throwing — the agent can explain the refusal and replan, matching the human-in-the-loop
approval gate's own rejection shape. See `.agents/hitl-approvals.md` for that gate.

`REQUIRE_APPROVAL` does not invent a new suspend mechanism — it reuses the gate's:
`ToolApprovalRequests.raise(...)` suspends the turn exactly the way `ApprovalGateToolCallback`
does, carrying the matching rule ids under `ToolSuspendConstants.RULE_IDS` /
`RULE_COMPONENT_NAME` / `RULE_TOOL_NAME` in the suspend payload. The resume branch in
`AbstractAiAgentChatAction` reads those back, calls
`ClusterElementToolCallbacks.recordRuleApprovalResolution(...)` (which never imports the rule
module directly — it delegates through `RuleEnforcingToolCallback.recordApprovalResolution`, a
static method taking the enforcer list as a parameter, exactly so the agent action does not need a
compile-time dependency on the EE rule service), and re-invokes the unwrapped tool. `approvedBy` is
the load-bearing field on that re-invocation: `ComponentRuleEnforcerImpl.checkBeforeCall` only
raises a `REQUIRE_APPROVAL` decision when `toolCall.approvedBy() == null`. The hosted approval form
is reached anonymously (no verified reviewer), so the resume branch sets `resolvedApprovedBy =
"anonymous"` rather than leaving it null — a real value, not a placeholder, and the only signal
that stops the same call from re-raising the same approval forever.

Delivery is the agent node's configured approval channels, or the chat channel when there are none,
**and always the `approvalTask` channel** on top — skipped only when an approval-task channel is
already attached, so an admin who configured one does not get two rows. That backstop is not
cosmetic: on a webhook- or schedule-triggered run with no channels the chat channel has a job id but
no listener, so without it the request reaches nobody, no Approval Tasks row exists, and the turn
hangs until expiry. A gate at least had an admin choose to attach it; the admin who authored a rule
may have no relationship to the agent node at all. Every channel is best-effort — the approval-task
channel included — and the call fails only when every one of them fails.

## Workspace scoping

A `component_rule` row carries a nullable `workspace_id`. A non-null value names the one workspace the rule
governs; `null` means every workspace in the tenant — the same "narrow or everyone" shape
`AiGuardrailsWorkspaceSettingsServiceImpl` already uses for guardrails, not a new idiom. Enforcement for a given
call is the **union** of that workspace's own rules and the tenant-wide (`workspace_id IS NULL`) ones —
`ComponentRuleRepository.findAllEnabledForWorkspace`'s `WHERE ... AND (workspace_id IS NULL OR workspace_id =
:workspaceId)`. Workspace rules do not override tenant rules; both apply. See "Rejected alternatives" in the
workspace-scoping spec for why override semantics were rejected for a *collection* of rules (ambiguous — replace
all tenant rules, or only same-tool, or only same-action?) even though they are the right shape for a single
settings record (next section).

**Resolving the workspace.** `ComponentRuleEnforcerImpl` resolves the workspace once per `checkBeforeCall` /
`recordAfterCall`, via the shared `JobPrincipalWorkspaceResolver`
(`server/ee/libs/platform/platform-ai/platform-ai-workspace`, `com.bytechef.ee.platform.ai.workspace`) — the
same class the guardrails and workspace-system-prompt advisors use, extracted because those two carried
character-identical private copies before this task. It resolves `(PlatformType, jobPrincipalId)` →
`ProjectDeployment` → `Project.getWorkspaceId()`, and is memoized in its own 5-minute Caffeine cache.

**The load-bearing invariant: any resolution failure narrows to tenant-wide rules, never to none, and never to
another workspace's.** A non-`AUTOMATION` run, a `null` job principal, a deleted deployment/project, a
transient lookup error, or the EE apps that carry this module but not the automation services it optionally
depends on (`ai-gateway-app`, `ai-copilot-app`) — every one of these resolves to `workspaceId = null`. Because
`null` is exactly the tenant-wide query, a resolution failure degrades to "only the rules that apply to
everyone" rather than "no rules apply" or, worse, leaking a rule scoped to some other workspace. Whether a
rule's own condition matches is a separate question this class never answers — a resolution failure must never
be mistaken by a caller for "nothing configured". The class Javadoc calls this out explicitly: **only the
workspace SCOPE is fail-open here.**

The second call site, `ClusterElementToolCallbacks.recordRuleApprovalResolution` (publishing
`RULE_APPROVED`/`RULE_REJECTED` audit events on an approval's resume), passes `null` for both the job principal
and the platform type — it already has the rule ids it needs and never queries rules, so it never resolves a
workspace at all. That is correct, not a shortcut: do not add a resolution there.

**A second, separate write path exists: the AI-agent authoring tools.** GraphQL is not the only place a rule gets
created — `server/ee/libs/automation/automation-ai/automation-ai-tool/.../componentrule/` has its own
`createComponentRule`/`listComponentRules` tool callbacks, registered on the AI Hub BUILD agent and the Copilot
`component_rule_build` agent, and this module was the one place the workspace-scoping plan initially missed.
`CreateComponentRuleToolCallback` resolves the workspace from `AgentToolInvocationContext.fromToolContext(...)`
on the chat's `ToolContext` — the same mechanism `CreateContextStoreSourceToolCallback` uses — and deliberately
has no `workspaceId` field on its tool schema: the model choosing the workspace would be guessing, and guessing
wrong here means authoring a rule that governs the wrong tenant's agents. Unlike the enforcement-side fail-open
(above), an unresolvable workspace here returns a tool error and writes nothing — this is an authoring path, not
enforcement, so failing visibly is correct and there is no "narrow to tenant-wide" option, because a tenant-wide
rule from this tool would be exactly the silent widening the whole design forbids. A tenant-wide rule remains
creatable only through the settings page's admin-gated "Apply to all workspaces" checkbox.
`ListComponentRulesToolCallback` resolves the workspace the same way and returns that workspace's rules plus the
tenant-wide ones (the enforcement union), with each summary carrying its own `workspaceId` so the model can tell
them apart; when the workspace cannot be resolved, it narrows to tenant-wide only, matching the enforcer's own
fail-open behavior, because a read carries none of the write path's risk. Its workspace filter is a **third**
copy of the `appliesToWorkspace` predicate (see the GraphQL section below) — this module has no dependency on the
GraphQL module to share it through.

## Observe mode and strict, together

`ComponentRuleSettings` is one `Property` row per scope, key `component_rule_settings` — the tenant default at
`Scope.PLATFORM` with `scopeId = null`, and an optional per-workspace override at `Scope.WORKSPACE` with that
workspace's id (mirroring `AiGuardrailsWorkspaceSettingsServiceImpl`'s storage shape: a scope-less row rather
than a sentinel workspace id such as `0L`, which could alias a real workspace). `getSettings(workspaceId)` owns
the whole fallback chain itself — workspace override → tenant default → `ComponentRuleSettings.DEFAULT`
(`observeMode = false, approvalExpiresInHours = 1440`) — unlike the guardrails precedent, where `fetchSettings`
reads only the requested scope and a higher layer (`AiGuardrails#resolvePolicy`) does the unioning; component
rules have no such higher layer, so `getSettings` is that layer. `observeMode` and `approvalExpiresInHours` are
therefore per-workspace with a tenant default, not tenant-wide only — a workspace can opt OUT of a tenant-wide
observe-mode rollout by storing its own `observeMode = false` override, and that is the entire point of
per-workspace observe mode: it lets a tenant roll rules out one workspace at a time.

`observeMode` is a blunt override, per-workspace-with-a-tenant-default rather than tenant-wide only (see
above): when on for a scope, every rule is still evaluated for calls in that scope, but nothing
is enforced — a would-be `BLOCK` or `REQUIRE_APPROVAL` is published as `RULE_OBSERVED` (carrying
what it would have done) and the call proceeds as `ALLOW`. It exists so a tenant (or one workspace within
it) can turn rules on without a mis-authored one taking their agents offline sight-unseen; it deliberately
fails toward "nothing is enforced" rather than "nothing is evaluated," since the whole point is to see what
*would* fire.

`strict` is per-rule and orthogonal to `observeMode`: it governs what happens when a condition is
*unevaluable* — not false, but unresolvable (an unresolved reference, or an evaluator exception).
`SpelEvaluator` returns the original source string for an unresolved reference rather than null or
throwing, so `ComponentRuleEnforcerImpl.matches()` treats three outcomes distinctly: `TRUE` (match),
`FALSE` (no match), and unevaluable. A non-strict rule (`strict = false`, the default) treats
unevaluable as no-match — **fails open** — so a mis-authored condition cannot take a tenant's whole
agent estate offline; the save-time parse check (`SpelEvaluator.validateFormulaExpression`, called
from `ComponentRuleServiceImpl.saveComponentRule`) already rejects conditions that don't parse at
all, but a condition can still be syntactically valid and reference a field that doesn't exist for
a particular call. A `strict` rule inverts this and treats unevaluable as a match — **fails
closed** — for the calls where "I'm not sure, so don't allow it" is the safer default (e.g. a
`BLOCK` rule guarding something genuinely dangerous). Audit payloads carry a `strictFallback` flag
so a reviewer can tell a genuine `TRUE` match apart from a strict fallback.

`strict` covers an unevaluable condition, and nothing else: it also fails **open** when the enforcer
itself throws, because `RuleEnforcingToolCallback` catches a per-enforcer exception, logs it and
allows the call — a `strict` `BLOCK` rule does not survive an enforcer that is down any more than a
lax one does. The empty-rule-list case documented at the end of this file is the same shape.

## The condition is a formula body, not free SpEL

The stored `condition` string is a ByteChef **formula body** — the text that follows the `=` prefix
`SpelEvaluator` requires before it parses full SpEL. `validateFormulaExpression` rejects `T(`, any
`.method(` call, and `new`, so conditions compose the evaluator's whitelisted functions
(`contains`, `equalsIgnoreCase`, `size`, …) — `contains(inputParameters['channel'], 'C05')`, never
`inputParameters['channel'].startsWith('C05')`. Both the save-time validation and the
runtime evaluation prepend the `=` themselves; it is never stored. The evaluation context exposes
`inputParameters`, `componentName`, `toolName`, `toolCallName`, `connectionId`, and (in the `AFTER`
phase only) `output`.

## The enforcement cache is keyed by (tenantId, componentName, workspaceId)

`ComponentRuleEnforcerImpl` caches the enabled-rules lookup per `TenantComponentWorkspaceKey(tenantId,
componentName, workspaceId)` for 10 seconds (Caffeine, `Ticker`-injectable for the TTL test). Component names
are global across tenants — keying on component name alone would serve one tenant's rules to another; the
workspace is part of the key for the identical reason — a cache keyed on `(tenant, component)` alone would serve
one workspace's rules to another. A component with zero matching rules costs one cache hit and nothing else:
`checkBeforeCall` returns `ALLOW` immediately when the cached list is empty, before it ever builds
an evaluation context or touches `ComponentRuleSettingsService`.

`ComponentRuleSettings` sits in a second Caffeine cache beside it — keyed by `TenantWorkspaceKey(tenantId,
workspaceId)`, same 10-second TTL, same injected `Ticker` — because it is read on the same hot path:
`checkBeforeCall` consults observe mode for every call that matched any rule, and again for the expiry when it
raises an approval. `ComponentRuleSettingsServiceImpl.getSettings()` is an uncached property lookup, so
without this each of those was a round trip. The cost is that a settings change (an observe-mode
toggle) takes up to 10 seconds to take effect, exactly as a rule edit does.

## GraphQL authorization: admin-only, and two checks deliberately absent

Every mutation on `ComponentRuleGraphQlController` carries method-level
`@PreAuthorize("hasAuthority(\"ROLE_ADMIN\")")`. That is the whole authorization story here today. If you are
asking whether a non-tenant-admin can create, widen or delete a tenant-wide rule, the answer is that they cannot
reach these methods at all.

Two finer-grained checks are deliberately NOT implemented, and both become necessary the day this surface admits
a caller who is not a tenant admin:

**A tenant-admin check on the tenant-wide case.** A rule with a null `workspaceId` governs every workspace, so
authoring or deleting one is a tenant-level act even though the page is workspace-scoped. Such a guard existed on
this branch briefly and was removed: with the `ROLE_ADMIN` floor above it could never fire, and its tests only
passed because they called the controller as a bare POJO, bypassing the Spring AOP proxy `@PreAuthorize` relies
on — security-shaped code that never executes, with tests implying coverage that was not real, is worse than its
absence. **The subtlety worth preserving is where the check belongs:** on the resolved *target* `workspaceId`,
never on whether an `id` argument is present. Clearing an existing workspace-scoped rule's workspace widens it to
the entire tenant just as much as creating a tenant-wide rule does, and a guard written against the create path
alone misses that. `deleteComponentRule` needs the symmetric check on the rule it is about to delete.

**Per-workspace management scope.** The design spec's Authorization subsection names a second check: "a
workspace-scoped mutation requires the caller to have management scope in that workspace." That presumed a
surface reachable by non-tenant-admins. The Rules page was subsequently ruled admin-only (see the top of this
file), which the spec predates — with a `ROLE_ADMIN` floor only tenant admins can reach any mutation here, and a
tenant admin already manages every workspace, so the check has nothing to add. Deferred, not forgotten; see the
amendment note in `docs/superpowers/specs/2026-09-03-component-rules-workspace-scoping-design.md`.

The client mirrors this: `ComponentRuleList` marks a tenant-wide row with an "All workspaces" badge but does not
gate any control on it, because on an admin-only route every viewer is a tenant admin. Re-gating the row is part
of the same work as re-adding the server guards, not a substitute for it.

**Three independent implementations of "does this rule apply to this workspace" — keep them in lockstep.**
Enforcement filters in SQL (`ComponentRuleRepository.findAllEnabledForWorkspace`:
`workspace_id IS NULL OR workspace_id = :workspaceId`). The GraphQL `componentRules` listing filters in memory,
after loading every rule for the component, via `ComponentRuleGraphQlController.appliesToWorkspace`
(`componentRuleWorkspaceId == null || componentRuleWorkspaceId == workspaceId`). `ListComponentRulesToolCallback`
(the AI-agent authoring tool, above) carries a third, private copy of the identical predicate, for the same
reason — no dependency from the automation-ai-tool module onto the GraphQL module to share it through. These
agree exactly today, and each has its own test. In-memory filtering is fine here — the endpoint is admin-only and
a tenant has tens of rules, not thousands — but there is no shared code path enforcing the agreement. **If you
change one predicate, change the other two, and re-verify all three agree** — the two most likely ways to drift
are the null-check direction and the `||`/`&&` choice, and any mistake makes either the settings page or the
agent lie about which rules govern a workspace: showing or reasoning against a rule that does not apply, or
hiding one that does. A governance UI or agent that lies is worse than no UI.
(`appliesToWorkspace` is unrelated to `enabled` filtering, which is deliberately asymmetric between the two
paths — the list must show disabled rules too, so they can be re-enabled; that asymmetry does not extend to the
workspace predicate.)

## Risk levels are advisory only

`ToolRiskLevelResolver` (`platform-component-api`) computes a `RiskLevel` for a tool — the level
its author declared, or one inferred from the tool's name (`deleteX` → `CRITICAL`, `sendX` →
`HIGH`, unrecognised → `MEDIUM`, never a false "safe"). This exists purely to help an admin decide
*which* tools need a rule in the authoring UI and the AI-authoring tools
(`describeComponentToolParameters` surfaces it). `ComponentRuleEnforcerImpl` never reads it — a
`CRITICAL` tool with no matching rule runs exactly as ungoverned as a `LOW` one. Do not assume risk
level does anything at enforcement time.

## Worker-app reachability: the fix in this file's companion task, and its residual gap

Task 14 of the design plan put `platform-component-rule-service` on `worker-app`'s classpath so the
enforcer bean actually exists where the agent runs (previously: not there at all, so
`List<ComponentRuleEnforcer>` was empty and every one of the three wrappers above was a silent
pass-through). Getting the module onto the classpath was not enough by itself — two dependencies
inside `-service` are not satisfiable on a distributed worker, and both needed to degrade rather
than crash `ApplicationContext` startup:

- `ComponentRuleSettingsServiceImpl` needs a `PropertyService`. On worker-app this resolves to
  `RemotePropertyServiceClient` (`platform-configuration-remote-client`), whose every method throws
  `UnsupportedOperationException` — there is no read-only remote settings lookup yet.
  `getSettings()` now catches any `RuntimeException` from the property lookup and falls back to
  `ComponentRuleSettings.DEFAULT` (observe mode off), logging a warning, rather than letting the
  exception propagate into `checkBeforeCall` and fail the tool call outright.
- `ComponentRuleServiceImpl` needs a `ComponentRuleRepository` (Spring Data JDBC, gated by
  `ComponentRuleJdbcRepositoryConfiguration`'s `@ConditionalOnBean(AbstractJdbcConfiguration.class)`).
  Worker-app has no local database at all (no `spring-boot-starter-data-jdbc`, no `DataSource`) —
  this is not a settings-row problem, it is the entire rule table being unreachable. The
  constructor now takes `ObjectProvider<ComponentRuleRepository>` and resolves it once
  (`getIfAvailable()`); on server-app (repository present) behavior is unchanged. On worker-app
  (repository absent), `getEnabledComponentRules()` — the one method the enforcement hot path
  calls — **fails open to an empty list** rather than throwing, with a once-per-JVM warning,
  matching this codebase's established fail-open pattern for a distributed app that cannot reach a
  seam's backing data (see `WorkflowVariablesResolverImpl`'s identical shape for `vars`). Every
  other method (the authoring surface, reachable only from server-app's GraphQL API) throws
  `IllegalStateException` instead — those should never be called where there is nothing to persist
  to, and throwing loudly there is more useful than a fabricated empty result.

**The residual gap this leaves**: on a genuinely distributed EE deployment, worker-app can now boot
and its enforcer bean is real and reachable, but until a remote-client path exists for
`ComponentRuleService` (calling configuration-app or another data-owning app over REST, the same
shape as `platform-configuration-remote-client`), the worker-side enforcer will find zero rules for
every component — not because nothing was configured, but because it has no way to fetch what was.

**What that costs you, stated plainly, because it is the thing worth checking before trusting this
feature in a distributed deployment**: `checkBeforeCall` returns `ALLOW` the moment the cached rule
list is empty, before `strict` or observe mode is consulted at all. So on worker-app a `strict` +
`BLOCK` rule — the mechanism built precisely so that "the condition cannot be evaluated, therefore
do not allow it" wins for genuinely dangerous tools — is exactly as inert as the laxest `TAG` rule.
Fail-closed does not survive a distributed deployment. Every tool call on a worker is allowed,
whatever any rule says.

`platform-component-policy`, the sibling feature, has the identical gap today (it isn't on
worker-app's classpath at all). Closing this properly means adding a `component-rule-remote-client`
+ a small REST endpoint on the app that owns the data, plus hosting `platform-component-rule-service`
there — real work, not a wiring change, and out of scope for the task that produced this note.
Audit events published from worker-app (`ComponentRuleAuditPublisher`) have the same distributed
gap for the same reason `platform-audit-service` isn't there either: they publish a local
`AuditApplicationEvent` that nothing persists outside server-app. Neither gap is new to this
feature — they are pre-existing limits of running EE governance features on a distributed worker,
made visible here rather than introduced here.

## History

CLAUDE.md previously described this feature at a point when a rule governed a **workflow action
execution** (`action_name`, enforcement hooked into `ActionDefinitionServiceImpl.doExecutePerform`
and `executePerformForPolyglot`), had only `BLOCK`/`TAG` (no `REQUIRE_APPROVAL`), and had no observe
mode, no strict evaluation, and no risk levels. That design is superseded — see the note at the top
of `docs/superpowers/specs/2026-08-12-component-rules-design.md`. Everything above describes the
current, tool-governance design.
