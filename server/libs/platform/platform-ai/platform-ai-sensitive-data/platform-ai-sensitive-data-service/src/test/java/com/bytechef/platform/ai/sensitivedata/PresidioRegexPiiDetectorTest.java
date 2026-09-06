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

    /**
     * Pins an incidental piece of behaviour so a future change to it is visible rather than silent: {@code US_SSN}
     * (<code>\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b</code>) and {@code AU_TFN} (<code>\b\d{9}\b</code>) both match a bare
     * 9-digit run at the same span, and {@link SensitiveDataRedactor}'s resolution order breaks the tie by category
     * ascending -- so a plain business identifier like a ticket number is labelled an Australian Tax File Number rather
     * than a US Social Security Number. See {@code PiiPatternCatalog}'s "Open question" javadoc note: this is left for
     * the spec owner, not treated as resolved.
     */
    @Test
    void testAmbiguousNineDigitRunResolvesToTheAlphabeticallyEarlierCategory() {
        List<SensitiveSpan> candidates = detector.detect("Ticket 987654321 escalated");

        assertThat(candidates)
            .extracting(SensitiveSpan::category)
            .contains("US_SSN", "AU_TFN");

        List<SensitiveSpan> accepted = SensitiveDataRedactor.resolve(candidates);

        assertThat(accepted)
            .extracting(SensitiveSpan::category)
            .containsExactly("AU_TFN");
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

    // NOTE: the design spec (docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md) and the task brief
    // both state the component's catalog has 37 entries. The actual source -- PiiDetectorUtils.DEFAULT_PII_PATTERNS,
    // verified by counting `new PiiPattern(` occurrences and cross-checked against the 1:1 parallel
    // getPiiDetectionOptions() list -- has 36. This is confirmed a stale figure in the spec (it never enumerates all
    // 37 names, and no other branch or file in this repository's history has a 37th pattern). The sizes below reflect
    // the actual, verbatim-copied catalog: 36 total, 33 curated (36 minus DATE_TIME, LOCATION and US_BANK_NUMBER --
    // see PiiPatternCatalog.LOW_SPECIFICITY_TYPES).
    @Test
    void testCatalogHasTheFullTaxonomyAndTheDefaultIsCurated() {
        assertThat(PiiPatternCatalog.ALL).hasSize(36);

        assertThat(PiiPatternCatalog.curatedDefault())
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .doesNotContain("DATE_TIME", "LOCATION", "US_BANK_NUMBER")
            .hasSize(33);
    }

    @Test
    void testLowSpecificityTypeIsStillSelectableFromTheCatalog() {
        assertThat(PiiPatternCatalog.filterByTypes(List.of("US_BANK_NUMBER")))
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .containsExactly("US_BANK_NUMBER");
    }

    /**
     * Pins spec §5a's behaviour, not just its set arithmetic: contextual text produces NO spans by default. The sample
     * must be chosen so no other catalog pattern matches it either — an ISO date and a bare street name, no digits
     * shaped like phones, ids or cards beyond the date itself.
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
