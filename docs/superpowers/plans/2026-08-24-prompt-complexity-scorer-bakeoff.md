# Prompt Complexity Scorer Bake-off Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the candidate `PromptComplexityScorer` implementations plus a measurement harness in a throwaway module, so the choice between them and the existing deterministic baseline is settled by numbers against pre-registered criteria.

**Architecture:** A new test-only Gradle module holds the candidates, a labelled corpus, a metrics library and a runner. Three candidates are measured against the baseline: **B** embedding-centroid over an in-process ONNX model, **C** OpenNLP maxent, and **D** the same centroid class as B over a remote embedding model. B and D share one implementation and differ only in the injected `EmbeddingModel`, so D costs one extra construction in the runner and no new class. Nothing depends on the module, which is the only way to keep ~240 MB of ONNX/DJL off `platform-ai-gateway-service` — that module is on the classpath of the monolith `server-app` and every EE app carrying the gateway. The runner is a JUnit class excluded from the default `test` task and invoked through a dedicated `bakeoff` Gradle task. No production code changes at all.

**Tech Stack:** Java 25, Gradle 9.7 Kotlin DSL, JUnit 5, AssertJ, Spring AI 2.0.1 (`spring-ai-transformers`, ONNX MiniLM-L6-v2), Apache OpenNLP 2.5.11, Jackson 3 (`tools.jackson`).

**Spec:** `docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-design.md`

## Global Constraints

- **Enterprise licence header on every file.** All new Java lives under `server/ee/`, so Spotless applies the ByteChef Enterprise header, not Apache 2.0. The header is selected by the `@version ee` Javadoc tag in the file **content**, not by path — every new class needs both.
- **`@version ee` Javadoc tag on every new class.**
- **No production module may gain a dependency.** `platform-ai-gateway-api` and `platform-ai-gateway-service` build files are not edited by any task in this plan.
- **Dependency versions come from the BOM.** `server/build.gradle.kts:33` applies `spring-ai-bom` to every `server` subproject, and `testImplementation` extends `implementation`, so Spring AI coordinates are declared without versions. OpenNLP is not in any BOM and gets a version in `gradle/libs.versions.toml`.
- **Candidate D is skipped, not failed, when no embedding API key is present.** The runner reads `bakeoff.embedding.apiKey` (system property) or `BAKEOFF_EMBEDDING_API_KEY` (environment). Absent, the runner logs that D was skipped and reports B and C — it must never silently omit a candidate from the report.
- **Never assert what Merge Gateway does internally.** Spec §3.7 fixes the evidence boundary: the embedding model, its location, and whether it is in-process are all undocumented. No comment, Javadoc, or report text may claim otherwise.
- **Exclude `ai.djl.pytorch` and `ai.djl:model-zoo`** from `spring-ai-transformers`. They are unused by `TransformersEmbeddingModel` and DJL's PyTorch engine lazily downloads a ~200 MB native on first use.
- **Checkstyle:** test method names are camelCase with no underscores, and the rule covers *every* method in test sources including private helpers. Empty blocks are forbidden — a comment does not satisfy the rule. `TODO:` comments are forbidden.
- **Java style:** one blank line before `if`/`for`/`while`/`switch`/`try` (except immediately after an opening brace or a method signature guard clause), one blank line between a variable modification and the next statement using it, no blank line before a class's closing brace, no `_` prefix on private methods, no short or cryptic variable names.
- **The last verification before every commit is `:check`, not `:test`.** The per-task steps below use `:test` for the fast red/green loop, which is right — but `:test` does not run SpotBugs, Checkstyle or PMD, so a task can pass its own steps and still leave `:check` red. This actually happened: Tasks 3, 4 and 5 all passed `:test` and together left three SpotBugs findings (two `CT_CONSTRUCTOR_THROW`, one `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`) that only surfaced when the controller ran `:check` afterwards. Run `:check --continue` as the final gate of each task, and read SpotBugs findings from `build/reports/spotbugs/test.html` — the XML report is disabled in this repo and will be stale.
- **Run `./gradlew spotlessApply` before every commit.** Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Commit message convention:** server-side changes are `<ticket_number> <description>`. No ticket exists for this work; use `bakeoff` as the prefix token, e.g. `bakeoff Add scorer bake-off module skeleton`.

---

## File Structure

Everything is created under one new module,
`server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff/`, with
`src/test` only — the module has no `src/main`, because nothing production-facing lives here.

| File | Responsibility |
|---|---|
| `build.gradle.kts` | Dependencies, DJL exclusions, `bakeoff` task, exclusion of the runner from `test` |
| `src/test/java/.../bakeoff/BakeoffPrompt.java` | One corpus entry: text, label, optional tool count and maxTokens |
| `src/test/java/.../bakeoff/BakeoffCorpus.java` | Loads a JSONL resource into `List<BakeoffPrompt>`; builds requests |
| `src/test/java/.../bakeoff/OpenNlpPromptComplexityScorer.java` | Candidate C |
| `src/test/java/.../bakeoff/EmbeddingCentroidPromptComplexityScorer.java` | Candidate B |
| `src/test/java/.../bakeoff/ClassificationMetrics.java` | Accuracy, precision, recall, ROC-AUC |
| `src/test/java/.../bakeoff/RoutingOutcomeMetrics.java` | Tier distribution and cost delta |
| `src/test/java/.../bakeoff/BakeoffReport.java` | Markdown + CSV rendering |
| `src/test/java/.../bakeoff/PromptComplexityScorerBakeoff.java` | The runner; excluded from `test` |
| `src/test/resources/bakeoff/exemplars.jsonl` | §5.1 training set, ~40 entries |
| `src/test/resources/bakeoff/adversarial.jsonl` | §5.2 evaluation set, ~60 entries |
| `src/test/resources/bakeoff/rubric.md` | The labelling rubric the corpus is held to |

Package for all classes: `com.bytechef.ee.platform.ai.gateway.bakeoff`.

---

## Task 1: Module skeleton and corpus loader

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff/build.gradle.kts`
- Modify: `settings.gradle.kts` (immediately after the `platform-ai-gateway-service` include)
- Create: `.../src/test/java/com/bytechef/ee/platform/ai/gateway/bakeoff/BakeoffPrompt.java`
- Create: `.../src/test/java/com/bytechef/ee/platform/ai/gateway/bakeoff/BakeoffCorpus.java`
- Create: `.../src/test/resources/bakeoff/exemplars.jsonl` (3 entries for now; Task 2 fills it)
- Test: `.../src/test/java/com/bytechef/ee/platform/ai/gateway/bakeoff/BakeoffCorpusTest.java`

**Interfaces:**
- Consumes: `AiGatewayChatCompletionRequest`, `AiGatewayChatMessage`, `AiGatewayChatRole`, `AiGatewayTool` from `platform-ai-gateway-api`.
- Produces:
  - `record BakeoffPrompt(String id, String text, String label, Integer toolCount, Integer maxTokens, String bucket, String rationale)` — `label` is `"SIMPLE"` or `"COMPLEX"`.
  - `BakeoffPrompt.isComplex()` → `boolean`
  - `BakeoffCorpus.load(String resourceName)` → `List<BakeoffPrompt>`
  - `BakeoffCorpus.toRequest(BakeoffPrompt)` → `AiGatewayChatCompletionRequest`

- [ ] **Step 1: Register the module in `settings.gradle.kts`**

Insert immediately after the existing line that ends `platform-ai-gateway:platform-ai-gateway-service` — locate it by content, not by line number, since the file shifts as modules are added:

```kotlin
include("server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff")
```

- [ ] **Step 2: Write the module build file**

Create `platform-ai-gateway-scorer-bakeoff/build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))
    testImplementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service"))
}
```

Candidates and their heavy dependencies are added in Tasks 2 and 3; the `bakeoff` task is added in Task 6. Keeping this file minimal now means Task 1 builds in seconds.

- [ ] **Step 3: Write the failing corpus test**

Create `BakeoffCorpusTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class BakeoffCorpusTest {

    @Test
    void testLoadReadsEveryLine() {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        assertThat(prompts).hasSize(3);
    }

    @Test
    void testLoadParsesLabels() {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        BakeoffPrompt first = prompts.getFirst();

        assertThat(first.id()).isEqualTo("ex-001");
        assertThat(first.isComplex()).isFalse();
    }

    @Test
    void testLoadPreservesEmbeddedNewlines() {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        BakeoffPrompt multiline = prompts.get(2);

        assertThat(multiline.text()).contains("\n");
    }

    @Test
    void testToRequestCarriesToolCountAndMaxTokens() {
        BakeoffPrompt prompt = new BakeoffPrompt("t-1", "hello", "SIMPLE", 3, 512, "sanity", "trivial");

        AiGatewayChatCompletionRequest request = BakeoffCorpus.toRequest(prompt);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.tools()).hasSize(3);
        assertThat(request.maxTokens()).isEqualTo(512);
    }

    @Test
    void testToRequestOmitsToolsWhenCountIsNull() {
        BakeoffPrompt prompt = new BakeoffPrompt("t-2", "hello", "SIMPLE", null, null, "sanity", "trivial");

        AiGatewayChatCompletionRequest request = BakeoffCorpus.toRequest(prompt);

        assertThat(request.tools()).isNull();
        assertThat(request.maxTokens()).isNull();
    }
}
```

- [ ] **Step 4: Create the three-entry fixture**

Create `src/test/resources/bakeoff/exemplars.jsonl`. One JSON object per line, no trailing newline issues — a blank final line is tolerated by the loader:

```
{"id":"ex-001","text":"What is the capital of France?","label":"SIMPLE","bucket":"factual","rationale":"single fact lookup"}
{"id":"ex-002","text":"Prove that the halting problem is undecidable, then explain why the diagonalisation argument does not also rule out partial decision procedures.","label":"COMPLEX","bucket":"short-but-hard","rationale":"terse but requires a full proof and a distinction"}
{"id":"ex-003","text":"Summarise the following log:\n2026-08-24T10:00:01Z INFO started\n2026-08-24T10:00:02Z INFO ready","label":"SIMPLE","bucket":"long-but-trivial","rationale":"mechanical summarisation of trivial input"}
```

- [ ] **Step 5: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t1.log 2>&1; echo "exit=$?"
```

Expected: FAIL — `BakeoffPrompt` and `BakeoffCorpus` do not exist, so compilation fails. Confirm with `grep -n "cannot find symbol" /tmp/bakeoff-t1.log`.

- [ ] **Step 6: Write `BakeoffPrompt`**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

/**
 * One labelled entry of the bake-off corpus. {@code toolCount} and {@code maxTokens} are optional and exist so the
 * corpus can express the multi-tool-but-trivial and large-output buckets, which the deterministic baseline reads.
 *
 * @version ee
 */
public record BakeoffPrompt(
    String id, String text, String label, Integer toolCount, Integer maxTokens, String bucket, String rationale) {

    public static final String COMPLEX = "COMPLEX";
    public static final String SIMPLE = "SIMPLE";

    public boolean isComplex() {
        return COMPLEX.equals(label);
    }
}
```

- [ ] **Step 7: Write `BakeoffCorpus`**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads a JSONL bake-off corpus from module test resources and turns entries into gateway chat completion requests.
 *
 * @version ee
 */
public final class BakeoffCorpus {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .build();

    private static final String RESOURCE_PREFIX = "/bakeoff/";

    private BakeoffCorpus() {
    }

    public static List<BakeoffPrompt> load(String resourceName) {
        List<BakeoffPrompt> prompts = new ArrayList<>();

        try (InputStream inputStream = BakeoffCorpus.class.getResourceAsStream(RESOURCE_PREFIX + resourceName)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Corpus resource not found: " + resourceName);
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();

                    if (trimmed.isEmpty()) {
                        continue;
                    }

                    prompts.add(OBJECT_MAPPER.readValue(trimmed, BakeoffPrompt.class));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read corpus " + resourceName, exception);
        }

        return prompts;
    }

    public static AiGatewayChatCompletionRequest toRequest(BakeoffPrompt prompt) {
        List<AiGatewayChatMessage> messages = List.of(
            new AiGatewayChatMessage(AiGatewayChatRole.USER, prompt.text()));

        List<AiGatewayTool> tools = null;

        Integer toolCount = prompt.toolCount();

        if (toolCount != null && toolCount > 0) {
            tools = new ArrayList<>();

            for (int index = 0; index < toolCount; index++) {
                tools.add(
                    new AiGatewayTool(
                        "function",
                        new AiGatewayTool.AiGatewayToolFunction("tool" + index, "description", Map.of())));
            }
        }

        return new AiGatewayChatCompletionRequest(
            "bakeoff", messages, null, prompt.maxTokens(), null, false, null, null, null, tools);
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t1.log 2>&1; echo "exit=$?"
```

Expected: exit=0. If `tools.jackson.databind.json.JsonMapper` does not resolve, the project's Jackson 3 migration has not reached this coordinate — fall back to `com.fasterxml.jackson.databind.ObjectMapper` with `testImplementation("com.fasterxml.jackson.core:jackson-databind")` and adjust the two imports. Note which one you used in the commit message.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add settings.gradle.kts server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add scorer bake-off module skeleton and corpus loader"
```

---

## Task 2: The corpus

This task is corpus authorship, not coding. It is separated because a reviewer should be able to reject
the corpus — the labels decide the experiment — without rejecting the loader.

**Files:**
- Modify: `.../src/test/resources/bakeoff/exemplars.jsonl` (grow from 3 to ~40)
- Create: `.../src/test/resources/bakeoff/adversarial.jsonl` (~60)
- Create: `.../src/test/resources/bakeoff/rubric.md`
- Test: `.../src/test/java/com/bytechef/ee/platform/ai/gateway/bakeoff/BakeoffCorpusShapeTest.java`

**Interfaces:**
- Consumes: `BakeoffCorpus.load`, `BakeoffPrompt` from Task 1.
- Produces: two corpus resources with the guarantees the shape test asserts — disjoint ids, balanced labels, every §5.2 bucket populated.

- [ ] **Step 1: Write the rubric**

Create `src/test/resources/bakeoff/rubric.md`:

```markdown
# Bake-off labelling rubric

A prompt is **COMPLEX** when a competent answer requires at least one of:

- multi-step reasoning whose intermediate steps are not stated in the prompt;
- synthesis across several distinct sources or constraints held simultaneously;
- generating a non-trivial artifact (a proof, a design, a program of more than a few lines);
- resolving genuine ambiguity in the request before answering.

A prompt is **SIMPLE** when a competent answer is:

- a lookup, a restatement, a translation, a format conversion, or a mechanical summary;
- a classification into stated categories;
- an extraction of values that are literally present in the input.

Length, presence of code fences, tool count and requested output size are explicitly **not** criteria.
They are the signals the baseline already uses, and the point of the corpus is to be able to disagree
with them.

Ties go to SIMPLE. If two labellers would plausibly disagree, the entry does not belong in
`adversarial.jsonl`; put it in `exemplars.jsonl` or drop it.
```

- [ ] **Step 2: Grow `exemplars.jsonl` to ~40 entries**

Keep the three existing entries. Add ~37 more, targeting 20 SIMPLE / 20 COMPLEX. These are ordinary
prompts, **not** adversarial ones — this is the set both candidates learn from, and training on
adversarial cases would leak the evaluation set. Draw from ordinary gateway traffic shapes: extraction,
classification, short factual questions, translation, format conversion on the SIMPLE side; multi-step
debugging, architecture questions, proofs, long-context synthesis, ambiguous requirements on the
COMPLEX side.

Format, one object per line, `toolCount`/`maxTokens` omitted unless meaningful:

```
{"id":"ex-004","text":"Translate 'good morning' into Japanese.","label":"SIMPLE","bucket":"translation","rationale":"direct lookup"}
{"id":"ex-005","text":"Our checkout service returns 502 for about 1% of requests, only between 09:00 and 09:15 UTC, and only for customers in the EU region. Walk me through how you would isolate the cause.","label":"COMPLEX","bucket":"debugging","rationale":"multi-step causal reasoning over several correlated constraints"}
```

- [ ] **Step 3: Write `adversarial.jsonl` with ~60 entries**

Ids `adv-001` onward — **disjoint from the exemplar ids**. Target counts per bucket, roughly balanced
between labels within each bucket:

| `bucket` | Count | What it is |
|---|---|---|
| `short-but-hard` | 10 | Terse prompts requiring deep reasoning |
| `long-but-trivial` | 10 | Large pasted input, mechanical request |
| `code-shaped-but-simple` | 8 | Fenced code, trivial question about it |
| `prose-but-complex` | 10 | Hard reasoning, no structural markers |
| `multi-tool-but-trivial` | 8 | High `toolCount`, one-line request |
| `non-english` | 8 | Both labels, at least three languages |
| `degenerate` | 6 | Empty text, 100k characters, punctuation only |

Worked examples, one per bucket:

```
{"id":"adv-001","text":"Is P equal to NP?","label":"COMPLEX","bucket":"short-but-hard","rationale":"17 characters, open problem"}
{"id":"adv-011","text":"Extract every email address from the text below.\n<50000 characters of pasted invoice text>","label":"SIMPLE","bucket":"long-but-trivial","maxTokens":4000,"rationale":"huge input, pure extraction"}
{"id":"adv-021","text":"```python\ndef f(x):\n    return x + 1\n```\nWhat language is this?","label":"SIMPLE","bucket":"code-shaped-but-simple","rationale":"code fence, trivial question"}
{"id":"adv-029","text":"If every member of a committee must approve, and approval is revocable at any time before the vote closes, and the vote closes when the last approval arrives, can the vote ever close? Explain your reasoning.","label":"COMPLEX","bucket":"prose-but-complex","rationale":"self-referential condition, no structural markers"}
{"id":"adv-039","text":"What time is it in Tokyo?","label":"SIMPLE","bucket":"multi-tool-but-trivial","toolCount":8,"rationale":"eight tools declared, one lookup"}
{"id":"adv-047","text":"Wie viele Bundeslaender hat Deutschland?","label":"SIMPLE","bucket":"non-english","rationale":"German, single fact"}
{"id":"adv-055","text":"","label":"SIMPLE","bucket":"degenerate","rationale":"empty content must not throw"}
```

For `adv-011` and any other entry needing bulk text, generate the filler at authoring time and paste the
real characters into the JSONL — the loader does no expansion, and a literal `<50000 characters…>`
placeholder would make the entry meaningless.

- [ ] **Step 4: Write the failing shape test**

Create `BakeoffCorpusShapeTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the corpus invariants the experiment design depends on. A corpus that drifts out of balance, or that
 * overlaps between training and evaluation, silently invalidates every number the bake-off produces.
 *
 * @version ee
 */
class BakeoffCorpusShapeTest {

    private static final List<BakeoffPrompt> ADVERSARIAL = BakeoffCorpus.load("adversarial.jsonl");
    private static final List<BakeoffPrompt> EXEMPLARS = BakeoffCorpus.load("exemplars.jsonl");

    private static Set<String> idsOf(List<BakeoffPrompt> prompts) {
        return prompts.stream()
            .map(BakeoffPrompt::id)
            .collect(Collectors.toSet());
    }

    private static long complexCount(List<BakeoffPrompt> prompts) {
        return prompts.stream()
            .filter(BakeoffPrompt::isComplex)
            .count();
    }

    @Test
    void testTrainingAndEvaluationSetsAreDisjoint() {
        Set<String> shared = idsOf(EXEMPLARS);

        shared.retainAll(idsOf(ADVERSARIAL));

        assertThat(shared).isEmpty();
    }

    @Test
    void testIdsAreUnique() {
        assertThat(idsOf(EXEMPLARS)).hasSameSizeAs(EXEMPLARS);
        assertThat(idsOf(ADVERSARIAL)).hasSameSizeAs(ADVERSARIAL);
    }

    @Test
    void testExemplarSetIsBalanced() {
        assertThat(EXEMPLARS).hasSizeGreaterThanOrEqualTo(36);

        long complex = complexCount(EXEMPLARS);

        assertThat(complex).isBetween(EXEMPLARS.size() * 2L / 5, EXEMPLARS.size() * 3L / 5);
    }

    @Test
    void testAdversarialSetIsBalanced() {
        assertThat(ADVERSARIAL).hasSizeGreaterThanOrEqualTo(55);

        long complex = complexCount(ADVERSARIAL);

        assertThat(complex).isBetween(ADVERSARIAL.size() * 2L / 5, ADVERSARIAL.size() * 3L / 5);
    }

    @Test
    void testEveryAdversarialBucketIsPopulated() {
        Set<String> buckets = ADVERSARIAL.stream()
            .map(BakeoffPrompt::bucket)
            .collect(Collectors.toSet());

        assertThat(buckets).contains(
            "short-but-hard", "long-but-trivial", "code-shaped-but-simple", "prose-but-complex",
            "multi-tool-but-trivial", "non-english", "degenerate");
    }

    @Test
    void testEveryEntryCarriesALabelAndARationale() {
        for (BakeoffPrompt prompt : ADVERSARIAL) {
            assertThat(prompt.label()).isIn(BakeoffPrompt.SIMPLE, BakeoffPrompt.COMPLEX);
            assertThat(prompt.rationale()).isNotBlank();
        }
    }
}
```

- [ ] **Step 5: Run the shape test**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t2.log 2>&1; echo "exit=$?"
```

Expected: PASS. If a balance or bucket assertion fails, fix the **corpus**, not the assertion — the
assertions encode §5 of the spec.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add labelled corpus, rubric and corpus shape guards"
```

---

## Task 3: Candidate C — the OpenNLP scorer

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `.../platform-ai-gateway-scorer-bakeoff/build.gradle.kts`
- Create: `.../bakeoff/OpenNlpPromptComplexityScorer.java`
- Test: `.../bakeoff/OpenNlpPromptComplexityScorerTest.java`

**Interfaces:**
- Consumes: `BakeoffPrompt`, `BakeoffCorpus` (Task 1); `PromptComplexityScorer` and `AiGatewayChatCompletionRequest` from `platform-ai-gateway-api`.
- Produces: `new OpenNlpPromptComplexityScorer(List<BakeoffPrompt> exemplars)` — trains in the constructor; `score(AiGatewayChatCompletionRequest)` → `double` in `[0, 1]`.

- [ ] **Step 1: Add the OpenNLP version and library to the version catalog**

In `gradle/libs.versions.toml`, add to `[versions]` in alphabetical position:

```toml
opennlp = "2.5.11"
```

and to `[libraries]` in alphabetical position:

```toml
org-apache-opennlp-opennlp-tools = { module = "org.apache.opennlp:opennlp-tools", version.ref = "opennlp" }
```

- [ ] **Step 2: Add the dependency to the module**

In `platform-ai-gateway-scorer-bakeoff/build.gradle.kts`, add inside `dependencies`:

```kotlin
    testImplementation(rootProject.libs.org.apache.opennlp.opennlp.tools)
```

- [ ] **Step 3: Write the failing test**

Create `OpenNlpPromptComplexityScorerTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class OpenNlpPromptComplexityScorerTest {

    private static final List<BakeoffPrompt> TRAINING = List.of(
        new BakeoffPrompt("t-1", "What is the capital of France?", "SIMPLE", null, null, "factual", "lookup"),
        new BakeoffPrompt("t-2", "Translate hello into Spanish.", "SIMPLE", null, null, "translation", "lookup"),
        new BakeoffPrompt("t-3", "List the days of the week.", "SIMPLE", null, null, "factual", "lookup"),
        new BakeoffPrompt(
            "t-4", "Prove that the square root of two is irrational and explain the contradiction.", "COMPLEX",
            null, null, "proof", "requires a proof"),
        new BakeoffPrompt(
            "t-5", "Design a fault tolerant queue and justify the trade offs you make.", "COMPLEX", null, null,
            "design", "requires a design and justification"),
        new BakeoffPrompt(
            "t-6", "Explain why the diagonalisation argument establishes undecidability.", "COMPLEX", null, null,
            "proof", "requires reasoning"));

    private final OpenNlpPromptComplexityScorer scorer = new OpenNlpPromptComplexityScorer(TRAINING);

    private static AiGatewayChatCompletionRequest request(String text) {
        return BakeoffCorpus.toRequest(new BakeoffPrompt("q", text, "SIMPLE", null, null, "query", "n/a"));
    }

    @Test
    void testScoreIsWithinUnitInterval() {
        assertThat(scorer.score(request("What is the capital of Spain?"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("Prove that there are infinitely many primes."))).isBetween(0.0, 1.0);
    }

    @Test
    void testComplexTrainingVocabularyScoresAboveSimple() {
        double complexScore = scorer.score(request("Prove that the contradiction establishes irrationality."));
        double simpleScore = scorer.score(request("What is the capital of Spain?"));

        assertThat(complexScore).isGreaterThan(simpleScore);
    }

    @Test
    void testEmptyPromptDoesNotThrow() {
        assertThat(scorer.score(request(""))).isBetween(0.0, 1.0);
    }

    @Test
    void testNullContentDoesNotThrow() {
        AiGatewayChatCompletionRequest request = BakeoffCorpus.toRequest(
            new BakeoffPrompt("q", "", "SIMPLE", null, null, "query", "n/a"));

        assertThat(scorer.score(request)).isBetween(0.0, 1.0);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t3.log 2>&1; echo "exit=$?"
```

Expected: FAIL, `cannot find symbol: class OpenNlpPromptComplexityScorer`.

- [ ] **Step 5: Write the scorer**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.TrainingParameters;

/**
 * Bake-off candidate C. Trains an OpenNLP maximum-entropy document categoriser over the labelled exemplar set at
 * construction time and reports the model's probability of the COMPLEX category as the complexity score. Training a
 * few dozen short documents takes well under a second, which is why no model artifact is shipped: the exemplar set
 * stays the single source of truth shared with candidate B.
 *
 * @version ee
 */
public class OpenNlpPromptComplexityScorer implements PromptComplexityScorer {

    private static final int CUTOFF = 1;
    private static final int ITERATIONS = 100;
    private static final String LANGUAGE = "en";

    private final DocumentCategorizerME categorizer;
    private final int complexIndex;

    public OpenNlpPromptComplexityScorer(List<BakeoffPrompt> exemplars) {
        List<DocumentSample> samples = new ArrayList<>();

        for (BakeoffPrompt exemplar : exemplars) {
            samples.add(new DocumentSample(exemplar.label(), tokenize(exemplar.text())));
        }

        TrainingParameters trainingParameters = new TrainingParameters();

        trainingParameters.put(TrainingParameters.CUTOFF_PARAM, CUTOFF);
        trainingParameters.put(TrainingParameters.ITERATIONS_PARAM, ITERATIONS);

        try (ObjectStream<DocumentSample> sampleStream = ObjectStreamUtils.createObjectStream(samples)) {
            DoccatModel model = DocumentCategorizerME.train(
                LANGUAGE, sampleStream, trainingParameters, new DoccatFactory());

            this.categorizer = new DocumentCategorizerME(model);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to train the OpenNLP document categoriser", exception);
        }

        this.complexIndex = categorizer.getIndex(BakeoffPrompt.COMPLEX);
    }

    @Override
    public double score(AiGatewayChatCompletionRequest request) {
        String[] tokens = tokenize(concatenateContent(request));

        if (tokens.length == 0) {
            return 0.0;
        }

        double[] outcomes = categorizer.categorize(tokens);

        return Math.max(0.0, Math.min(outcomes[complexIndex], 1.0));
    }

    private static String concatenateContent(AiGatewayChatCompletionRequest request) {
        StringBuilder builder = new StringBuilder();

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null) {
                builder.append(content)
                    .append(' ');
            }
        }

        return builder.toString();
    }

    private static String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }

        return SimpleTokenizer.INSTANCE.tokenize(text);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t3.log 2>&1; echo "exit=$?"
```

Expected: exit=0. The whole OpenNLP surface used here was verified against the 2.5.11 jar on 2026-08-25 — `DocumentCategorizerME.train(String, ObjectStream, TrainingParameters, DoccatFactory)`, `getIndex(String)`, `categorize(String[])`, `ObjectStreamUtils.createObjectStream(Collection)`, `DocumentSample(String, String[])`, `SimpleTokenizer.INSTANCE.tokenize(String)`, and `ObjectStream extends AutoCloseable` for the try-with-resources. If something does not compile, the version resolved is not 2.5.11 — check that before rewriting the code.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add gradle/libs.versions.toml server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add OpenNLP maxent prompt complexity scorer"
```

---

## Task 4: Candidates B and D — the embedding centroid scorer

**Files:**
- Modify: `.../platform-ai-gateway-scorer-bakeoff/build.gradle.kts`
- Create: `.../bakeoff/EmbeddingCentroidPromptComplexityScorer.java`
- Test: `.../bakeoff/EmbeddingCentroidPromptComplexityScorerTest.java`

**Interfaces:**
- Consumes: `BakeoffPrompt`, `BakeoffCorpus`; `org.springframework.ai.embedding.EmbeddingModel`.
- Produces: `new EmbeddingCentroidPromptComplexityScorer(EmbeddingModel, List<BakeoffPrompt>)`; `score(AiGatewayChatCompletionRequest)` → `double` in `[0, 1]`.

The constructor takes the `EmbeddingModel` rather than building one. This is what makes candidates B and D **the same class**: B injects an in-process `TransformersEmbeddingModel`, D injects a remote OpenAI-compatible one. It also means the unit test injects a stub, so CI never downloads a 90 MB model and never needs an API key. Task 6's runner is the only place either real model is constructed.

Do not write a second scorer class for D. If you find yourself doing so, the constructor injection has been dropped somewhere — fix that instead.

- [ ] **Step 1: Add the dependency with exclusions**

In `platform-ai-gateway-scorer-bakeoff/build.gradle.kts`, add inside `dependencies`:

```kotlin
    testImplementation("org.springframework.ai:spring-ai-transformers") {
        exclude(group = "ai.djl.pytorch", module = "pytorch-engine")
        exclude(group = "ai.djl", module = "model-zoo")
    }
```

- [ ] **Step 2: Write the failing test**

Create `EmbeddingCentroidPromptComplexityScorerTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Uses a stub embedding model with hand-placed vectors so the mapping from cosine geometry to the 0-1 contract is
 * asserted exactly, and so CI never downloads an ONNX model.
 *
 * @version ee
 */
class EmbeddingCentroidPromptComplexityScorerTest {

    private static final float[] COMPLEX_VECTOR = {
        0.0f, 1.0f
    };
    private static final float[] SIMPLE_VECTOR = {
        1.0f, 0.0f
    };

    private static final List<BakeoffPrompt> TRAINING = List.of(
        new BakeoffPrompt("t-1", "simple one", "SIMPLE", null, null, "b", "r"),
        new BakeoffPrompt("t-2", "simple two", "SIMPLE", null, null, "b", "r"),
        new BakeoffPrompt("t-3", "complex one", "COMPLEX", null, null, "b", "r"),
        new BakeoffPrompt("t-4", "complex two", "COMPLEX", null, null, "b", "r"));

    private static final Map<String, float[]> VECTORS = Map.of(
        "complex one", COMPLEX_VECTOR,
        "complex two", COMPLEX_VECTOR,
        "simple one", SIMPLE_VECTOR,
        "simple two", SIMPLE_VECTOR);

    private final EmbeddingModel embeddingModel = new StubEmbeddingModel();

    private final EmbeddingCentroidPromptComplexityScorer scorer =
        new EmbeddingCentroidPromptComplexityScorer(embeddingModel, TRAINING);

    private static AiGatewayChatCompletionRequest request(String text) {
        return BakeoffCorpus.toRequest(new BakeoffPrompt("q", text, "SIMPLE", null, null, "query", "n/a"));
    }

    @Test
    void testPromptOnTheComplexCentroidScoresOne() {
        assertThat(scorer.score(request("complex one"))).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testPromptOnTheSimpleCentroidScoresZero() {
        assertThat(scorer.score(request("simple one"))).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testEquidistantPromptScoresAHalf() {
        assertThat(scorer.score(request("midpoint"))).isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testScoreIsAlwaysWithinUnitInterval() {
        assertThat(scorer.score(request("complex one"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("simple one"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("midpoint"))).isBetween(0.0, 1.0);
    }

    @Test
    void testEmptyPromptDoesNotThrow() {
        assertThat(scorer.score(request(""))).isBetween(0.0, 1.0);
    }

    @Test
    void testNullContentDoesNotThrow() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "bakeoff", List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, null)), null, null, null,
            false, null, null, null, null);

        assertThat(scorer.score(request)).isBetween(0.0, 1.0);
    }

    /**
     * Returns the placed vector for known training text, and the equidistant vector for anything else.
     */
    private static final class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public float[] embed(String text) {
            float[] placed = VECTORS.get(text.trim());

            if (placed != null) {
                return placed.clone();
            }

            float halfRootTwo = (float) (1.0 / Math.sqrt(2.0));

            return new float[] {
                halfRootTwo, halfRootTwo
            };
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("The bake-off stub only implements embed(String)");
        }
    }
}
```

`testNullContentDoesNotThrow` builds the request **directly** rather than through `BakeoffCorpus.toRequest`, because that helper always passes a non-null string — routing through it would silently make this a duplicate of the empty-prompt test. `AiGatewayChatMessage` permits null content when `contentBlocks` is also null; its only both-set guard is `content != null && contentBlocks != null && !contentBlocks.isEmpty()`. This is spec §6.4's null-content degenerate case, and it is the exact defect the Task 3 review caught in the sibling test. Add `AiGatewayChatMessage` and `AiGatewayChatRole` to the imports.

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t4.log 2>&1; echo "exit=$?"
```

Expected: FAIL, `cannot find symbol: class EmbeddingCentroidPromptComplexityScorer`. If `EmbeddingModel` in Spring AI 2.0.1 declares additional abstract methods, implement them on the stub by delegating to `embed(String)` or throwing `UnsupportedOperationException`, and note which in the commit message.

- [ ] **Step 4: Write the scorer**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Bake-off candidate B, reproducing Merge Gateway's technique: the labelled exemplars are embedded and averaged into
 * a SIMPLE centroid and a COMPLEX centroid, and a prompt is scored by where it falls between them.
 *
 * <p>
 * The cosine difference ranges over [-2, 2], so the mapping to the 0-1 contract is {@code (1 + difference) / 2}. It is
 * monotonic in "closer to the complex centroid", which is the only property {@code IntelligentRoutingStrategy} relies
 * on.
 *
 * @version ee
 */
public class EmbeddingCentroidPromptComplexityScorer implements PromptComplexityScorer {

    private final float[] complexCentroid;
    private final EmbeddingModel embeddingModel;
    private final float[] simpleCentroid;

    public EmbeddingCentroidPromptComplexityScorer(
        EmbeddingModel embeddingModel, List<BakeoffPrompt> exemplars) {

        this.embeddingModel = embeddingModel;

        this.complexCentroid = centroidOf(embeddingModel, exemplars, true);
        this.simpleCentroid = centroidOf(embeddingModel, exemplars, false);
    }

    @Override
    public double score(AiGatewayChatCompletionRequest request) {
        String content = concatenateContent(request);

        if (content.isBlank()) {
            return 0.0;
        }

        float[] embedding = embeddingModel.embed(content);

        double difference = cosineSimilarity(embedding, complexCentroid)
            - cosineSimilarity(embedding, simpleCentroid);

        return Math.max(0.0, Math.min((1.0 + difference) / 2.0, 1.0));
    }

    private static float[] centroidOf(
        EmbeddingModel embeddingModel, List<BakeoffPrompt> exemplars, boolean complex) {

        float[] sum = null;
        int count = 0;

        for (BakeoffPrompt exemplar : exemplars) {
            if (exemplar.isComplex() != complex) {
                continue;
            }

            float[] embedding = embeddingModel.embed(exemplar.text());

            if (sum == null) {
                sum = new float[embedding.length];
            }

            for (int index = 0; index < embedding.length; index++) {
                sum[index] += embedding[index];
            }

            count++;
        }

        if (sum == null || count == 0) {
            throw new IllegalArgumentException(
                "The exemplar set contains no " + (complex ? "COMPLEX" : "SIMPLE") + " entries");
        }

        for (int index = 0; index < sum.length; index++) {
            sum[index] /= count;
        }

        return sum;
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        double dotProduct = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String concatenateContent(AiGatewayChatCompletionRequest request) {
        StringBuilder builder = new StringBuilder();

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null) {
                builder.append(content)
                    .append(' ');
            }
        }

        return builder.toString();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t4.log 2>&1; echo "exit=$?"
```

Expected: exit=0.

- [ ] **Step 6: Verify the DJL exclusions took effect**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:dependencies --configuration testRuntimeClasspath > /tmp/bakeoff-deps.log 2>&1; echo "exit=$?"
grep -c "pytorch-engine\|model-zoo" /tmp/bakeoff-deps.log
```

Expected: exit=0 and a count of `0`. A non-zero count means the exclusions are misspelled — fix before committing, because leaving `pytorch-engine` on the classpath risks a ~200 MB native download during the run.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add embedding centroid prompt complexity scorer"
```

---

## Task 5: Metrics

**Files:**
- Create: `.../bakeoff/ClassificationMetrics.java`
- Create: `.../bakeoff/RoutingOutcomeMetrics.java`
- Test: `.../bakeoff/ClassificationMetricsTest.java`
- Test: `.../bakeoff/RoutingOutcomeMetricsTest.java`

**Interfaces:**
- Consumes: `BakeoffPrompt`; `AiGatewayModelTier` from `platform-ai-gateway-api`.
- Produces:
  - `record ClassificationResult(double accuracy, double precision, double recall, double areaUnderCurve, int count)`
  - `ClassificationMetrics.evaluate(List<BakeoffPrompt> prompts, double[] scores, double threshold)` → `ClassificationResult`
  - `RoutingOutcomeMetrics.tierDistribution(double[] scores, AiGatewayRoutingStrategyType axis, List<AiGatewayModelTier> presentTiers)` → `Map<AiGatewayModelTier, Integer>`

`tierDistribution` deliberately re-implements the index arithmetic of `IntelligentRoutingStrategy` rather
than invoking it, because that class is package-private and needs deployments and an
`AiGatewayRoutingContext` to run. Keep the two in sync by eye; the unit test below pins the transform.

- [ ] **Step 1: Write the failing classification metrics test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class ClassificationMetricsTest {

    private static final Offset<Double> TOLERANCE = Offset.offset(1e-6);

    private static BakeoffPrompt prompt(String id, String label) {
        return new BakeoffPrompt(id, "text", label, null, null, "bucket", "rationale");
    }

    @Test
    void testPerfectSeparationScoresOne() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "SIMPLE"), prompt("b", "SIMPLE"), prompt("c", "COMPLEX"), prompt("d", "COMPLEX"));

        double[] scores = {
            0.1, 0.2, 0.8, 0.9
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.accuracy()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.precision()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.recall()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.areaUnderCurve()).isEqualTo(1.0, TOLERANCE);
    }

    @Test
    void testInvertedSeparationScoresZeroAreaUnderCurve() {
        List<BakeoffPrompt> prompts = List.of(prompt("a", "SIMPLE"), prompt("b", "COMPLEX"));

        double[] scores = {
            0.9, 0.1
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.areaUnderCurve()).isEqualTo(0.0, TOLERANCE);
        assertThat(result.accuracy()).isEqualTo(0.0, TOLERANCE);
    }

    @Test
    void testAllTiedScoresGiveHalfAreaUnderCurve() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "SIMPLE"), prompt("b", "COMPLEX"), prompt("c", "SIMPLE"), prompt("d", "COMPLEX"));

        double[] scores = {
            0.5, 0.5, 0.5, 0.5
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.areaUnderCurve()).isEqualTo(0.5, TOLERANCE);
    }

    @Test
    void testPrecisionAndRecallDifferWhenPredictionsAreSkewed() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "COMPLEX"), prompt("b", "COMPLEX"), prompt("c", "SIMPLE"), prompt("d", "SIMPLE"));

        double[] scores = {
            0.9, 0.1, 0.9, 0.1
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.precision()).isEqualTo(0.5, TOLERANCE);
        assertThat(result.recall()).isEqualTo(0.5, TOLERANCE);
        assertThat(result.accuracy()).isEqualTo(0.5, TOLERANCE);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t5.log 2>&1; echo "exit=$?"
```

Expected: FAIL, `cannot find symbol: class ClassificationMetrics`.

- [ ] **Step 3: Write `ClassificationResult` and `ClassificationMetrics`**

Create `ClassificationResult.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

/**
 * @version ee
 */
public record ClassificationResult(
    double accuracy, double precision, double recall, double areaUnderCurve, int count) {
}
```

Create `ClassificationMetrics.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Accuracy, precision, recall and rank based ROC-AUC over a set of scored prompts. The area under the curve is
 * computed by the Mann-Whitney U identity using average ranks, so tied scores contribute 0.5 rather than being
 * silently ordered by input position.
 *
 * @version ee
 */
public final class ClassificationMetrics {

    private ClassificationMetrics() {
    }

    public static ClassificationResult evaluate(
        List<BakeoffPrompt> prompts, double[] scores, double threshold) {

        if (prompts.size() != scores.length) {
            throw new IllegalArgumentException(
                "prompts and scores must be the same length: " + prompts.size() + " vs " + scores.length);
        }

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        int correct = 0;

        for (int index = 0; index < scores.length; index++) {
            boolean actual = prompts.get(index)
                .isComplex();
            boolean predicted = scores[index] >= threshold;

            if (predicted == actual) {
                correct++;
            }

            if (predicted && actual) {
                truePositives++;
            } else if (predicted) {
                falsePositives++;
            } else if (actual) {
                falseNegatives++;
            }
        }

        double precision = truePositives + falsePositives == 0
            ? 0.0
            : (double) truePositives / (truePositives + falsePositives);
        double recall = truePositives + falseNegatives == 0
            ? 0.0
            : (double) truePositives / (truePositives + falseNegatives);

        return new ClassificationResult(
            (double) correct / scores.length, precision, recall, areaUnderCurve(prompts, scores), scores.length);
    }

    private static double areaUnderCurve(List<BakeoffPrompt> prompts, double[] scores) {
        List<Integer> order = new ArrayList<>();

        for (int index = 0; index < scores.length; index++) {
            order.add(index);
        }

        order.sort(Comparator.comparingDouble(index -> scores[index]));

        double[] ranks = new double[scores.length];

        int position = 0;

        while (position < order.size()) {
            int end = position;

            while (end + 1 < order.size() && scores[order.get(end + 1)] == scores[order.get(position)]) {
                end++;
            }

            double averageRank = (position + end + 2) / 2.0;

            for (int index = position; index <= end; index++) {
                ranks[order.get(index)] = averageRank;
            }

            position = end + 1;
        }

        double positiveRankSum = 0.0;
        int positives = 0;

        for (int index = 0; index < scores.length; index++) {
            if (prompts.get(index)
                .isComplex()) {

                positiveRankSum += ranks[index];
                positives++;
            }
        }

        int negatives = scores.length - positives;

        if (positives == 0 || negatives == 0) {
            return 0.5;
        }

        return (positiveRankSum - positives * (positives + 1) / 2.0) / ((double) positives * negatives);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t5.log 2>&1; echo "exit=$?"
```

Expected: exit=0.

- [ ] **Step 5: Write the failing routing outcome test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class RoutingOutcomeMetricsTest {

    private static final List<AiGatewayModelTier> TWO_TIERS = List.of(
        AiGatewayModelTier.BASIC, AiGatewayModelTier.FRONTIER);

    @Test
    void testCostAxisSkewsTowardTheCheapTier() {
        double[] scores = {
            0.1, 0.3, 0.5, 0.6
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_COST, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.BASIC)).isEqualTo(4);
    }

    @Test
    void testQualityAxisSkewsTowardTheCapableTier() {
        double[] scores = {
            0.4, 0.5, 0.7, 0.9
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_QUALITY, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.FRONTIER)).isEqualTo(4);
    }

    @Test
    void testBalancedAxisSplitsAtTheMidpoint() {
        double[] scores = {
            0.1, 0.4, 0.6, 0.9
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.BASIC)).isEqualTo(2);
        assertThat(distribution.get(AiGatewayModelTier.FRONTIER)).isEqualTo(2);
    }

    @Test
    void testEveryPresentTierAppearsInTheDistribution() {
        double[] scores = {
            0.5
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, TWO_TIERS);

        assertThat(distribution).containsKeys(AiGatewayModelTier.BASIC, AiGatewayModelTier.FRONTIER);
    }
}
```

- [ ] **Step 6: Run to verify it fails, then write `RoutingOutcomeMetrics`**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the tier selection arithmetic of {@code IntelligentRoutingStrategy} so a set of scores can be turned
 * into a routing distribution without constructing deployments and a routing context. The transforms below must stay
 * identical to that class; the unit tests pin them.
 *
 * @version ee
 */
public final class RoutingOutcomeMetrics {

    private RoutingOutcomeMetrics() {
    }

    public static Map<AiGatewayModelTier, Integer> tierDistribution(
        double[] scores, AiGatewayRoutingStrategyType axis, List<AiGatewayModelTier> presentTiers) {

        Map<AiGatewayModelTier, Integer> distribution = new EnumMap<>(AiGatewayModelTier.class);

        for (AiGatewayModelTier tier : presentTiers) {
            distribution.put(tier, 0);
        }

        for (double score : scores) {
            AiGatewayModelTier tier = presentTiers.get(indexOf(score, axis, presentTiers.size()));

            distribution.merge(tier, 1, Integer::sum);
        }

        return distribution;
    }

    private static int indexOf(double score, AiGatewayRoutingStrategyType axis, int tierCount) {
        double effective = transform(score, axis);

        int index = (int) Math.floor(effective * tierCount);

        return Math.max(0, Math.min(index, tierCount - 1));
    }

    private static double transform(double score, AiGatewayRoutingStrategyType axis) {
        return switch (axis) {
            case INTELLIGENT_COST -> score * score;
            case INTELLIGENT_BALANCED -> score;
            case INTELLIGENT_QUALITY -> 1.0 - (1.0 - score) * (1.0 - score);
            default -> throw new IllegalArgumentException("Not an intelligent routing axis: " + axis);
        };
    }
}
```

- [ ] **Step 7: Run to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t5.log 2>&1; echo "exit=$?"
```

Expected: exit=0.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add classification and routing outcome metrics"
```

---

## Task 6: The runner and its Gradle task

**Files:**
- Modify: `.../platform-ai-gateway-scorer-bakeoff/build.gradle.kts`
- Create: `.../bakeoff/BakeoffReport.java`
- Create: `.../bakeoff/PromptComplexityScorerBakeoff.java`
- Test: `.../bakeoff/BakeoffReportTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5; `DeterministicPromptComplexityScorer` from `platform-ai-gateway-service`; `TransformersEmbeddingModel` from `spring-ai-transformers`; `OpenAiEmbeddingModel` from `spring-ai-openai` for candidate D.
- Produces: a `bakeoff` Gradle task writing `build/bakeoff/report.md` and `build/bakeoff/scores.csv`, covering the baseline, B, C, and D when a key is available.

Add both of these to the module build file in Step 1:

```kotlin
    testImplementation("com.openai:openai-java-client-okhttp")
    testImplementation("org.springframework.ai:spring-ai-openai")
```

`platform-ai-gateway-service` declares both as `implementation`, which does **not** put them on a consumer's compile classpath — hence the explicit re-declaration. Versions come from the BOM and the root `dependencyManagement` block.

The construction below mirrors `AiGatewayEmbeddingModelFactoryImpl.createOpenAiCompatibleEmbeddingModel` in this repo, which is where the two-line `OpenAIOkHttpClient` → `OpenAiEmbeddingModel` shape is verified. Do not invent a different constructor; if it does not compile, read that file rather than guessing.

- [ ] **Step 1: Add the Gradle task and exclude the runner from `test`**

Append to `platform-ai-gateway-scorer-bakeoff/build.gradle.kts`:

```kotlin
tasks.test {
    exclude("**/PromptComplexityScorerBakeoff*")
}

tasks.register<Test>("bakeoff") {
    description = "Execute the prompt complexity scorer bake-off and write its report."
    group = "verification"

    useJUnitPlatform()

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(tasks.testClasses)

    include("**/PromptComplexityScorerBakeoff*")

    // The default test task caps the heap at 256m, which cannot hold the ONNX session plus the model.
    jvmArgs("-Xmx2g")

    testLogging {
        events("standardOut", "failed")
        showExceptions = true
    }
}
```

The exclusion is required because Gradle's `Test` task discovers every class in `testClassesDirs`; without it, `./gradlew check` would download the 90 MB model.

- [ ] **Step 2: Write the failing report test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class BakeoffReportTest {

    private static final Map<String, ClassificationResult> RESULTS = Map.of(
        "baseline", new ClassificationResult(0.55, 0.5, 0.6, 0.58, 60),
        "opennlp", new ClassificationResult(0.70, 0.7, 0.7, 0.75, 60));

    @Test
    void testMarkdownContainsARowPerScorer() {
        String markdown = BakeoffReport.toMarkdown(RESULTS, Map.of());

        assertThat(markdown).contains("baseline");
        assertThat(markdown).contains("opennlp");
    }

    @Test
    void testMarkdownFormatsScoresToThreeDecimals() {
        String markdown = BakeoffReport.toMarkdown(RESULTS, Map.of());

        assertThat(markdown).contains("0.550");
    }

    @Test
    void testCsvHasAHeaderAndARowPerPrompt() {
        List<BakeoffPrompt> prompts = List.of(
            new BakeoffPrompt("adv-001", "text", "COMPLEX", null, null, "short-but-hard", "reason"));

        String csv = BakeoffReport.toCsv(prompts, Map.of("baseline", new double[] {
            0.25
        }));

        String[] lines = csv.split("\n");

        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("id,bucket,label");
        assertThat(lines[1]).startsWith("adv-001,short-but-hard,COMPLEX");
    }

    @Test
    void testCsvQuotesFieldsContainingCommas() {
        List<BakeoffPrompt> prompts = List.of(
            new BakeoffPrompt("adv-002", "text", "SIMPLE", null, null, "non-english", "a, b"));

        String csv = BakeoffReport.toCsv(prompts, Map.of("baseline", new double[] {
            0.1
        }));

        assertThat(csv).contains("\"a, b\"");
    }
}
```

- [ ] **Step 3: Run to verify it fails, then write `BakeoffReport`**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders bake-off results as Markdown for the spec's outcome section and as CSV for per-prompt inspection.
 *
 * @version ee
 */
public final class BakeoffReport {

    private BakeoffReport() {
    }

    public static String toMarkdown(
        Map<String, ClassificationResult> results,
        Map<String, Map<AiGatewayModelTier, Integer>> distributions) {

        StringBuilder builder = new StringBuilder();

        builder.append("| scorer | accuracy | precision | recall | AUC | n |\n");
        builder.append("|---|---|---|---|---|---|\n");

        for (Map.Entry<String, ClassificationResult> entry : new TreeMap<>(results).entrySet()) {
            ClassificationResult result = entry.getValue();

            builder.append(String.format(
                "| %s | %.3f | %.3f | %.3f | %.3f | %d |%n",
                entry.getKey(), result.accuracy(), result.precision(), result.recall(), result.areaUnderCurve(),
                result.count()));
        }

        if (!distributions.isEmpty()) {
            builder.append("\n| scorer | tier distribution |\n|---|---|\n");

            for (Map.Entry<String, Map<AiGatewayModelTier, Integer>> entry
                : new TreeMap<>(distributions).entrySet()) {

                builder.append(String.format("| %s | %s |%n", entry.getKey(), entry.getValue()));
            }
        }

        return builder.toString();
    }

    public static String toCsv(List<BakeoffPrompt> prompts, Map<String, double[]> scoresByScorer) {
        Map<String, double[]> ordered = new TreeMap<>(scoresByScorer);

        StringBuilder builder = new StringBuilder("id,bucket,label,rationale");

        for (String scorer : ordered.keySet()) {
            builder.append(',')
                .append(scorer);
        }

        builder.append('\n');

        for (int index = 0; index < prompts.size(); index++) {
            BakeoffPrompt prompt = prompts.get(index);

            builder.append(escape(prompt.id()))
                .append(',')
                .append(escape(prompt.bucket()))
                .append(',')
                .append(escape(prompt.label()))
                .append(',')
                .append(escape(prompt.rationale()));

            for (double[] scores : ordered.values()) {
                builder.append(',')
                    .append(String.format("%.4f", scores[index]));
            }

            builder.append('\n');
        }

        return builder.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
```

- [ ] **Step 4: Run to verify the report test passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:test > /tmp/bakeoff-t6.log 2>&1; echo "exit=$?"
```

Expected: exit=0. `%n` in `String.format` emits the platform line separator; the CSV test splits on `\n`, which holds on this project's Linux and macOS targets.

- [ ] **Step 5: Write the runner**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.routing.DeterministicPromptComplexityScorer;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * The bake-off runner. Excluded from the default test task by the module build file and invoked through the
 * {@code bakeoff} Gradle task; it is a measurement instrument, not a test, and asserts nothing about which scorer
 * wins. Its output is {@code build/bakeoff/report.md} and {@code build/bakeoff/scores.csv}.
 *
 * @version ee
 */
class PromptComplexityScorerBakeoff {

    private static final List<AiGatewayModelTier> PRESENT_TIERS = List.of(
        AiGatewayModelTier.BASIC, AiGatewayModelTier.EFFICIENT, AiGatewayModelTier.STANDARD,
        AiGatewayModelTier.ADVANCED, AiGatewayModelTier.FRONTIER);

    private static final Path OUTPUT_DIRECTORY = Path.of("build", "bakeoff");

    @Test
    void testRunBakeoff() throws IOException {
        List<BakeoffPrompt> exemplars = BakeoffCorpus.load("exemplars.jsonl");
        List<BakeoffPrompt> adversarial = BakeoffCorpus.load("adversarial.jsonl");

        TransformersEmbeddingModel localEmbeddingModel = new TransformersEmbeddingModel();

        localEmbeddingModel.afterPropertiesSet();

        Map<String, PromptComplexityScorer> scorers = new LinkedHashMap<>();

        scorers.put("baseline", new DeterministicPromptComplexityScorer());
        scorers.put("opennlp", new OpenNlpPromptComplexityScorer(exemplars));
        scorers.put(
            "embedding-local", new EmbeddingCentroidPromptComplexityScorer(localEmbeddingModel, exemplars));

        String apiKey = remoteApiKey();

        if (apiKey == null) {
            System.out.println(
                "SKIPPED candidate D (embedding-remote): no bakeoff.embedding.apiKey system property and no "
                    + "BAKEOFF_EMBEDDING_API_KEY environment variable. The report below covers the baseline, "
                    + "candidate B and candidate C only.");
        } else {
            OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

            EmbeddingModel remoteEmbeddingModel = new OpenAiEmbeddingModel(openAiClient);

            scorers.put(
                "embedding-remote",
                new EmbeddingCentroidPromptComplexityScorer(remoteEmbeddingModel, exemplars));
        }

        List<AiGatewayChatCompletionRequest> requests = new ArrayList<>();

        for (BakeoffPrompt prompt : adversarial) {
            requests.add(BakeoffCorpus.toRequest(prompt));
        }

        Map<String, double[]> scoresByScorer = new LinkedHashMap<>();
        Map<String, ClassificationResult> resultsByScorer = new LinkedHashMap<>();
        Map<String, Map<AiGatewayModelTier, Integer>> distributionsByScorer = new LinkedHashMap<>();
        Map<String, Long> latencyByScorer = new LinkedHashMap<>();

        for (Map.Entry<String, PromptComplexityScorer> entry : scorers.entrySet()) {
            PromptComplexityScorer scorer = entry.getValue();

            for (AiGatewayChatCompletionRequest request : requests) {
                scorer.score(request);
            }

            double[] scores = new double[requests.size()];

            long startNanos = System.nanoTime();

            for (int index = 0; index < requests.size(); index++) {
                scores[index] = scorer.score(requests.get(index));
            }

            long elapsedNanos = System.nanoTime() - startNanos;

            scoresByScorer.put(entry.getKey(), scores);
            resultsByScorer.put(entry.getKey(), ClassificationMetrics.evaluate(adversarial, scores, 0.5));
            distributionsByScorer.put(
                entry.getKey(),
                RoutingOutcomeMetrics.tierDistribution(
                    scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, PRESENT_TIERS));
            latencyByScorer.put(entry.getKey(), elapsedNanos / requests.size() / 1000);
        }

        Files.createDirectories(OUTPUT_DIRECTORY);

        StringBuilder markdown = new StringBuilder(
            BakeoffReport.toMarkdown(resultsByScorer, distributionsByScorer));

        markdown.append("\n| scorer | mean score() microseconds |\n|---|---|\n");

        for (Map.Entry<String, Long> entry : latencyByScorer.entrySet()) {
            markdown.append(String.format("| %s | %d |%n", entry.getKey(), entry.getValue()));
        }

        Files.writeString(
            OUTPUT_DIRECTORY.resolve("report.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(
            OUTPUT_DIRECTORY.resolve("scores.csv"), BakeoffReport.toCsv(adversarial, scoresByScorer),
            StandardCharsets.UTF_8);

        System.out.println(markdown);
    }

    /**
     * Candidate D's key, from a system property or the environment. Absent, candidate D is skipped and said so in
     * the run output — never silently omitted.
     */
    private static String remoteApiKey() {
        String property = System.getProperty("bakeoff.embedding.apiKey");

        if (property != null && !property.isBlank()) {
            return property;
        }

        String environment = System.getenv("BAKEOFF_EMBEDDING_API_KEY");

        if (environment != null && !environment.isBlank()) {
            return environment;
        }

        return null;
    }
}
```

The latency column this produces is a **mean over the adversarial set**, not a p99. §9 criterion 2 is
stated as p99, so read the per-prompt timings out of `scores.csv` if the mean lands near the 10 ms
threshold; for candidates B and C the mean and p99 will not straddle it, and for D the wide-area caveat
in spec §6.3 dominates either statistic.

- [ ] **Step 6: Verify `check` does not run the bake-off**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:check > /tmp/bakeoff-check.log 2>&1; echo "exit=$?"
grep -c "PromptComplexityScorerBakeoff" /tmp/bakeoff-check.log
```

Expected: exit=0 and a count of `0`. A non-zero count means the `exclude` pattern is wrong — fix it before committing, since otherwise CI downloads a 90 MB model on every run.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/bakeoff-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway/platform-ai-gateway-scorer-bakeoff
git commit -m "bakeoff Add bake-off runner, report rendering and gradle task"
```

---

## Task 7: Run the bake-off and record the outcome

**Files:**
- Modify: `docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-design.md` (§13)
- Create: `docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-report.md`

- [ ] **Step 1: Run it**

```bash
BAKEOFF_EMBEDDING_API_KEY=<key> ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:bakeoff > /tmp/bakeoff-run.log 2>&1; echo "exit=$?"
```

Expected: exit=0. First run downloads the MiniLM model and tokenizer over HTTP and will take a few
minutes; subsequent runs read the local cache. If the run fails with a DJL native download, the
exclusions from Task 4 Step 6 have regressed.

Ask the user for an embeddings API key before this step. Without one the run still succeeds and still
produces a valid comparison of the baseline, B and C — but candidate D is skipped, and a §9 criterion 4
verdict cannot be reached, because D holds the smallest footprint slot. Check the run output for the
`SKIPPED candidate D` line and say so plainly in the report if it appears; do not present a
three-way result as if it were the full bake-off.

- [ ] **Step 2: Measure the footprint**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-scorer-bakeoff:dependencies --configuration testRuntimeClasspath > /tmp/bakeoff-deps.log 2>&1; echo "exit=$?"
```

Record the resolved sizes of `onnxruntime`, `tokenizers`, `ai.djl:api` and `opennlp-tools`, plus the
cached `model.onnx`, against the §3.6 figures. Report what you measured, not what the spec predicted.

- [ ] **Step 3: Write the report document**

Copy `build/bakeoff/report.md` into
`docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-report.md`, and add above the tables:
the date, the commit SHA the run was made at, the corpus sizes, and the per-bucket accuracy breakdown
read off `scores.csv`.

If candidate B wins, state in the report what an operator has to do to run it: mount the onnxruntime,
tokenizers and djl-api jars into `/opt/bytechef/external_jars`, and point `modelResource` /
`tokenizerResource` at the mounted model and tokenizer files. The sideload path itself already works and
needs no infrastructure change (spec §3.9).

- [ ] **Step 4: Fill in §13 of the spec**

Replace the placeholder paragraph in §13 with:
- the headline table,
- an explicit verdict against each of the four §9 criteria, stated as pass or fail with the number,
- the resulting action from §8, named as one of the three cases,
- anything the run revealed that the design got wrong.

State the verdict even if it is "neither candidate clears criterion 1, the baseline stays". Do **not**
adjust the §9 thresholds after seeing results — that is the one move that would make the whole exercise
worthless.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs
git commit -m "bakeoff Record prompt complexity scorer bake-off results"
```

- [ ] **Step 6: Report to the user and stop**

The follow-up — deleting the losers and integrating the winner per §8 — is a separate piece of work with
its own approval. Do not begin it as part of this plan.

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| §3.7 evidence boundary on Merge | Global Constraints — no code or report text may assert Merge internals |
| §4 A baseline | Task 6 (used as-is, unmodified) |
| §4 B embedding centroid, in-process | Task 4 + Task 6 Step 5 (`embedding-local`) |
| §4 C OpenNLP | Task 3 |
| §4 D embedding centroid, remote | Task 6 Step 5 (`embedding-remote`) — same class as B, no new code |
| §4 shared exemplar constraint | Task 6 Step 5 — all candidates constructed from the same `exemplars` list |
| §5.1 exemplar set | Task 2 Step 2 |
| §5.2 adversarial set | Task 2 Step 3, bucket counts guarded by Task 2 Step 4 |
| §5.3 real-traffic sample | **Not implemented.** Optional in the spec, unlabelled, and contributes only advisory metrics. Recorded here as a deliberate omission rather than a gap. |
| §6.1 classification quality | Task 5 `ClassificationMetrics` |
| §6.2 routing outcome | Task 5 `RoutingOutcomeMetrics`; the cost-delta half is reported as the tier distribution, from which cost follows directly — a separate cost function would need a synthetic price list the spec does not fix |
| §6.3 scorer cost | Task 6 runner (latency), Task 7 Step 2 (footprint). Heap delta and cold-start are read from the run log rather than computed. The runner reports a mean, not a p99 — the plan says where to get p99 when it matters |
| §6.3 candidate D upper-bound caveat | Task 7 Step 3, carried into the report |
| §6.4 robustness | Task 2 `degenerate` bucket; Tasks 3 and 4 empty-prompt tests |
| §7 harness | Task 6 |
| §9 pre-registered criteria | Task 7 Step 4 |
| §10 testing | Tasks 1–6 all end in a green test task |
| §13 outcome | Task 7 |

Three acknowledged partial coverages, all above: §5.3 is not built; §6.2's cost delta is left implicit in
the tier distribution; and the runner reports mean rather than p99 latency, with the plan naming where to
recover p99. The first two do not affect the §9 criteria. The third does touch criterion 2, which is why
the plan says explicitly when the mean is not good enough.

One coverage gap that is **not** the plan's to close: §9 criterion 4 cannot be evaluated at all if
candidate D is skipped for want of an API key. Task 7 Step 1 makes that visible rather than papering over
it.

**Placeholder scan:** no TBD, TODO, "add error handling", or "similar to Task N" appears. Every code step
carries complete compilable source. The two corpus steps specify exact counts, schema and worked examples
per bucket rather than the full 100 entries — corpus authorship is the human judgment this experiment
exists to capture, and the shape test in Task 2 Step 4 mechanically enforces what the plan asks for.

**Type consistency:** `BakeoffPrompt`'s seven-component signature is used identically in Tasks 1–6.
`BakeoffCorpus.load(String)`, `BakeoffCorpus.toRequest(BakeoffPrompt)`, `ClassificationMetrics.evaluate`,
`RoutingOutcomeMetrics.tierDistribution`, `BakeoffReport.toMarkdown` and `BakeoffReport.toCsv` each match
their declarations and every call site. `BakeoffPrompt.SIMPLE` / `.COMPLEX` are used in place of string
literals everywhere except the JSONL fixtures, where they are data.
