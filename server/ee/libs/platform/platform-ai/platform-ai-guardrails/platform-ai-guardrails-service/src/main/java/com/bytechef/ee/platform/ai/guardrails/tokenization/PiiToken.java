/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.tokenization;

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
 * @version ee
 *
 * @author Ivica Cardic
 */
public record PiiToken(String category, int ordinal, String sessionId) {

    public static final int SESSION_ID_LENGTH = 4;

    private static final Pattern PATTERN =
        Pattern.compile("\\[PII_([A-Z][A-Z0-9_]*)_(\\d+)_([a-z0-9]{" + SESSION_ID_LENGTH + "})\\]");

    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[a-z0-9]{" + SESSION_ID_LENGTH + "}");

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
