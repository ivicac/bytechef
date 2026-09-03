# Automation AI Gateway — smart routing, remaining work

**Status as of 2026-09-03.** The bake-off is complete — all four candidates measured, verdict recorded in
spec §13: **candidate C (OpenNLP) wins**. Nothing has shipped yet; the production scorer is unchanged.
This document records what was measured, what it means, and the work that remains.

**Superseded sections.** §1–§3 below describe the 2026-09-02 run, which measured only three candidates and
whose latency figures were taken on a loaded machine. They are kept because the reasoning about the
baseline still holds, but the verdict is §13 of the spec, not §3 here. The complete four-candidate results
are at `results/2026-09-03-*` and summarised in §6.

Spec: `2026-08-24-prompt-complexity-scorer-bakeoff-design.md` (criteria in §9, outcomes in §8).
Plan: `2026-08-24-prompt-complexity-scorer-bakeoff.md`.
Raw results: `platform-ai-gateway-scorer-bakeoff/src/test/resources/bakeoff/results/2026-09-02-*`.

## 1. What was measured

Run 2026-09-02 against the 60-prompt adversarial set, threshold 0.5. Candidate D (remote embeddings) was
**not measured** — it needs `BAKEOFF_EMBEDDING_API_KEY`, which was never supplied.

| scorer | accuracy | precision | recall | AUC | mean `score()` |
|---|---|---|---|---|---|
| baseline (shipping) | 0.600 | **0.000** | **0.000** | **0.212** | 4 µs |
| B — embedding centroid, local ONNX | 0.750 | 0.765 | 0.542 | 0.838 | 104,587 µs |
| C — OpenNLP maxent | 0.733 | 0.750 | 0.500 | **0.882** | 767 µs |

Tier distribution over the same 60 prompts:

| scorer | BASIC | EFFICIENT | STANDARD | ADVANCED | FRONTIER |
|---|---|---|---|---|---|
| baseline | 45 | 10 | 5 | 0 | 0 |
| B | 2 | 9 | 47 | 2 | 0 |
| C | 18 | 15 | 17 | 5 | 5 |

Mean score per bucket — the evidence for *why*, not just *whether*:

| bucket | label | baseline | B | C |
|---|---|---|---|---|
| long-but-trivial | SIMPLE | **0.314** | 0.472 | **0.040** |
| short-but-hard | COMPLEX | **0.002** | 0.515 | 0.457 |
| prose-but-complex | COMPLEX | 0.016 | 0.515 | **0.748** |
| code-shaped-but-simple | SIMPLE | 0.155 | 0.420 | 0.174 |
| multi-tool-but-trivial | SIMPLE | 0.216 | 0.409 | 0.336 |
| non-english | mixed | 0.004 | 0.394 | 0.431 |
| degenerate | SIMPLE | 0.084 | 0.303 | 0.256 |

## 2. What the numbers mean

**The shipping scorer is not weak, it is inverted.** Its precision and recall are exactly zero because its
maximum score across all 60 prompts is 0.400 — it never crosses its own threshold, so it classifies
everything SIMPLE, and its 0.600 accuracy is precisely the SIMPLE base rate. An AUC of 0.212 is *below*
chance: its ranking is anti-correlated with the truth. The per-bucket means show why — it scores the
SIMPLE long-paste bucket ~150× higher than the COMPLEX terse bucket, because length, code fences and tool
count are its features and those diverge from difficulty exactly where the adversarial set probes.

This matters for the fix: recalibrating cannot help. Raising or lowering a threshold moves the operating
point along a curve that is upside down.

**B discriminates but does not separate.** Its scores span 0.0–0.613 with a 0.449 median, and its COMPLEX
buckets sit ~0.05 above its SIMPLE ones. That compression is visible in the tier distribution — 47 of 60
prompts land in STANDARD, so the tier mapping receives almost no signal even where the binary
classification is sound.

**C's weakness is terse prompts.** `short-but-hard` averages 0.457, just under threshold, which is what
caps its recall at 0.500. A linguistic-feature model has little to work with when there is little text.

## 3. Verdict against the pre-registered criteria (§9)

| criterion | baseline | B | C | D |
|---|---|---|---|---|
| 1. accuracy ≥ baseline + 10pp | — | ✅ +15.0 | ✅ +13.3 | unmeasured |
| 2. p99 `score()` ≤ 10 ms | ✅ | ❌ 104 ms mean, p99 worse | ✅ 767 µs | unmeasured |
| 3. no robustness disqualification | ✅ | ✅ | ✅ | unmeasured |
| 4. smallest imposed cost wins unless beaten by ≥ 5pp | — | dearest | **cheapest** | middle |
| 5. per-workspace extensibility breaks a tie inside 5pp | — | favours B | disfavours C | favours D |

On the evidence in hand **C is the only candidate clearing 1–3**, and criterion 4 makes it the winner
outright: it adds no bytes (OpenNLP already ships in this image for guardrails) and makes no network call.
B fails criterion 2 by an order of magnitude. C beats B on AUC despite B's marginally higher accuracy —
0.750 vs 0.733 is 45 vs 44 correct out of 60, one prompt, inside noise at this n.

**This verdict is provisional and must not be recorded in spec §13 until D is resolved.** §8 pre-committed
a "ship both" outcome if C and D land within five points, and §5 registers per-workspace extensibility as
the tie-breaker inside that band — which favours D. Skipping D's measurement would decide by omission the
question the candidate was added to answer.

## 4. What remains

### 4.1 Resolve candidate D — blocks everything else

Either export `BAKEOFF_EMBEDDING_API_KEY` and run
`./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:bakeoff`,
or formally record D as unmeasured with the reason.

Note §6.3's pre-registered caveat: D's latency measured from a developer machine is an **upper bound**,
carrying wide-area round-trip a production gateway sharing a region with its provider would not pay. If D
fails criterion 2 by a small margin the honest verdict is "needs re-measuring from a representative host",
not "rejected".

### 4.2 Fill spec §13, and write the report

§13 is still its placeholder. It needs the measured table, the criteria verdict, the resulting §8 action,
and anything the run revealed that the design got wrong.

### 4.3 Ship the winner — work the bake-off plan does not contain

The bake-off is an experiment. Landing its winner is separate, and §3.8 records the obstacle:

> `AiGatewayFacadeImpl` takes one `PromptComplexityScorer` constructor parameter, not a `List` and not a
> `@Qualifier`-ed pair. `DeterministicPromptComplexityScorer` … has no discriminator saying *which*
> scorer it is. So any second implementation on the same classpath fails context startup with
> `NoUniqueBeanDefinitionException`.

So whichever candidate wins requires one production edit the plan's non-goals did not anticipate: turning
the scorer choice into a **selector property** rather than a boolean, and giving the deterministic scorer a
selector condition so it remains the default. That edit is a prerequisite for shipping any candidate, and
supporting a third value afterwards costs one more `@ConditionalOnProperty` value.

The scorer classes currently live under `src/test` in the bake-off module, so shipping also means moving
the winner into `platform-ai-gateway-service` proper.

### 4.4 Decide the default

Even with a selector, someone must choose what a fresh install gets. The measurements say the current
default is worse than random on adversarial traffic; they do not say what fraction of real traffic is
adversarial. A conservative rollout — selector defaulting to the existing scorer, operators opting in —
ships the capability without betting production on a 60-prompt corpus.

## 5. Fixed along the way

Automation-facing gateway bugs found while doing embedded work, all landed on this branch:

- `chatCompletionStream` ignored a model's `defaultRoutingPolicyId` entirely while `chatCompletion`
  honoured it; both paths now run the same chain.
- `ai_gateway_model_deployment` had no `version` column despite `@Version`, so every insert failed —
  routing via deployments could not be exercised at all.
- `AiModel.defaultRoutingPolicyId` was accepted by its GraphQL mutation and silently dropped on update.
- `AiGatewayRoutingPolicy.tags` was missing `@MappedCollection`, breaking tag persistence.

The first is separable as a single commit if it should land ahead of the rest.


## 6. The final run (2026-09-03) — all four candidates

| scorer | accuracy | precision | recall | AUC | mean `score()` |
|---|---|---|---|---|---|
| baseline | 0.600 | 0.000 | 0.000 | 0.212 | 5 µs |
| B local ONNX | 0.750 | 0.765 | 0.542 | 0.838 | 18,558 µs |
| C OpenNLP | 0.733 | 0.750 | 0.500 | 0.882 | **198 µs** |
| D remote embeddings | **0.850** | 0.826 | **0.792** | **0.939** | 165,134 µs |

Score calibration, which is what the tier mapping actually consumes:

| scorer | min | median | max | spread | COMPLEX − SIMPLE gap | tiers used |
|---|---|---|---|---|---|---|
| baseline | 0.000 | 0.019 | 0.400 | 0.400 | **−0.175** | 3 |
| B | 0.000 | 0.449 | 0.613 | 0.613 | +0.095 | 4 |
| C | 0.000 | 0.348 | **0.964** | **0.964** | **+0.364** | **5** |
| D | 0.000 | 0.495 | 0.514 | 0.514 | +0.041 | 2 |

**C wins.** Only C clears criteria 1–3; D is the most accurate candidate and fails the latency budget by
~16×, which no deployment closes. And independently of latency, D's scores cluster so tightly around 0.5
that it routes 58 of 60 prompts to a single tier — the best ranking in the field, and not usable as a
routing signal. The full verdict, including what the design got wrong, is in spec §13.

## 7. What remains, after the verdict

1. **Ship C.** Move `OpenNlpPromptComplexityScorer` out of the bake-off module into
   `platform-ai-gateway-service`, and add the selector property §3.8 requires — the injection point takes a
   single `PromptComplexityScorer`, so a second bean on the classpath fails context startup today.
2. **Decide the default** (see §4.4). The measurements say the current scorer is worse than random on
   adversarial traffic; they do not say what share of real traffic is adversarial. A selector defaulting to
   the existing scorer ships the capability without betting production on a 60-prompt corpus.
3. **Delete B and D**, per §8's pre-committed branch. Keep the module, corpus and metrics as the evidence
   base and the regression harness for any future scorer.
4. **If a future bake-off runs**, promote score separation to a criterion and capture p99 rather than a
   mean — §13 records why both matter.
