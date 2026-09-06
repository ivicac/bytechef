# Guardrail Observe Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `BlockingMode` a third value, `ALLOW`, that detects and records a blocking violation but forwards the content unmodified — so an operator can turn a guardrail on against real traffic before enforcing it.

**Architecture:** `AiGuardrails.checkInput` already computes two texts for a blocking violation: the PII/secret-redacted text, and that text with the violation additionally masked (blocked term replaced, or the whole message replaced for moderation). Today only the second escapes. `GuardrailCheckResult` gains the first as a second component, and `AiGuardrailsAdvisor` — which already owns the `BlockingMode` decision — picks between them at the switch it already has.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI advisors, Micrometer, Spring for GraphQL, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-08-31-guardrail-action-policy-design.md`

## Global Constraints

- **Only the model boundary ships.** The spec's §3 (`ToolPiiAction`) and the tool-boundary rows of its §8 blast-radius table are **deferred by its own D4** to the Component Policies per-action slice. Nothing in `PiiTokenBoundaryToolCallingManager` changes in this plan. Do not add a per-tool action map to `AiGuardrailsWorkspaceSettings`.
- **Append `ALLOW` at the end of `BlockingMode`, never reorder** (spec D1). Ordinals are pinned by `BlockingModeStabilityTest`.
- **`ALLOW` must record a metric** (spec D2). An action that neither modifies nor reports is indistinguishable from the guardrail being off.
- **EE files** (`server/ee/**`): ByteChef Enterprise license header, `@version ee` Javadoc tag.
- **No new UI.** API and GraphQL only, matching the confidence-threshold precedent (spec §9).
- Run `./gradlew spotlessApply` before every commit. Judge Gradle by `$?` on its own line and `grep '^> Task .* FAILED'`, never by a pipe's exit code.

## Two corrections to the spec, resolved here

The spec was written before this code was read closely. Both corrections are recorded so an implementer does not try to follow the letter of §5 and find no such code.

**1. §5 says "`ALLOW` in `AiGuardrails`, at the existing `BlockingMode` switch."** There is no `BlockingMode` switch in `AiGuardrails`. The engine is deliberately mode-agnostic — `checkInputs`' javadoc states it exists for "callers that need to choose HOW to handle a blocking violation … instead of having this engine always throw". The only switch is `AiGuardrailsAdvisor:270`. **Decision: the arm goes in the advisor, and the engine stays mode-agnostic.** The engine's job is to compute both candidate texts; the advisor's job is to choose. Pushing the mode into `AiGuardrails` would have given `EffectivePolicy` a field the gateway's throwing path must then be proven to ignore — a proof obligation the chosen shape does not create.

**2. §7's first test says "`ALLOW` forwards text byte-identical to the input".** That holds only for an input carrying no PII or secrets. `BlockingMode` governs the three *blocking* guardrails — blocked terms, injection, moderation (see `AiGuardrailsWorkspaceSettings#blockingMode`'s javadoc and `AiGuardrails`' class javadoc) — while PII/secret redaction is documented as "always applied inline" on both paths and is not a blocking guardrail. **Decision: `ALLOW` suppresses only the blocking-specific transformation.** The test payload must therefore contain a blocked term and no PII, or it pins the wrong thing.

## What `ALLOW` changes, per violation category

| Category | Text forwarded today (`REDACT_AND_CONTINUE`) | Text forwarded under `ALLOW` |
|---|---|---|
| `blocked_term` | `maskBlockedTerm(redacted, term)` — the term replaced with `[REDACTED_BLOCKED_TERM]` | `redacted` — the term left in place |
| `injection_flagged` | `redacted` | `redacted` — **no change**; injection has no locatable span, so nothing was masked to begin with |
| `moderation_flagged` | `MODERATION_PLACEHOLDER` — the whole message replaced | `redacted` — the message left in place |
| none | `redacted` | `redacted` — **no change** |

`injection_flagged` is deliberately a no-op on the text. It still records `guardrail_allowed`, because the operator needs to know the classifier fired.

## File Structure

**Modify:**
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsWorkspaceSettings.java` — `BlockingMode` gains `ALLOW`
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/test/java/com/bytechef/ee/platform/ai/guardrails/domain/BlockingModeStabilityTest.java` — pin the third ordinal
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls` — `AiGuardrailsBlockingMode` gains `ALLOW`
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java` — `GuardrailCheckResult` gains `unmaskedText`
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java` — the `ALLOW` arm
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailMetrics.java` — `guardrail_allowed` in the event list javadoc
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsTest.java` — record-shape call sites
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorTest.java` — the new behaviour
- `.agents/ai-guardrails.md` — observe mode

**Do not create any new file.**

---

## Task 1: `ALLOW` reaches the settings surface

**Files:**
- Modify: `…/platform-ai-guardrails-api/…/domain/AiGuardrailsWorkspaceSettings.java`
- Modify: `…/platform-ai-guardrails-api/src/test/…/domain/BlockingModeStabilityTest.java`
- Modify: `…/platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls`

**Interfaces:**
- Produces: `BlockingMode.ALLOW`, ordinal 2. Task 2 consumes it.

- [ ] **Step 1: Extend the ordinal pin first, and watch it fail**

In `BlockingModeStabilityTest`:

```java
    @Test
    void testBlockingModeOrdinalsArePinned() {
        assertThat(BlockingMode.BLOCK.ordinal()).isEqualTo(0);
        assertThat(BlockingMode.REDACT_AND_CONTINUE.ordinal()).isEqualTo(1);
        assertThat(BlockingMode.ALLOW.ordinal()).isEqualTo(2);
        assertThat(BlockingMode.values()).hasSize(3);
    }
```

- [ ] **Step 2: Run it and confirm it does not compile**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:compileTestJava
```

Expected: `cannot find symbol: variable ALLOW`. That is the failing state — a compile error, not a red assertion, because the pin names a constant that does not exist yet.

- [ ] **Step 3: Append the value**

In `AiGuardrailsWorkspaceSettings`:

```java
    public enum BlockingMode {

        BLOCK,
        REDACT_AND_CONTINUE,

        /**
         * Observe mode: the violation is detected and recorded, and the content is forwarded unmodified. Governs the
         * three blocking guardrails only — blocked terms, injection, moderation — exactly as the other two values do;
         * PII and secret redaction are applied inline on every path and are unaffected by this setting.
         *
         * <p>
         * Exists so a guardrail can be turned on against real traffic before it enforces: run a week in
         * {@code ALLOW}, read the {@code guardrail_allowed} counter, then promote to {@code REDACT_AND_CONTINUE} or
         * {@code BLOCK}. Without it, every guardrail change is a leap taken on production traffic.
         * </p>
         */
        ALLOW
    }
```

- [ ] **Step 4: Run the pin and confirm it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:test --tests '*BlockingModeStabilityTest*'
```

Expected: PASS.

- [ ] **Step 5: Extend the GraphQL enum**

In `ai-guardrails-workspace-settings.graphqls`:

```graphql
enum AiGuardrailsBlockingMode {
    BLOCK
    REDACT_AND_CONTINUE
    ALLOW
}
```

The controller binds `BlockingMode` directly (`AiGuardrailsWorkspaceSettingsGraphQlController:112` takes a `@Nullable BlockingMode`), so no mapper changes. Leave `bin/main/graphql/…` alone — it is a stale build artefact, not a source file.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add -A && git commit -m "732 Add BlockingMode.ALLOW, the observe mode"
```

---

## Task 2: The advisor forwards unmodified under `ALLOW`

**Files:**
- Modify: `…/platform-ai-guardrails-service/…/AiGuardrails.java`
- Modify: `…/platform-ai-guardrails-service/…/advisor/AiGuardrailsAdvisor.java`
- Modify: `…/platform-ai-guardrails-service/…/AiGuardrailMetrics.java`
- Test: `…/platform-ai-guardrails-service/src/test/…/advisor/AiGuardrailsAdvisorTest.java`
- Test: `…/platform-ai-guardrails-service/src/test/…/AiGuardrailsTest.java`

**Interfaces:**
- Consumes: `BlockingMode.ALLOW` from Task 1.
- Produces: `GuardrailCheckResult(String text, String unmaskedText, String category)` — a three-component record. Its only consumers are `AiGuardrailsAdvisor` and the two tests above; nothing outside this module reads it.
- Produces: the `guardrail_allowed` metric event.

- [ ] **Step 1: Write the failing tests**

In `AiGuardrailsAdvisorTest`. Follow the file's existing fixture helpers rather than inventing new ones — it already builds an advisor with a stubbed `AiGuardrails`.

```java
    @Test
    void testAllowForwardsTheBlockedTermUnmasked() {
        // Observe mode's entire product: the violation is seen and counted, and the text goes out as it came in.
        // The payload deliberately carries no PII -- BlockingMode governs the three blocking guardrails only, so
        // an email address here would be redacted under ALLOW too and the assertion would pin the wrong thing.
        when(aiGuardrails.resolveBlockingMode(WORKSPACE_ID)).thenReturn(BlockingMode.ALLOW);
        when(aiGuardrails.tokenizeInputs(anyList(), eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of(new GuardrailCheckResult(
                "tell me about [REDACTED_BLOCKED_TERM]", "tell me about acquisitions", "blocked_term")));

        ChatClientRequest advised = adviseRequest("tell me about acquisitions");

        assertThat(promptTextOf(advised)).isEqualTo("tell me about acquisitions");
    }

    @Test
    void testAllowRecordsGuardrailAllowedRatherThanBlockingDowngraded() {
        // Both halves matter. A mode that reports nothing is indistinguishable from the guardrail being off, and
        // reporting it as blocking_downgraded would claim a redaction that did not happen.
        when(aiGuardrails.resolveBlockingMode(WORKSPACE_ID)).thenReturn(BlockingMode.ALLOW);
        when(aiGuardrails.tokenizeInputs(anyList(), eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of(new GuardrailCheckResult(
                "tell me about [REDACTED_BLOCKED_TERM]", "tell me about acquisitions", "blocked_term")));

        adviseRequest("tell me about acquisitions");

        verify(metrics).record("guardrail_allowed");
        verify(metrics, never()).record("blocking_downgraded");
    }

    @Test
    void testAllowDoesNotBlockAPayloadThatBlockWouldHaveRejected() {
        when(aiGuardrails.resolveBlockingMode(WORKSPACE_ID)).thenReturn(BlockingMode.ALLOW);
        when(aiGuardrails.tokenizeInputs(anyList(), eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of(new GuardrailCheckResult(
                "[REDACTED_MODERATED]", "the unmoderated text", "moderation_flagged")));

        assertThatCode(() -> adviseRequest("the unmoderated text")).doesNotThrowAnyException();
    }

    @Test
    void testRedactAndContinueStillForwardsTheMaskedText() {
        // The regression guard for the change: picking unmaskedText unconditionally would silently turn
        // REDACT_AND_CONTINUE into ALLOW for every existing workspace.
        when(aiGuardrails.resolveBlockingMode(WORKSPACE_ID)).thenReturn(BlockingMode.REDACT_AND_CONTINUE);
        when(aiGuardrails.tokenizeInputs(anyList(), eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of(new GuardrailCheckResult(
                "tell me about [REDACTED_BLOCKED_TERM]", "tell me about acquisitions", "blocked_term")));

        ChatClientRequest advised = adviseRequest("tell me about acquisitions");

        assertThat(promptTextOf(advised)).isEqualTo("tell me about [REDACTED_BLOCKED_TERM]");
        verify(metrics).record("blocking_downgraded");
    }
```

In `AiGuardrailsTest`, pin that the engine populates the second component:

```java
    @Test
    void testCheckInputCarriesTheUnmaskedTextAlongsideTheMaskedOne() {
        // The engine stays mode-agnostic: it computes both candidates and the advisor chooses. If this ever
        // returns the same string twice for a blocked term, ALLOW silently stops working.
        AiGuardrails guardrails = guardrailsWithBlockedTerms("acquisitions");

        GuardrailCheckResult result = guardrails.checkInputs(
            List.of("tell me about acquisitions"), 7L, metrics)
            .getFirst();

        assertThat(result.category()).isEqualTo("blocked_term");
        assertThat(result.text()).isEqualTo("tell me about [REDACTED_BLOCKED_TERM]");
        assertThat(result.unmaskedText()).isEqualTo("tell me about acquisitions");
    }
```

Adapt `guardrailsWithBlockedTerms` to whatever the file's existing settings-stub helper is called; do not add a second builder if one exists.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorTest*' --tests '*AiGuardrailsTest*'
```

Expected: compile failure on the three-argument `GuardrailCheckResult` constructor.

- [ ] **Step 3: Give `GuardrailCheckResult` the second text**

In `AiGuardrails`:

```java
    /**
     * @param text         the content to forward, with any blocking-specific transformation already applied — a
     *                     blocked term masked, or the whole message replaced for moderation
     * @param unmaskedText the same content with PII and secret redaction applied but WITHOUT the blocking-specific
     *                     transformation. Equal to {@code text} whenever nothing blocking-specific was applied.
     *                     Exists so a caller running {@code BlockingMode.ALLOW} can forward the content unmodified
     *                     while still learning that the violation happened — this engine stays mode-agnostic and
     *                     computes both candidates rather than resolving the mode itself.
     * @param category     the violation category, or {@code null} when nothing blocking fired
     */
    public record GuardrailCheckResult(
        @Nullable String text, @Nullable String unmaskedText, @Nullable String category) {

        public boolean blocked() {
            return category != null;
        }
    }
```

Update the five construction sites in `checkInput`:

```java
        if (content == null) {
            return new GuardrailCheckResult(null, null, null);
        }

        String redacted = redactPiiAndSecrets(content, policy, recordingMetrics, session);

        String blockedTerm = findBlockedTerm(redacted, policy.blockedTerms());

        if (blockedTerm != null) {
            record(recordingMetrics, "blocked_term");

            return new GuardrailCheckResult(maskBlockedTerm(redacted, blockedTerm), redacted, "blocked_term");
        }

        if (policy.detectInjection() && injectionClassifier != null && injectionClassifier.isInjection(redacted)) {
            record(recordingMetrics, "injection_flagged");

            return new GuardrailCheckResult(redacted, redacted, "injection_flagged");
        }

        if (policy.moderate() && moderationClassifier != null && moderationClassifier.isFlagged(redacted)) {
            record(recordingMetrics, "moderation_flagged");

            return new GuardrailCheckResult(MODERATION_PLACEHOLDER, redacted, "moderation_flagged");
        }

        return new GuardrailCheckResult(redacted, redacted, null);
```

- [ ] **Step 4: Add the `ALLOW` arm in the advisor**

In `AiGuardrailsAdvisor#applyInputGuardrails`, replace the block from `if (anyBlocked && …)` through the loop's `String newText = result.text();`:

```java
        // Resolved only when something is blocked, so an unblocked request costs the same settings lookups it did
        // before ALLOW existed.
        BlockingMode blockingMode = anyBlocked ? aiGuardrails.resolveBlockingMode(workspaceId) : null;

        if (anyBlocked && blockingMode == BlockingMode.BLOCK) {
            String category = results.stream()
                .filter(GuardrailCheckResult::blocked)
                .findFirst()
                .orElseThrow()
                .category();

            throw new AiGuardrailViolationException(category);
        }

        List<Message> patched = new ArrayList<>(instructions);
        boolean changed = false;

        for (int i = 0; i < guardedIndexes.size(); i++) {
            GuardrailCheckResult result = results.get(i);
            boolean allowed = result.blocked() && blockingMode == BlockingMode.ALLOW;

            if (result.blocked()) {
                metrics.record(allowed ? "guardrail_allowed" : "blocking_downgraded");
            }

            int index = guardedIndexes.get(i);
            Message original = instructions.get(index);
            String newText = allowed ? result.unmaskedText() : result.text();
```

Leave the rest of the loop unchanged.

- [ ] **Step 5: Document the event**

In `AiGuardrailMetrics`' class javadoc, add `guardrail_allowed` to the event list, immediately after `injection_flagged`:

```
 * {@code guardrail_allowed} (a blocking violation was detected under {@code BlockingMode.ALLOW} and the content was
 * forwarded unmodified — observe mode's entire product, and the reason {@code ALLOW} is not merely the guardrail
 * switched off),
```

Also extend the advisor's class javadoc line 67, which currently describes only the downgrade arm, to name both.

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorTest*' --tests '*AiGuardrailsTest*'
```

Expected: PASS.

- [ ] **Step 7: Negative-control the arm**

Temporarily change `newText = allowed ? result.unmaskedText() : result.text();` back to `newText = result.text();` and re-run. Expected: `testAllowForwardsTheBlockedTermUnmasked` fails and nothing else does. Restore.

Then temporarily change it to `newText = result.unmaskedText();` unconditionally and re-run. Expected: `testRedactAndContinueStillForwardsTheMaskedText` fails. Restore. Two controls, because one arm of a ternary can be wrong without the other being.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check
git add -A && git commit -m "732 Forward content unmodified and record guardrail_allowed under ALLOW"
```

---

## Task 3: Documentation

**Files:**
- Modify: `.agents/ai-guardrails.md`

- [ ] **Step 1: Add an observe-mode section**

Write it next to the existing `BlockingMode` material. It must carry four things, because each is a question the code does not answer on its own:

1. What `ALLOW` does per category — reproduce the table from this plan's "What `ALLOW` changes" section.
2. That `ALLOW` does **not** disable PII/secret redaction, and why: `BlockingMode` governs the three blocking guardrails only.
3. That `guardrail_allowed` is the entire product — an operator reads that counter for a week, then promotes the mode.
4. The plain warning that under `ALLOW`, moderation-flagged content reaches the model. That is the point of observe mode and it is opt-in, but it must be written down rather than discovered.

- [ ] **Step 2: Commit**

```bash
git add -A && git commit -m "732 Document guardrail observe mode"
```

---

## Task 4: Whole-change verification

- [ ] **Step 1: Compile and check the touched modules**

```bash
./gradlew compileJava compileTestJava --continue
```

- [ ] **Step 2: Check the three guardrail modules**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:check --continue
```

Expected: `EXIT=0`, no `^> Task .* FAILED`.

- [ ] **Step 3: Confirm the gateway path is untouched**

```bash
git diff --stat HEAD~3 -- server/ee/libs/platform/platform-ai/platform-ai-gateway
```

Expected: empty. `applyToInputs` and its HTTP 422 contract must not have moved; if this is non-empty, the mode leaked into the throwing path.

## Self-Review

**Spec coverage.** §2 (`ALLOW` appended, records its metric) → Tasks 1 and 2. §5's first bullet → Task 2, with the location corrected above. §6's `guardrail_allowed` → Task 2 Step 5. §7's first four bullets → Task 2 Step 1; its last three bullets are `ToolPiiAction` tests, deferred with §3 by D4. §8's EE api and EE service rows → Tasks 1 and 2; its CE api/CE service rows are the deferred tool half. §9's "no settings-page UI" → honoured, GraphQL only. D1 → Task 1 Steps 1–4. D2 → Task 2. D4 → the Global Constraints.

**Placeholder scan.** No TBDs. Every code step carries the code. The two places that say "adapt to the file's existing helper" name the helper's role and forbid adding a second one, which is a constraint rather than a gap.

**Type consistency.** `GuardrailCheckResult` is three components — `(text, unmaskedText, category)` — in Task 2's tests, its declaration, and all five construction sites. `blockingMode` is `@Nullable BlockingMode`, null exactly when nothing is blocked, and is only dereferenced under `result.blocked()`.
