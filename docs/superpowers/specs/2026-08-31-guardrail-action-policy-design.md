# Guardrail Action Policy — Design

**Status:** **`ALLOW` implemented 2026-09-03** (plan: `docs/superpowers/plans/2026-09-03-guardrail-observe-mode.md`).
`ToolPiiAction` remains deferred by this document's own D4 to the Component Policies per-action slice.
**Prompted by:** Merge's security gateway (`docs.merge.dev/merge-agent-handler/secure/security-gateway`), which offers Allow / Redact / Block per rule with per-surface overrides
**Builds on:** `2026-08-31-tool-boundary-pii-restoration-design.md`

## 1. Two gaps, one missing concept

**At the model boundary, there is no way to observe without enforcing.** `BlockingMode` offers `BLOCK` and
`REDACT_AND_CONTINUE`. An operator who wants to know what a guardrail *would* catch has to turn it on and
accept the consequences on live traffic. Every guardrail change is therefore a leap, and the cost of being
wrong is a blocked or mangled production call.

**At the tool boundary, there is no action model at all.** The tool-boundary work restores PII into tool
arguments unconditionally. That is right for `send-email`, whose whole job is to reach a real address. It is
wrong for a generic HTTP node pointed at a third-party API, which now forwards data that previously left
ByteChef tokenized.

That second point deserves stating plainly, because it is a withdrawal rather than an omission. Before the
tool-boundary work, the model only ever saw placeholders, so anything it echoed into a tool call was a
placeholder. Third parties were protected — **accidentally**, as a side effect of the boundary never being
handled. Nobody designed it, nothing documents it, and it is indiscriminate: it protected the partner API
and broke `send-email` by the same mechanism. Replacing an accidental default with a deliberate one is
correct; shipping the deliberate one with no off switch is not.

The missing concept in both cases is the same: **a detection should have an action, and the action should be
configurable separately from the detection.**

## 2. Three actions at the model boundary

`BlockingMode` gains `ALLOW`: the violation is detected and recorded, and the content is forwarded
**unmodified**.

This is the observe mode that makes every other guardrail change safe to roll out — turn a rule on in
`ALLOW`, watch a week of real traffic, then promote it to `REDACT_AND_CONTINUE` or `BLOCK`. It is also why
this design does not adopt Merge's Rule Tester: testing against real traffic strictly dominates testing
against samples.

`ALLOW` must record its metric. An action that neither modifies nor reports is indistinguishable from the
guardrail being off, which would make it worse than useless — it would look like coverage.

**Append the enum value at the end.** Enum ordinals are persisted as INT in this codebase; reordering
silently reassigns every stored row.

## 3. Three actions at the tool boundary — DEFERRED, recorded here for its successor

**This section does not ship in this project.** §4 explains why: a per-tool policy home already exists and
this must not build a second one. The shape is recorded so the Component Policies per-action slice inherits a
worked design rather than starting cold.

A separate enum, `ToolPiiAction`, **not** a reuse of `BlockingMode`. The two describe different things — one
is what happens to a *violation*, the other is what a tool is allowed to *see* — and collapsing them would
force one vocabulary to carry two meanings.

| Action | Behaviour |
|---|---|
| `RESTORE` | Tokens in the tool's arguments are replaced with real values. The tool works normally. |
| `KEEP_TOKENIZED` | Tokens are left in place. The tool receives placeholders and cannot leak PII onward. |
| `BLOCK` | The tool call is refused and the model receives an error it can reason about. |

**`RESTORE` is the default**, so this design is additive rather than a reversal: a workspace that configures
nothing keeps exactly the behaviour the tool-boundary work ships. The escape hatch exists for the tools that
need it.

Result tokenization is **not** governed by this. A tool's result is tokenized on every path regardless of
action, because the risk it addresses — PII reaching the model provider — does not vary with which tool
produced it.

## 4. Where the policy lives — and why not where this spec first put it

**A per-tool policy home already exists, and this project must not build a second one.**

`platform-component-policy` is shipped EE code — `ComponentPolicy` keyed on `componentName`, with a
repository, service and visibility provider. `2026-06-20-component-policies-visibility-design.md` covers
component-level enable/disable, and `2026-08-04-per-action-component-policies-design.md` (approved, pending
implementation) adds per-action and per-trigger toggles with deny-list semantics.

That spec's own non-goals already claim this ground:

> *"Policing tool-typed cluster elements independently — tools stay governed by the component toggle and
> inherit per-action policy in a later slice."*
> *"The 'per-workspace policy overrides' promise from the guardrails spec remains a separate follow-up."*

So tools are already promised per-action policy, and per-workspace guardrail overrides are already a known
follow-up. An earlier draft of this section proposed a JSON map on `AiGuardrailsWorkspaceSettings` keyed by
tool name. That would have produced two policy systems keyed differently, neither aware of the other — the
divergence this codebase keeps rediscovering, this time built deliberately.

**Consequently, `ToolPiiAction` is deferred out of this project.** It belongs as a dimension of Component
Policies once the per-action slice lands, not as a parallel mechanism shipped ahead of it.

One genuine tension has to be resolved when it does land, and neither existing spec resolves it: **Component
Policies are org-wide; guardrail settings are per-workspace.** A PII action is a guardrail concern expressed
against a component, so it needs a scope decision — org-wide like its neighbours, per-workspace like the
setting it derives from, or org-wide with a workspace override. That is exactly the "per-workspace policy
overrides" follow-up the per-action spec names, and it should be settled there rather than pre-empted here.

**What ships in this project is `ALLOW` at the model boundary only.** That has no per-tool dimension, no
policy-home question, and delivers the observe mode every later guardrail change depends on.

## 5. Where the actions are applied

- `ALLOW` in `AiGuardrails`, at the existing `BlockingMode` switch. It is a third arm, not a new path.
- `ToolPiiAction` in `PiiTokenBoundaryToolCallingManager`, in the argument-restoration step the tool-boundary
  work already built. The decorator is the seam; this is a policy consulted at a point that already exists.

`BLOCK` at the tool boundary returns a tool error rather than throwing. A thrown exception aborts the agent
run; a tool error is something the model can see, explain, and route around — which is the behaviour an agent
framework expects when a tool refuses.

## 6. Observability

- `guardrail_allowed` — a violation was detected under `ALLOW` and forwarded unmodified.
- `tool_pii_kept_tokenized` — a tool call ran with placeholders by policy.
- `tool_pii_blocked` — a tool call was refused by policy.

All three are incidence events, at most once per content or per tool invocation, matching the existing
`containsKind` idiom.

`guardrail_allowed` is the one that matters most: it is the entire product of observe mode, and without it
`ALLOW` is silent.

## 7. Testing

- **`ALLOW` forwards text byte-identical to the input** and records `guardrail_allowed`. Both halves — a mode
  that modifies nothing and reports nothing is the guardrail being off.
- **`ALLOW` does not block**, on a payload that `BLOCK` would have rejected.
- **`KEEP_TOKENIZED` leaves a token in a tool's arguments** that `RESTORE` would have replaced, proving the
  policy is consulted rather than the restoration merely being absent.
- **`BLOCK` produces a tool error, not a thrown exception**, and the run continues.
- **An unconfigured tool restores**, so the default is verified rather than assumed.
- **An unknown tool name in the map is ignored**, and does not fail the run.
- **Result tokenization is unaffected by the action** — assert it under all three.

## 8. Blast radius

| Area | Change |
|---|---|
| EE api | `BlockingMode` gains `ALLOW` (appended); settings record gains a per-tool action map |
| EE service | Third arm at the `BlockingMode` switch; GraphQL surface for the new field |
| CE service | `PiiTokenBoundaryToolCallingManager` consults the action before restoring |
| CE api | `ToolPiiAction` enum; two metric events |
| Docs | `.agents/ai-guardrails.md`; the customer PII page gains the per-tool control and observe mode |

## 9. Non-goals

- **Custom detection rules.** Merge's org-defined regex with context keywords is the successor to confidence
  scoring — it is what would let a bare digit run score high near "SSN:" and low near an order number, buying
  back the recall the confidence work traded away. Its own spec.
- **Violation records with drill-down.** Per-detection records naming the matched field and calling user.
  Worth most once custom rules exist, since drill-down is how you debug your own rules. Its own spec.
- **A settings-page UI.** API and GraphQL only, matching the confidence-threshold precedent. Stated, not
  silently omitted.
- **Coverage for uncovered surfaces.** Copilot, MCP servers, the knowledge base and A2A have no guardrails at
  all. That is a coverage problem, not a policy problem, and a policy engine on two of six surfaces is worth
  less than basic protection on all six. Separate and, in my view, higher priority.
- **Per-agent or wildcard tool matching.** Exact tool name only, until someone asks.

## 10. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Append `ALLOW` to `BlockingMode`, never reorder | Enum ordinals are persisted as INT; reordering reassigns stored rows |
| D2 | `ALLOW` records a metric | An action that neither modifies nor reports is indistinguishable from no guardrail |
| D3 | No Rule Tester | Observe mode tests against real traffic, which dominates testing against samples |
| **D4** | **`ToolPiiAction` is DEFERRED to the Component Policies per-action slice, not built here** | A per-tool policy home already exists (`platform-component-policy`, shipped) and that slice already promises to cover tools. Building a parallel JSON map on the guardrails settings record would create two policy systems keyed differently and unaware of each other |
| D5 | When it lands, `ToolPiiAction` is a separate enum from `BlockingMode` | "What happens to a violation" and "what a tool may see" are different questions; one vocabulary should not carry both |
| D6 | When it lands, `RESTORE` is the default | Keeps the tool-boundary work's shipped behaviour; the policy is additive, not a reversal |
| D7 | When it lands, tool `BLOCK` returns a tool error, not an exception | The model can see and route around a tool error; an exception aborts the run |
| D8 | Result tokenization ignores the action, always | The risk it addresses does not vary by which tool produced the data |
| D9 | The org-wide vs per-workspace scope question is settled in the Component Policies slice, not pre-empted here | Component Policies are org-wide, guardrail settings per-workspace; that tension is exactly the "per-workspace policy overrides" follow-up that spec already names |
