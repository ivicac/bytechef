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

package com.bytechef.platform.ai.sensitivedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    private static final Set<SensitiveKind> BOTH = EnumSet.allOf(SensitiveKind.class);

    @Test
    void testAppliesNonOverlappingSpansLeftToRightInResult() {
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true,
                SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 3),
                SensitiveSpan.of(SensitiveKind.PII, "IP", 4, 7)));

        assertThat(redactor.redact("abc def", BOTH, null)).isEqualTo("[REDACTED_EMAIL] [REDACTED_IP]");
    }

    @Test
    void testSecretBeatsOverlappingPii() {
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "CC", 5, 21)),
            fixed("secret", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 28)));

        assertThat(redactor.redact("xoxb-1234567890123456-abcdef", BOTH, null)).isEqualTo("[REDACTED_SECRET]");
    }

    @Test
    void testLongerSpanBeatsNestedSpanOfTheSameKind() {
        SensitiveDataRedactor redactor = redactor(
            fixed("outer", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 10)),
            fixed("inner", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 3, 6)));

        assertThat(redactor.redact("0123456789", BOTH, null)).isEqualTo("[REDACTED_SECRET]");
    }

    @Test
    void testLongerSpanBeatsPartiallyOverlappingShorterSpanOfTheSameKind() {
        SensitiveDataRedactor redactor = redactor(
            fixed("early", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 10)),
            fixed("longer", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 5, 20)));

        // Length wins over position: the later-but-longer span is accepted, the earlier one dropped.
        assertThat(redactor.redact("0123456789abcdefghij", BOTH, null)).isEqualTo("01234[REDACTED_SECRET]");
    }

    @Test
    void testEarlierStartWinsWhenKindAndLengthTieAndSpansOverlap() {
        SensitiveDataRedactor redactor = redactor(
            fixed("later", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 5, 15)),
            fixed("earlier", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 10)));

        // Same kind, same length (10), overlapping: position wins and the earlier-starting span is accepted.
        assertThat(redactor.redact("0123456789abcde", BOTH, null)).isEqualTo("[REDACTED_SECRET]abcde");
    }

    @Test
    void testEarlierCategoryWinsWhenKindLengthAndStartAllTie() {
        SensitiveDataRedactor redactor = redactor(
            fixed("beta", true, SensitiveSpan.of(SensitiveKind.SECRET, "BETA", 0, 5)),
            fixed("alpha", true, SensitiveSpan.of(SensitiveKind.SECRET, "ALPHA", 0, 5)));

        // Same kind, same length, same start -- the two spans overlap exactly, so only one can be accepted.
        // The alphabetically earlier category wins.
        assertThat(redactor.redact("01234", BOTH, null)).isEqualTo("[REDACTED_ALPHA]");
    }

    @Test
    void testTouchingSpansBothSurvive() {
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true,
                SensitiveSpan.of(SensitiveKind.PII, "IP", 0, 5),
                SensitiveSpan.of(SensitiveKind.PII, "IP", 5, 10)));

        assertThat(redactor.redact("0123456789", BOTH, null)).isEqualTo("[REDACTED_IP][REDACTED_IP]");
    }

    @Test
    void testResultIsIndependentOfDetectorOrder() {
        SensitiveDataDetector piiDetector = fixed(
            "pii", true, SensitiveSpan.of(SensitiveKind.PII, "CC", 5, 21));
        SensitiveDataDetector secretDetector = fixed(
            "secret", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 28));

        String text = "xoxb-1234567890123456-abcdef";

        List<SensitiveDataDetector> detectors = new ArrayList<>(List.of(piiDetector, secretDetector));
        String expected = new SensitiveDataRedactor(detectors).redact(text, BOTH, null);

        for (int attempt = 0; attempt < 20; attempt++) {
            Collections.shuffle(detectors);

            assertThat(new SensitiveDataRedactor(detectors).redact(text, BOTH, null)).isEqualTo(expected);
        }
    }

    @Test
    void testKindFilterIsAppliedBeforeResolutionSoAPiiOnlyCallStillRedacts() {
        // The SECRET span would win the overlap, but a PII-only caller never sees it -- filtering candidates before
        // resolution is what keeps single-toggle output identical to the pre-SPI engine. Filtering after resolution
        // would leave this text untouched, which is a redaction regression.
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "CC", 5, 21)),
            fixed("secret", true, SensitiveSpan.of(SensitiveKind.SECRET, "SECRET", 0, 28)));

        assertThat(redactor.redact("xoxb-1234567890123456-abcdef", EnumSet.of(SensitiveKind.PII), null))
            .isEqualTo("xoxb-[REDACTED_CC]-abcdef");
    }

    @Test
    void testFailingDetectorIsSkippedAndOthersStillApply() {
        // The event-name/surface-tag behaviour this used to assert through a real metrics implementation is EE's
        // (AiGuardrailMetrics#recordDetectorFailure) concern now -- see AiGuardrailMetricsTest for that coverage. This
        // module only owns the seam: the failing detector's name reaches SensitiveDataMetrics, and the other detector's
        // span still applies.
        RecordingMetrics metrics = new RecordingMetrics();

        SensitiveDataRedactor redactor = redactor(
            throwing("broken"),
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 3)));

        assertThat(redactor.redact("abc def", BOTH, metrics)).isEqualTo("[REDACTED_EMAIL] def");
        assertThat(metrics.failures).containsExactly("broken");
    }

    @Test
    void testFailingDetectorWithoutMetricsDoesNotThrow() {
        SensitiveDataRedactor redactor = redactor(throwing("broken"));

        assertThat(redactor.redact("abc def", BOTH, null)).isEqualTo("abc def");
    }

    @Test
    void testSpanBeyondTextIsTreatedAsDetectorFailure() {
        SensitiveDataRedactor redactor = redactor(
            fixed("rogue", true, SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 500)),
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "IP", 4, 7)));

        assertThat(redactor.redact("abc def", BOTH, null)).isEqualTo("abc [REDACTED_IP]");
    }

    @Test
    void testDetectorReportingOneOutOfBoundsSpanDiscardsAllOfItsSpans() {
        SensitiveDataRedactor redactor = redactor(
            fixed("mixed", true,
                SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 3),
                SensitiveSpan.of(SensitiveKind.PII, "IP", 0, 500)),
            fixed("valid", true, SensitiveSpan.of(SensitiveKind.PII, "SSN", 4, 7)));

        // The "mixed" detector reports one valid span (EMAIL) alongside one out-of-bounds span (IP). collectSpans
        // validates a detector's whole batch before adding any of it, so BOTH of "mixed"'s spans are discarded --
        // including the otherwise-valid EMAIL one. The "valid" detector's span is a separate detector and still
        // applies.
        assertThat(redactor.redact("abc def", BOTH, null)).isEqualTo("abc [REDACTED_SSN]");
    }

    @Test
    void testStreamSafeViewExcludesNonStreamSafeDetectors() {
        SensitiveDataRedactor redactor = redactor(
            fixed("local", true, SensitiveSpan.of(SensitiveKind.PII, "IP", 4, 7)),
            fixed("contextual", false, SensitiveSpan.of(SensitiveKind.PII, "PERSON", 0, 3)));

        assertThat(redactor.redact("abc def", BOTH, null)).isEqualTo("[REDACTED_PERSON] [REDACTED_IP]");
        assertThat(redactor.streamSafeView()
            .redact("abc def", BOTH, null)).isEqualTo("abc [REDACTED_IP]");
    }

    @Test
    void testEmptyTextIsReturnedUnchanged() {
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 3)));

        assertThat(redactor.redact("", BOTH, null)).isEmpty();
    }

    @Test
    void testEmptyKindSetRedactsNothing() {
        SensitiveDataRedactor redactor = redactor(
            fixed("pii", true, SensitiveSpan.of(SensitiveKind.PII, "EMAIL", 0, 3)));

        assertThat(redactor.redact("abc def", EnumSet.noneOf(SensitiveKind.class), null)).isEqualTo("abc def");
    }

    private static SensitiveDataRedactor redactor(SensitiveDataDetector... detectors) {
        return new SensitiveDataRedactor(List.of(detectors));
    }

    private static SensitiveDataDetector fixed(String name, boolean streamSafe, SensitiveSpan... spans) {
        return new SensitiveDataDetector() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                return List.of(spans);
            }

            @Override
            public boolean streamSafe() {
                return streamSafe;
            }
        };
    }

    private static SensitiveDataDetector throwing(String name) {
        return new SensitiveDataDetector() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public List<SensitiveSpan> detect(String text) {
                throw new IllegalStateException("detector is broken");
            }
        };
    }

    private static final class RecordingMetrics implements SensitiveDataMetrics {

        private final List<String> failures = new ArrayList<>();

        @Override
        public void recordDetectorFailure(String detectorName) {
            failures.add(detectorName);
        }
    }
}
