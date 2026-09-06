/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetector;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataDetectors;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class StreamingResponseRedactorTest {

    private static final String AWS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String OPENAI_KEY = "sk-abcdefghij1234567890ABCD";

    // Deliberately sensitive-value-free filler -- no emails, digit runs, or key shapes -- appended to every
    // testStreamedOutputEqualsWholeTextRedaction corpus entry so each one comfortably exceeds the largest tested
    // window (512). Without this, push() never triggers an emit for these short entries and the test degenerates
    // into comparing flush() against an identical whole-text redaction, which proves nothing about the streaming
    // cut logic the class exists for -- see that test's pushEmittedSomething guard, which catches a future corpus
    // or window change that reintroduces the gap.
    private static final String CLEAN_SENTENCE =
        "The system processed a routine batch of records without incident and every check completed cleanly "
            + "within the expected time for this environment. ";
    private static final String CLEAN_TAIL = CLEAN_SENTENCE.repeat(6)
        .strip();

    private final SensitiveDataRedactor builtInRedactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

    @Test
    void testShortCleanInputHeldUntilFlush() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 64);

        assertThat(redactor.push("hello ")).isEmpty();
        assertThat(redactor.push("world")).isEmpty();
        assertThat(redactor.flush()).isEqualTo("hello world");
    }

    @Test
    void testFlushIsEmptyAfterDraining() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 8);

        redactor.push("some text");
        redactor.flush();

        assertThat(redactor.flush()).isEmpty();
    }

    @Test
    void testSecretWellBeforeBoundaryIsRedactedAndEmitted() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 32);

        StringBuilder emitted = new StringBuilder();

        emitted.append(redactor.push("token " + AWS_KEY + " and a long tail of clean words to push past window"));
        emitted.append(redactor.flush());

        assertThat(emitted.toString()).contains("[REDACTED_SECRET]");
        assertThat(emitted.toString()).doesNotContain(AWS_KEY);
    }

    @Test
    void testSecretSplitAcrossPushesIsNeverLeaked() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 32);

        List<String> emissions = new ArrayList<>();

        // Feed the AWS key split down the middle across two pushes, then trailing context.
        emissions.add(redactor.push("prefix AKIAIOSF"));
        emissions.add(redactor.push("ODNN7EXAMPLE trailing context that is long enough to move past the window"));
        emissions.add(redactor.flush());

        String all = String.join("", emissions);

        assertThat(all).contains("[REDACTED_SECRET]");
        assertThat(all).doesNotContain(AWS_KEY);

        for (String emission : emissions) {
            assertThat(emission).doesNotContain("AKIAIOSF");
        }
    }

    @Test
    void testCharByCharFeedNeverLeaksAndMatchesWholeBufferRedaction() {
        String full = "intro AKIAIOSFODNN7EXAMPLE middle " + OPENAI_KEY + " and some closing words here.";

        // The window must exceed the longest token (OpenAI key, 27 chars) for the no-leak guarantee to hold.
        for (int window : new int[] {
            32, 64, 128
        }) {
            StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, window);

            List<String> emissions = new ArrayList<>();

            for (int i = 0; i < full.length(); i++) {
                emissions.add(redactor.push(String.valueOf(full.charAt(i))));
            }

            emissions.add(redactor.flush());

            String reassembled = String.join("", emissions);

            assertThat(reassembled)
                .as("window=%d reassembled equals whole-buffer redaction", window)
                .isEqualTo(builtInRedactor.redact(full, EnumSet.allOf(SensitiveKind.class), null));

            for (String emission : emissions) {
                assertThat(emission)
                    .as("window=%d no emission leaks a raw secret", window)
                    .doesNotContain(AWS_KEY)
                    .doesNotContain(OPENAI_KEY);
            }
        }
    }

    @Test
    void testCleanStreamReassemblesUnchanged() {
        String full = "The quarterly report is ready and the dashboard has been refreshed for the whole team today.";

        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 16);

        StringBuilder reassembled = new StringBuilder();

        for (String word : full.split(" ")) {
            reassembled.append(redactor.push(word + " "));
        }

        reassembled.append(redactor.flush());

        assertThat(reassembled.toString()).isEqualTo(full + " ");
    }

    @Test
    void testNullAndEmptyPushesAreNoOps() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 8);

        assertThat(redactor.push(null)).isEmpty();
        assertThat(redactor.push("")).isEmpty();
        assertThat(redactor.flush()).isEmpty();
    }

    @Test
    void testIsRedactedTrueAfterMaskingSecret() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 32);

        redactor.push("token " + AWS_KEY + " and a long tail of clean words to push past the window");
        redactor.flush();

        assertThat(redactor.isRedacted()).isTrue();
    }

    @Test
    void testIsRedactedFalseForCleanStream() {
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 16);

        redactor.push("all clean text here with nothing sensitive at all across this stream of words");
        redactor.flush();

        assertThat(redactor.isRedacted()).isFalse();
    }

    @Test
    void testStreamedOutputEqualsWholeTextRedaction() {
        SensitiveDataRedactor sensitiveDataRedactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        // Sensitive values stay near the start of each entry; CLEAN_TAIL only pads length so push()'s safe-cut
        // logic actually runs (see the field javadoc above).
        List<String> corpus = List.of(
            "contact bob@example.com about AKIAIOSFODNN7EXAMPLE today " + CLEAN_TAIL,
            "token sk-proj-abcdefghijklmnopqrstuvwx and card 4111 1111 1111 1111 " + CLEAN_TAIL,
            "no sensitive content whatsoever in this sentence " + CLEAN_TAIL,
            "xoxb-1234567890123456-abcdef trailing text " + CLEAN_TAIL,
            "ip 10.0.0.5 phone 555-123-4567 ssn 123-45-6789 " + CLEAN_TAIL);

        for (String text : corpus) {
            String expected = sensitiveDataRedactor.redact(text, EnumSet.allOf(SensitiveKind.class), null);

            // Windows below the corpus's longest sensitive value (the 32-character sk-proj- secret) are deliberately
            // excluded: the class javadoc's equivalence guarantee only holds once the window exceeds the longest value
            // being redacted, so asserting it here for a smaller window would pin a promise this class never makes.
            // testValueLongerThanTheWindowMayHaveAPrefixEmitted pins the opposite, documented behavior for that case.
            for (int window : new int[] {
                64, 128, 512
            }) {
                for (int chunkSize : new int[] {
                    1, 3, 7, 100
                }) {
                    StreamingResponseRedactor redactor =
                        new StreamingResponseRedactor(sensitiveDataRedactor, window);
                    StringBuilder emitted = new StringBuilder();
                    boolean pushEmittedSomething = false;

                    for (int index = 0; index < text.length(); index += chunkSize) {
                        String pushed =
                            redactor.push(text.substring(index, Math.min(index + chunkSize, text.length())));

                        if (!pushed.isEmpty()) {
                            pushEmittedSomething = true;
                        }

                        emitted.append(pushed);
                    }

                    emitted.append(redactor.flush());

                    // Anti-vacuity guard: without at least one non-empty push(), this combination never exercised the
                    // safe-cut pull-back logic and the equality assertion below would just be comparing flush() against
                    // an identical whole-text redaction -- a tautology, not a streaming-correctness check. A future
                    // corpus or window change that stops triggering an emit fails loudly here instead of passing
                    // silently.
                    assertThat(pushEmittedSomething)
                        .as(
                            "text=%s window=%d chunkSize=%d: no push() call emitted anything, so this combination "
                                + "never exercised the streaming cut logic",
                            text, window, chunkSize)
                        .isTrue();

                    assertThat(emitted.toString())
                        .as("text=%s window=%d chunkSize=%d", text, window, chunkSize)
                        .isEqualTo(expected);
                }
            }
        }
    }

    /**
     * Pins the bounded-window trade-off documented on this class's javadoc: a value longer than the window may have a
     * prefix emitted before its pattern can match, because the sliding buffer evicts the value's earlier characters
     * before its later characters arrive to complete the pattern. Window 8 is shorter than the AWS key (20 characters)
     * in this text, so streaming it one character at a time never lets the buffer hold the whole key at once — the key
     * is never detected, and the raw key survives verbatim in the emitted output.
     */
    @Test
    void testValueLongerThanTheWindowMayHaveAPrefixEmitted() {
        String text = "contact bob@example.com about " + AWS_KEY + " today";

        StreamingResponseRedactor redactor = new StreamingResponseRedactor(builtInRedactor, 8);
        StringBuilder emitted = new StringBuilder();

        for (int index = 0; index < text.length(); index++) {
            emitted.append(redactor.push(String.valueOf(text.charAt(index))));
        }

        emitted.append(redactor.flush());

        String wholeTextRedaction = builtInRedactor.redact(text, EnumSet.allOf(SensitiveKind.class), null);

        assertThat(emitted.toString()).isNotEqualTo(wholeTextRedaction);
        assertThat(emitted.toString()).contains(AWS_KEY);
    }

    @Test
    void testNonStreamSafeDetectorDoesNotContributeToTheStream() {
        SensitiveDataDetector contextual = new SensitiveDataDetector() {

            @Override
            public String name() {
                return "contextual";
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                int index = text.indexOf("Ada");

                if (index < 0) {
                    return List.of();
                }

                return List.of(SensitiveSpan.of(SensitiveKind.PII, "PERSON", index, index + 3));
            }

            @Override
            public boolean streamSafe() {
                return false;
            }
        };

        SensitiveDataRedactor fullRedactor = new SensitiveDataRedactor(List.of(contextual));

        assertThat(fullRedactor.redact("Ada wrote it", EnumSet.allOf(SensitiveKind.class), null))
            .isEqualTo("[REDACTED_PERSON] wrote it");

        StreamingResponseRedactor redactor = new StreamingResponseRedactor(fullRedactor.streamSafeView(), 4);

        String emitted = redactor.push("Ada wrote it") + redactor.flush();

        assertThat(emitted).isEqualTo("Ada wrote it");
    }

    /**
     * A detector that fails while redacting a streamed completion must still be counted. The streaming path previously
     * passed a literal {@code null} metrics instance at every call site, so a failure here was invisible on every
     * deployment, gateway-enabled or not.
     */
    @Test
    void testDetectorFailureDuringStreamingIsRecorded() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        SensitiveDataRedactor failingRedactor = new SensitiveDataRedactor(List.of(throwingDetector()));
        StreamingResponseRedactor redactor = new StreamingResponseRedactor(failingRedactor, 8, metrics);

        String emitted = redactor.push("contact bob@example.com about the outage today") + redactor.flush();

        assertThat(emitted).isEqualTo("contact bob@example.com about the outage today");
        assertThat(
            meterRegistry.counter(AiGuardrailMetrics.COUNTER_NAME, "event", "detector_failed", "surface", "ai_hub")
                .count()).isGreaterThan(0.0);
    }

    @Test
    void testATokenIsNeverSplitAcrossEmittedChunks() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String token = session.tokenFor("EMAIL", "bob@acme.io");
        String text = "please contact " + token + " about the outage as soon as you can today";

        // A token this shape (category EMAIL, single-digit ordinal, 4-char session id) is 18 characters
        // ("[PII_EMAIL_1_xxxx]"). The window must comfortably exceed that, same rule as every other char-by-char
        // test in this file (see testCharByCharFeedNeverLeaksAndMatchesWholeBufferRedaction's comment) -- a window
        // only 1-2 characters short of the token's length reproduces exactly the documented
        // testValueLongerThanTheWindowMayHaveAPrefixEmitted trade-off, where the token's opening bracket is evicted
        // one push before the closing bracket arrives and the token can never be recognised as a whole.
        StreamingResponseRedactor streamingRedactor = new StreamingResponseRedactor(redactor, 32, session);

        StringBuilder emitted = new StringBuilder();

        for (int index = 0; index < text.length(); index++) {
            emitted.append(streamingRedactor.push(text.substring(index, index + 1)));
        }

        emitted.append(streamingRedactor.flush());

        assertThat(emitted.toString()).isEqualTo(
            "please contact bob@acme.io about the outage as soon as you can today");
    }

    /**
     * {@link PiiTokenSession#restore} alone throws away how many tokens it could not resolve, so the streaming path
     * must go through {@link PiiTokenSession#restoreWithUnresolvedCount} and record the same two metrics
     * {@code AiGuardrails#restoreResponseText} records for the non-streaming path, or a token substitution on the
     * streaming path would be invisible to metrics entirely.
     */
    @Test
    void testRestorationRecordsPiiRestoredMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL", "bob@acme.io");

        StreamingResponseRedactor streamingRedactor = new StreamingResponseRedactor(redactor, metrics, session);

        String emitted = streamingRedactor.push("contact " + token + " today") + streamingRedactor.flush();

        assertThat(emitted).isEqualTo("contact bob@acme.io today");
        assertThat(
            meterRegistry.counter(AiGuardrailMetrics.COUNTER_NAME, "event", "pii_restored", "surface", "ai_hub")
                .count()).isGreaterThan(0.0);
        assertThat(
            meterRegistry.find(AiGuardrailMetrics.COUNTER_NAME)
                .tag("event", "token_unresolved")
                .counter())
                    .as("no token was unresolved, so this counter should never have been touched")
                    .isNull();
    }

    /**
     * The alarm signal {@code token_unresolved} exists for: a token-shaped span this session never minted (here, one
     * minted by a different session) is left completely untouched in the output -- not restored, not stripped -- and
     * the miss is still counted, so an operator watching this metric on the exact path this task made live is not
     * flying blind.
     */
    @Test
    void testRestorationRecordsTokenUnresolvedMetricForForeignToken() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        // PiiTokenSession#restoreWithUnresolvedCount short-circuits (skips scanning entirely) when this session has
        // minted nothing at all -- so an empty session correctly restores nothing, but would also never notice a
        // foreign token, for the same reason a session that minted nothing has nothing to compare against. Minting
        // one token of our own is what makes the foreign token below a genuine miss rather than an untested case.
        session.tokenFor("EMAIL", "known@acme.io");

        PiiTokenSession foreignSession = PiiTokenSession.create();
        String foreignToken = foreignSession.tokenFor("EMAIL", "someone@else.example.com");

        StreamingResponseRedactor streamingRedactor = new StreamingResponseRedactor(redactor, metrics, session);

        String emitted = streamingRedactor.push("contact " + foreignToken + " today") + streamingRedactor.flush();

        assertThat(emitted).isEqualTo("contact " + foreignToken + " today");
        assertThat(
            meterRegistry.counter(AiGuardrailMetrics.COUNTER_NAME, "event", "token_unresolved", "surface", "ai_hub")
                .count()).isGreaterThan(0.0);
    }

    /**
     * Restoration is independent of scanning (see the class javadoc's "Restoration is independent of scanning"
     * paragraph): with an empty {@code kinds} set -- the shape {@code AiGuardrails} uses when the operator has not
     * opted into streaming response scanning -- a session's minted token still restores, char by char, without ever
     * being split, while a genuinely new email in the same text is left completely alone. The un-redacted new email is
     * the load-bearing assertion: without it, a bug that accidentally still scanned with every kind would pass this
     * test too, since it would also restore the token (scanning first is a no-op over a token, which matches no PII
     * pattern) and this test would not catch a reversal of the "restoration is independent of scanning" invariant.
     */
    @Test
    void testTokenRestoredWithoutScanningAndNeverSplitAcrossChunks() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        PiiTokenSession session = PiiTokenSession.create();

        String token = session.tokenFor("EMAIL", "bob@acme.io");
        String text = "please contact " + token + " about jane.doe@example.com and the outage today";

        // Same window precondition as testATokenIsNeverSplitAcrossEmittedChunks (32, comfortably above the token's
        // 18 characters) -- restoration still needs the token to survive intact in the buffer, even though scanning
        // itself is off.
        StreamingResponseRedactor streamingRedactor =
            new StreamingResponseRedactor(redactor, 32, null, session, EnumSet.noneOf(SensitiveKind.class));

        StringBuilder emitted = new StringBuilder();

        for (int index = 0; index < text.length(); index++) {
            emitted.append(streamingRedactor.push(text.substring(index, index + 1)));
        }

        emitted.append(streamingRedactor.flush());

        assertThat(emitted.toString()).contains("bob@acme.io");
        assertThat(emitted.toString()).doesNotContain(token);
        assertThat(emitted.toString()).contains("jane.doe@example.com");
        assertThat(emitted.toString()).doesNotContain("[REDACTED_EMAIL]");
    }

    private static SensitiveDataDetector throwingDetector() {
        return new SensitiveDataDetector() {

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                throw new IllegalStateException("detector is broken");
            }
        };
    }
}
