/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WorkflowCodeEditorSpringAIAgentTest {

    @Test
    void testFormatInstructionYaml() {
        assertThat(WorkflowCodeEditorSpringAIAgent.formatInstruction("yaml"))
            .contains("YAML");
    }

    @Test
    void testFormatInstructionJson() {
        assertThat(WorkflowCodeEditorSpringAIAgent.formatInstruction("json"))
            .contains("JSON");
    }

    @Test
    void testFormatInstructionDefaultsToJson() {
        assertThat(WorkflowCodeEditorSpringAIAgent.formatInstruction(null))
            .contains("JSON");
    }
}
