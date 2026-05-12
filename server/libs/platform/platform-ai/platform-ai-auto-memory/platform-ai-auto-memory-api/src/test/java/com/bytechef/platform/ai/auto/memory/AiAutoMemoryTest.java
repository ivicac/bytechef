/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.ai.auto.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the slug-regex enforcement on {@link AiAutoMemory#setName(String)}. Without explicit coverage a future refactor
 * that removes the matcher (or relaxes the pattern) ships silently — the field is described in the class Javadoc as a
 * "load-bearing invariant" but only the setter actually enforces it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiAutoMemoryTest {

    @Test
    void testSetNameRejectsNull() {
        AiAutoMemory memory = new AiAutoMemory();

        assertThatThrownBy(() -> memory.setName(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    void testSetNameRejectsEmpty() {
        assertRejected("");
    }

    @Test
    void testSetNameRejectsSpaces() {
        assertRejected("Has Spaces");
    }

    @Test
    void testSetNameRejectsUppercase() {
        assertRejected("UPPER");
    }

    @Test
    void testSetNameRejectsEmoji() {
        assertRejected("emoji😀");
    }

    @Test
    void testSetNameRejectsDots() {
        assertRejected("with.dots");
    }

    @Test
    void testSetNameRejectsSlashes() {
        assertRejected("with/slash");
    }

    @Test
    void testSetNameRejects65CharString() {
        // The pattern caps at 64 chars; 65 must be rejected.
        String tooLong = "a".repeat(65);

        assertRejected(tooLong);
    }

    @Test
    void testSetNameAcceptsSlugInputs() {
        for (String input : new String[] {
            "a", "valid_name-1", "abc", "user-pref-2024", "a".repeat(64)
        }) {
            AiAutoMemory memory = new AiAutoMemory();

            memory.setName(input);

            assertThat(memory.getName()).isEqualTo(input);
        }
    }

    private static void assertRejected(String input) {
        AiAutoMemory memory = new AiAutoMemory();

        assertThatThrownBy(() -> memory.setName(input))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
