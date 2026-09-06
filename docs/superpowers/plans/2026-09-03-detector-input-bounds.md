# Detector Input Bounds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop unbounded, undeadlined sensitive-data detection from stalling a request thread, without ever silently reducing coverage.

**Architecture:** `SensitiveDataRedactor#detectCandidates` is the single funnel every detection path goes through — the two internal callers (`redactWithSpans`, `tokenizeWithSpans`) and the one external caller (`StreamingResponseRedactor`). All three bounds go there and nowhere else. Windowable detectors (`streamSafe() == true`) run first over overlapping windows with spans offset back into document coordinates; unwindowable ones run afterwards, whole, and are skipped above a size threshold. A cooperative deadline covers the pass.

**Tech Stack:** Java 25, Spring Boot 4, Micrometer, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-03-detector-input-bounds-design.md`

## Global Constraints

- **Never truncate.** A detector is handed either a full window (with overlap) or, if it cannot be windowed and the input is too large, nothing at all — recorded. Handing a detector a prefix and reporting a clean scan is the failure mode the spec exists to reject (§3, D7a).
- **Both new events must be implemented on `AiGuardrailMetrics`, not left as seam defaults.** `AiGuardrailMetricsTest#testEverySensitiveDataMetricsEventIsImplementedRatherThanLeftAsANoOpDefault` will fail until they are. That is the interlock working; do not weaken the test.
- **Default behaviour must be byte-identical to today** for any input that fits one window. This is the no-regression constraint and it is what makes the change shippable without a migration.
- **Every new property is a field on `ApplicationProperties`.** Binding is strict — a key with no field fails application startup.
- CE module (`server/libs/…`): Apache 2.0 header, no `@version ee` tag. EE module: Enterprise header, `@version ee`.
- Run `./gradlew spotlessApply` before each commit. Judge Gradle by `$?` on its own line plus `grep '^> Task .* FAILED'`.

## Design deviations resolved before planning

The spec was revised against the code before this plan was written. Three changes an implementer should know about rather than rediscover:

1. **There is no windowing precedent to reuse.** `StreamingResponseRedactor` bounds a carry buffer but never offsets spans into document coordinates — its buffer *is* the coordinate space. Offsetting is new code.
2. **No `cheap()` flag.** The spec's original §4c added one; it would have been 100% correlated with the existing `streamSafe()` across every detector that exists, which is a way for the two to disagree later. `streamSafe()` already means exactly "can this be applied to a fragment", which is the windowability question. Ordering (windowable first, to completion) delivers the guarantee `cheap()` was for.
3. **The overlap is configured, not derived.** A compiled `Pattern` does not expose a maximum match length. The claim is pinned by a test over adversarial inputs instead.

## File Structure

**Modify (CE):**
- `…/platform-ai-sensitive-data-api/…/SensitiveSpan.java` — a `withOffset(int)` helper
- `…/platform-ai-sensitive-data-api/…/SensitiveDataMetrics.java` — two new seam events
- `…/platform-ai-sensitive-data-service/…/SensitiveDataRedactor.java` — the three bounds, all inside `detectCandidates`
- `…/platform-ai-sensitive-data-service/src/test/…/SensitiveDataRedactorTest.java`
- `…/platform-ai-sensitive-data-service/src/test/…/PiiPatternCatalogTest.java` — the overlap claim

**Modify (EE / config):**
- `server/libs/config/app-config/…/ApplicationProperties.java` — `Ai.Guardrails.Detection`
- `…/platform-ai-guardrails-service/…/AiGuardrails.java` — pass the bounds through
- `…/platform-ai-guardrails-service/…/AiGuardrailMetrics.java` — implement both events
- `.agents/ai-guardrails.md`

**Create:** nothing.

---

## Task 1: The seam gains two events, and `SensitiveSpan` gains an offset

**Files:**
- Modify: `…/platform-ai-sensitive-data-api/…/SensitiveDataMetrics.java`
- Modify: `…/platform-ai-sensitive-data-api/…/SensitiveSpan.java`
- Modify: `…/platform-ai-guardrails-service/…/AiGuardrailMetrics.java`
- Test: `…/platform-ai-sensitive-data-api/src/test/…/SensitiveSpanTest.java`

**Interfaces:**
- Produces: `SensitiveDataMetrics#recordDetectorTimedOut(String)`, `#recordDetectorSkippedOversize(String)`, `SensitiveSpan#withOffset(int)`. Task 2 consumes all three.

- [ ] **Step 1: Write the failing offset test**

In `SensitiveSpanTest`:

```java
    @Test
    void testWithOffsetMovesBothEndsAndKeepsEverythingElse() {
        SensitiveSpan span = new SensitiveSpan(SensitiveKind.PII, "EMAIL_ADDRESS", 5, 20, 0.9);

        SensitiveSpan offset = span.withOffset(1000);

        assertThat(offset.start()).isEqualTo(1005);
        assertThat(offset.end()).isEqualTo(1020);
        assertThat(offset.kind()).isEqualTo(span.kind());
        assertThat(offset.category()).isEqualTo(span.category());
        assertThat(offset.confidence()).isEqualTo(span.confidence());
    }

    @Test
    void testWithOffsetOfZeroReturnsAnEqualSpan() {
        SensitiveSpan span = new SensitiveSpan(SensitiveKind.SECRET, "AWS_ACCESS_KEY", 0, 20, 0.9);

        assertThat(span.withOffset(0)).isEqualTo(span);
    }
```

- [ ] **Step 2: Run and confirm it does not compile**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:compileTestJava
```

Expected: `cannot find symbol: method withOffset(int)`.

- [ ] **Step 3: Add the helper**

In `SensitiveSpan`:

```java
    /**
     * Returns this span translated by {@code offset} characters, for lifting a span found in a window back into the
     * coordinates of the document that window came from.
     *
     * @param offset the window's start position in the document; {@code 0} returns an equal span
     * @return the translated span
     */
    public SensitiveSpan withOffset(int offset) {
        if (offset == 0) {
            return this;
        }

        return new SensitiveSpan(kind, category, start + offset, end + offset, confidence);
    }
```

- [ ] **Step 4: Add the two seam events**

In `SensitiveDataMetrics`, after `recordDetectorFailure`:

```java
    /**
     * Records that a detection pass abandoned its remaining work because it exceeded the configured deadline, so the
     * spans it returned are partial. Recorded at most once per call, tagged with the detector that was running when
     * the budget ran out.
     *
     * <p>
     * Distinct from {@link #recordDetectorFailure} on purpose. That event means a detector threw and the engine
     * continued without it; this one means a detector was still working and was cut off. Conflating them would lose
     * the distinction the whole design turns on -- the engine's fail-open catch cannot see a slow detector at all,
     * because a slow detector never throws.
     * </p>
     *
     * @param detectorName the detector that was running when the deadline expired
     */
    default void recordDetectorTimedOut(String detectorName) {
        // No-op default, for the same reason recordBelowConfidenceThreshold is one.
    }

    /**
     * Records that a detector which cannot be applied to a fragment ({@code streamSafe() == false}) was not run at
     * all, because the input exceeded the configured maximum for an unwindowable detector.
     *
     * <p>
     * This is the loud half of a deliberate coverage loss: such a detector cannot be windowed by its own contract, so
     * on a very large input the choice is between skipping it and handing it a truncated prefix. A truncated prefix
     * would report a clean scan of a document only partly read; a skip reports, on a counter carrying the detector's
     * name, that it did not run.
     * </p>
     *
     * @param detectorName the detector that was skipped
     */
    default void recordDetectorSkippedOversize(String detectorName) {
        // No-op default, for the same reason recordBelowConfidenceThreshold is one.
    }
```

- [ ] **Step 5: Implement both on `AiGuardrailMetrics`**

Add the two constants beside the existing event constants and the two overrides beside `recordDetectorFailure`, following that method's exact shape (it delegates to `record(String)`):

```java
    private static final String DETECTOR_TIMED_OUT_EVENT = "detector_timed_out";
    private static final String DETECTOR_SKIPPED_OVERSIZE_EVENT = "detector_skipped_oversize";
```

```java
    @Override
    public void recordDetectorTimedOut(String detectorName) {
        record(DETECTOR_TIMED_OUT_EVENT);
    }

    @Override
    public void recordDetectorSkippedOversize(String detectorName) {
        record(DETECTOR_SKIPPED_OVERSIZE_EVENT);
    }
```

Note the detector name is accepted and not tagged, matching `recordDetectorFailure`'s existing treatment — the counter deliberately carries only `event` and `surface` to stay cheap on unbounded multi-tenant deployments, and the name reaches operators through the log line instead. If `recordDetectorFailure` tags it, follow whatever it does rather than this snippet.

Add both event names to the class javadoc's event list.

- [ ] **Step 6: Run the tests**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:test :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailMetricsTest*' --tests '*SensitiveSpanTest*'
```

Expected: PASS, including `testEverySensitiveDataMetricsEventIsImplementedRatherThanLeftAsANoOpDefault`, which would have failed had Step 5 been skipped.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A && git commit -m "732 Add the detector-bound metric events and a span offset helper"
```

---

## Task 2: The three bounds, all inside `detectCandidates`

**Files:**
- Modify: `…/platform-ai-sensitive-data-service/…/SensitiveDataRedactor.java`
- Test: `…/platform-ai-sensitive-data-service/src/test/…/SensitiveDataRedactorTest.java`

**Interfaces:**
- Consumes: `SensitiveSpan#withOffset`, the two seam events from Task 1.
- Produces: a second `SensitiveDataRedactor` constructor `(List<SensitiveDataDetector>, DetectionBounds)`. The existing single-argument constructor stays and delegates with `DetectionBounds.DEFAULTS`, so every current caller — including `streamSafeView()` and all tests — keeps compiling.
- Produces: `public record DetectionBounds(int windowSize, int windowOverlap, Duration timeout, int maxUnwindowableInput)` with a `DEFAULTS` constant. Task 3 consumes it.

- [ ] **Step 1: Write the failing tests**

In `SensitiveDataRedactorTest`. Use the file's existing detector-stub idiom rather than inventing one; if it has no stub helper, a lambda satisfies `SensitiveDataDetector` only if the interface is functional — it is not (it has `name()`), so write small named static classes.

```java
    /**
     * The coverage-preservation claim, and the only test here that would notice an off-by-one in the overlap: the
     * match sits deliberately across a window boundary. Without this case, a windowing bug that silently drops
     * boundary-straddling matches passes every other test in this file.
     */
    @Test
    void testAMatchStraddlingAWindowBoundaryIsStillFound() {
        String filler = "x".repeat(100);
        String text = filler + "mail bob@acme.io now" + "y".repeat(100);
        SensitiveDataRedactor whole = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        SensitiveDataRedactor windowed = new SensitiveDataRedactor(
            SensitiveDataDetectors.builtIn(),
            new SensitiveDataRedactor.DetectionBounds(105, 32, Duration.ofSeconds(30), Integer.MAX_VALUE));

        assertThat(windowed.detectCandidates(text, null))
            .containsExactlyInAnyOrderElementsOf(whole.detectCandidates(text, null));
    }

    @Test
    void testSpansFromALaterWindowCarryDocumentCoordinates() {
        // An off-by-window offset replaces the wrong characters, which a single-window test cannot see.
        String text = "x".repeat(500) + "mail bob@acme.io";
        SensitiveDataRedactor windowed = new SensitiveDataRedactor(
            SensitiveDataDetectors.builtIn(),
            new SensitiveDataRedactor.DetectionBounds(64, 32, Duration.ofSeconds(30), Integer.MAX_VALUE));

        SensitiveSpan span = windowed.detectCandidates(text, null)
            .getFirst();

        assertThat(text.substring(span.start(), span.end())).isEqualTo("bob@acme.io");
    }

    @Test
    void testAnOverlapDuplicateIsNotReturnedTwice() {
        // The overlap re-scans characters, so a match inside it is found by two windows. Returning it twice would
        // double-count every boundary match and hand resolution a self-overlapping candidate set.
        String text = "x".repeat(60) + "mail bob@acme.io" + "y".repeat(60);
        SensitiveDataRedactor windowed = new SensitiveDataRedactor(
            SensitiveDataDetectors.builtIn(),
            new SensitiveDataRedactor.DetectionBounds(64, 48, Duration.ofSeconds(30), Integer.MAX_VALUE));

        assertThat(windowed.detectCandidates(text, null)).hasSize(1);
    }

    @Test
    void testAnUnwindowableDetectorIsSkippedAboveTheThresholdAndTheOthersStillRun() {
        RecordingMetrics metrics = new RecordingMetrics();
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(
            List.of(new RegexPiiDetector(), new UnwindowableDetector()),
            new SensitiveDataRedactor.DetectionBounds(
                65536, 1024, Duration.ofSeconds(30), 10));

        List<SensitiveSpan> spans = redactor.detectCandidates("mail bob@acme.io please", metrics);

        assertThat(spans)
            .as("the windowable detectors must still cover an input the unwindowable one was skipped for")
            .isNotEmpty();
        assertThat(spans)
            .noneMatch(span -> "UNWINDOWABLE".equals(span.category()));
        assertThat(metrics.skippedOversize).containsExactly("unwindowable");
    }

    @Test
    void testTheDeadlineAbandonsRemainingWorkKeepsWhatWasFoundAndRecordsTheDetector() {
        RecordingMetrics metrics = new RecordingMetrics();
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(
            List.of(new RegexPiiDetector(), new SlowUnwindowableDetector()),
            new SensitiveDataRedactor.DetectionBounds(
                65536, 1024, Duration.ofMillis(50), Integer.MAX_VALUE));

        List<SensitiveSpan> spans = redactor.detectCandidates("mail bob@acme.io please", metrics);

        // All three halves. A timeout that discards what was already found is a different, worse behaviour than the
        // one designed, and one that reports nothing is indistinguishable from detection being off.
        assertThat(spans).isNotEmpty();
        assertThat(spans).noneMatch(span -> "SLOW".equals(span.category()));
        assertThat(metrics.timedOut).containsExactly("slow");
    }

    @Test
    void testWindowableDetectorsRunToCompletionBeforeAnUnwindowableOneCanExhaustTheBudget() {
        // The ordering guarantee. If the slow detector ran first, the regex pass would be cut off and the email
        // would go unredacted -- which is the failure the ordering exists to prevent.
        RecordingMetrics metrics = new RecordingMetrics();
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(
            List.of(new SlowUnwindowableDetector(), new RegexPiiDetector()),
            new SensitiveDataRedactor.DetectionBounds(
                65536, 1024, Duration.ofMillis(50), Integer.MAX_VALUE));

        assertThat(redactor.detectCandidates("mail bob@acme.io please", metrics))
            .anyMatch(span -> "EMAIL_ADDRESS".equals(span.category()));
    }

    @Test
    void testAnInputThatFitsOneWindowBehavesExactlyAsBefore() {
        // The no-regression pin: default bounds over an ordinary prompt must be byte-identical to the pre-windowing
        // behaviour, or this change needs a migration it does not have.
        String text = "mail bob@acme.io and call 555-0100";

        assertThat(new SensitiveDataRedactor(SensitiveDataDetectors.builtIn()).detectCandidates(text, null))
            .containsExactlyInAnyOrderElementsOf(
                new SensitiveDataRedactor(
                    SensitiveDataDetectors.builtIn(),
                    new SensitiveDataRedactor.DetectionBounds(
                        Integer.MAX_VALUE, 0, Duration.ofSeconds(30), Integer.MAX_VALUE))
                    .detectCandidates(text, null));
    }
```

Plus the fixtures, as static nested classes in the test:

```java
    private static final class UnwindowableDetector implements SensitiveDataDetector {

        @Override
        public String name() {
            return "unwindowable";
        }

        @Override
        public boolean streamSafe() {
            return false;
        }

        @Override
        public List<SensitiveSpan> detect(String text) {
            return List.of(new SensitiveSpan(SensitiveKind.PII, "UNWINDOWABLE", 0, 1, 0.9));
        }
    }

    private static final class SlowUnwindowableDetector implements SensitiveDataDetector {

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public boolean streamSafe() {
            return false;
        }

        @Override
        public List<SensitiveSpan> detect(String text) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread()
                    .interrupt();
            }

            return List.of(new SensitiveSpan(SensitiveKind.PII, "SLOW", 0, 1, 0.9));
        }
    }

    private static final class RecordingMetrics implements SensitiveDataMetrics {

        private final List<String> failed = new ArrayList<>();
        private final List<String> timedOut = new ArrayList<>();
        private final List<String> skippedOversize = new ArrayList<>();

        @Override
        public void recordDetectorFailure(String detectorName) {
            failed.add(detectorName);
        }

        @Override
        public void recordDetectorTimedOut(String detectorName) {
            timedOut.add(detectorName);
        }

        @Override
        public void recordDetectorSkippedOversize(String detectorName) {
            skippedOversize.add(detectorName);
        }
    }
```

`SlowUnwindowableDetector` sleeps as a *fixture*, not as an assertion — no test asserts on elapsed time, only on the outcome.

- [ ] **Step 2: Run and confirm they fail**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*SensitiveDataRedactorTest*'
```

Expected: compile failure on the two-argument constructor and `DetectionBounds`.

- [ ] **Step 3: Add `DetectionBounds` and the second constructor**

In `SensitiveDataRedactor`:

```java
    /**
     * The bounds a detection pass runs under.
     *
     * @param windowSize           the maximum number of characters handed to a windowable detector at once
     * @param windowOverlap        characters re-scanned at each window boundary, so a match straddling one is still
     *                             found. Cannot be derived from the catalog -- a compiled {@link java.util.regex.Pattern}
     *                             does not expose a maximum match length -- so it is configured, and
     *                             {@code PiiPatternCatalogTest} pins that no catalog pattern can match longer than the
     *                             default.
     * @param timeout              the budget for the whole pass, checked between windows and between detectors
     * @param maxUnwindowableInput the input length above which a detector with {@code streamSafe() == false} is not
     *                             run at all, since it cannot be windowed by its own contract
     */
    public record DetectionBounds(int windowSize, int windowOverlap, Duration timeout, int maxUnwindowableInput) {

        public static final DetectionBounds DEFAULTS =
            new DetectionBounds(65536, 1024, Duration.ofSeconds(2), 262144);

        public DetectionBounds {
            if (windowSize < 1) {
                throw new IllegalArgumentException("windowSize must be >= 1, got: " + windowSize);
            }

            if (windowOverlap < 0 || windowOverlap >= windowSize) {
                throw new IllegalArgumentException(
                    "windowOverlap must be >= 0 and < windowSize, got: " + windowOverlap + " with windowSize " +
                        windowSize);
            }

            Objects.requireNonNull(timeout, "timeout must not be null");

            if (maxUnwindowableInput < 0) {
                throw new IllegalArgumentException("maxUnwindowableInput must be >= 0");
            }
        }
    }
```

The `windowOverlap < windowSize` check is load-bearing: an overlap at or above the window size makes the windowing loop fail to advance.

Then the constructors:

```java
    public SensitiveDataRedactor(List<SensitiveDataDetector> detectors) {
        this(detectors, DetectionBounds.DEFAULTS);
    }

    public SensitiveDataRedactor(List<SensitiveDataDetector> detectors, DetectionBounds bounds) {
        this.detectors = List.copyOf(detectors);
        this.bounds = Objects.requireNonNull(bounds, "bounds must not be null");
    }
```

`streamSafeView()` must carry the bounds into the view it builds, or the streaming path silently reverts to defaults.

- [ ] **Step 4: Rewrite `detectCandidates`**

```java
    public List<SensitiveSpan> detectCandidates(String text, @Nullable SensitiveDataMetrics metrics) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // A LinkedHashSet, not a list: the overlap deliberately re-scans characters, so a match inside it is found
        // by two consecutive windows. Two identical spans are one finding, and letting both through would hand
        // resolution a candidate set that overlaps itself.
        Set<SensitiveSpan> candidates = new LinkedHashSet<>();
        long deadline = System.nanoTime() + bounds.timeout()
            .toNanos();

        // Windowable detectors run first, to completion. That ordering IS the guarantee that the regex pass --
        // where every identifying pattern lives -- cannot be starved by an expensive detector that runs long.
        for (SensitiveDataDetector detector : detectors) {
            if (!detector.streamSafe()) {
                continue;
            }

            if (timedOut(deadline, detector, metrics)) {
                return List.copyOf(candidates);
            }

            collectWindowed(detector, text, candidates, deadline, metrics);
        }

        for (SensitiveDataDetector detector : detectors) {
            if (detector.streamSafe()) {
                continue;
            }

            if (text.length() > bounds.maxUnwindowableInput()) {
                log.warn(
                    "Sensitive-data detector '{}' cannot be windowed and was skipped for a {}-character input " +
                        "exceeding the {}-character limit; its coverage is absent for this call",
                    detector.name(), text.length(), bounds.maxUnwindowableInput());

                if (metrics != null) {
                    metrics.recordDetectorSkippedOversize(detector.name());
                }

                continue;
            }

            if (timedOut(deadline, detector, metrics)) {
                return List.copyOf(candidates);
            }

            collectSpans(detector, text, candidates, metrics);
        }

        return List.copyOf(candidates);
    }

    private void collectWindowed(
        SensitiveDataDetector detector, String text, Set<SensitiveSpan> candidates, long deadline,
        @Nullable SensitiveDataMetrics metrics) {

        int stride = bounds.windowSize() - bounds.windowOverlap();

        for (int start = 0; start < text.length(); start += stride) {
            if (timedOut(deadline, detector, metrics)) {
                return;
            }

            int end = Math.min(start + bounds.windowSize(), text.length());
            List<SensitiveSpan> windowSpans = new ArrayList<>();

            collectSpans(detector, text.substring(start, end), windowSpans, metrics);

            for (SensitiveSpan span : windowSpans) {
                candidates.add(span.withOffset(start));
            }

            if (end == text.length()) {
                return;
            }
        }
    }

    private static boolean timedOut(
        long deadline, SensitiveDataDetector detector, @Nullable SensitiveDataMetrics metrics) {

        if (System.nanoTime() < deadline) {
            return false;
        }

        log.warn(
            "Sensitive-data detection exceeded its budget while running '{}'; the spans returned for this call are " +
                "partial and some of the content was not scanned",
            detector.name());

        if (metrics != null) {
            metrics.recordDetectorTimedOut(detector.name());
        }

        return true;
    }
```

`collectSpans`'s signature widens from `List<SensitiveSpan>` to `Collection<SensitiveSpan>` so both call sites work. Its span-bounds validation (`span.end() > text.length()`) keeps comparing against the **window**, not the document — that is correct, since the detector was handed the window.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*SensitiveDataRedactorTest*'
```

Expected: PASS.

- [ ] **Step 6: Negative-control the two claims that carry the design**

Each control is applied, run, observed, reverted.

1. Set `candidates.add(span.withOffset(start))` to `candidates.add(span)`. Expected: `testSpansFromALaterWindowCarryDocumentCoordinates` fails and `testAMatchStraddlingAWindowBoundaryIsStillFound` fails. Nothing else.
2. Set `int stride = bounds.windowSize();` (no overlap). Expected: `testAMatchStraddlingAWindowBoundaryIsStillFound` fails. This is the control that matters most — a boundary-straddling match silently disappearing is the exact failure windowing must not introduce.
3. Swap the two detector loops so unwindowable detectors run first. Expected: `testWindowableDetectorsRunToCompletionBeforeAnUnwindowableOneCanExhaustTheBudget` fails.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check
git add -A && git commit -m "732 Window, deadline and size-bound sensitive-data detection"
```

---

## Task 3: Configuration reaches the redactor, and the overlap claim is pinned

**Files:**
- Modify: `server/libs/config/app-config/…/ApplicationProperties.java`
- Modify: `…/platform-ai-guardrails-service/…/AiGuardrails.java`
- Test: `…/platform-ai-sensitive-data-service/src/test/…/PiiPatternCatalogTest.java`

- [ ] **Step 1: Pin the overlap claim first**

In `PiiPatternCatalogTest`. This is the test that replaces the derivation the spec originally asked for:

```java
    /**
     * The default window overlap (1024) is a configured number, not a derived one -- a compiled Pattern does not
     * expose a maximum match length. So the claim it rests on is pinned empirically: no pattern in the catalog can
     * produce a match longer than the overlap, or a match straddling a window boundary would be lost.
     *
     * <p>
     * The adversarial input is built from each pattern's own alphabet rather than from prose, because a pattern only
     * runs long on input it can actually consume.
     * </p>
     */
    @Test
    void testNoCatalogPatternCanMatchLongerThanTheDefaultWindowOverlap() {
        int overlap = SensitiveDataRedactor.DetectionBounds.DEFAULTS.windowOverlap();
        String haystack = ("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_.+@/: ").repeat(200);
        List<String> tooLong = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            Matcher matcher = piiPattern.pattern()
                .matcher(haystack);

            while (matcher.find()) {
                if (matcher.end() - matcher.start() > overlap) {
                    tooLong.add(piiPattern.type());

                    break;
                }
            }
        }

        assertThat(tooLong)
            .as("a pattern that can match longer than the window overlap loses boundary-straddling matches when "
                + "detection is windowed; either tighten the pattern or raise DetectionBounds.DEFAULTS' overlap")
            .isEmpty();
    }
```

Add the identical test to `SecretPatternCatalogTest` over `SecretPatternCatalog.ALL`. Duplication is right here: the two catalogs are independent and a shared helper would let one be dropped without notice.

- [ ] **Step 2: Run it**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*PatternCatalogTest*'
```

Expected: PASS. **If it fails, do not raise the default to make it pass** — read the offending pattern first. A pattern matching over 1024 characters is far more likely to be a defective pattern than a legitimate one, and the consolidation work already found one such bug (`FI_PERSONAL_IDENTITY_CODE`'s range).

- [ ] **Step 3: Add the properties**

In `ApplicationProperties`, inside `Ai.Guardrails`, beside `openNlp`:

```java
            private Detection detection = new Detection();

            public Detection getDetection() {
                return detection;
            }

            public void setDetection(Detection detection) {
                this.detection = detection;
            }

            public static class Detection {

                private int windowSize = 65536;
                private int windowOverlap = 1024;
                private Duration timeout = Duration.ofSeconds(2);
                private int maxUnwindowableInput = 262144;

                // getters and setters for all four, matching the style of the surrounding OpenNlp class
            }
```

The defaults must equal `DetectionBounds.DEFAULTS` exactly. Two sets of defaults that can drift is how a documented value stops being the real one.

- [ ] **Step 4: Thread them into `AiGuardrails`**

Add four `@Value` parameters to the `@Autowired` constructor, following the existing `bytechef.ai.gateway.guardrails.*` parameters' style but under the correct prefix, and build the bounds:

```java
        @Value("${bytechef.ai.guardrails.detection.window-size:65536}") int detectionWindowSize,
        @Value("${bytechef.ai.guardrails.detection.window-overlap:1024}") int detectionWindowOverlap,
        @Value("${bytechef.ai.guardrails.detection.timeout:2s}") Duration detectionTimeout,
        @Value("${bytechef.ai.guardrails.detection.max-unwindowable-input:262144}") int maxUnwindowableInput) {
```

```java
        this.sensitiveDataRedactor = new SensitiveDataRedactor(
            sensitiveDataDetectors,
            new SensitiveDataRedactor.DetectionBounds(
                detectionWindowSize, detectionWindowOverlap, detectionTimeout, maxUnwindowableInput));
```

The **non**-`@Autowired` convenience constructor keeps delegating with `DetectionBounds.DEFAULTS`, so every existing test construction — there are many, across `AiGuardrailsTest` and `AiGuardrailsAdvisorTest` — compiles unchanged.

- [ ] **Step 5: Compile everything**

```bash
./gradlew compileJava compileTestJava --continue
```

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add -A && git commit -m "732 Make the detection bounds configurable and pin the overlap against both catalogs"
```

---

## Task 4: Documentation and whole-change verification

**Files:**
- Modify: `.agents/ai-guardrails.md`

- [ ] **Step 1: Document it**

A section carrying five things:

1. The defect in one line: the fail-open catch cannot see a slow detector, because a slow detector never throws.
2. The three bounds and which failure each addresses.
3. That windowing preserves coverage exactly, and that the overlap is configured rather than derived, pinned by a catalog test.
4. That an unwindowable detector is **skipped, never truncated**, above a size — and that this is a deliberate, metered coverage loss rather than a silent one.
5. The stated limitation: the deadline is cooperative, so it cannot interrupt a single pathological unit, and **that becomes insufficient when operator-supplied regexes land** (queue item 6).

- [ ] **Step 2: Verify the whole change**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:libs:config:app-config:check --continue
```

Expected: `EXIT=0`, no `^> Task .* FAILED`.

- [ ] **Step 3: Confirm the streaming path still carries its bounds**

```bash
grep -n "streamSafeView" server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/SensitiveDataRedactor.java
```

The view it returns must be constructed with `this.bounds`, not with defaults. A view that silently reverts to defaults is invisible in every test that does not configure bounds — which is all of them but the ones in Task 2.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "732 Document the detector input bounds and their one stated limitation"
```

## Self-Review

**Spec coverage.** §4a windowing with overlap → Task 2 Steps 3–4, tested by the boundary and offset cases. §4b cooperative deadline, ordering guarantee, fail-open, mandatory metric → Task 2. §4c skip-not-truncate → Task 2, with its own event. §5 four properties as `ApplicationProperties` fields → Task 3. §6 both events, separate from `detector_failed` → Task 1. §7 all seven test bullets → Task 2 Step 1 and Task 3 Step 1. §9 D1/D2 → the overlap plus its catalog pin; D3/D4/D5 → the deadline and its event; D6/D7/D7a/D7b → the ordering, no `cheap()`, the skip, and the documented limitation. §10's ruling (fail open, do not downgrade) is what Task 2 implements.

**Placeholder scan.** No TBDs. The two places that defer to the file's own idiom — `recordDetectorFailure`'s tagging treatment, `PiiPatternCatalogTest`'s imports — say what to match and why, rather than leaving a gap.

**Type consistency.** `DetectionBounds(int windowSize, int windowOverlap, Duration timeout, int maxUnwindowableInput)` is four components in its declaration, its `DEFAULTS`, all six test constructions, and the `ApplicationProperties` block. `withOffset(int)` returns `SensitiveSpan`. `collectSpans` takes `Collection<SensitiveSpan>` after Task 2, called from both `collectWindowed` (an `ArrayList`) and `detectCandidates` (a `LinkedHashSet`).
