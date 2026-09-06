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
        SensitiveDataRedactor onlySsn = new SensitiveDataRedactor(
            List.of(new RegexPiiDetector(PiiPatternCatalog.filterByTypes(List.of("US_SSN")))));

        assertThat(onlySsn.redact("mail bob@acme.io now", Set.of(SensitiveKind.PII), null))
            .isEqualTo("mail bob@acme.io now");
    }

    /**
     * Two spans of different lengths and types in one text: replacing one must not shift the offsets the other is still
     * expressed in. This is the offset invariant the per-node sanitizer's {@code <TYPE>} notation used to own itself
     * and now inherits from the engine's right-to-left {@code apply} loop.
     */
    @Test
    void testTheReplacerPreservesOffsetsAcrossSeveralSpans() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(List.of(new RegexPiiDetector()));

        SensitiveDataRedactor.RedactionResult result = redactor.redactWithSpans(
            "call 555-123-4567 or email a@b.co", Set.of(SensitiveKind.PII),
            SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, null, List.of(), span -> "<" + span.category() + ">");

        assertThat(result.text()).isEqualTo("call <PHONE_NUMBER> or email <EMAIL_ADDRESS>");
    }
}
