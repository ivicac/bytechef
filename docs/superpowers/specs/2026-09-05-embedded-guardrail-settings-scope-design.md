# Embedded guardrail settings scope — design

**Status:** **Implemented. D4 resolved as moot (2026-09-05)** — its premise (pre-existing embedded rows)
was void, nothing having shipped. The code is unaffected, and the upgrade note D4 originally produced in
`.agents/ai-guardrails.md` — which warned about a hazard that cannot occur — has been deleted, not
reworded, matching the re-decision below. See §D4.
Approach decided by the maintainer; §2
corrected the same day when grounding showed the resolver is shared — see D6. Plan at
`docs/superpowers/plans/2026-09-05-embedded-guardrail-settings-scope.md`.
**Ticket:** wants its own ticket; discovered by the whole-branch review of the restoration-destination work
**Date:** 2026-09-05
**Relates to:** `2026-09-05-restoration-destination-boundary-design.md` (the review that surfaced this), `2026-08-25-guardrails-consolidation-design.md` §6 (the settings-scope discriminator this completes)

## 1. The problem

The embedded guardrails settings page has eight controls. **Seven of them do nothing.**

| Control | Read at runtime? |
|---|---|
| Redact PII | no |
| Redact secrets | no |
| Scan responses | no |
| Model-based moderation | no |
| Prompt-injection detection | no |
| Blocked terms | no |
| Blocking mode | no |
| Redact MCP tool results | **yes** |

An embedded admin can switch off PII redaction, save, watch the page re-render with it off, and every model call in every embedded integration keeps redacting. Or — the direction that matters — set blocking mode to BLOCK and get no blocking.

### Why

The settings model has three scopes, and the storage layer implements all three correctly:
`AiGuardrailsSettingsScope.PLATFORM` / `WORKSPACE` / `EMBEDDED` map onto three distinct
`Property.Scope` values, and `AiGuardrailsWorkspaceSettingsServiceImpl` has both a `fetchSettings(Long)`
and a `fetchEmbeddedSettings()`.

The runtime read path does not. `JobPrincipalWorkspaceResolver#resolve(PlatformType, Long)` collapses its
two arguments into a single `@Nullable Long workspaceId`:

```java
if (platformType != PlatformType.AUTOMATION || jobPrincipalId == null) {
    return null;
}
```

and everything downstream — `AiGuardrails`'s eleven public settings-reading methods, `AiGuardrailsAdvisor`,
`AiGuardrailMetrics` — knows only that `Long`. `AiGuardrails#findSettings` then calls
`fetchSettings(workspaceId)`, which maps `null` to `PLATFORM` and non-null to `WORKSPACE`. There is no
input it can be given that produces `EMBEDDED`.

So `null` is overloaded. It means both "an automation run with no resolvable workspace" and "an embedded
run", and both read the **PLATFORM** row. The embedded row is written by the page and read by exactly one
runtime path — `resolveEmbeddedMcpOutboundPolicy`, reached from `McpOutboundRedactorProviderImpl`, which
knows it is embedded from its own **surface constant** rather than from the resolver. That is why
`redactMcpResults` is the one control that works: it is the one that never goes through the resolver.

**The `EMBEDDED` scope is not missing. It is unreachable from the agent path**, because the resolver throws
away the fact that distinguishes it.

### Why this is worth fixing rather than deleting

The alternative — removing the seven controls — was considered and rejected. Four layers already implement
this feature end to end: the scope enum, the property scope, the service method, the GraphQL controller's
`EMBEDDED` branch, and the page itself. Only the runtime dispatch is missing. Deleting the controls would
discard a nearly-complete feature and leave embedded deployments with no PII, moderation, or injection
configuration at all, on the one product surface where the tenant is somebody else's SaaS.

## 2. Decision

**The guardrails side stops discarding the platform type.** It already holds `platformType` at both places
it calls the resolver, and simply drops it on the floor. Instead it now builds a settings *target* from the
platform type and the workspace the resolver returns:

| Input | Target |
|---|---|
| `PlatformType.EMBEDDED` | `embedded()` |
| anything else, with a resolvable workspace | `workspace(id)` |
| anything else, without one | `platform()` |

A new record carries it:

```java
public record AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope scope, @Nullable Long workspaceId)
```

with the invariant `AiGuardrailsWorkspaceSettings` already enforces — `workspaceId` is non-null exactly
when the scope is `WORKSPACE` — validated in the compact constructor, and four static factories: the three
obvious ones, `workspace(long)` / `platform()` / `embedded()`, plus one that performs exactly the mapping in
the table above, so the interpretation lives in a single place rather than at each call site:

```java
public static AiGuardrailsSettingsTarget resolve(@Nullable PlatformType platformType, @Nullable Long workspaceId)
```

`AiGuardrails`'s eleven public settings-reading methods take the target in place of `@Nullable Long
workspaceId`. `findSettings` switches on its scope: `EMBEDDED` dispatches to the already-existing
`fetchEmbeddedSettings()`, `PLATFORM` and `WORKSPACE` keep today's path. Fail-open behaviour is unchanged
on every branch.

That is the whole repair. Everything else in this spec is a consequence of it.

### D1 — the target replaces the Long everywhere, not only where embedded reaches

A half-converted API — some methods scope-aware, others still taking a bare `Long` — is precisely the shape
that produced this bug. The distinction has to be visible in every signature that reads settings, so a
future caller cannot accidentally resolve the wrong row by passing the argument it happens to hold.

### D2 — EMBEDDED unions with the GLOBAL properties, and never falls back to PLATFORM

An `EMBEDDED` row's null field unions with the GLOBAL `bytechef.ai.gateway.guardrails.*` properties exactly
as a `WORKSPACE` row's does, and does not fall back to the tenant-default `PLATFORM` row.

`AiGuardrailsWorkspaceSettings`'s own javadoc already asserts that the two rows are "otherwise independent".
Today that claim is vacuous, because one of the two is never read on this path. This makes it true rather
than aspirational, and it is the same rule `WORKSPACE` already follows — one union rule for all three
scopes, not two.

### D3 — `resolveEmbeddedMcpOutboundPolicy` keeps its own read

It is deliberately fail-**closed** where every other settings read here is fail-open: a lookup failure during
a `tools/call` must end in a tool error, never in raw customer records with `isError` false. Folding it into
the general mechanism would either lose that property or leak fail-closed semantics into paths where a broken
guardrail must never be worse than no guardrail.

It stays as it is, and gains a comment pointing at the general mechanism so a future reader knows the
duplication is deliberate rather than residual.

### D4 — pre-existing embedded rows take effect immediately

**Decided by the maintainer, 2026-09-05.** No marker, no re-save requirement, no forced observe mode.

> ### ⚑ D4 RE-OPENED, 2026-09-05 — its premise is void, and it produced a warning about an impossible hazard
>
> **Nothing has shipped.** `git ls-tree -r --name-only v0.31.4 | grep -c AiGuardrails` returns **0**: the
> guardrails feature, the embedded settings page included, is absent from the latest release tag. So no
> embedded deployment has ever written one of these rows, and there is no "pre-existing embedded row" for
> this decision to be about.
>
> D4 chose between three ways of handling rows that already exist. None exist. The decision is not wrong so
> much as **empty** — with no stored rows, "take effect immediately", "take effect after re-save" and
> "force observe mode first" are the same behaviour on every deployment there is.
>
> **The part that is actively wrong.** D4's stated cost was written into `.agents/ai-guardrails.md` as an
> upgrade note, in these words: *"any value saved there — including a blocking mode set long ago on a page
> that did nothing — begins governing embedded model calls immediately. Review the embedded guardrails
> settings page before upgrading."* That warns an operator about a hazard that **cannot occur**: nobody has
> a blocking mode set long ago, because the page has never been in a release. A release note carrying it
> would send readers to check a page that has never held a value.
>
> ### D4 RESOLVED, 2026-09-05: **moot**
>
> The maintainer recorded D4 as **moot**. With no stored rows, "take effect immediately", "take effect
> after re-save" and "force observe mode first" are the same behaviour on every deployment that exists, so
> there is nothing to choose between. The code needs no change — the implemented behaviour is correct
> under any of the three.
>
> **The upgrade note is deleted, not reworded.** "Review the embedded guardrails settings page before
> upgrading" has no first-release equivalent worth saying: on a first release there is nothing to review
> and nothing that changes underneath anyone. A reworded note would be words occupying the space where a
> real warning would go, in a file whose value is that its warnings are real.

**Superseded by the D4 re-decision above.** The paragraph that stood here argued the pre-reversal case for
D4's original decision — that honouring a pre-existing embedded row is correct, and that the cost (a stale
toggle set long ago suddenly taking effect at upgrade) is real and belongs in a release note. That argument
was premised on pre-existing rows existing to take effect; the re-decision found none do, on any deployment
there is, which is why D4 resolved as moot rather than needing a release note. There is nothing here for a
release note to warn about.

### D6 — the shared resolver is left alone

An earlier draft of §2 had `JobPrincipalWorkspaceResolver#resolve` return the target directly. **Grounding
against the code corrected it.** That class lives in `platform-ai-workspace` and serves three consumers, not
one: guardrails, `WorkspaceSystemPromptAdvisorProviderImpl`, and `ComponentRuleEnforcerImpl`. Changing its
return type would push a guardrails-specific type into a module the other two depend on, to serve a
distinction neither of them has — component rules and workspace prompts have no embedded scope to resolve.

Its contract is also still correct as written: it answers "which workspace does this run belong to", and for
an embedded run the honest answer is `null`, because embedded has no workspaces. The bug is not that it
returns null; it is that guardrails treats that null as the whole answer when it also holds the platform type
that disambiguates it. So the resolver keeps its signature, and the interpretation moves to the one consumer
that needs it.

## 3. What changes

- **New:** `AiGuardrailsSettingsTarget` record, alongside `AiGuardrailsSettingsScope` in the guardrails
  `-api` module (both are EE), with factories `workspace(long)`, `platform()`, `embedded()` and
  `resolve(PlatformType, Long)`.
- `JobPrincipalWorkspaceResolver` is **unchanged** (D6).
- `AiGuardrailsAdvisorProviderImpl` builds the target via `resolve(platformType, workspaceId)` at both places
  it currently calls the resolver, instead of passing the bare `Long` on.
- `AiGuardrails`'s eleven public settings-reading methods take the target. `findSettings` gains the
  `EMBEDDED` branch.
- `AiGuardrailsAdvisor` carries the target instead of a `Long`; `AiGuardrailMetrics` is built from it.
  Metrics tags are unchanged — an embedded run's `workspaceId` tag stays absent, as it is today.
- Three callers outside the guardrails service — `AiHubSpringAIAgent`, `WorkspaceAdvisorContributor`,
  `AiGatewayGuardrails` — build `workspace(id)` or `platform()` from the workspace they already resolved.
- **Folded in from the restoration-boundary branch's deferred list:** `AiGuardrailsAdvisor` currently
  resolves `isRestoreIntoWorkflowOutput` twice per call, once in `withSessionInToolContext` and once in
  `applyResponseGuardrails`, against an uncached property row. Both reads move to a single resolution at the
  top of `adviseCall`, threaded down. This belongs here rather than in its own change because it rewrites
  the same resolution path — doing it separately means doing it twice. It also closes a real inconsistency:
  a toggle flipped mid-call can currently restore the tool arguments and withhold the response.

## 4. Non-goals

- **Giving embedded a workspace concept.** Embedded has no workspaces and this does not invent them; the
  `EMBEDDED` target simply carries a null workspace id, as the settings record already does.
- **Changing what the embedded page writes.** The page and the GraphQL layer are already correct.
- **Changing MCP's fail-closed behaviour** (D3).
- **Per-integration guardrail settings.** One embedded row per tenant, as today. If per-integration
  configuration is ever wanted it fits this shape without redesign.

## 5. Testing

- The load-bearing pair: an embedded run resolves the `EMBEDDED` row and **not** the `PLATFORM` row; an
  automation run with no resolvable workspace resolves `PLATFORM` and **not** `EMBEDDED`. Both directions,
  because a fix that made everything read `EMBEDDED` would pass a one-directional test.
- One test per newly-live control proving it now takes effect on an embedded run — that is the actual bug,
  and seven controls that silently do nothing is what a test suite failed to catch the first time.
- A test that an `EMBEDDED` row's null field unions with the GLOBAL property and does **not** pick up the
  `PLATFORM` row's value for the same field (D2). This is the one a naive implementation gets wrong by
  reusing `resolvePolicy`'s existing fallback.
- A test that `resolveEmbeddedMcpOutboundPolicy` still fails closed (D3) — it is the one read here that must
  not become fail-open when its neighbours are refactored around it.
- The invariant on `AiGuardrailsSettingsTarget`: constructing `WORKSPACE` with a null id, or `EMBEDDED` /
  `PLATFORM` with a non-null one, is refused.
- For the folded-in I5: a test that the setting is read once per call, and that the request and response
  halves of one call cannot disagree.

## 6. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | The target replaces `@Nullable Long workspaceId` on every settings-reading method, not only the ones embedded reaches | A half-converted API is the shape that produced this bug; the distinction must be visible in every signature |
| D2 | `EMBEDDED` unions with the GLOBAL properties and never falls back to `PLATFORM` | One union rule for all three scopes; makes the settings record's existing independence claim true rather than vacuous |
| D3 | `resolveEmbeddedMcpOutboundPolicy` keeps its own fail-closed read | A `tools/call` lookup failure must end in a tool error, never in raw records; the general mechanism is fail-open by design and must stay so |
| D4 | **Resolved as moot** — first decided "pre-existing embedded rows take effect immediately, no marker, no re-save, no forced observe mode", then RE-OPENED and RESOLVED as moot, both by the maintainer, 2026-09-05; see §D4 | The original decision's premise (pre-existing embedded rows) was void — nothing had shipped, so no row exists to take effect any particular way. With no stored rows, all three candidate behaviours coincide, so there is nothing to choose and no code change; the upgrade note the original decision produced in `.agents/ai-guardrails.md` warned about a hazard that cannot occur and has been deleted, not reworded |
| D5 | The restoration-boundary branch's deferred I5 is folded in here | It rewrites the same resolution path; done separately it would be done twice, and it fixes a real intra-call inconsistency |
| D6 | `JobPrincipalWorkspaceResolver` keeps its signature; the guardrails side interprets `platformType` itself | **Corrected 2026-09-05 after grounding.** The resolver is shared with `WorkspaceSystemPromptAdvisorProviderImpl` and `ComponentRuleEnforcerImpl`, neither of which has an embedded scope; its `null` for an embedded run is the honest answer to the question it asks |
