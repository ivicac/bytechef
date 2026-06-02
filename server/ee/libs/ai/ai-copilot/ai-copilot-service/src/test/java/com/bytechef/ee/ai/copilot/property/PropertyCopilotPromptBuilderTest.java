/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.property;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class PropertyCopilotPromptBuilderTest {

    private final PropertyCopilotPromptBuilder promptBuilder = new PropertyCopilotPromptBuilder();

    @Test
    void testBuildTextModeIncludesPromptAndOutputsAndPillInstruction() {
        PropertyCopilotRequest request = new PropertyCopilotRequest(
            "greet the customer by first name", PropertyCopilotMode.TEXT, "wf1", "node2", "message", "STRING", 0);

        String prompt = promptBuilder.build(request, "trigger_1: {\"firstName\":\"Ada\"}\n", "");

        assertThat(prompt).contains("greet the customer by first name");
        assertThat(prompt).contains("trigger_1");
        assertThat(prompt).contains("${");
        assertThat(prompt).doesNotContain("function");
    }

    @Test
    void testBuildFormulaModeIncludesFunctionCatalogAndFormulaInstruction() {
        PropertyCopilotRequest request = new PropertyCopilotRequest(
            "uppercase the city", PropertyCopilotMode.FORMULA, "wf1", "node2", "city", "STRING", 0);

        String prompt = promptBuilder.build(
            request, "trigger_1: {\"city\":\"paris\"}\n", "- upperCase(value): converts to upper case\n");

        assertThat(prompt).contains("uppercase the city");
        assertThat(prompt).contains("upperCase");
        assertThat(prompt).contains("=");
        assertThat(prompt).contains("trigger_1");
    }
}
