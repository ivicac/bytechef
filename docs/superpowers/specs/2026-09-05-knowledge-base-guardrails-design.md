# Knowledge-base guardrails — design

**Status:** **Proposed, and deliberately parked by the maintainer on 2026-09-05** — not to be picked up until
they say otherwise. Two decisions (D1, D2) remain open; no plan yet. §1a describes a live gap, so parking is
a scheduling choice rather than a judgement that the finding is wrong.
**Ticket:** wants its own ticket
**Date:** 2026-09-05
**Relates to:** `2026-08-25-guardrails-consolidation-design.md` (which names knowledge bases a non-goal), `2026-08-24-guardrails-pii-tokenization-design.md`

## 1. The problem

Knowledge bases have **no guardrail coverage of any kind**. A grep for `AiGuardrails` or `guardrail`
across `platform-knowledge-base`, `automation-knowledge-base` and `embedded-knowledge-base-graphql`
returns nothing, and `.agents/ai-guardrails.md` does not mention knowledge bases at all — so the gap is
not merely unclosed, it is unrecorded.

Three distinct exposures, and they are not equally serious.

### 1a. Retrieved content reaches the model unscanned — the one that matters

This is the significant finding, and it is a direct hole in the guarantee the whole tokenization design
rests on.

`GuardrailAdvisorOrder.WORKSPACE_FLOOR` is `Ordered.HIGHEST_PRECEDENCE`. Spring AI executes the advisor
chain in ascending order on the request, so the floor runs **first** — it scans, redacts and tokenizes the
user's message. Retrieval-augmentation happens afterwards: `QuestionAnswerRag` attaches Spring AI's stock
`QuestionAnswerAdvisor`, which appends retrieved documents to the prompt at its own, later order. The same
holds for the modular path (`ModularRag`, `VectorStoreDocumentRetriever`).

So the sequence is: **floor scans the user text → RAG appends retrieved documents → the model sees both.**
Retrieved chunks are never scanned, never redacted and never tokenized.

The tokenization design's central claim is that PII does not reach the model provider. For any agent with a
knowledge base attached, that claim is false for every byte of retrieved context — and it fails silently,
because the floor did run, did report, and did its job on the half of the prompt it could see.

This is worse than an unguarded surface. `GuardrailSurfaceCoverageTest` exists precisely to force every
model-reaching call site to be either guarded or named with a reason. A RAG-augmented agent passes that test
— it *is* behind a guarded `ChatClient` — while sending unscanned content to the provider.

### 1b. Ingestion stores PII permanently, unredacted

Documents are chunked, embedded and written to pgvector as-is. Nothing inspects them. PII in an uploaded
document is stored indefinitely, is searchable by similarity, and survives long after the source document
would have been deleted under any retention policy. Unlike 1a this is not a disclosure to a model provider
— it is a data-at-rest and retention problem.

### 1c. Responses quoting retrieved PII

Already covered *if* `scanResponses` is on: the floor is outermost, so it runs last on the response and
scans the model's output whatever the text came from. No new work; noted so the spec's coverage claim is
complete.

## 2. Why the ordering cannot simply be changed

The obvious repair — move the floor after the RAG advisor so it sees the augmented prompt — is wrong, and
for a reason this codebase has already paid for once.

The consolidation spec's §6 records an emergent defect from exactly this class: two advisors both declared
`HIGHEST_PRECEDENCE`, Spring AI broke the tie toward the last registration, and the per-node check ended up
wrapping the workspace floor — where it judged the floor's *transformed* text and blocked responses that
merely echoed the caller's own restored input. Advisor ordering here is load-bearing and its failures are
non-obvious.

More fundamentally, the floor must stay outermost for its other duties: it opens the token session that the
tool boundary and the response restore both depend on, it is the last thing to touch the response, and
`GuardrailAdvisorOrder.NODE_CHECK` is defined relative to it. Reordering to catch RAG would disturb all of
that to fix one of the floor's several jobs.

## 3. Decision — scan at the retrieval boundary, not by reordering

Retrieved documents are scanned where they enter the prompt, by the retrieval path itself, using the same
engine the floor uses. The floor keeps its position and its duties; the retrieval boundary gains its own.

That gives the same shape the MCP outbound boundary already has: a second place that calls
`AiGuardrails`, resolved from the same workspace settings, rather than a second advisor competing for
position in the chain.

### D1 — redact, or tokenize? **Maintainer decision required.**

**(a) Redact.** Retrieved PII is replaced with `[REDACTED_*]` before the model sees it. Simple, and
correct for the common case where the model needs to reason about the *content* rather than the specific
value.

**(b) Tokenize into the caller's session.** Retrieved PII becomes `[PII_*_n_nonce]` in the same session the
floor opened, so a value the agent then passes to a tool or returns is restored on the way out, exactly as
caller-supplied PII is. Strictly more capable, and consistent with the rest of the design.

**Recommendation: (b).** The floor already opened a session for this call; joining it costs one argument.
(a) would create a surface where a value is irreversibly destroyed on one input path and reversibly
protected on another, which is precisely the input/output asymmetry the per-category scanning spec is also
grappling with. But (b) is more work and the session's ordinal high-water mark has to stay coherent across
two writers.

### D2 — ingestion too, or retrieval only? **Maintainer decision required.**

**(a) Retrieval only.** Closes 1a; leaves 1b. The store still holds raw PII, but nothing reaches a provider.
Cheapest, and reversible — a later ingestion pass can still be added.

**(b) Ingestion as well.** Documents are scanned as they are chunked, and PII never enters the vector store.
Closes 1b too. But it is destructive and irreversible in a way retrieval scanning is not: the stored chunk
is what future retrieval returns, so a false positive silently degrades the knowledge base permanently, and
re-ingestion is the only repair. It also changes what similarity search matches, since the embedded text
differs from the source.

**Recommendation: (a) first.** 1a is the live disclosure to a third party; 1b is a retention concern on
data the customer chose to upload to their own store. Doing (a) alone is honest and complete against the
stated guarantee. (b) deserves its own spec, because "the knowledge base silently no longer contains what
you uploaded" is a product decision, not a guardrail one.

## 4. What changes (under D1=(b), D2=(a))

- A retrieval-boundary hook in the two RAG paths — `QuestionAnswerRag` and `ModularRag`/
  `VectorStoreDocumentRetriever` — that passes retrieved document text through the guardrails engine with
  the call's own settings target and token session before it is appended to the prompt.
- The session must be reachable there. It is already on the `ToolContext` via `PiiTokenSessionToolContext`,
  which is how `PiiTokenBoundaryToolCallingManager` reaches it; the retrieval path needs the same access.
- A new surface constant in `GuardrailSurface` for the retrieval boundary, so its metrics are attributable
  and `GuardrailSurfaceCoverageTest` can pin it.
- `.agents/ai-guardrails.md` gains a knowledge-base section. It currently has none, which is why this went
  unrecorded.

## 5. Testing

- **The failing-first test:** an agent with a knowledge base whose documents contain PII, over a tokenizing
  workspace, asserting the text handed to the model contains the raw value today and does not after. This is
  the test that demonstrates the hole; write it before anything else.
- A retrieved value tokenized into the caller's session is restored on the way out, and its ordinal does not
  collide with one the floor minted for the same call.
- A knowledge base with no PII is byte-identical through the boundary — no false-positive rewriting of
  retrieved context, which would degrade answer quality invisibly.
- Both RAG paths, not just `QuestionAnswerRag`. The modular path is the easier one to forget.
- Ingestion is untouched (D2=(a)): a document with PII is stored as uploaded.

## 6. Non-goals

- Ingestion-time scanning (D2=(b)) — wants its own spec.
- Per-document or per-knowledge-base guardrail settings. Workspace settings govern, as everywhere else.
- Changing advisor ordering (§2).
- Embedding-model exposure: the *embedding* provider still sees raw document text at ingestion. That is a
  real and separate disclosure, and closing it is exactly D2=(b)'s territory.
