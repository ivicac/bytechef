# Guardrails PII Tokenization — Design (reversible tokens replacing irreversible redaction)

- **Date:** 2026-08-24
- **Branch:** `worktree-pii-tokenization` (off `0_732`)
- **Status:** Accepted — four forks were put to the user and answered (§12 records each, plus the
  decisions this document made on its own).
- **Ticket:** none filed yet.
- **Origin:** the observation that `"forward bob@acme.io's note to alice@acme.io"` redacts to
  `"forward [REDACTED_EMAIL]'s note to [REDACTED_EMAIL]"` — two different people collapsed into one
  indistinguishable string.
- **Builds on:** `2026-08-24-guardrails-sensitive-data-detector-spi-design.md` (the span pipeline this
  extends) and `2026-08-24-guardrails-opennlp-detector-design.md`.
- **Related:** `.agents/ai-guardrails.md` (engine and SPI reference).

## 1. Summary

Redaction today is **irreversible and type-only**. Every email in a prompt becomes the same six
characters, so the model loses not just the values but the *distinction between them*:

```
"forward bob@acme.io's note to alice@acme.io"
  → "forward [REDACTED_EMAIL]'s note to [REDACTED_EMAIL]"
```

A model asked to act on that cannot tell whose note goes to whom. Any reasoning about who-did-what
is silently corrupted — silently, because the output looks correctly redacted.

This spec replaces PII redaction with **reversible tokenization**: each distinct value becomes a
distinct, typed, stable token; the model reasons over tokens; and a response-direction pass
substitutes the real values back before the answer reaches the user.

```
  → "forward [PII_EMAIL_1_k3n9]'s note to [PII_EMAIL_2_k3n9]"
  → model answers about token 1 and token 2
  → "forward bob@acme.io's note to alice@acme.io"
```

**Secrets do not participate.** §3 explains why that is not a symmetry to fix.

## 2. What this changes about the product, stated once

This is a **security-posture change, not a formatting change**, and the user chose it with that
stated. Today a workspace with `redactPii` enabled has PII *destroyed* at the boundary. After this,
the same workspace has it *withheld from the model and then restored*. Those are different
guarantees:

- **Unchanged:** the model provider never receives the real value.
- **Changed:** the value now survives the round trip and reappears in the response.
- **New:** for the conversation's life, the platform retains a token→value mapping. That mapping is
  itself sensitive data, and §6 exists because of it.

An operator who chose redaction because they wanted values *gone* does not get that any more. There
is no mode flag (§12, D2) — this replaces the previous behaviour outright.

## 3. Secrets never round-trip

`SensitiveKind` already splits the two axes, and it carries this design:

| Kind | Treatment |
|---|---|
| `PII` | tokenized, restored on the response |
| `SECRET` | redacted to `[REDACTED_SECRET]`, **destroyed, unchanged** |

Restoring a secret would take an API key the guardrail successfully caught and paste it back into the
output. The whole point of catching it is that it should not travel. So the pipeline applies two
treatments in one pass over the resolved spans, and `[REDACTED_SECRET]` keeps its exact current form.

This is the third distinct payoff from making `kind` a closed two-value enum rather than folding it
into the open `category`. It was originally justified by the two policy toggles; it now also
separates reversible from irreversible handling.

## 4. Token format

```
[PII_EMAIL_1_k3n9]
 │   │     │  └── session discriminator (4 chars, per session)
 │   │     └───── per-value ordinal within the session
 │   └─────────── SensitiveSpan.category
 └─────────────── fixed prefix
```

Four requirements produced this shape:

- **Typed** — the model must know it is reasoning about an email, not an opaque blob.
- **Distinct per value** — the ordinal is what fixes §1.
- **Stable per value** — the same address anywhere in the conversation gets the same token, or the
  model cannot tell that two mentions are one person.
- **Session-bound** — a token minted by session `k3n9` is only ever restored by session `k3n9`.
  Cross-session restoration becomes impossible by construction rather than by policy.

ASCII only, no unicode. Models mangle exotic characters, and a token that does not survive the round
trip verbatim is a token that cannot be restored. The bracket-and-underscore shape also survives JSON
encoding and tokenizer splitting intact.

Restoration matches `\[PII_[A-Z][A-Z0-9_]*_\d+_[a-z0-9]{4}\]` and resolves only tokens whose
discriminator matches the live session.

## 5. Session scope — conversation, not request

**The token→value mapping is scoped to the conversation.**

This was forced by a finding, not chosen for elegance. Advisor ordering is:

| Advisor | Order |
|---|---|
| `AiGuardrailsAdvisor` | `Ordered.HIGHEST_PRECEDENCE` |
| chat memory | `HIGHEST_PRECEDENCE + 400` |

Lower runs first on the request, so guardrails rewrites the message **before** chat memory persists
it. History therefore stores whatever the guardrail produced. Today that is `[REDACTED_EMAIL]` —
meaningless but stable, so harmless. With request-scoped tokens it breaks:

```
turn 1   "email bob@acme.io" → "[PII_EMAIL_1_k3n9]" → persisted
         session k3n9 closes
turn 5   history replays "[PII_EMAIL_1_k3n9]"        ← dead token, unresolvable
         this turn mints "[PII_EMAIL_1_x8p2]" for someone else entirely
```

History would accumulate dead tokens whose ordinals mean something different in every turn — noise
that looks structured, which is worse than noise that looks like noise.

Conversation scope fixes it without moving the advisor: a token minted in turn 1 still resolves in
turn 5, history stays coherent, and the `HIGHEST_PRECEDENCE` position — documented as "the guardrail
floor must see the final outbound request before any other advisor's rewrite" — is untouched.

It also keeps **raw PII out of chat storage**: history holds tokens, exactly as it holds placeholders
today. The alternative (§12, D3) inverts that.

**Identifying the conversation.** The advisor reads `ChatMemory.CONVERSATION_ID` from the request
context, the same way `AiHubModelUsageAdvisor` already does. When it is absent — a one-shot call, a
unit-test `ChatClient`, the gateway's DTO path — the session degrades to request scope: tokenize,
restore, discard. That is correct rather than a compromise, because a call with no conversation has
no later turn to stay coherent with.

## 6. Session lifecycle and storage

`PiiTokenSession` holds two maps — token→value and value→token, the second being what makes a value
stable — plus its discriminator.

**Where it lives.** The mapping has exactly the lifetime and the distribution requirements of chat
memory itself: it must survive between turns, and in a multi-instance deployment turn 5 may land on a
different node than turn 1. Rather than invent a parallel storage decision, the session store follows
the chat-memory storage the deployment already uses.

**It must be encrypted at rest.** This is a store of detected personal data keyed by conversation.
`server/libs/core/encryption` (`Encryption` / `EncryptionKey`) is the platform's existing facility and
must be used; a plaintext mapping table would hand an attacker exactly the values the guardrail
exists to protect.

**Eviction** on conversation deletion, and on a TTL for conversations that are simply abandoned. A
session that outlives its conversation is a PII store nobody designed.

## 7. The ordering rule: scan, then restore

Response-direction scanning (`scanResponseText`) already redacts PII found in the **model's output** —
a different job from restoration, and the two collide if sequenced naively:

```
model: "I forwarded it to [PII_EMAIL_1_k3n9]"
  restore first → "I forwarded it to bob@acme.io"
  then scan     → "I forwarded it to [REDACTED_EMAIL]"     ← round trip achieved nothing
```

**Scanning runs first, restoration second.** Scanning then sees tokens (which match no PII pattern)
plus any genuinely *new* PII the model produced — so novel leakage is still caught, while known values
come back intact. Reversing this makes the entire feature a no-op, which is why it is a numbered
section rather than an implementation note.

## 8. Streaming

A token can straddle a chunk boundary. If `push` emits `[PII_EMA` and `IL_1_k3n9]` in separate frames,
restoration never matches and the user sees a raw token.

Structurally this is the problem `StreamingResponseRedactor` already solves: its safe-cut logic pulls
the cut back so it never emits through the middle of a matched span. Tokens are fixed-shape, so they
join the set of values the cut may not split. The existing machinery extends; it is not replaced.

Restoration then applies to each emitted segment after scanning, per §7.

## 9. Failure behaviour

- **Unknown or foreign-session token** — left untouched. Not restored, not stripped, not thrown on. It
  surfaces in the output as a visible signal that something is wrong, which is strictly better than a
  silent wrong substitution or a failed turn.
- **Session unavailable** (store unreachable, conversation id absent) — degrade to request scope. The
  turn still gets full protection; only cross-turn coherence is lost.
- **Session close** on completion, error **and** cancellation — the streaming path's `doFinally`, not
  just its success path.
- **New metrics** `pii_tokenized`, `pii_restored`, `token_unresolved`. The last one is the operator's
  signal that §9's first bullet fired.

## 10. Blast radius

- **The PII placeholder contract breaks.** `[REDACTED_EMAIL]` and friends are replaced by tokens
  across **12 Java files and 2 documents**. `[REDACTED_SECRET]` is untouched, so files that only pin
  the secret placeholder do not move. Every pinned PII assertion becomes a token-shape assertion.
- **One of those documents is customer-facing**:
  `docs/content/docs/platform/automation/deploy/ai-gateway.md` describes the redaction behaviour to
  users. This is not an internal rename — published documentation states what the product does with
  their data, and §2's posture change has to be reflected there truthfully rather than quietly
  reworded. Treat that edit as part of the feature, not as cleanup.
- **Every workspace with `redactPii` enabled** changes behaviour (§2).
- **A new persistent store** of sensitive data, encrypted, with an eviction policy.
- The gateway adapter threads a session from `apply` to `redactResponse`; it has both directions
  within one HTTP exchange, so this is threading, not redesign.

## 10a. Delivery in two phases

The design is one coherent feature, but it is **too large for a single implementation plan**, and the
split is clean because the second half is additive:

**Phase 1 — tokenization with request-scoped sessions.** Token format, the two-treatment pass
(§3), scan-then-restore (§7), streaming safe-cut extension (§8), failure behaviour (§9), advisor and
gateway wiring, and the contract/doc updates (§10). Sessions live for one request only. This is
shippable and fully protective on its own; what it lacks is cross-turn coherence, so a value
mentioned in turn 1 gets a fresh token in turn 5.

**Phase 2 — conversation-scoped session store.** The persistent, encrypted, evictable store of §6,
keyed by `ChatMemory.CONVERSATION_ID`, with the request-scoped path of phase 1 remaining as the
documented fallback when no conversation id is present.

Phase 1 must not be shipped to users on its own **without** phase 2 if chat history is in play — per
§5, request-scoped tokens persisted into history become dead tokens on later turns. Either phase 2
lands with it, or phase 1 ships only where history is not retained. That constraint belongs in
phase 1's plan, not discovered during it.

## 11. Non-goals

- **Tool boundaries.** Tool arguments and tool results remain uncovered — the parent agent still does
  not re-scan tool output. That gap is real and documented in `.agents/ai-guardrails.md`; it is a
  separate piece of work with its own spike.
- **RAG / vector-store retrieval** boundaries.
- **Secrets** stay destroyed (§3).
- **No new setting** — this replaces rather than adds a mode (§12, D2).
- No change to blocked terms, injection detection, moderation, or `BlockingMode`.

## 12. Decisions

| # | Decision | Who | Rationale |
|---|---|---|---|
| D1 | **Full round trip** — tokenize and restore, not merely distinguish | **User**, from three options | Distinguishing alone would let the model reason correctly but still return placeholders to the user. |
| D2 | **Replace redaction entirely**, no mode flag | **User**, from three options | Chosen with the posture change stated (§2). Costs the placeholder contract and changes behaviour for every existing `redactPii` workspace. |
| D3 | **Conversation-scoped sessions**, history keeps tokens | **User**, from three options | The alternative — storing raw text and tokenizing at send time — is what the reference project does, but it puts real PII into chat storage and requires moving the advisor off `HIGHEST_PRECEDENCE`. |
| D4 | **Explicit session handle**, no thread-locals | **User**, from three approaches | The streaming path is Reactor; thread affinity is not guaranteed, and this repo has been bitten by thread-local context propagation before. |
| D5 | Secrets never tokenize | This document | Restoring a caught credential into the output defeats catching it. |
| D6 | Scan before restore | This document | The reverse order makes the feature a no-op (§7). |
| D7 | Tokens carry a session discriminator | This document | Makes cross-session restoration impossible by construction rather than by policy. |
| D8 | Unknown tokens are left untouched | This document | A visible anomaly beats a silent wrong substitution or a failed turn. |
| D9 | Session store encrypted at rest, reusing `core/encryption` | This document | It is a store of detected personal data; plaintext would hand over exactly what the guardrail protects. |

## 13. Open questions

None blocking. One deliberately deferred:

- **Whether restoration should be suppressible per surface.** A workspace might want restoration in an
  interactive chat but not in a logged/audited automation run. No such need has been expressed, and
  adding it now would reintroduce the mode flag D2 removed. Revisit if it turns up.
