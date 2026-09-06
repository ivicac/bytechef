# Guardrails Core Extraction — Implementation Plan (sub-project 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the sensitive-data detection engine out of EE into a new CE module pair, replace its 5-pattern PII detector with a curated default drawn from the 36-pattern Presidio catalog that already exists in CE, and redirect the component to that catalog so it exists exactly once.

**Architecture:** The engine — span model, detector SPI, resolution pipeline, replacer, token format and session — becomes `platform-ai-sensitive-data-{api,service}` under `server/libs/platform/platform-ai/`. EE keeps policy, the advisor, the streaming redactor, metrics, the gateway adapter and OpenNLP, all consuming the CE core. The component's `PiiDetectorUtils` keeps its own matching loop and picker but reads patterns from the platform catalog (spec §10: the sub-project boundary is *pattern source*, not pipeline). Only 19 files import the moving types, so the move is mechanical; the taxonomy swap is where the behaviour changes.

**Tech Stack:** Java 25, Spring Boot 4, Gradle 9.7 (Kotlin DSL), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` (sub-project 1 in §10; see §3 for the edition split, §4 module structure, §5 taxonomy)

## Global Constraints

- **CE files use the Apache 2.0 licence header and must NOT carry `@version ee`.** Spotless selects the EE header by the presence of `@version ee` **in the file content, not by path** — so a file moved from EE to CE must have that tag removed *and* the header swapped, or the build fails. Keep `@author Ivica Cardic`, which is this repo's convention in both editions.
- **Package rename on the move:** `com.bytechef.ee.platform.ai.guardrails.detector` → `com.bytechef.platform.ai.sensitivedata`, and `com.bytechef.ee.platform.ai.guardrails.tokenization` → `com.bytechef.platform.ai.sensitivedata.tokenization`.
- **`SensitiveKind` has exactly two values, `PII` and `SECRET`, and is deliberately closed.** The replacer's `SECRET`/else dispatch depends on that; do not add a third value.
- **The catalog is a menu; the platform default is curated (spec §5a).** The platform detector's default set is the full catalog **minus `DATE_TIME` and `LOCATION`** — applied wholesale, those two tokenize every date and street address and break model reasoning. Both stay in the catalog for the component's per-node picker.
- **Secrets never tokenize.** A `SECRET` span keeps `[REDACTED_SECRET]` and never round-trips. If any test named `testSecretsDoNotSurviveTheRoundTrip` or `testTokenizeInputsNeverTokenizesSecrets` fails, stop — that is the safety property of the whole feature.
- **`apply`, `filterByKind` and `resolve` on `SensitiveDataRedactor` are package-private.** They were public once and were deliberately returned to package-private; keep them package-private after the move.
- Java style: one blank line before control statements (`if`, `for`, `while`, `try`, `switch`), except immediately after an opening `{` and except when the keyword continues the previous block on the same line (`} else {`, `} finally {`). No blank line between the last member and a class's closing `}`. Blank line between a variable modification and a following statement that uses it. No abbreviated variable names, including lambda parameters and loop variables. Test method names camelCase with no underscores; unit test classes end in `Test`.
- **Scope spotless to the modules you touch** (`./gradlew :<module>:spotlessApply`). A bare `./gradlew spotlessApply` reformats every module in the repo and has twice left an unrelated file dirty during this project.
- **Never judge a Gradle run piped into `tail`/`grep`** — the pipeline's exit code is the filter's. Redirect to a file, check `$?` on its own line, then grep for `^> Task .* FAILED`.
- Run `git status --short` before every commit and stage only files the task should touch.

**Modules**

| Path | Gradle |
|---|---|
| CE api (new) | `:server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api` |
| CE service (new) | `:server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service` |
| EE guardrails api | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api` |
| EE guardrails service | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service` |
| EE opennlp | `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp` |
| EE gateway | `:server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service` |

**The 19 importers**, by module: EE guardrails-service (12), EE gateway-service (4), EE opennlp (3). Find them with:

```bash
grep -rln 'guardrails\.detector\.Sensitive\|guardrails\.tokenization' --include='*.java' server --exclude-dir=build
```

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `…/platform-ai-sensitive-data-api/build.gradle.kts` | CE api module |
| `…/sensitivedata/SensitiveKind.java` | Moved from EE |
| `…/sensitivedata/SensitiveSpan.java` | Moved from EE |
| `…/sensitivedata/SensitiveDataDetector.java` | Moved from EE — the SPI |
| `…/platform-ai-sensitive-data-service/build.gradle.kts` | CE service module |
| `…/sensitivedata/SensitiveDataRedactor.java` | Moved from EE — the pipeline |
| `…/sensitivedata/SensitiveDataDetectors.java` | Moved from EE — `builtIn()` |
| `…/sensitivedata/RegexSecretDetector.java` | Moved from EE |
| `…/sensitivedata/PiiPatternCatalog.java` | **New** — the single 36-entry pattern source; menu vs curated default |
| `…/sensitivedata/PresidioRegexPiiDetector.java` | **New** — detects over `curatedDefault()` |
| `…/sensitivedata/tokenization/PiiToken.java` | Moved from EE |
| `…/sensitivedata/tokenization/PiiTokenSession.java` | Moved from EE |
| Tests for each of the above | Moved or new |

**Deleted**

| File | When |
|---|---|
| EE `detector/SensitiveKind.java`, `SensitiveSpan.java`, `SensitiveDataDetector.java` | Task 1 |
| EE `detector/SensitiveDataRedactor.java`, `SensitiveDataDetectors.java`, `RegexSecretDetector.java` | Task 2 |
| EE `tokenization/PiiToken.java`, `PiiTokenSession.java` | Task 3 |
| EE `detector/RegexPiiDetector.java` (the 5-pattern detector) | Task 5 |
| The 36 inline pattern entries in the component's `PiiDetectorUtils` | Task 5b (replaced by the catalog redirect) |

**Modified:** `settings.gradle.kts`, the 19 importers, EE and gateway build files, the guardrails component's `PiiDetectorUtils` + `build.gradle.kts` (Task 5b), `.agents/ai-guardrails.md`, `docs/content/docs/platform/automation/deploy/ai-gateway.md`.

---

## Task 1: The CE api module and the span model

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/build.gradle.kts`
- Create: `…/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/{SensitiveKind,SensitiveSpan,SensitiveDataDetector}.java`
- Create: `…/src/test/java/com/bytechef/platform/ai/sensitivedata/SensitiveSpanTest.java`
- Modify: `settings.gradle.kts`
- Modify: the 19 importers (imports only)
- Modify: EE guardrails-api/service, opennlp, gateway-service build files
- Delete: the three EE `detector/` files and EE `SensitiveSpanTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.bytechef.platform.ai.sensitivedata.SensitiveKind` (enum `PII`, `SECRET`); `SensitiveSpan(SensitiveKind kind, String category, int start, int end, double confidence)` with `static of(kind, category, start, end)`, `String placeholder()`, `int length()`, `boolean overlaps(SensitiveSpan)`; `interface SensitiveDataDetector` with `String name()` and `List<SensitiveSpan> detect(String text)`.

- [ ] **Step 1: Register the module**

Add to `settings.gradle.kts`, beside the other `server:libs:platform:platform-ai:` entries:

```kotlin
include("server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api")
```

- [ ] **Step 2: Create the build file**

`…/platform-ai-sensitive-data-api/build.gradle.kts` — the EE api module it replaces had no production dependencies, so this one does not either:

```kotlin
dependencies {
    compileOnly("com.github.spotbugs:spotbugs-annotations")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}
```

- [ ] **Step 3: Move the three types**

`git mv` each file from `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/detector/` to `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/`, then in each file:

1. Change `package com.bytechef.ee.platform.ai.guardrails.detector;` → `package com.bytechef.platform.ai.sensitivedata;`
2. Replace the ByteChef Enterprise licence header with the Apache 2.0 header used across CE — copy it verbatim from `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/AiGuardrailsAdvisorProvider.java`.
3. **Delete the `@version ee` Javadoc tag.** Leaving it makes spotless demand the EE header on a CE file and the build fails.
4. Keep `@author Ivica Cardic`.

Do not change any logic. `SensitiveSpan`'s `CATEGORY_PATTERN` stays `[A-Z][A-Z0-9_]*` — Task 4 depends on the Presidio type names matching it.

Move `SensitiveSpanTest.java` the same way.

- [ ] **Step 4: Update the importers**

```bash
grep -rl 'com\.bytechef\.ee\.platform\.ai\.guardrails\.detector\.\(SensitiveSpan\|SensitiveKind\|SensitiveDataDetector\)' --include='*.java' server --exclude-dir=build \
  | xargs sed -i '' 's|com\.bytechef\.ee\.platform\.ai\.guardrails\.detector\.\(SensitiveSpan\|SensitiveKind\|SensitiveDataDetector\)|com.bytechef.platform.ai.sensitivedata.\1|g'
```

Then add the new module to the three consuming EE build files. In EE guardrails-api, guardrails-service, opennlp and gateway-service `build.gradle.kts`, add:

```kotlin
api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))
```

Use `api(...)` rather than `implementation(...)` where the module exposes these types on its own public surface (EE guardrails-api and -service do; check the others and use `implementation` if they only consume internally).

- [ ] **Step 5: Compile and check**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check > /tmp/t1.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t1.log || echo "no failed tasks"
```

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t1c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t1c.log || echo "no failed tasks"
```

Expected: both exit=0. A compile error naming the old package means an importer was missed — the `sed` above only rewrites fully-qualified single-type imports; check for wildcard imports with `grep -rn 'guardrails\.detector\.\*'`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "732 Move the sensitive-data span model to a CE module"
```

---

## Task 2: The CE service module and the pipeline

**Files:**
- Create: `…/platform-ai-sensitive-data-service/build.gradle.kts`
- Create: `…/sensitivedata/{SensitiveDataRedactor,SensitiveDataDetectors,RegexSecretDetector}.java` (moved)
- Create: the corresponding moved tests, including `SensitiveDataRedactorTest` and `RegexDetectorsTest`
- Modify: `settings.gradle.kts`, EE build files, importers
- Delete: those three files from EE guardrails-service

**Interfaces:**
- Consumes: Task 1's `SensitiveSpan`, `SensitiveKind`, `SensitiveDataDetector`.
- Produces: `SensitiveDataRedactor` with `public String redact(String, Set<SensitiveKind>, @Nullable AiGuardrailMetrics)`, `public RedactionResult redactWithSpans(...)`, `public RedactionResult tokenizeWithSpans(String, Set<SensitiveKind>, PiiTokenSession, @Nullable AiGuardrailMetrics)`, `public List<SensitiveSpan> detectCandidates(...)`, `public SensitiveDataRedactor streamSafeView()`, and package-private statics `apply`, `filterByKind`, `resolve`; `SensitiveDataDetectors.builtIn()` returning `List<SensitiveDataDetector>`.

**Note on `AiGuardrailMetrics`:** it lives in EE and the redactor takes it as a parameter. A CE module cannot depend on EE. Introduce a CE interface in the api module:

```java
public interface SensitiveDataMetrics {

    void recordDetectorFailure(String detectorName);
}
```

and have EE's `AiGuardrailMetrics` implement it. Change the redactor's parameters from `@Nullable AiGuardrailMetrics` to `@Nullable SensitiveDataMetrics`. This is the only signature change in the move; everything else is a package rename.

- [ ] **Step 1: Write the failing test for the metrics seam**

In the CE api module, `…/sensitivedata/SensitiveDataMetricsTest.java`:

```java
package com.bytechef.platform.ai.sensitivedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class SensitiveDataMetricsTest {

    @Test
    void testRecordsTheFailingDetectorName() {
        List<String> recorded = new ArrayList<>();

        SensitiveDataMetrics metrics = recorded::add;

        metrics.recordDetectorFailure("opennlp");

        assertThat(recorded).containsExactly("opennlp");
    }
}
```

- [ ] **Step 2: Run it and see it fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:test --tests '*SensitiveDataMetricsTest' > /tmp/t2r.log 2>&1
echo "exit=$?"
grep -E 'error:' /tmp/t2r.log | head -3
```

Expected: FAIL — `SensitiveDataMetrics` does not exist.

- [ ] **Step 3: Add the interface, create the module, move the pipeline**

Add `SensitiveDataMetrics` to the CE api module (single abstract method, so the lambda in the test compiles).

Register the service module in `settings.gradle.kts`:

```kotlin
include("server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service")
```

`…/platform-ai-sensitive-data-service/build.gradle.kts`:

```kotlin
dependencies {
    compileOnly("com.github.spotbugs:spotbugs-annotations")

    api(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}
```

`git mv` `SensitiveDataRedactor.java`, `SensitiveDataDetectors.java`, `RegexSecretDetector.java` and their tests into it, applying the same three edits as Task 1 Step 3 (package, Apache header, remove `@version ee`). Change the `AiGuardrailMetrics` parameter type to `SensitiveDataMetrics` wherever it appears in these files.

In EE, make `AiGuardrailMetrics implements SensitiveDataMetrics` and add `recordDetectorFailure(String)` delegating to its existing `record(...)` helper with the `detector_failed` event, preserving the current surface tag behaviour.

- [ ] **Step 4: Run the tests**

```bash
./gradlew spotlessApply --offline > /dev/null 2>&1 || true
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check --continue > /tmp/t2.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t2.log || echo "no failed tasks"
```

(Scope `spotlessApply` to the two modules; the line above is a placeholder — use the two `:<module>:spotlessApply` invocations.)

Expected: exit=0, all pre-existing redactor tests still green. `detector_failed` metric attribution must be unchanged — if `AiGuardrailsTest`'s detector-failure test fails, the delegation in Step 3 is wrong.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Move the sensitive-data pipeline to the CE service module"
```

---

## Task 3: Tokenization moves to CE

**Files:**
- Create: `…/sensitivedata/tokenization/{PiiToken,PiiTokenSession}.java` (moved) and their tests
- Modify: importers, EE build files
- Delete: EE `tokenization/` package

**Interfaces:**
- Consumes: nothing new.
- Produces: `com.bytechef.platform.ai.sensitivedata.tokenization.PiiToken` — record `(String category, int ordinal, String sessionId)`, `static final int SESSION_ID_LENGTH = 4`, `String text()`, `static Optional<PiiToken> parse(String)`, `static Pattern pattern()`; `PiiTokenSession` — `static create()`, `String tokenFor(String category, String value)`, `String restore(@Nullable String)`, `RestoreResult restoreWithUnresolvedCount(@Nullable String)` where `RestoreResult(@Nullable String text, int unresolvedCount)`, `String sessionId()`, `int size()`, `void close()`.

- [ ] **Step 1: Move the files**

`git mv` both classes and both tests into `…/platform-ai-sensitive-data-service/src/{main,test}/java/com/bytechef/platform/ai/sensitivedata/tokenization/`, applying the same three edits (package → `com.bytechef.platform.ai.sensitivedata.tokenization`, Apache header, remove `@version ee`).

Update importers:

```bash
grep -rl 'com\.bytechef\.ee\.platform\.ai\.guardrails\.tokenization' --include='*.java' server --exclude-dir=build \
  | xargs sed -i '' 's|com\.bytechef\.ee\.platform\.ai\.guardrails\.tokenization|com.bytechef.platform.ai.sensitivedata.tokenization|g'
```

- [ ] **Step 2: Run the tests**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check --continue > /tmp/t3.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t3.log || echo "no failed tasks"
```

Then the wider compile:

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t3c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t3c.log || echo "no failed tasks"
```

Expected: exit=0. All tokenization tests move with the classes and must pass unchanged — this task changes no behaviour.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "732 Move PII tokenization to the CE service module"
```

---

## Task 4: The shared catalog and the curated-default detector

**Files:**
- Create: `…/sensitivedata/PiiPatternCatalog.java`
- Create: `…/sensitivedata/PresidioRegexPiiDetector.java`
- Test: `…/sensitivedata/PresidioRegexPiiDetectorTest.java`

**Interfaces:**
- Consumes: `SensitiveDataDetector`, `SensitiveSpan`, `SensitiveKind`.
- Produces: `PiiPatternCatalog` with `static final List<PiiPattern> ALL` (36 entries), `static final Set<String> CONTEXTUAL_TYPES` (`DATE_TIME`, `LOCATION`), `static List<PiiPattern> curatedDefault()` (ALL minus contextual), `static List<PiiPattern> filterByTypes(List<String> selectedTypes)`; nested `record PiiPattern(String type, Pattern pattern)`. `PresidioRegexPiiDetector implements SensitiveDataDetector`, detecting over `curatedDefault()`.

**Where the patterns come from:** `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiDetectorUtils.java`, constant `DEFAULT_PII_PATTERNS` — 36 entries of `PiiPattern(String type, Pattern pattern)`. Copy the list **verbatim** into `PiiPatternCatalog.ALL`, preserving order and type names exactly (`EMAIL_ADDRESS`, `US_SSN`, `UK_NINO`, `SG_NRIC_FIN`, `IN_AADHAAR`, `IT_FISCAL_CODE`, `AU_TFN`, `PL_PESEL`, `ES_NIF`, `IBAN_CODE`, `CRYPTO`, `MEDICAL_LICENSE`, `CREDIT_CARD`, `IP_ADDRESS`, `PHONE_NUMBER`, `DATE_TIME`, `LOCATION`, and the rest). Also port `PiiDetectorUtils.filterByTypes`'s selection semantics onto the catalog — Task 5b redirects the component to it.

**The catalog/detector split is the point of this task** (spec §5a): `ALL` is the menu the component's per-node picker selects from; `curatedDefault()` is what the always-on platform policy applies. `DATE_TIME` and `LOCATION` are in the menu and not in the default, because applied wholesale they tokenize every date and street address and break the model's ability to reason about schedules and places.

- [ ] **Step 1: Write the failing tests**

`PresidioRegexPiiDetectorTest.java`:

```java
package com.bytechef.platform.ai.sensitivedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PresidioRegexPiiDetectorTest {

    private final PresidioRegexPiiDetector detector = new PresidioRegexPiiDetector();

    @Test
    void testDetectsEmailUnderThePresidioTypeName() {
        List<SensitiveSpan> spans = detector.detect("mail bob@acme.io please");

        assertThat(spans)
            .extracting(SensitiveSpan::category)
            .contains("EMAIL_ADDRESS");
    }

    @Test
    void testDetectsInternationalIdentifiersTheOldDetectorMissed() {
        assertThat(detector.detect("ID S1234567D"))
            .extracting(SensitiveSpan::category)
            .contains("SG_NRIC_FIN");

        assertThat(detector.detect("HETU 131052-308T"))
            .extracting(SensitiveSpan::category)
            .contains("FI_PERSONAL_IDENTITY_CODE");

        assertThat(detector.detect("ACN 123 456 789"))
            .extracting(SensitiveSpan::category)
            .contains("AU_ACN");
    }

    @Test
    void testEverySpanIsPiiKindWithOffsetsInsideTheText() {
        String text = "mail bob@acme.io from 10.0.0.1";

        for (SensitiveSpan span : detector.detect(text)) {
            assertThat(span.kind()).isEqualTo(SensitiveKind.PII);
            assertThat(span.start()).isGreaterThanOrEqualTo(0);
            assertThat(span.end()).isLessThanOrEqualTo(text.length());
            assertThat(span.start()).isLessThan(span.end());
        }
    }

    @Test
    void testEveryCatalogTypeIsAValidSpanCategory() {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            assertThat(piiPattern.type()).matches("[A-Z][A-Z0-9_]*");
        }
    }

    @Test
    void testCatalogHasTheFullTaxonomyAndTheDefaultIsCurated() {
        assertThat(PiiPatternCatalog.ALL).hasSize(36);

        assertThat(PiiPatternCatalog.curatedDefault())
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .doesNotContain("DATE_TIME", "LOCATION")
            .hasSize(34);
    }

    /**
     * Pins spec §5a's behaviour, not just its set arithmetic: contextual text produces NO spans by default. The
     * sample must be chosen so no other catalog pattern matches it either — an ISO date and a bare street name, no
     * digits shaped like phones, ids or cards beyond the date itself.
     */
    @Test
    void testContextualTextProducesNoSpansByDefault() {
        assertThat(detector.detect("meet on 2026-08-25 at Baker Street")).isEmpty();
    }

    @Test
    void testContextualTypesAreStillSelectableFromTheCatalog() {
        assertThat(PiiPatternCatalog.filterByTypes(List.of("DATE_TIME")))
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .containsExactly("DATE_TIME");
    }
}
```

If `testContextualTextProducesNoSpansByDefault` fails because some *other* pattern genuinely matches the sample (verify which, by printing the spans), adjust the sample text to dodge that pattern — do not weaken the assertion.

- [ ] **Step 2: Run them and see them fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*PresidioRegexPiiDetectorTest' > /tmp/t4r.log 2>&1
echo "exit=$?"
grep -E 'error:' /tmp/t4r.log | head -3
```

Expected: FAIL — neither class exists.

- [ ] **Step 3: Write the catalog and the detector**

`PiiPatternCatalog.java`:

```java
/*
 * (Apache 2.0 header — copy verbatim from an existing CE file)
 */

package com.bytechef.platform.ai.sensitivedata;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single source of the Presidio-taxonomy PII patterns — 36 types, copied verbatim from what was the guardrails
 * component's own list, which is redirected here so the catalog exists exactly once.
 *
 * <p>
 * The catalog is a <b>menu</b>, not a default. {@link #ALL} is what per-node pickers select from;
 * {@link #curatedDefault()} is what always-on platform policy applies, and it excludes the {@link #CONTEXTUAL_TYPES}
 * — {@code DATE_TIME} and {@code LOCATION} — because a date or an address identifies nobody by itself, and masking
 * every one of them destroys the model's ability to reason about schedules and places.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiPatternCatalog {

    public static final Set<String> CONTEXTUAL_TYPES = Set.of("DATE_TIME", "LOCATION");

    public static final List<PiiPattern> ALL = List.of(
    // Copy every entry of PiiDetectorUtils.DEFAULT_PII_PATTERNS here, verbatim, in the same order.
    );

    private PiiPatternCatalog() {
    }

    /**
     * Returns the always-on default: the full catalog minus the contextual types.
     *
     * @return the curated default pattern list
     */
    public static List<PiiPattern> curatedDefault() {
        return ALL.stream()
            .filter(piiPattern -> !CONTEXTUAL_TYPES.contains(piiPattern.type()))
            .toList();
    }

    /**
     * Returns the patterns whose types appear in {@code selectedTypes}, preserving catalog order. Port the selection
     * semantics from {@code PiiDetectorUtils.filterByTypes} exactly, including its handling of an empty selection.
     *
     * @param selectedTypes the type names a caller opted into
     * @return the matching patterns
     */
    public static List<PiiPattern> filterByTypes(List<String> selectedTypes) {
        return ALL.stream()
            .filter(piiPattern -> selectedTypes.contains(piiPattern.type()))
            .toList();
    }

    /**
     * One named PII pattern.
     *
     * @param type    the Presidio entity type, which doubles as the span category
     * @param pattern the recognising regex
     */
    public record PiiPattern(String type, Pattern pattern) {
    }
}
```

Before writing `filterByTypes`, read the component's version and mirror its empty-selection behaviour exactly — if it returns the full list on empty selection, so must this.

`PresidioRegexPiiDetector.java`:

```java
/*
 * (Apache 2.0 header)
 */

package com.bytechef.platform.ai.sensitivedata;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Regex PII detection over {@link PiiPatternCatalog#curatedDefault()} — the always-on platform default, which is the
 * full Presidio catalog minus the contextual types. See the catalog's javadoc for why the two differ.
 *
 * @author Ivica Cardic
 */
public class PresidioRegexPiiDetector implements SensitiveDataDetector {

    private final List<PiiPatternCatalog.PiiPattern> patterns = PiiPatternCatalog.curatedDefault();

    @Override
    public String name() {
        return "presidio-regex-pii";
    }

    @Override
    public List<SensitiveSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern piiPattern : patterns) {
            Matcher matcher = piiPattern.pattern()
                .matcher(text);

            while (matcher.find()) {
                spans.add(SensitiveSpan.of(SensitiveKind.PII, piiPattern.type(), matcher.start(), matcher.end()));
            }
        }

        return spans;
    }
}
```

Do **not** deduplicate overlapping spans here — `SensitiveDataRedactor.resolve` owns overlap resolution and its total order (SECRET → longer → earlier → category) is already tested. A detector that pre-resolves would bypass that.

- [ ] **Step 4: Run the tests**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check > /tmp/t4.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t4.log || echo "no failed tasks"
```

Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Add the shared PII pattern catalog and the curated-default detector"
```

---

## Task 5: Switch the engine to the new taxonomy

**Files:**
- Modify: `…/sensitivedata/SensitiveDataDetectors.java`
- Delete: EE `detector/RegexPiiDetector.java` and its tests
- Modify: every test asserting an old category name or old token category

**Interfaces:**
- Consumes: `PresidioRegexPiiDetector`.
- Produces: `SensitiveDataDetectors.builtIn()` now returns the Presidio detector plus `RegexSecretDetector`.

**This is the task that changes behaviour.** Category names change, so `[REDACTED_EMAIL]` becomes `[REDACTED_EMAIL_ADDRESS]` and `[PII_EMAIL_1_k3n9]` becomes `[PII_EMAIL_ADDRESS_1_k3n9]`. Update the assertions **in this task** — a task must be green at its own commit. But be discriminating: an assertion failing for any reason other than a category rename is a real regression; investigate rather than update it.

- [ ] **Step 1: Write the failing test**

Add to `RegexDetectorsTest`:

```java
    @Test
    void testBuiltInDetectorsUseThePresidioTaxonomy() {
        String redacted = redactor.redact("mail bob@acme.io", BOTH, null);

        assertThat(redacted).isEqualTo("mail [REDACTED_EMAIL_ADDRESS]");
    }

    @Test
    void testBuiltInDetectorsCoverInternationalIdentifiers() {
        String redacted = redactor.redact("ID S1234567D", BOTH, null);

        assertThat(redacted).contains("[REDACTED_SG_NRIC_FIN]");
    }

    @Test
    void testSecretsStillRedactIrreversiblyAfterTheTaxonomySwitch() {
        PiiTokenSession session = PiiTokenSession.create();

        String restored = session.restore(
            redactor.tokenizeWithSpans("token AKIAIOSFODNN7EXAMPLE", BOTH, session, null)
                .text());

        assertThat(restored).isEqualTo("token [REDACTED_SECRET]");
        assertThat(restored).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }
```

- [ ] **Step 2: Run and see them fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*RegexDetectorsTest' > /tmp/t5r.log 2>&1
echo "exit=$?"
grep -E 'expected|but was' /tmp/t5r.log | head -5
```

Expected: FAIL — the first two, because `builtIn()` still returns the 5-pattern detector emitting `EMAIL`.

- [ ] **Step 3: Switch and delete**

In `SensitiveDataDetectors.builtIn()`, replace `new RegexPiiDetector()` with `new PresidioRegexPiiDetector()`. Delete `RegexPiiDetector.java` and `RegexPiiDetectorTest.java` (or the equivalent test class) from the EE tree — the five patterns are covered by the curated default (all five map to non-contextual catalog types), so nothing is lost.

Then find and update every assertion carrying an old category name:

```bash
grep -rn '\[REDACTED_EMAIL\]\|\[REDACTED_IP\]\|\[REDACTED_CC\]\|\[REDACTED_PHONE\]\|\[REDACTED_SSN\]\|PII_EMAIL_' --include='*.java' server --exclude-dir=build
```

Map: `EMAIL`→`EMAIL_ADDRESS`, `IP`→`IP_ADDRESS`, `CC`→`CREDIT_CARD`, `PHONE`→`PHONE_NUMBER`, `SSN`→`US_SSN`. Note `SensitiveDataRedactorTest` uses **stub** detectors with hand-written categories — those are pipeline tests and must NOT be renamed; only tests exercising `SensitiveDataDetectors.builtIn()` change.

- [ ] **Step 4: Run everything**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/t5.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t5.log || echo "no failed tasks"
```

Expected: exit=0. `testSecretsDoNotSurviveTheRoundTrip` and `testTokenizeInputsNeverTokenizesSecrets` must both still pass — if either fails, stop and report.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Switch the guardrails engine to the Presidio PII taxonomy"
```

---

## Task 5b: Redirect the component to the catalog

*(Runs after Task 5; numbered 5b because it depends on the catalog from Task 4 and nothing in Task 5 depends on it.)*

**Files:**
- Modify: `server/libs/modules/components/ai/agent/guardrails/src/main/java/com/bytechef/component/ai/agent/guardrails/util/PiiDetectorUtils.java`
- Modify: the component's `build.gradle.kts`
- Test: `…util/PiiDetectorUtilsTest.java` (existing tests must pass unchanged), plus one new identity test

**Interfaces:**
- Consumes: `PiiPatternCatalog.ALL`, `PiiPatternCatalog.PiiPattern`.
- Produces: `PiiDetectorUtils.DEFAULT_PII_PATTERNS` now *derived from* the catalog; the component's own `PiiPattern` record delegates or aliases to the catalog's.

The component keeps its matching loop (`detect`), masking (`mask`), and picker (`getPiiDetectionOptions`) — sub-project 2 touches those. This task changes only where the patterns come from, so the catalog exists exactly once (spec D5b).

- [ ] **Step 1: Write the failing identity test**

Add to the component's test sources:

```java
    @Test
    void testPatternsAreTheSharedCatalog() {
        assertThat(PiiDetectorUtils.DEFAULT_PII_PATTERNS)
            .extracting(PiiDetectorUtils.PiiPattern::type)
            .containsExactlyElementsOf(
                PiiPatternCatalog.ALL.stream()
                    .map(PiiPatternCatalog.PiiPattern::type)
                    .toList());

        assertThat(PiiDetectorUtils.DEFAULT_PII_PATTERNS)
            .extracting(piiPattern -> piiPattern.pattern()
                .pattern())
            .containsExactlyElementsOf(
                PiiPatternCatalog.ALL.stream()
                    .map(piiPattern -> piiPattern.pattern()
                        .pattern())
                    .toList());
    }
```

Add `implementation(project(":server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service"))` to the component's `build.gradle.kts`. (Check whether the component module builds against platform modules elsewhere — `platform-component-api` is already a dependency, so a platform dependency is precedented; if the build fails on an unexpected cycle, report BLOCKED with the cycle rather than working around it.)

- [ ] **Step 2: Run and see it fail**

```bash
./gradlew :server:libs:modules:components:ai:agent:guardrails:test --tests '*PiiDetectorUtilsTest' > /tmp/t5br.log 2>&1
echo "exit=$?"
grep -E 'error:|expected' /tmp/t5br.log | head -3
```

Expected: FAIL — `PiiPatternCatalog` is not imported/known, or the lists are separate copies.

- [ ] **Step 3: Redirect**

In `PiiDetectorUtils`, replace the 36 inline entries with a mapping from the catalog:

```java
    public static final List<PiiPattern> DEFAULT_PII_PATTERNS = PiiPatternCatalog.ALL.stream()
        .map(catalogPattern -> new PiiPattern(catalogPattern.type(), catalogPattern.pattern()))
        .toList();
```

Keep the component's own `PiiPattern` record — its `sanitize-text` callers use it by that type, and collapsing the record is sub-project 2's business. Delete the now-unused inline pattern entries. `filterByTypes` and `getPiiDetectionOptions` keep working over `DEFAULT_PII_PATTERNS` unchanged — including offering `DATE_TIME` and `LOCATION`, which per spec §5a stay selectable per node.

- [ ] **Step 4: Run the component's full check**

```bash
./gradlew :server:libs:modules:components:ai:agent:guardrails:check > /tmp/t5b.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t5b.log || echo "no failed tasks"
```

**Every pre-existing `PiiDetectorUtils` test must pass unchanged** — the parity test included. A parity-test failure means the copy into the catalog was not verbatim; fix the catalog, not the test.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Redirect the guardrails component to the shared PII pattern catalog"
```

---

## Task 6: Reconcile the secret patterns

**Files:**
- Modify: `…/sensitivedata/RegexSecretDetector.java`
- Test: its existing test class

**Interfaces:** unchanged — `RegexSecretDetector` keeps emitting category `SECRET`.

The component's `SecretKeyDetectorUtils` carries named patterns (`AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `GITHUB_PAT`, `GITHUB_FINE_GRAINED_PAT`, `SLACK_TOKEN`, `STRIPE_KEY`, `GOOGLE_API_KEY`, `OPENAI_KEY`, JWT, PEM). Compare them against `RegexSecretDetector`'s and add any the platform detector lacks.

**Keep the single `SECRET` category.** The named types are useful for the component's reporting, but the platform engine deliberately collapses them: a secret is never tokenized, so its category only ever appears inside `[REDACTED_SECRET]`, and splitting it would change that placeholder for no gain.

- [ ] **Step 1: Write the failing test**

For each pattern present in `SecretKeyDetectorUtils` but absent from `RegexSecretDetector`, add a case:

```java
    @Test
    void testDetectsGitHubFineGrainedTokens() {
        assertThat(redactor.redact("token github_pat_" + "A".repeat(82), BOTH, null))
            .isEqualTo("token [REDACTED_SECRET]");
    }
```

Write one such test per missing pattern, using a realistic sample that matches the component's regex.

- [ ] **Step 2: Run and see them fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*RegexDetectorsTest' > /tmp/t6r.log 2>&1
echo "exit=$?"
grep -E 'expected|but was' /tmp/t6r.log | head -5
```

- [ ] **Step 3: Add the missing patterns**

Copy them verbatim from `SecretKeyDetectorUtils`, preserving the regexes exactly. If a pattern is already present under a different spelling, keep the platform's existing one and note it in your report rather than having two that overlap.

- [ ] **Step 4: Run the module check**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check > /tmp/t6.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t6.log || echo "no failed tasks"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "732 Align the platform secret patterns with the component's set"
```

---

## Task 7: Documentation

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `.agents/ai-gateway-guardrails.md`
- Modify: `docs/content/docs/platform/automation/deploy/ai-gateway.md`

- [ ] **Step 1: Update `.agents/ai-guardrails.md`**

In that document's existing voice, cover: the engine now lives in CE `platform-ai-sensitive-data-{api,service}` and EE consumes it; the edition split and why (spec §3); the Presidio taxonomy and the resulting token category names; the curated default versus the full catalog (spec §5a) and that the platform applies `curatedDefault()` while the component's per-node picker selects from `ALL`; and that the component's `PiiDetectorUtils` now reads the shared catalog, so the pattern list exists exactly once.

Update every example token in the file — `[PII_EMAIL_1_k3n9]` is now `[PII_EMAIL_ADDRESS_1_k3n9]`, which is **24 characters**, so re-check the streaming window precondition wording that cites token lengths.

- [ ] **Step 2: Update the customer-facing page**

`docs/content/docs/platform/automation/deploy/ai-gateway.md` names the detected categories ("emails, US SSNs, credit-card numbers, phone numbers, and IPv4 addresses") and shows an example token. Both change: the detected set is now far broader, and the token format carries the new category names. Describe the broader coverage honestly rather than leaving the old five-item list.

- [ ] **Step 3: Commit**

```bash
git add .agents docs/content
git commit -m "732 docs - Document the CE detection core and the Presidio taxonomy"
```

---

## Task 8: Full verification

- [ ] **Step 1: Format and check every touched module**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check \
          :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
          :server:libs:modules:components:ai:agent:guardrails:check \
          --rerun-tasks --continue > /tmp/t8.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t8.log || echo "no failed tasks"
```

`--rerun-tasks` matters: a cached `UP-TO-DATE` result is not evidence for a verification task.

- [ ] **Step 2: Compile the whole server**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t8c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t8c.log || echo "no failed tasks"
```

- [ ] **Step 3: Confirm the constraints, each with a command**

1. **No EE-licensed file in CE:** `grep -rn '@version ee' server/libs/platform/platform-ai/platform-ai-sensitive-data/` returns nothing.
2. **No CE file carries the Enterprise header:** `grep -rln 'Enterprise License' server/libs/platform/platform-ai/platform-ai-sensitive-data/` returns nothing.
3. **The old packages are gone:** `grep -rn 'ee\.platform\.ai\.guardrails\.detector\|ee\.platform\.ai\.guardrails\.tokenization' --include='*.java' server --exclude-dir=build` returns nothing.
4. **CE does not depend on EE:** `grep -rn 'server:ee' server/libs/platform/platform-ai/platform-ai-sensitive-data/*/build.gradle.kts` returns nothing.
5. **Secrets never tokenize:** both `testSecretsDoNotSurviveTheRoundTrip` and `testTokenizeInputsNeverTokenizesSecrets` pass.
6. **Package-private statics stayed package-private:** `apply`, `filterByKind`, `resolve` on `SensitiveDataRedactor` have no `public` modifier.
7. **The catalog is complete and single-sourced:** `PiiPatternCatalog.ALL` has 36 entries, `curatedDefault()` has 34 and contains neither `DATE_TIME` nor `LOCATION`, the component's identity test passes, and `grep -c 'Pattern.compile' …/PiiDetectorUtils.java` shows the inline pattern entries are gone.
8. **Scan-before-restore survived the move:** in `AiGuardrailsAdvisor`, `StreamingResponseRedactor` (both `push` and `flush`) and `AiGatewayGuardrails`, the restore call appears after the scan call in the same method.

- [ ] **Step 4: Report**

State the check and compile results, the eight confirmations, and anything that failed or looked weaker than expected.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §3 edition split | Tasks 1–3 (what moves), Task 8 §3.4 (CE must not depend on EE) |
| §4 module structure and naming | Tasks 1, 2 |
| §5 taxonomy convergence | Tasks 4, 5 |
| §5a curated default, menu stays selectable | Task 4 (`testCatalogHasTheFullTaxonomyAndTheDefaultIsCurated`, `testContextualTextProducesNoSpansByDefault`, `testContextualTypesAreStillSelectableFromTheCatalog`) |
| §5b token-length precondition restated | Task 7 Step 1 (re-check the streaming window wording against the new longest token) |
| §5 timing (rename is free pre-release) | Task 5 |
| §8 metrics unchanged | Task 2's `SensitiveDataMetrics` seam preserves `detector_failed` attribution |
| §9 parity test stays green over the redirected list | Task 5b Step 4 |
| §9 curated-default pin | Task 4 |
| §10 single-copy boundary — component redirected to the catalog | Task 5b |

Spec §6 (front-end reconciliation) and §7 (session scope) are sub-projects 2 and 3 and are deliberately absent. §9's agreement test between front-ends belongs to sub-project 2, once both call the same core.

**Placeholder scan:** one deliberate ellipsis — Task 4 Step 3's `PiiPatternCatalog.ALL` says to copy 36 entries verbatim from a named constant rather than reproducing them inline. Transcribing 36 regexes into the plan would invite a transcription error in exactly the place where an error is a detection gap; pointing at the authoritative source is safer, and Task 5b's identity test plus the surviving parity test prove the copy is faithful. Everything else is concrete.

**Type consistency:** `SensitiveSpan.of(kind, category, start, end)` is used identically in Tasks 1 and 4. `PiiPatternCatalog.PiiPattern(String type, Pattern pattern)` matches the component's record shape, which Task 5b's identity test compares field-by-field. `curatedDefault()` is consumed in Task 4's detector and asserted in Task 4's tests with the same 34/36 arithmetic. `SensitiveDataMetrics.recordDetectorFailure(String)` is defined in Task 2 and referenced nowhere later, which is correct — EE implements it, CE only calls it.

**One risk for the executor:** Task 5 renames categories, so many assertions change. An assertion failing for a reason *other* than a category rename is a real regression — investigate it rather than updating it. In particular, `SensitiveDataRedactorTest` uses stub detectors with hand-written category names and must not be touched.
