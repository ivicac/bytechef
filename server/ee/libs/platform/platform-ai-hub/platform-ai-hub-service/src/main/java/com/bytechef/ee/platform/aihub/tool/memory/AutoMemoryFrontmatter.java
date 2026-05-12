/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool.memory;

import com.bytechef.platform.ai.auto.memory.AiAutoMemoryType;
import org.jspecify.annotations.Nullable;

/**
 * Renders an {@link com.bytechef.platform.ai.auto.memory.AiAutoMemory} as a frontmatter document and parses such a
 * document back into its fields. The on-the-wire form the LLM sees through the Memory* tools is:
 *
 * <pre>
 * ---
 * name: &lt;slug&gt;
 * title: &lt;title&gt;
 * description: &lt;description&gt;   (omitted when blank)
 * type: &lt;USER|FEEDBACK|PROJECT|REFERENCE&gt;
 * ---
 * &lt;body&gt;
 * </pre>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class AutoMemoryFrontmatter {

    private static final String DELIMITER = "---";

    private AutoMemoryFrontmatter() {
    }

    record Parsed(
        @Nullable String title, @Nullable String description, @Nullable AiAutoMemoryType memoryType, String content) {
    }

    static String render(
        String name, String title, @Nullable String description, AiAutoMemoryType memoryType, String content) {

        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append(DELIMITER)
            .append("\n")
            .append("name: ")
            .append(name)
            .append("\n")
            .append("title: ")
            .append(title)
            .append("\n");

        if (description != null && !description.isBlank()) {
            stringBuilder.append("description: ")
                .append(description)
                .append("\n");
        }

        stringBuilder.append("type: ")
            .append(memoryType.name())
            .append("\n")
            .append(DELIMITER)
            .append("\n")
            .append(content);

        return stringBuilder.toString();
    }

    static Parsed parse(String text) {
        String normalized = text == null ? "" : text;

        if (!normalized.stripLeading()
            .startsWith(DELIMITER)) {

            return new Parsed(null, null, null, normalized);
        }

        String afterFirst = normalized.stripLeading()
            .substring(DELIMITER.length());

        int closingIndex = afterFirst.indexOf("\n" + DELIMITER);

        if (closingIndex < 0) {
            return new Parsed(null, null, null, normalized);
        }

        String frontmatter = afterFirst.substring(0, closingIndex);
        String afterClosing = afterFirst.substring(closingIndex + ("\n" + DELIMITER).length());
        String content = afterClosing.startsWith("\n") ? afterClosing.substring(1) : afterClosing.stripLeading();

        String title = null;
        String description = null;
        AiAutoMemoryType memoryType = null;

        for (String line : frontmatter.split("\n")) {
            int separator = line.indexOf(':');

            if (separator < 0) {
                continue;
            }

            String key = line.substring(0, separator)
                .trim();
            String value = line.substring(separator + 1)
                .trim();

            switch (key) {
                case "title" -> title = value;
                case "description" -> description = value;
                case "type" -> memoryType = parseType(value);
                default -> {
                    // "name" is authoritative from the path, not the frontmatter; ignore other keys.
                }
            }
        }

        return new Parsed(title, description, memoryType, content);
    }

    @Nullable
    private static AiAutoMemoryType parseType(String value) {
        try {
            return AiAutoMemoryType.valueOf(value.trim()
                .toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
