# Restoration and the destination boundary — design

**Status:** **D3 decided ON by the maintainer, 2026-09-05.** Otherwise as proposed; no implementation plan yet.
**Ticket:** 732 follow-on; wants its own ticket
**Date:** 2026-09-05
**Relates to:** `2026-08-25-guardrails-consolidation-design.md` §6 (the ordering rule this narrows), `2026-08-31-tool-boundary-pii-restoration-design.md` (the other ungated outbound boundary — see the correction in §1), `2026-08-24-guardrails-pii-tokenization-design.md` (Phase 1 tokenization)

## 1. The problem

Workspace guardrails tokenize a caller's PII on the way in and restore it on the way out. The rule that makes that safe was stated in the consolidation spec §6:

> **Restoration only ever returns a value to the party that supplied it.**

That rule is true on a chat surface. It is **false on the canvas AI Agent**, and the gap is not small.

`AiAgentChatAction#perform` returns the model's text — already restored by `AiGuardrailsAdvisor#applyResponseGuardrails`, which is outermost and restores last — as the **workflow task output**. Downstream nodes read it as `${aiAgent.output.response}`. The next node may be a Slack post, a Google Sheets append, an outbound HTTP call, or an email to a third party.

So on this surface the sentence has an unexamined word in it. The "party that supplied" the PII is the person who triggered the workflow; the party that *receives* the restored value is whatever the workflow author wired downstream. Those are not the same party, and nothing in the current design notices the difference.

### What makes this more than theoretical

A workflow author who wants that protection reaches for the control that appears to give it — a per-node `sanitize-text` → `pii` guardrail — and it does nothing, for a reason that is invisible from the canvas:

- On the request, the floor replaced the caller's e-mail with `[PII_EMAIL_ADDRESS_1_k3n9]`.
- On the response, the node sanitizer runs **inside** the floor (`DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1` versus `GuardrailAdvisorOrder.WORKSPACE_FLOOR`). It sees the token. Its detectors do not match a token, so it masks nothing.
- The floor then restores, and the real e-mail lands in the task output.

The author configured a PII guardrail, the guardrail reported no violation, and the PII reached the downstream node anyway. The consolidation spec §6 documents the masking half of this ("a node `sanitize-text` → `pii` on input under a tokenizing workspace masks nothing") and argues it is acceptable *because the value only goes back to the caller*. On the canvas agent that premise does not hold.

### Correction, 2026-09-05: the tool boundary does NOT gate this either

An earlier draft of this section claimed `PiiTokenBoundaryToolCallingManager` already gates restoration with `PiiTokenBoundaryPolicy`, and that this design merely generalises it. **That is wrong, and reading the class corrected it.** The policy governs the INBOUND direction only — which `SensitiveKind`s to tokenize or redact in a tool's *result* before it reaches the model. The OUTBOUND direction is unconditional: the class's own javadoc says each tool call's `arguments` is "passed through `PiiTokenSession#restoreWithUnresolvedCount` before the delegate runs", with no policy consulted, failing open on a token it cannot resolve.

So there is no precedent to generalise. There are **two** outbound boundaries and neither is gated:

- **tool-call arguments** — the agent calls a Slack tool; the tool receives the caller's real e-mail;
- **workflow task output** — the agent returns; the downstream Slack node receives the caller's real e-mail.

Those are the same question with the same shape, reached by two routes that differ only in whether the author wired the integration *inside* the agent or *after* it. A design that gated one and not the other would be arbitrary, and an admin would have no way to reason about which of their workflows was protected.

This strengthens the case for the design rather than weakening it — the gap is wider than the first draft described — but it also means this spec defines a policy that does not yet exist anywhere, rather than extending one that does. See D7.

## 2. Three destinations, not one

Restoration today makes one undifferentiated decision. There are three destinations, and they have different answers:

| Destination | Who receives the value | Today | Correct |
|---|---|---|---|
| A tool call's arguments | An external system the workflow author chose | **Unconditional** — the policy governs the inbound result, not this | **unexamined** |
| A chat response | The human who typed it | Always restored | ✅ as-is |
| A workflow task output | Whatever node the author wired next | Always restored | **unexamined** |

The chat destinations — AI Hub threads, the Copilot panel, and every surface whose response is rendered back to the person who produced the input — are correct as they are, and this design does not touch them. §6's rule holds there exactly as written.

Rows one and three are in scope and share one setting; row two is correct as it stands.

## 3. Why the obvious repairs do not work

**"Let the node sanitizer mask it."** It cannot see it. The node runs inside the floor and receives a token, and a token is not PII by any detector's reckoning. Making node detectors match tokens would mean teaching every child about the floor's notation and would still leave the node unable to distinguish its own workspace's tokens from a replayed foreign one.

**"Have the floor skip restoring spans a node masked."** This was the first shape considered and it is too narrow. It only helps when a node sanitizer is configured, it requires a response-direction channel between the two layers that does not exist, and it still restores everything on the far commoner configuration where no node guardrail is present at all — which is precisely the configuration a workspace-level policy exists to protect.

**"Never restore on the canvas agent."** Breaks the legitimate majority: an agent asked to draft a reply to the address the user supplied must see and emit that address. Tokens leaking into a Slack message are a worse failure than the one being fixed.

The repair has to live where the decision belongs — at the boundary, as policy. No boundary demonstrates that today; both outbound ones restore unconditionally, which is why this design defines the policy rather than extending one.

## 4. Decision

**Restoration into a workflow task output becomes a policy decision, resolved from workspace guardrail settings, on the same shape as the tool boundary.**

Concretely:

- `AiGuardrailsAdvisor` learns whether the response it is about to restore is destined for a **conversation** or a **workflow output**. That is a property of the surface, not of the request: `GuardrailSurface.AI_AGENT` is a workflow output; `COPILOT`, `AI_HUB` and the rest are conversations. No new parameter is threaded anywhere — the advisor already holds its surface through `AiGuardrailMetrics#getSurface`.
- For a workflow-output surface, restoration consults a new workspace setting, `restoreIntoWorkflowOutput`. When it is off, the response keeps its tokens and the tokens reach the task output.
- **The same setting gates tool-call argument restoration** in `PiiTokenBoundaryToolCallingManager`, for the reason the correction above gives: an integration wired inside the agent and one wired after it are the same destination reached two ways, and gating only one would be arbitrary. The setting's name is therefore about the destination class, not about the workflow-output route specifically.
- The setting is per workspace, alongside the guardrail settings that already exist, and never per node. §7 established why a workflow author must not hold a boundary of this kind; the same argument applies here, with the same holder — the workspace admin — as every other guardrail policy.
- Secrets are unaffected: a `SECRET` span was never tokenized, so there is nothing to restore and nothing to decide.

### D3 — the default, and why it is the maintainer's call

Two defaults are defensible and they trade different harms:

**Default ON (restore, today's behaviour).** No shipped workflow changes. The exposure stays until an admin turns it off, but it is now documented, discoverable in settings, and observable — an admin can watch the `pii_restored` metric on the `ai_agent` surface before deciding, exactly the path observe mode was built for.

**Default OFF (do not restore).** Closes the exposure immediately for every deployment. But it silently changes the *data* every existing canvas AI Agent hands downstream: a workflow that posts the agent's answer to Slack starts posting `[PII_EMAIL_ADDRESS_1_k3n9]` instead of an address, with no error and no failed step. That is a data-corruption-shaped regression, and it lands on workflows whose authors never opted into tokenization in the first place.

**Decided by the maintainer, 2026-09-05: default ON.** A documented, observable, admin-flippable exposure is a smaller harm than silently rewriting the output of running workflows, and the whole point of shipping observe mode first was to make exactly this kind of change safe to adopt deliberately.

Two consequences follow from that default and belong in the implementation plan rather than being left implicit. First, the setting ships doing nothing on every existing deployment, so the plan's first shippable increment must be the metric — an admin cannot decide to turn it off without first seeing `pii_restored` on the `ai_agent` surface. Second, the exposure is real until an admin acts, so the settings copy and `.agents/ai-guardrails.md` must state it plainly rather than describing the toggle neutrally.

## 5. What changes

- `AiGuardrailsWorkspaceSettings` gains `restoreIntoWorkflowOutput` (boolean). Enum ordinals are untouched; this is a new column with a default, so existing rows keep today's behaviour whichever default D3 picks for new ones.
- `AiGuardrails` gains a resolver for it, on the same shape as `resolveToolBoundaryPolicy(Long)`.
- `AiGuardrailsAdvisor#applyResponseGuardrails` and the streaming redactor's tail consult it, and skip the restore step when it is off. **Scanning is not skipped** — response scanning and restoration are separate steps and only the second one is in question.
- A new metric event, `restore_suppressed`, recorded once per call when the policy withheld a restoration. Without it an admin cannot tell "no tokens to restore" from "restoration withheld", and those are the two states the setting exists to move between.
- The settings page gains the toggle, with copy that names the actual consequence: downstream workflow nodes receive placeholders rather than values.
- `.agents/ai-guardrails.md` §6's ordering rule gains the qualification: restoration returns a value to the party that supplied it **on a conversation surface**; on a workflow surface the destination is whatever the author wired next, and policy decides.

## 6. Non-goals

- **Per-node restoration control.** Rejected for the reason §7 rejected a per-node conversation-scope checkbox: it puts a security boundary in the hands of an author who cannot see what is at stake, and here it would additionally be a boundary about *other people's* data.
- **Per-entity-type restoration.** The tool boundary gates by `SensitiveKind`, not by category, and there is no evidence yet that anyone wants "restore e-mails but not SSNs" into a workflow output. If that demand appears it fits this shape without redesign.
- **Changing the chat surfaces.** They are correct today and stay untouched.
- **Restoring inside a node's own output.** A node sanitizer's `<TYPE>` masks are irreversible by construction and this design does not make them reversible.

## 7. Testing

- A test proving the tool-argument half of the defect: an agent whose tool call carries the caller's tokenized e-mail as an argument, asserting the delegate receives the real value today and the token once the setting is off.
- A test proving the workflow-output half before the fix: a canvas-surface advisor over a tokenizing workspace, a response echoing the caller's tokenized e-mail, asserting the returned text carries the real e-mail. It must go red when the fix is applied with the setting off, and stay green with it on.
- A chat-surface control asserting that surface still restores unconditionally — the fix must not touch it.
- A streaming test: the tail flush path restores separately from `adviseCall` and is the easier of the two to leave behind.
- A metric test for `restore_suppressed`, since an admin's ability to adopt the setting depends on it.
- A test that a `SECRET` span is unaffected either way, pinning that this design cannot resurrect a redacted secret.

## 8. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Restoration is a per-destination decision; the destination is derived from the surface | The advisor already holds its surface; a request-carried flag would be author-influenceable, which is the thing being guarded against |
| D2 | The control is a workspace setting, never per node | Same holder as every other guardrail policy, and the same argument §7 used to refuse a per-node conversation-scope checkbox |
| D3 | **Default ON** (today's behaviour), with the exposure documented and observable — decided by the maintainer, 2026-09-05 | Silently rewriting the output of running workflows is a data regression with no error; a documented, admin-flippable exposure is the smaller harm. The metric is therefore not optional: the setting is unadoptable without it |
| D4 | Scanning stays unconditional; only restoration is gated | They are separate steps, and disabling response scanning was never in question |
| D5 | `restore_suppressed` is required, not optional | Without it "nothing to restore" and "restoration withheld" are indistinguishable, and the setting is unadoptable |
| D6 | Per-entity-type restoration deferred | The inbound tool policy gates by kind and nobody has asked for finer on the outbound side; the shape accommodates it later without redesign |
| D7 | The setting gates BOTH outbound boundaries — tool-call arguments and workflow task output | **Added 2026-09-05 after correcting a factual error in §1.** Neither is gated today; the first draft wrongly claimed the tool boundary was. They are one destination class reached by two routes, and an admin cannot reason about a protection that covers only the route they did not take |
