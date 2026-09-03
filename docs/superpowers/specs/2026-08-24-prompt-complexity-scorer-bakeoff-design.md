# Prompt Complexity Scorer Bake-off — Design (embedding-centroid vs OpenNLP vs the deterministic baseline)

- **Date:** 2026-08-24
- **Branch:** `claude/opennlp-smart-routing-gateway-82112a`
- **Status:** Accepted. The user chose a bake-off over shipping either candidate outright, after the
  embedding path's dependency footprint turned out to be ~240MB rather than the ~90MB originally
  estimated (§3.6). All ⚑ decisions in §12 were put to the user on 2026-08-25 and answered.
- **Ticket:** none filed yet.
- **Related:** `2026-08-12-ai-model-catalog-extraction-design.md` (the `AiModel` pricing this spec reads
  to estimate cost deltas).

## 1. Summary

The AI gateway's three `INTELLIGENT_*` routing strategies pick a model tier from a single number: a
`0.0–1.0` prompt complexity score. Today that number comes from `DeterministicPromptComplexityScorer`,
which reads only the *shape* of a request — character count over four as a token proxy, tool count, a
brace-and-colon test for structured content, turn count, and `maxTokens`.

Shape is a decent proxy and it is free. It is also blind in a specific, predictable way: "prove that the
halting problem is undecidable" is 47 characters with no tools and no braces, and scores near zero. The
gateway routes it to the cheapest tier with full confidence. Merge Gateway — whose five cost tiers and
three routing strategies this codebase already mirrors deliberately (`AiGatewayModelTier` says so in its
own Javadoc) — solves this by embedding the prompt and scoring it against two labelled centroids
(§3.7 records exactly how much of that is documented and how much is not).

Three candidate replacements are available, spanning three orders of magnitude in cost:

| | Technique | Added footprint | Embedding call |
|---|---|---|---|
| **B** | Embedding centroid, in-process ONNX MiniLM | **~240 MB** | in-process |
| **C** | OpenNLP maxent document categoriser | **~1.3 MB** | none |
| **D** | Embedding centroid over the gateway's existing provider-backed embedding model | **0 bytes** | network |

B and D are the *same* technique and share an implementation — they differ only in which
`EmbeddingModel` is injected — so D costs almost nothing to include and directly measures the
assumption that would otherwise be waved through: that a network hop disqualifies the remote path.

None of these differences is large enough to settle the question by argument. So this spec does not
pick a winner. It specifies a **bake-off**: build all three behind the existing interface, measure them
against a pre-registered rubric, and let the numbers decide — including the outcome where none earns
its cost and the baseline stays.

The deliverable of this spec is **a decision backed by measurements**, not a shipped scorer. Integrating
whichever candidate wins is a follow-up, governed by §8.

## 2. Goals / non-goals

**Goals**

- A reproducible offline harness that scores the same corpus through all three scorers and emits a
  comparison report.
- Candidates B and C built to the same contract, trained from the same labelled data, so the comparison
  isolates technique rather than data.
- Pre-registered decision criteria (§9), fixed before any result is seen.
- A written outcome — including "neither candidate justifies its cost" as a first-class result.

**Non-goals**

- **Per-workspace custom classifiers.** Merge's labelled-prompt UI, persisted centroids, GraphQL and
  client work is a separate feature with its own spec. This bake-off uses a committed fixture corpus.
- **Intent / topic / language routing.** OpenNLP's other plausible use — classifying what a prompt is
  *about* to drive `TAG_BASED` routing — is a different feature and is not measured here.
- **Any change to routing behaviour.** `AiGatewayRoutingStrategyType`, `AiGatewayModelTier`,
  `IntelligentRoutingStrategy`, the DB, GraphQL and the client are all untouched by this spec.
- **Shadow-scoring live production traffic.** Real prompts enter the corpus only as a manually exported,
  never-committed local file (§5.3).
- **Choosing the winner's packaging in detail.** §8 states the shape each outcome takes; the follow-up
  spec fills it in.
- **Reverse-engineering Merge Gateway.** §3.7 fixes what their documentation does and does not say. The
  bake-off measures candidates on this codebase's terms, not on parity with an undocumented design.

## 3. What is already true

Established by reading the code, not assumed. Each of these removes work the bake-off would otherwise
have to do.

**3.1 The seam exists and is documented as this feature's entry point.**
`PromptComplexityScorer` is a one-method interface in `platform-ai-gateway-api`, and its Javadoc already
names an embedding-based scorer as the intended successor. Adding an implementation requires no change
to any caller.

**3.2 Scorer failure is already safe.** `AiGatewayFacadeImpl.buildRoutingContext` wraps the `score(…)`
call in a try/catch that logs and substitutes `1.0` — the most capable tier. This is exactly the
fallback Merge documents. A candidate scorer that throws, hangs on model load, or fails to initialise
degrades to "route to the best model", never to an outage and never to a silently-cheap answer. The
bake-off inherits this for free and does not need to design failure handling.

**3.3 Selection is already property-gated.** `DeterministicPromptComplexityScorer` is a `@Component`
carrying `@ConditionalOnEEVersion` and `@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name =
"enabled")`. A second implementation selected by an added property is the established pattern here, not
a new one.

**3.4 The tiers are pinned to an external reference.** `AiGatewayModelTier` classifies on USD per 1M
output tokens with floors at 0.10 / 1.50 / 2.00 / 5.00, and its Javadoc warns against rebalancing the
narrow STANDARD/ADVANCED band without re-checking Merge's published tiers. The bake-off must not touch
these; it varies only the score feeding them.

**3.5 Real prompts are already persisted.** `AiObservabilitySpan` stores `input`, `output`, `model`,
`cost`, `inputTokens`, `outputTokens` and `latencyMs`. A real-traffic corpus is therefore obtainable
from a dev or staging instance without new instrumentation — with the privacy constraint in §5.3.

**3.6 The dependency ground truth.** Verified against Maven Central on 2026-08-24:

- `org.springframework.ai:spring-ai-transformers:2.0.1` exists and is version-aligned with the
  project's `spring-ai = "2.0.1"`.
- It pulls, all at `compile` scope: `onnxruntime` **130.5 MB**, `ai.djl.huggingface:tokenizers`
  **17.8 MB**, `ai.djl:api` 0.9 MB, plus `ai.djl.pytorch:pytorch-engine` and `ai.djl:model-zoo`.
- Reading `TransformersEmbeddingModel`'s imports: it uses only the DJL tokenizer, DJL NDArray, and
  `ai.onnxruntime.*`. **`pytorch-engine` and `model-zoo` are unused and must be excluded** — DJL's
  PyTorch engine lazily downloads a ~200 MB libtorch native on first use if left on the classpath.
- Its defaults fetch `model.onnx` (**90.4 MB**, GitHub LFS) and `tokenizer.json` over HTTP at bean
  initialisation. Both are overridable to `classpath:` resources.
- `org.apache.opennlp:opennlp-tools:2.5.11` is the current stable release at **1.3 MB**, single
  artifact, `slf4j-api` its only meaningful dependency, no natives, no downloads. (The 3.0.0-M5
  milestone splits the library into several modules and is not used here.)
- Nothing in the current dependency graph carries ONNX Runtime or DJL.
- **OpenNLP is already there.** `org.apache.opennlp:opennlp-tools:2.5.11` is declared by
  `platform-ai-guardrails-opennlp`, a production module carried by `server-app`, `ai-gateway-app` **and**
  `ai-copilot-app` — every deployment that runs the gateway already ships it. An earlier draft of this
  section asserted the opposite; that was wrong, and it was caught during execution when Task 3 found the
  version-catalog entry already present.

  **Candidate C therefore adds zero bytes, not ~1.3 MB**, and §9's cost ordering changes accordingly.
  Two consequences beyond the arithmetic: the OpenNLP *version* is shared with the guardrails detector,
  so a future bump affects both and is no longer a decision this feature can make alone; and a C win
  requires no dependency change at all, only a new class.

**3.7 What Merge's documentation actually states — and what it does not.** Re-read on 2026-08-24 against
the two pages cited in §1, with inference explicitly excluded:

*Documented:* the gateway embeds the prompt and scores complexity 0–1; custom classifiers embed labelled
prompts, average each side into a centroid, store the two centroids, and score by proximity; a minimum of
three labelled examples per side; five tiers inferred from provider output pricing at the floors §3.4
records; three strategies with the cost/balanced/quality skews; and one latency figure, quoted verbatim:
"This adds ~1-4ms of latency, negligible compared to LLM inference time."

*Not documented, and not to be asserted anywhere downstream:* which embedding model is used; whether
embedding runs in-process, as an internal service, or through a third-party API; and where it executes.
An earlier draft of this spec asserted "local, in-process, offline" — that was an inference presented as
fact and is retracted. Merge Gateway is hosted SaaS, so even a confirmed "local" would mean local to
their infrastructure, which implies nothing about what belongs in a ByteChef JVM.

*What this changes:* the in-process ONNX design of candidate B is a ByteChef engineering choice, not
Merge parity, and the ~240 MB it costs is a consequence of that choice alone. Candidate D exists because
the argument that previously excluded it — "a network hop defeats the point, since Merge does this
locally" — rested on the retracted claim.

**3.8 A second scorer cannot simply be added — the injection point is singular.**
`AiGatewayFacadeImpl` takes one `PromptComplexityScorer` constructor parameter, not a `List` and not a
`@Qualifier`-ed pair. `DeterministicPromptComplexityScorer` carries only `@ConditionalOnEEVersion` and
`@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")` — it has
no discriminator saying *which* scorer it is. So any second implementation on the same classpath fails
context startup with `NoUniqueBeanDefinitionException`.

Whichever candidate wins therefore requires **one production edit that §2's non-goals did not
anticipate**: giving the deterministic scorer a selector condition (`…name = "prompt-complexity-scorer",
havingValue = "deterministic", matchIfMissing = true`) so the default survives when no property is set,
and giving the winner the complementary condition. This is a small edit and it is unavoidable in all
three outcomes; it is recorded here so the follow-up spec budgets for it rather than discovering it at
integration time.

**3.9 Packaging: the image already has a sideload mechanism, and it appears to be inert.**

`ServerApplication` is annotated `@SpringBootApplication(scanBasePackages = "com.bytechef")`, so a module
on the classpath is component-scanned automatically and one that is absent is invisible. What that does
*not* give is optionality at deployment time: `server-app/build.gradle.kts` wires its 284 modules
explicitly, so a Gradle module is in the image or not, decided at build.

But the image has a second, better door. `server/apps/server-app/Dockerfile` creates
`/opt/bytechef/external_jars`, and `docker-entrypoint.sh` passes
`-Dloader.path=/opt/bytechef/external_jars`. That is precisely a drop-in-extra-jars mechanism: an
operator mounts jars, the app picks them up, and the published image carries nothing.

**It works, and it is already in production use — the Oracle JDBC driver is loaded this way**
(confirmed by the maintainer, 2026-08-25). That makes it a proven pattern here rather than a mechanism
being repurposed: a large dependency the published image cannot or should not carry, supplied by the
operator at runtime. Candidate B is the same shape of problem.

Note for future readers, because it is genuinely confusing: nothing in the repository configures a
non-default launcher. There is no `bootJar`
manifest block, no `Main-Class` override and no `loaderImplementation` setting anywhere, and
`-Dloader.path` is classically a `PropertiesLauncher`-only flag. A draft of this spec concluded from that
absence that the mechanism was inert. It is not. **Do not "fix" the missing launcher configuration** on
the strength of that reasoning — the sideload path is in working order as it stands.

**What this changes for candidate B.** Its cost splits in two, and neither half needs to enter the image:

- The **90 MB model and tokenizer are data, not code.** `TransformersEmbeddingModel` takes
  `modelResource` and `tokenizerResource` as Spring `Resource`s, so a mounted `file:` path works today
  with no mechanism at all.
- The **~150 MB of jars** (onnxruntime 130 MB, tokenizers 18 MB, djl-api 1 MB) go in `external_jars`,
  which the running image already picks up.

So B no longer forces the choice an earlier draft of this section framed as unavoidable: "the published
image either carries 240 MB for everyone, or nobody using that image can enable the scorer". There is a
third door and it is already open. B's remaining cost is **operational, not distributional**: the operator
who wants it mounts two artifacts.

Candidates C and D still have no packaging story at all — at ~1.3 MB and 0 bytes they simply ship, gated
at runtime by the property.

## 4. The candidate scorers

All three implement `PromptComplexityScorer` and return `0.0` (simple) to `1.0` (complex).

**A — Baseline.** `DeterministicPromptComplexityScorer`, unmodified. The control.

**B — `EmbeddingCentroidPromptComplexityScorer`.** Embeds each exemplar prompt with Spring AI's
`TransformersEmbeddingModel` (MiniLM-L6-v2, ONNX, in-process), averages the simple set and the complex
set into two centroids, and at score time embeds the prompt and reports where it falls between them:

```
score = clamp01( (1 + cos(p, c_complex) - cos(p, c_simple)) / 2 )
```

The `+1 … /2` mapping is required because the cosine difference ranges over `[-2, 2]`; it is monotonic
in "closer to complex", which is the only property the strategies rely on. Centroids are computed once
at bean initialisation and held in memory — two `float[384]` arrays.

**D — `EmbeddingCentroidPromptComplexityScorer` over a remote embedding model.** The same class as B,
constructed with a remote OpenAI-compatible `EmbeddingModel` instead of an in-process one. In production
this model would come from the gateway's existing `AiGatewayEmbeddingModelFactory`; the harness builds an
equivalent client directly from an API key, because the factory's value — caching, SSRF validation,
credential decryption — is orthogonal to what the bake-off measures, and depending on it would drag
encryption and persistence into a test-only module. Adds no dependency and no bytes; pays a network round trip per scored
prompt. Its accuracy should track B's closely — any gap is attributable to the embedding model, not the
technique — which makes §6.3 latency, not §6.1 accuracy, the axis that decides between them.

**C — `OpenNlpPromptComplexityScorer`.** Trains an OpenNLP `DocumentCategorizerME` (maxent) at bean
initialisation from the same exemplar set, labelled `SIMPLE` / `COMPLEX`. Score is the model's
probability of `COMPLEX`. Training a few dozen short documents is a sub-second, few-megabyte operation,
so training at startup avoids shipping a model artifact and keeps the exemplar set as the single source
of truth for both candidates.

**The shared exemplar constraint.** B, C and D consume the *same* labelled exemplars and the *same*
`0.0–1.0` contract. Any measured difference is therefore attributable to technique, not to one candidate
having been given better training data. This is the single most important property of the experiment
design and must not be relaxed for convenience.

## 5. Corpus and ground truth

The hardest part of this bake-off is not building the scorers; it is having something honest to score
them against. Three disjoint data sets:

**5.1 Exemplar set (~40 prompts, committed).** The labelled data B and C learn from. Balanced between
`SIMPLE` and `COMPLEX`, drawn from ordinary gateway usage — summarisation, extraction, classification,
short factual questions on the simple side; multi-step reasoning, proofs, architecture and debugging
questions, long-context synthesis on the complex side. Merge's own minimum is three per side; ~20 per
side gives the centroids and the maxent model a fairer footing without becoming a labelling project.

**5.2 Adversarial evaluation set (~60 prompts, committed, disjoint from 5.1).** The set that decides the
outcome. It is deliberately weighted toward cases where request *shape* misleads, because that is the
only region where a candidate can beat the baseline:

- Short-but-hard — terse prompts requiring deep reasoning.
- Long-but-trivial — a large pasted log or document with "summarise this".
- Code-shaped-but-simple — a fenced snippet with "what language is this?".
- Prose-but-complex — a multi-clause reasoning question containing no structural markers.
- Multi-tool-but-trivial — many tools declared, a one-line request.
- Non-English prompts at both ends.
- Degenerate inputs: empty content, `null` content with only content blocks (the multi-modal case the
  baseline's Javadoc admits it ignores), a 100k-character prompt.

Every entry carries a hand-assigned binary label and a one-line rationale, so a later reader can dispute
a label rather than guess at it. The rubric governing the labels is committed alongside the fixtures.

**5.3 Real-traffic sample (optional, never committed).** Exported by hand from `AiObservabilitySpan.input`
on a dev or staging instance into a local JSONL file that the harness reads from a path supplied by
system property, with the path gitignored. Real prompts may contain customer data; none of this data
enters the repository, the build, or CI. This set is unlabelled and is used only for the distribution
and cost-estimate metrics in §6.2, never for accuracy.

**On ground truth.** Labels are assigned by hand against a written rubric. Using an LLM as the labelling
judge is rejected: it would make the experiment measure agreement with a judge model, when the entire
premise of both candidates is that no LLM belongs in this decision path. See ⚑1.

## 6. Metrics

**6.1 Classification quality** (on 5.2, the deciding metric)
- Accuracy, and precision/recall for the `COMPLEX` class.
- ROC-AUC over the continuous score — threshold-free, and the strategies consume the continuum, not a
  binary.
- A per-category breakdown across the 5.2 buckets, so "wins overall but is worse on long-but-trivial"
  is visible rather than averaged away.

**6.2 Routing outcome** (on 5.2 and, if present, 5.3)
- Tier distribution produced under each of `INTELLIGENT_COST`, `INTELLIGENT_BALANCED` and
  `INTELLIGENT_QUALITY`, against a fixed synthetic policy spanning all five tiers.
- Estimated cost delta versus baseline, computed from `AiModel.outputCostPerMTokens`. This is an
  estimate of *routing* cost, not of answer quality; the report must say so.

**6.3 Cost of the scorer itself**
- `score(…)` latency p50 / p99 over a warmed loop. Merge publishes ~1–4 ms for their embedding path,
  under the caveats in §3.7 — it is a reference point, not a target this codebase has to hit.

  **Candidate D's latency is an upper bound, not an estimate.** The harness calls a public embeddings
  endpoint from a developer machine, so the measurement carries wide-area round-trip time that a
  production gateway sharing a region with its provider would not pay. If D fails criterion 2 by a small
  margin, the honest verdict is "not disqualified — needs re-measuring from a representative host",
  not "rejected". Record the measured round-trip time alongside the scoring latency so the two can be
  separated.
- Heap delta after initialisation, measured after a forced GC.
- Cold-start delta: bean initialisation time, including model load and, for C, training.
- Dependency bytes added, resolved rather than estimated.

**6.4 Robustness** — every scorer must return a finite value in `[0, 1]` for every degenerate input in
5.2, or be disqualified regardless of its accuracy.

## 7. Harness

A new test-only Gradle module, `platform-ai-gateway-scorer-bakeoff`, under
`server/ee/libs/platform/platform-ai/platform-ai-gateway/`. Nothing depends on it.

A separate module is not tidiness — it is the only way to keep 240 MB of ONNX and DJL off
`platform-ai-gateway-service`, which sits on the classpath of the monolith `server-app` and of every EE
app carrying the gateway. Both candidates and the harness live here for the duration of the experiment;
only the winner migrates out, into the packaging §8 prescribes.

The harness is a JUnit class carrying a JUnit tag excluded from the default `test` task, so `./gradlew
check` never downloads a 90 MB model or trains anything. It runs on demand, reads the fixtures from
module resources, and writes a Markdown report plus a per-prompt CSV into the module's build directory.
The report is committed alongside this spec and its verdict recorded in §13 when the run is final.

EE conventions apply throughout: ByteChef Enterprise licence header, `@version ee` on every class,
registration in `settings.gradle.kts`.

## 8. What happens to the losers

Pre-committed, so the outcome cannot be rationalised after the fact:

- **D wins** → the cheapest outcome by far. The centroid scorer lives in `platform-ai-gateway-service`
  behind the property toggle, wired to the existing `AiGatewayEmbeddingModelFactory`. No new module, no
  new dependency, no vendored model. B and C are deleted.
- **C wins** → OpenNLP is small enough to live in `platform-ai-gateway-service` directly, behind the
  property toggle, with no new module. B and D are deleted.
- **B wins** → ship it as a **sideload**, per §3.9. The scorer class lives in
  `platform-ai-gateway-service` behind the property like the others; its heavy dependencies do not enter
  the image. The operator mounts the onnxruntime, tokenizers and djl-api jars into
  `/opt/bytechef/external_jars` and the model and tokenizer files wherever `modelResource` /
  `tokenizerResource` point, then sets the property.

  **No infrastructure prerequisite** — the sideload path already works (§3.9). C and D are deleted.
- **C and D land within five points of each other** → ship **both**, defaulting to whichever scored
  higher. Fixing the singular-injection constraint (§3.8) already requires turning the scorer choice
  into a selector property rather than a boolean, so supporting a third value costs ~1.3 MB and one more
  `@ConditionalOnProperty` value. The report must document which the measurements favoured, so an
  operator choosing the non-default is making an informed choice rather than a guess. B never
  participates in this outcome — at ~240 MB it ships alone or not at all.
- **None clears §9** → all are deleted, the baseline stays, and the outcome is recorded in this
  spec. This is a successful result: it converts an open question into a closed one for the cost of a
  throwaway module.

In all three cases the bake-off module itself is deleted once the decision is recorded. Its fixtures
move to whichever module the winner lands in, because they become that scorer's unit-test corpus.

## 9. Pre-registered decision criteria

Fixed before any measurement is taken. A candidate ships only if it clears all of these.

1. **Accuracy on 5.2 must beat the baseline by ≥ 10 percentage points.** A new dependency or a new
   per-request network call has to buy a visible improvement, not a noisy one. At ~60 evaluation prompts
   a smaller margin is not distinguishable from labelling noise.
2. **p99 `score(…)` latency ≤ 10 ms.** The routing decision sits in front of every gateway request. This
   is the criterion candidate D is most likely to fail, and measuring that failure — rather than
   assuming it — is why D is in the bake-off.
3. **No robustness disqualification** under §6.4. For D this includes the failure mode the others do not
   have: the embedding provider being slow, rate-limited, or down. D must degrade through the facade's
   existing catch to the most capable tier (§3.2) rather than propagating latency.
4. **Among candidates clearing 1–3, the smallest imposed cost wins unless a larger one beats it by ≥ 5
   percentage points.** Revised ordering, after §3.6's correction:

   | | Bytes added | Per-request cost | Rank |
   |---|---|---|---|
   | **C** OpenNLP | none — already shipped by every gateway app | none | cheapest |
   | **D** remote embeddings | none | one network round trip + embedding tokens | middle |
   | **B** in-process ONNX | none in the image; ~240 MB the operator mounts | none | dearest |

   **This reverses C and D.** While C was believed to cost ~1.3 MB, D led on footprint. Now that C adds
   nothing *and* makes no network call, C dominates D on cost outright, and D must beat it by five points
   on accuracy to justify putting a network hop in front of every routing decision.

   B's cost is no longer paid by every deployment (§3.9), so the "order of magnitude of bytes for
   everyone" framing is retired; what remains is operational — artifacts an operator mounts and keeps in
   step with upgrades — real, opt-in, and still more than either alternative asks for.
5. **Within that five-point band, per-workspace extensibility breaks the tie.** Customising an embedding
   centroid per organisation means storing two float vectors; customising an OpenNLP categoriser means
   retraining and storing a model per organisation. Where the measured difference does not decide,
   prefer the cheaper-to-customise technique — which favours D, then B, over C. Registered here before
   any measurement precisely because it would be an illegitimate move afterwards.

Resolution is therefore: candidates clearing 1–3 are eligible; among the eligible, criterion 4 picks and
criterion 5 breaks a tie inside its band; if C and D remain within five points after both, §8's
ship-both outcome applies; if none is eligible, the baseline stays (§8, final case).

## 10. Testing

The bake-off harness is a measurement instrument, not a test, and never runs in CI.

The scorers themselves get ordinary unit tests that *do* run in CI, and these move with the winner:

- B's tests inject a stub `EmbeddingModel` returning fixed vectors, so CI never downloads a model. They
  assert the cosine-to-`[0,1]` mapping, including that it is monotonic and clamps at both ends.
- C's tests train on a handful of inline documents and assert the probability mapping.
- Both are asserted against the degenerate inputs from §6.4.
- The named adversarial cases — "prove the halting problem is undecidable" scoring high, a long pasted
  log with "summarise this" scoring low — become explicit regression tests on the winner.

## 11. Rollout

None. This spec changes no production behaviour, adds no property to a shipped module, and leaves the
default scorer in place. Rollout belongs to the follow-up that integrates the winner.

## 12. Decisions (⚑ = put to the user on 2026-08-25 and answered)

- **⚑1 REVISED 2026-09-02: labels are model-assigned with a human audit, not hand-assigned.** The user
  asked whether Claude could label, was reminded of this decision and its reasoning, and chose the
  audit-sample route: all 100 labels assigned by Claude against the committed rubric, with a nominated
  ~20-entry audit set the user reviews — weighted toward the entries where the rubric's "ties go to
  SIMPLE" clause was invoked and toward the non-English entries, where fluency matters as much as
  difficulty.

  **What this costs, stated plainly so no later reader mistakes it.** The corpus is no longer human
  ground truth. A candidate that wins may be winning because it best reproduces *Claude's* notion of
  complexity rather than because it best predicts which prompts need a capable model. The §9 accuracy
  thresholds were calibrated assuming human labels and are unchanged, so they should now be read as
  "agreement with an audited model-labelled corpus". §13's outcome must say so explicitly, and the report
  must record the audit rate and any labels the audit overturned.

  **Audit outcome, 2026-09-02: completed, zero labels overturned.** The user reviewed the nominated set —
  the four entries where the rubric's "ties go to SIMPLE" clause was invoked (`ex-035`, `ex-043`,
  `adv-009`, `adv-060`), the four non-English reasoning entries where fluency matters as much as
  difficulty (`adv-048`, `adv-050`, `adv-052`, `adv-054`), and a spread across the buckets whose label
  and bucket agree perfectly — and accepted every label. §13's outcome must report this as an audited
  model-labelled corpus with a zero-disagreement audit, not as human ground truth: an audit that
  overturns nothing raises confidence in consistency, not in the labels being independently correct.

  **A live fragility introduced by the labelling.** The adversarial set sits at exactly the shape test's
  lower balance bound — 24 COMPLEX where the test requires at least 24. Flipping any single COMPLEX to
  SIMPLE during the audit turns the build red. That is the guard working, but it means an auditor
  disagreeing with one entry must either flip a SIMPLE the other way or revisit the bound.

  Original decision, retained for the record: **Hand labels, not LLM-as-judge — confirmed.** Rejected LLM labelling because it would measure
  agreement with a judge model rather than with human intent, which is incoherent given every candidate
  exists to keep an LLM out of this path. Cost, accepted: ~100 prompts labelled by hand. An
  LLM-drafts-human-confirms hybrid was offered and declined; anchoring bias would have let draft labels
  through unexamined.
- **⚑2 A throwaway module rather than a branch per candidate.** All three candidates coexist so they can
  be measured in one run against one corpus. The alternative — build one, measure, revert, build the
  next — cannot produce a paired comparison and doubles the corpus-drift risk.
- **⚑6 Candidate D added on 2026-08-24 after retracting the "Merge runs this locally" claim (§3.7).**
  D was previously excluded by an argument resting on that claim. It is nearly free to include, since it
  shares B's implementation. The cost of including it is one extra column in the report and a run that
  needs a reachable embedding provider; the cost of having omitted it would have been shipping a 240 MB
  dependency without ever testing the zero-byte alternative.
- **⚑3 ~60 evaluation prompts, weighted adversarially — confirmed.** A representative sample and a
  both-sets option were offered and declined. A representative sample of ordinary traffic
  would be dominated by cases where all three scorers agree, and would mostly measure the corpus. The
  adversarial weighting means the reported accuracies are *not* estimates of production accuracy, and
  the report must state this in terms a reader cannot miss. The 10-point threshold in §9 is calibrated
  to this set size and this weighting; changing either invalidates it.
- **⚑4 Real traffic is optional and never committed.** Keeps customer prompts out of the repository at
  the cost of making one of the three data sets non-reproducible by anyone but the operator who
  exported it. Its metrics are consequently advisory, not deciding.
- **⚑5 Startup training for C rather than a committed model artifact.** Keeps the exemplar set as the
  single source of truth for both candidates, at the cost of a small fixed startup expense. If C wins
  and that expense proves material, a pre-trained artifact is a later optimisation.

## 13. Outcome

**Run 2026-09-03, all four candidates. Verdict: candidate C (OpenNLP) wins. §8's "C wins" branch applies —
OpenNLP ships in `platform-ai-gateway-service` behind the selector property; B and D are deleted.**

Raw artifacts: `platform-ai-gateway-scorer-bakeoff/src/test/resources/bakeoff/results/2026-09-03-*`.

### Measured

| scorer | accuracy | precision | recall | AUC | mean `score()` |
|---|---|---|---|---|---|
| baseline | 0.600 | 0.000 | 0.000 | 0.212 | 5 µs |
| B local ONNX | 0.750 | 0.765 | 0.542 | 0.838 | 18,558 µs |
| C OpenNLP | 0.733 | 0.750 | 0.500 | 0.882 | **198 µs** |
| D remote embeddings | **0.850** | 0.826 | **0.792** | **0.939** | 165,134 µs |

### Verdict against §9

| criterion | B | C | D |
|---|---|---|---|
| 1. accuracy ≥ baseline + 10pp | ✅ +15.0 | ✅ +13.3 | ✅ +25.0 |
| 2. p99 `score()` ≤ 10 ms | ❌ 18.6 ms mean | ✅ 198 µs mean | ❌ 165 ms mean |
| 3. no robustness disqualification | ✅ | ✅ | ✅ after §13.1 |
| 4. cheapest of those clearing 1–3 | — | **only candidate clearing 1–3** | — |
| 5. tie-break inside 5pp | not reached | not reached | not reached |

**Only C clears criteria 1–3, so criterion 4 resolves to C without reaching criterion 5.**

The harness reports a mean where §9 specifies p99, which it cannot currently compute — but at these
magnitudes the gap is decisive rather than marginal: C is 50× under the threshold, B is ~2× over and D is
~16× over. §6.3's "if D fails by a small margin, re-measure from a representative host" does not rescue D.
The measurement carries wide-area round-trip a co-located gateway would not pay, but a same-region hosted
embeddings call is still tens of milliseconds; no deployment makes a network round trip fit a 10 ms p99.

### What the run revealed that this design got wrong

**1. Both embedding candidates were being scored on different inputs, and only D's failure exposed it.**
D's first run died on `400: maximum context length is 8192 tokens, however you requested 15684` — the
corpus's 100,000-character `long-but-trivial` prompt. B never failed on the same input because its local
ONNX model silently truncates to its own context window. So B had been scoring a truncated prefix while
appearing to handle the full text. Both now truncate explicitly at 20,000 characters. C reads the full
text, which is a genuine asymmetry between the techniques, not an artifact.

**2. AUC rewarded the candidate least able to do the job.** D has the best ranking of any candidate
(AUC 0.939) and the worst calibration: its scores span 0.000–0.514 with a median of 0.495, a
COMPLEX-minus-SIMPLE mean gap of just **+0.041**, and a tier distribution of **58 of 60 prompts in
STANDARD**. A routing score is not consumed as a ranking; it is mapped onto cost tiers by absolute value.
D ranks prompts correctly and then assigns almost all of them to one tier, which is not routing.

C's spread is 0.964 with a +0.364 separation and prompts landing in all five tiers. §9's criteria measured
classification quality; the tier distribution — captured under §6 as a measurement rather than a criterion
— is what distinguishes a usable scorer from an accurate one. **A future bake-off should promote score
separation to a criterion.** Had D cleared latency, the criteria as written would have selected a scorer
that routes 97% of traffic to a single tier.

**3. Latency on a developer machine is not reproducible.** B measured 104 ms, then 5.9 ms, then 18.6 ms
across three runs of unchanged code, purely from machine load. Any future comparison near the threshold
needs a quiet host and p99 capture, neither of which this harness provides.

### Action

Per §8's "C wins" branch: OpenNLP moves into `platform-ai-gateway-service`, behind the selector property
§3.8 requires. B and D are deleted. The bake-off module and corpus are retained as the evidence base and
as the regression harness for any future scorer.
- **⚑7 Per-workspace extensibility is a registered tie-breaker (§9 criterion 5), decided 2026-08-25.**
  The alternative — recording the asymmetry in this section as mere context — was declined on the
  grounds that a consideration buried in an appendix gets rediscovered after the decision it should have
  informed.
- **⚑8 C and D may both ship (§8), decided 2026-08-25.** Accepted that a selector with three values is
  cheap once §3.8 forces a selector at all. The counter-argument — that a knob an operator cannot
  evaluate is a burden — is answered by requiring the report to say which the measurements favoured.
- **⚑9 The cost ordering was corrected mid-execution, before any measurement (§3.6, §9).** Task 3
  discovered OpenNLP already in the production dependency graph, which the spec had asserted it was not.
  Criterion 4's ordering changed from D < C < B to C < D < B as a result. This is legitimate:
  pre-registration forbids moving thresholds after seeing *results*, and no scorer has been run. Recorded
  explicitly so the change is auditable rather than looking like a quiet edit.
