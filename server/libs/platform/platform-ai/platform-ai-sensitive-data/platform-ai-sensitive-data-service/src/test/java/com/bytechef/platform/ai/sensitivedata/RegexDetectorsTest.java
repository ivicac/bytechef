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

import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegexDetectorsTest {

    private static final Set<SensitiveKind> BOTH = EnumSet.allOf(SensitiveKind.class);

    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor(SensitiveDataDetectors.builtIn());

    @Test
    void testDetectsEveryPiiCategory() {
        String redacted = redactor.redact(
            "Email me at jane.doe@example.com or call 415-555-0132. SSN 123-45-6789, card 4111 1111 1111 1111, " +
                "host 192.168.1.20.",
            BOTH, null);

        assertThat(redacted).contains("[REDACTED_EMAIL_ADDRESS]");
        assertThat(redacted).contains("[REDACTED_US_SSN]");
        assertThat(redacted).contains("[REDACTED_CREDIT_CARD]");
        assertThat(redacted).contains("[REDACTED_PHONE_NUMBER]");
        assertThat(redacted).contains("[REDACTED_IP_ADDRESS]");
        assertThat(redacted).doesNotContain("jane.doe@example.com");
        assertThat(redacted).doesNotContain("123-45-6789");
    }

    @Test
    void testDetectsKnownSecretShapes() {
        String redacted = redactor.redact(
            "aws AKIAIOSFODNN7EXAMPLE gh ghp_1234567890abcdefghij1234567890abcdef openai " +
                "sk-abcdefghij1234567890ABCD jwt eyJhbGciOiJIUzI.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4 done",
            BOTH, null);

        assertThat(redacted).contains("[REDACTED_SECRET]");
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).doesNotContain("ghp_1234567890abcdefghij1234567890abcdef");
        assertThat(redacted).doesNotContain("sk-abcdefghij1234567890ABCD");
        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI");
    }

    @Test
    void testRedactsPemPrivateKeyBlockWhole() {
        String redacted = redactor.redact(
            "key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAKj34Gkx...\n-----END RSA PRIVATE KEY-----\ntail",
            BOTH, null);

        assertThat(redacted).contains("[REDACTED_SECRET]");
        assertThat(redacted).doesNotContain("BEGIN RSA PRIVATE KEY");
        assertThat(redacted).contains("tail");
    }

    /**
     * AWS_SECRET_KEY exists in the component's {@code SecretKeyDetectorUtils} but was entirely absent from the
     * platform's pattern list before the consolidation onto {@code SecretPatternCatalog}.
     */
    @Test
    void testDetectsAwsSecretKeyPattern() {
        assertThat(redactor.redact(
            "awsSecretKey=\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\" done", BOTH, null))
                .isEqualTo("[REDACTED_SECRET] done");
    }

    /**
     * A Stripe {@code pk_} key is a <em>publishable</em> key -- public by design, meant to be embedded in client-side
     * JavaScript. It is not a secret, so the platform must not redact it. {@link SecretPatternCatalog} carries it as
     * the separate {@code STRIPE_PUBLISHABLE_KEY} entry precisely so {@link RegexSecretDetector} can exclude it while
     * the component (which has per-type reporting, not a single collapsed category) keeps detecting it under its
     * existing {@code STRIPE_KEY} type.
     */
    @Test
    void testDoesNotRedactStripePublishableKey() {
        String text = "key pk_test_AAAAAAAAAAAAAAAAAAAA done";

        assertThat(redactor.redact(text, BOTH, null)).isEqualTo(text);
    }

    /**
     * Secret-mode Stripe keys ({@code sk_live_}/{@code sk_test_}) are still redacted -- only the publishable shape was
     * carved out.
     */
    @Test
    void testDetectsStripeSecretTestKey() {
        assertThat(redactor.redact("key sk_test_AAAAAAAAAAAAAAAAAAAA done", BOTH, null))
            .isEqualTo("key [REDACTED_SECRET] done");
    }

    /**
     * The platform-only half of the STRIPE_KEY union (restricted live keys) must still be detected after the
     * consolidation -- this guards against a future edit dropping that alternative.
     */
    @Test
    void testDetectsStripeRestrictedLiveKey() {
        assertThat(redactor.redact("key rk_live_BBBBBBBBBBBBBBBBBBBBBBBB done", BOTH, null))
            .isEqualTo("key [REDACTED_SECRET] done");
    }

    @Test
    void testLeavesCleanTextUnchanged() {
        String content = "Summarize the quarterly revenue report. The deployment succeeded.";

        assertThat(redactor.redact(content, BOTH, null)).isEqualTo(content);
    }

    /**
     * The bug this SPI was built to fix. The old sequential chain ran CREDIT_CARD before the secret patterns, so it
     * rewrote the digits the secret pattern needed and emitted "sk-proj-[REDACTED_CC]" -- disclosing that an OpenAI key
     * was present and leaking its prefix. Resolving spans against the original text redacts the whole secret.
     */
    @Test
    void testSecretContainingDigitRunIsRedactedWholeNotPartially() {
        assertThat(redactor.redact("sk-proj-1234567890123456", BOTH, null)).isEqualTo("[REDACTED_SECRET]");
        assertThat(redactor.redact("xoxb-1234567890123456-abcdef", BOTH, null)).isEqualTo("[REDACTED_SECRET]");
    }

    /**
     * Control cases: inputs with no PII/secret overlap must be byte-identical to what the old chain produced.
     */
    @Test
    void testNonOverlappingInputsMatchThePreSpiOutput() {
        assertThat(redactor.redact("card 4111 1111 1111 1111 ok", BOTH, null))
            .isEqualTo("card [REDACTED_CREDIT_CARD] ok");
        assertThat(redactor.redact("mail me at bob@example.com please", BOTH, null))
            .isEqualTo("mail me at [REDACTED_EMAIL_ADDRESS] please");
        assertThat(redactor.redact("contact sk-proj-abcdefghijklmnopqrstuvwx now", BOTH, null))
            .isEqualTo("contact [REDACTED_SECRET] now");
        assertThat(redactor.redact("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123", BOTH, null))
            .isEqualTo("[REDACTED_SECRET]");
        assertThat(redactor.redact("bob@example.com called from 10.0.0.5 with 555-123-4567", BOTH, null))
            .isEqualTo("[REDACTED_EMAIL_ADDRESS] called from [REDACTED_IP_ADDRESS] with [REDACTED_PHONE_NUMBER]");
    }

    /**
     * The bug this feature exists to fix: ordinary business identifiers -- an order number, an invoice number, a SKU, a
     * support-ticket number -- must not be redacted at the shipped default. Every assertion here calls the
     * default-applying {@link SensitiveDataRedactor#redact(String, Set, SensitiveDataMetrics)} overload rather than
     * passing a threshold explicitly, because the point is what a real caller gets, not what an arbitrary threshold
     * produces.
     */
    @Test
    void testOrdinaryBusinessIdentifiersProduceNoSpansAtTheDefaultThreshold() {
        assertThat(redactor.redact("Order 20260825 shipped; invoice 4500123987 total 1234.56", BOTH, null))
            .isEqualTo("Order 20260825 shipped; invoice 4500123987 total 1234.56");
        assertThat(redactor.redact("SKU AB123456 qty 12345678", BOTH, null))
            .isEqualTo("SKU AB123456 qty 12345678");
        assertThat(redactor.redact("Ticket 987654321 escalated", BOTH, null))
            .isEqualTo("Ticket 987654321 escalated");
    }

    /**
     * The systematic version of {@link #testOrdinaryBusinessIdentifiersProduceNoSpansAtTheDefaultThreshold()}: that
     * test pins four hand-picked business identifiers, which is exactly why the {@code FI_PERSONAL_IDENTITY_CODE} regex
     * bug (an unintended character-class range that made the pattern match any bare 11-character digit run) went
     * unnoticed for the life of this feature -- none of the four hand-picked examples happened to be 11 characters
     * long. This test instead generates the corpus exhaustively across the shapes ordinary business identifiers
     * actually take -- bare digit runs at every length from 8 through 17 (the exact range {@code US_BANK_NUMBER}'s
     * {@code \b\d{8,17}\b} spans), letter-prefixed SKU shapes at every prefix length from 1 through 3 letters against
     * digit runs from 3 through 10 digits, and order/invoice/ticket/purchase-order text -- so a coverage gap shows up
     * as a test failure here rather than as a customer report. Every case is asserted together (rather than one
     * assertion per case) so a single failing shape does not hide a second one behind an early `assertThat` abort.
     */
    @Test
    void testGeneratedBusinessIdentifierCorpusProducesNoSpansAtTheDefaultThreshold() {
        List<String> corpus = new ArrayList<>();

        for (int length = 8; length <= 17; length++) {
            corpus.add("Reference " + "1".repeat(length) + " confirmed.");
            corpus.add("Reference " + "9".repeat(length) + " confirmed.");
        }

        for (int prefixLength = 1; prefixLength <= 3; prefixLength++) {
            String prefix = "ABC".substring(0, prefixLength);

            for (int digitLength = 3; digitLength <= 10; digitLength++) {
                corpus.add("SKU " + prefix + "1".repeat(digitLength) + " qty 5");
            }
        }

        for (int digitLength = 6; digitLength <= 12; digitLength++) {
            String digits = "1".repeat(digitLength);

            corpus.add("Order " + digits + " shipped");
            corpus.add("Invoice " + digits + " total 1234.56");
            corpus.add("Ticket " + digits + " escalated");
            corpus.add("PO-" + digits + " approved");
        }

        // Group 1 (2026-08-31): CREDIT_CARD's separators are optional again and the match is gated by a Luhn
        // checksum instead, so a bare 16-digit business identifier is exactly the shape CREDIT_CARD's regex now
        // matches. This case is Luhn-invalid (unlike a real card number), so it must still produce no spans -- the
        // checksum, not the regex shape, is what is supposed to keep it out.
        corpus.add("Order 1234567890123456 shipped");

        List<String> unexpectedlyRedacted = new ArrayList<>();

        for (String text : corpus) {
            String redacted = redactor.redact(text, BOTH, null);

            if (!redacted.equals(text)) {
                unexpectedlyRedacted.add(text + " -> " + redacted);
            }
        }

        assertThat(unexpectedlyRedacted).isEmpty();
    }

    /**
     * Group 1 (2026-08-31): {@code CREDIT_CARD} regained the ability to match a bare, unformatted 16-digit card number
     * -- lost in the prior fix round when the separators were made mandatory to close the degenerate-match trap -- by
     * gating the match on a Luhn checksum instead. End-to-end through the real redactor: a Luhn-valid bare number
     * redacts; a Luhn-invalid 16-digit run with the identical shape (an ordinary order number) does not.
     */
    @Test
    void testUnformattedCreditCardRedactsOnlyWhenLuhnValid() {
        assertThat(redactor.redact("card 4532015112830366 ok", BOTH, null))
            .isEqualTo("card [REDACTED_CREDIT_CARD] ok");
        assertThat(redactor.redact("Order 1234567890123456 shipped", BOTH, null))
            .isEqualTo("Order 1234567890123456 shipped");
    }

    /**
     * The other half of the same guarantee: dropping the low-confidence bare-digit patterns must not also drop the
     * strong, structurally distinctive ones.
     */
    @Test
    void testStrongPatternsStillDetectAtTheDefaultThreshold() {
        assertThat(redactor.redact("mail bob@acme.io", BOTH, null)).isEqualTo("mail [REDACTED_EMAIL_ADDRESS]");
        assertThat(redactor.redact("key AKIAIOSFODNN7EXAMPLE", BOTH, null)).isEqualTo("key [REDACTED_SECRET]");
    }

    @Test
    void testBothDetectorsAreStreamSafe() {
        List<SensitiveDataDetector> detectors = SensitiveDataDetectors.builtIn();

        assertThat(detectors).hasSize(2);
        assertThat(detectors).allMatch(SensitiveDataDetector::streamSafe);
    }

    @Test
    void testKindsAreAssignedCorrectly() {
        assertThat(new RegexPiiDetector().detect("bob@example.com"))
            .allMatch(span -> span.kind() == SensitiveKind.PII);
        assertThat(new RegexSecretDetector().detect("AKIAIOSFODNN7EXAMPLE"))
            .allMatch(span -> span.kind() == SensitiveKind.SECRET);
    }

    /**
     * {@link RegexSecretDetector} collapses every match to the single {@code SECRET} category (see its class javadoc),
     * so category alone can't disambiguate which pattern matched the way it can for {@link RegexPiiDetector}. This text
     * is chosen to match exactly one secret pattern, so the single resulting span's confidence can only have come from
     * that pattern's own score.
     */
    @Test
    void testSecretSpanCarriesItsPatternScoreRatherThanFullConfidence() {
        List<SensitiveSpan> spans = new RegexSecretDetector().detect("aws AKIAIOSFODNN7EXAMPLE done");

        assertThat(spans).hasSize(1);

        double expected = SecretPatternCatalog.ALL.stream()
            .filter(secretPattern -> "AWS_ACCESS_KEY".equals(secretPattern.type()))
            .findFirst()
            .orElseThrow()
            .score();

        assertThat(spans.get(0)
            .confidence()).isEqualTo(expected);
    }

    @Test
    void testTokenizesPiiAndLeavesSecretsRedacted() {
        PiiTokenSession session = PiiTokenSession.create();

        String text = "mail bob@example.com about AKIAIOSFODNN7EXAMPLE";

        String tokenized = redactor.tokenizeWithSpans(text, BOTH, session, null)
            .text();

        assertThat(tokenized).contains("[PII_EMAIL_ADDRESS_1_" + session.sessionId() + "]");
        assertThat(tokenized).contains("[REDACTED_SECRET]");
        assertThat(tokenized).doesNotContain("bob@example.com");
        assertThat(tokenized).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    /**
     * The motivating case: two different addresses must not collapse into one indistinguishable string.
     */
    @Test
    void testTwoDifferentValuesBecomeTwoDifferentTokens() {
        PiiTokenSession session = PiiTokenSession.create();

        String tokenized = redactor
            .tokenizeWithSpans("forward bob@acme.io's note to alice@acme.io", BOTH, session, null)
            .text();

        assertThat(tokenized).contains("[PII_EMAIL_ADDRESS_1_" + session.sessionId() + "]");
        assertThat(tokenized).contains("[PII_EMAIL_ADDRESS_2_" + session.sessionId() + "]");
    }

    @Test
    void testTheSameValueTwiceBecomesTheSameToken() {
        PiiTokenSession session = PiiTokenSession.create();

        String tokenized = redactor
            .tokenizeWithSpans("bob@acme.io told bob@acme.io", BOTH, session, null)
            .text();

        assertThat(tokenized).isEqualTo(
            "[PII_EMAIL_ADDRESS_1_" + session.sessionId() + "] told [PII_EMAIL_ADDRESS_1_" + session.sessionId()
                + "]");
    }

    @Test
    void testTokenizingThenRestoringIsTheIdentityForPii() {
        PiiTokenSession session = PiiTokenSession.create();

        String text = "forward bob@acme.io's note to alice@acme.io";

        String restored = session.restore(
            redactor.tokenizeWithSpans(text, BOTH, session, null)
                .text());

        assertThat(restored).isEqualTo(text);
    }

    @Test
    void testSecretsDoNotSurviveTheRoundTrip() {
        PiiTokenSession session = PiiTokenSession.create();

        String text = "token AKIAIOSFODNN7EXAMPLE";

        String restored = session.restore(
            redactor.tokenizeWithSpans(text, BOTH, session, null)
                .text());

        assertThat(restored).isEqualTo("token [REDACTED_SECRET]");
        assertThat(restored).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void testBuiltInDetectorsRedactUnderTheEntityTypeName() {
        String redacted = redactor.redact("mail bob@acme.io", BOTH, null);

        assertThat(redacted).isEqualTo("mail [REDACTED_EMAIL_ADDRESS]");
    }

    @Test
    void testBuiltInDetectorsCoverInternationalIdentifiers() {
        String redacted = redactor.redact("ID S1234567D", BOTH, null);

        assertThat(redacted).contains("[REDACTED_SG_NRIC_FIN]");
    }
}
