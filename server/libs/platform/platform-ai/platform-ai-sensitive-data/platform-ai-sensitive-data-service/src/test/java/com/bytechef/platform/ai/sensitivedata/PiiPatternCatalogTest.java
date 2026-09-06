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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the confidence score carried by every {@link PiiPatternCatalog.PiiPattern}: the drift guard in
 * {@link #testEveryCatalogScoreMatchesTheProvenanceTable()} hardcodes the same 36 values as
 * {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md}, so an edit to one without the other
 * fails here instead of silently diverging.
 *
 * @author Ivica Cardic
 */
class PiiPatternCatalogTest {

    /**
     * Every score from {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md} §3, with the
     * standing, deliberate exceptions documented there in the 2026-08-31 revision note: {@code US_SSN} (table lists
     * {@code 0.2} for its retired combined dashed+bare-digit entry; the surviving dashed-only pattern scores
     * {@code 0.6}), and the final-review sweep's ten corrections below, none of which were back-ported into the frozen
     * table (see {@link PiiPatternCatalog}'s class javadoc for the reasoning on each):
     *
     * <ul>
     * <li>{@code FI_PERSONAL_IDENTITY_CODE} — table and code both said {@code 0.6}, but the code's regex had an
     * unintended character-class range ({@code [+-A]}) that made it match an 11-digit bare run at that score. The regex
     * is fixed to mean what the table always assumed it meant; the score ({@code 0.6}) does not change.</li>
     * <li>{@code US_DRIVER_LICENSE}, {@code US_PASSPORT}, {@code IT_DRIVER_LICENSE}, {@code IT_PASSPORT},
     * {@code IT_IDENTITY_CARD}, {@code IN_VOTER}, {@code IN_PASSPORT} — rescored {@code 0.2} (Low), following the same
     * "bare letter-prefix + digit run" reasoning the table already applied to {@code MEDICAL_LICENSE}, which the first
     * sweep did not carry across the rest of the catalog.</li>
     * </ul>
     *
     * <p>
     * {@code CREDIT_CARD} — rescored {@code 0.9} (High) on 2026-08-31 (spec decision D10 reversed for this one type;
     * see the design spec's decision table), when the entry gained a Luhn-checksum
     * {@link PiiPatternCatalog.PiiPattern#validator()} and its separators went back to optional so it matches both
     * formatted and bare 16-digit card numbers. Unlike the exceptions above, this row <em>was</em> amended in the
     * frozen table (matching the precedent already set there for {@code FI_PERSONAL_IDENTITY_CODE}'s and
     * {@code CREDIT_CARD}'s own regex-text corrections), so there is no standing divergence for it here.
     * </p>
     * <p>
     * {@code ES_NIF} and {@code ES_NIE} — rescored {@code 0.6} (Medium), <em>not</em> carried into the Low group above.
     * They were not merely under-scored by the first sweep; their regex was wrong at the shape level (both were the
     * byte-identical, letter-first {@code \b[A-Z]\d{8}\b}, matching neither real Spanish format and indistinguishable
     * from each other). Corrected to the real digits-first formats — {@code ES_NIF}: {@code \b\d{8}[A-Z]\b};
     * {@code ES_NIE}: {@code \b[XYZ]\d{7}[A-Z]\b} — each of which coincides with a shape this catalog already scores
     * Medium elsewhere ({@code SG_UEN}'s 8-digit alternative and the {@code UK_NINO}/{@code SG_NRIC_FIN} bookended
     * shape, respectively), so Medium is the score the "same shape, same score" rule requires. See
     * {@link PiiPatternCatalog}'s class javadoc for the full correction, including the exact-length collision the
     * {@code ES_NIF} fix introduces with {@code SG_UEN}.
     * </p>
     */
    private static final Map<String, Double> EXPECTED_SCORES = Map.ofEntries(
        Map.entry("EMAIL_ADDRESS", 0.9),
        Map.entry("PHONE_NUMBER", 0.6),
        Map.entry("CREDIT_CARD", 0.9),
        Map.entry("IP_ADDRESS", 0.6),
        Map.entry("IBAN_CODE", 0.9),
        Map.entry("CRYPTO", 0.9),
        Map.entry("DATE_TIME", 0.6),
        Map.entry("LOCATION", 0.6),
        Map.entry("MEDICAL_LICENSE", 0.2),
        Map.entry("US_BANK_NUMBER", 0.2),
        Map.entry("US_DRIVER_LICENSE", 0.2),
        Map.entry("US_ITIN", 0.6),
        Map.entry("US_PASSPORT", 0.2),
        Map.entry("US_SSN", 0.6),
        Map.entry("UK_NHS", 0.6),
        Map.entry("UK_NINO", 0.6),
        Map.entry("ES_NIF", 0.6),
        Map.entry("ES_NIE", 0.6),
        Map.entry("IT_FISCAL_CODE", 0.9),
        Map.entry("IT_DRIVER_LICENSE", 0.2),
        Map.entry("IT_VAT_CODE", 0.9),
        Map.entry("IT_PASSPORT", 0.2),
        Map.entry("IT_IDENTITY_CARD", 0.2),
        Map.entry("PL_PESEL", 0.2),
        Map.entry("SG_NRIC_FIN", 0.6),
        Map.entry("SG_UEN", 0.6),
        Map.entry("AU_ABN", 0.6),
        Map.entry("AU_ACN", 0.6),
        Map.entry("AU_TFN", 0.2),
        Map.entry("AU_MEDICARE", 0.6),
        Map.entry("IN_PAN", 0.6),
        Map.entry("IN_AADHAAR", 0.6),
        Map.entry("IN_VEHICLE_REGISTRATION", 0.6),
        Map.entry("IN_VOTER", 0.2),
        Map.entry("IN_PASSPORT", 0.2),
        Map.entry("FI_PERSONAL_IDENTITY_CODE", 0.6));

    @Test
    void testEveryPatternCarriesAScoreInRange() {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            assertThat(piiPattern.score())
                .as("score for %s", piiPattern.type())
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    void testBareDigitRunsScoreBelowAnchoredPatterns() {
        double bankNumber = scoreOf("US_BANK_NUMBER");
        double email = scoreOf("EMAIL_ADDRESS");

        assertThat(bankNumber).isLessThan(email);
    }

    /**
     * The drift guard: every one of the catalog's 36 entries must carry exactly the score
     * {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md} assigns it (§3), with the one
     * standing exception ({@code US_SSN}) documented on {@link #EXPECTED_SCORES}. A catalog edit that changes a score
     * without updating this map, or vice versa, fails here.
     */
    @Test
    void testEveryCatalogScoreMatchesTheProvenanceTable() {
        assertThat(PiiPatternCatalog.ALL)
            .hasSize(EXPECTED_SCORES.size());

        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            Double expected = EXPECTED_SCORES.get(piiPattern.type());

            assertThat(expected)
                .as("no expected score recorded for catalog type %s", piiPattern.type())
                .isNotNull();
            assertThat(piiPattern.score())
                .as("score for %s", piiPattern.type())
                .isEqualTo(expected);
        }
    }

    @Test
    void testEveryScoreIsOneOfTheThreeBandValues() {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            assertThat(piiPattern.score())
                .as("score for %s", piiPattern.type())
                .isIn(0.2, 0.6, 0.9);
        }
    }

    @Test
    void testCompactConstructorRejectsScoreOutsideZeroToOne() {
        Pattern pattern = Pattern.compile("x");

        assertThatThrownBy(() -> new PiiPatternCatalog.PiiPattern("X", pattern, 1.1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiPatternCatalog.PiiPattern("X", pattern, -0.1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PiiPatternCatalog.PiiPattern("X", pattern, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The three-argument constructor is the convenience overload every entry but {@code CREDIT_CARD} uses; it must
     * still produce a pattern with no validator, so those 35 entries behave exactly as before this field existed.
     */
    @Test
    void testThreeArgumentConstructorLeavesValidatorNull() {
        PiiPatternCatalog.PiiPattern pattern = new PiiPatternCatalog.PiiPattern("X", Pattern.compile("x"), 0.6);

        assertThat(pattern.validator()).isNull();
    }

    /**
     * {@code CREDIT_CARD} is the only catalog entry carrying a validator today; every other entry's regex match alone
     * decides whether it fires.
     */
    @Test
    void testOnlyCreditCardCarriesAValidator() {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            if ("CREDIT_CARD".equals(piiPattern.type())) {
                assertThat(piiPattern.validator())
                    .as("CREDIT_CARD must carry a Luhn validator")
                    .isNotNull();
            } else {
                assertThat(piiPattern.validator())
                    .as("%s must not carry a validator", piiPattern.type())
                    .isNull();
            }
        }
    }

    /**
     * Mutation evidence for the Luhn gate: a Luhn-valid card number passes, a 16-digit run that merely has the right
     * shape but fails the checksum (an ordinary order number) does not.
     */
    @Test
    void testCreditCardValidatorAcceptsLuhnValidAndRejectsInvalid() {
        Predicate<String> validator = patternOf("CREDIT_CARD").validator();

        assertThat(validator).isNotNull();
        assertThat(validator.test("4532015112830366"))
            .as("Luhn-valid card number must pass")
            .isTrue();
        assertThat(validator.test("4532 0151 1283 0366"))
            .as("Luhn-valid card number with spaces must pass")
            .isTrue();
        assertThat(validator.test("1234567890123456"))
            .as("Luhn-invalid 16-digit run must fail")
            .isFalse();
    }

    /**
     * Mutation evidence for the ruling in {@link PiiPatternCatalog}'s class javadoc: {@code US_SSN} lost its
     * bare-9-digit alternative, so it now matches only the dashed form. {@code AU_TFN} still carries the identical
     * bare-digit shape, so the same text still matches, just under the other category.
     */
    @Test
    void testUsSsnPatternNoLongerMatchesBareNineDigits() {
        PiiPatternCatalog.PiiPattern usSsn = patternOf("US_SSN");
        PiiPatternCatalog.PiiPattern auTfn = patternOf("AU_TFN");

        assertThat(usSsn.pattern()
            .matcher("123-45-6789")
            .find())
                .as("US_SSN must still match the dashed form")
                .isTrue();
        assertThat(usSsn.pattern()
            .matcher("987654321")
            .find())
                .as("US_SSN must no longer match a bare 9-digit run")
                .isFalse();
        assertThat(auTfn.pattern()
            .matcher("987654321")
            .find())
                .as("AU_TFN must still match the bare 9-digit run US_SSN no longer claims")
                .isTrue();
    }

    @Test
    void testUsSsnScoresMediumNotLow() {
        assertThat(scoreOf("US_SSN")).isEqualTo(0.6);
    }

    /**
     * Mutation evidence for the {@code ES_NIF}/{@code ES_NIE} regex fix: the two patterns used to be byte-identical
     * ({@code \b[A-Z]\d{8}\b}), matching neither real Spanish format and indistinguishable from one another. Reverting
     * either pattern back to that shape makes this test fail — see {@link PiiPatternCatalog}'s class javadoc for the
     * full correction.
     */
    @Test
    void testEsNifAndEsNieMatchOwnFormatNotEachOther() {
        PiiPatternCatalog.PiiPattern esNif = patternOf("ES_NIF");
        PiiPatternCatalog.PiiPattern esNie = patternOf("ES_NIE");

        assertThat(esNif.pattern()
            .matcher("12345678Z")
            .find())
                .as("ES_NIF must match the real digits-then-check-letter NIF format")
                .isTrue();
        assertThat(esNie.pattern()
            .matcher("X1234567L")
            .find())
                .as("ES_NIE must match the real X-then-digits-then-check-letter NIE format")
                .isTrue();
        assertThat(esNie.pattern()
            .matcher("Y1234567L")
            .find())
                .as("ES_NIE must match a leading Y")
                .isTrue();
        assertThat(esNie.pattern()
            .matcher("Z1234567L")
            .find())
                .as("ES_NIE must match a leading Z")
                .isTrue();

        assertThat(esNif.pattern()
            .matcher("X1234567L")
            .find())
                .as("ES_NIF must not match an ES_NIE sample")
                .isFalse();
        assertThat(esNie.pattern()
            .matcher("12345678Z")
            .find())
                .as("ES_NIE must not match an ES_NIF sample")
                .isFalse();
        assertThat(esNie.pattern()
            .matcher("A1234567L")
            .find())
                .as("ES_NIE must not match a leading letter outside X/Y/Z")
                .isFalse();
    }

    @Test
    void testEsNifAndEsNieScoreMediumNotLow() {
        assertThat(scoreOf("ES_NIF")).isEqualTo(0.6);
        assertThat(scoreOf("ES_NIE")).isEqualTo(0.6);
    }

    /**
     * Documents the resolver-tie-break collision the {@code ES_NIF} fix introduces, at the regex level: its corrected
     * pattern is byte-identical to {@code SG_UEN}'s 8-digit alternative, so both fire on the identical span for a
     * matching input. {@link SensitiveDataRedactor}'s category-ascending tie-break is what decides {@code ES_NIF} wins
     * that overlap; this test only pins the regex-level fact the tie-break acts on.
     */
    @Test
    void testEsNifEightDigitShapeCollidesWithSgUen() {
        PiiPatternCatalog.PiiPattern esNif = patternOf("ES_NIF");
        PiiPatternCatalog.PiiPattern sgUen = patternOf("SG_UEN");

        Matcher esNifMatcher = esNif.pattern()
            .matcher("12345678Z");
        Matcher sgUenMatcher = sgUen.pattern()
            .matcher("12345678Z");

        assertThat(esNifMatcher.find()).isTrue();
        assertThat(sgUenMatcher.find()).isTrue();
        assertThat(esNifMatcher.start()).isEqualTo(sgUenMatcher.start());
        assertThat(esNifMatcher.end()).isEqualTo(sgUenMatcher.end());
    }

    /**
     * Mutation evidence for the CRITICAL finding-1 fix: the century-marker class used to be written {@code [+-A]}, an
     * unintended character-class RANGE ({@code '+'} through {@code 'A'}, which includes every digit) rather than the
     * 3-character set {@code {+, -, A}} the Finnish century markers require, making the pattern effectively
     * {@code \b\d{10}[A-Z0-9]\b} -- a bare 11-character digit run. Reverting the fix (restoring {@code [+-A]}) makes
     * this test fail: an ordinary 11-digit business identifier like an order or invoice number would match.
     */
    @Test
    void testFiPersonalIdentityCodeDoesNotMatchBareElevenCharacterBusinessIdentifiers() {
        PiiPatternCatalog.PiiPattern fiCode = patternOf("FI_PERSONAL_IDENTITY_CODE");

        assertThat(fiCode.pattern()
            .matcher("Order 20260825123 shipped")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must not match an ordinary 11-digit order number")
                .isFalse();
        assertThat(fiCode.pattern()
            .matcher("invoice 45001239871 total 1234.56")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must not match an ordinary 11-digit invoice number")
                .isFalse();
        assertThat(fiCode.pattern()
            .matcher("PESEL 44051401359")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must not match a bare 11-digit run generally")
                .isFalse();
        assertThat(fiCode.pattern()
            .matcher("010101-123A")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must still match a genuine 1900s-century code")
                .isTrue();
        assertThat(fiCode.pattern()
            .matcher("010101+123A")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must still match a genuine 1800s-century code")
                .isTrue();
        assertThat(fiCode.pattern()
            .matcher("010101A123A")
            .find())
                .as("FI_PERSONAL_IDENTITY_CODE must still match a genuine 2000s-century code")
                .isTrue();
    }

    private static double scoreOf(String type) {
        return patternOf(type).score();
    }

    private static PiiPatternCatalog.PiiPattern patternOf(String type) {
        return PiiPatternCatalog.ALL.stream()
            .filter(piiPattern -> type.equals(piiPattern.type()))
            .findFirst()
            .orElseThrow();
    }

    /**
     * A Low-scored pattern with no context rule is detected by NOTHING at the default threshold -- its score sits below
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}. That is a deliberate state only for a type nobody names in
     * text, and an accident for every other, so this test is what makes leaving one bare a decision rather than an
     * oversight.
     */
    @Test
    void testEveryLowScoredTypeCanBePromotedByContext() {
        List<String> bare = PiiPatternCatalog.ALL.stream()
            .filter(piiPattern -> piiPattern.score() < SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE)
            .filter(piiPattern -> piiPattern.contextRule() == null)
            .map(PiiPatternCatalog.PiiPattern::type)
            .toList();

        assertThat(bare)
            .as("these types score below the default threshold and have no keyword that can lift them, so nothing "
                + "detects them at all; either give them keywords or accept the gap explicitly here")
            .isEmpty();
    }

    @Test
    void testEveryContextRulePromotesAboveTheDefaultThreshold() {
        // A promotion that lands below the threshold buys nothing back -- the match is still dropped.
        // A plain loop, not a stream: SpotBugs cannot carry a null-check across a filter lambda into the next one,
        // so the stream form reads as a possible null dereference.
        List<String> tooLow = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            PiiPatternCatalog.ContextRule contextRule = piiPattern.contextRule();

            if (contextRule != null && contextRule.score() < SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE) {
                tooLow.add(piiPattern.type());
            }
        }

        assertThat(tooLow).isEmpty();
    }

    @Test
    void testContextKeywordsAreLowerCasedInTheCatalog() {
        // Matching lower-cases the haystack, so an upper-case keyword here would never fire -- a silently dead rule
        // that looks configured.
        List<String> wrongCase = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            PiiPatternCatalog.ContextRule contextRule = piiPattern.contextRule();

            if (contextRule == null) {
                continue;
            }

            for (String keyword : contextRule.keywords()) {
                if (!keyword.equals(keyword.toLowerCase(Locale.ROOT))) {
                    wrongCase.add(keyword);
                }
            }
        }

        assertThat(wrongCase).isEmpty();
    }

    @Test
    void testAContextRuleThatWouldLowerConfidenceIsRejected() {
        // Raise-only is a security property. A lowering rule is an off switch an attacker writes into the prompt:
        // put "order number:" in front of a real SSN and the guardrail stops firing.
        assertThatThrownBy(
            () -> new PiiPatternCatalog.PiiPattern(
                "TEST_TYPE", Pattern.compile("\\d{9}"), 0.9,
                new PiiPatternCatalog.ContextRule(Set.of("ssn"), 40, 0.2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RAISE");
    }
}
