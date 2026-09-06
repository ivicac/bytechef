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
class RegexPiiDetectorTest {

    private final RegexPiiDetector detector = new RegexPiiDetector();

    @Test
    void testDetectsEmailUnderItsEntityTypeName() {
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
     * Pins the resolution of the collision this test used to document: {@code US_SSN} previously carried a bare-9-digit
     * alternative (<code>\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b</code>) that matched the same span as {@code AU_TFN}'s
     * (<code>\b\d{9}\b</code>), so a plain business identifier like a ticket number was labelled an Australian Tax File
     * Number by {@link SensitiveDataRedactor}'s category-ascending tie-break. That alternative was dropped from
     * {@code US_SSN} (see {@code PiiPatternCatalog}'s class javadoc): {@code AU_TFN} alone now matches the bare-digit
     * span, so there is no longer a collision to resolve at that level.
     *
     * <p>
     * The candidate-level assertions below call {@link #detector}'s {@code detect} directly, which is a legitimate
     * unit-level check of the regex facts (what matches, what doesn't). The outcome that matters in production is what
     * happens next, and that requires going through {@link SensitiveDataRedactor}'s actual public pipeline —
     * {@code redactWithSpans}, which runs {@code filterByConfidence} before {@code resolve} — rather than calling
     * {@code resolve} on the unfiltered candidates directly, which would skip the confidence filter and assert an
     * outcome ({@code AU_TFN} winning the tie-break) the real pipeline never produces at the default threshold:
     * {@code AU_TFN} scores Low ({@code 0.2}), below {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}, so it is
     * dropped before resolution ever runs and the text is not redacted at all. See {@code PiiPatternCatalog}'s class
     * javadoc and {@code .agents/ai-guardrails.md}'s "Confidence scoring" section: this is the intended coverage
     * reduction the rubric produces, not detection continuing under a different label.
     * </p>
     */
    @Test
    void testAmbiguousNineDigitRunIsNotRedactedThroughTheRealPipeline() {
        String text = "Ticket 987654321 escalated";

        List<SensitiveSpan> candidates = detector.detect(text);

        assertThat(candidates)
            .extracting(SensitiveSpan::category)
            .contains("AU_TFN")
            .doesNotContain("US_SSN");

        SensitiveDataRedactor redactor = new SensitiveDataRedactor(List.of(detector));

        SensitiveDataRedactor.RedactionResult result = redactor.redactWithSpans(
            text, Set.of(SensitiveKind.PII), null);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.accepted()).isEmpty();
    }

    /**
     * Group 1 (2026-08-31, reversing spec decision D10 for {@code CREDIT_CARD} only): the pattern's separators are
     * optional again, so it matches both formatted and bare 16-digit card numbers, but a Luhn checksum
     * ({@code PiiPatternCatalog.PiiPattern#validator()}) gates which of those the detector actually emits a span for. A
     * Luhn-invalid 16-digit run -- the exact shape of an ordinary order number -- must not be reported as
     * {@code CREDIT_CARD} at all, not merely scored low; the alternative is a Luhn-valid bare number, which must still
     * be detected even with no separators.
     */
    @Test
    void testCreditCardDetectionRequiresLuhnValidChecksum() {
        assertThat(detector.detect("card 4532015112830366 ok"))
            .extracting(SensitiveSpan::category)
            .contains("CREDIT_CARD");

        assertThat(detector.detect("Order 1234567890123456 shipped"))
            .extracting(SensitiveSpan::category)
            .doesNotContain("CREDIT_CARD");
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
    // the actual, verbatim-copied catalog: 36 total, 34 curated (36 minus DATE_TIME and LOCATION -- confidence
    // scoring, not set membership, is what now keeps low-specificity patterns like US_BANK_NUMBER from firing on
    // ordinary business text; see PiiPatternCatalog's class javadoc).
    @Test
    void testCatalogHasTheFullTaxonomyAndTheDefaultIsCurated() {
        assertThat(PiiPatternCatalog.ALL).hasSize(36);

        assertThat(PiiPatternCatalog.curatedDefault())
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .doesNotContain("DATE_TIME", "LOCATION")
            .hasSize(34);
    }

    @Test
    void testLowSpecificityTypeIsStillSelectableFromTheCatalog() {
        assertThat(PiiPatternCatalog.filterByTypes(List.of("US_BANK_NUMBER")))
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .containsExactly("US_BANK_NUMBER");
    }

    /**
     * {@code US_BANK_NUMBER} returns to the curated set now that confidence scoring, not set membership, decides
     * whether it survives redaction -- it no longer fires on business text because it scores low, not because it was
     * excluded by name.
     */
    @Test
    void testCuratedDefaultExcludesOnlyTheContextualTypes() {
        assertThat(PiiPatternCatalog.curatedDefault())
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .doesNotContain("DATE_TIME", "LOCATION")
            .contains("US_BANK_NUMBER");
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

    @Test
    void testSpanCarriesItsPatternScoreRatherThanFullConfidence() {
        List<SensitiveSpan> spans = detector.detect("mail bob@acme.io please");

        SensitiveSpan emailSpan = spans.stream()
            .filter(span -> "EMAIL_ADDRESS".equals(span.category()))
            .findFirst()
            .orElseThrow();

        double expected = PiiPatternCatalog.ALL.stream()
            .filter(piiPattern -> "EMAIL_ADDRESS".equals(piiPattern.type()))
            .findFirst()
            .orElseThrow()
            .score();

        assertThat(emailSpan.confidence()).isEqualTo(expected);
    }

    /**
     * "account 123456789012" matches only {@code US_BANK_NUMBER} (score {@code 0.2}) and "mail bob@acme.io" matches
     * only {@code EMAIL_ADDRESS} (score {@code 0.9}) under {@link PiiPatternCatalog#curatedDefault()} — verified by
     * checking every curated pattern, not assumed. A text that matched two categories would make "the first span"
     * incidental (span order is detector-iteration order, not meaning), which is exactly the defect
     * {@link #confidenceOfCategory} is written to avoid: it looks a span up by the category the assertion is actually
     * about, so the assertion keeps meaning what it says even if a text later starts matching more than one pattern.
     *
     * <p>
     * This text previously matched {@code PHONE_NUMBER} instead — a bare 12-digit run, with no separators, matched the
     * pre-fix {@code PHONE_NUMBER} pattern because both of its {@code [-\s.]} separators were optional. Once both were
     * made mandatory (see {@code PiiPatternCatalog}'s {@code PHONE_NUMBER} comment), this text stopped matching
     * {@code PHONE_NUMBER} and now falls through to {@code US_BANK_NUMBER} instead, which is still weak relative to
     * {@code EMAIL_ADDRESS} and now correctly reflects that a bare digit run is Low, not Medium, specificity.
     * </p>
     */
    @Test
    void testWeakPatternProducesALowerConfidenceSpanThanAStrongOne() {
        double weak = confidenceOfCategory("account 123456789012", "US_BANK_NUMBER");
        double strong = confidenceOfCategory("mail bob@acme.io", "EMAIL_ADDRESS");

        assertThat(weak).isLessThan(strong);
    }

    private double confidenceOfCategory(String text, String category) {
        List<SensitiveSpan> spans = detector.detect(text);

        return spans.stream()
            .filter(span -> category.equals(span.category()))
            .findFirst()
            .orElseThrow()
            .confidence();
    }
}
