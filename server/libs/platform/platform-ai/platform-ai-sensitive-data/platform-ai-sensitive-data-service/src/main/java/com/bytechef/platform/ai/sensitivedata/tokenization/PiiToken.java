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

package com.bytechef.platform.ai.sensitivedata.tokenization;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One placeholder token standing in for a detected PII value, e.g. {@code [PII_EMAIL_1_k3n9]}.
 *
 * <p>
 * The shape carries four requirements. The <b>category</b> lets the model know it is reasoning about an email rather
 * than an opaque blob. The <b>ordinal</b> distinguishes one value from another, which is the whole point: without it,
 * two different addresses collapse into the same string and the model cannot tell them apart. The <b>session id</b>
 * binds a token to the session that minted it, so a token from one session can never be restored by another — an
 * impossibility by construction rather than a policy. And it is <b>ASCII only</b>, because a token that a model mangles
 * in transit is a token that cannot be restored.
 * </p>
 *
 * @param category  the {@code SensitiveSpan} category this token stands for
 * @param ordinal   1-based, distinct per value within a session
 * @param sessionId the minting session's discriminator
 *
 * @author Ivica Cardic
 */
public record PiiToken(String category, int ordinal, String sessionId) {

    public static final int SESSION_ID_LENGTH = 4;

    /**
     * The characters a session-id discriminator is drawn from. Lives here, beside the patterns that must accept it,
     * rather than in {@code PiiTokenSession} which mints from it -- the same reason {@link #SESSION_ID_LENGTH} does.
     *
     * <p>
     * It was written three times before this: once as the minting alphabet and twice as a {@code [a-z0-9]} character
     * class in the two patterns below. Nothing tied them together, so widening the alphabet (adding uppercase, say)
     * would have kept minting tokens that this type's own patterns silently refuse to parse -- and an unparseable token
     * is a value that can never be restored, which surfaces only as a rising {@code token_unresolved} counter.
     * {@code PiiTokenTest} pins that every character here is accepted by {@link #SESSION_ID_PATTERN}, which is the one
     * correspondence the derivation below cannot enforce by construction.
     * </p>
     */
    public static final String SESSION_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String SESSION_ID_CHARACTER_CLASS = "[a-z0-9]";

    private static final Pattern PATTERN = Pattern.compile(
        "\\[PII_([A-Z][A-Z0-9_]*)_(\\d+)_(" + SESSION_ID_CHARACTER_CLASS + "{" + SESSION_ID_LENGTH + "})\\]");

    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern SESSION_ID_PATTERN =
        Pattern.compile(SESSION_ID_CHARACTER_CLASS + "{" + SESSION_ID_LENGTH + "}");

    public PiiToken {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        if (!CATEGORY_PATTERN.matcher(category)
            .matches()) {

            throw new IllegalArgumentException("category must match [A-Z][A-Z0-9_]*, got: " + category);
        }

        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got: " + ordinal);
        }

        if (!SESSION_ID_PATTERN.matcher(sessionId)
            .matches()) {

            throw new IllegalArgumentException(
                "sessionId must be " + SESSION_ID_LENGTH + " lowercase alphanumerics, got: " + sessionId);
        }
    }

    /**
     * Returns the pattern that recognises any token, for scanning a body of text.
     *
     * @return the recognition pattern
     */
    public static Pattern pattern() {
        return PATTERN;
    }

    /**
     * Parses one token, returning empty when {@code text} is not exactly a token.
     *
     * @param text the candidate token text
     * @return the parsed token, or empty
     */
    public static Optional<PiiToken> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }

        Matcher matcher = PATTERN.matcher(text);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                new PiiToken(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns this token's rendered text, as it appears in a prompt.
     *
     * @return the token text
     */
    public String text() {
        return "[PII_" + category + "_" + ordinal + "_" + sessionId + "]";
    }
}
