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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single source of the named secret-key / API-credential patterns, reconciled from what were two independently
 * maintained lists: the guardrails component's {@code SecretKeyDetectorUtils} (named-provider patterns, used for its
 * own per-type reporting) and this module's {@code RegexSecretDetector} (which collapses every match to the single
 * {@code SECRET} category). {@link #ALL} is the merged, deduplicated result so the patterns exist exactly once.
 *
 * <p>
 * Reconciliation notes, for the pairs that were not byte-identical between the two original lists:
 * </p>
 * <ul>
 * <li>{@code PEM_PRIVATE_KEY} existed only on the platform side (the component never detected PEM blocks). Kept as a
 * catalog entry so it too exists exactly once, even though only {@link RegexSecretDetector} currently consumes it.</li>
 * <li>{@code AWS_SECRET_KEY} existed only on the component side. Added verbatim.</li>
 * <li>{@code GITHUB_PAT}, {@code GITHUB_FINE_GRAINED_PAT}, {@code SLACK_TOKEN}, {@code OPENAI_KEY} each had two
 * differently-scoped regexes for the same conceptual secret; in every one of these four, the platform's original regex
 * is a strict superset of the component's (verified by character-class inclusion, not just sampling), so the platform
 * spelling was kept and the component now inherits the broader match instead of narrowing the platform.</li>
 * <li>{@code JWT} is the reverse: the component's original regex (just an {@code "ey"} prefix on all three segments) is
 * a strict superset of the platform's ({@code "eyJ"} required on the first two segments). The component's spelling was
 * kept. Note that this regex ends in a {@code \b} word-boundary assertion, and its character class includes both
 * {@code -} and {@code _}, but only {@code -} is a non-word character under {@code \b} — {@code _} is itself a word
 * character in Java's {@code \w}/{@code \b} definition ({@code [a-zA-Z0-9_]}), so a JWT whose final segment ends in
 * {@code _} still satisfies the boundary and is matched in full. Only a trailing {@code -}, immediately before
 * whitespace, makes the engine backtrack one character to satisfy the boundary: detection still fires, but the match
 * span is one character short, leaving a stray trailing {@code -} beside the {@code [REDACTED_...]} placeholder instead
 * of being consumed by it. Not worth changing the regex over; recorded here so it isn't rediscovered as a
 * surprise.</li>
 * <li>{@code STRIPE_KEY} originally had no superset relationship either way: the component's pattern covered
 * {@code sk_}/{@code pk_}, live and test mode, 16+ chars; the platform's covered {@code sk_}/{@code rk_}, live mode
 * only, exactly 24 chars. An initial union (both verbatim sub-patterns joined by alternation, under one
 * {@code STRIPE_KEY} entry) preserved every case but introduced a real problem: a Stripe {@code pk_} key is a
 * <b>publishable</b> key, public by design and meant to be embedded in client-side code -- not a secret. Unioning it
 * into the platform's single {@code SECRET} category would have redacted a non-secret and, because the platform had
 * never matched {@code pk_} before this consolidation, introduced that false positive as new surface rather than
 * inheriting a pre-existing one. The entry is therefore split into {@code STRIPE_SECRET_KEY} ({@code sk_} live/test,
 * verbatim from the component; and {@code rk_} live, verbatim from the platform -- both genuinely secret shapes) and
 * {@code STRIPE_PUBLISHABLE_KEY} ({@code pk_} live/test, verbatim from the component -- the public shape).
 * {@link RegexSecretDetector} consumes only {@code STRIPE_SECRET_KEY}, the same way it never carries
 * {@code PEM_PRIVATE_KEY} out to the component's own reporting -- symmetric exclusions in each direction. The component
 * still detects both split entries but folds them back into its original single {@code STRIPE_KEY} type, so its
 * observable behavior (what {@code SecretMatch::type} reports) is unchanged by the split.</li>
 * </ul>
 *
 * @author Ivica Cardic
 */
// REDOS is suppressed because every pattern here uses only fixed {N,M}/{N}/{N,} quantifiers or a single reluctant
// bounded quantifier (AWS_SECRET_KEY's `.{0,20}?`); none can be driven into catastrophic backtracking. Field-level
// suppression is ignored due to how findsecbugs attributes Pattern.compile in the static initializer, so
// class-level is required -- the same reasoning PiiPatternCatalog carries for the identical reason.
@SuppressFBWarnings("REDOS")
public final class SecretPatternCatalog {

    public static final List<SecretPattern> ALL = List.of(
        // PEM private-key block (redact the whole block, not just the marker). Platform-only; not surfaced by the
        // component's own named-type reporting.
        new SecretPattern(
            "PEM_PRIVATE_KEY",
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            0.9),
        new SecretPattern(
            "AWS_ACCESS_KEY",
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            0.9),
        new SecretPattern(
            "AWS_SECRET_KEY",
            Pattern.compile("(?i)aws.{0,20}?[\"'][0-9a-zA-Z/+]{40}[\"']"),
            0.6),
        new SecretPattern(
            "GITHUB_PAT",
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36}\\b"),
            0.9),
        new SecretPattern(
            "GITHUB_FINE_GRAINED_PAT",
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}\\b"),
            0.9),
        new SecretPattern(
            "SLACK_TOKEN",
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            0.9),
        new SecretPattern(
            "STRIPE_SECRET_KEY",
            Pattern.compile("\\b(?:sk_(?:live|test)_[0-9A-Za-z]{16,}|[sr]k_live_[0-9a-zA-Z]{24})\\b"),
            0.9),
        new SecretPattern(
            "STRIPE_PUBLISHABLE_KEY",
            Pattern.compile("\\bpk_(?:live|test)_[0-9A-Za-z]{16,}\\b"),
            0.9),
        new SecretPattern(
            "GOOGLE_API_KEY",
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            0.9),
        new SecretPattern(
            "OPENAI_KEY",
            Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b"),
            0.9),
        new SecretPattern(
            "JWT",
            Pattern.compile("\\bey[0-9A-Za-z_-]+\\.[0-9A-Za-z_-]+\\.[0-9A-Za-z_-]+\\b"),
            0.9));

    private SecretPatternCatalog() {
    }

    /**
     * One named secret pattern.
     *
     * @param type    the provider/shape name. For most entries this doubles as the component's per-match {@code type}
     *                directly; {@code PEM_PRIVATE_KEY}, {@code STRIPE_SECRET_KEY} and {@code STRIPE_PUBLISHABLE_KEY}
     *                are the documented exceptions -- see the class javadoc.
     * @param pattern the recognising regex
     * @param score   detection confidence between {@code 0.0} and {@code 1.0}, always one of {@code 0.2}/{@code 0.6}/
     *                {@code 0.9} per {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md}
     */
    public record SecretPattern(String type, Pattern pattern, double score) {

        public SecretPattern {
            if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be between 0.0 and 1.0, got: " + score);
            }
        }
    }
}
