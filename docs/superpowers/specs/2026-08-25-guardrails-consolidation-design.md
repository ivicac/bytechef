# Guardrails Consolidation — Design

**Status:** **sub-project 1 implemented** (plan: `docs/superpowers/plans/2026-08-25-guardrails-core-extraction.md`).
Sub-project 2 (front-end reconciliation, §6) is **implemented** (plan: `docs/superpowers/plans/2026-09-04-front-end-reconciliation.md`). Sub-project 3 (conversation-scoped sessions, §7) is **implemented** (plan: `docs/superpowers/plans/2026-09-05-conversation-scoped-sessions.md`).
**Supersedes in part:** `2026-08-24-guardrails-pii-tokenization-design.md` §5, §6, §10a (session scope and storage)
**Relates to:** Phase 1 tokenization, landed on `worktree-pii-tokenization`

## 1. The problem

ByteChef has **three independent implementations of sensitive-data detection and masking**, none of which knows the others exist.

| | Where | Detection | Verdict | Reversible |
|---|---|---|---|---|
| **Platform guardrails** | EE `platform-ai-guardrails` | `SensitiveDataDetector` SPI; `RegexPiiDetector` has **5** patterns | Block · redact · tokenize | Yes (Phase 1) |
| **Node guardrails** | CE `ai/agent/guardrails` | `PiiDetectorUtils` has **36** patterns; `SecretKeyDetectorUtils` | Block · mask | No |
| **Workflow actions** | CE `universal-text` Mask/Unmask | An **LLM** is prompted to find PII | Mask | Yes — an LLM is asked to substitute values back |

Three consequences, each independently sufficient to justify this work:

**The paid engine detects less than the free one.** EE's `RegexPiiDetector` covers `EMAIL`, `SSN`, `CC`, `PHONE`, `IP`. CE's `PiiDetectorUtils` covers 36 entity types whose *names* match Microsoft Presidio's taxonomy (regex-level correspondence was never checked at the time this table was written — see §5's revised framing) — `EMAIL_ADDRESS`, `US_SSN`, `UK_NINO`, `SG_NRIC_FIN`, `IN_AADHAAR`, `IT_FISCAL_CODE`, `AU_TFN`, `PL_PESEL`, `ES_NIF`, `IBAN_CODE`, `CRYPTO`, `MEDICAL_LICENSE` and more, with a `PiiDetectorUtilsParityTest` exercising that vocabulary (see §5 for what that test does and does not establish). The same concepts carry different names in each (`EMAIL` vs `EMAIL_ADDRESS`, `CC` vs `CREDIT_CARD`).

**Security-critical logic is duplicated.** A fix applied to one detector and missed in another is a guardrail bypass, not a style drift. This exact argument was accepted three separate times during Phase 1's reviews for duplication *within* one module; it applies with more force across three.

**The mechanisms interact in ways nobody designed.** The EE advisor sits at `Ordered.HIGHEST_PRECEDENCE` and wraps the chain; `SanitizeTextAdvisor` orders itself at `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1`. Both are wrapping advisors, so the response unwinds in reverse and an outbound `Sanitize Text` + `PII` sees **tokens**, finds nothing, and EE restores real values *after* it ran. That behaviour is defensible (see §6) but it is an accident of ordering that no one chose and nothing documents. Reading the code later showed the accident is deeper than this paragraph knew: `CheckForViolationsAdvisor` ties the EE advisor at `HIGHEST_PRECEDENCE`, and Spring AI resolves the tie toward the *later* registration, so the node check — not EE — is outermost today (§6.0).

## 2. The principle

**One detection-and-replacement engine. Three policy front-ends.**

The three mechanisms overlap on *mechanics* and differ legitimately on *policy*. Consolidation unifies the first and preserves the second — it does not collapse three products into one.

| Shared — one implementation | Different — legitimately three |
|---|---|
| Span model, detector SPI | Where policy comes from |
| Overlap resolution, span ordering | Verdict: block · mask · tokenize |
| Replacement (the replacer seam) | Restoration semantics |
| Token / placeholder format | LLM-stage classifiers (not span-based; stay put) |

## 3. Edition split

**CE gets the mechanism. EE gets the governance.**

| | CE | EE |
|---|---|---|
| Span model — `SensitiveSpan`, `SensitiveKind`, `SensitiveDataDetector` | ✓ | |
| Pipeline — detect → resolve → apply, with the replacer | ✓ | |
| Regex detectors — the 36 PII patterns, secret patterns | ✓ | |
| Token format and session — `PiiToken`, `PiiTokenSession` | ✓ | |
| Node guardrails — `sanitize-text`, `check-for-violations`, children | ✓ | |
| Workflow actions — Mask/Unmask, rebuilt on the core | ✓ | |
| Policy engine — workspace/global settings, project overlay, GraphQL admin | | ✓ |
| Automatic enforcement — advisor applied to every agent, no per-node opt-in | | ✓ |
| AI Gateway DLP | | ✓ |
| Cross-surface metrics tagged `gateway`/`ai_agent`/`ai_hub` | | ✓ |
| ML detection — OpenNLP NER | | ✓ |
| Blocking modes, moderation classifier | | ✓ |

Read as a sentence: **CE lets you protect an agent you configure; EE enforces across the organisation, on every surface, and reports on it.**

Three facts make CE placement the only workable answer, not a concession:

1. **`sanitize-text` is CE and deliberately cannot depend on EE** — `AbstractAiAgentChatAction:265` states the constraint explicitly. If the engine stays EE, CE keeps its own copy and nothing is consolidated.
2. **Detection is already effectively CE** — the better implementation is the free one.
3. **The knowledge base is CE and has no guardrails at all** (zero `Guardrail` references in `automation-knowledge-base`). If detection stays EE, that path can never be covered without a fourth copy. See §11.

Tokenization sits in CE because **reversibility already ships free**: `universal-text` Mask/Unmask is on `master` today. Putting the deterministic version behind a paywall would leave the free edition with the unreliable round trip — a worse product story than either alternative.

## 4. Module structure

A new CE pair holds the engine, following the repo's nested `platform-ai/<feature>/<feature>-{api,service}` layout:

- `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api` — `SensitiveSpan`, `SensitiveKind`, `SensitiveDataDetector`, and the metrics seam the redactor reports detector failures through
- `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service` — `SensitiveDataRedactor`, the regex detectors, and the `tokenization` package (`PiiToken`, `PiiTokenSession` — the format type travels with the session that mints against it)

Named for what it does rather than "guardrails", so that **guardrails** keeps meaning the policy layer in EE and the cluster elements in the component. This also avoids a CE and an EE module both named `platform-ai-guardrails-api`.

EE `platform-ai-guardrails-api` largely empties into CE; EE retains `AiGuardrails`, `AiGuardrailsAdvisor`, `StreamingResponseRedactor`, `AiGuardrailMetrics`, settings/policy, GraphQL, and the OpenNLP module — which ends up implementing a CE interface. EE depending on CE is the normal direction.

## 5. Taxonomy convergence

**CE's 36-pattern vocabulary wins over EE's 5 — a naming resemblance to Presidio, not a verified one.** CE's 36 patterns become `SensitiveDataDetector` implementations behind Phase 1's SPI; EE's five-pattern detector is deleted rather than merged. This document originally called that vocabulary "the Presidio taxonomy" on the strength of its entity *names* (`EMAIL_ADDRESS`, `US_SSN`, `UK_NINO`, ...) matching Microsoft Presidio's published entity types; nobody had yet checked whether the *regexes* did too (line 86, below, already flagged this as unverified). A later pattern-by-pattern comparison (`docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md`) found regex-level correspondence for a minority of the 47 final catalog entries: 21 of 47 (45%) match Presidio's published regex for the same entity, 13 (28%) have a Presidio counterpart with different, usually tighter, constraints, and 13 (28%) — all 11 secret patterns plus `PHONE_NUMBER` and `LOCATION` — have no Presidio counterpart at all. The decision itself is unaffected: adopting CE's broader vocabulary over EE's 5-type detector stands on its own merits (36 > 5, with a parity test establishing the vocabulary is deliberate, not accidental) and never depended on the patterns actually being Presidio's. Read every "Presidio taxonomy" phrase in this document as a *naming* claim from here on, not an origin claim.

This renames token categories: `[PII_EMAIL_1_k3n9]` becomes `[PII_EMAIL_ADDRESS_1_k3n9]`.

**Timing makes this free, and only now.** Token format is a compatibility surface — it appears in chat history, in traces, and in customer documentation. `master` has none of this and Phase 1 is unreleased, so the rename costs nothing today. After a release it is a migration whose failure mode is dead tokens in stored history, which is precisely the hazard §7 exists to prevent.

`PiiDetectorUtilsParityTest` moves with the patterns. Its name and entity vocabulary indicate alignment with an external reference; what that reference is has not been verified, so nothing in this design depends on the claim.

### 5a. The catalog is a menu, not a default

**The 36 patterns were built to be selected from, not applied wholesale.** The component narrows them per node via `filterByTypes(selectedTypes)`, rendered from `getPiiDetectionOptions()`. Two entries make that distinction load-bearing:

- `DATE_TIME` — "Date or date-time (ISO-8601)"
- `LOCATION` — "Street address / location"

Applied unconditionally, those tokenize every date and every address-shaped string in every prompt, destroying the model's ability to reason about schedules or places. The EE policy is a boolean (`pii-redaction-enabled`) with nothing to select with, so a wholesale port would be **worse than the five-pattern detector it replaces**.

**Decision.** The platform detector ships a curated default: the full catalog **minus `DATE_TIME`, `LOCATION` and `US_BANK_NUMBER`**. The first two are contextual rather than identifying; `US_BANK_NUMBER`'s pattern (`\b\d{8,17}\b`) is a different failure mode but the same criterion — it matches essentially any long digit run, so left in the default it tokenizes order numbers, invoice numbers and ticket numbers, not just bank account numbers. Every remaining identity, financial and national-identifier type is on. The excluded three remain in the catalog and become available when policy-level type selection lands.

**Policy-level type selection is a follow-up, not part of this work.** Giving the EE policy a per-type list mirrors the component's picker and needs new settings, GraphQL surface and UI — its own change. Until then the curated default is the whole story, and it must be documented as a default rather than as the complete catalog.

**Open question, resolved (2026-08-31, ticket 732 follow-up — see
`2026-08-25-sensitive-data-confidence-scoring-design.md`).** The criterion above — a pattern so
unspecific that applying it unconditionally degrades ordinary business text — was checked against
`DATE_TIME`, `LOCATION` and `US_BANK_NUMBER` at the time this section was written, but had *not* been
re-applied to the other bare-digit-run types still in the curated default: `US_SSN`, `AU_TFN`,
`PL_PESEL`, `MEDICAL_LICENSE`, `UK_NHS`, `AU_ACN`, `AU_MEDICARE` and `IN_AADHAAR`. The concrete
collision that motivated the question: the input `987654321` (e.g. a support-ticket number) matched
both `US_SSN` (`\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b`) and `AU_TFN` (`\b\d{9}\b`) at the same span, and
`SensitiveDataRedactor`'s tie-break picked `AU_TFN`, so a plain business identifier was redacted and
labelled an Australian Tax File Number. Deciding which of these eight national-identifier types (if
any) should move out of the default was framed as a product and compliance call, left for the spec
owner rather than resolved implicitly here.

**The answer: none of them move, because the binary in/out choice this section posed no longer
exists.** `LOW_SPECIFICITY_TYPES` — the exclusion set `US_BANK_NUMBER` sat in alone — is deleted.
Confidence scoring replaced it: every pattern in both catalogs now carries a score reflecting how
specific its own shape is, and `SensitiveDataRedactor` drops any candidate below a confidence
threshold before resolution runs, so a pattern doesn't have to be excluded from the curated default by
name to stop it from firing on ordinary text — it can stay in the default and simply score low.
`MEDICAL_LICENSE`, `US_BANK_NUMBER`, `PL_PESEL`, and `AU_TFN` score Low and are suppressed at the
platform default; `US_SSN`, `UK_NHS`, `AU_ACN`, `AU_MEDICARE`, and `IN_AADHAAR` score Medium, because a
dashed or spaced digit group is more constrained than a bare run. Separately, the `US_SSN`/`AU_TFN`
collision itself is closed structurally, not just filtered: `US_SSN`'s bare-digit alternative — the
half of the pattern that was byte-identical to `AU_TFN` — was dropped from the regex entirely, since a
single score cannot represent both a dashed branch and a bare-digit branch at different specificities.
`AU_TFN` already covers the same bare-digit shape, so it still *matches* that input at the regex layer
— but `AU_TFN` scores Low (`0.2`), below the platform default, so the match is then dropped before
resolution and `SSN 123456789` is redacted by nothing, where before this feature it was redacted
(mislabelled `AU_TFN`, but redacted) because every span was unfiltered at `1.0`. That is an intended
coverage reduction the confidence rubric produces, not a coverage-preserving rename of the
mislabelling fix — the mislabelling is gone, but so is the redaction. See
`docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md` for
the full score derivation and `.agents/ai-guardrails.md`'s "Confidence scoring" section for the
mechanism.

### 5b. Costs this convergence introduces

Two, both accepted, both previously unstated:

**Detection cost rises roughly sevenfold.** Five regexes become ~35, over every prompt *and* every completion, and on the streaming path per `push`. ReDoS is not the concern — `PiiDetectorUtils` carries `@SuppressFBWarnings("REDOS")` with the rationale that every pattern uses fixed `{N,M}` or possessive quantifiers — but raw cost is. This lands on a path with a **known open defect**: detectors run synchronously pre-LLM with no input-size cap, and the engine's fail-open cannot help because a slow detector never throws. This convergence makes that item materially more urgent; it should be closed before a third detector is added.

**Tokens get longer.** `[PII_FI_PERSONAL_IDENTITY_CODE_1_k3n9]` is 38 characters against `[PII_EMAIL_1_k3n9]`'s 18. That burns more context per mention, gives the model more surface to mangle (raising `token_unresolved`), and tightens the streaming safe-cut precondition that the lookahead window must exceed the longest possible token. `DEFAULT_WINDOW` is 512, so the invariant holds comfortably — but the precondition's documentation must be restated against the new maximum rather than the old one.

## 6. Front-end reconciliation

All three call the same core and differ only in policy source and verdict:

| Front-end | Edition | Released | Policy from | Verdict | Restores |
|---|---|---|---|---|---|
| `check-for-violations` / `sanitize-text` + children | CE | **v0.31.4** | Node configuration | Block · mask | No |
| `AiGuardrailsAdvisor` | EE | unreleased | Workspace/global settings, project overlay | Block · redact · tokenize · observe | Yes |
| `universal-text` Mask/Unmask | CE | **v0.31.4** | Action parameters | Mask | Yes — caller-held map |

### 6.0 What reading the code changed (2026-09-04)

This section was written before sub-project 1 landed and before the code was read closely. Grounding it
against the tree changed it in six places, and two of them invalidate decisions recorded in §12.

**1. The ordering is not what the spec, the code comment, or `.agents/ai-guardrails.md` say — and it is
measured, not assumed.** `CheckForViolationsAdvisor.getOrder()` returns `HIGHEST_PRECEDENCE`, the same value
as `AiGuardrailsAdvisor`. `AbstractAiAgentChatAction` registers the EE advisor list first with a comment that
"listing it first here documents that it is meant to run before every other advisor", and `.agents/ai-guardrails.md`
states twice that the workspace advisor runs before per-node elements. All three assume registration order
breaks the tie in favour of the first registration. It breaks it the other way:
`DefaultChatClient#buildAdvisorChain` hands the whole list to `DefaultAroundAdvisorChain.Builder#pushAll`, which
`Deque#push`es each advisor (an `addFirst`) and then runs a stable `OrderComparator` sort — reversed and then
stably sorted, a tie resolves to the advisor registered **last**. `SpringAiTiedAdvisorOrderTest` (commit
`73f5f587e08`) pins this through the real `ChatClient` path with a negative control. So today, in the canvas AI
Agent, the per-node `check-for-violations` advisor is **outermost**, wrapping the workspace floor. It is the only
tie in the repository: Copilot's `DeferredGuardrailsAdvisor` is also `HIGHEST_PRECEDENCE` but that surface has
no node elements, the AI Hub advisors sit at `MAX_VALUE - 1` and `MAX_VALUE`, and `WorkspaceSystemPromptAdvisor`
at `HIGHEST_PRECEDENCE + 100`.

The `getAdvisors` guard against two `check-for-violations` parents says the order "becomes undefined". It is
defined; it is just the inverse of the intuitive one.

**2. Neither position of a single around-advisor gives the right verdict in both directions.** Walk the two
orders with a workspace on `TOKENIZE` and a node `check-for-violations` → `pii` on both input and output:

| | Request (input check sees) | Response (output check sees) |
|---|---|---|
| Today — node outermost | Raw caller text → node "block PII" **fires** (as before EE existed) | The **restored** text — the caller's own e-mail, put back by EE one frame earlier → node **blocks** a response that merely echoes what the caller typed |
| §6 as written — EE outermost | Tokens → node "block PII" **never fires**; the workspace's permissive verdict silently neutralises the node's stricter one | Tokens → node judges only what the model contributed (correct) |

The response column wants EE outermost; the request column wants the node check to see what the floor saw.
That is why "document the ordering rule" was never enough: the rule has to be *built*, by giving the node
check the floor's detection result instead of the floor's transformed text. See the revised ordering rule below.

**3. The node layer is value-based; the core is span-based; the spans already exist and are thrown away.**
`PiiDetectorUtils.detect` returns `PiiMatch(value, start, end, type)`; `Pii.collectMaskEntities` keeps the
values and discards the offsets, and `MaskEntityMapUtils.applyTo` then re-finds each value in the text with a
boundary-aware regex to substitute `<TYPE>`. Pointing the node children at the shared pipeline replaces the
value→regex round trip with the core's offset replacement. `Violation.PatternViolation.matchedSubstrings`
carries the raw values further, but `CheckForViolationsAdvisor#toPublicView` only ever emits their **count** —
the values are retained in memory for the call and reach nothing.

**4. The node layer ignores `score` and `contextRule`.** `DEFAULT_PII_PATTERNS` constructs
`PiiPattern(type, pattern, validator)` with no score and no context rule, and there is no threshold in the
node verdict — every regex hit counts. The core has a 0.4 threshold, eleven Low-scored types that reach it only
through a context keyword, and a per-workspace custom-rule set. Consolidating detection means the node front-end
inherits all three. That is a **behaviour change** for a released surface: fewer bare nine-digit numbers flagged
as `US_BANK_NUMBER`, and the same detection a workspace rule adds becoming visible to node checks.

**5. Two runtime regex bounds and two pattern validators.** CE `RegexParserUtils` counts `charAt` calls
(10 M budget, 1 MiB input, 4 KiB expression, JS `/pattern/flags` syntax); the core's `MatchDeadline` is
wall-clock, enforced inside every detector match, with `DetectionBounds` for oversize input. `CustomRegex`
compiles through `RegexParserUtils.compile`; workspace rules go through `CustomPatternValidator` (bounded
quantifiers mandatory). One engine cannot carry two bounds; which one survives is decided below.

**6. Mask/Unmask are released, so D6 was wrong.** `v0.31.4` ships both actions: the `[EMAIL_1]` /
`[REDACTED_1]` / `[CUSTOM_1]` placeholders, the `{text, maskMap}` output, the `piiDetection` option values
(`EMAIL`, `PHONE`, `CREDIT_CARD`, `IP_ADDRESS`, `SSN` — a third taxonomy), the `sensitiveKeywords` and
`customRegexPatterns` inputs, and the provider/model properties. Unmask today sends the **whole mask map to an
LLM** and asks it to substitute the values back — the reversible half is itself a disclosure. What the
release protects is narrower than it looks: the placeholder text was produced by a model prompt and was never
deterministic, so no saved workflow can have depended on its exact shape. The parameter names, the option
values, and the output shape are what a saved workflow depends on.

### The ordering rule

Three advisor positions, fixed by **explicit, distinct** order values rather than by registration:

```
AiGuardrailsAdvisor          HIGHEST_PRECEDENCE          the workspace floor; outermost
CheckForViolationsAdvisor    HIGHEST_PRECEDENCE + 1      node verdicts; inside the floor
SanitizeTextAdvisor          DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1   (unchanged)
```

The two guardrail constants live together in one CE module both sides already depend on, each side's advisor
returns its constant, and a test on each side pins the value — so the tie cannot silently return when someone
"tidies" an order. `SpringAiTiedAdvisorOrderTest` stays as the record of why.

On the **request**, the floor detects once and **publishes what it found** — the `SensitiveSpan`s (kind,
category, offsets, confidence; never the matched value) it detected in each USER message — into the advisor
context before it tokenizes, redacts, allows or blocks. The node check reads the published spans for its
`pii` and `secret-keys` input verdicts when they are present, and runs the same core detection itself when they
are not (CE, or a workspace with guardrails off). Either way the node judges **the same detection the floor
judged**, and its verdict is order-independent:

> **On input, every layer judges one detection, and the strictest verdict wins.**

A workspace on `ALLOW` or `TOKENIZE` with a node that says "block PII" blocks. A workspace on `BLOCK` throws
before the node runs. The floor never lowers a node's bar, and the node never sees the floor's transformed text
as if it were the caller's.

On the **response** the chain unwinds inside-out:

```
model → sanitize-text (masks model-originated PII; tokens are invisible to it)
      → check-for-violations (judges model-originated PII; tokens are invisible to it)
      → EE scans, then restores → caller
```

> **On output, node layers see only what the model contributed. Restoration is the floor's last act, and it
> returns a value only to the party that supplied it.**

A node-level sanitizer or check exists to catch **model-originated** PII — invented by the model, or surfaced
from a tool result or a retrieved document. EE tokens are **caller-originated**. Handing a value back to the
person who typed it is not a leak, and a node check that fired on it would be blocking the user's own input
back at them — which is exactly what today's emergent order does.

One consequence to state plainly: a node `sanitize-text` → `pii` on **input** under a tokenizing workspace
masks nothing, because the floor already replaced the PII with tokens. The node author asked for irreversible
masking and got reversible tokenization instead. The model sees neither; the difference is only whether the
caller gets their value back on the response, and the rule above says they do. This is documented, not hidden.

### The node front-end on the shared pipeline

- The `pii` child calls the core's detection (`SensitiveDataRedactor` detect → resolve) and the core's offset
  replacement for masking; `PiiDetectorUtils` is deleted. `secret-keys` keeps its own detector for now (D20) but
  judges published spans too (D19). `MaskEntityMapUtils` and `PreflightMasking` stay while `keywords`, `llm-pii`
  and the advisors still reference them.
- The node layer inherits the core's threshold, context-keyword promotion and workspace custom rules
  (finding 4). The n8n-parity picker gains nothing new; the parity that changes is *which* matches count.
- **The node mask format stays `<TYPE>`.** §2 lists the placeholder format as shared; the node layer is the
  one released front-end whose masks appear verbatim in workflow outputs, so it keeps its notation through the
  core's replacer seam — the seam renders, the engine detects. This is the exception to §2, and it is the only
  one. `[REDACTED_X]` and `[PII_X_n_nonce]` remain the core's own notations.
- **`MatchDeadline` is the single runtime bound.** `RegexParserUtils` keeps its compile role (JS-literal
  syntax is an n8n-compatibility feature) and loses `bounded`; `custom-regex` matches under the deadline like
  every detector. The count budget was deterministic and the deadline is not, but two bounds is two behaviours
  to reason about and the core already chose the deadline in item 7.
- `custom-regex` keeps `compileOrThrow` at configuration time and is **not** put through
  `CustomPatternValidator`: that gate exists for persisted rules that run on every call of every workflow in a
  workspace, and it rejects `\d+`, which released node configurations legitimately contain.
- `Violation.PatternViolation.matchedSubstrings` is left as is. It is a released CE SDK type, the values are
  only ever counted, and replacing them with a count is an API change with its own compatibility argument —
  deferred, recorded in §12.

### Mask/Unmask rebuilt on the core

- **Mask** = detect → resolve → tokenize with a fresh `PiiTokenSession` per run; the session's map is the
  action's `maskMap`. **Unmask** = restore the map's tokens; no model on either end. Both actions drop the
  provider/model properties. A saved workflow that still carries them keeps working: unknown parameters are
  ignored, and the editor drops them on next save.
- The placeholder converges to the core's token format. Nothing depended on the model-produced shape
  (finding 6), and Mask's tokens now carry the same guarantees as the advisor's: unique per run, one ordinal per
  distinct value, restorable only through the map the caller holds.
- **Secrets are never in the map.** A `SECRET`-kind span is redacted irreversibly, as everywhere else in the
  engine. Today's action promises every masked value is in `maskMap`; a secret in a map that flows through
  workflow outputs and the execution page is precisely the disclosure the engine forbids, and the promise
  changes with the rebuild.
- `sensitiveKeywords` become caller-declared PII spans of type `KEYWORD` (reversible); `customRegexPatterns`
  become spans of type `CUSTOM`, compiled per run and matched under `MatchDeadline` with **no** syntactic gate —
  an action that times out fails visibly in the run, which is the right signal for a per-action pattern, whereas
  the syntactic gate exists for rules that fail silently inside an advisor.
- The `piiDetection` picker lists the shared catalog. The five released option values are kept as aliases
  (`EMAIL`→`EMAIL_ADDRESS`, `PHONE`→`PHONE_NUMBER`, `SSN`→`US_SSN`, `CREDIT_CARD` and `IP_ADDRESS` unchanged),
  so a saved selection keeps matching rather than silently matching nothing.

### Compatibility

What `v0.31.4` fixed and what this sub-project keeps: the node children's parameter names and option values;
the `<TYPE>` node mask notation; Mask/Unmask parameter names, the `{text, maskMap}` output shape, and the five
legacy `piiDetection` values as aliases. What changes, deliberately and documented: node detection inherits the
core's threshold and context rules; Mask's placeholder text; secrets leave the mask map; the provider/model
properties on Mask/Unmask go away. `universal-text`'s definition snapshot and generated reference page are
regenerated as part of the work.

## 7. Session scope and the session key

Phase 1 sessions are request-scoped. Phase 1's own spec (§10a) says it must not ship alone where chat history is retained, because tokens persisted into history become dead tokens on later turns.

The fix in that spec was to key sessions on `ChatMemory.CONVERSATION_ID`. **That is not safe, and this section supersedes it.**

### 7.0 What reading the code changed (2026-09-05)

This section was written on 2026-08-25, before sub-projects 1 and 2 landed. Grounding it against the tree changed it in six places; one removes an enabled surface, and one replaces the mechanism the whole section assumed.

**1. The session key can ride the advisor context, so no seam has to change.** This section assumed conversation identity would have to be threaded down to the guardrails advisor. It is already there. Both surfaces publish it as an advisor param, and Spring AI's `AdvisorSpec.params()` become `ChatClientRequest.context()`, which every advisor in the chain reads:

- canvas — `AbstractAiAgentChatAction#getConversationAdvisor` calls `advisor.param(ChatMemory.CONVERSATION_ID, conversationId)`;
- AI Hub — `AiHubSpringAIAgent` does `advisorParams.put(ChatMemory.CONVERSATION_ID, threadId)`.

So `AiGuardrailsAdvisor` can already SEE a conversation id on both. What it cannot learn from the id is whether that id is trustworthy — and the thing that answers that is the **surface** the advisor was constructed for, which it already holds. **Conversation scope is therefore a per-surface policy over an id the advisor already receives**, not a new parameter on `AiGuardrailsAdvisorProvider`. That is a much smaller change than this section implied, and it puts the security decision in the one place that cannot be spoofed by a workflow author.

**2. `GuardrailSurface.AI_HUB` is not the AI Hub chat surface.** It is used at exactly one site — `TitleGenerationService` — for chat-title generation. AI Hub chat turns resolve their advisor through `CopilotGuardrailsAdvisorFactory` under `GuardrailSurface.COPILOT`, via `DeferredGuardrailsAdvisor`. "AI Hub threads" in the original Decision is really **the `COPILOT` surface**, which also covers Copilot's own panel and the AI Hub delegation sub-agents. Enabling conversation scope there enables it for all three, so each needs its own answer to "is this id platform-verified?" — see the revised Decision.

**3. The AI Hub isolation argument is stronger than this section claimed.** §7 said thread-id isolation "holds because thread ids are unique in practice, not because an invariant enforces it." That is now false. `ai_hub_chat.thread_id` carries a **global UNIQUE constraint**, and `AiHubChatServiceImpl#create` deliberately looks up by `threadId` ALONE — not `(threadId, userId)` — so a cross-user collision surfaces as an explicit `ConflictException` instead of falling through, and its javadoc names the probe-oracle reason for that choice. A thread id already identifies exactly one `(workspaceId, userId)` pair by enforced invariant. Keeping `userId` in the key is defence-in-depth against that invariant weakening later, not the load-bearing part of it.

**4. The "channel agents" enabled surface does not exist, and is removed from this sub-project.** The original Decision keyed it `(conversationId, channelUserId)`. There is no `channelUserId`, `endUserId` or `externalUserId` anywhere in the tree. `ResolvedAgentChannel` is a *generation-time* projection — trigger type, reply action, parameters, binding, approval delivery — and carries no end-user identity at all. Where a channel has a per-message identity (a WhatsApp number, a Slack user), it arrives inside a generated workflow's trigger payload, which puts it straight back under author-supplied canvas semantics. **Deferred to the SDK agent-channels spec**, which has to define an end-user identity contract before any token store can key on one.

**5. A whole class of canvas agents needs no change at all.** `resolveConversationId` returns `null` when the node has no `CHAT_MEMORY` cluster element — no memory, no retained history, no dead tokens, so request scope is already correct there. When the element exists but pins no explicit `conversationId`, it returns a **fresh random UUID per call**: memory is configured, but every turn is its own conversation and nothing is retained across turns. Only an explicit author-supplied `conversationId` creates the hazard this section exists for. The permanent limitation below is therefore narrower than it sounded: it binds one configuration of one surface, not the surface.

**6. The change surface is two call sites.** Sessions are opened in exactly two places — `AiGuardrailsAdvisor#adviseCall` and `#adviseStream`, both `aiGuardrails.newTokenSession()` — and released on every termination path (a `finally` and a `doFinally` respectively). Everything else in this section is about what those two calls consult before minting.

### Why conversation id is not a safe key

**Canvas AI Agent.** `resolveConversationId` reads the id off the `CHAT_MEMORY` cluster element's `conversationId` parameter — an arbitrary workflow-author expression. Nothing binds it to an authenticated principal, and the platform cannot verify its uniqueness. An author writing `conversationId: "support-queue"` is doing something reasonable, and multiple end users then share one id.

**AI Hub.** Chat memory is keyed on `threadId` alone. Unlike the 2026-08-25 reading, that id is now protected by a database uniqueness invariant and an explicit conflict path (§7.0 finding 3) — so the AI Hub half of this argument no longer holds, and the surface is safe to enable.

Keying the token store on an *unverified* conversation id permits: user A sends `bob@acme.io` in turn 1; user B, on the same conversation id, receives it restored in turn 5. **A cross-user PII disclosure produced by the privacy feature.**

The obvious repair — add the supplying principal to the key — does not work on the canvas agent, because a workflow runs as a job and the "end user" is again author-supplied.

### Decision

Conversation-scoped sessions are enabled **only on a surface whose conversation id the platform itself issued and can prove unique**. The advisor decides from its own `surface`, never from the shape of the id:

| Surface | Scope | Why |
|---|---|---|
| `COPILOT` — AI Hub chat threads | **Conversation**, keyed `(workspaceId, userId, threadId)` | `thread_id` is platform-issued with a global unique constraint and an explicit cross-user conflict path |
| `COPILOT` — Copilot panel, AI Hub delegation sub-agents | **Request**, until each is shown to publish a platform-issued id | The surface is shared, so enablement is per-id-provenance within it, not per-surface |
| `AI_AGENT` — canvas, explicit author-supplied `conversationId` | **Request**, permanently | The id is a workflow-author expression; no platform invariant can be attached to it |
| `AI_AGENT` — canvas, no `CHAT_MEMORY` or no explicit id | **Request**, and nothing is lost | Nothing is retained across turns, so there are no dead tokens to fix (§7.0 finding 5) |
| `AI_HUB`, `API_CONNECTOR`, `AI_EVAL`, MCP surfaces | **Request** | Single-shot calls with no conversation at all |

Because `COPILOT` is one surface carrying three callers, enablement cannot be a surface constant alone: the advisor must be told, by the caller that published the id, that the id is platform-issued. That marker is published the same way the id is — as an advisor param the caller sets — and is trusted only because a workflow author cannot reach the code that sets it.

Request scope is documented as a deliberate limitation, not a gap. The rejected alternative — a per-node checkbox asserting "this conversation belongs to one person" — puts a security boundary in the hands of a workflow author who has no way to know what is at stake.

**This narrows the original Phase 2 promise, and the narrowing is permanent for one configuration of one surface.** A canvas AI Agent with an explicit author-supplied `conversationId` keeps request-scoped sessions indefinitely, because its conversation identity cannot be made trustworthy from the platform side. Cross-turn coherence there arrives only if a channel supplies a verifiable end-user id — which, per §7.0 finding 4, nothing does today. That trade — coherence lost on one configuration versus cross-user PII disclosure risked on all of them — is taken deliberately.

### Storage

With scope narrowed to surfaces that have real identity, the store follows those surfaces rather than the 15 per-node `CHAT_MEMORY` backends. The mapping is sensitive data and must be encrypted at rest using `server/libs/core/encryption` — whose `Encryption` interface is exactly `encrypt(String)` / `decrypt(String)`, so the stored artefact is one encrypted blob per session rather than a per-value column — with eviction on conversation deletion and a TTL for abandoned conversations.

Self-describing tokens (encrypting the value into the token) were considered and rejected: `EncryptionImpl` emits `base64(IV + ciphertext + tag)`, so a ~20-character email becomes roughly 70 characters of opaque token. That burns context on every mention, raises the chance the model mangles it, and discards the `_1_` / `_2_` ordinal that tells a model two mentions are different people.

## 8. Metrics

Existing events keep their names and incidence semantics — recorded at most once per content, matching the `containsKind` idiom. `pii_tokenized` fires instead of `pii_redacted` wherever tokenization is active; `pii_restored` and `token_unresolved` are unchanged.

`token_unresolved` gains significance under this design: it is the signal that a token-shaped string reached a response without resolving — including a dead token replayed from history on a request-scoped surface.

## 9. Testing

- The parity test moves with the patterns and continues to pin taxonomy alignment.
- A curated-default test pins §5a: the platform detector's default set contains neither `DATE_TIME` nor `LOCATION`, and a text containing only an ISO date and a street address produces zero spans by default — with the sample text chosen so no *other* pattern in the catalog matches it either, or the test pins the wrong thing.
- The component-redirect (§10) gets a test that the component's selectable options and the platform catalog are the same list object or provably identical — the drift test an earlier draft needed exists here only as an identity check, not as a pin between two copies.
- One agreement test per front-end pair: the same input through node guardrails and through the EE advisor must produce the same *spans*, differing only in replacement.
- A cross-user test for §7: two principals on one conversation id must not observe each other's values.
- The ordering rule gets a test that fails if restoration is moved ahead of scanning — this defect shipped once during Phase 1 and was caught only by mutation testing.

## 10. Delivery

Three sub-projects, each with its own plan:

1. **Extract the core to CE and converge the taxonomy** (§3, §4, §5). Moves the whole engine — `SensitiveSpan`, `SensitiveKind`, `SensitiveDataDetector`, `SensitiveDataRedactor`, **and** `PiiToken` / `PiiTokenSession` — into the new CE modules, adopts the curated Presidio-*named* default (§5a — naming resemblance, not verified provenance; see §5), and deletes EE's five-pattern detector. **The component's `PiiDetectorUtils` is redirected to read the platform's pattern list in this same sub-project**, so the catalog exists exactly once from the first shippable increment. EE's `AiGuardrails`, advisor, streaming redactor, metrics, policy and OpenNLP module stay put and consume the CE core.
2. **Reconcile the front-ends** (§6) — fix the advisor tie with explicit, distinct orders and have the node check judge the floor's *published detection* rather than its transformed text (the ordering rule, built rather than merely documented); point the node `pii` / `secret-keys` children at the shared pipeline; rebuild Mask/Unmask on the core with no model on either end; retire the second regex bound; correct `.agents/ai-guardrails.md`, which states the inverse of the measured order twice.
3. **Conversation-scoped sessions** (§7) — the surviving half of Phase 2, narrowed again by §7.0: conversation scope becomes a per-surface, per-provenance policy read off the advisor context the caller already publishes, enabled for AI Hub chat threads and nothing else; the channel-agent surface is removed for want of any end-user identity to key on, and the canvas agent keeps request scope permanently for its one hazardous configuration.

The boundary between 1 and 2 is **pattern source versus pipeline**. Sub-project 1 changes where the regexes live — the component keeps its own matching loop, options picker and masking, reading `PiiPattern`s from the platform module (its `filterByTypes` selection continues to work over the shared catalog, including the two §5a-excluded types, which remain selectable per node). Sub-project 2 changes *execution*: advisors calling the shared detect → resolve → replace pipeline, verdict ordering, Mask/Unmask. An earlier draft deferred the redirect to sub-project 2 and kept two pattern lists pinned by a drift test; that was rejected because the first increment of a de-duplication project must not add a copy — if sub-project 2 slipped, the codebase would be left worse than it started.

Sub-project 1 pays for itself immediately and unblocks the others.

## 11. Non-goals

- **Knowledge base coverage.** `automation-knowledge-base` has no guardrails at all, and documents ingested there are chunked, embedded, and persisted — a larger exposure than anything here. It needs its own spec (redact at ingestion, losing retrieval fidelity, versus detect at retrieval time). The CE placement in §3 is what makes it possible later.
- **Tool-boundary restoration.** Restoring original values immediately before a protected tool runs, per entity type by policy, then re-tokenizing the result. This is what makes "forward bob@acme.io's note to alice@acme.io" actually work — today the send-email tool receives a token. The reference implementation this design borrowed from does exactly this. Its own spec; ranked above the item below.
- **Embedding-based detection.** No embedding model exists anywhere in the repo today. If added, it follows the OpenNLP shape — optional module, off by default, model never bundled — or is externalised as an HTTP service, which is how the reference implementation runs Presidio. Note the open cost defect it would inherit: detectors run synchronously pre-LLM with no input-size cap, and fail-open cannot help because a slow detector never throws.
- **Embeddings never tokenize.** The gateway's embeddings path stays redact-only. This is an invariant, not a phase gap: an embedding has no text response to restore into, and session ids are random per session, so tokenizing would make the same document embed to a different vector on every call.

## 12. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | One engine, three front-ends | Mechanics duplicate; policy legitimately differs |
| D2 | Engine in CE | `sanitize-text` and the knowledge base cannot depend on EE; detection is already effectively CE |
| D3 | EE keeps policy, scope, gateway, metrics, OpenNLP | What enterprises buy is unavoidable org-wide enforcement and reporting |
| D4 | Tokenization in CE | Reversibility already ships free, badly; paywalling the good version is a worse story |
| D5 | CE's 36-pattern, Presidio-*named* taxonomy wins; EE's 5-pattern detector deleted | 36 > 5, with a parity test establishing the vocabulary is deliberate; renaming is free only before release. Naming resemblance to Presidio, not verified regex provenance — see §5 |
| D5a | Curated default excludes `DATE_TIME` and `LOCATION` | The catalog is a menu; applied wholesale, those two tokenize every date and address and break model reasoning |
| D5b | Component redirected to the platform pattern list in sub-project 1 | A de-duplication project must not ship an added copy as its first increment |
| D6 | Mask/Unmask converge placeholder format immediately; parameter names, output shape and the five legacy `piiDetection` values are kept | **Revised 2026-09-04.** Both actions are released in `v0.31.4` (§6.0 finding 6). Only the model-produced placeholder text was never deterministic enough to depend on; the contract a saved workflow does depend on is preserved |
| D7 | EE outermost by explicit order; node *checks* judge the floor's published spans on input and see tokens on output; sanitizers see tokens | Restoration returns a value only to the party that supplied it. **Revised 2026-09-04:** a single advisor position cannot satisfy both directions (§6.0 finding 2), so the node reads the detection, not the text — on input the strictest verdict wins, on output node layers judge only what the model contributed |
| D8 | Conversation scope only where the platform ITSELF issued the conversation id and can prove it unique | Author-supplied conversation ids permit cross-user disclosure. **Revised 2026-09-05:** the test is provenance, not visibility — AI Hub's `thread_id` qualifies because of a global unique constraint plus an explicit cross-user conflict path (§7.0 finding 3), and the advisor decides from its own surface, never from the shape of the id |
| D9 | Self-describing tokens rejected | ~70-character opaque tokens; loses ordinal semantics |
| D10 | Tool-boundary restoration deferred to its own spec | Distinct feature; this spec must stay shippable |
| D11 | Policy-level PII type selection deferred | Needs settings, GraphQL and UI; the curated default carries until it lands |
| D12 | Commercial-boundary thinning accepted | With engine, tokenization and patterns in CE, a CE-only advisor equivalent becomes cheap to build; EE's remaining value is policy, unavoidable org-wide scope, gateway DLP, cross-surface metrics and OpenNLP. Named as a product trade-off, decided by the maintainer, not silently implied |
| D13 | Advisor orders are explicit, distinct constants in one shared CE module, pinned by a test on each side | Registration order breaks a tie toward the **last** registration (`SpringAiTiedAdvisorOrderTest`, commit `73f5f587e08`); today's tie puts the node check outermost and makes it block responses that echo the caller's own restored input |
| D14 | Node `pii` / `secret-keys` inherit the core's threshold, context-keyword promotion and workspace custom rules | One engine means one detection; the verdict is the only per-layer policy. A released-surface behaviour change, documented |
| D15 | The node mask notation `<TYPE>` is kept, rendered through the core's replacer seam | Released surface whose masks appear verbatim in workflow outputs; the only exception to §2's shared placeholder format |
| D16 | `MatchDeadline` is the single runtime regex bound; `RegexParserUtils.bounded` retired; `custom-regex` and Mask's custom patterns are runtime-bounded, not syntax-gated | Two bounds is two behaviours to reason about; `CustomPatternValidator` exists for persisted rules that fail silently inside an advisor, and it rejects `\d+`, which released node configurations contain |
| D17 | Secrets never appear in Mask's `maskMap`; Unmask restores from the map with no model | A map that flows through workflow outputs and the execution page must not carry a secret; sending the map to an LLM to restore it was itself a disclosure |
| D22 | Conversation scope is a per-surface, per-provenance policy over the conversation id the advisor ALREADY receives on `ChatClientRequest#context()`; `AiGuardrailsAdvisorProvider` does not change | Both surfaces already publish `ChatMemory.CONVERSATION_ID` as an advisor param, and Spring AI turns advisor params into the request context every advisor reads (§7.0 finding 1). Threading a second copy through the provider seam would add a channel without adding information |
| D23 | The channel-agent surface is removed from sub-project 3 and deferred to the SDK agent-channels spec | No `channelUserId`/`endUserId`/`externalUserId` exists anywhere; `ResolvedAgentChannel` is a generation-time projection carrying no end-user identity, and per-message identity arrives in a generated workflow's trigger payload — author-supplied again (§7.0 finding 4). A store cannot key on an id the system does not have |
| D24 | The "this id is platform-issued" marker is published by the caller as an advisor param, alongside the id | `COPILOT` is one surface carrying three callers (AI Hub chat, the Copilot panel, AI Hub delegation sub-agents), so a surface constant alone cannot express enablement. The marker is trustworthy for the same reason the id's provenance is: a workflow author cannot reach the code that sets it |
| D18 | `Violation.PatternViolation.matchedSubstrings` left as is | Released CE SDK type; the values are only ever counted; replacing them is an API change with its own argument — deferred |
| D19 | Node input verdict = published ∪ own detection; span-derived verdicts reported through a new additive `Violation.SpanViolation` | Publication alone would make `secret-keys` less strict under EE than in CE, since the core's secret detector is a strict subset of the node's. Union keeps the floor from lowering any node's bar and leaves `PatternViolation` untouched (D18) |
| D20 | `secret-keys` keeps `SecretKeyDetectorUtils` for detection in sub-project 2; it consumes published SECRET spans and matches under `MatchDeadline` | The core has the 11 catalog patterns only; the node utility adds prefixed-token, high-entropy (three permissiveness levels), `KEY=VALUE`, fenced-code-block skipping and obfuscation handling. Porting them is its own sub-project |
| D21 | Mask with an empty `piiDetection` selection scans the curated default; secrets are always scanned and redacted | The model prompt Mask replaces redacted "sensitive information" generically; an empty selection must not become "mask nothing" |
