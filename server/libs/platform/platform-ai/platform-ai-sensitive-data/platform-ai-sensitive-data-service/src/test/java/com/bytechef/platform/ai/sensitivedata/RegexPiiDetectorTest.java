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

import java.util.EnumSet;
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
    // both state the component's catalog has 37 entries. The actual source -- the guardrail component's former
    // PiiDetectorUtils.DEFAULT_PII_PATTERNS, verified by counting `new PiiPattern(` occurrences and cross-checked
    // against the 1:1 parallel picker option list -- has 36. This is confirmed a stale figure in the spec (it never
    // enumerates all
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

    /**
     * The whole point of context keywords, asserted as an OUTCOME rather than as a score: at the default threshold a
     * named passport number survives redaction and a bare one does not. Asserting the number would pin the arithmetic
     * and miss whether it changes anything.
     */
    @Test
    void testANamedIdentifierIsRedactedWhereABareOneIsNot() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("Passport: A12345678", EnumSet.allOf(SensitiveKind.class), null))
            .as("a naming keyword beside the shape is what makes it identifiable")
            .isEqualTo("Passport: [REDACTED_US_PASSPORT]");

        assertThat(redactor.redact("Order A12345678", EnumSet.allOf(SensitiveKind.class), null))
            .as("the same shape with no keyword is an order code, and the false positive the confidence work fixed")
            .isEqualTo("Order A12345678");
    }

    @Test
    void testTheTaxFileNumberCaseFromTheConsolidationSpec() {
        // The concrete regression the confidence work knowingly accepted: a real TFN was forwarded in the clear
        // because its shape is a bare 9-digit run. A keyword buys it back without re-redacting ticket numbers.
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("TFN 123456789", EnumSet.allOf(SensitiveKind.class), null))
            .isEqualTo("TFN [REDACTED_AU_TFN]");
        assertThat(redactor.redact("ticket 123456789", EnumSet.allOf(SensitiveKind.class), null))
            .isEqualTo("ticket 123456789");
    }

    @Test
    void testAKeywordOutsideTheWindowDoesNotPromote() {
        // Without a bounded window, one keyword anywhere in a long document would promote every match in it -- which
        // would reintroduce exactly the false positives the confidence rubric exists to suppress.
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());
        String text = "passport" + " ".repeat(80) + "A12345678";

        assertThat(redactor.redact(text, EnumSet.allOf(SensitiveKind.class), null)).isEqualTo(text);
    }

    @Test
    void testKeywordMatchingIsCaseInsensitive() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("PASSPORT: A12345678", EnumSet.allOf(SensitiveKind.class), null))
            .isEqualTo("PASSPORT: [REDACTED_US_PASSPORT]");
    }

    @Test
    void testAKeywordAfterTheMatchPromotesToo() {
        // The window is two-sided. "A12345678 (passport)" is as clear as "passport A12345678", and a one-sided
        // window would silently cover only half of how people actually write.
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

        assertThat(redactor.redact("A12345678 (passport)", EnumSet.allOf(SensitiveKind.class), null))
            .isEqualTo("[REDACTED_US_PASSPORT] (passport)");
    }

    @Test
    void testEmailRegexDoesNotAcceptPipeInTld() {
        // Regression: the email pattern used [A-Z|a-z]{2,} for the TLD, which is a character class containing
        // the literal '|' rather than alternation. Strings like "foo@bar.|x" or "foo@bar.X|Y|Z" matched as valid
        // emails and leaked into PII masking. Pin the corrected [A-Za-z]{2,} behavior.
        assertThat(detector.detect("foo@bar.|x"))
            .as("'foo@bar.|x' must not be detected as an EMAIL_ADDRESS — '|' is not a valid TLD character")
            .extracting(SensitiveSpan::category)
            .doesNotContain("EMAIL_ADDRESS");
        assertThat(detector.detect("foo@bar.X|Y|Z"))
            .as("'foo@bar.X|Y|Z' must not be detected as an EMAIL_ADDRESS")
            .extracting(SensitiveSpan::category)
            .doesNotContain("EMAIL_ADDRESS");
    }

    @Test
    void testEmailDetectionAcceptsSubdomainsAndPlusTags() {
        assertThat(detector.detect("Email: first.last+filter@mail.sub.example.co.uk"))
            .filteredOn(span -> "EMAIL_ADDRESS".equals(span.category()))
            .isNotEmpty();
    }

    @Test
    void testPhoneDetectionAcceptsDashDotAndParenthesisSeparators() {
        assertDetectsType("Call 555-123-4567 today", "PHONE_NUMBER");
        assertDetectsType("Call (555).123.4567 today", "PHONE_NUMBER");
    }

    /**
     * Both of {@code PHONE_NUMBER}'s {@code [-\s.]} separators used to be optional, so the pattern degenerated to a
     * bare {@code \b\d{10,12}\b} run — structurally identical to {@code US_BANK_NUMBER}'s bare-digit shape, and just as
     * prone to matching an order number as an actual phone number. Both separators are now mandatory, so raw digits
     * with no delimiter no longer match {@code PHONE_NUMBER} at all — they still surface as {@code US_BANK_NUMBER},
     * since that pattern's whole purpose is to catch any bare long digit run.
     */
    @Test
    void testRawDigitsWithNoSeparatorAreNoLongerPhoneNumber() {
        List<SensitiveSpan> spans = detector.detect("Call 5551234567 now");

        assertThat(spans)
            .extracting(SensitiveSpan::category)
            .doesNotContain("PHONE_NUMBER")
            .contains("US_BANK_NUMBER");
    }

    @Test
    void testCreditCardDetectionAcceptsTheDashedForm() {
        assertDetectsType("CC: 4111-1111-1111-1111", "CREDIT_CARD");
    }

    @Test
    void testSsnDetectionAcceptsTheDashedForm() {
        assertDetectsType("SSN: 123-45-6789", "US_SSN");
    }

    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    void testIpAddressDetection() {
        assertDetectsType("Server at 192.168.1.100", "IP_ADDRESS");
    }

    @Test
    void testEmptyAndNullInputProduceNoSpans() {
        assertThat(detector.detect("")).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }

    @Test
    void testFilterByTypesIgnoresUnknownTypesAndEmptySelections() {
        assertThat(PiiPatternCatalog.filterByTypes(List.of("EMAIL_ADDRESS", "PHONE_NUMBER")))
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .containsExactlyInAnyOrder("EMAIL_ADDRESS", "PHONE_NUMBER");
        assertThat(PiiPatternCatalog.filterByTypes(List.of("EMAIL_ADDRESS", "BOGUS")))
            .extracting(PiiPatternCatalog.PiiPattern::type)
            .containsExactly("EMAIL_ADDRESS");
        assertThat(PiiPatternCatalog.filterByTypes(List.of())).isEmpty();
        assertThat(PiiPatternCatalog.filterByTypes(null)).isEmpty();
    }

    @Test
    void testCleanProseEmitsNoSpans() {
        assertThat(detector.detect("the quick brown fox jumps over the lazy dog")).isEmpty();
    }

    /**
     * The catalog's known false positives, pinned as candidates rather than as outcomes: a page number is shaped like a
     * bank account and a tax file number, and a version string is shaped like an IPv4 address. Both are emitted by the
     * detector; whether they survive is the redactor's confidence threshold's business, not this class's.
     */
    @Test
    void testNumericAndVersionProseFireKnownFalsePositiveCandidates() {
        assertThat(detector.detect("see page 123456789 of the manual"))
            .extracting(SensitiveSpan::category)
            .contains("US_BANK_NUMBER", "AU_TFN");
        assertThat(detector.detect("running version 1.2.3.4 of the library"))
            .extracting(SensitiveSpan::category)
            .contains("IP_ADDRESS");
    }

    @Test
    void testDetectsTheGlobalAndLocaleSpecificVocabulary() {
        assertDetectsType("Send funds to DE89370400440532013000 today", "IBAN_CODE");
        assertDetectsType("Wallet: 1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "CRYPTO");
        assertDetectsType("Provider CA123456 confirmed", "MEDICAL_LICENSE");
        assertDetectsType("Account 123456789 verified", "US_BANK_NUMBER");
        assertDetectsType("DL D1234567 issued", "US_DRIVER_LICENSE");
        assertDetectsType("ITIN 912-71-1234 on file", "US_ITIN");
        assertDetectsType("Passport A12345678 expires soon", "US_PASSPORT");
        assertDetectsType("NHS 943 476 5919 verified", "UK_NHS");
        assertDetectsType("NINO AB123456C provided", "UK_NINO");
        assertDetectsType("NIF 12345678Z provided", "ES_NIF");
        assertDetectsType("NIE X1234567L provided", "ES_NIE");
        assertDetectsType("CF RSSMRA85M01H501U registered", "IT_FISCAL_CODE");
        assertDetectsType("VAT IT12345678901 invoiced", "IT_VAT_CODE");
        assertDetectsType("Patente AB1234567 rilasciata", "IT_DRIVER_LICENSE");
        assertDetectsType("Passaporto YA1234567", "IT_PASSPORT");
        assertDetectsType("Carta identita CA1234567 emessa", "IT_IDENTITY_CARD");
        assertDetectsType("PESEL 44051401358 confirmed", "PL_PESEL");
        assertDetectsType("UEN 201912345K", "SG_UEN");
        assertDetectsType("ABN 12 345 678 901 listed", "AU_ABN");
        assertDetectsType("TFN 123456789 supplied", "AU_TFN");
        assertDetectsType("Medicare 2123 45678 1 valid", "AU_MEDICARE");
        assertDetectsType("Aadhaar 1234 5678 9012 captured", "IN_AADHAAR");
        assertDetectsType("PAN ABCDE1234F linked", "IN_PAN");
        assertDetectsType("Indian passport A1234567 issued", "IN_PASSPORT");
        assertDetectsType("Car: MH12AB1234", "IN_VEHICLE_REGISTRATION");
        assertDetectsType("Voter ID ABC1234567 active", "IN_VOTER");
        assertDetectsType("HETU 131052-308T verified", "FI_PERSONAL_IDENTITY_CODE");
    }

    /**
     * The contextual types are absent from {@link PiiPatternCatalog#curatedDefault()}, so they are only reachable
     * through a detector built over an explicit pattern list — which is exactly what the per-node picker does.
     */
    @Test
    void testDetectsTheContextualVocabularyWhenExplicitlySelected() {
        RegexPiiDetector wholeCatalog = new RegexPiiDetector(PiiPatternCatalog.ALL);

        assertThat(wholeCatalog.detect("Appointment 2024-01-15T13:45:00Z scheduled"))
            .extracting(SensitiveSpan::category)
            .contains("DATE_TIME");
        assertThat(wholeCatalog.detect("Visit 123 Main Street, Springfield"))
            .extracting(SensitiveSpan::category)
            .contains("LOCATION");
    }

    @Test
    void testRenamedTypesAreNoLongerPresentUnderTheirOldNames() {
        assertThat(detector.detect("S1234567D HETU 131052-308T"))
            .extracting(SensitiveSpan::category)
            .doesNotContain("SG_NRIC", "FI_PIC");
    }

    private void assertDetectsType(String content, String expectedType) {
        assertThat(detector.detect(content))
            .as("Expected PII type %s to be detected in: %s", expectedType, content)
            .extracting(SensitiveSpan::category)
            .contains(expectedType);
    }
}
