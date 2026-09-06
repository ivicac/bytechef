# Per-category response scanning — design

**Status:** **Implemented.** D1 RE-DECIDED, 2026-09-05: (c) — make the output direction honour the two
category switches. An earlier decision of (a) was taken on a premise that turned out false; see the
correction below. Both items §4's "Two things (c) needs" called out have landed: the AI Gateway's
response path (`AiGatewayGuardrails`, both the non-streaming project-overlay branch and the streaming
one) now composes with the category switches instead of calling `redactAll`/redacting every kind
unconditionally, and the settings copy on both the automation and embedded pages now says plainly that
`Scan responses` does nothing with both category switches off. The (a)-era documentation this section
flagged as asserting the opposite has been revised to match (c). §5 is kept only as the record of the
reversed decision and is struck through; **§6 states the rule** that governs which scope's category
switches apply to the response direction, and why no redactor is exempt from resolving one.
**Ticket:** wants its own ticket
**Date:** 2026-09-05
**Relates to:** `2026-08-25-guardrails-consolidation-design.md`, `2026-09-02-guardrails-settings-scope-design.md`

## 1. The asymmetry

Guardrail settings carry two category switches — `redactPii` and `redactSecrets` — and one output switch,
`scanResponses`. **The input direction honours the categories; the output direction ignores them.**

On input, `redactPiiAndSecrets` builds its kind set conditionally: `SensitiveKind.PII` is added only when
`policy.redactPii()`, `SECRET` only when `policy.redactSecrets()`. On output, `scanResponseText` gates
solely on `scanResponses` and then calls:

```java
return sensitiveDataRedactor.redact(content, EnumSet.allOf(SensitiveKind.class), minConfidence,
    recordingMetrics);
```

`EnumSet.allOf` — both kinds, unconditionally. So "redact PII but not secrets" is expressible on the way in
and inexpressible on the way out.

This is pinned by an existing test, `AiGuardrailsTest#testScanResponseTextScrubsPiiAndSecretsWhenEnabled`,
which constructs guardrails with **both** category flags false and asserts both a secret and an e-mail are
redacted from the response. The test records the behaviour; it records no reason for it.

That matters for how this spec is read: the behaviour is deliberate enough that someone wrote a test around
it, and undocumented enough that nothing says whether it was a choice or an observation. This spec cannot
settle that from the code, which is why it stops at D1.

## 2. Why it is not obviously a bug

Two readings, both coherent:

**A single switch is the point.** "Scan responses" means "do not let anything sensitive leave in the model's
output". A model can emit a secret it inferred, recombined, or hallucinated from context that never came
from the caller — so the output direction is not symmetric with the input one, and letting an admin switch
off secret scrubbing on the way out is arming a foot-gun for a saving nobody asked for.

**The switches should mean the same thing in both directions.** An admin who turned `redactSecrets` off did
so for a reason — most plausibly that their workflows legitimately return credentials to their own caller —
and the settings page gives no hint that the switch governs only half the traffic. A control that means one
thing inbound and nothing outbound is the kind of silent mismatch this codebase's `.agents/` docs exist to
prevent.

## 3. The cost of changing it

Making `scanResponseText` honour the categories is a **weakening** for every workspace that has
`scanResponses` on with either category off. Today they get both kinds scrubbed from responses; afterwards
they would get less, with no error, no failed step and no migration — the class of harm the embedded
settings spec's D4 accepted only because the pre-change state was itself a defect. Here the pre-change state
is *more* protective, so the same reasoning points the other way.

## 4. D1 — three shapes. **Maintainer decision required.**

**(a) Leave it, and document it.** `scanResponses` stays all-or-nothing. The settings page copy and
`.agents/ai-guardrails.md` say so explicitly, and `testScanResponseTextScrubsPiiAndSecretsWhenEnabled` gains
the rationale comment it never had. Zero behaviour change; closes the "silent mismatch" half of the problem
without touching the protective half.

**(b) Add two output switches, defaulting on** — `scanResponsesPii` and `scanResponsesSecrets`, both
defaulting to true so no existing workspace changes. `scanResponses` remains the master gate. Expressible,
no regression, at the cost of two more fields on a record that already has twelve and two more toggles on a
page that already has eight.

**(c) Make `scanResponseText` honour `redactPii`/`redactSecrets` directly.** Most consistent, no new fields —
and the only option that silently reduces protection for existing deployments. It also breaks the existing
test, which is the signal to take seriously rather than the obstacle to route around.

**Recommendation: (a).** The asymmetry is real, but the half that is actually harmful is that nobody is
told about it — not that the output direction over-protects. (a) fixes the harmful half for the cost of a
paragraph. (b) is the right follow-up if a customer ever asks for the capability; nobody has. (c) trades
protection for symmetry, which is the wrong direction for a guardrail.

### D1 first decided (a), then re-decided (c) — and why the first decision was wrong

**(a) was chosen on 2026-09-05 and reversed the same day.** The reversal belongs here in full, because
the failure was in the analysis rather than in the decision.

**The premise that broke.** §3 above argues that making the output direction honour the categories is a
*weakening*: "for every workspace that has `scanResponses` on with either category off", they would get
less scrubbing than before, silently and with no migration. That was the load-bearing argument for (a),
and **it is void — nothing has shipped.** `git ls-tree -r --name-only v0.31.4 | grep -c AiGuardrails`
returns **0**: the entire guardrails feature, settings and all, is absent from the latest release tag. No
workspace has ever had `redactPii`, `redactSecrets` or `scanResponses` set to anything, so there is no
installed base to protect and no behaviour to preserve. §3 should be read as void, not as a live
trade-off.

**What survives, and why it now points the other way.** The second argument for (a) was that a model can
emit a secret it inferred, recombined or hallucinated from context the caller never supplied, so the
output direction is not symmetric with the input one. That observation is true, but it is an argument
about **defaults**, not about **expressibility**. An admin who switches off "Redact secrets" has said this
system handles credentials deliberately; denying them the outbound half does not protect them, it makes
the switch lie.

**The positive case for (c), which §4 under-weighted.** `scanResponses` already exists as a *separate
direction switch*. Its existence means the design already treats output as its own question. The coherent
model is therefore: `scanResponses` decides **whether** the output direction is scanned, and
`redactPii`/`redactSecrets` decide **which kinds** — composing in both directions. That is exactly (c). It
adds no fields, and it makes the settings page stop lying.

(b) stays rejected on YAGNI: two more fields on a twelve-field record, for a capability nobody has asked
for.

### Two things (c) needs that this spec did not originally say

1. **The AI gateway's response path has the identical defect.** `AiGatewayGuardrails` calls
   `aiGuardrails.redactAll(scanned, aiGuardrails.resolveMinConfidence(target))` on the response direction,
   so fixing only `scanResponseText` would leave the gateway asymmetric — and that is the surface carrying
   the customer's own traffic. Both response paths move together. `redactAll` keeps its `EnumSet.allOf`
   meaning for callers that genuinely want every kind; the change is a category-aware sibling that the two
   response paths use.

   *Corrected 2026-09-05, after implementation:* there turned out to be **three** such paths, not two —
   the streaming redactor's call sites were the third, and the gateway's project-overlay streaming branch
   the fourth. All four now compose. `redactAll` kept its `EnumSet.allOf` meaning but lost every
   production caller in the process; §6 states the rule that replaced it.

2. **(c) makes `scanResponses = true` with both categories off a no-op that reads as enabled.** That is a
   new footgun (c) creates and (a) did not have. Handle it: disable or grey the `Scan responses` control
   when both category switches are off, or state plainly in the copy that it does nothing in that state.

### What the (a) work left behind, and must now be revised

(a) was implemented before the reversal, so each of these now asserts the opposite of the current
decision and is part of (c)'s work, not separate from it:

- Javadoc on `AiGuardrails#scanResponseText` and `#redactAll` stating the output direction is deliberately
  all-or-nothing.
- The comment on `AiGuardrailsTest#testScanResponseTextScrubsPiiAndSecretsWhenEnabled` telling a future
  reader that its two `false` flags are on purpose and must not be "fixed" — under (c) that test's own
  expectation changes.
- The paragraph in `.agents/ai-guardrails.md` recording the asymmetry as decided.
- The settings-page copy on both the automation and embedded pages, saying `Scan responses` covers both
  kinds "regardless of the Redact PII and Redact secrets switches above".

## 5. What (a) would have changed — **superseded, do not implement**

This section is kept as the record of the reversed decision. Every bullet in it asserts the opposite of
what shipped; none of it is work to do.

- ~~`AiGuardrails#scanResponseText` and `#redactAll` gain a javadoc paragraph stating that the output
  direction is deliberately all-or-nothing, that `redactPii`/`redactSecrets` govern the input direction
  only, and why the two directions differ.~~
- ~~`testScanResponseTextScrubsPiiAndSecretsWhenEnabled` gains a comment naming the invariant it pins, so
  a future reader does not "fix" the flags it deliberately sets to false.~~
- ~~`.agents/ai-guardrails.md` records the asymmetry in its settings section.~~
- ~~The settings page's `Scan responses` description says it covers PII and secrets regardless of the two
  switches above it.~~

## 6. Which scope's switches govern which direction

Three implementation rounds each found one more response path that did not compose the category switches:
the non-streaming gateway branch, then `StreamingResponseRedactor`'s call sites, then the gateway's
project-overlay streaming branch — the last of which had acquired a justification ("the overload's callers
have already decided streaming scanning applies") that read as deliberate. It was not deliberate; it was
the same omission wearing an explanation. What stops a fourth round is a stated rule, so here it is.

**The rule.** Every path that redacts model output derives its kind set from `AiGuardrails#enabledKinds`.
There is one derivation, and no path is exempt. A factory or overload that omits the policy argument does
not thereby acquire permission to redact every kind — it has to resolve one.

**The two questions stay separate at every scope.** `scanResponses` decides *whether* the output direction
is scanned; `redactPii`/`redactSecrets` decide *which kinds*. A path may legitimately bypass the first —
`newStreamingResponseRedactor(AiGuardrailsSettingsTarget)` exists precisely because its caller has already
established that streaming scanning applies for this deployment — but bypassing the first never implies
bypassing the second. **There is no legitimate policy-bypassing redactor.** A redactor whose caller has
settled the direction question still resolves the kind question.

**The gateway project overlay: additive in both directions.** The overlay's contract is that a project can
*widen* what its parent workspace scans, never narrow it. That contract was previously honoured on the
request direction and silently dropped on the response direction, so a project's own `Scan responses`
control did nothing unless a different scope happened to enable a category. The response direction now
unions the project's `redactPii`/`redactSecrets` onto the workspace's resolved kind set, the same shape
`applyProjectOverlay` already used inbound. A project can therefore widen but not narrow what its
responses scan — which is what "additive" means everywhere else in that class.

**Restoration is not scanning, and does not follow this rule.** `StreamingResponseRedactor` restores
tokens whenever a session is present, gated solely on `session == null` and never on the kind set. An
empty kind set makes the *scan* a no-op; it must never suppress restoration, or a realtime voice agent
would read a token back to the person who just spoke the value it stands for. Any future change to the
kind set must leave that gate alone.

## 7. Non-goals

- Per-*entity-type* output scanning (e-mails but not phone numbers). Categories are `SensitiveKind`, and
  nothing suggests finer is wanted.
- Changing the input direction, which already honours both switches and is correct.
