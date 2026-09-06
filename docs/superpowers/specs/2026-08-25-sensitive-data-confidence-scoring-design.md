# Sensitive-Data Confidence Scoring — Design

**Status:** design, not yet planned
**Resolves:** `2026-08-25-guardrails-consolidation-design.md` §5a's open question
**Builds on:** the CE detection core extracted in that spec's sub-project 1

## 1. The problem

`SensitiveSpan` carries a `confidence` field. It is validated (`0.0`–`1.0`), documented, and **read nowhere in the detection pipeline**. Every regex span is created through `SensitiveSpan.of(kind, category, start, end)`, whose javadoc says "for deterministic detectors" and which hard-codes `1.0`.

That conflation is the bug. *Deterministic* means reproducible; *confident* means specific. `\b\d{9}\b` matches deterministically and is very weak evidence that the digits are a US Social Security Number.

The consequence, found by running the curated patterns over ordinary business text:

```
'Order 20260825 shipped; invoice 4500123987 total 1234.56'
    US_BANK_NUMBER  '20260825'
    US_BANK_NUMBER / PHONE_NUMBER  '4500123987'
'SKU AB123456 qty 12345678'
    MEDICAL_LICENSE 'AB123456'
    US_BANK_NUMBER  '12345678'
'Ticket 987654321 escalated'
    US_BANK_NUMBER / US_SSN / AU_TFN  '987654321'
```

Three harms follow. On tokenizing paths the model receives `[PII_US_BANK_NUMBER_1_k3n9]` where an order number was, degrading exactly the reasoning §5a exists to protect. On redact-only paths — the gateway's embeddings endpoint and its streaming completions — the destruction is **irreversible**. And because `RESOLUTION_ORDER` breaks final ties on `category` ascending, `987654321` is labelled `AU_TFN`: a US ticket number reported to the model as an Australian Tax File Number.

The consolidation spec's §5a excluded `DATE_TIME` and `LOCATION` by name and recorded the rest as an open decision. Naming types is the wrong instrument; this spec supplies the right one.

## 2. Why not adopt the reference library

`spring-ai-privacy-guardrails` solves this with per-pattern scores, and it is technically compatible — same Spring AI 2.0.1, Apache 2.0, verified against Java 17–25. It is nonetheless **not** taken as a dependency: version 0.2.1, 19 stars, 0 forks, 36 commits, one maintainer, and its own README declines to claim it is "a complete DLP system." For the most security-sensitive path in the product, whose failure modes are customer PII reaching a model provider and customer content silently corrupted, that is the wrong dependency to acquire.

Its **ideas** are taken: per-pattern scores (this spec), tool-boundary restoration (a separate follow-up), and a Presidio HTTP detector — which our one-method `SensitiveDataDetector` SPI can host directly, with no library. Whether its entity names line up with ours without a mapping layer depends on the provenance question in §3a, which is unresolved.

## 3. Scores

### 3a. A provenance question that must be answered first

This codebase **does not use Presidio**. There is no dependency, no HTTP client, no service. Every occurrence of the word is a name or a comment — the class `PresidioRegexPiiDetector`, a javadoc line, some test method names — all introduced by the consolidation work on the assumption that the entity vocabulary indicated a Presidio origin.

That assumption has never been verified. `PiiDetectorUtilsParityTest`, repeatedly cited as evidence of alignment with an external reference, contains no reference to Presidio; it is a set of detection tests for international entity types, and the "parity" in its name is unexplained. The consolidation spec already recorded this honestly — *"what that reference is has not been verified, so nothing in this design depends on the claim"* — and this spec must not quietly depend on it either.

**It matters here specifically, because a score is calibrated to a regex.** Presidio assigns a confidence to *its* pattern for an entity. Attaching that number to a differently-shaped pattern for the same entity borrows authority the number does not have.

**So implementation begins with a verification step**: for each catalog entry, compare the regex against Presidio's published recognizer for the same entity type.

- Where the pattern **matches** Presidio's, adopt Presidio's published score and record that it was verified.
- Where it **differs**, assign a score from structural specificity — anchored, prefixed, checksum-shaped patterns high; bare digit runs low — and record that it was derived, with the reason.

The outcome is a scored catalog where every number's provenance is stated. That is worth more than a uniformly-sourced set whose applicability is assumed.

### 3b. Where scores live

No score appears in this document. This project has shipped six claims that were asserted rather than checked — a pattern count, a token length, a regex-boundary claim, a character count, a duplicate test name, and the Presidio provenance above. The remedy is fewer assertions, not more care.

Scores live **on the pattern**: `PiiPattern` becomes `(String type, Pattern pattern, double score)` and `SecretPattern` likewise. Not a side map keyed by type — this project has been bitten four separate times by two lists that had to agree and did not, and a score that travels with its pattern cannot drift from it.

`SensitiveSpan.of(kind, category, start, end)` survives, but its contract changes: it means *"this detector has no meaningful score to report,"* not *"deterministic detectors are certain."*

## 4. Where the threshold applies

**After detection, before resolution.** `detectCandidates` continues to return everything; the redact and tokenize paths drop below-threshold spans before `filterByKind` and `resolve`.

Two reasons for that placement, both load-bearing:

- **Before resolution**, so a weak span cannot win a tie-break and then be discarded, leaving a value unredacted when a stronger overlapping span existed. Filtering after `resolve` would do exactly that.
- **`detectCandidates` stays unfiltered**, so the streaming safe-cut keeps pulling its emit boundary back for *any* candidate. A low-confidence match still cannot be split across chunks. The cost is that streaming briefly holds back for spans it will never redact; that is the safe direction, and `DEFAULT_WINDOW` is 512 characters.

## 5. Where the threshold lives

The engine is CE; policy is EE. So:

- **The CE core ships a default threshold as a constant**, chosen so that today's genuinely weak patterns fall below it and everything else behaves exactly as it does now. CE consumers — the guardrails component, and the knowledge base when it is covered — get good behaviour with no configuration.
- **EE workspace policy may override it.** `AiGuardrailsWorkspaceSettings` gains a nullable `Double` beside `piiRedactionEnabled` and `secretRedactionEnabled`. **Null means "use the CE default,"** so existing workspaces are unaffected and no migration has to populate anything.

`SensitiveDataRedactor` is a singleton constructed with its detectors, and the threshold varies per workspace, so it cannot be instance state. The public methods gain overloads: today's signatures apply the CE default, and a form taking an explicit threshold serves EE. That mirrors the overload pattern the gateway adapter already uses for optional context, and no existing call site churns. `AiGuardrails` already resolves an `EffectivePolicy` per workspace and simply passes the value through.

## 6. Exclusion sets after scoring

**`LOW_SPECIFICITY_TYPES` is deleted.** It was introduced to hold `US_BANK_NUMBER`, and its entire justification — "this match is weak evidence" — is what a score says, more granularly and with an external reference behind it. Two mechanisms deciding whether a type fires is the divergence pattern this codebase keeps rediscovering.

**`CONTEXTUAL_TYPES` survives untouched.** `DATE_TIME` matching an ISO date is *high* confidence: the pattern is right, that really is a date. It is excluded because a date is not worth tokenizing, not because the match is doubtful. Confidence and sensitivity are different axes, and a threshold cannot express the second one.

## 7. Observability

One new event, recorded **at most once per content** when any span was dropped for scoring below the threshold — matching the `containsKind` incidence idiom the existing family uses.

Without it the threshold is invisible: an operator cannot distinguish "nothing sensitive was present" from "three candidates were found and discarded." That distinction is the entire reason the knob is worth having.

## 8. Testing

- **The test that would have caught this.** Genuine business text — `Order 20260825`, `invoice 4500123987`, `SKU AB123456`, `Ticket 987654321` — must produce **zero spans** at the default threshold. The existing "clean text unchanged" test uses a sentence containing no digits, which is why nothing failed.
- **Strong patterns are unaffected**: an email address, an AWS access key and an IBAN still detect exactly as before.
- **Scores are pinned** against their Presidio source, alongside the existing taxonomy parity test.
- **The filter's placement is mutation-verified**: moving it after `resolve` must change the ambiguous-nine-digit case.
- **The EE override path**: a workspace value overrides the CE default; null falls back to it.
- **OpenNLP now faces two thresholds** — its own `minConfidence` noise floor, then the pipeline's policy floor. That composes sensibly but is a behaviour change for anyone running OpenNLP, and needs a test and a documented line rather than being discovered.

## 9. Blast radius

| Area | Change |
|---|---|
| CE api | `SensitiveSpan.of`'s contract javadoc |
| CE service | Both catalogs gain a score per entry; both regex detectors construct scored spans; `SensitiveDataRedactor` gains overloads and the filter; `LOW_SPECIFICITY_TYPES` deleted |
| EE | Nullable settings field, its GraphQL surface, one line in `AiGuardrails`, one metric name in `AiGuardrailMetrics`'s javadoc |
| Component | `PiiDetectorUtils` maps catalog entries into its own record and must drop the new field cleanly |
| Docs | `.agents/ai-guardrails.md`; the customer page's coverage description; **§5a's open question resolved rather than restated** |

## 10. Non-goals

- **Checksum validators.** Luhn for credit cards, check digits for most national identifiers. A validated match legitimately scores 1.0 and an unvalidated one stays low — which is what makes `\b\d{9}\b` genuinely *safe* rather than merely deprioritised. It is the strongest available precision win and its own project, one algorithm per country. **Partially reversed 2026-08-31 (D10): Luhn for `CREDIT_CARD` only**, to close a regression the prior fix round introduced (see D10's rationale) — national-identifier check digits remain fully deferred and out of scope.
- **Context-word boosting.** Presidio scores a match near "SSN:" higher. It changes the detector contract — detectors would need surrounding text, not just the match — and interacts awkwardly with the streaming path's bounded lookahead. Deferred indefinitely; smaller benefit than checksums.
- **A settings-page UI for the threshold.** The default is what fixes the reported problem; tuning is the long tail, reachable through the existing API. A stated follow-up, not a silent omission.
- **A Presidio HTTP detector.** Cheap under the existing SPI and worth doing, but independent of scoring.

## 11. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Verify each pattern's provenance first; adopt Presidio's score where the regex genuinely matches, derive from specificity where it does not, and record which | We do not use Presidio and never verified that our regexes came from it; a score is calibrated to a regex, so borrowed numbers on differing patterns carry false authority |
| D2 | Scores live on the pattern record | A side map is a parallel list, and parallel lists have drifted four times here |
| D3 | Filter after detection, before resolution | A weak span must not win a tie-break and then be dropped |
| D4 | `detectCandidates` stays unfiltered | Preserves the streaming safe-cut's conservatism |
| D5 | CE default constant, EE policy override, null means default | Engine is CE, policy is EE; no migration, no new CE config surface |
| D6 | Threshold as a method parameter via overloads | The redactor is a singleton; the threshold is per-workspace |
| D7 | Delete `LOW_SPECIFICITY_TYPES`, keep `CONTEXTUAL_TYPES` | Scoring expresses weak evidence; it cannot express "reliably detected but not worth redacting" |
| D8 | One incidence metric for below-threshold drops | Otherwise the threshold is invisible to the operator tuning it |
| D9 | Do not depend on `spring-ai-privacy-guardrails` | 0.2.1, 19 stars, 0 forks, one maintainer, on the most security-sensitive path in the product |
| D11 | Rename `PresidioRegexPiiDetector` unless provenance is confirmed — **done 2026-08-31**, renamed to `RegexPiiDetector` (Task 1 found regex-level correspondence for only 21/47 = 45%, so the condition for renaming was met) | The name asserts an origin nobody has verified, and a class name outlives the conversation that produced it |
| D10 | Checksums deferred to their own project — **reversed 2026-08-31, `CREDIT_CARD` only**, spec owner's decision: `PiiPattern` gains an optional `Predicate<String>` validator, applied after the regex matches and before a span is emitted; `CREDIT_CARD` supplies a Luhn check and rescores High (`0.9`), matching both formatted and bare 16-digit numbers. Every other entry passes `null` and is unaffected — national-identifier check digits remain deferred, unstarted, and out of scope | The deferral assumed checksums were independent of this project; the prior fix round's `CREDIT_CARD` separator fix (closing a degenerate-match trap) created a regression this branch itself introduced — an unformatted card number fell through to `US_BANK_NUMBER` (Low) and was suppressed — and Luhn is the direct, narrowly-scoped fix for exactly that regression, not a step toward the deferred national-identifier project |
