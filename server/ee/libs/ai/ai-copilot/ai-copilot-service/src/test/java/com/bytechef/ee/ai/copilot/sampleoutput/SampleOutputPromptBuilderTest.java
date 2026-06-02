/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.sampleoutput;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class SampleOutputPromptBuilderTest {

    private final SampleOutputPromptBuilder promptBuilder = new SampleOutputPromptBuilder();

    @Test
    void testBuildAsksForJsonInstanceNotSchema() {
        String prompt = promptBuilder.build("an order with an id and a list of line items");

        assertThat(prompt).contains("an order with an id and a list of line items");
        assertThat(prompt).contains("JSON");
        assertThat(prompt).containsIgnoringCase("example");
        assertThat(prompt).doesNotContain("JSON Schema");
    }
}
