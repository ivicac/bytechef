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

package com.bytechef.component.ai.agent.guardrails.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.ai.agent.guardrails.util.SecretKeyDetectorUtils.Permissiveness;
import com.bytechef.component.ai.agent.guardrails.util.SecretKeyDetectorUtils.SecretMatch;
import com.bytechef.platform.ai.sensitivedata.SecretPatternCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the consolidation of the named secret patterns onto {@link SecretPatternCatalog}: the component now reads its
 * named-provider regexes from the shared catalog instead of carrying its own copies, and several of those regexes were
 * reconciled to the broader of the two original spellings. These tests exercise the broadened cases directly (they
 * would have failed against the component's pre-consolidation, narrower regexes) and guard the component-vs-catalog
 * type parity so a future catalog edit cannot silently add or drop a type the component reports. They also cover the
 * catalog's {@code STRIPE_KEY} split ({@code STRIPE_SECRET_KEY}/{@code STRIPE_PUBLISHABLE_KEY}), confirming the split
 * is invisible to this component's consumers via {@code SecretKeyDetectorUtils.CATALOG_TYPE_OVERRIDES}.
 *
 * @author Ivica Cardic
 */
class SecretKeyDetectorUtilsCatalogParityTest {

    @Test
    void testDetectsGithubOAuthTokenNowThatGithubPatIsThePlatformSuperset() {
        // The component's original GITHUB_PAT regex only matched the classic 'ghp_' prefix. The catalog's
        // reconciled regex is the platform's original, broader spelling ('gh[pousr]_'), which also covers OAuth
        // ('gho_'), user-to-server ('ghu_'), server-to-server ('ghs_') and refresh ('ghr_') tokens.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "token gho_" + "a".repeat(36) + " here", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("GITHUB_PAT");
    }

    @Test
    void testDetectsSlackRestrictedTokenNowThatSlackTokenIsThePlatformSuperset() {
        // The component's original SLACK_TOKEN regex only matched the 'a'/'b'/'p' prefixes. The catalog's
        // reconciled regex additionally covers 'r' (refresh) and 's' (legacy) prefixes.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "token xoxr-" + "a".repeat(20) + " here", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("SLACK_TOKEN");
    }

    @Test
    void testDetectsGithubFineGrainedTokenShorterThanTheComponentsOldExactLength() {
        // The component's original GITHUB_FINE_GRAINED_PAT regex required exactly 82 trailing characters. The
        // catalog's reconciled regex (the platform's original spelling) only requires 22 or more.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "token github_pat_" + "A".repeat(30) + " here", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("GITHUB_FINE_GRAINED_PAT");
    }

    @Test
    void testDetectsOpenAiProjectScopedKeyNowThatOpenAiKeyIsThePlatformSuperset() {
        // The component's original OPENAI_KEY regex required the 20+ characters right after 'sk-' to be plain
        // alphanumeric, so a literal 'proj-' infix broke the match. The catalog's reconciled regex (the platform's
        // original spelling) explicitly allows an optional 'proj-' segment.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "token sk-proj-abcdefghij1234567890abcdef end", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("OPENAI_KEY");
    }

    @Test
    void testDetectsStripeRestrictedLiveKeyStillReportedAsStripeKey() {
        // The component's original STRIPE_KEY regex covered only 'sk_'/'pk_' with live/test mode. The catalog's
        // STRIPE_SECRET_KEY entry adds the platform's original 'rk_live_' (restricted key) coverage, and the
        // component folds that catalog type back to its own pre-split 'STRIPE_KEY' name (see
        // CATALOG_TYPE_OVERRIDES), so neither side of the two original lists loses a case and the component's
        // reported type is unchanged.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "key rk_live_" + "B".repeat(24) + " done", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("STRIPE_KEY");
    }

    @Test
    void testDetectsStripePublishableKeyStillReportedAsStripeKey() {
        // Stripe publishable keys ('pk_') are public by design -- not a secret -- so SecretPatternCatalog carries
        // them as the separate STRIPE_PUBLISHABLE_KEY entry precisely so RegexSecretDetector (platform) can exclude
        // them. This component has per-type reporting rather than a single collapsed category, so it keeps
        // detecting the shape and, via CATALOG_TYPE_OVERRIDES, keeps reporting it under the original 'STRIPE_KEY'
        // type exactly as it did before the catalog split -- the split must be invisible to this component's
        // consumers.
        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(
            "key pk_test_" + "A".repeat(20) + " done", Permissiveness.PERMISSIVE);

        assertThat(matches)
            .extracting(SecretMatch::type)
            .contains("STRIPE_KEY")
            .doesNotContain("STRIPE_PUBLISHABLE_KEY", "STRIPE_SECRET_KEY");
    }

    @Test
    void testNamedTypesMatchCatalogWithKnownExclusionsAndOverridesApplied() {
        // PEM_PRIVATE_KEY is a platform-only catalog entry -- the component never detected PEM blocks and this
        // consolidation must not silently add that capability. STRIPE_SECRET_KEY and STRIPE_PUBLISHABLE_KEY are a
        // platform-only split of what this component still reports as one 'STRIPE_KEY' type (see
        // CATALOG_TYPE_OVERRIDES). Feed one realistic sample per catalog entry (including a PEM block and both
        // Stripe shapes) through the component's public detect() and assert the type set it reports is exactly the
        // catalog's types with those exclusions/overrides applied. A catalog entry added without updating the
        // exclusion filter or override map, or a filter/override that over- or under-applies, fails this test.
        String sample = String.join(
            " ",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAKj34Gkx...\n-----END RSA PRIVATE KEY-----",
            "AKIAIOSFODNN7EXAMPLE",
            "awsSecretKey=\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"",
            "ghp_" + "a".repeat(36),
            "github_pat_" + "A".repeat(30),
            "xoxb-" + "a".repeat(20),
            "sk_live_" + "A".repeat(20),
            "pk_test_" + "A".repeat(20),
            "AIza" + "A".repeat(35),
            "sk-" + "a".repeat(24),
            "eyJhbGciOiJIUzI.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4");

        List<SecretMatch> matches = SecretKeyDetectorUtils.detect(sample, Permissiveness.PERMISSIVE);

        Set<String> catalogTypes = SecretPatternCatalog.ALL.stream()
            .map(SecretPatternCatalog.SecretPattern::type)
            .collect(Collectors.toSet());

        Set<String> expectedComponentTypes = catalogTypes.stream()
            .filter(type -> !type.equals("PEM_PRIVATE_KEY"))
            .filter(type -> !type.equals("STRIPE_SECRET_KEY"))
            .filter(type -> !type.equals("STRIPE_PUBLISHABLE_KEY"))
            .collect(Collectors.toCollection(HashSet::new));

        expectedComponentTypes.add("STRIPE_KEY");

        Set<String> namedTypesReported = matches.stream()
            .map(SecretMatch::type)
            .filter(expectedComponentTypes::contains)
            .collect(Collectors.toSet());

        assertThat(namedTypesReported)
            .as("every catalog type, with known exclusions/overrides applied, must be reachable through the "
                + "component")
            .isEqualTo(expectedComponentTypes);
        assertThat(matches)
            .as("the raw catalog-only types must never be reported by the component's public detect()")
            .extracting(SecretMatch::type)
            .doesNotContain("PEM_PRIVATE_KEY", "STRIPE_SECRET_KEY", "STRIPE_PUBLISHABLE_KEY");
    }
}
