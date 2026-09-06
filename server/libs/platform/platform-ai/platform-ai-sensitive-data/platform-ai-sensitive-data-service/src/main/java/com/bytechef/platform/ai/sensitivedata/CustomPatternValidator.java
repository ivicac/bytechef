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

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The gate an operator-supplied pattern passes at save time.
 *
 * <p>
 * Four defences, and the first one carries most of the weight.
 * </p>
 *
 * <p>
 * <b>1. Every quantifier must be bounded.</b> The design proposed rejecting "nested quantifiers, alternation inside a
 * quantified group with overlapping branches, unbounded backreferences" — a list that needs a regex parser to apply and
 * still misses cases. Requiring an upper bound on every quantifier is one rule, checkable by a scan, and stronger for
 * this purpose: catastrophic backtracking needs an unbounded repetition to explode into. It also buys something free —
 * a pattern whose every quantifier is {@code {n,m}} has a computable maximum match length, which is exactly the
 * invariant windowed detection could never establish for the built-in catalog. Custom rules satisfy it by construction.
 * The cost is that an operator writes {@code \\d{1,20}} rather than {@code \\d+}, which is a rewrite rather than an
 * outage.
 * </p>
 *
 * <p>
 * <b>2. A timing check against adversarial input</b>, as the net for what a lexical scan cannot see. <b>Its inputs are
 * derived from the candidate pattern, never from a list of famous ReDoS patterns.</b> Measured on this JVM:
 * {@code (a+)+$}, {@code (a*)*b}, {@code ([a-zA-Z]+)*$} and {@code (a|aa)+$} all complete in under a millisecond at any
 * input length worth testing, so a check seeded from reputation approves everything.
 * </p>
 *
 * <p>
 * <b>3. A length cap</b>, which bounds the blast radius of whatever the first two let through.
 * </p>
 *
 * <p>
 * <b>4. A {@link StackOverflowError} catch.</b> Not defensive clutter: {@code (a|aa)+$} over 4,000 characters overflows
 * the stack in about 8ms — faster than any deadline, and an {@link Error}, so a {@code catch
 * (RuntimeException)} never sees it. Without this, a malformed rule kills the save request rather than being rejected
 * with a reason.
 * </p>
 *
 * <p>
 * Every failure <b>refuses</b> rather than warning. An ignored warning hangs production; a false positive costs a
 * rewrite.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class CustomPatternValidator {

    /**
     * Must match {@code PiiToken}'s own grammar. A name outside it mints tokens {@code PiiToken} cannot parse back, and
     * an unparseable token is a value that can never be restored — visible only as a rising {@code token_unresolved}
     * counter.
     */
    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private static final int MAX_PATTERN_LENGTH = 512;

    /**
     * The budget one candidate gets against one adversarial input. Generous against any legitimate pattern — the whole
     * built-in catalog scans 144,000 characters in about 4ms — and far below what a runaway needs.
     */
    private static final Duration TIMING_BUDGET = Duration.ofMillis(250);

    private static final int ADVERSARIAL_LENGTH = 4000;

    private CustomPatternValidator() {
    }

    /**
     * Throws {@link CustomPatternRejectedException} unless {@code type} and {@code regex} are safe to run.
     *
     * @param type  the rule's name
     * @param regex the operator-supplied pattern source
     * @throws CustomPatternRejectedException naming which defence rejected it
     */
    public static void validate(String type, String regex) {
        if (type == null || !TYPE_PATTERN.matcher(type)
            .matches()) {

            throw new CustomPatternRejectedException(
                Defence.NAME,
                "a rule name must match [A-Z][A-Z0-9_]{1,63}, or it mints tokens that cannot be parsed back: " + type);
        }

        if (isBuiltInType(type)) {
            throw new CustomPatternRejectedException(
                Defence.NAME,
                "a rule may not redefine the built-in type " + type +
                    "; rules are additive, and an override would weaken a shipped protection through a settings field");
        }

        if (regex == null || regex.isEmpty()) {
            throw new CustomPatternRejectedException(Defence.LENGTH, "a rule needs a pattern");
        }

        if (regex.length() > MAX_PATTERN_LENGTH) {
            throw new CustomPatternRejectedException(
                Defence.LENGTH,
                "a pattern may be at most " + MAX_PATTERN_LENGTH + " characters, got " + regex.length());
        }

        String unbounded = findUnboundedQuantifier(regex);

        if (unbounded != null) {
            throw new CustomPatternRejectedException(
                Defence.SYNTAX,
                "every quantifier must carry an upper bound, but found '" + unbounded +
                    "'; write {n,m} instead (e.g. \\d{1,20} rather than \\d+)");
        }

        Pattern pattern = compile(regex);

        assertRunsQuickly(pattern, regex);
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException patternSyntaxException) {
            throw new CustomPatternRejectedException(
                Defence.SYNTAX, "not a valid regular expression: " + patternSyntaxException.getDescription());
        }
    }

    /**
     * Runs the candidate against long runs of the character classes IT contains, under a short deadline.
     *
     * <p>
     * The inputs come from the pattern rather than from a fixed list, for the reason in this class's javadoc: a fixed
     * list of textbook ReDoS patterns approves everything on this JVM. A pattern only runs long on input it can
     * actually consume.
     * </p>
     */
    private static void assertRunsQuickly(Pattern pattern, String regex) {
        for (String haystack : adversarialInputs(regex)) {
            MatchDeadline deadline = MatchDeadline.in(TIMING_BUDGET);

            try {
                pattern.matcher(deadline.bound(haystack))
                    .find();
            } catch (DetectionTimeoutException detectionTimeoutException) {
                throw new CustomPatternRejectedException(
                    Defence.TIMING,
                    "the pattern ran longer than " + TIMING_BUDGET.toMillis() +
                        "ms on a generated input of " + haystack.length() +
                        " characters, so it would stall detection on ordinary traffic");
            } catch (StackOverflowError stackOverflowError) {
                // Deliberately catching an Error. Deep recursion overflows in milliseconds -- faster than the
                // deadline above can fire -- and is not a RuntimeException, so without this the save request dies
                // instead of the rule being rejected with a reason.
                throw new CustomPatternRejectedException(
                    Defence.TIMING,
                    "the pattern recursed deeply enough to overflow the stack on a generated input of " +
                        haystack.length() + " characters");
            }
        }
    }

    /**
     * Builds long single-class runs, seeded with the pattern's own leading literal so an anchored pattern is actually
     * reached.
     *
     * <p>
     * The prefix recovery is deliberately simple and deliberately not relied upon: a pattern whose prefix it cannot
     * recover still faces the bare runs, and the bounded-quantifier rule is what actually makes an explosion unlikely.
     * An earlier attempt at proving a catalog-wide property by prefix derivation alone was incomplete for exactly this
     * reason — it stopped at the first character class.
     * </p>
     */
    private static List<String> adversarialInputs(String regex) {
        String prefix = literalPrefix(regex);

        return List.of(
            prefix + "a".repeat(ADVERSARIAL_LENGTH),
            prefix + "A".repeat(ADVERSARIAL_LENGTH),
            prefix + "0".repeat(ADVERSARIAL_LENGTH),
            prefix + "a1".repeat(ADVERSARIAL_LENGTH / 2),
            prefix + "-".repeat(ADVERSARIAL_LENGTH),
            prefix + "x".repeat(ADVERSARIAL_LENGTH),
            prefix + " ".repeat(ADVERSARIAL_LENGTH));
    }

    private static String literalPrefix(String regex) {
        StringBuilder prefix = new StringBuilder();
        int index = regex.startsWith("\\b") ? 2 : 0;

        while (index < regex.length()) {
            char character = regex.charAt(index);

            if ("\\[](){}.*+?|^$".indexOf(character) >= 0) {
                break;
            }

            prefix.append(character);

            index++;
        }

        return prefix.toString();
    }

    /**
     * Returns the first unbounded quantifier in {@code regex}, or {@code null} when every quantifier is bounded.
     *
     * <p>
     * Skips anything escaped and anything inside a character-class definition, where {@code +} and {@code *} are
     * literals rather than quantifiers.
     * </p>
     */
    private static String findUnboundedQuantifier(String regex) {
        boolean inCharacterClass = false;

        for (int index = 0; index < regex.length(); index++) {
            char character = regex.charAt(index);

            if (character == '\\') {
                index++;

                continue;
            }

            if (inCharacterClass) {
                if (character == ']') {
                    inCharacterClass = false;
                }

                continue;
            }

            if (character == '[') {
                inCharacterClass = true;

                continue;
            }

            if (character == '*' || character == '+') {
                return String.valueOf(character);
            }

            if (character == '{') {
                int close = regex.indexOf('}', index);

                if (close > index) {
                    String bounds = regex.substring(index + 1, close);

                    if (bounds.matches("\\d+,")) {
                        return "{" + bounds + "}";
                    }
                }
            }
        }

        return null;
    }

    private static boolean isBuiltInType(String type) {
        for (PiiPatternCatalog.PiiPattern piiPattern : PiiPatternCatalog.ALL) {
            if (piiPattern.type()
                .equals(type)) {

                return true;
            }
        }

        for (SecretPatternCatalog.SecretPattern secretPattern : SecretPatternCatalog.ALL) {
            if (secretPattern.type()
                .equals(type)) {

                return true;
            }
        }

        return false;
    }

    /**
     * Which defence rejected a rule. Carried on the exception so the {@code custom_rule_rejected} metric can be tagged
     * with it — an admin needs to know whether the rejection rules are too tight, which is otherwise invisible.
     */
    public enum Defence {

        NAME,
        LENGTH,
        SYNTAX,
        TIMING
    }

    /**
     * Thrown when a candidate rule is refused. A checked-style failure expressed as a runtime exception because every
     * caller is a save path that turns it into a user-facing message.
     */
    public static class CustomPatternRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Defence defence;

        public CustomPatternRejectedException(Defence defence, String message) {
            super(message);

            this.defence = defence;
        }

        public Defence getDefence() {
            return defence;
        }
    }
}
