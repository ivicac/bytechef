# Sensitive-Data Confidence Scoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every detection pattern a confidence score and filter below-threshold spans, so bare-digit patterns stop firing on order numbers, invoice numbers and ticket IDs.

**Architecture:** `SensitiveSpan.confidence` already exists, is validated, and is read nowhere — every regex span is hard-coded to `1.0`. Scores move onto the pattern records, detectors report them honestly, and `SensitiveDataRedactor` drops below-threshold spans after detection but before resolution. The CE core ships a default threshold; EE workspace policy may override it.

**Tech Stack:** Java 25, Spring Boot 4, Gradle 9.7 (Kotlin DSL), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scoring-design.md`

## Global Constraints

- **No score may be invented.** Spec §3a requires per-pattern provenance: compare each catalog regex against Presidio's published recognizer for that entity type; where it matches, adopt Presidio's score and record it as **verified**; where it differs, derive from structural specificity and record it as **derived, with the reason**. A score whose provenance is unrecorded is a plan failure.
- **This codebase does not use Presidio.** There is no dependency, no HTTP client, no service. Every occurrence of the word is a name or comment introduced on an unverified assumption. Do not add a Presidio dependency in this plan.
- **`CONTEXTUAL_TYPES` survives; `LOW_SPECIFICITY_TYPES` is deleted.** `DATE_TIME` matching a date is *high* confidence — it is excluded because a date is not worth tokenizing, which no score can express. `US_BANK_NUMBER` is excluded because the match is weak evidence, which is exactly what a score expresses.
- **The filter runs after detection and before resolution.** Filtering after `resolve` would let a weak span win a tie-break and then be discarded, leaving a value unredacted when a stronger overlapping span existed.
- **`detectCandidates` stays unfiltered.** The streaming safe-cut pulls its emit boundary back for *any* candidate; a low-confidence match must still not be split across chunks.
- **Secrets never tokenize.** If `testSecretsDoNotSurviveTheRoundTrip` or `testTokenizeInputsNeverTokenizesSecrets` fails, stop and report — that is the safety property the whole feature rests on.
- **CE must never depend on EE.** Any `server:ee:` reference in a CE `build.gradle.kts` is a Critical defect.
- CE files use the Apache 2.0 header and must not contain `@version ee`; `@author Ivica Cardic` is repo convention.
- Java style: one blank line before control statements (`if`, `for`, `while`, `try`, `switch`), except immediately after an opening `{` and except when the keyword continues the previous block on the same line (`} else {`). No blank line between the last member and a class's closing `}`. Blank line between a variable modification and a following statement using it. No abbreviated variable names, including lambda parameters and loop variables. Test method names camelCase with no underscores.
- **Scope spotless to the modules you touch.** A bare `./gradlew spotlessApply` reformats every module and has twice dirtied an unrelated file in this project.
- **Never judge a Gradle run piped into `tail`/`grep`** — the exit code is the filter's. Redirect to a file, check `$?` on its own line, then grep `^> Task .* FAILED`. **Never grep `error:`** — this repo has modules whose paths contain "error" and that false positive has already fooled one check here.
- **Testcontainers needs the OrbStack socket**: `export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock`. `/var/run/docker.sock` is a dangling symlink on this machine.
- Run `git status --short` before every commit and stage only what the task touched.

**Modules**

| Path | Gradle |
|---|---|
| CE api | `:server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api` |
| CE service | `:server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service` |
| Component | `:server:libs:modules:components:ai:agent:guardrails` |
| EE guardrails api | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api` |
| EE guardrails service | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service` |
| EE opennlp | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp` |

**Current shapes you will be changing**

```java
// PiiPatternCatalog
public static final Set<String> CONTEXTUAL_TYPES = Set.of("DATE_TIME", "LOCATION");
public static final Set<String> LOW_SPECIFICITY_TYPES = Set.of("US_BANK_NUMBER");   // deleted by Task 5
public static final List<PiiPattern> ALL = List.of(/* 36 entries */);
public static List<PiiPattern> curatedDefault()                                      // filters both sets
public static List<PiiPattern> filterByTypes(List<String> selectedTypes)
public record PiiPattern(String type, Pattern pattern) { }

// SecretPatternCatalog
public static final List<SecretPattern> ALL = List.of(/* 11 entries */);
public record SecretPattern(String type, Pattern pattern) { }

// SensitiveDataRedactor
public List<SensitiveSpan> detectCandidates(String text, @Nullable SensitiveDataMetrics metrics)
public RedactionResult redactWithSpans(String text, Set<SensitiveKind> kinds, @Nullable SensitiveDataMetrics metrics)
public RedactionResult tokenizeWithSpans(String text, Set<SensitiveKind> kinds, PiiTokenSession session,
    @Nullable SensitiveDataMetrics metrics)

// SensitiveSpan (api module) — the 5-arg canonical constructor already exists
public record SensitiveSpan(SensitiveKind kind, String category, int start, int end, double confidence)
public static SensitiveSpan of(SensitiveKind kind, String category, int start, int end)   // hard-codes 1.0

// AiGuardrailsWorkspaceSettings (EE)
public record AiGuardrailsWorkspaceSettings(
    Boolean redactPii, Boolean redactSecrets, String blockedTerms,
    Boolean moderationEnabled, Boolean injectionDetectionEnabled, Boolean scanResponses)
```

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md` | The provenance table — every score, its source, and whether it was verified or derived |

**Modified**

| File | Change |
|---|---|
| `PiiPatternCatalog.java` | `PiiPattern` gains `score`; 36 entries scored; `LOW_SPECIFICITY_TYPES` deleted |
| `SecretPatternCatalog.java` | `SecretPattern` gains `score`; 11 entries scored |
| `PresidioRegexPiiDetector.java` | Constructs scored spans |
| `RegexSecretDetector.java` | Constructs scored spans |
| `SensitiveSpan.java` | `of(...)`'s contract javadoc |
| `SensitiveDataRedactor.java` | Default constant, threshold overloads, the filter, the metric |
| `AiGuardrailMetrics.java` | One event name in the javadoc enumeration |
| `PiiDetectorUtils.java` (component) | Mapping drops the new field |
| `AiGuardrailsWorkspaceSettings.java` + settings service + GraphQL | Nullable threshold field |
| `AiGuardrails.java` | `EffectivePolicy` carries the threshold; passes it through |
| `.agents/ai-guardrails.md`, `docs/content/docs/platform/automation/deploy/ai-gateway.md` | Scoring documented; §5a's open question resolved |

---

## Task 1: Establish score provenance

**Files:**
- Create: `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a committed table with one row per catalog entry — `type | our regex | Presidio's regex for that entity (or "none found") | verdict: matches / differs / no counterpart | score | provenance: verified / derived | reason`.

This task writes **no code**. Its output is the input to every later task, and the spec forbids inventing scores without provenance.

- [ ] **Step 1: Extract our patterns**

```bash
awk '/ALL = List.of\(/,/^    \);/' server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/PiiPatternCatalog.java > /tmp/pii-patterns.txt
awk '/ALL = List.of\(/,/^    \);/' server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/SecretPatternCatalog.java > /tmp/secret-patterns.txt
wc -l /tmp/pii-patterns.txt /tmp/secret-patterns.txt
```

- [ ] **Step 2: Compare each against Presidio's published recognizers**

Presidio's predefined recognizers and their default scores are published in its documentation and source. For each of our entity types, find the corresponding recognizer, compare the regex, and record the verdict.

Three outcomes, all legitimate:
- **matches** — adopt Presidio's published score, provenance `verified`.
- **differs** — do NOT adopt Presidio's score. Derive one from structural specificity and record the reason. A score is calibrated to a regex; borrowing a number for a differently-shaped pattern gives it authority it has not earned.
- **no counterpart** — Presidio has no recognizer for this entity. Derive, and say so.

For derived scores, use this rubric and state which band each falls in:
- **High** — a fixed literal prefix plus a fixed-length body (`AKIA`+16, `ghp_`+36, `sk_live_`+…), or a structurally distinctive shape (email, IBAN, PEM block). A false positive is implausible.
- **Medium** — a constrained shape with some ambiguity: a letter prefix plus digits, a dotted quad, a dashed group.
- **Low** — a bare digit or alphanumeric run with no anchoring beyond `\b` (`\b\d{9}\b`, `\b\d{8,17}\b`). These are the patterns this whole feature exists for.

Pick the numeric values for the three bands yourself and state them once at the top of the table, so every derived score traces to a stated rule rather than a per-pattern judgement call.

- [ ] **Step 3: Write the table and commit**

The document must open with: the three band values, the date, and an explicit statement that this codebase does not use Presidio and that the comparison was made against its published recognizers by inspection.

```bash
git add docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md
git commit -m "732 docs - Record confidence-score provenance for the detection catalogs"
```

- [ ] **Step 4: Report what you found**

State how many of the 47 entries matched Presidio, how many differed, and how many had no counterpart. If the great majority differ, say so plainly — that is a finding about the "Presidio taxonomy" claim itself, and it should reach the humans rather than being absorbed silently.

---

## Task 2: Scores on the pattern records

**Files:**
- Modify: `PiiPatternCatalog.java`, `SecretPatternCatalog.java`
- Modify: `PiiDetectorUtils.java` (component) — its mapping must drop the new field
- Test: `PiiPatternCatalogTest.java` (new or existing), `PiiDetectorUtilsTest.java`

**Interfaces:**
- Consumes: Task 1's score table.
- Produces: `PiiPatternCatalog.PiiPattern(String type, Pattern pattern, double score)`; `SecretPatternCatalog.SecretPattern(String type, Pattern pattern, double score)`. `ALL`, `curatedDefault()`, `filterByTypes()` keep their signatures.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testEveryPatternCarriesAScoreInRange() {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            assertThat(piiPattern.score())
                .as("score for %s", piiPattern.type())
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    void testBareDigitRunsScoreBelowAnchoredPatterns() {
        double bankNumber = scoreOf("US_BANK_NUMBER");
        double email = scoreOf("EMAIL_ADDRESS");

        assertThat(bankNumber).isLessThan(email);
    }

    private static double scoreOf(String type) {
        return PiiPatternCatalog.ALL.stream()
            .filter(piiPattern -> type.equals(piiPattern.type()))
            .findFirst()
            .orElseThrow()
            .score();
    }
```

- [ ] **Step 2: Run it and see it fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*PiiPatternCatalogTest' > /tmp/t2r.log 2>&1
echo "exit=$?"
grep -E 'error:' /tmp/t2r.log | head -3
```

Expected: FAIL — `score()` does not exist.

- [ ] **Step 3: Add the field and populate from Task 1's table**

Add `double score` as the third component of both records. Populate every entry from the table — **do not re-derive scores here**; the table is the source, and a score that disagrees with it is a bug in this step.

Validate in the compact constructor, matching how `SensitiveSpan` validates its own:

```java
    public record PiiPattern(String type, Pattern pattern, double score) {

        public PiiPattern {
            if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be between 0.0 and 1.0, got: " + score);
            }
        }
    }
```

Then fix the component's mapping at `PiiDetectorUtils.java:41`, which maps catalog entries into the component's own two-field `PiiPattern`. The component does not score; it drops the field.

- [ ] **Step 4: Run the checks**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:libs:modules:components:ai:agent:guardrails:check --continue > /tmp/t2.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t2.log || echo "no failed tasks"
```

Every pre-existing test must pass unmodified — this task adds a field, it changes no behaviour.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Add confidence scores to the detection pattern catalogs"
```

---

## Task 3: Detectors emit scored spans

**Files:**
- Modify: `PresidioRegexPiiDetector.java:67`, `RegexSecretDetector.java`, `SensitiveSpan.java` (javadoc only)
- Test: `PresidioRegexPiiDetectorTest.java`, `RegexDetectorsTest.java`

**Interfaces:**
- Consumes: the scored pattern records from Task 2.
- Produces: spans whose `confidence()` is the matching pattern's score.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testSpanCarriesItsPatternScoreRatherThanFullConfidence() {
        List<SensitiveSpan> spans = detector.detect("mail bob@acme.io please");

        SensitiveSpan emailSpan = spans.stream()
            .filter(span -> "EMAIL_ADDRESS".equals(span.category()))
            .findFirst()
            .orElseThrow();

        double expected = PiiPatternCatalog.ALL.stream()
            .filter(piiPattern -> "EMAIL_ADDRESS".equals(piiPattern.type()))
            .findFirst()
            .orElseThrow()
            .score();

        assertThat(emailSpan.confidence()).isEqualTo(expected);
    }

    @Test
    void testWeakPatternProducesALowerConfidenceSpanThanAStrongOne() {
        double weak = confidenceOfFirstSpan("account 123456789012");
        double strong = confidenceOfFirstSpan("mail bob@acme.io");

        assertThat(weak).isLessThan(strong);
    }
```

Add a `confidenceOfFirstSpan(String)` helper alongside them.

- [ ] **Step 2: Run it and see it fail**

Expected: FAIL — every span currently reports `1.0`, so both assertions fail.

- [ ] **Step 3: Construct scored spans**

Replace `SensitiveSpan.of(SensitiveKind.PII, piiPattern.type(), matcher.start(), matcher.end())` with the five-argument canonical constructor carrying `piiPattern.score()`. Do the same in `RegexSecretDetector`.

Then change `SensitiveSpan.of(...)`'s javadoc. It currently says "Creates a span with full confidence, for deterministic detectors." That phrasing is what produced this whole problem — it conflates *reproducible* with *specific*. It now means: **a detector that has no meaningful score to report**. Say that, and say why the distinction matters.

- [ ] **Step 4: Run the checks**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check > /tmp/t3.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t3.log || echo "no failed tasks"
```

Nothing reads `confidence` yet, so no detection behaviour changes. Any pre-existing failure is a regression.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Report per-pattern confidence from the regex detectors"
```

---

## Task 4: The threshold, the filter, and the metric

**Files:**
- Modify: `SensitiveDataRedactor.java`, `AiGuardrailMetrics.java` (javadoc only)
- Test: `SensitiveDataRedactorTest.java`

**Interfaces:**
- Consumes: scored spans from Task 3.
- Produces: `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE` (a `double` constant); overloads `redactWithSpans(text, kinds, minConfidence, metrics)` and `tokenizeWithSpans(text, kinds, session, minConfidence, metrics)`. Existing three- and four-argument signatures keep working and apply the default.

- [ ] **Step 1: Write the failing test**

`SensitiveDataRedactorTest` uses **stub** detectors deliberately, so it can set scores directly without depending on real regex behaviour. Use that.

```java
    @Test
    void testDropsSpansBelowTheThreshold() {
        SensitiveDataRedactor redactor = redactor(
            fixed("weak", true, new SensitiveSpan(SensitiveKind.PII, "WEAK", 0, 3, 0.2)));

        assertThat(redactor.redact("abc def", BOTH, 0.5, null)).isEqualTo("abc def");
    }

    @Test
    void testKeepsSpansAtOrAboveTheThreshold() {
        SensitiveDataRedactor redactor = redactor(
            fixed("strong", true, new SensitiveSpan(SensitiveKind.PII, "STRONG", 0, 3, 0.9)));

        assertThat(redactor.redact("abc def", BOTH, 0.5, null)).isEqualTo("[REDACTED_STRONG] def");
    }

    @Test
    void testFilteringHappensBeforeResolutionSoAWeakSpanCannotWinATieBreak() {
        SensitiveDataRedactor redactor = redactor(
            fixed("weak", true, new SensitiveSpan(SensitiveKind.PII, "AAA_WEAK", 0, 3, 0.2)),
            fixed("strong", true, new SensitiveSpan(SensitiveKind.PII, "ZZZ_STRONG", 0, 3, 0.9)));

        // Same span, same length. Category ascending would pick AAA_WEAK, but it is below the
        // threshold and must be gone before resolution ever compares them.
        assertThat(redactor.redact("abc def", BOTH, 0.5, null)).isEqualTo("[REDACTED_ZZZ_STRONG] def");
    }

    @Test
    void testDetectCandidatesStaysUnfilteredForTheStreamingSafeCut() {
        SensitiveDataRedactor redactor = redactor(
            fixed("weak", true, new SensitiveSpan(SensitiveKind.PII, "WEAK", 0, 3, 0.2)));

        assertThat(redactor.detectCandidates("abc def", null)).hasSize(1);
    }
```

**`testFilteringHappensBeforeResolutionSoAWeakSpanCannotWinATieBreak` is the load-bearing one.** The category names are chosen so that alphabetical tie-breaking would pick the *weak* span; if filtering moved after `resolve`, the result would be unredacted text instead of `[REDACTED_ZZZ_STRONG]`.

- [ ] **Step 2: Run them and see them fail**

Expected: FAIL — no four/five-argument overload exists.

- [ ] **Step 3: Implement**

Add the constant, the overloads, and the filter. The filter goes between `detectCandidates` and `filterByKind`:

```java
    private static List<SensitiveSpan> filterByConfidence(
        List<SensitiveSpan> candidates, double minConfidence, @Nullable SensitiveDataMetrics metrics) {

        List<SensitiveSpan> kept = new ArrayList<>(candidates.size());
        boolean dropped = false;

        for (SensitiveSpan candidate : candidates) {
            if (candidate.confidence() >= minConfidence) {
                kept.add(candidate);
            } else {
                dropped = true;
            }
        }

        if (dropped && metrics != null) {
            metrics.recordBelowConfidenceThreshold();
        }

        return kept;
    }
```

Add `recordBelowConfidenceThreshold()` to the CE `SensitiveDataMetrics` interface and implement it in EE's `AiGuardrailMetrics` by delegating to the existing string-keyed `record(...)` helper with a new event name — **matching how `recordDetectorFailure` already does it**. Record at most once per content, as the boolean above ensures; the existing family are incidence counters via `containsKind` and this must match. Add the new event name to `AiGuardrailMetrics`'s javadoc enumeration, which nothing else keeps current.

**Choosing the default:** pick `DEFAULT_MIN_CONFIDENCE` so that Task 1's "low" band falls below it and "medium" and "high" stay above. State the value and its justification in the constant's javadoc.

- [ ] **Step 4: Run the checks**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check --continue > /tmp/t4.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t4.log || echo "no failed tasks"
```

- [ ] **Step 5: Prove the placement matters**

Temporarily move the `filterByConfidence` call to after `resolve`. Confirm `testFilteringHappensBeforeResolutionSoAWeakSpanCannotWinATieBreak` FAILS. Revert, confirm it passes, and confirm with `git diff` that nothing of the temporary change survives. **Report which failure you observed** — a placement I cannot see fail is not verified.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "732 Filter sensitive-data spans below a confidence threshold"
```

---

## Task 5: Delete LOW_SPECIFICITY_TYPES

**Files:**
- Modify: `PiiPatternCatalog.java` (delete the set, update `curatedDefault()` and the class javadoc)
- Test: `PresidioRegexPiiDetectorTest.java`, `RegexDetectorsTest.java`

**Interfaces:**
- Consumes: the threshold from Task 4.
- Produces: `curatedDefault()` excluding only `CONTEXTUAL_TYPES`; count becomes 34.

- [ ] **Step 1: Write the failing test — the one that would have caught this**

The existing "clean text unchanged" test uses a sentence containing no digits, which is why nothing ever failed. This one uses real business text:

```java
    @Test
    void testOrdinaryBusinessIdentifiersProduceNoSpansAtTheDefaultThreshold() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("Order 20260825 shipped; invoice 4500123987 total 1234.56", BOTH, null))
            .isEqualTo("Order 20260825 shipped; invoice 4500123987 total 1234.56");
        assertThat(redactor.redact("SKU AB123456 qty 12345678", BOTH, null))
            .isEqualTo("SKU AB123456 qty 12345678");
        assertThat(redactor.redact("Ticket 987654321 escalated", BOTH, null))
            .isEqualTo("Ticket 987654321 escalated");
    }

    @Test
    void testStrongPatternsStillDetectAtTheDefaultThreshold() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("mail bob@acme.io", BOTH, null)).isEqualTo("mail [REDACTED_EMAIL_ADDRESS]");
        assertThat(redactor.redact("key AKIAIOSFODNN7EXAMPLE", BOTH, null)).isEqualTo("key [REDACTED_SECRET]");
    }

    @Test
    void testCuratedDefaultExcludesOnlyTheContextualTypes() {
        assertThat(PiiPatternCatalog.curatedDefault())
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .doesNotContain("DATE_TIME", "LOCATION")
            .contains("US_BANK_NUMBER");
    }
```

Note the third: `US_BANK_NUMBER` returns to the curated set. It no longer fires because it scores low, not because it was named.

- [ ] **Step 2: Run them and see them fail**

Expected: the business-text test FAILS if any score or the threshold is wrong — which is the point. The `curatedDefault` test FAILS because `US_BANK_NUMBER` is still excluded by name.

- [ ] **Step 3: Delete the set**

Remove `LOW_SPECIFICITY_TYPES` and its filter line from `curatedDefault()`. Rewrite the class javadoc: the "Open question" paragraph about the eight bare-digit types is now **resolved** — they are scored, and the threshold decides. Say that, and delete the open-question framing rather than leaving it to confuse a future reader.

Update any test asserting the curated count from 33 to 34, and verify the number with a command rather than arithmetic.

- [ ] **Step 4: Run everything**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:libs:modules:components:ai:agent:guardrails:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check --continue > /tmp/t5.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t5.log || echo "no failed tasks"
```

If a business-text assertion fails, **do not adjust the test** — it is the specification. Either a score or the threshold is wrong; fix that, or report BLOCKED with the specifics.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Replace the low-specificity exclusion set with confidence scoring"
```

---

## Task 6: EE workspace policy override

**Files:**
- Modify: `AiGuardrailsWorkspaceSettings.java`, its settings service, the guardrails GraphQL schema and mapping, `AiGuardrails.java`
- Test: `AiGuardrailsTest.java`

**Interfaces:**
- Consumes: the overloads from Task 4.
- Produces: `AiGuardrailsWorkspaceSettings` with a nullable `Double minConfidence` as its final component; `EffectivePolicy` carrying a resolved `double minConfidence`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testWorkspaceThresholdOverridesTheCoreDefault() {
        AiGuardrails guardrails = guardrails(null, true, false, "", false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID))
            .thenReturn(Optional.of(settingsWithMinConfidence(0.95)));

        List<GuardrailCheckResult> results = guardrails.checkInputs(
            List.of("mail bob@acme.io"), WORKSPACE_ID, metrics);

        // At 0.95 even a strong pattern is below threshold, so nothing is redacted.
        assertThat(results.getFirst()
            .text()).isEqualTo("mail bob@acme.io");
    }

    @Test
    void testNullWorkspaceThresholdFallsBackToTheCoreDefault() {
        AiGuardrails guardrails = guardrails(null, true, false, "", false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID))
            .thenReturn(Optional.of(settingsWithMinConfidence(null)));

        List<GuardrailCheckResult> results = guardrails.checkInputs(
            List.of("mail bob@acme.io"), WORKSPACE_ID, metrics);

        assertThat(results.getFirst()
            .text()).isEqualTo("mail [REDACTED_EMAIL_ADDRESS]");
    }
```

Add a `settingsWithMinConfidence(Double)` helper building an `AiGuardrailsWorkspaceSettings` with the other fields at their existing defaults.

- [ ] **Step 2: Run them and see them fail**

Expected: FAIL — the settings record has no such component.

- [ ] **Step 3: Implement**

Add `Double minConfidence` as the final component of `AiGuardrailsWorkspaceSettings`. **Nullable, and null means "use the CE default"** — so existing stored settings deserialize unchanged and nothing has to migrate.

Carry it into `EffectivePolicy` (constructed at `AiGuardrails.java:809`, declared at `:902`) as a resolved `double`, falling back to `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE` when the setting is null. Pass it into the Task 4 overloads from `redactPiiAndSecrets`.

Expose it on the guardrails GraphQL type and input alongside the existing settings fields, following whatever pattern `scanResponses` uses in that schema.

- [ ] **Step 4: Run the checks**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:check --continue > /tmp/t6.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t6.log || echo "no failed tasks"
```

Then the wider compile, since a public record gained a component:

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t6c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t6c.log || echo "no failed tasks"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Allow workspace policy to override the confidence threshold"
```

---

## Task 7: The OpenNLP double-threshold interaction

**Files:**
- Test: `OpenNlpSensitiveDataDetectorTest.java` (EE opennlp module)
- Modify: `OpenNlpSensitiveDataDetector.java` (javadoc only)

**Interfaces:**
- Consumes: the pipeline threshold from Task 4.
- Produces: nothing new.

`OpenNlpSensitiveDataDetector` already drops spans below its own `minConfidence`, computing a real per-span probability from the model. Its survivors will now *also* face the pipeline's threshold. That composes sensibly — a model noise floor, then a policy floor — but it is a **behaviour change for anyone running OpenNLP** and must be tested and documented rather than discovered.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testSpansSurvivingTheModelFloorStillFaceThePipelineThreshold() {
        // A span the detector itself accepts (above its own minConfidence) but which the pipeline
        // rejects, because the pipeline's policy floor is higher than the model's noise floor.
        SensitiveSpan modelSpan = new SensitiveSpan(SensitiveKind.PII, "PERSON", 0, 5, 0.55);

        SensitiveDataRedactor redactor = new SensitiveDataRedactor(List.of(fixedDetector(modelSpan)));

        assertThat(redactor.redact("Alice went home", EnumSet.allOf(SensitiveKind.class), 0.8, null))
            .isEqualTo("Alice went home");
        assertThat(redactor.redact("Alice went home", EnumSet.allOf(SensitiveKind.class), 0.5, null))
            .isEqualTo("[REDACTED_PERSON] went home");
    }
```

Add a small `fixedDetector(SensitiveSpan...)` helper if the module has none.

- [ ] **Step 2: Run it and see it fail**

Expected: FAIL — no threshold overload before Task 4; if Tasks 1–6 are done, it should pass, in which case verify by mutation that removing the pipeline filter breaks it.

- [ ] **Step 3: Document the composition**

Add a paragraph to `OpenNlpSensitiveDataDetector`'s javadoc explaining that its `minConfidence` is a **model noise floor** — "is this span real?" — while the pipeline's threshold is a **policy floor** — "is this type of match worth acting on?" — and that a span must clear both. State plainly that an operator who previously tuned `minConfidence` alone may now see fewer redactions.

- [ ] **Step 4: Run the check**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp:check > /tmp/t7.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t7.log || echo "no failed tasks"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Test and document how OpenNLP's model floor composes with the policy threshold"
```

---

## Task 8: Documentation

**Files:**
- Modify: `.agents/ai-guardrails.md`, `docs/content/docs/platform/automation/deploy/ai-gateway.md`
- Modify: `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` (§5a's open question)

- [ ] **Step 1: Update the internal doc**

In its existing voice: that scores now live on the patterns with recorded provenance; that the threshold filters after detection and before resolution, and why; that the CE default can be overridden per workspace with null meaning default; the new metric; the OpenNLP composition; and that `LOW_SPECIFICITY_TYPES` is gone while `CONTEXTUAL_TYPES` remains, with the confidence-versus-sensitivity distinction stated explicitly — it is the thing most likely to be re-conflated later.

**Verify every number against the code with a command before writing it.** This project has shipped six claims that were asserted rather than checked.

- [ ] **Step 2: Update the customer-facing page**

An operator needs to know: detection no longer fires on every long number; there is a tunable threshold; and what the default means for them. Match the page's register — it is written for operators, not engineers. Do not restate the internal doc.

- [ ] **Step 3: Resolve §5a's open question in the consolidation spec**

That section records "whether any of these eight types should move to `LOW_SPECIFICITY_TYPES` is a product and compliance decision... left for the spec owner." It is now answered: none of them move, because the set is gone and scoring decides. Replace the open-question paragraph with the resolution and a pointer to the scoring spec. Do not delete the history of why it was open.

- [ ] **Step 4: Commit**

```bash
git add .agents docs/content docs/superpowers
git commit -m "732 docs - Document confidence scoring and resolve the curated-default question"
```

---

## Task 9: Full verification

- [ ] **Step 1: Every touched module, forced**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check \
          :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check \
          :server:libs:modules:components:ai:agent:guardrails:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
          --rerun-tasks --continue > /tmp/t9.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t9.log || echo "no failed tasks"
```

`--rerun-tasks` matters: a cached `UP-TO-DATE` is not evidence for a verification task.

- [ ] **Step 2: Whole-server compile**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t9c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t9c.log || echo "no failed tasks"
```

- [ ] **Step 3: Confirm each constraint with a real command**

1. **Business identifiers produce no spans** — `testOrdinaryBusinessIdentifiersProduceNoSpansAtTheDefaultThreshold` passes.
2. **Strong patterns still fire** — `testStrongPatternsStillDetectAtTheDefaultThreshold` passes.
3. **Secrets never tokenize** — `testSecretsDoNotSurviveTheRoundTrip` and `testTokenizeInputsNeverTokenizesSecrets` both pass.
4. **Every score has recorded provenance** — every type in `PiiPatternCatalog.ALL` and `SecretPatternCatalog.ALL` appears in the Task 1 table.
5. **Every score is in range** — no entry outside `[0.0, 1.0]`.
6. **`LOW_SPECIFICITY_TYPES` is gone** — `grep -rn 'LOW_SPECIFICITY_TYPES' server --include='*.java' --exclude-dir=build` returns nothing.
7. **`curatedDefault()` excludes only the two contextual types**, count 34.
8. **Filtering precedes resolution** — in `SensitiveDataRedactor`, the confidence filter's call site appears before `resolve` in the same method.
9. **`detectCandidates` is unfiltered** — it does not call the confidence filter.
10. **CE does not depend on EE** — no `server:ee:` in either CE module's build file.
11. **No Presidio dependency was added** — `grep -rn 'presidio' gradle/libs.versions.toml` returns nothing.

- [ ] **Step 4: Report**

State the check and compile results, all eleven confirmations, the mutation evidence from Task 4 Step 5, and anything that failed or looked weaker than expected.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §3a provenance verification | Task 1, Task 9 §3.4 |
| §3b scores on the pattern record | Task 2 |
| §3b `SensitiveSpan.of` contract | Task 3 |
| §4 filter after detection, before resolution | Task 4 (+ mutation evidence at Step 5), Task 9 §3.8 |
| §4 `detectCandidates` stays unfiltered | Task 4, Task 9 §3.9 |
| §5 CE default constant | Task 4 |
| §5 EE override, null means default | Task 6 |
| §6 delete `LOW_SPECIFICITY_TYPES`, keep `CONTEXTUAL_TYPES` | Task 5 |
| §7 incidence metric | Task 4 |
| §8 business-text test, strong patterns, score pinning, placement mutation, EE override, OpenNLP | Tasks 4, 5, 6, 7 |
| §9 blast radius | Tasks 2–8 |
| §2 no library dependency | Task 9 §3.11 |

§10's non-goals — checksums, context-word boosting, the settings UI, a Presidio HTTP detector — are deliberately absent.

**Placeholder scan:** Task 1 deliberately does not state score values, because §3a forbids inventing them and the whole task is establishing what they should be; it specifies the rubric, the three bands, and the decision procedure instead. Task 4's `DEFAULT_MIN_CONFIDENCE` value likewise follows from Task 1's bands rather than being asserted here. Everything else is concrete.

**Type consistency:** `PiiPattern(String type, Pattern pattern, double score)` and `SecretPattern(String type, Pattern pattern, double score)` are used identically in Tasks 2, 3 and 5. `score()` is the accessor throughout. `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE` is defined in Task 4 and referenced in Task 6. `recordBelowConfidenceThreshold()` is added to `SensitiveDataMetrics` in Task 4 and implemented in EE in the same task. `minConfidence` is the parameter name everywhere it appears.

**One risk for the executor:** Task 5's business-text test is the specification, not a check. If it fails, a score or the threshold is wrong — adjusting the test to pass would defeat the entire feature. The plan says so at the point of failure, but it is the single most likely way this work goes wrong.
