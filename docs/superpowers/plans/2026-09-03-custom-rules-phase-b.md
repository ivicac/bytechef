# Custom Detection Rules — Implementation Plan (queue item 6, Phase B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a workspace define its own detection patterns, safely — so `ACME-4417-XY` can be redacted without waiting for a catalog release.

**Architecture:** The evaluator is a **stateless CE helper, not a registered `SensitiveDataDetector` bean.** `SensitiveDataRedactor#detectCandidates(text, metrics)` takes no workspace, so a registered bean could never see per-workspace rules. `EffectivePolicy` already carries per-workspace `minConfidence` and `blockedTerms` resolved from settings, so it carries the rules too, and `AiGuardrails` evaluates them and hands the spans into the redactor **before** confidence filtering — which keeps overlap resolution in the one place that already does it.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, Spring for GraphQL, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-03-custom-detection-rules-design.md` — Phase B (§2, §3, §5, §6). Phase A shipped.

## Global Constraints

- **Every quantifier in an operator-supplied regex must be bounded.** See Task 2 for why this one rule replaces most of §5b, and what it buys beyond safety.
- **Validation refuses, never warns** (D8). An ignored warning hangs production; a false positive costs a rewrite.
- **Rules are additive; a built-in type cannot be redefined** (D4). An override would silently weaken a shipped protection through a settings field.
- **Rules are created disabled** (D10). Enabling is a second, deliberate act, and observe mode (`BlockingMode.ALLOW`) is the intended on-ramp.
- **A rule name must satisfy the token grammar `[A-Z][A-Z0-9_]*`** (D12), or it mints tokens `PiiToken` cannot parse back — a value that can never be restored.
- **Context rules raise only** — inherited from Phase A's `ContextRule`, which enforces it in its constructor.
- CE: Apache 2.0 header. EE: Enterprise header, `@version ee`.

## Design refinement recorded here

The spec's §3 says the mechanism "goes in CE `platform-ai-sensitive-data`, next to `RegexPiiDetector`". It does — as a class. It is **not** a `SensitiveDataDetector` bean, because that interface's `detect` is handed text and a deadline and nothing else; per-workspace rules are invisible from there. Reading that settled the seam, and it is the same shape item 8 hit: the thing that knows the workspace is upstream of the thing that does the work.

---

## Task 1: The CE evaluator

**Files:**
- Create: `…/platform-ai-sensitive-data-service/…/CustomPattern.java`, `CustomPatternEvaluator.java`
- Modify: `…/platform-ai-sensitive-data-service/…/SensitiveDataRedactor.java` — accept pre-found spans
- Test: `CustomPatternEvaluatorTest.java`, `SensitiveDataRedactorTest.java`

**Interfaces:**
- Produces: `CustomPattern(String type, Pattern pattern, SensitiveKind kind, double score, @Nullable PiiPatternCatalog.ContextRule contextRule)`
- Produces: `CustomPatternEvaluator.detect(String text, List<CustomPattern> patterns, MatchDeadline deadline)` → `List<SensitiveSpan>`
- Produces: `SensitiveDataRedactor#redactWithSpans(text, kinds, minConfidence, metrics, List<SensitiveSpan> extraCandidates)` and the tokenizing counterpart.

- [ ] **Step 1: Write the failing tests**

Pin: a custom pattern produces a span with its own type and score; its context rule promotes exactly as Phase A's does; the match runs under the deadline so a pathological rule is interrupted; and — the one that matters for §2's "no new precedence concept" — a custom span overlapping a built-in span resolves through the existing rule rather than by insertion order.

- [ ] **Step 2–4:** implement, run, commit.

The evaluator shares Phase A's promotion logic. Extract `confidenceOf` from `RegexPiiDetector` rather than copying it: two copies of the promotion rule is exactly the duplication the consolidation project spent a sub-project removing.

---

## Task 2: The validator — the load-bearing safety slice

**Files:**
- Create: `…/platform-ai-sensitive-data-service/…/CustomPatternValidator.java`
- Test: `CustomPatternValidatorTest.java`

### Why "every quantifier must be bounded" replaces most of §5b

§5b proposed rejecting "nested quantifiers, alternation inside a quantified group with overlapping branches, unbounded backreferences" — a list that needs a regex parser to apply and still misses cases. **Requiring every quantifier to carry an upper bound is one rule, checkable by a scan, and strictly stronger for this purpose:** catastrophic backtracking needs an unbounded repetition to explode into, and a pattern whose every quantifier is `{n,m}` has a computable maximum match length.

That second consequence is worth naming because it is free: **it makes operator rules windowable**, which is precisely the invariant item 7's withdrawn windowing (§4a there) could not establish for the built-in catalog. Custom rules would satisfy it by construction.

The cost is real and accepted: an operator writes `\d{1,20}` rather than `\d+`. D8 already says a false positive costs a rewrite.

- [ ] **Step 1: Write the failing tests**

```java
    @Test void testAnUnboundedQuantifierIsRejected() { … }              // +, *, {n,}
    @Test void testABoundedEquivalentIsAccepted() { … }                 // {1,20}
    @Test void testAQuantifierInsideACharacterClassIsNotAQuantifier() { … }  // [a+b] is literal
    @Test void testAnEscapedQuantifierIsNotAQuantifier() { … }          // \+ is literal
    @Test void testANameOutsideTheTokenGrammarIsRejected() { … }
    @Test void testARuleCannotShadowABuiltInType() { … }
    @Test void testAnOverlongPatternIsRejected() { … }
    @Test void testAPatternThatRunsLongOnAdversarialInputIsRejected() { … }
    @Test void testValidationCatchesAStackOverflowRatherThanPropagatingIt() { … }
```

The last two are not redundant with the bounded-quantifier rule — they are the net for what a lexical scan cannot see. **The `StackOverflowError` one is mandatory**: `(a|aa)+$` over 4,000 characters overflows in ~8ms, faster than any deadline, and is an `Error`. Without the catch, a malformed rule kills the save request instead of being rejected. (That pattern is rejected by the quantifier rule anyway; the test uses a bounded pattern that still recurses deeply, or asserts the catch directly.)

**Do not seed the adversarial timing check from a list of famous ReDoS patterns.** Measured on this JVM: `(a+)+$`, `(a*)*b`, `([a-zA-Z]+)*$` and `(a|aa)+$` all complete in under a millisecond at any input length worth testing. A check seeded from reputation passes everything. Build inputs from the candidate pattern's own character classes and run under a short `MatchDeadline`.

- [ ] **Step 2–4:** implement, run, negative-control (accept an unbounded quantifier → the rejection tests fail), commit.

---

## Task 3: EE storage

**Files:** entity + enum in `…-guardrails-api/domain`, repository/service in `…-guardrails-service`, a changelog beside `ai_guardrail_violation`'s, registered in `master.xml`.

Table `ai_guardrail_custom_rule`: `id`, `workspace_id`, `type`, `pattern`, `kind`, `score`, `context_keywords`, `context_window`, `context_score`, `enabled`, `created_date`, `last_modified_date`. Unique on `(workspace_id, type)` — a workspace cannot define one type twice.

Verified the way the violation table was: an `*IntTest` against Testcontainers, because `master.xml`'s `includeAll` carries `errorIfMissingOrEmpty="false"` and a wrong path is **silently skipped**.

---

## Task 4: Policy wiring

`EffectivePolicy` gains `List<CustomPattern> customRules`, resolved in `effectivePolicyOf` from the workspace's enabled rules. `redactPiiAndSecrets` evaluates them via `CustomPatternEvaluator` and passes the spans as extra candidates.

Pin: a disabled rule detects nothing; a rule from another workspace detects nothing; and rules do not leak into the AI Gateway's throwing `applyToInputs` path unless that path resolves them too.

---

## Task 5: GraphQL CRUD and documentation

Mutations call the validator before persisting, and a rejection reports which defence caught it (`custom_rule_rejected`, tagged `syntax` / `timing` / `length` / `name`). Admin-scoped, workspace-filtered, same authorization shape as the violation read surface.

## Self-Review

**Spec coverage.** §2's rule fields → Tasks 1 and 3. §3's placement → Task 1, with the refinement above. §5a → already shipped (`MatchDeadline`). §5b → Task 2, replaced by a stronger single rule with the reasoning recorded. §5c → Task 2. §5d → Task 2 (length) and Task 3 (count, as a unique constraint plus a cap). §6 → Task 3's `enabled` default and Task 5. §7's `custom_rule_rejected` → Task 5. §8's eight tests → Tasks 1, 2 and 4. D4, D8, D10, D12 → the Global Constraints.

**Placeholder scan.** Tasks 3–5 name their files, their table, their tests and their authorization shape; their bodies follow patterns this branch has now executed twice (violation records, settings). Task 2's test names are given in full because that is the slice where improvising would be dangerous.

**Type consistency.** `CustomPattern` is five components throughout. `CustomPatternEvaluator.detect` takes `(String, List<CustomPattern>, MatchDeadline)`.
