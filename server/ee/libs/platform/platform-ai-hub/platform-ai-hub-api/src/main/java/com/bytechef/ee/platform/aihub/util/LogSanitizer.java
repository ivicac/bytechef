/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.util;

/**
 * Strips control characters and clamps length on values that flow into log lines from user-controlled or LLM-controlled
 * sources (request body fields, AG-UI state values, tool input/output payloads). Without this, an attacker can pad
 * arbitrarily long fake log lines via {@code \r\n} in any logged field, hide content via overstrike (backspace), or
 * corrupt downstream log shippers that interpret ANSI CSI escape sequences.
 *
 * <p>
 * Use anywhere a string crosses the trust boundary into SLF4J — the controller, the artifact recorder, and every tool
 * callback that logs LLM-supplied values.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class LogSanitizer {

    private static final int MAX_LENGTH = 64;

    private LogSanitizer() {
    }

    /**
     * Replaces every C0 control character except TAB ({@code 0x09}), plus DEL ({@code 0x7F}), with underscores and
     * truncates to {@value MAX_LENGTH} characters with an ellipsis. TAB is preserved because it is rendered safely by
     * every terminal and log viewer ByteChef ships with — stripping it would mangle aligned columnar log output for no
     * security benefit.
     *
     * <p>
     * Returns the literal string {@code "null"} when {@code value} is {@code null} so callers that embed the result in
     * a manually built string (outside SLF4J's null-safe placeholder substitution) get a readable token rather than a
     * {@link NullPointerException}.
     */
    public static String sanitizeForLog(Object value) {
        if (value == null) {
            return "null";
        }

        String raw = value.toString();
        StringBuilder builder = new StringBuilder(raw.length());

        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);

            // Strip C0 controls (< 0x20) except TAB (0x09), and DEL (0x7F). This blocks CR/LF log injection,
            // ANSI CSI escapes (which start with 0x1B), backspace overstrike, NUL, etc. Characters >= 0x80
            // are passed through — they may be legitimate UTF-8 multi-byte payloads.
            if ((character < 0x20 && character != 0x09) || character == 0x7F) {
                builder.append('_');
            } else {
                builder.append(character);
            }
        }

        String stripped = builder.toString();

        return stripped.length() > MAX_LENGTH ? stripped.substring(0, MAX_LENGTH) + "..." : stripped;
    }
}
