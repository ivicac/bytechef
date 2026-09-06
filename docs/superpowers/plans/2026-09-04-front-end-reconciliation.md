# Guardrails Front-End Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the three guardrail front-ends (per-node `check-for-violations`/`sanitize-text`, the EE workspace `AiGuardrailsAdvisor`, and `universal-text` Mask/Unmask) run on the one CE detection engine with an ordering rule that is built into the code rather than left to registration order.

**Architecture:** The workspace advisor becomes outermost by an explicit distinct order value and *publishes* the `SensitiveSpan`s it detected in USER messages into the Spring AI advisor context; the per-node check advisor (one order value inside it) hands those spans to its `pii`/`secret-keys` children, whose input verdict is *published ∪ own* so the strictest verdict wins regardless of position. The `pii` child detects and masks through `SensitiveDataRedactor` (inheriting threshold, context keywords and overlap resolution) while keeping its `<TYPE>` notation through the redactor's replacer seam. `RegexParserUtils.bounded`'s count budget is replaced by the core's `MatchDeadline` everywhere. Mask/Unmask are rebuilt as deterministic actions over `PiiTokenSession`, with no model on either end.

**Tech Stack:** Java 25, Spring AI 2.0.1 (`ChatClientRequest.context()` as the advisor-only channel), JUnit 5 + AssertJ + Mockito, Gradle 9.7, Spotless/Checkstyle/PMD/SpotBugs.

**Spec:** `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` — §6 (revised 2026-09-04, §6.0 "what reading the code changed") and §12 decisions D6, D7, D13–D18 plus D19–D21 added by this plan.

## Global Constraints

- Advisor orders: `AiGuardrailsAdvisor` = `Ordered.HIGHEST_PRECEDENCE`; `CheckForViolationsAdvisor` = `Ordered.HIGHEST_PRECEDENCE + 1`; `SanitizeTextAdvisor` = `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1` (unchanged). Both guardrail constants live in ONE shared CE class, `com.bytechef.platform.ai.guardrails.GuardrailAdvisorOrder` in `platform-ai-api`, and each advisor's own test pins its value (spec D13).
- Published spans are `SensitiveSpan(kind, category, start, end, confidence)` — **never the matched value** (spec §6 "The ordering rule").
- Node input verdict = published spans ∪ the child's own detection over the text it receives; strictest verdict wins (spec D19, added by this plan).
- The node mask notation stays `<TYPE>` (spec D15). `[REDACTED_X]` and `[PII_X_n_nonce]` remain the core's own notations.
- `MatchDeadline` is the single runtime regex bound; `RegexParserUtils` keeps only `compile`, `MAX_EXPRESSION_LENGTH` and the JS-literal syntax (spec D16). Default budget = `SensitiveDataRedactor.DetectionBounds.DEFAULTS.timeout()` (2 s).
- `custom-regex` is NOT put through `CustomPatternValidator` (spec D16). Mask's custom patterns likewise.
- Mask/Unmask keep parameter names (`text`, `sensitiveKeywords`, `piiDetection`, `customRegexPatterns`, `maskMap`) and the `{text, maskMap}` output shape; the five legacy `piiDetection` values are aliases: `EMAIL`→`EMAIL_ADDRESS`, `PHONE`→`PHONE_NUMBER`, `SSN`→`US_SSN`, `CREDIT_CARD` and `IP_ADDRESS` unchanged (spec D6). Secrets never appear in `maskMap` (spec D17). Provider/model properties are dropped.
- `Violation.PatternViolation.matchedSubstrings` is left as is (spec D18); span-derived verdicts use a NEW additive variant, `Violation.SpanViolation` (spec D19).
- `secret-keys` keeps `SecretKeyDetectorUtils` for its own detection (spec D20, added by this plan): the core's `RegexSecretDetector` has only the 11 catalog patterns, while the node utility adds prefixed-token, high-entropy (three permissiveness levels), `KEY=VALUE`, fenced-code-block skipping and obfuscation handling. Porting those into the core is its own sub-project.
- Mask with an empty `piiDetection` selection scans `PiiPatternCatalog.curatedDefault()` (D5a's curated set); secrets are always scanned and redacted (spec D21, added by this plan).
- Conventions from `CLAUDE.md`: blank line before control statements and after a variable modification; no `_`-prefixed methods; test names camelCase without underscores and ending in `Test`; Apache 2.0 header under `server/libs/`, ByteChef Enterprise header + `@version ee` under `server/ee/`; run `./gradlew spotlessApply` before every commit; judge Gradle by `$?` on its own line and `grep '^> Task .* FAILED'` on a redirected log, never a piped exit code; never `git stash`; never amend; commit by path only.
- Java: `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce` prefixed to every `./gradlew` invocation; Bash timeout 600000.
- Do not add code comments in commits unless the surrounding file's density calls for it; rationale goes in the commit message.

---

## Rulings made while planning (spec amendments, applied to §12 in Task 1 Step 5)

| # | Ruling | Why |
|---|---|---|
| D19 | Node input verdict = **published ∪ own**; span-derived verdicts are reported through a new additive `Violation.SpanViolation(guardrail, matchCount, info)` | Publication alone would make `secret-keys` LESS strict under EE than in CE (the core misses entropy tokens the node catches); union keeps "the floor never lowers a node's bar" true for every child without changing `PatternViolation` (D18) |
| D20 | `secret-keys` keeps `SecretKeyDetectorUtils` for detection in this sub-project; it consumes published SECRET spans and switches its bound to `MatchDeadline` | The core's secret detector is a strict subset of the node utility's; consolidating would regress a released surface |
| D21 | Mask with no `piiDetection` selection scans the curated default; secrets are always scanned and redacted | Today's model prompt redacts "sensitive information" generically; the deterministic rebuild must not turn an empty selection into "mask nothing" |

---

## File Structure

**Created**
- `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrder.java` — the two order constants.
- `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/PublishedInputSpans.java` — the advisor-context key and the typed reader.
- `server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrderTest.java`, `PublishedInputSpansTest.java`.
- `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/PiiPatternLabels.java` — one label table for the picker, shared by the node picker and Mask.
- `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiEntityOptions.java` — the node picker options and selection (what survives of `PiiDetectorUtils`).
- `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/GuardrailMatchDeadline.java` — one place that starts a `MatchDeadline` with the shared default budget.
- `server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/NodeCheckJudgesPublishedSpansTest.java` — end-to-end ordering proof through a real `ChatClient`.
- `server/libs/modules/components/ai/universal/universal-text/src/main/java/com/bytechef/component/ai/universal/text/util/MaskSpans.java` — keyword and custom-pattern spans for Mask.
- `server/libs/modules/components/ai/universal/universal-text/src/test/java/com/bytechef/component/ai/universal/text/action/MaskActionTest.java`, `UnmaskActionTest.java`.

**Modified**
- `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/.../advisor/AiGuardrailsAdvisor.java` — order constant; publishes USER-message spans.
- `server/libs/ai/ai-copilot/ai-copilot-service/.../advisor/DeferredGuardrailsAdvisor.java` — order constant.
- `server/libs/modules/components/ai/agent/guardrails/.../advisor/CheckForViolationsAdvisor.java` — order constant; threads published spans into `GuardrailContext`; `SpanViolation` arms.
- `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java` — registration comment and guard message.
- `server/libs/platform/platform-component/platform-component-api/.../guardrails/GuardrailContext.java`, `Violation.java` — published spans; `SpanViolation`.
- `server/libs/platform/platform-component/platform-component-api/build.gradle.kts` — `api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))`.
- `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/.../RegexPiiDetector.java` (pattern-list constructor), `SensitiveDataRedactor.java` (replacer overload), `tokenization/PiiTokenSession.java` (`tokens()`).
- `server/libs/modules/components/ai/agent/guardrails/pii/.../cluster/Pii.java` — rebuilt on the core.
- `server/libs/modules/components/ai/agent/guardrails/secret-keys/.../cluster/SecretKeys.java` — published-span union.
- `server/libs/modules/components/ai/agent/guardrails/llm-pii/.../cluster/LlmPii.java` — picker import; `MatchDeadline`.
- `server/libs/modules/components/ai/agent/guardrails/custom-regex/.../cluster/CustomRegex.java` — `MatchDeadline`.
- `server/libs/modules/components/ai/agent/guardrails/.../util/{RegexParserUtils,SecretKeyDetectorUtils,KeywordMatcherUtils,MaskEntityMapUtils}.java` — `MatchDeadline`.
- `server/libs/modules/components/ai/agent/guardrails/build.gradle.kts` — `platform-ai-api` dependency; `pii` test dependency.
- `server/libs/modules/components/ai/universal/universal-text/.../action/{MaskAction,UnmaskAction}.java`, `AiTextComponentHandler.java`, `build.gradle.kts`, `src/test/resources/definition/ai-text_v1.json`.
- `.agents/ai-guardrails.md`, `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md`.

**Deleted**
- `server/libs/modules/components/ai/agent/guardrails/.../util/PiiDetectorUtils.java` and its tests `PiiDetectorUtilsTest`, `PiiDetectorUtilsParityTest`, `PiiDetectorUtilsCustomRegexTest`; the PII case of `DetectorConcurrencyTest`.

---

### Task 1: Shared order constants and the published-spans channel

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrder.java`
- Create: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/PublishedInputSpans.java`
- Test: `server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrderTest.java`
- Test: `server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/PublishedInputSpansTest.java`

**Interfaces:**
- Consumes: `org.springframework.core.Ordered` (spring-core, already on `platform-ai-api`'s classpath through `spring-ai-client-chat`), `com.bytechef.platform.ai.sensitivedata.SensitiveSpan` (`platform-ai-sensitive-data-api` is already an `api` dependency of `platform-ai-api`).
- Produces: `GuardrailAdvisorOrder.WORKSPACE_FLOOR` (int), `GuardrailAdvisorOrder.NODE_CHECK` (int); `PublishedInputSpans.CONTEXT_KEY` (String), `static List<SensitiveSpan> PublishedInputSpans.from(Map<String, ?> context)`.

- [ ] **Step 1: Write the failing tests**

`GuardrailAdvisorOrderTest.java`:

```java
package com.bytechef.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/**
 * @author Ivica Cardic
 */
class GuardrailAdvisorOrderTest {

    @Test
    void testTheWorkspaceFloorIsHighestPrecedence() {
        assertThat(GuardrailAdvisorOrder.WORKSPACE_FLOOR).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void testTheNodeCheckSitsExactlyOneInsideTheFloor() {
        // Distinct, adjacent values: nothing can register between the floor and the node check, and nothing can
        // tie either of them. Spring AI breaks a tie toward the LATER registration, which is what put the node
        // check outermost before these constants existed.
        assertThat(GuardrailAdvisorOrder.NODE_CHECK).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(GuardrailAdvisorOrder.NODE_CHECK).isGreaterThan(GuardrailAdvisorOrder.WORKSPACE_FLOOR);
    }
}
```

`PublishedInputSpansTest.java`:

```java
package com.bytechef.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PublishedInputSpansTest {

    @Test
    void testReadsBackWhatWasPublishedUnderTheKey() {
        SensitiveSpan span = SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 3, 14);
        Map<String, Object> context = new HashMap<>();

        context.put(PublishedInputSpans.CONTEXT_KEY, List.of(span));

        assertThat(PublishedInputSpans.from(context)).containsExactly(span);
    }

    @Test
    void testIsEmptyWhenNothingWasPublished() {
        assertThat(PublishedInputSpans.from(Map.of())).isEmpty();
    }

    @Test
    void testIgnoresAForeignValueUnderTheKeyRatherThanThrowing() {
        // The context map is shared by every advisor in the chain; a wrong-typed value there is a bug elsewhere,
        // and a guardrail must fail towards "nothing published" rather than take the whole request down on a cast.
        Map<String, Object> context = new HashMap<>();

        context.put(PublishedInputSpans.CONTEXT_KEY, "not a list");

        assertThat(PublishedInputSpans.from(context)).isEmpty();

        context.put(PublishedInputSpans.CONTEXT_KEY, List.of("not a span"));

        assertThat(PublishedInputSpans.from(context)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-api:test --tests '*GuardrailAdvisorOrderTest*' --tests '*PublishedInputSpansTest*' --console=plain > /tmp/task1-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, compilation error `cannot find symbol ... GuardrailAdvisorOrder`.

- [ ] **Step 3: Write the two classes**

`GuardrailAdvisorOrder.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.guardrails;

import org.springframework.core.Ordered;

/**
 * The chain positions of the two guardrail advisors, fixed here so they can never tie.
 *
 * <p>
 * Spring AI breaks an {@code Ordered} tie toward the advisor registered LAST ({@code DefaultAroundAdvisorChain}
 * pushes with {@code Deque#push} and then stable-sorts), which is the inverse of what a reader assumes. Before these
 * constants existed the per-node check advisor tied the workspace advisor at {@code HIGHEST_PRECEDENCE} and, being
 * registered later, wrapped it — so a node-level output check saw the caller's restored values and blocked responses
 * that merely echoed the caller's own input. Two distinct values make the position a property of the code rather
 * than of registration order.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class GuardrailAdvisorOrder {

    /**
     * The workspace floor ({@code AiGuardrailsAdvisor}, and the deferred stand-in Copilot registers): outermost, so it
     * sees the caller's original request before any other advisor rewrites it and restores its tokens as the last
     * act on the response.
     */
    public static final int WORKSPACE_FLOOR = Ordered.HIGHEST_PRECEDENCE;

    /**
     * The per-node {@code CheckForViolationsAdvisor}: exactly one inside the floor, so on the response it judges only
     * what the model contributed — the floor's tokens are invisible to it and are restored after it ran.
     */
    public static final int NODE_CHECK = Ordered.HIGHEST_PRECEDENCE + 1;

    private GuardrailAdvisorOrder() {
    }
}
```

`PublishedInputSpans.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.guardrails;

import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The advisor-context channel through which the workspace floor tells the per-node check what it detected in the
 * caller's USER messages, BEFORE it tokenized, redacted, allowed or blocked them.
 *
 * <p>
 * This is what makes the node's input verdict independent of chain position: the floor runs first and transforms
 * the text, so a node check that only looked at the text it receives would see tokens and never fire. Reading the
 * floor's own detection instead lets a node say "block PII" and have it hold even when the workspace chose to let
 * PII through tokenized. The published spans carry kind, category, offsets and confidence — never the matched value.
 * </p>
 *
 * <p>
 * {@code ChatClientRequest#context()} is an advisor-only map: {@code DefaultChatClientUtils#toChatClientRequest} keeps
 * it separate from the {@code ToolContext} that tool callbacks see, so nothing published here reaches a tool.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PublishedInputSpans {

    public static final String CONTEXT_KEY = "bytechef.guardrails.publishedInputSpans";

    private PublishedInputSpans() {
    }

    /**
     * Returns the spans published under {@link #CONTEXT_KEY}, or an empty list when nothing was published or the value
     * is not a list of spans — a guardrail fails towards "nothing published", never towards a cast failure on the
     * request thread.
     *
     * @param context the advisor context map
     * @return the published spans, never {@code null}
     */
    public static List<SensitiveSpan> from(Map<String, ?> context) {
        Object value = context.get(CONTEXT_KEY);

        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>(list.size());

        for (Object element : list) {
            if (!(element instanceof SensitiveSpan span)) {
                return List.of();
            }

            spans.add(span);
        }

        return List.copyOf(spans);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the Step 2 command with `/tmp/task1-green.log`. Expected: `exit=0`; `grep -c '^> Task .* FAILED' /tmp/task1-green.log` prints `0`.

- [ ] **Step 5: Amend the spec with the three rulings**

Append the three rows below to the `## 12. Decisions` table of `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md`, directly after the `D18` row (find `| D18 |`):

```markdown
| D19 | Node input verdict = published ∪ own detection; span-derived verdicts reported through a new additive `Violation.SpanViolation` | Publication alone would make `secret-keys` less strict under EE than in CE, since the core's secret detector is a strict subset of the node's. Union keeps the floor from lowering any node's bar and leaves `PatternViolation` untouched (D18) |
| D20 | `secret-keys` keeps `SecretKeyDetectorUtils` for detection in sub-project 2; it consumes published SECRET spans and matches under `MatchDeadline` | The core has the 11 catalog patterns only; the node utility adds prefixed-token, high-entropy (three permissiveness levels), `KEY=VALUE`, fenced-code-block skipping and obfuscation handling. Porting them is its own sub-project |
| D21 | Mask with an empty `piiDetection` selection scans the curated default; secrets are always scanned and redacted | The model prompt Mask replaces redacted "sensitive information" generically; an empty selection must not become "mask nothing" |
```

And in `### The node front-end on the shared pipeline`, replace the first bullet's opening sentence

```
- `pii` and `secret-keys` children call the core's detection (`SensitiveDataRedactor` detect → resolve) and
  the core's offset replacement for masking. `PiiDetectorUtils`, `SecretKeyDetectorUtils`,
  `MaskEntityMapUtils` and `PreflightMasking` are deleted once unreferenced.
```

with

```
- The `pii` child calls the core's detection (`SensitiveDataRedactor` detect → resolve) and the core's offset
  replacement for masking; `PiiDetectorUtils` is deleted. `secret-keys` keeps its own detector for now (D20) but
  judges published spans too (D19). `MaskEntityMapUtils` and `PreflightMasking` stay while `keywords`, `llm-pii`
  and the advisors still reference them.
```

- [ ] **Step 6: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-api:spotlessApply --console=plain > /tmp/task1-fmt.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrder.java server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/PublishedInputSpans.java server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/GuardrailAdvisorOrderTest.java server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/PublishedInputSpansTest.java docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md
git commit -m "732 Give the two guardrail advisors distinct, shared order constants and a span channel

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Adopt the constants in every advisor and the registration site

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java:182-185` (`getOrder`)
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/advisor/DeferredGuardrailsAdvisor.java:89-92` (`getOrder`)
- Modify: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisor.java:106-109` (`getOrder`)
- Modify: `server/libs/modules/components/ai/agent/guardrails/build.gradle.kts` (add `implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))`)
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java:268-272` (comment) and `:813-817` (guard message)
- Test: `server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisorTest.java` (existing `getOrder` assertion around line 69–88 — update to the constant)
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorTest.java` (existing `getOrder` assertion — update to the constant)

**Interfaces:**
- Consumes: `GuardrailAdvisorOrder.WORKSPACE_FLOOR`, `GuardrailAdvisorOrder.NODE_CHECK` (Task 1).
- Produces: nothing new; every `getOrder()` returns a shared constant.

- [ ] **Step 1: Update the existing order assertions to fail first**

In `CheckForViolationsAdvisorTest`, find the assertion on `getOrder()` (grep `getOrder` in the file) and change it to:

```java
assertThat(advisor.getOrder())
    .as("the node check sits exactly one inside the workspace floor; see GuardrailAdvisorOrder")
    .isEqualTo(GuardrailAdvisorOrder.NODE_CHECK);
```

with `import com.bytechef.platform.ai.guardrails.GuardrailAdvisorOrder;`. In `AiGuardrailsAdvisorTest` change its `getOrder` assertion to `.isEqualTo(GuardrailAdvisorOrder.WORKSPACE_FLOOR)`. Add to `DeferredGuardrailsAdvisor`'s test class (`server/libs/ai/ai-copilot/ai-copilot-service/src/test/java/com/bytechef/ai/copilot/advisor/CopilotGuardrailsAdvisorFactoryTest.java`, or a new `DeferredGuardrailsAdvisorTest` if that class does not construct the advisor directly):

```java
@Test
void testTheDeferredStandInHoldsTheFloorsPosition() {
    assertThat(new DeferredGuardrailsAdvisor(() -> Optional.empty()).getOrder())
        .isEqualTo(GuardrailAdvisorOrder.WORKSPACE_FLOOR);
}
```

(Match the constructor `DeferredGuardrailsAdvisor` actually has — read its file first; the assertion is the point, not the construction.)

- [ ] **Step 2: Run to verify the node test fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:test --tests '*CheckForViolationsAdvisorTest*' --console=plain > /tmp/task2-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` (compile error on the missing dependency, or the assertion failing with `-2147483648` vs `-2147483647`).

- [ ] **Step 3: Add the dependency and switch the three `getOrder` bodies**

`server/libs/modules/components/ai/agent/guardrails/build.gradle.kts` — add inside `dependencies {` after the `caffeine` line:

```kotlin
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
```

`CheckForViolationsAdvisor.getOrder()`:

```java
    @Override
    public int getOrder() {
        return GuardrailAdvisorOrder.NODE_CHECK;
    }
```

`AiGuardrailsAdvisor.getOrder()` and `DeferredGuardrailsAdvisor.getOrder()`:

```java
    @Override
    public int getOrder() {
        return GuardrailAdvisorOrder.WORKSPACE_FLOOR;
    }
```

Add `import com.bytechef.platform.ai.guardrails.GuardrailAdvisorOrder;` to each; remove the now-unused `HIGHEST_PRECEDENCE` static import where one exists. In `AiGuardrailsAdvisor`'s class javadoc, the "Order" paragraph that says it runs BEFORE per-node elements stays true now — leave it.

- [ ] **Step 4: Fix the registration comment and the guard message**

`AbstractAiAgentChatAction.java` lines 268–272, replace the comment with:

```java
        // Workspace-bound content guardrails, resolved through the optional CE SPI so this component never depends on
        // the EE guardrails module directly (see AiGuardrailsAdvisorProvider). Its position is GuardrailAdvisorOrder
        // .WORKSPACE_FLOOR, one outside the per-node check at GuardrailAdvisorOrder.NODE_CHECK; registration order is
        // NOT what puts it first -- Spring AI breaks an order tie toward the LAST registration, which is why the two
        // advisors carry distinct constants rather than both declaring HIGHEST_PRECEDENCE.
```

Lines 813–817, the first `IllegalStateException` message becomes:

```java
            throw new IllegalStateException(
                "Multiple CheckForViolations parent cluster elements configured — they would tie at " +
                    "GuardrailAdvisorOrder.NODE_CHECK and Spring AI would run the last-registered one outermost. " +
                    "Configure at most one.");
```

- [ ] **Step 5: Run the three modules' tests**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:test :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test :server:libs:ai:ai-copilot:ai-copilot-service:test :server:libs:modules:components:ai:agent:compileJava --continue --console=plain > /tmp/task2-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task2-green.log
```
Expected: `exit=0`, no FAILED lines. `SpringAiTiedAdvisorOrderTest` still passes (it pins the framework, not our constants).

- [ ] **Step 6: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew spotlessApply --console=plain > /tmp/task2-fmt.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorTest.java server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/advisor/DeferredGuardrailsAdvisor.java server/libs/ai/ai-copilot/ai-copilot-service/src/test server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisor.java server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisorTest.java server/libs/modules/components/ai/agent/guardrails/build.gradle.kts server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java
git commit -m "732 Put the workspace floor outermost by order value, not by registration

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The floor publishes the spans it detected in USER messages

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java:258-345` (`applyInputGuardrails`)
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorPublishesSpansTest.java` (new)

**Interfaces:**
- Consumes: `PublishedInputSpans.CONTEXT_KEY`/`from` (Task 1); `AiGuardrails.GuardrailCheckResult.spans()` (existing, `List<SensitiveSpan>`).
- Produces: after `applyInputGuardrails`, `PublishedInputSpans.from(request.context())` returns the union of `spans()` over results whose guarded message is `MessageType.USER`, when that union is non-empty.

- [ ] **Step 1: Write the failing test**

Read `AiGuardrailsAdvisorTest` first and copy its construction of `AiGuardrailsAdvisor` (it builds an `AiGuardrails` stub/mock and metrics); reuse the same helpers. The test:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.AiGuardrails;
import com.bytechef.ee.platform.ai.guardrails.AiGuardrails.GuardrailCheckResult;
import com.bytechef.platform.ai.guardrails.PublishedInputSpans;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailsAdvisorPublishesSpansTest {

    private static final SensitiveSpan USER_SPAN = SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 11, 22);
    private static final SensitiveSpan SYSTEM_SPAN = SensitiveSpan.of(SensitiveKind.PII, "PHONE_NUMBER", 0, 12);

    @Test
    void testPublishesTheSpansItDetectedInUserMessagesOnly() {
        AiGuardrails aiGuardrails = mock(AiGuardrails.class);

        when(aiGuardrails.newTokenSession()).thenReturn(PiiTokenSession.create());
        // Two guarded messages, SYSTEM first then USER, in the order applyInputGuardrails collects them.
        when(aiGuardrails.tokenizeInputs(anyList(), any(), any(), any()))
            .thenReturn(List.of(
                new GuardrailCheckResult("555-123-4567", null, null, List.of(SYSTEM_SPAN)),
                new GuardrailCheckResult("contact me [PII_EMAIL_ADDRESS_1_abcd]", null, null, List.of(USER_SPAN))));

        AiGuardrailsAdvisor advisor = AiGuardrailsAdvisorTest.advisorOver(aiGuardrails);

        AtomicReference<ChatClientRequest> seenByTheChain = new AtomicReference<>();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenAnswer(invocation -> {
            seenByTheChain.set(invocation.getArgument(0));

            return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))))
                .context(invocation.<ChatClientRequest>getArgument(0)
                    .context())
                .build();
        });

        advisor.adviseCall(
            ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new SystemMessage("555-123-4567"), new UserMessage("contact me a@b.com"))))
                .build(),
            chain);

        assertThat(PublishedInputSpans.from(seenByTheChain.get()
            .context()))
                .as("SYSTEM spans are the operator's, not the caller's; only USER spans are published")
                .containsExactly(USER_SPAN);
    }

    @Test
    void testPublishesNothingWhenTheFloorDetectedNothing() {
        AiGuardrails aiGuardrails = mock(AiGuardrails.class);

        when(aiGuardrails.newTokenSession()).thenReturn(PiiTokenSession.create());
        when(aiGuardrails.tokenizeInputs(anyList(), any(), any(), any()))
            .thenReturn(List.of(new GuardrailCheckResult("hello", null, null, List.of())));

        AiGuardrailsAdvisor advisor = AiGuardrailsAdvisorTest.advisorOver(aiGuardrails);

        AtomicReference<ChatClientRequest> seenByTheChain = new AtomicReference<>();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenAnswer(invocation -> {
            seenByTheChain.set(invocation.getArgument(0));

            return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))))
                .build();
        });

        advisor.adviseCall(
            ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("hello"))))
                .build(),
            chain);

        assertThat(seenByTheChain.get()
            .context()).doesNotContainKey(PublishedInputSpans.CONTEXT_KEY);
    }
}
```

`AiGuardrailsAdvisorTest.advisorOver(AiGuardrails)` is a package-private static factory to ADD to the existing test class, wrapping however that class already constructs the advisor (a `workspaceId` of `null`/`1L`, an `AiGuardrailMetrics` built the way that class builds one, and `GuardrailSurface.AI_AGENT`). If the existing test constructs it inline, extract that expression into the factory and use the factory there too.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorPublishesSpansTest*' --console=plain > /tmp/task3-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`; the first test fails with an empty list where `USER_SPAN` was expected.

- [ ] **Step 3: Publish in `applyInputGuardrails`**

In `AiGuardrailsAdvisor.applyInputGuardrails`, after `submitViolationRecords(results, blockingMode, workspaceId);` (the non-BLOCK path) and before `List<Message> patched = new ArrayList<>(instructions);`, add:

```java
        List<SensitiveSpan> publishedSpans = userMessageSpans(instructions, guardedIndexes, results);
```

Replace the tail of the method — from `if (!changed) {` to the end — with:

```java
        ChatClientRequest.Builder builder = chatClientRequest.mutate();

        if (changed) {
            builder.prompt(new Prompt(patched, prompt.getOptions()));
        }

        if (!publishedSpans.isEmpty()) {
            builder.context(PublishedInputSpans.CONTEXT_KEY, publishedSpans);
        }

        if (!changed && publishedSpans.isEmpty()) {
            return chatClientRequest;
        }

        return builder.build();
```

Add the helper at the END of the class (after the last method, to avoid the stacked-javadoc Checkstyle error):

```java
    /**
     * The spans the floor detected in the caller's USER messages, for {@link PublishedInputSpans}. SYSTEM messages
     * are guarded too, but their spans are the operator's prompt, not the caller's input, and a per-node input check
     * judges the latter.
     */
    private static List<SensitiveSpan> userMessageSpans(
        List<Message> instructions, List<Integer> guardedIndexes, List<GuardrailCheckResult> results) {

        List<SensitiveSpan> spans = new ArrayList<>();

        for (int i = 0; i < guardedIndexes.size(); i++) {
            Message message = instructions.get(guardedIndexes.get(i));

            if (message.getMessageType() != MessageType.USER) {
                continue;
            }

            spans.addAll(results.get(i)
                .spans());
        }

        return List.copyOf(spans);
    }
```

Imports: `com.bytechef.platform.ai.guardrails.PublishedInputSpans`, `com.bytechef.platform.ai.sensitivedata.SensitiveSpan` (check which are already present).

- [ ] **Step 4: Run the module tests**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --console=plain > /tmp/task3-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task3-green.log
```
Expected: `exit=0`, no FAILED lines (the existing "unchanged request is returned as is" assertions still hold when nothing was detected).

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:spotlessApply --console=plain > /tmp/task3-fmt.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorPublishesSpansTest.java server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorTest.java
git commit -m "732 Publish the spans the workspace floor detected in USER messages

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Thread published spans to the node children; `Violation.SpanViolation`

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/build.gradle.kts`
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/guardrails/GuardrailContext.java`
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/guardrails/Violation.java`
- Modify: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisor.java:192-235` (`runChecks`), `:330-340` (`logViolations` switch), `:428-452` (`toPublicView`)
- Test: `server/libs/platform/platform-component/platform-component-api/src/test/java/com/bytechef/platform/component/definition/ai/agent/guardrails/ViolationTest.java` (new)
- Test: `server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisorPublishedSpansTest.java` (new)

**Interfaces:**
- Consumes: `PublishedInputSpans.from` (Task 1).
- Produces: `GuardrailContext.publishedInputSpans()` → `List<SensitiveSpan>`; `GuardrailContext withPublishedInputSpans(List<SensitiveSpan>)`; `Violation.ofSpans(String guardrail, int matchCount, Map<String, ? extends Serializable> info)` → `Violation.SpanViolation(String guardrail, int matchCount, Map<String, Serializable> info)`.

- [ ] **Step 1: Write the failing tests**

`ViolationTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.component.definition.ai.agent.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ViolationTest {

    @Test
    void testSpanViolationCarriesACountAndInfoButNoValues() {
        Violation violation = Violation.ofSpans(
            "piiCheck", 2, Map.of("entityTypes", new ArrayList<>(List.of("EMAIL_ADDRESS"))));

        assertThat(violation).isInstanceOf(Violation.SpanViolation.class);
        assertThat(((Violation.SpanViolation) violation).matchCount()).isEqualTo(2);
        assertThat(violation.info()).containsKey("entityTypes");
    }

    @Test
    void testSpanViolationRejectsAZeroCount() {
        // A violation with nothing behind it is not a violation; the factory refuses it the way ofMatches refuses
        // an empty substring list.
        assertThatThrownBy(() -> Violation.ofSpans("piiCheck", 0, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`CheckForViolationsAdvisorPublishedSpansTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.ai.agent.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Context;
import com.bytechef.platform.ai.guardrails.PublishedInputSpans;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.ai.agent.guardrails.GuardrailContext;
import com.bytechef.platform.component.definition.ai.agent.guardrails.PreflightCheckFunction;
import com.bytechef.platform.component.definition.ai.agent.guardrails.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * @author Ivica Cardic
 */
class CheckForViolationsAdvisorPublishedSpansTest {

    private static final SensitiveSpan PUBLISHED = SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 0, 7);

    @Test
    void testPreflightInputChecksReceiveThePublishedSpans() {
        AtomicReference<List<SensitiveSpan>> seenOnInput = new AtomicReference<>();
        AtomicReference<List<SensitiveSpan>> seenOnOutput = new AtomicReference<>();

        PreflightCheckFunction recording = (text, context) -> {
            if ("[PII_EMAIL_ADDRESS_1_abcd]".equals(text)) {
                seenOnInput.set(context.publishedInputSpans());
            } else {
                seenOnOutput.set(context.publishedInputSpans());
            }

            return Optional.empty();
        };

        CheckForViolationsAdvisor advisor = CheckForViolationsAdvisor.builder()
            .context(mock(Context.class))
            .add("recording", recording, ParametersFactory.create(Map.of("validateOutput", true)),
                ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()))
            .build();

        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(any())).thenReturn(
            ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("model text")))))
                .build());

        advisor.adviseCall(
            ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("[PII_EMAIL_ADDRESS_1_abcd]"))))
                .context(PublishedInputSpans.CONTEXT_KEY, List.of(PUBLISHED))
                .build(),
            chain);

        assertThat(seenOnInput.get()).containsExactly(PUBLISHED);
        assertThat(seenOnOutput.get())
            .as("published spans describe the caller's input; an output check judges the model's contribution only")
            .isEmpty();
    }

    @Test
    void testASpanViolationBlocksAndReportsItsCountInThePublicView() {
        PreflightCheckFunction fromSpans = (text, context) -> context.publishedInputSpans()
            .isEmpty()
                ? Optional.empty()
                : Optional.of(Violation.ofSpans("piiCheck", context.publishedInputSpans()
                    .size(), Map.of()));

        CheckForViolationsAdvisor advisor = CheckForViolationsAdvisor.builder()
            .context(mock(Context.class))
            .add("piiCheck", fromSpans, ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()),
                ParametersFactory.create(Map.of()))
            .build();

        ChatClientResponse response = advisor.adviseCall(
            ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("[PII_EMAIL_ADDRESS_1_abcd]"))))
                .context(PublishedInputSpans.CONTEXT_KEY, List.of(PUBLISHED))
                .build(),
            mock(CallAdvisorChain.class));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) response.chatResponse()
            .getMetadata()
            .get(CheckForViolationsAdvisor.VIOLATIONS_METADATA_KEY);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).containsEntry("guardrail", "piiCheck")
            .containsEntry("matchCount", 1);
    }
}
```

If `VIOLATIONS_METADATA_KEY` is private today, make it `static final` package-visible (other tests in this package already read the metadata — reuse whatever key constant they use).

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-component:platform-component-api:test --tests '*ViolationTest*' :server:libs:modules:components:ai:agent:guardrails:test --tests '*CheckForViolationsAdvisorPublishedSpansTest*' --continue --console=plain > /tmp/task4-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, compile errors on `ofSpans`/`publishedInputSpans`.

- [ ] **Step 3: Add the dependency, the context field and the variant**

`platform-component-api/build.gradle.kts` — add after the `exception-api` line:

```kotlin
    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))
```

`GuardrailContext.java` — add a field `private final List<SensitiveSpan> publishedInputSpans;`, extend the private constructor with a trailing `List<SensitiveSpan> publishedInputSpans` parameter (`this.publishedInputSpans = publishedInputSpans == null ? List.of() : List.copyOf(publishedInputSpans);`), have the public constructor and `withConversationHistoryMessages` pass `List.of()` / the current value respectively, and add at the end of the accessor block:

```java
    /**
     * The spans the workspace floor detected in the caller's USER messages before it transformed them, or empty when
     * no floor ran (CE, or guardrails off) or this context is for an output check. A child's input verdict unions
     * these with its own detection so the strictest verdict wins regardless of chain position.
     */
    public List<SensitiveSpan> publishedInputSpans() {
        return publishedInputSpans;
    }

    public GuardrailContext withPublishedInputSpans(List<SensitiveSpan> publishedInputSpans) {
        return new GuardrailContext(inputParameters, connectionParameters, parentParameters, extensions,
            componentConnections, chatClient, context, conversationHistoryMessages, publishedInputSpans);
    }
```

`Violation.java` — add `Violation.SpanViolation` to the `permits` list and:

```java
    static Violation ofSpans(String guardrail, int matchCount, Map<String, ? extends Serializable> info) {
        return new SpanViolation(guardrail, matchCount, copyInfo(info));
    }
```

and the record, after `PatternViolation`:

```java
    /**
     * A verdict derived from spans rather than matched text — the workspace floor's published detection, unioned
     * with a child's own. Carries a count only: a span locates a match without reproducing it, and the public view
     * of a {@link PatternViolation} never exposed more than a count either.
     */
    @SuppressFBWarnings({
        "EI_EXPOSE_REP", "EI_EXPOSE_REP2"
    })
    record SpanViolation(String guardrail, int matchCount, Map<String, Serializable> info) implements Violation {

        public SpanViolation {
            if (guardrail == null || guardrail.isBlank()) {
                throw new IllegalArgumentException("guardrail must be non-blank");
            }

            if (matchCount < 1) {
                throw new IllegalArgumentException("matchCount must be positive, got " + matchCount);
            }

            info = info == null ? Map.of() : Map.copyOf(info);
        }
    }
```

- [ ] **Step 4: Thread the spans and handle the variant in the advisor**

In `CheckForViolationsAdvisor.runChecks`, before the PREFLIGHT loop:

```java
        List<SensitiveSpan> publishedInputSpans = PublishedInputSpans.from(request.context());
```

and inside the loop, replace `entry.function.applyAll(textForLlm, entry.context)` and the `masking.mask(textForLlm, entry.context)` call with a per-entry context:

```java
                GuardrailContext inputContext = publishedInputSpans.isEmpty()
                    ? entry.context
                    : entry.context.withPublishedInputSpans(publishedInputSpans);

                List<Violation> results = entry.function.applyAll(textForLlm, inputContext);
```

(and `masking.mask(textForLlm, inputContext)`). `runOutputChecks` is untouched — output checks never get published spans.

In `logViolations`' switch (around line 334) add `case Violation.SpanViolation ignored -> "-";`. In `toPublicView` change the `matchCount` switch to:

```java
        int matchCount = switch (violation) {
            case Violation.PatternViolation pattern -> {
                List<String> matchedSubstrings = pattern.matchedSubstrings();

                yield matchedSubstrings.size();
            }
            case Violation.SpanViolation spans -> spans.matchCount();
            case Violation.ClassifiedViolation ignored -> 0;
            case Violation.ExecutionFailureViolation ignored -> 0;
        };
```

and add `case Violation.SpanViolation ignored -> { }` to the second switch (the one with `failureKind`/`confidenceScore`). Grep the module's `src/main` for any other `switch (violation)` and add the arm. Imports: `PublishedInputSpans`, `SensitiveSpan`.

- [ ] **Step 5: Run the two modules' tests**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-component:platform-component-api:test :server:libs:modules:components:ai:agent:guardrails:test --continue --console=plain > /tmp/task4-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task4-green.log
```
Expected: `exit=0`, no FAILED lines.

- [ ] **Step 6: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew spotlessApply --console=plain > /tmp/task4-fmt.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-component/platform-component-api/build.gradle.kts server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/guardrails/GuardrailContext.java server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/guardrails/Violation.java server/libs/platform/platform-component/platform-component-api/src/test/java/com/bytechef/platform/component/definition/ai/agent/guardrails/ViolationTest.java server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisor.java server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/CheckForViolationsAdvisorPublishedSpansTest.java
git commit -m "732 Hand the floor's published spans to per-node input checks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: The `pii` child on the shared engine

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/RegexPiiDetector.java:60-63` (constructors)
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/SensitiveDataRedactor.java:366-395` (replacer overload)
- Create: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiEntityOptions.java`
- Rewrite: `server/libs/modules/components/ai/agent/guardrails/pii/src/main/java/com/bytechef/component/ai/agent/guardrails/pii/cluster/Pii.java`
- Modify: `server/libs/modules/components/ai/agent/guardrails/llm-pii/src/main/java/com/bytechef/component/ai/agent/guardrails/llmpii/cluster/LlmPii.java:30,125` (`PiiDetectorUtils.getPiiDetectionOptions()` → `PiiEntityOptions.getPiiDetectionOptions()`)
- Delete: `.../guardrails/util/PiiDetectorUtils.java`, `.../guardrails/src/test/.../util/PiiDetectorUtilsTest.java`, `PiiDetectorUtilsParityTest.java`, `PiiDetectorUtilsCustomRegexTest.java`; remove `testPiiDetectorIsThreadSafeAcrossConcurrentInvocations` from `DetectorConcurrencyTest.java`
- Test: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/SensitiveDataRedactorReplacerTest.java` (new)
- Test: `server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/util/PiiEntityOptionsTest.java` (new; ports `testPickerOptionsMatchCatalogTypesExactly`)
- Test: `server/libs/modules/components/ai/agent/guardrails/pii/src/test/java/com/bytechef/component/ai/agent/guardrails/pii/cluster/PiiTest.java` (update)

**Interfaces:**
- Consumes: `GuardrailContext.publishedInputSpans()`, `Violation.ofSpans` (Task 4); `PiiPatternCatalog.ALL/curatedDefault()/filterByTypes(List<String>)`, `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE`, `RedactionResult(text, accepted)`.
- Produces: `public RegexPiiDetector(List<PiiPatternCatalog.PiiPattern> patterns)`; `public RedactionResult SensitiveDataRedactor.redactWithSpans(String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics, List<SensitiveSpan> extraCandidates, Function<SensitiveSpan, String> replacer)`; `PiiEntityOptions.getPiiDetectionOptions()` (`List<Option<String>>`), `PiiEntityOptions.selectedPatterns(Parameters)` (`List<PiiPatternCatalog.PiiPattern>`).

- [ ] **Step 1: Write the failing core test**

`SensitiveDataRedactorReplacerTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 * (Apache 2.0 header as in the sibling tests)
 */

package com.bytechef.platform.ai.sensitivedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class SensitiveDataRedactorReplacerTest {

    @Test
    void testACallerSuppliedReplacerRendersTheAcceptedSpans() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(List.of(new RegexPiiDetector()));

        SensitiveDataRedactor.RedactionResult result = redactor.redactWithSpans(
            "mail bob@acme.io now", Set.of(SensitiveKind.PII), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, null,
            List.of(), span -> "<" + span.category() + ">");

        assertThat(result.text()).isEqualTo("mail <EMAIL_ADDRESS> now");
        assertThat(result.accepted()).extracting(SensitiveSpan::category)
            .containsExactly("EMAIL_ADDRESS");
    }

    @Test
    void testAPatternListConstructorRestrictsWhatTheDetectorSees() {
        // Selection happens at the CANDIDATE level, so an unselected type cannot win an overlap and then be dropped.
        SensitiveDataRedactor onlySsn = new SensitiveDataRedactor(
            List.of(new RegexPiiDetector(PiiPatternCatalog.filterByTypes(List.of("US_SSN")))));

        assertThat(onlySsn.redact("mail bob@acme.io now", Set.of(SensitiveKind.PII), null))
            .isEqualTo("mail bob@acme.io now");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*SensitiveDataRedactorReplacerTest*' --console=plain > /tmp/task5-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, compile errors (no such constructor / overload).

- [ ] **Step 3: Add the constructor and the overload**

`RegexPiiDetector.java` — replace the field initializer with two constructors:

```java
    private final List<PiiPatternCatalog.PiiPattern> patterns;

    public RegexPiiDetector() {
        this(PiiPatternCatalog.curatedDefault());
    }

    /**
     * A detector over an explicit pattern list — how a per-node picker restricts detection to the types its author
     * selected. Restricting at the detector rather than filtering spans afterwards matters: filtering after
     * resolution would let an unselected type win an overlap and then be discarded, leaving the selected one
     * undetected.
     *
     * @param patterns the patterns to run, in catalog order
     */
    public RegexPiiDetector(List<PiiPatternCatalog.PiiPattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }
```

`SensitiveDataRedactor.java` — change the existing 5-argument `redactWithSpans` body's last line to `return redactWithSpans(text, kinds, minConfidence, metrics, extraCandidates, SensitiveSpan::placeholder);` and add directly below it:

```java
    /**
     * As the overload without {@code replacer}, but rendering each accepted span through {@code replacer} instead of
     * {@link SensitiveSpan#placeholder()}. This is the seam a front-end with its own notation uses — the per-node
     * sanitizer renders {@code <TYPE>} — so the engine detects and resolves once and only the notation differs.
     *
     * @param replacer produces the replacement text for one accepted span
     */
    public RedactionResult redactWithSpans(
        String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics,
        List<SensitiveSpan> extraCandidates, Function<SensitiveSpan, String> replacer) {

        if (text == null || text.isEmpty() || kinds.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> candidates = filterByKind(
            filterByConfidence(withExtras(detectCandidates(text, metrics), extraCandidates), minConfidence, metrics),
            kinds);

        if (candidates.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> accepted = resolve(candidates);

        return new RedactionResult(apply(text, accepted, replacer), accepted);
    }
```

(Move the existing body's null/empty guard and pipeline into this overload; the 5-arg one becomes a one-line delegate. `java.util.function.Function` is already imported for `apply`.)

Run the Step 2 command again → `exit=0`.

- [ ] **Step 4: Write the failing node tests**

`PiiEntityOptionsTest.java` (port of the deleted picker invariant):

```java
package com.bytechef.component.ai.agent.guardrails.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.Option;
import com.bytechef.platform.ai.sensitivedata.PiiPatternCatalog;
import com.bytechef.platform.component.definition.ParametersFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PiiEntityOptionsTest {

    @Test
    void testPickerOptionsMatchCatalogTypesExactly() {
        List<String> optionValues = PiiEntityOptions.getPiiDetectionOptions()
            .stream()
            .map(Option::getValue)
            .toList();
        List<String> catalogTypes = PiiPatternCatalog.ALL.stream()
            .map(PiiPatternCatalog.PiiPattern::type)
            .toList();

        assertThat(optionValues).containsExactlyInAnyOrderElementsOf(catalogTypes);
    }

    @Test
    void testAllSelectsTheWholeCatalogIncludingTheContextualTypes() {
        // The node picker is a menu over the whole catalog: DATE_TIME and LOCATION are excluded from the platform's
        // curated DEFAULT, not from what a node author may select (spec §5a).
        assertThat(PiiEntityOptions.selectedPatterns(ParametersFactory.create(Map.of("type", "ALL"))))
            .containsExactlyElementsOf(PiiPatternCatalog.ALL);
    }

    @Test
    void testSelectedRequiresAtLeastOneEntity() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PiiEntityOptions.selectedPatterns(
                ParametersFactory.create(Map.of("type", "SELECTED", "entities", List.of()))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Update `PiiTest.java`: keep every existing test (they still describe the contract) but change `testSanitizeMaskGroupsEntitiesByType` to expect the span-based result:

```java
    @Test
    void testSanitizeMaskReturnsTheMaskedTextRatherThanAnEntityMap() {
        PreflightSanitizerFunction function = (PreflightSanitizerFunction) Pii.ofSanitize()
            .getElement();

        MaskResult result = function.mask(
            "email user@example.com and phone 555-123-4567", contextOf(Map.of("type", "ALL")));

        assertThat(result).isInstanceOf(MaskResult.Masked.class);
        assertThat(((MaskResult.Masked) result).text())
            .isEqualTo("email <EMAIL_ADDRESS> and phone <PHONE_NUMBER>");
    }
```

and add three tests:

```java
    @Test
    void testCheckUnionsPublishedSpansWithItsOwnDetection() throws Exception {
        GuardrailCheckFunction function = Pii.ofCheck()
            .getElement();

        // The floor tokenized the e-mail (so this text carries no e-mail) but published its span; the node's own
        // detection over the received text finds the phone number the floor did not act on.
        GuardrailContext context = contextOf(Map.of("type", "ALL"))
            .withPublishedInputSpans(List.of(SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 0, 16)));

        Optional<Violation> violation = function.apply("[PII_EMAIL_ADDRESS_1_abcd] or 555-123-4567", context);

        assertThat(violation).isPresent();
        assertThat(violation.get()).isInstanceOf(Violation.SpanViolation.class);
        assertThat(((Violation.SpanViolation) violation.get()).matchCount()).isEqualTo(2);
        assertThat(violation.get()
            .info()).containsEntry("entityTypes", new java.util.ArrayList<>(List.of("EMAIL_ADDRESS", "PHONE_NUMBER")));
    }

    @Test
    void testPublishedSpansAreFilteredByTheNodesOwnSelection() throws Exception {
        GuardrailCheckFunction function = Pii.ofCheck()
            .getElement();

        GuardrailContext context = contextOf(Map.of("type", "SELECTED", "entities", List.of("US_SSN")))
            .withPublishedInputSpans(List.of(SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 0, 16)));

        assertThat(function.apply("[PII_EMAIL_ADDRESS_1_abcd]", context))
            .as("a node that only asked about SSNs does not fire on the floor's e-mail span")
            .isEmpty();
    }

    @Test
    void testPublishedSecretSpansNeverCountForThePiiCheck() throws Exception {
        GuardrailCheckFunction function = Pii.ofCheck()
            .getElement();

        GuardrailContext context = contextOf(Map.of("type", "ALL"))
            .withPublishedInputSpans(List.of(SensitiveSpan.of(SensitiveKind.SECRET, "AWS_ACCESS_KEY", 0, 20)));

        assertThat(function.apply("[REDACTED_AWS_ACCESS_KEY]", context)).isEmpty();
    }
```

(with imports `com.bytechef.platform.ai.sensitivedata.SensitiveKind`, `SensitiveSpan`). Note the pii module's `build.gradle.kts` gets `implementation(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service"))` so `Pii` can use the core directly (the parent `guardrails` module exposes it only as `implementation`).

- [ ] **Step 5: Run to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:pii:test :server:libs:modules:components:ai:agent:guardrails:test --tests '*PiiEntityOptionsTest*' --continue --console=plain > /tmp/task5b-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`.

- [ ] **Step 6: Write `PiiEntityOptions` and rewrite `Pii`**

`PiiEntityOptions.java` (guardrails `util`): move `getPiiDetectionOptions()` VERBATIM from `PiiDetectorUtils` (its ordered 36-entry list, unchanged — the order fixes the generated definition JSON), plus:

```java
    /**
     * Resolves the node's {@code type}/{@code entities} parameters to the catalog patterns to run. {@code ALL} is the
     * whole catalog, contextual types included — the picker is a menu, and the curated default's exclusions are the
     * platform's, not the node author's (spec §5a).
     */
    public static List<PiiPatternCatalog.PiiPattern> selectedPatterns(Parameters parameters) {
        String type = parameters.getString(TYPE, TYPE_ALL);

        if (!TYPE_SELECTED.equals(type)) {
            return PiiPatternCatalog.ALL;
        }

        List<PiiPatternCatalog.PiiPattern> selected =
            PiiPatternCatalog.filterByTypes(parameters.getList(ENTITIES, String.class));

        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                "PII guardrail TYPE='SELECTED' requires at least one entity in 'Entities'.");
        }

        return selected;
    }
```

(static imports of `TYPE`, `TYPE_ALL`, `TYPE_SELECTED`, `ENTITIES` from `GuardrailsConstants`; class is `final` with a private constructor.)

`Pii.java` — replace everything from `resolvePatterns` down with:

```java
    private static Optional<Violation> applyCheck(String text, GuardrailContext context) {
        List<PiiPatternCatalog.PiiPattern> patterns = PiiEntityOptions.selectedPatterns(context.inputParameters());
        Set<String> selectedTypes = patterns.stream()
            .map(PiiPatternCatalog.PiiPattern::type)
            .collect(Collectors.toSet());

        List<SensitiveSpan> own = detect(text, patterns);
        List<SensitiveSpan> published = context.publishedInputSpans()
            .stream()
            .filter(span -> span.kind() == SensitiveKind.PII && selectedTypes.contains(span.category()))
            .toList();

        if (own.isEmpty() && published.isEmpty()) {
            return Optional.empty();
        }

        ArrayList<String> entityTypes = Stream.concat(published.stream(), own.stream())
            .map(SensitiveSpan::category)
            .distinct()
            .collect(Collectors.toCollection(ArrayList::new));

        if (published.isEmpty()) {
            List<String> values = own.stream()
                .map(span -> text.substring(span.start(), span.end()))
                .toList();

            return Optional.of(Violation.ofMatches("piiCheck", values, Map.of("entityTypes", entityTypes)));
        }

        return Optional.of(
            Violation.ofSpans("piiCheck", published.size() + own.size(), Map.of("entityTypes", entityTypes)));
    }

    private static MaskResult mask(String text, GuardrailContext context) {
        if (text == null || text.isEmpty()) {
            return MaskResult.unchanged();
        }

        SensitiveDataRedactor.RedactionResult result = redactor(PiiEntityOptions.selectedPatterns(
            context.inputParameters()))
                .redactWithSpans(
                    text, Set.of(SensitiveKind.PII), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, null, List.of(),
                    span -> "<" + span.category() + ">");

        if (result.accepted()
            .isEmpty()) {
            return MaskResult.unchanged();
        }

        return MaskResult.masked(result.text());
    }

    private static List<SensitiveSpan> detect(String text, List<PiiPatternCatalog.PiiPattern> patterns) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        return redactor(patterns)
            .redactWithSpans(text, Set.of(SensitiveKind.PII), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, null)
            .accepted();
    }

    private static SensitiveDataRedactor redactor(List<PiiPatternCatalog.PiiPattern> patterns) {
        return new SensitiveDataRedactor(List.of(new RegexPiiDetector(patterns)));
    }
```

and the two anonymous functions become:

```java
            .object(() -> new PreflightCheckFunction() {

                @Override
                public Optional<Violation> apply(String text, GuardrailContext context) {
                    return applyCheck(text, context);
                }

                @Override
                public MaskResult mask(String text, GuardrailContext context) {
                    return Pii.mask(text, context);
                }
            });
```

```java
            .object(() -> new PreflightSanitizerFunction() {

                @Override
                public String apply(String text, GuardrailContext context) {
                    MaskResult result = Pii.mask(text, context);

                    return result instanceof MaskResult.Masked masked ? masked.text() : text;
                }

                @Override
                public MaskResult mask(String text, GuardrailContext context) {
                    return Pii.mask(text, context);
                }
            });
```

`sharedProperties()` uses `PiiEntityOptions.getPiiDetectionOptions()`. Remove the `MaskEntityMapUtils`/`PiiDetectorUtils` imports; add `SensitiveDataRedactor`, `RegexPiiDetector`, `PiiPatternCatalog`, `SensitiveKind`, `SensitiveSpan`, `java.util.Set`, `java.util.stream.Stream`. Check `MaskResult` has `unchanged()` and `masked(String)` factories (spec summary says it does; confirm in the file).

`LlmPii.java` — swap the import and the call to `PiiEntityOptions.getPiiDetectionOptions()`.

Delete `PiiDetectorUtils.java`, `PiiDetectorUtilsTest.java`, `PiiDetectorUtilsParityTest.java`, `PiiDetectorUtilsCustomRegexTest.java`; in `DetectorConcurrencyTest.java` delete `testPiiDetectorIsThreadSafeAcrossConcurrentInvocations` and its now-unused imports.

- [ ] **Step 7: Run the affected modules**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test :server:libs:modules:components:ai:agent:guardrails:test :server:libs:modules:components:ai:agent:guardrails:pii:test :server:libs:modules:components:ai:agent:guardrails:llm-pii:test --continue --console=plain > /tmp/task5-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task5-green.log
```
Expected: `exit=0`, no FAILED lines. If `PiiTest.testCheckFindsEmail` now fails because `user@example.com` scores below 0.4 — it does not (EMAIL_ADDRESS is High-scored in the catalog); if any other existing `PiiTest` fixture relies on a Low-scored type without context, replace its fixture with an e-mail and note the threshold in the commit message (spec D14 — this is the documented behaviour change).

- [ ] **Step 8: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew spotlessApply --console=plain > /tmp/task5-fmt.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service server/libs/modules/components/ai/agent/guardrails/src server/libs/modules/components/ai/agent/guardrails/pii server/libs/modules/components/ai/agent/guardrails/llm-pii/src/main
git commit -m "732 Run the per-node PII child on the shared detection engine

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: The `secret-keys` child judges published spans too

**Files:**
- Modify: `server/libs/modules/components/ai/agent/guardrails/secret-keys/src/main/java/com/bytechef/component/ai/agent/guardrails/secretkeys/cluster/SecretKeys.java` (`applyCheck`)
- Test: `server/libs/modules/components/ai/agent/guardrails/secret-keys/src/test/java/com/bytechef/component/ai/agent/guardrails/secretkeys/cluster/SecretKeysTest.java`

**Interfaces:**
- Consumes: `GuardrailContext.publishedInputSpans()`, `Violation.ofSpans` (Task 4).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** (append to `SecretKeysTest`)

```java
    @Test
    void testCheckUnionsPublishedSecretSpansWithItsOwnDetection() throws Exception {
        GuardrailCheckFunction function = SecretKeys.ofCheck()
            .getElement();

        // The floor redacted an AWS key and published its span; the text it hands on carries a prefixed token the
        // core does not know but this child's own detector does. Both count.
        GuardrailContext context = contextOf(Map.of())
            .withPublishedInputSpans(List.of(SensitiveSpan.of(SensitiveKind.SECRET, "AWS_ACCESS_KEY", 0, 20)));

        Optional<Violation> violation = function.apply(
            "[REDACTED_AWS_ACCESS_KEY] and sk-abcdefghijklmnopqrstuvwxyz0123456789", context);

        assertThat(violation).isPresent();
        assertThat(violation.get()).isInstanceOf(Violation.SpanViolation.class);
        assertThat(((Violation.SpanViolation) violation.get()).matchCount()).isEqualTo(2);
        assertThat(violation.get()
            .info()).containsKey("providerTypes");
    }

    @Test
    void testPublishedPiiSpansNeverCountForTheSecretKeysCheck() throws Exception {
        GuardrailCheckFunction function = SecretKeys.ofCheck()
            .getElement();

        GuardrailContext context = contextOf(Map.of())
            .withPublishedInputSpans(List.of(SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", 0, 16)));

        assertThat(function.apply("[PII_EMAIL_ADDRESS_1_abcd]", context)).isEmpty();
    }
```

`contextOf` is the helper the test class already has; use the same shape. Confirm `sk-abcdefghijklmnopqrstuvwxyz0123456789` is detected by the existing prefixed-token path by checking `SecretKeysTest`'s existing fixtures and reusing one of theirs if it differs.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:secret-keys:test --console=plain > /tmp/task6-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` (a `PatternViolation` with count 1 where a `SpanViolation` with count 2 was expected).

- [ ] **Step 3: Union in `applyCheck`**

Replace `SecretKeys.applyCheck` with:

```java
    private static Optional<Violation> applyCheck(String text, GuardrailContext context) {
        ResolvedConfig config = resolveConfig(context);

        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            text, config.permissiveness(), List.of(), config.allowedFileExtensions());
        List<SensitiveSpan> published = context.publishedInputSpans()
            .stream()
            .filter(span -> span.kind() == SensitiveKind.SECRET)
            .toList();

        if (matches.isEmpty() && published.isEmpty()) {
            return Optional.empty();
        }

        ArrayList<String> providerTypes = Stream.concat(
            published.stream()
                .map(SensitiveSpan::category),
            matches.stream()
                .map(SecretMatch::type))
            .distinct()
            .collect(Collectors.toCollection(ArrayList::new));

        if (published.isEmpty()) {
            List<String> values = matches.stream()
                .map(SecretMatch::value)
                .distinct()
                .toList();

            return Optional.of(
                Violation.ofMatches("secretKeysCheck", values, Map.of("providerTypes", providerTypes)));
        }

        return Optional.of(
            Violation.ofSpans(
                "secretKeysCheck", published.size() + matches.size(), Map.of("providerTypes", providerTypes)));
    }
```

and update the `ofCheck` anonymous class to call `applyCheck(text, context)`. Imports: `SensitiveKind`, `SensitiveSpan`, `java.util.stream.Stream`. `secret-keys/build.gradle.kts` gets `implementation(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))` (the span types only).

- [ ] **Step 4: Run, format, commit**

Run the Step 2 command with `/tmp/task6-green.log` → `exit=0`. Then:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:secret-keys:spotlessApply --console=plain > /tmp/task6-fmt.log 2>&1; echo "exit=$?"
git add server/libs/modules/components/ai/agent/guardrails/secret-keys
git commit -m "732 Let the per-node secret-keys check judge the floor's published spans

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: End-to-end proof of the ordering rule through a real `ChatClient`

**Files:**
- Modify: `server/libs/modules/components/ai/agent/guardrails/build.gradle.kts` (add `testImplementation(project(":server:libs:modules:components:ai:agent:guardrails:pii"))`)
- Test: `server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/NodeCheckJudgesPublishedSpansTest.java` (new)

**Interfaces:**
- Consumes: `GuardrailAdvisorOrder`, `PublishedInputSpans` (Task 1), `CheckForViolationsAdvisor` (Task 4), `Pii.ofCheck()` (Task 5).
- Produces: nothing; this task is the regression net for spec §6 "The ordering rule".

- [ ] **Step 1: Write the test**

```java
/*
 * (Apache 2.0 header)
 */

package com.bytechef.component.ai.agent.guardrails.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bytechef.component.ai.agent.guardrails.pii.cluster.Pii;
import com.bytechef.component.definition.Context;
import com.bytechef.platform.ai.guardrails.GuardrailAdvisorOrder;
import com.bytechef.platform.ai.guardrails.PublishedInputSpans;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.component.definition.ParametersFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The ordering rule of the consolidation spec (§6), proven through Spring AI's real chain rather than mocked
 * neighbours: a floor stand-in at {@code WORKSPACE_FLOOR} tokenizes the caller's e-mail and publishes its span; the
 * real {@code CheckForViolationsAdvisor} with a real {@code pii} child sits at {@code NODE_CHECK}.
 *
 * @author Ivica Cardic
 */
class NodeCheckJudgesPublishedSpansTest {

    private static final String EMAIL = "bob@acme.io";
    private static final String TOKEN = "[PII_EMAIL_ADDRESS_1_abcd]";

    @Test
    void testANodeBlockOnInputPiiStillFiresWhenTheFloorTokenizedIt() {
        String content = ChatClient.create(echoModel(TOKEN))
            .prompt("please mail " + EMAIL)
            .advisors(floorStandIn(true))
            .advisors(nodePiiCheck(true, false))
            .call()
            .content();

        assertThat(content)
            .as("the node judged the floor's published detection, not the tokens it received")
            .isEqualTo("blocked");
    }

    @Test
    void testWithoutPublicationTheSameNodeCheckIsBlindNegativeControl() {
        String content = ChatClient.create(echoModel(TOKEN))
            .prompt("please mail " + EMAIL)
            .advisors(floorStandIn(false))
            .advisors(nodePiiCheck(true, false))
            .call()
            .content();

        assertThat(content)
            .as("proves the verdict above came from publication: same floor, same node, no spans -> no block")
            .isEqualTo(EMAIL);
    }

    @Test
    void testANodeOutputCheckDoesNotFireOnTheCallersRestoredValue() {
        String content = ChatClient.create(echoModel(TOKEN))
            .prompt("please mail " + EMAIL)
            .advisors(floorStandIn(true))
            .advisors(nodePiiCheck(false, true))
            .call()
            .content();

        assertThat(content)
            .as("the node saw the token on the response; the floor restored the caller's value afterwards")
            .isEqualTo(EMAIL);
    }

    private static CheckForViolationsAdvisor nodePiiCheck(boolean validateInput, boolean validateOutput) {
        return CheckForViolationsAdvisor.builder()
            .context(mock(Context.class))
            .blockedMessage("blocked")
            .add(
                "piiCheck", Pii.ofCheck()
                    .getElement(),
                ParametersFactory.create(
                    Map.of("type", "ALL", "validateInput", validateInput, "validateOutput", validateOutput)),
                ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()))
            .build();
    }

    /**
     * Replaces the e-mail with a token on the way in, restores it on the way out, and publishes the span when asked.
     */
    private static CallAdvisor floorStandIn(boolean publish) {
        return new CallAdvisor() {

            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                List<Message> patched = new ArrayList<>();
                int start = -1;

                for (Message message : request.prompt()
                    .getInstructions()) {
                    String text = message.getText();

                    if (text != null && text.contains(EMAIL)) {
                        start = text.indexOf(EMAIL);

                        patched.add(new UserMessage(text.replace(EMAIL, TOKEN)));
                    } else {
                        patched.add(message);
                    }
                }

                ChatClientRequest.Builder builder = request.mutate()
                    .prompt(new Prompt(patched, request.prompt()
                        .getOptions()));

                if (publish && start >= 0) {
                    builder.context(
                        PublishedInputSpans.CONTEXT_KEY,
                        List.of(SensitiveSpan.of(SensitiveKind.PII, "EMAIL_ADDRESS", start, start + EMAIL.length())));
                }

                ChatClientResponse response = chain.nextCall(builder.build());
                String restored = response.chatResponse()
                    .getResult()
                    .getOutput()
                    .getText()
                    .replace(TOKEN, EMAIL);

                return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(restored)))))
                    .context(response.context())
                    .build();
            }

            @Override
            public String getName() {
                return "floor-stand-in";
            }

            @Override
            public int getOrder() {
                return GuardrailAdvisorOrder.WORKSPACE_FLOOR;
            }
        };
    }

    private static ChatModel echoModel(String reply) {
        return new ChatModel() {

            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
            }
        };
    }
}
```

`CheckForViolationsAdvisor.Builder.blockedMessage(String)` exists (line 502). If `Context` mocking trips `contextLog`, look at how `CheckForViolationsAdvisorTest` builds its `Context` and copy that.

- [ ] **Step 2: Run; expect all three green, then prove the negative control is load-bearing**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:test --tests '*NodeCheckJudgesPublishedSpansTest*' --console=plain > /tmp/task7-green.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`. Then temporarily change `CheckForViolationsAdvisor.getOrder()` to return `GuardrailAdvisorOrder.WORKSPACE_FLOOR` (recreating the tie), re-run: `testANodeOutputCheckDoesNotFireOnTheCallersRestoredValue` must FAIL (the node, now outermost, sees the restored e-mail and blocks). Revert the change (`git checkout -- <file>`) and re-run to green. Mention this in the commit message — it is the evidence the test guards the right thing.

- [ ] **Step 3: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:spotlessApply --console=plain > /tmp/task7-fmt.log 2>&1; echo "exit=$?"
git add server/libs/modules/components/ai/agent/guardrails/build.gradle.kts server/libs/modules/components/ai/agent/guardrails/src/test/java/com/bytechef/component/ai/agent/guardrails/advisor/NodeCheckJudgesPublishedSpansTest.java
git commit -m "732 Prove the guardrail ordering rule end to end through the real chain

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: One runtime regex bound — retire `RegexParserUtils.bounded`

**Files:**
- Create: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/GuardrailMatchDeadline.java`
- Modify: `.../guardrails/util/RegexParserUtils.java` (delete `MAX_INPUT_LENGTH`, `MAX_CHAR_ACCESSES`, `bounded`, `RegexExecutionLimitException`, `BoundedCharSequence`, `Counter`; fix the class javadoc)
- Modify: `.../guardrails/util/SecretKeyDetectorUtils.java:119-260`, `.../guardrails/util/KeywordMatcherUtils.java:41-185`, `.../guardrails/util/MaskEntityMapUtils.java:140-160`
- Modify: `.../guardrails/custom-regex/.../cluster/CustomRegex.java:137-270`, `.../guardrails/llm-pii/.../cluster/LlmPii.java:150-176`
- Tests to update: `RegexParserUtilsTest` (delete the four `testBounded*` and `testCompile*MaxLength` stay), `SecretKeyDetectorUtilsTest` (lines ~225-240 and ~305-316), `SecretKeyDetectorUtilsCustomRegexTest` (~58-80), `KeywordMatcherUtilsTest` (~190-200), `MaskEntityMapUtilsTest` (~250-265), `UrlDetectorUtilsTest` (~308-318), `CheckForViolationsAdvisorConfigurationErrorTest` (~225-252), `CustomRegexTest` (~155-245)

**Interfaces:**
- Consumes: `com.bytechef.platform.ai.sensitivedata.MatchDeadline` (`in(Duration)`, `bound(String)`), `DetectionTimeoutException`, `SensitiveDataRedactor.DetectionBounds.DEFAULTS.timeout()`.
- Produces: `GuardrailMatchDeadline.start()` → `MatchDeadline`; overloads `SecretKeyDetectorUtils.detect(String, Permissiveness, List<Pattern>, List<String>, MatchDeadline)`, `KeywordMatcherUtils.match(String, List<String>, boolean, MatchDeadline)`, `KeywordMatcherUtils.findMatchedSubstrings(String, List<String>, boolean, MatchDeadline)`; the shorter overloads call `GuardrailMatchDeadline.start()`.

- [ ] **Step 1: Rewrite the tests first**

Every test that asserted `RegexParserUtils.RegexExecutionLimitException` for catastrophic backtracking now asserts `DetectionTimeoutException` and passes a short deadline where the API allows it, e.g. in `SecretKeyDetectorUtilsCustomRegexTest`:

```java
        assertThatThrownBy(() -> SecretKeyDetectorUtils.detect(
            input, SecretKeyDetectorUtils.Permissiveness.BALANCED, List.of(Pattern.compile("(x+x+)+y")), List.of(),
            MatchDeadline.in(Duration.ofMillis(200))))
            .isInstanceOf(DetectionTimeoutException.class);
```

Every test that asserted an oversize input (`"a".repeat(RegexParserUtils.MAX_INPUT_LENGTH + 1)`) is DELETED: there is no size cap on regex input any more — the deadline bounds time, and the core caps size only for detectors that cannot be windowed. Delete `RegexParserUtilsTest.testBoundedAbortsCatastrophicBacktracking`, `testBoundedRejectsInputLargerThanMaxLength`, `testBoundedAllowsOrdinaryMatching`, `testBoundedReturnsNullForNullInput`; `SecretKeyDetectorUtilsTest`'s two oversize tests; `KeywordMatcherUtilsTest`'s oversize test; `UrlDetectorUtilsTest`'s oversize test (lines ~308–318 — `UrlDetectorUtils` itself never used `bounded`; the test only referenced the constant). `MaskEntityMapUtilsTest` ~250–265: the value→regex path is `Pattern.quote` with boundaries and cannot backtrack — delete that test and say so in the commit. `CheckForViolationsAdvisorConfigurationErrorTest` ~232: throw `new DetectionTimeoutException("budget")` instead and expect `failureKind` `"DetectionTimeoutException"`. `CustomRegexTest` ~155–245: expect `DetectionTimeoutException`; these run under the 2 s default deadline — acceptable.

- [ ] **Step 2: Run the module tests to see them fail to compile**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:compileTestJava :server:libs:modules:components:ai:agent:guardrails:custom-regex:compileTestJava --continue --console=plain > /tmp/task8-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` (the deadline overloads do not exist yet).

- [ ] **Step 3: Add `GuardrailMatchDeadline` and rewrite the five sites**

`GuardrailMatchDeadline.java`:

```java
package com.bytechef.component.ai.agent.guardrails.util;

import com.bytechef.platform.ai.sensitivedata.MatchDeadline;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;

/**
 * Starts the one regex time budget the per-node guardrail utilities match under. The budget is the platform's
 * {@code DetectionBounds.DEFAULTS.timeout()} so a node child and the workspace floor answer "how long may one match
 * run" identically; the count-based budget this replaces was a second bound with its own failure type.
 *
 * @author Ivica Cardic
 */
public final class GuardrailMatchDeadline {

    private GuardrailMatchDeadline() {
    }

    public static MatchDeadline start() {
        return MatchDeadline.in(SensitiveDataRedactor.DetectionBounds.DEFAULTS.timeout());
    }
}
```

Rewrite pattern, applied identically at every site: `CharSequence bounded = deadline.bound(content);` replaces `RegexParserUtils.bounded(content)`; the `budgetFailures` list, its `catch (RegexParserUtils.RegexExecutionLimitException ...)` and the "headline + suppressed" rethrow block are DELETED — a `DetectionTimeoutException` thrown from `charAt` propagates as is (the advisors already catch `Throwable` into an execution-failure violation). Concretely:

- `SecretKeyDetectorUtils`: make the 2-arg and 3-arg `detect` delegate to the 4-arg one, and the 4-arg one delegate to a new 5-arg `detect(String content, Permissiveness level, List<Pattern> extraRegexes, List<String> allowedFileExtensions, MatchDeadline deadline)` that threads `deadline` into the private provider-pattern loop, the `KEY_EQUALS_VALUE` block, the extra-regex loop and `stripAllowedCodeBlocks` (add a `MatchDeadline` parameter to it).
- `KeywordMatcherUtils`: add `match(..., MatchDeadline deadline)` and `findMatchedSubstrings(..., MatchDeadline deadline)`; the existing signatures delegate with `GuardrailMatchDeadline.start()`. `mask(...)` matches against `deadline.bound(result)` per keyword with ONE deadline for the whole call (drop the "each keyword pays its own budget" comment — a wall-clock budget is per call by nature).
- `MaskEntityMapUtils.applyTo`: `CharSequence bounded = GuardrailMatchDeadline.start().bound(text);`.
- `CustomRegex.applyCheck`/`mask`/the sanitize path: one `MatchDeadline deadline = GuardrailMatchDeadline.start();` per method, `entry.pattern().matcher(deadline.bound(text))`; delete the budget-failure plumbing.
- `LlmPii` line ~170: `pattern.matcher(GuardrailMatchDeadline.start().bound(result))`.
- `RegexParserUtils`: remove everything but `MAX_EXPRESSION_LENGTH`, the private constructor and `compile`; rewrite the class javadoc's "DoS hardening" paragraph to: "Matching is bounded elsewhere — every per-node utility matches against a `MatchDeadline`-bound sequence (see `GuardrailMatchDeadline`); this class only bounds compile time via `MAX_EXPRESSION_LENGTH`."

- [ ] **Step 4: Run every guardrails module**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:agent:guardrails:test :server:libs:modules:components:ai:agent:guardrails:custom-regex:test :server:libs:modules:components:ai:agent:guardrails:keywords:test :server:libs:modules:components:ai:agent:guardrails:llm-pii:test :server:libs:modules:components:ai:agent:guardrails:secret-keys:test :server:libs:modules:components:ai:agent:guardrails:urls:test --continue --console=plain > /tmp/task8-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task8-green.log
grep -rn "RegexExecutionLimitException\|MAX_INPUT_LENGTH\|MAX_CHAR_ACCESSES\|RegexParserUtils.bounded" server/libs/modules/components/ai/agent/guardrails --include=*.java | grep -v /build/
```
Expected: `exit=0`, no FAILED lines, the final grep prints nothing.

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew spotlessApply --console=plain > /tmp/task8-fmt.log 2>&1; echo "exit=$?"
git add server/libs/modules/components/ai/agent/guardrails
git commit -m "732 Match every per-node guardrail regex under the one MatchDeadline bound

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Mask/Unmask rebuilt on the core, without a model

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSession.java` (add `tokens()`)
- Create: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/PiiPatternLabels.java`
- Modify: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiEntityOptions.java` (labels from `PiiPatternLabels`)
- Modify: `server/libs/modules/components/ai/universal/universal-text/build.gradle.kts`
- Create: `server/libs/modules/components/ai/universal/universal-text/src/main/java/com/bytechef/component/ai/universal/text/util/MaskSpans.java`
- Rewrite: `.../universal/text/action/MaskAction.java`, `.../universal/text/action/UnmaskAction.java`
- Modify: `.../universal/text/AiTextComponentHandler.java:73-74`
- Delete + regenerate: `server/libs/modules/components/ai/universal/universal-text/src/test/resources/definition/ai-text_v1.json`
- Test: `.../sensitivedata/tokenization/PiiTokenSessionTest.java` (append), `.../sensitivedata/PiiPatternLabelsTest.java` (new), `.../universal/text/action/MaskActionTest.java`, `UnmaskActionTest.java` (new)

**Interfaces:**
- Consumes: `SensitiveDataRedactor.tokenizeWithSpans(String, Set<SensitiveKind>, PiiTokenSession, double, SensitiveDataMetrics, List<SensitiveSpan>)`, `CustomPatternEvaluator.detect(String, List<CustomPattern>, MatchDeadline)`, `CustomPattern(type, pattern, kind, score, contextRule)`, `PiiPatternCatalog.curatedDefault()/filterByTypes`, `RegexPiiDetector(List)` (Task 5), `RegexSecretDetector()`.
- Produces: `PiiTokenSession.tokens()` → `Map<String, String>` (token → value); `PiiPatternLabels.labelOf(String type)` → `String`; `MaskSpans.keywordSpans(String, List<String>)`, `MaskSpans.customPatternSpans(String, List<String>, MatchDeadline)` → `List<SensitiveSpan>`; `MaskAction.of()`/`UnmaskAction.of()` → `ModifiableActionDefinition`; `MaskAction.perform(Parameters, Parameters, ActionContext)` → `Map<String, Object>`; `UnmaskAction.perform(...)` → `String`.

- [ ] **Step 1: Write the failing core tests**

Append to `PiiTokenSessionTest`:

```java
    @Test
    void testTokensExportsTheMintedMapAsACopy() {
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        Map<String, String> tokens = session.tokens();

        assertThat(tokens).containsExactly(Map.entry(token, "bob@acme.io"));

        session.close();

        assertThat(tokens)
            .as("a caller-held map must survive the session that minted it")
            .containsKey(token);
    }
```

`PiiPatternLabelsTest.java`:

```java
class PiiPatternLabelsTest {

    @Test
    void testEveryCatalogTypeHasALabel() {
        for (PiiPatternCatalog.PiiPattern pattern : PiiPatternCatalog.ALL) {
            assertThat(PiiPatternLabels.labelOf(pattern.type()))
                .as(pattern.type())
                .isNotBlank();
        }
    }

    @Test
    void testAnUnknownTypeFallsBackToTheTypeItself() {
        assertThat(PiiPatternLabels.labelOf("CUSTOM")).isEqualTo("CUSTOM");
    }
}
```

- [ ] **Step 2: Run to verify they fail; add `tokens()` and `PiiPatternLabels`**

`PiiTokenSession`:

```java
    /**
     * Returns a copy of every token this session minted, mapped to its value. For callers that hold the map
     * themselves — the Mask action returns it as its output, and Unmask restores from it later without a session.
     *
     * @return token text to value, in no particular order
     */
    public Map<String, String> tokens() {
        return Map.copyOf(tokenToValue);
    }
```

`PiiPatternLabels`: a `final` class with `private static final Map<String, String> LABELS = Map.ofEntries(...)` holding the 36 `("EMAIL_ADDRESS", "Email address")` … pairs copied VERBATIM from `PiiEntityOptions.getPiiDetectionOptions()` (Task 5), and `public static String labelOf(String type) { return LABELS.getOrDefault(type, type); }`. Then change `PiiEntityOptions.getPiiDetectionOptions()` to keep its ORDERED list of types but build each option as `ComponentDsl.option(PiiPatternLabels.labelOf(type), type)` — one label table, the picker order preserved. Re-run `PiiEntityOptionsTest` and the core tests → green.

- [ ] **Step 3: Write the failing action tests**

`MaskActionTest.java`:

```java
package com.bytechef.component.ai.universal.text.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class MaskActionTest {

    @Test
    void testMasksSelectedPiiIntoTokensAndReturnsTheMap() {
        Map<String, Object> output = perform(Map.of(
            "text", "mail bob@acme.io or call 555-123-4567",
            "piiDetection", List.of("EMAIL_ADDRESS")));

        String text = (String) output.get("text");
        @SuppressWarnings("unchecked")
        Map<String, String> maskMap = (Map<String, String>) output.get("maskMap");

        assertThat(text).doesNotContain("bob@acme.io")
            .contains("555-123-4567");
        assertThat(maskMap).hasSize(1)
            .containsValue("bob@acme.io");
        assertThat(text).contains(maskMap.keySet()
            .iterator()
            .next());
    }

    @Test
    void testLegacyOptionValuesStillSelectTheirType() {
        Map<String, Object> output = perform(Map.of("text", "mail bob@acme.io", "piiDetection", List.of("EMAIL")));

        assertThat((String) output.get("text")).doesNotContain("bob@acme.io");
    }

    @Test
    void testEmptySelectionScansTheCuratedDefault() {
        Map<String, Object> output = perform(Map.of("text", "mail bob@acme.io on 2026-09-04"));

        assertThat((String) output.get("text")).doesNotContain("bob@acme.io")
            .as("DATE_TIME is outside the curated default")
            .contains("2026-09-04");
    }

    @Test
    void testSecretsAreRedactedAndNeverEnterTheMap() {
        Map<String, Object> output = perform(Map.of("text", "key AKIAIOSFODNN7EXAMPLE here"));

        @SuppressWarnings("unchecked")
        Map<String, String> maskMap = (Map<String, String>) output.get("maskMap");

        assertThat((String) output.get("text")).doesNotContain("AKIAIOSFODNN7EXAMPLE")
            .contains("[REDACTED_");
        assertThat(maskMap.values()).noneMatch(value -> value.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void testKeywordsAndCustomPatternsBecomeReversibleSpans() {
        Map<String, Object> output = perform(Map.of(
            "text", "Project Falcon ships order ACME-1234 soon",
            "sensitiveKeywords", List.of("falcon"),
            "customRegexPatterns", List.of("ACME-\\d{4}")));

        @SuppressWarnings("unchecked")
        Map<String, String> maskMap = (Map<String, String>) output.get("maskMap");

        assertThat((String) output.get("text")).doesNotContain("Falcon")
            .doesNotContain("ACME-1234");
        assertThat(maskMap.values()).containsExactlyInAnyOrder("Falcon", "ACME-1234");
        assertThat(maskMap.keySet()).allMatch(token -> token.startsWith("[PII_KEYWORD_")
            || token.startsWith("[PII_CUSTOM_"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> perform(Map<String, Object> input) {
        return (Map<String, Object>) MaskAction.perform(
            ParametersFactory.create(input), ParametersFactory.create(Map.of()), Mockito.mock(ActionContext.class));
    }
}
```

`UnmaskActionTest.java`:

```java
@ExtendWith(ObjectMapperSetupExtension.class)
class UnmaskActionTest {

    @Test
    void testRestoresEveryTokenInTheMapAndLeavesUnknownOnesAlone() {
        String restored = UnmaskAction.perform(
            ParametersFactory.create(Map.of(
                "text", "mail [PII_EMAIL_ADDRESS_1_abcd] not [PII_EMAIL_ADDRESS_2_zzzz]",
                "maskMap", Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"))),
            ParametersFactory.create(Map.of()), Mockito.mock(ActionContext.class));

        assertThat(restored).isEqualTo("mail bob@acme.io not [PII_EMAIL_ADDRESS_2_zzzz]");
    }

    @Test
    void testRoundTripsWhatMaskProduced() {
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) MaskAction.perform(
            ParametersFactory.create(Map.of("text", "mail bob@acme.io and alice@acme.io")),
            ParametersFactory.create(Map.of()), Mockito.mock(ActionContext.class));

        String restored = UnmaskAction.perform(
            ParametersFactory.create(Map.of("text", masked.get("text"), "maskMap", masked.get("maskMap"))),
            ParametersFactory.create(Map.of()), Mockito.mock(ActionContext.class));

        assertThat(restored).isEqualTo("mail bob@acme.io and alice@acme.io");
    }
}
```

- [ ] **Step 4: Run to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:universal:universal-text:test --tests '*MaskActionTest*' --tests '*UnmaskActionTest*' --console=plain > /tmp/task9-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` (no static `perform`).

- [ ] **Step 5: Build dependency, `MaskSpans`, and the two actions**

`universal-text/build.gradle.kts` — add:

```kotlin
    implementation(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service"))
```

`MaskSpans.java`:

```java
package com.bytechef.component.ai.universal.text.util;

import com.bytechef.platform.ai.sensitivedata.CustomPattern;
import com.bytechef.platform.ai.sensitivedata.CustomPatternEvaluator;
import com.bytechef.platform.ai.sensitivedata.MatchDeadline;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The two caller-declared span sources of the Mask action: literal keywords and per-run regex patterns. Both feed the
 * engine as extra candidates, so overlaps with catalog spans are settled by the one span-ordering rule.
 *
 * @author Ivica Cardic
 */
public final class MaskSpans {

    public static final String KEYWORD_CATEGORY = "KEYWORD";
    public static final String CUSTOM_CATEGORY = "CUSTOM";

    private MaskSpans() {
    }

    /**
     * Case-insensitive literal matches of every keyword, as reversible PII spans.
     */
    public static List<SensitiveSpan> keywordSpans(String text, List<String> keywords) {
        List<SensitiveSpan> spans = new ArrayList<>();
        String haystack = text.toLowerCase(Locale.ROOT);

        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            String needle = keyword.toLowerCase(Locale.ROOT);
            int from = 0;

            while (true) {
                int start = haystack.indexOf(needle, from);

                if (start < 0) {
                    break;
                }

                spans.add(SensitiveSpan.of(SensitiveKind.PII, KEYWORD_CATEGORY, start, start + needle.length()));

                from = start + needle.length();
            }
        }

        return spans;
    }

    /**
     * Matches of every caller pattern, compiled per run and matched under {@code deadline}. Deliberately NOT put
     * through {@code CustomPatternValidator}: that gate exists for persisted rules that fail silently inside an
     * advisor; a per-run pattern that times out fails this action visibly, which is the right signal here.
     */
    public static List<SensitiveSpan> customPatternSpans(String text, List<String> patterns, MatchDeadline deadline) {
        List<CustomPattern> compiled = new ArrayList<>(patterns.size());

        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }

            try {
                compiled.add(new CustomPattern(CUSTOM_CATEGORY, Pattern.compile(pattern), SensitiveKind.PII, 1.0, null));
            } catch (PatternSyntaxException patternSyntaxException) {
                throw new IllegalArgumentException("Invalid custom pattern: " + pattern, patternSyntaxException);
            }
        }

        if (compiled.isEmpty()) {
            return List.of();
        }

        return CustomPatternEvaluator.detect(text, compiled, deadline);
    }
}
```

`MaskAction.java` (whole file, Apache header):

```java
package com.bytechef.component.ai.universal.text.action;

import static com.bytechef.component.ai.universal.text.constant.AiTextConstants.CUSTOM_PATTERNS;
import static com.bytechef.component.ai.universal.text.constant.AiTextConstants.MASK_MAP;
import static com.bytechef.component.ai.universal.text.constant.AiTextConstants.PII_DETECTION;
import static com.bytechef.component.ai.universal.text.constant.AiTextConstants.SENSITIVE_KEYWORDS;
import static com.bytechef.component.ai.universal.text.constant.AiTextConstants.TEXT;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.sampleOutput;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.ai.universal.text.constant.AiTextConstants;
import com.bytechef.component.ai.universal.text.util.MaskSpans;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.ai.sensitivedata.MatchDeadline;
import com.bytechef.platform.ai.sensitivedata.PiiPatternCatalog;
import com.bytechef.platform.ai.sensitivedata.PiiPatternLabels;
import com.bytechef.platform.ai.sensitivedata.RegexPiiDetector;
import com.bytechef.platform.ai.sensitivedata.RegexSecretDetector;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic masking on the platform's sensitive-data engine: PII, keywords and custom patterns become
 * session tokens the caller can restore with {@link UnmaskAction}; secrets are redacted irreversibly and never enter
 * the returned map.
 *
 * @author Marko Kriskovic
 * @author Ivica Cardic
 */
public final class MaskAction {

    /**
     * The option values the released action used before it moved to the shared catalog, kept so a saved selection
     * keeps matching rather than silently matching nothing.
     */
    private static final Map<String, String> LEGACY_TYPE_ALIASES = Map.of(
        "EMAIL", "EMAIL_ADDRESS",
        "PHONE", "PHONE_NUMBER",
        "SSN", "US_SSN");

    private MaskAction() {
    }

    public static ModifiableActionDefinition of() {
        return action(AiTextConstants.MASK)
            .title("Mask")
            .description(
                "Replaces sensitive content with reversible tokens and returns the map to restore them. Secrets are " +
                    "redacted irreversibly and are never in the map.")
            .properties(
                string(TEXT)
                    .label("Text")
                    .description("The text to process.")
                    .required(true),
                array(SENSITIVE_KEYWORDS)
                    .label("Sensitive Keywords")
                    .description("Words or phrases to mask, matched case-insensitively.")
                    .items(string()),
                array(PII_DETECTION)
                    .label("PII Detection")
                    .description("PII types to mask. Leave empty to mask every type in the curated default.")
                    .items(string())
                    .options(getPiiDetectionOptions()),
                array(CUSTOM_PATTERNS)
                    .label("Custom Patterns")
                    .description("Java regular expressions to mask.")
                    .items(string()))
            .output(
                outputSchema(
                    object()
                        .properties(
                            string(TEXT)
                                .description("The text with sensitive content replaced by tokens."),
                            object(MASK_MAP)
                                .description("Mapping of each token to the value it replaced.")
                                .additionalProperties(string()))),
                sampleOutput(
                    Map.of(
                        TEXT, "Hello, my name is [PII_KEYWORD_1_k3n9] and my email is [PII_EMAIL_ADDRESS_2_k3n9].",
                        MASK_MAP, Map.of(
                            "[PII_KEYWORD_1_k3n9]", "John Doe",
                            "[PII_EMAIL_ADDRESS_2_k3n9]", "john@example.com"))))
            .perform(MaskAction::perform);
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, ActionContext context) {
        String text = inputParameters.getRequiredString(TEXT);
        List<String> keywords = inputParameters.getList(SENSITIVE_KEYWORDS, String.class, List.of());
        List<String> customPatterns = inputParameters.getList(CUSTOM_PATTERNS, String.class, List.of());
        MatchDeadline deadline = MatchDeadline.in(SensitiveDataRedactor.DetectionBounds.DEFAULTS.timeout());

        List<SensitiveSpan> extraCandidates = new ArrayList<>(MaskSpans.keywordSpans(text, keywords));

        extraCandidates.addAll(MaskSpans.customPatternSpans(text, customPatterns, deadline));

        SensitiveDataRedactor redactor = new SensitiveDataRedactor(
            List.of(new RegexPiiDetector(selectedPatterns(inputParameters)), new RegexSecretDetector()));
        PiiTokenSession session = PiiTokenSession.create();

        try {
            SensitiveDataRedactor.RedactionResult result = redactor.tokenizeWithSpans(
                text, Set.of(SensitiveKind.PII, SensitiveKind.SECRET), session,
                SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, null, extraCandidates);

            return Map.of(TEXT, result.text(), MASK_MAP, session.tokens());
        } finally {
            session.close();
        }
    }

    public static List<Option<String>> getPiiDetectionOptions() {
        List<Option<String>> options = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern pattern : PiiPatternCatalog.ALL) {
            options.add(option(PiiPatternLabels.labelOf(pattern.type()), pattern.type()));
        }

        return options;
    }

    private static List<PiiPatternCatalog.PiiPattern> selectedPatterns(Parameters inputParameters) {
        List<String> selected = inputParameters.getList(PII_DETECTION, String.class, List.of());

        if (selected.isEmpty()) {
            return PiiPatternCatalog.curatedDefault();
        }

        List<String> resolved = selected.stream()
            .map(type -> LEGACY_TYPE_ALIASES.getOrDefault(type, type))
            .toList();

        return PiiPatternCatalog.filterByTypes(resolved);
    }
}
```

`UnmaskAction.java`:

```java
public final class UnmaskAction {

    private UnmaskAction() {
    }

    public static ModifiableActionDefinition of() {
        return action(AiTextConstants.UNMASK)
            .title("Unmask")
            .description("Restores the tokens in a text from the map the Mask action returned.")
            .properties(
                string(TEXT)
                    .label("Text")
                    .description("The text to process.")
                    .required(true),
                object(MASK_MAP)
                    .label("Masked map")
                    .description("Map of tokens to the values to restore.")
                    .additionalProperties(string()))
            .output(
                outputSchema(string().description("The text with tokens restored.")),
                sampleOutput("Hello, my name is John Doe and my email is john@example.com."))
            .perform(UnmaskAction::perform);
    }

    public static String perform(Parameters inputParameters, Parameters connectionParameters, ActionContext context) {
        String text = inputParameters.getRequiredString(TEXT);
        Map<String, String> maskMap = inputParameters.getMap(MASK_MAP, String.class, Map.of());

        for (Map.Entry<String, String> entry : maskMap.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }

        return text;
    }
}
```

`AiTextComponentHandler` lines 73–74 → `MaskAction.of(),` / `UnmaskAction.of(),`. Remove the now-unused `MASK`/`UNMASK` provider plumbing only if nothing else references it.

- [ ] **Step 6: Run the action tests, then regenerate the definition snapshot**

Run the Step 4 command with `/tmp/task9-green.log` → `exit=0`. Then:

```bash
rm server/libs/modules/components/ai/universal/universal-text/src/test/resources/definition/ai-text_v1.json
rm -rf server/libs/modules/components/ai/universal/universal-text/build/resources/test/definition
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:universal:universal-text:test --tests '*AiTextComponentHandlerTest*' --console=plain > /tmp/task9-snap1.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` with `NullPointerException: url` — the documented midpoint (CLAUDE.md "Regenerating takes two runs"). Run the same command again with `/tmp/task9-snap2.log` → `exit=0`. Inspect the diff of `ai-text_v1.json`: the `mask` action must have no `provider`/`model` properties, `piiDetection` must list the catalog, `maskMap` must remain in the output schema.

Then the whole module plus the generated reference page:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:modules:components:ai:universal:universal-text:check --console=plain > /tmp/task9-check.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task9-check.log
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew generateDocumentation --console=plain > /tmp/task9-docs.log 2>&1; echo "exit=$?"
git status --short docs/content/docs/reference/components/ | head
```
Stage ONLY `docs/content/docs/reference/components/ai-text_v1.mdx` from the docs run.

- [ ] **Step 7: Format and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew spotlessApply --console=plain > /tmp/task9-fmt.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiEntityOptions.java server/libs/modules/components/ai/universal/universal-text docs/content/docs/reference/components/ai-text_v1.mdx
git commit -m "732 Rebuild the AI Text Mask and Unmask actions on the sensitive-data engine

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Documentation — the ordering rule is written where it is read

**Files:**
- Modify: `.agents/ai-guardrails.md:1267-1269` and `:1967-1968`; add a section `## Advisor ordering rule` directly before `## The advisor` (line ~1260)
- Modify: `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md:3-4` (status line)

- [ ] **Step 1: Correct the two inverse claims**

Lines 1267–1269 currently read:

```
- **Order**: `HIGHEST_PRECEDENCE` — the guardrail floor must see the final outbound request before
  any other advisor's rewrite, and the model's raw completion before any other advisor
  post-processes it. This also means it runs BEFORE per-node canvas guardrail cluster elements —
  workspace policy is a floor, node elements only add restrictions on top.
```

Replace with:

```
- **Order**: `GuardrailAdvisorOrder.WORKSPACE_FLOOR` (`HIGHEST_PRECEDENCE`), with the per-node
  `CheckForViolationsAdvisor` at `GuardrailAdvisorOrder.NODE_CHECK` (`HIGHEST_PRECEDENCE + 1`) — distinct
  by construction. Before the constants existed both declared `HIGHEST_PRECEDENCE` and the node check,
  registered later, was OUTERMOST: Spring AI breaks an `Ordered` tie toward the LAST registration
  (`SpringAiTiedAdvisorOrderTest` pins this). See "Advisor ordering rule" above for what each direction
  sees.
```

Lines 1967–1968, replace `registers at \`HIGHEST_PRECEDENCE\`, ahead of per-node canvas guardrail cluster elements — the workspace policy is a floor, node elements only add restrictions.` with `registers at \`GuardrailAdvisorOrder.WORKSPACE_FLOOR\`, one outside the per-node check advisor; the per-node input verdict unions the floor's published spans with its own detection (see "Advisor ordering rule").`

- [ ] **Step 2: Add the section**

Insert before `## The advisor`:

```markdown
## Advisor ordering rule

Three positions, fixed by `GuardrailAdvisorOrder` (CE, `platform-ai-api`) rather than by registration order:

| Advisor | Order | Position |
|---|---|---|
| `AiGuardrailsAdvisor` (EE) / `DeferredGuardrailsAdvisor` (Copilot) | `WORKSPACE_FLOOR` = `HIGHEST_PRECEDENCE` | outermost |
| `CheckForViolationsAdvisor` (per node) | `NODE_CHECK` = `HIGHEST_PRECEDENCE + 1` | inside the floor |
| `SanitizeTextAdvisor` (per node) | `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1` | inside both |

**Request.** The floor detects once, publishes the `SensitiveSpan`s it found in USER messages under
`PublishedInputSpans.CONTEXT_KEY` in the advisor context (kind, category, offsets, confidence — never the
value), then tokenizes / redacts / allows / blocks. `CheckForViolationsAdvisor` hands the published spans to
its PREFLIGHT input children through `GuardrailContext.publishedInputSpans()`; `pii` and `secret-keys` judge
**published ∪ own detection over the text they receive** and report a span-derived verdict as
`Violation.SpanViolation`. So the strictest verdict wins whatever the chain position: a workspace on `ALLOW`
or `TOKENIZE` with a node that says "block PII" blocks; a workspace on `BLOCK` throws before the node runs.

**Response.** The chain unwinds inside-out: `sanitize-text` masks model-originated PII (tokens are invisible
to it) → `check-for-violations` judges model-originated PII (tokens invisible) → the floor scans, then
restores. **Restoration only ever returns a value to the party that supplied it.** A node output check that
fired on a restored value would be blocking the caller's own input back at them — which is exactly what the
pre-constant tie did.

**Why a tie is not "undefined".** `DefaultChatClient#buildAdvisorChain` → `DefaultAroundAdvisorChain.Builder#pushAll`
does `Deque#push` (an `addFirst`) per advisor and then a stable `OrderComparator` sort; reversed-then-stably-sorted,
a tie resolves to the LAST registration. `SpringAiTiedAdvisorOrderTest` pins it;
`NodeCheckJudgesPublishedSpansTest` proves both directions through the real chain, with the negative control
that un-publishing the spans makes the same node check blind.

One consequence to know: a node `sanitize-text` → `pii` on INPUT under a tokenizing workspace masks nothing —
the floor already replaced the PII with tokens. The model sees neither; only restoration on the response differs,
and the rule above says the caller gets their value back.
```

- [ ] **Step 3: Update the spec status line and commit**

Spec line 4 → `Sub-project 2 (front-end reconciliation, §6) is **implemented** (plan: \`docs/superpowers/plans/2026-09-04-front-end-reconciliation.md\`). Sub-project 3 (conversation-scoped sessions, §7) is not started.`

```bash
git add .agents/ai-guardrails.md docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md
git commit -m "docs - Write the guardrail advisor ordering rule where it is read

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: Whole-branch verification

- [ ] **Step 1: Repo-wide compile and the touched modules' `check`**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue --console=plain > /tmp/task11-compile.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task11-compile.log
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-api:check :server:libs:platform:platform-component:platform-component-api:check :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:libs:modules:components:ai:agent:guardrails:check :server:libs:modules:components:ai:agent:guardrails:pii:check :server:libs:modules:components:ai:agent:guardrails:secret-keys:check :server:libs:modules:components:ai:agent:guardrails:custom-regex:check :server:libs:modules:components:ai:agent:guardrails:llm-pii:check :server:libs:modules:components:ai:agent:guardrails:keywords:check :server:libs:modules:components:ai:universal:universal-text:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:libs:ai:ai-copilot:ai-copilot-service:check :server:libs:modules:components:ai:agent:check --continue --console=plain > /tmp/task11-check.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task11-check.log
```
Expected: both `exit=0`, no FAILED lines. SpotBugs findings are read from each module's `build/reports/spotbugs/*.html`, never from the XML.

- [ ] **Step 2: Repo-wide tests**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew test --continue --console=plain > /tmp/task11-test.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/task11-test.log
```
Expected: no FAILED lines other than the known GraalPy-load `testPythonStarterLoadsThroughLoader` 30 s timeout, which passes in isolation and touches nothing here.

- [ ] **Step 3: Report**

`git log --oneline` from the plan's first commit; the three verification logs' FAILED greps; the `ai-text_v1.json` diff summary. No commit in this task.
