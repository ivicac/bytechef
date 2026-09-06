# PII Tokenization — Phase 1 Implementation Plan (request-scoped sessions)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace irreversible PII redaction with reversible tokenization, so a model receives typed, distinct, value-stable tokens and the real values are restored before the answer reaches the user — with sessions scoped to a single request.

**Architecture:** `SensitiveDataRedactor.apply()` currently replaces each span with `span.placeholder()`, a pure function of the span. Tokenization makes the replacement a function of *(span, session)* instead: PII spans become tokens minted by a `PiiTokenSession`, SECRET spans keep `[REDACTED_SECRET]`. One pass, two treatments, no second pipeline. The advisor creates a session per request, tokenizes outbound, and — after response scanning — restores inbound.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI advisors, Project Reactor (streaming), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-guardrails-pii-tokenization-design.md`

**This plan is Phase 1 of two** (spec §10a). Phase 2 adds the conversation-scoped, encrypted, evictable session store. **Phase 1 must not ship to users on its own where chat history is retained** — per spec §5, request-scoped tokens persisted into history become dead tokens on later turns. That is a release constraint, not an implementation one, but it belongs in the PR description.

## Global Constraints

- **Secrets never tokenize.** `SensitiveKind.SECRET` spans keep `[REDACTED_SECRET]` exactly. Restoring a caught credential defeats catching it (spec §3). A test must pin this.
- **Scan before restore.** Response-direction scanning runs first, restoration second. Reversed, the feature is a no-op — restore puts the value back and scanning immediately re-redacts it (spec §7).
- **Token format is exactly** `[PII_<CATEGORY>_<ordinal>_<sessionId>]`, ASCII only, e.g. `[PII_EMAIL_1_k3n9]`. No unicode — a token that does not survive the model round trip verbatim cannot be restored.
- **A value is stable within a session.** The same address anywhere in one request maps to the same token, or the model cannot tell that two mentions are one person.
- **Sessions are thread-safe.** The streaming path is Reactor; `push` calls are not guaranteed to stay on one thread.
- **Unknown or foreign-session tokens are left untouched** — not restored, not stripped, not thrown on (spec §9).
- **A task that breaks an assertion updates it in the same task.** 12 Java files pin `[REDACTED_EMAIL]`-style strings; do NOT defer those edits to the docs task, or intermediate commits are red.
- **EE license header + `@version ee`** on every new class under `server/ee/`.
- Blank line before control statements (`if`, `for`, `while`, `try`, `switch`) except immediately after an opening `{`. No blank line before a class's closing `}`. Blank line after a variable modification a following statement uses.
- No abbreviated variable names, including lambda parameters and loop variables. No `_` prefix on private methods.
- Test method names camelCase with no underscores. Unit test classes end in `Test`, never `IntTest`.
- Run `./gradlew spotlessApply` before every commit.
- **Run the module's full `check`, not just `test`, before committing.** On a SpotBugs failure read `build/reports/spotbugs/main.html` — the XML report is disabled in this repo and is stale.
- **Never judge a Gradle run piped into `tail`/`grep`** — redirect to a file, check `$?` on its own line, then grep for `^> Task .* FAILED`.

**Module path:** `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service`

**Existing types this builds on** (all committed, in `com.bytechef.ee.platform.ai.guardrails.detector`):

- `enum SensitiveKind { PII, SECRET }`
- `record SensitiveSpan(SensitiveKind kind, String category, int start, int end, double confidence)` with `placeholder()` → `"[REDACTED_" + category + "]"`, `length()`, `overlaps(other)`. Category matches `[A-Z][A-Z0-9_]*`.
- `SensitiveDataRedactor` with `redact(text, kinds, metrics)`, `redactWithSpans(text, kinds, metrics)` → `RedactionResult(String text, List<SensitiveSpan> accepted)`, `detectCandidates(text, metrics)`, `streamSafeView()`, and package-private statics `filterByKind`, `resolve`, `apply(String, List<SensitiveSpan>)`.

---

## File Structure

**Created** — package `com.bytechef.ee.platform.ai.guardrails.tokenization` in `platform-ai-guardrails-service`:

| File | Responsibility |
|---|---|
| `PiiToken.java` | Token format: render, parse, and the recognition pattern |
| `PiiTokenSession.java` | Mints stable tokens for values; restores text; thread-safe |
| `PiiTokenTest.java` | Format round-trip and rejection cases |
| `PiiTokenSessionTest.java` | Stability, distinctness, restore, unknown/foreign token handling |

**Modified**

| File | Change |
|---|---|
| `detector/SensitiveDataRedactor.java` | `apply` gains a replacer; new `tokenizeWithSpans` |
| `AiGuardrails.java` | Session-aware tokenize/restore entry points |
| `advisor/AiGuardrailsAdvisor.java` | Session per request; tokenize outbound; scan-then-restore inbound; close on all terminations |
| `StreamingResponseRedactor.java` | Tokens join the safe-cut set; restore per emitted segment |
| `AiGatewayGuardrails.java` | Thread a session from `apply` to `redactResponse` |
| 12 Java test files pinning PII placeholders | Updated in the task that breaks them |
| `.agents/ai-guardrails.md`, `docs/content/docs/platform/automation/deploy/ai-gateway.md` | Documentation (Task 7) |

---

## Task 1: Token format

**Files:**
- Create: `.../guardrails/tokenization/PiiToken.java`
- Test: `.../guardrails/tokenization/PiiTokenTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PiiToken(String category, int ordinal, String sessionId)` with `String text()`, `static Optional<PiiToken> parse(String)`, `static Pattern pattern()`, `static final int SESSION_ID_LENGTH = 4`.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class PiiTokenTest {

    @Test
    void testRendersTheDocumentedFormat() {
        assertThat(new PiiToken("EMAIL", 1, "k3n9").text()).isEqualTo("[PII_EMAIL_1_k3n9]");
    }

    @Test
    void testParsesWhatItRenders() {
        PiiToken token = new PiiToken("ORGANIZATION", 12, "ab12");

        assertThat(PiiToken.parse(token.text())).contains(token);
    }

    @Test
    void testRejectsTextThatIsNotAToken() {
        assertThat(PiiToken.parse("[REDACTED_EMAIL]")).isEmpty();
        assertThat(PiiToken.parse("[PII_EMAIL_1]")).isEmpty();
        assertThat(PiiToken.parse("[PII_email_1_k3n9]")).isEmpty();
        assertThat(PiiToken.parse("plain text")).isEmpty();
    }

    @Test
    void testPatternFindsEveryTokenInASentence() {
        String text = "forward [PII_EMAIL_1_k3n9] to [PII_EMAIL_2_k3n9] now";

        assertThat(PiiToken.pattern()
            .matcher(text)
            .results()
            .count()).isEqualTo(2);
    }

    @Test
    void testRejectsInvalidComponents() {
        assertThatThrownBy(() -> new PiiToken("lower", 1, "k3n9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiToken("EMAIL", 0, "k3n9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiToken("EMAIL", 1, "TOOLONG")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*PiiTokenTest' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED|error:' /tmp/t1.log | head
```

Expected: FAIL — `PiiToken` does not exist.

- [ ] **Step 3: Write the implementation**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One placeholder token standing in for a detected PII value, e.g. {@code [PII_EMAIL_1_k3n9]}.
 *
 * <p>
 * The shape carries four requirements. The <b>category</b> lets the model know it is reasoning about an email rather
 * than an opaque blob. The <b>ordinal</b> distinguishes one value from another, which is the whole point: without it,
 * two different addresses collapse into the same string and the model cannot tell them apart. The <b>session id</b>
 * binds a token to the session that minted it, so a token from one session can never be restored by another — an
 * impossibility by construction rather than a policy. And it is <b>ASCII only</b>, because a token that a model mangles
 * in transit is a token that cannot be restored.
 * </p>
 *
 * @param category  the {@code SensitiveSpan} category this token stands for
 * @param ordinal   1-based, distinct per value within a session
 * @param sessionId the minting session's discriminator
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record PiiToken(String category, int ordinal, String sessionId) {

    public static final int SESSION_ID_LENGTH = 4;

    private static final Pattern PATTERN = Pattern.compile("\\[PII_([A-Z][A-Z0-9_]*)_(\\d+)_([a-z0-9]{4})\\]");

    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[a-z0-9]{4}");

    public PiiToken {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        if (!CATEGORY_PATTERN.matcher(category)
            .matches()) {

            throw new IllegalArgumentException("category must match [A-Z][A-Z0-9_]*, got: " + category);
        }

        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got: " + ordinal);
        }

        if (!SESSION_ID_PATTERN.matcher(sessionId)
            .matches()) {

            throw new IllegalArgumentException("sessionId must be 4 lowercase alphanumerics, got: " + sessionId);
        }
    }

    /**
     * Returns the pattern that recognises any token, for scanning a body of text.
     *
     * @return the recognition pattern
     */
    public static Pattern pattern() {
        return PATTERN;
    }

    /**
     * Parses one token, returning empty when {@code text} is not exactly a token.
     *
     * @param text the candidate token text
     * @return the parsed token, or empty
     */
    public static Optional<PiiToken> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }

        Matcher matcher = PATTERN.matcher(text);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(
            new PiiToken(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3)));
    }

    /**
     * Returns this token's rendered text, as it appears in a prompt.
     *
     * @return the token text
     */
    public String text() {
        return "[PII_" + category + "_" + ordinal + "_" + sessionId + "]";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t1.log || echo "no failed tasks"
```

Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Add the PII token format"
```

---

## Task 2: The session

**Files:**
- Create: `.../guardrails/tokenization/PiiTokenSession.java`
- Test: `.../guardrails/tokenization/PiiTokenSessionTest.java`

**Interfaces:**
- Consumes: `PiiToken` (Task 1).
- Produces: `PiiTokenSession` with `static PiiTokenSession create()`, `String tokenFor(String category, String value)`, `String restore(String text)`, `String sessionId()`, `int size()`, `void close()`.

**Thread safety is required, not optional.** The streaming path is Reactor and `push` may run on different threads across a single stream.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class PiiTokenSessionTest {

    @Test
    void testTheSameValueAlwaysGetsTheSameToken() {
        PiiTokenSession session = PiiTokenSession.create();

        String first = session.tokenFor("EMAIL", "bob@acme.io");
        String second = session.tokenFor("EMAIL", "bob@acme.io");

        assertThat(first).isEqualTo(second);
        assertThat(session.size()).isEqualTo(1);
    }

    @Test
    void testDifferentValuesGetDifferentTokens() {
        PiiTokenSession session = PiiTokenSession.create();

        String bob = session.tokenFor("EMAIL", "bob@acme.io");
        String alice = session.tokenFor("EMAIL", "alice@acme.io");

        assertThat(bob).isNotEqualTo(alice);
        assertThat(session.size()).isEqualTo(2);
    }

    @Test
    void testRestoreSubstitutesEveryKnownToken() {
        PiiTokenSession session = PiiTokenSession.create();

        String bob = session.tokenFor("EMAIL", "bob@acme.io");
        String alice = session.tokenFor("EMAIL", "alice@acme.io");

        assertThat(session.restore("forward " + bob + " to " + alice))
            .isEqualTo("forward bob@acme.io to alice@acme.io");
    }

    @Test
    void testRestoreLeavesAnUnknownTokenUntouched() {
        PiiTokenSession session = PiiTokenSession.create();

        String text = "see [PII_EMAIL_9_" + session.sessionId() + "]";

        assertThat(session.restore(text)).isEqualTo(text);
    }

    @Test
    void testRestoreLeavesAForeignSessionTokenUntouched() {
        PiiTokenSession session = PiiTokenSession.create();
        PiiTokenSession other = PiiTokenSession.create();

        String foreign = other.tokenFor("EMAIL", "bob@acme.io");

        assertThat(session.restore("see " + foreign)).isEqualTo("see " + foreign);
    }

    @Test
    void testCloseClearsTheMapping() {
        PiiTokenSession session = PiiTokenSession.create();

        String token = session.tokenFor("EMAIL", "bob@acme.io");

        session.close();

        assertThat(session.size()).isZero();
        assertThat(session.restore("see " + token)).isEqualTo("see " + token);
    }

    @Test
    void testSessionsGetDistinctIds() {
        Set<String> ids = new java.util.HashSet<>();

        for (int attempt = 0; attempt < 50; attempt++) {
            ids.add(PiiTokenSession.create()
                .sessionId());
        }

        assertThat(ids).hasSizeGreaterThan(1);
    }

    @Test
    void testConcurrentMintingKeepsOneTokenPerValue() throws Exception {
        PiiTokenSession session = PiiTokenSession.create();

        ExecutorService executorService = Executors.newFixedThreadPool(8);

        try {
            List<Future<String>> futures = new ArrayList<>();

            for (int attempt = 0; attempt < 200; attempt++) {
                futures.add(executorService.submit(() -> session.tokenFor("EMAIL", "bob@acme.io")));
            }

            Set<String> minted = ConcurrentHashMap.newKeySet();

            for (Future<String> future : futures) {
                minted.add(future.get());
            }

            assertThat(minted).hasSize(1);
            assertThat(session.size()).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*PiiTokenSessionTest' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E 'error:' /tmp/t2.log | head -3
```

Expected: FAIL — `PiiTokenSession` does not exist.

- [ ] **Step 3: Write the implementation**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/**
 * Holds the token-to-value mapping for one request, mints stable tokens, and substitutes values back.
 *
 * <p>
 * <b>The mapping is sensitive data.</b> For as long as this session lives, it retains every PII value the detectors
 * found. {@link #close()} clears it, and callers must call it on every termination path — completion, error and
 * cancellation alike. A session that outlives its request is a PII store nobody designed.
 * </p>
 *
 * <p>
 * Thread-safe by construction: the streaming path is Reactor, so {@code push} is not guaranteed to stay on one thread
 * across a single stream. {@code computeIfAbsent} on a concurrent map is what makes "the same value always gets the
 * same token" hold under concurrency rather than only in a single-threaded test.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class PiiTokenSession {

    private static final String SESSION_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AtomicInteger nextOrdinal = new AtomicInteger(1);
    private final String sessionId;
    private final Map<String, String> tokenToValue = new ConcurrentHashMap<>();
    private final Map<String, String> valueToToken = new ConcurrentHashMap<>();

    private PiiTokenSession(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Creates a session with a fresh random discriminator.
     *
     * @return the new session
     */
    public static PiiTokenSession create() {
        StringBuilder builder = new StringBuilder(PiiToken.SESSION_ID_LENGTH);

        for (int index = 0; index < PiiToken.SESSION_ID_LENGTH; index++) {
            builder.append(SESSION_ID_ALPHABET.charAt(SECURE_RANDOM.nextInt(SESSION_ID_ALPHABET.length())));
        }

        return new PiiTokenSession(builder.toString());
    }

    /**
     * Returns the token standing for {@code value}, minting one on first sight. The same value always yields the same
     * token within a session, which is what lets a model see that two mentions are one person.
     *
     * @param category the span category the value was detected as
     * @param value    the detected value
     * @return the token text
     */
    public String tokenFor(String category, String value) {
        return valueToToken.computeIfAbsent(value, presentValue -> {
            String token = new PiiToken(category, nextOrdinal.getAndIncrement(), sessionId).text();

            tokenToValue.put(token, presentValue);

            return token;
        });
    }

    /**
     * Substitutes every token this session minted back to its value. A token this session does not know — an unknown
     * ordinal, or one minted by another session — is left exactly as it is, so an anomaly surfaces visibly instead of
     * becoming a silent wrong substitution.
     *
     * @param text the text to restore
     * @return the text with known tokens replaced by their values
     */
    public String restore(String text) {
        if (text == null || text.isEmpty() || tokenToValue.isEmpty()) {
            return text;
        }

        Matcher matcher = PiiToken.pattern()
            .matcher(text);

        StringBuilder builder = new StringBuilder();

        while (matcher.find()) {
            String value = tokenToValue.get(matcher.group());

            matcher.appendReplacement(builder, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }

        matcher.appendTail(builder);

        return builder.toString();
    }

    /**
     * Returns this session's discriminator.
     *
     * @return the session id
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns how many distinct values this session holds.
     *
     * @return the mapping size
     */
    public int size() {
        return tokenToValue.size();
    }

    /**
     * Clears the mapping. Must be called on every termination path.
     */
    public void close() {
        tokenToValue.clear();
        valueToToken.clear();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t2.log || echo "no failed tasks"
```

Expected: exit=0, all 8 tests pass.

**If SpotBugs flags `EI_EXPOSE_REP` on the maps**, they are private and never returned, so it should not — if it does, read the HTML report and report what it actually says rather than reaching for a suppression.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Add the request-scoped PII token session"
```

---

## Task 3: Two treatments in one pass

**Files:**
- Modify: `.../guardrails/detector/SensitiveDataRedactor.java`
- Test: `.../guardrails/detector/SensitiveDataRedactorTest.java`

**Interfaces:**
- Consumes: `PiiTokenSession` (Task 2).
- Produces: `SensitiveDataRedactor.tokenizeWithSpans(String text, Set<SensitiveKind> kinds, PiiTokenSession session, @Nullable AiGuardrailMetrics metrics)` → `RedactionResult`; `apply(String, List<SensitiveSpan>, Function<SensitiveSpan, String>)` package-private.

**This is the crux of the design.** `apply` already replaces each span with `span.placeholder()` — a pure function of the span. Tokenization makes the replacement a function of *(span, session)*, so PII and SECRET get different treatments from one pass over the same resolved spans. There is no second pipeline.

- [ ] **Step 1: Write the failing test**

Add to `SensitiveDataRedactorTest`:

```java
    @Test
    void testTokenizesPiiAndLeavesSecretsRedacted() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String text = "mail bob@example.com about AKIAIOSFODNN7EXAMPLE";

        String tokenized = redactor.tokenizeWithSpans(text, BOTH, session, null)
            .text();

        assertThat(tokenized).contains("[PII_EMAIL_1_" + session.sessionId() + "]");
        assertThat(tokenized).contains("[REDACTED_SECRET]");
        assertThat(tokenized).doesNotContain("bob@example.com");
        assertThat(tokenized).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    /**
     * The motivating case: two different addresses must not collapse into one indistinguishable string.
     */
    @Test
    void testTwoDifferentValuesBecomeTwoDifferentTokens() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String tokenized = redactor
            .tokenizeWithSpans("forward bob@acme.io's note to alice@acme.io", BOTH, session, null)
            .text();

        assertThat(tokenized).contains("[PII_EMAIL_1_" + session.sessionId() + "]");
        assertThat(tokenized).contains("[PII_EMAIL_2_" + session.sessionId() + "]");
    }

    @Test
    void testTheSameValueTwiceBecomesTheSameToken() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String tokenized = redactor
            .tokenizeWithSpans("bob@acme.io told bob@acme.io", BOTH, session, null)
            .text();

        assertThat(tokenized).isEqualTo(
            "[PII_EMAIL_1_" + session.sessionId() + "] told [PII_EMAIL_1_" + session.sessionId() + "]");
    }

    @Test
    void testTokenizingThenRestoringIsTheIdentityForPii() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String text = "forward bob@acme.io's note to alice@acme.io";

        String restored = session.restore(
            redactor.tokenizeWithSpans(text, BOTH, session, null)
                .text());

        assertThat(restored).isEqualTo(text);
    }

    @Test
    void testSecretsDoNotSurviveTheRoundTrip() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String text = "token AKIAIOSFODNN7EXAMPLE";

        String restored = session.restore(
            redactor.tokenizeWithSpans(text, BOTH, session, null)
                .text());

        assertThat(restored).isEqualTo("token [REDACTED_SECRET]");
        assertThat(restored).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }
```

Add the import `com.bytechef.ee.platform.ai.guardrails.tokenization.PiiTokenSession`.

**`testSecretsDoNotSurviveTheRoundTrip` is the constraint test for spec §3.** If it ever fails, a caught credential is being handed back into the output.

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*SensitiveDataRedactorTest' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E 'error:' /tmp/t3.log | head -3
```

Expected: FAIL — `tokenizeWithSpans` does not exist.

- [ ] **Step 3: Generalise `apply` and add `tokenizeWithSpans`**

Replace the existing `apply` with a two-argument form delegating to a three-argument one:

```java
    static String apply(String text, List<SensitiveSpan> accepted) {
        return apply(text, accepted, SensitiveSpan::placeholder);
    }

    /**
     * Replaces every accepted span with whatever {@code replacer} returns for it, working right to left so that each
     * replacement leaves the offsets of the spans not yet applied valid.
     *
     * <p>
     * The replacer is what lets one pass produce two treatments: redaction passes {@code SensitiveSpan::placeholder},
     * tokenization passes a function that mints a token for PII and keeps the placeholder for secrets. Without it,
     * tokenization would need a parallel copy of this loop.
     * </p>
     *
     * @param text     the original text the spans were located in
     * @param accepted non-overlapping spans, in any order
     * @param replacer produces the replacement text for one span
     * @return the rewritten text
     */
    static String apply(String text, List<SensitiveSpan> accepted, Function<SensitiveSpan, String> replacer) {
        if (accepted.isEmpty()) {
            return text;
        }

        List<SensitiveSpan> ordered = new ArrayList<>(accepted);

        ordered.sort(
            Comparator.comparingInt(SensitiveSpan::start)
                .reversed());

        StringBuilder builder = new StringBuilder(text);

        for (SensitiveSpan span : ordered) {
            builder.replace(span.start(), span.end(), replacer.apply(span));
        }

        return builder.toString();
    }
```

Then add the tokenizing entry point beside `redactWithSpans`:

```java
    /**
     * As {@link #redactWithSpans}, but PII spans become tokens minted by {@code session} while SECRET spans keep their
     * {@code [REDACTED_SECRET]} placeholder.
     *
     * <p>
     * Secrets deliberately do not tokenize. Restoring a secret would take a credential the guardrail successfully
     * caught and paste it back into the output, defeating the catch. The two treatments come from one pass over the
     * same resolved spans, so there is no second pipeline to drift.
     * </p>
     *
     * @param text    the text to tokenize
     * @param kinds   the kinds the caller's policy has enabled
     * @param session the session minting tokens for this request
     * @param metrics the metrics instance to count detector failures through, or {@code null}
     * @return the tokenized text and the spans applied to produce it
     */
    public RedactionResult tokenizeWithSpans(
        String text, Set<SensitiveKind> kinds, PiiTokenSession session, @Nullable AiGuardrailMetrics metrics) {

        if (text == null || text.isEmpty() || kinds.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> candidates = filterByKind(detectCandidates(text, metrics), kinds);

        if (candidates.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> accepted = resolve(candidates);

        String tokenized = apply(text, accepted, span -> {
            if (span.kind() == SensitiveKind.SECRET) {
                return span.placeholder();
            }

            return session.tokenFor(span.category(), text.substring(span.start(), span.end()));
        });

        return new RedactionResult(tokenized, accepted);
    }
```

Add imports: `java.util.function.Function`, `com.bytechef.ee.platform.ai.guardrails.tokenization.PiiTokenSession`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t3.log || echo "no failed tasks"
```

Expected: exit=0. Every pre-existing redaction test still passes — `redactWithSpans` is untouched, so this task adds a path rather than changing one.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Tokenize PII and redact secrets in one pass"
```

---

## Task 4: Engine entry points

**Files:**
- Modify: `.../guardrails/AiGuardrails.java`
- Test: `.../guardrails/AiGuardrailsTest.java`

**Interfaces:**
- Consumes: `tokenizeWithSpans` (Task 3), `PiiTokenSession` (Task 2).
- Produces: `AiGuardrails.newTokenSession()` → `PiiTokenSession`; `AiGuardrails.tokenizeInputs(List<String> inputs, Long workspaceId, PiiTokenSession session, AiGuardrailMetrics metrics)` → `List<GuardrailCheckResult>`; `AiGuardrails.restoreResponseText(String text, PiiTokenSession session)` → `String`.

`tokenizeInputs` mirrors `checkInputs` exactly — same blocked-term, injection and moderation handling — differing only in that PII becomes tokens. Blocked-term and injection checks continue to run on the *rewritten* text, as they do today.

- [ ] **Step 1: Write the failing test**

Add to `AiGuardrailsTest`:

```java
    @Test
    void testTokenizeInputsDistinguishesTwoValues() {
        AiGuardrails guardrails = guardrails(null, true, true, "", false, false);
        PiiTokenSession session = guardrails.newTokenSession();

        List<GuardrailCheckResult> results = guardrails.tokenizeInputs(
            List.of("forward bob@acme.io's note to alice@acme.io"), null, session, metrics);

        String text = results.getFirst()
            .text();

        assertThat(text).contains("[PII_EMAIL_1_" + session.sessionId() + "]");
        assertThat(text).contains("[PII_EMAIL_2_" + session.sessionId() + "]");
    }

    @Test
    void testRestoreResponseTextReversesTokenization() {
        AiGuardrails guardrails = guardrails(null, true, true, "", false, false);
        PiiTokenSession session = guardrails.newTokenSession();

        String original = "forward bob@acme.io's note to alice@acme.io";

        String tokenized = guardrails.tokenizeInputs(List.of(original), null, session, metrics)
            .getFirst()
            .text();

        assertThat(guardrails.restoreResponseText(tokenized, session)).isEqualTo(original);
    }

    @Test
    void testTokenizeInputsStillBlocksBlockedTerms() {
        AiGuardrails guardrails = guardrails(null, true, false, "classified", false, false);
        PiiTokenSession session = guardrails.newTokenSession();

        List<GuardrailCheckResult> results = guardrails.tokenizeInputs(
            List.of("the CLASSIFIED memo"), null, session, metrics);

        assertThat(results.getFirst()
            .category()).isEqualTo("blocked_term");
    }
```

Add the import `com.bytechef.ee.platform.ai.guardrails.tokenization.PiiTokenSession`.

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsTest' > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E 'error:' /tmp/t4.log | head -3
```

Expected: FAIL — `newTokenSession` / `tokenizeInputs` / `restoreResponseText` do not exist.

- [ ] **Step 3: Add the entry points**

```java
    /**
     * Returns a fresh token session for one request.
     *
     * @return the session; the caller owns closing it on every termination path
     */
    public PiiTokenSession newTokenSession() {
        return PiiTokenSession.create();
    }

    /**
     * The tokenizing counterpart of {@link #checkInputs}: identical blocked-term, injection and moderation handling,
     * differing only in that PII becomes session-minted tokens instead of {@code [REDACTED_*]} placeholders. Secrets
     * are still redacted irreversibly.
     *
     * @param inputs      the input strings
     * @param workspaceId the workspace the call is attributed to, or {@code null}
     * @param session     the session minting tokens for this request
     * @param metrics     the metrics instance to record through
     * @return one result per input, in order
     */
    public List<GuardrailCheckResult> tokenizeInputs(
        @Nullable List<String> inputs, @Nullable Long workspaceId, PiiTokenSession session,
        AiGuardrailMetrics metrics) {

        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        EffectivePolicy policy = resolvePolicy(workspaceId);
        List<GuardrailCheckResult> results = new ArrayList<>(inputs.size());

        for (String input : inputs) {
            results.add(checkInput(input, policy, metrics, session));
        }

        return results;
    }

    /**
     * Substitutes values back for tokens {@code session} minted. Runs AFTER response scanning — see the class javadoc
     * and the design spec's ordering rule; restoring first would let response scanning immediately re-redact what was
     * just restored, making the whole round trip a no-op.
     *
     * @param text    the model's response text, already scanned
     * @param session the session that tokenized the request
     * @return the text with known tokens restored
     */
    public String restoreResponseText(String text, PiiTokenSession session) {
        return session.restore(text);
    }
```

Then thread the session into the existing private path. Change `checkInput` and `redactPiiAndSecrets` to take a `@Nullable PiiTokenSession`, and in `redactPiiAndSecrets` choose the pass:

```java
        RedactionResult redactionResult = session == null
            ? sensitiveDataRedactor.redactWithSpans(content, kinds, recordingMetrics)
            : sensitiveDataRedactor.tokenizeWithSpans(content, kinds, session, recordingMetrics);
```

The existing `checkInputs` passes `null` for the session, so its behaviour is unchanged. Record `pii_tokenized` instead of `pii_redacted` when a session is present.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t4.log || echo "no failed tasks"
```

Expected: exit=0, every pre-existing test still green (the redaction path passes `null`).

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Add session-aware tokenize and restore entry points"
```

---

## Task 5: Advisor round trip

**Files:**
- Modify: `.../guardrails/advisor/AiGuardrailsAdvisor.java`
- Test: `.../guardrails/advisor/AiGuardrailsAdvisorTest.java`

**Interfaces:**
- Consumes: `newTokenSession`, `tokenizeInputs`, `restoreResponseText` (Task 4).
- Produces: nothing new; the advisor's public surface is unchanged.

**Request and response are one unit here** — a session created on the request must be visible to the response and closed on every termination. Do not split this across tasks.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testRoundTripRestoresDistinctValues() {
        AiGuardrails aiGuardrails = guardrails(true, true, "", false, false, false);

        when(settingsService.fetchSettings(WORKSPACE_ID)).thenReturn(Optional.empty());

        AiGuardrailsAdvisor advisor = new AiGuardrailsAdvisor(aiGuardrails, WORKSPACE_ID, advisorMetrics);
        ChatClientRequest request = requestWithUserMessage("forward bob@acme.io's note to alice@acme.io");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> forwardedCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);

        // Echo whatever the model was sent, so the assertion covers the full round trip.
        when(chain.nextCall(forwardedCaptor.capture()))
            .thenAnswer(invocation -> responseChunk(
                ((ChatClientRequest) invocation.getArgument(0)).prompt()
                    .getInstructions()
                    .getFirst()
                    .getText()));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        String forwarded = forwardedCaptor.getValue()
            .prompt()
            .getInstructions()
            .getFirst()
            .getText();

        assertThat(forwarded).doesNotContain("bob@acme.io");
        assertThat(forwarded).doesNotContain("alice@acme.io");
        assertThat(forwarded).containsPattern("\\[PII_EMAIL_1_[a-z0-9]{4}\\]");
        assertThat(forwarded).containsPattern("\\[PII_EMAIL_2_[a-z0-9]{4}\\]");

        String returned = Objects.requireNonNull(response.chatResponse())
            .getResult()
            .getOutput()
            .getText();

        assertThat(returned).isEqualTo("forward bob@acme.io's note to alice@acme.io");
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorTest' > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E 'expected|but was' /tmp/t5.log | head -3
```

Expected: FAIL — the forwarded text contains `[REDACTED_EMAIL]` twice, not two distinct tokens.

- [ ] **Step 3: Wire the advisor**

In `adviseCall`: create a session, use `tokenizeInputs` instead of `checkInputs`, and in a `finally` close the session. On the response side, keep the existing `scanResponseText` call and add restoration **after** it:

```java
        String scanned = aiGuardrails.scanResponseText(text, workspaceId, metrics);

        // Ordering is load-bearing: scanning first means it sees tokens (which match no PII pattern) plus any NEW PII
        // the model produced, so novel leakage is still caught. Restoring first would hand the scanner the real values
        // back and it would immediately re-redact them, making the round trip a no-op.
        String restored = aiGuardrails.restoreResponseText(scanned, session);
```

In `adviseStream`, create the session before subscribing and close it in `doFinally` so completion, error and cancellation all release it.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t5.log || echo "no failed tasks"
```

**Pre-existing advisor tests asserting `[REDACTED_EMAIL]` in forwarded text will now fail** — that is this task's contract change. Update those assertions here, in this task, to the token shape. Do NOT defer them; a task must be green at its own commit.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Tokenize on the request and restore after scanning the response"
```

---

## Task 6: Streaming

**Files:**
- Modify: `.../guardrails/StreamingResponseRedactor.java`
- Test: `.../guardrails/StreamingResponseRedactorTest.java`

**Interfaces:**
- Consumes: `PiiTokenSession` (Task 2).
- Produces: `StreamingResponseRedactor(SensitiveDataRedactor, PiiTokenSession)` and `(SensitiveDataRedactor, int window, PiiTokenSession)`.

**The failure this prevents:** a token split across chunks — `[PII_EMA` then `IL_1_k3n9]` — never matches on restore, and the user sees a raw token. Structurally this is what the existing safe-cut already solves for spans; tokens simply join the set of things the cut may not split.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testATokenIsNeverSplitAcrossEmittedChunks() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String token = session.tokenFor("EMAIL", "bob@acme.io");
        String text = "please contact " + token + " about the outage as soon as you can today";

        StreamingResponseRedactor streamingRedactor = new StreamingResponseRedactor(redactor, 16, session);

        StringBuilder emitted = new StringBuilder();

        for (int index = 0; index < text.length(); index++) {
            emitted.append(streamingRedactor.push(text.substring(index, index + 1)));
        }

        emitted.append(streamingRedactor.flush());

        assertThat(emitted.toString()).isEqualTo(
            "please contact bob@acme.io about the outage as soon as you can today");
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*StreamingResponseRedactorTest' > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E 'error:|expected' /tmp/t6.log | head -3
```

Expected: FAIL — no constructor taking a session.

- [ ] **Step 3: Extend the redactor**

The class already has four constructors — two public (`(redactor)`, `(redactor, metrics)`) and two package-private (`(redactor, window)`, `(redactor, window, metrics)`), all funnelling into the last. **Do not add a session variant of each**; that is eight constructors and an overload-resolution hazard. Instead:

- Make `(redactor, int window, @Nullable AiGuardrailMetrics metrics, @Nullable PiiTokenSession session)` the single canonical package-private constructor, and have all four existing ones delegate to it with `null` for the session.
- Add exactly one new **public** constructor `(redactor, @Nullable AiGuardrailMetrics metrics, @Nullable PiiTokenSession session)` for the advisor.
- Add exactly one new **package-private** constructor `(redactor, int window, @Nullable PiiTokenSession session)` for the tests.

`int` and `PiiTokenSession` are distinct enough that no call with a bare `null` becomes ambiguous — unlike the `newStreamingResponseRedactor(AiGuardrailMetrics)` / `(Long workspaceId)` collision already documented on that method. Verify this by compiling, not by reasoning.

In `push`, after computing the candidate spans, also collect token ranges via `PiiToken.pattern().matcher(carry)` and feed them into the same pull-back loop, so the safe cut never lands inside a token. After scanning an emitted segment, restore it:

```java
        String scanned = sensitiveDataRedactor.redact(rawSegment, EnumSet.allOf(SensitiveKind.class), metrics);
        String emitted = session == null ? scanned : session.restore(scanned);
```

Apply the same scan-then-restore in `flush`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t6.log || echo "no failed tasks"
```

Expected: exit=0, including the existing equivalence and anti-vacuity tests, which pass `null` for the session and so are unaffected.

- [ ] **Step 5: Prove the safe-cut extension has teeth**

Temporarily remove token ranges from the pull-back loop and confirm `testATokenIsNeverSplitAcrossEmittedChunks` FAILS. Revert, confirm it passes, and confirm with `git diff` that nothing of the temporary change survives. Report which failure you observed.

- [ ] **Step 6: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "Keep tokens whole across stream chunks and restore per segment"
```

---

## Task 7: Gateway threading and documentation

**Files:**
- Modify: `.../gateway/guardrail/AiGatewayGuardrails.java`
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/content/docs/platform/automation/deploy/ai-gateway.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new.

The gateway adapter sees both directions within one HTTP exchange — `apply(request)` and `redactResponse(response)`, both called by `AiGatewayFacadeImpl`. Thread a session between them. Where the facade cannot (a detached call), fall back to `null` and the existing redaction path.

- [ ] **Step 1: Thread the session through the gateway**

Add a session parameter to the gateway's request and response entry points, created by the facade per exchange and closed in a `finally`. Where no session is threaded, behaviour is exactly as today.

- [ ] **Step 2: Run the gateway module's check**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check > /tmp/t7.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t7.log || echo "no failed tasks"
```

Expected: exit=0. Update any assertion this breaks here, in this task.

- [ ] **Step 3: Update `.agents/ai-guardrails.md`**

In that document's existing voice, cover: PII tokenizes while **secrets stay destroyed**; the token format and what each part is for; the scan-then-restore ordering and why reversing it is a no-op; that the mapping is sensitive and closed on every termination path; the streaming safe-cut extension; and the new metrics.

State plainly that **this is a posture change** — a workspace that previously had values destroyed now has them restored — and that Phase 1's sessions are request-scoped, so cross-turn coherence arrives with Phase 2.

- [ ] **Step 4: Update the customer-facing documentation**

`docs/content/docs/platform/automation/deploy/ai-gateway.md` describes redaction behaviour to users. It must now describe tokenization truthfully: the model provider still never receives real values, and the values are restored in the response. Do not quietly reword — users are entitled to know their data now round-trips.

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/automation .agents/ai-guardrails.md docs/content
git commit -m "Thread a token session through the gateway and document tokenization"
```

---

## Task 8: Full verification

- [ ] **Step 1: Format and check every touched module**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew spotlessApply > /dev/null 2>&1
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-opennlp:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
          :server:apps:server-app:test --continue > /tmp/t8.log 2>&1
echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t8.log || echo "no failed tasks"
```

- [ ] **Step 2: Compile the whole server**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t8c.log 2>&1; echo "exit=$?"; grep -E '^> Task .* FAILED' /tmp/t8c.log || echo "no failed tasks"
```

- [ ] **Step 3: Confirm the spec's hard constraints hold**

Run each and report the result:

1. **No secret ever round-trips** — `grep -rn 'testSecretsDoNotSurviveTheRoundTrip' --include='*.java' server` returns the test, and it passes.
2. **No raw PII in a token** — `grep -rn 'PII_' --include='*.java' server | grep -v '/build/'` shows no code embedding a value into a token string.
3. **Sessions close on every path** — every `newTokenSession()` call site is paired with a `close()` in a `finally` or `doFinally`.
4. **Scan precedes restore** — in both `AiGuardrailsAdvisor` and `StreamingResponseRedactor`, the `restore` call appears after the scan call in the same method.

- [ ] **Step 4: Report**

State the check and compile results, the four confirmations, and the Task 6 Step 5 mutation evidence.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §3 secrets never round-trip | Task 3 (`testSecretsDoNotSurviveTheRoundTrip`), Task 8 §3.1 |
| §4 token format | Task 1 |
| §5 request-scoped fallback (Phase 1 scope) | Task 4 (`newTokenSession`), Task 5 |
| §6 session lifecycle | Task 2 (`close`), Task 5 (`finally` / `doFinally`) |
| §7 scan then restore | Task 5, Task 6, Task 8 §3.4 |
| §8 streaming safe cut | Task 6 |
| §9 failure behaviour | Task 2 (unknown/foreign tokens), Task 5 |
| §10 blast radius | Tasks 5, 7 (assertions updated where broken) |
| §10a phase boundary | Header |

Spec §6's persistent encrypted store and §5's conversation scope are **Phase 2** and deliberately absent.

**Placeholder scan:** no "TBD", "TODO", or "similar to Task N". Tasks 5, 6 and 7 describe edits to existing method bodies in prose plus the load-bearing snippets rather than reproducing whole files — those methods are long and quoting them entirely would date the plan against a moving file. Every new type and every new signature is given in full.

**Type consistency:** `PiiTokenSession.tokenFor(String, String)`, `restore(String)`, `sessionId()`, `size()`, `close()` and `create()` are used identically in Tasks 2–6. `tokenizeWithSpans(text, kinds, session, metrics)` keeps one argument order throughout. `PiiToken(category, ordinal, sessionId)` matches its use in Task 3's assertions.

**One risk I want the executor to watch:** Task 5 changes what the advisor forwards, so pre-existing tests asserting `[REDACTED_EMAIL]` in forwarded text fail there by design. The plan says to fix them in Task 5 rather than Task 7. If an assertion fails that is *not* about a PII placeholder, that is a real regression — report it rather than updating it.
