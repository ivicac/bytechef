# Tool-Boundary PII Restoration — Design

**Status:** design, not yet planned
**Builds on:** `2026-08-24-guardrails-pii-tokenization-design.md` (Phase 1 tokenization),
`2026-08-25-guardrails-consolidation-design.md` (the CE detection core)

## 1. The problem is two bugs, not one

PII tokenization replaces a detected value with a reversible, per-session token
(`[PII_EMAIL_ADDRESS_1_k3n9]`) before a prompt leaves ByteChef, and substitutes the real value back
into the model's completion. `AiGuardrailsAdvisor` tokenizes `UserMessage` and `SystemMessage`;
everything else is passed through untouched.

Tools sit outside that round trip, in both directions.

**Outbound — the tool malfunctions.** The model sees a token, echoes it into a tool call, and
`send-email` executes with `[PII_EMAIL_1_k3n9]` where the address should be. The feature silently
breaks any tool whose argument is the very data being protected.

**Inbound — PII reaches the provider.** A tool that *returns* PII — a CRM lookup, a customer record
query, a database read — has its result appended as a `ToolResponseMessage` and sent to the model on
the next turn, in clear. `AiGuardrailsAdvisor` only rewrites USER and SYSTEM messages, so nothing
touches it.

The second is the more serious. The first is a visible malfunction someone reports; the second is a
live leak of exactly the data tokenization exists to contain, and it is invisible.

## 2. Where the boundary is enforced

**A `ToolCallingManager` decorator**, not a `ToolCallback` decorator.

Wrapping each callback would match four existing decorators — `ApprovalGateToolCallback`,
`RehydrateContextToolCallback`, `MeteredToolCallback`, `ProgressReportingToolCallback` — and would be
the more idiomatic choice on familiarity alone. It is rejected for one decisive reason: **AI Hub
resolves tools dynamically at call time** through tool search and `MapToolCallbackResolver`, so a
decorator applied where tools are *registered* cannot see a callback that is resolved later. It would
cover most tools, miss some, and give no signal about which.

The registration-site failure mode is also one this repository keeps hitting: a component wired into
no app's classpath, picker options that had to agree with a catalog and did not. "Did we wrap every
tool?" becomes a question someone must keep answering correctly, forever, with silence as the cost of
getting it wrong.

`ToolCallingManager.executeToolCalls(Prompt, ChatResponse)` sees arguments and results together, and
sits below every registration path, so the question stops being asked.

**Two wrap points, both verified to exist:**

- `AgentToolCallingManagers.getToolCallingManager(...)` — the sole factory for the AI Agent component,
  used by all three actions. `AiAgentRealtimeChatAction` goes through it too: it is a WebSocket
  transport around ordinary streaming calls, **not** provider-side tool execution, so realtime is in
  scope rather than an unsupported surface.
- AI Hub's chain, where `LazyToolCallingManager` and `UnknownToolRecoveringToolCallingManager` already
  compose. This adds a third layer to an existing stack rather than introducing the pattern.

## 3. Placement: CE

`PiiTokenBoundaryToolCallingManager` lives in CE, in `platform-ai-sensitive-data-service`, taking
`SensitiveDataRedactor`.

It needs no EE policy. The policy decision was already made when the advisor chose to open a session,
so **a session being present in `ToolContext` is itself the signal that tokenization is active**. No
session, no work, no dependency on workspace settings. That preserves the engine-is-CE /
policy-is-EE split the consolidation spec established.

## 4. How the session reaches the tool

`AiGuardrailsAdvisor` opens the session in `adviseCall` / `adviseStream` and must place it into the
request's `ToolContext`.

This is not optional plumbing. **Tool calls run on worker threads that do not inherit ThreadLocal
context** — `EnvironmentContext`, `TenantContext` and `SecurityContext` are all lost, which is the
entire reason `RehydrateContextToolCallback` exists. A ThreadLocal-held session would be invisible at
the tool boundary. `ToolContext` is the channel `AgentToolInvocationContext` already uses for exactly
this hop, so the session follows a proven path rather than a new one.

The session object travels by reference within a single call in one JVM. It is never serialised.

## 5. Data flow

1. Advisor opens session `S`, tokenizes USER/SYSTEM messages, puts `S` into `ToolContext`.
2. Model returns tool calls whose arguments contain tokens.
3. The decorator restores tokens in each argument payload using `S`, delegates to the wrapped manager,
   then tokenizes PII in each result using `S`.
4. Results return to the model as `ToolResponseMessage`s containing tokens.
5. Loop, for as many tool rounds as the model takes.
6. Advisor scans and restores the final completion, then closes `S`.

Because every step shares one session, **a value keeps one stable token for the whole call** — the
same address tokenized in the prompt, echoed into a tool call, and returned by a later tool is one
identity the model can reason about, not three.

## 6. Error handling is deliberately asymmetric

The two directions fail in opposite ways, because their failure costs are not symmetric.

**Argument restoration fails open.** An unresolved token — the model hallucinated
`[PII_EMAIL_9_zzzz]`, or referenced a token from a prior call — records `token_unresolved` and leaves
the text as it stands. The tool then malfunctions visibly. This matches the precedent already set in
the response direction, and aborting the run on a model hallucination would be a worse trade.

**Result tokenization fails closed.** If tokenization cannot complete, the result is **redacted**
rather than returned raw. Failing open here would hand the provider the exact data the feature
exists to contain, and a silent leak is worse than a degraded answer.

Stating this asymmetry explicitly is the point. A single "fail open, the engine never blocks a call"
rule read across both directions would produce the leak.

## 7. Secrets keep their invariant

Secrets are redacted irreversibly and never round-trip — on any path, in any direction. A secret in a
tool result is redacted, not tokenized, and nothing at the tool boundary can restore one. The
existing `SensitiveKind` split already carries this; the decorator must not weaken it.

## 8. Observability

Two new incidence events, recorded at most once per tool invocation, matching the existing
`containsKind` idiom rather than counting per span:

- `tool_args_restored` — at least one token was substituted into a tool's arguments.
- `tool_result_tokenized` — at least one value in a tool's result was tokenized.

`token_unresolved` is reused rather than duplicated; it already means what it needs to mean here.

Without these an operator cannot tell a tool boundary that is working from one that is never reached
— which, given the whole point of §2 is coverage, is the thing most worth being able to see.

## 9. Testing

The cases that pin the reasoning above, rather than the ones that merely exercise the happy path:

- **A dynamically resolved tool-search callback is covered.** This is the case that decided against
  the per-callback approach; if it is not tested, that decision is unverified.
- **The session reaches the manager when the tool runs on another thread.** The constraint in §4 is
  the one that would silently produce a no-op.
- **A secret in a tool result is redacted, not tokenized**, and cannot be restored.
- **Result tokenization failing closed** — a forced failure redacts rather than returning raw PII.
- **An unresolved argument token does not abort the run**, and records `token_unresolved`.
- **One value, one token, across the whole call** — prompt, tool argument, and tool result agree.

## 10. Blast radius

| Area | Change |
|---|---|
| CE service | New `PiiTokenBoundaryToolCallingManager`; no change to `SensitiveDataRedactor`'s contract |
| EE guardrails | `AiGuardrailsAdvisor` puts the session into `ToolContext`; two metric names |
| Agent component | `AgentToolCallingManagers` wraps its returned manager |
| AI Hub | One more layer in the existing `ToolCallingManager` chain |
| Docs | `.agents/ai-guardrails.md`; the customer-facing PII page gains the tool-boundary behaviour |

## 11. Non-goals

- **The AI Gateway.** It proxies chat completions and has no tools of its own. Nothing to intercept.
- **Per-tool exemptions.** No mechanism for "this tool may not see real PII." Nothing needs it today,
  and the natural home for that policy is authorization, not tokenization.
- **Cross-call token memory.** Tokens remain scoped to one call, as in Phase 1. A token from a
  previous call is an unresolved token, handled by §6.
- **A separate workspace setting.** The existing PII protection setting governs the tool boundary too;
  an operator who wants tool output visible to the model turns PII protection off, which is the
  honest description of that choice.

## 12. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Cover both directions | Inbound is a live leak to the provider; outbound is a functional break. They share one seam and one session |
| D2 | Decorate `ToolCallingManager`, not `ToolCallback` | Registration-time wrapping cannot see AI Hub's dynamically resolved tools, and a missed site fails silently |
| D3 | Decorator lives in CE | A session in `ToolContext` is itself the policy signal; no EE dependency needed |
| D4 | Session travels via `ToolContext` | Tool calls run on worker threads that lose ThreadLocal context |
| D5 | Arguments fail open, results fail closed | A hallucinated token should not abort a run; an untokenizable result must not reach the provider |
| D6 | Reuse the existing PII protection setting | No new config surface, consistent with the confidence-threshold decision |
| D7 | Realtime is in scope | `AiAgentRealtimeChatAction` uses `AgentToolCallingManagers`; its WebSocket layer is transport, not provider-side tool calling |
| D8 | Secrets never restored at the boundary | Unchanged invariant from Phase 1 |
