/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.ai.copilot.util.Source;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class CopilotConfigurationWorkflowExecutionTest {

    @Test
    void testWorkflowExecutionSourceExists() {
        assertThat(Source.valueOf("WORKFLOW_EXECUTION")).isEqualTo(Source.WORKFLOW_EXECUTION);
    }

    @Test
    void testAgentIdsAreLowerSnakeCase() {
        assertThat(Source.WORKFLOW_EXECUTION.name()
            .toLowerCase() + "_ask").isEqualTo("workflow_execution_ask");
        assertThat(Source.WORKFLOW_EXECUTION.name()
            .toLowerCase() + "_build").isEqualTo("workflow_execution_build");
    }
}
