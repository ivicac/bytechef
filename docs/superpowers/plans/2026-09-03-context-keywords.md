# Context Keywords — Implementation Plan (queue item 6, Phase A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Buy back the eleven pattern types the confidence work suppressed, by raising a match's confidence when a naming keyword sits next to it — so `Passport: A12345678` is redacted and `Order A12345678` is not.

**Architecture:** `PiiPatternCatalog.PiiPattern` gains an optional `ContextRule` (keywords, window, promoted score), following the same convenience-overload shape `validator` already uses so the unaffected entries do not change. `RegexPiiDetector` emits the promoted score instead of the base score when a keyword falls within the window of a match. Nothing else moves: `SensitiveDataRedactor`'s existing confidence filter then does the work.

**Tech Stack:** Java 25, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-03-custom-detection-rules-design.md` — **Phase A only.** Phase B (operator-supplied rules) is blocked; see that spec's §0.

## Global Constraints

- **Raise only, never lower** (spec D2). A negative keyword is an attacker-writable off switch: `Order number: <real SSN>` would disable the guardrail with a prefix. The `ContextRule` constructor must reject a promoted score at or below the base.
- **Keyword lists are catalog content, not operator-editable** (D3). They are reviewed with the patterns. An operator wanting different keywords writes a custom rule — Phase B.
- **The false positives the confidence work fixed must stay fixed.** `Order 123456789` still scores `0.2` and is still dropped. This is the regression direction and it needs its own test.
- **No `context_keyword_promoted` metric in this phase** — the detector SPI takes no metrics parameter. Deferred with the reason in the spec's §0; do not add an SPI overload for it here.
- CE module: Apache 2.0 header, no `@version ee`.
- `./gradlew spotlessApply` before each commit; judge Gradle by `$?` and `grep '^> Task .* FAILED'`.

## File Structure

**Modify:**
- `…/platform-ai-sensitive-data-service/…/PiiPatternCatalog.java` — the `ContextRule` record, an overload, and keywords on the suppressed types
- `…/platform-ai-sensitive-data-service/…/RegexPiiDetector.java` — apply the promotion
- `…/platform-ai-sensitive-data-service/src/test/…/PiiPatternCatalogTest.java`
- `…/platform-ai-sensitive-data-service/src/test/…/RegexPiiDetectorTest.java`
- `.agents/ai-guardrails.md`

**Create:** nothing.

---

## Task 1: `ContextRule`, and the detector applies it

**Files:**
- Modify: `PiiPatternCatalog.java`, `RegexPiiDetector.java`
- Test: `RegexPiiDetectorTest.java`

**Interfaces:**
- Produces: `PiiPatternCatalog.ContextRule(Set<String> keywords, int window, double score)` and a `PiiPattern` overload taking one. Task 2 populates them.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testAKeywordNearAMatchRaisesItsConfidenceAboveTheDefaultThreshold() {
        // The whole point, asserted as an OUTCOME rather than as a score: at the default threshold a promoted
        // US_PASSPORT survives redaction and an unpromoted one does not. Asserting the number would pin the
        // arithmetic and miss whether it changes anything.
        …
    }

    @Test
    void testTheSameShapeWithoutAKeywordKeepsItsLowScore() { … }

    @Test
    void testAKeywordOutsideTheWindowDoesNotPromote() {
        // Without this, one keyword anywhere in a long document promotes every match in it.
        …
    }

    @Test
    void testKeywordMatchingIsCaseInsensitive() { … }

    @Test
    void testAContextRuleThatWouldLowerTheScoreIsRejected() {
        // Raise-only is a security property, not a style choice: a lowering keyword is an off switch an attacker
        // writes into the prompt.
        …
    }
```

- [ ] **Step 2: Run them, expect a compile failure on `ContextRule`**

- [ ] **Step 3: Add the record and the overload**

```java
    /**
     * Raises a match's confidence when a naming keyword sits near it.
     *
     * @param keywords the lower-cased terms that promote a match; compared case-insensitively
     * @param window   how many characters either side of the match a keyword may sit in
     * @param score    the promoted confidence, which must EXCEED the pattern's base score
     */
    public record ContextRule(Set<String> keywords, int window, double score) { … }
```

with a compact constructor rejecting an empty keyword set, a non-positive window, and a score outside `(0.0, 1.0]`. The base-score comparison belongs in `PiiPattern`'s constructor, which is the only place that knows both.

- [ ] **Step 4: Apply it in the detector**

In `RegexPiiDetector#detect`, replace the span's confidence with a computed one:

```java
                spans.add(
                    new SensitiveSpan(
                        SensitiveKind.PII, piiPattern.type(), matcher.start(), matcher.end(),
                        confidenceOf(piiPattern, text, matcher.start(), matcher.end())));
```

`confidenceOf` returns the base score when there is no rule, and the promoted score when any keyword occurs in `text` within `window` characters either side of the match. Case-insensitive; the window clamps to the text bounds.

- [ ] **Step 5: Run the tests, expect PASS**

- [ ] **Step 6: Negative-control the promotion**

Force `confidenceOf` to return `piiPattern.score()` unconditionally. Expected: the promotion tests fail and `testTheSameShapeWithoutAKeywordKeepsItsLowScore` still passes. Then force it to return the promoted score unconditionally: expected, the no-keyword and out-of-window tests fail. Two controls, because one arm of the branch can be wrong without the other.

- [ ] **Step 7: Commit**

---

## Task 2: Keywords for the eleven suppressed types

**Files:** `PiiPatternCatalog.java`, `PiiPatternCatalogTest.java`

Every `0.2`-scored entry is below `DEFAULT_MIN_CONFIDENCE` and therefore detected by nothing today: `MEDICAL_LICENSE`, `US_BANK_NUMBER`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `IT_DRIVER_LICENSE`, `IT_PASSPORT`, `IT_IDENTITY_CARD`, `PL_PESEL`, `AU_TFN`, `IN_VOTER`, `IN_PASSPORT`.

- [ ] **Step 1: Give each a keyword set**

Terms an operator's own text would actually contain near the value — the entity's names and its common abbreviations, in the languages the type belongs to. Window 40 characters, promoted score `0.9`: a keyword next to a shape this specific is strong evidence, and a lower promotion would leave the type still filtered at a workspace that raised its threshold.

- [ ] **Step 2: Pin the catalog invariants**

```java
    @Test
    void testEveryLowScoredTypeHasContextKeywords() {
        // A Low-scored pattern with no keywords is detected by NOTHING at the default threshold. That is a
        // deliberate state for a type nobody can name in text, and an accident for every other -- so the list of
        // exceptions is explicit and this test is what makes adding one a decision.
        …
    }

    @Test
    void testEveryContextRulePromotesAboveTheDefaultThreshold() { … }

    @Test
    void testContextKeywordsAreLowerCasedInTheCatalog() {
        // Matching lower-cases the haystack, so an upper-case keyword in the catalog would never fire.
        …
    }
```

- [ ] **Step 3: The regression direction**

```java
    @Test
    void testOrdinaryBusinessIdentifiersAreStillNotRedacted() {
        // The false positives the confidence work fixed must stay fixed. Extends the existing corpus test rather
        // than replacing it.
        …
    }
```

Extend the existing `testACorpusOfOrdinaryBusinessIdentifiers…` test if one exists rather than adding a parallel one.

- [ ] **Step 4: Run, commit**

---

## Task 3: Documentation

- [ ] `.agents/ai-guardrails.md` — a section carrying: the eleven-type coverage hole this closes; that promotion is raise-only and why lowering is an off switch; that keyword lists are catalog content; that the metric is absent; and that Phase B is blocked on a genuinely interruptible regex bound.

## Self-Review

**Spec coverage.** §1's mechanism → Task 1. §4 ("keywords apply to built-in patterns too", the day-one value) → Task 2. D1, D2, D3 → Tasks 1–2 and the Global Constraints. §7's first three test bullets → Tasks 1–2. D4–D12 and §2/§3/§5/§6 are all Phase B, blocked in the spec's §0. §7's `context_keyword_promoted` → explicitly deferred.

**Placeholder scan.** Task 1's and Task 2's test bodies are elided to their names and the comment stating what each pins, because the fixtures depend on `ContextRule`'s final shape from Task 1 Step 3. Every one names the property and the failure it guards against, which is the part that must not be improvised.

**Type consistency.** `ContextRule(Set<String>, int, double)` throughout. `confidenceOf(PiiPattern, String, int, int)` returns `double`.
