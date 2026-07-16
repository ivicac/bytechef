/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class OpenSkillTabToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void testCallReturnsOpenedResult() {
        OpenSkillTabToolCallback callback = new OpenSkillTabToolCallback(null);

        String result = callback.call("{\"skillId\":\"7\",\"name\":\"Triage\"}", null);

        assertThat(result).contains("\"opened\":true")
            .contains("\"skillId\":\"7\"")
            .contains("Triage");
    }

    @Test
    void testCallReturnsToolErrorWhenSkillIdBlank() {
        OpenSkillTabToolCallback callback = new OpenSkillTabToolCallback(null);

        String result = callback.call("{\"skillId\":\"\",\"name\":\"Triage\"}", null);

        assertThat(result).contains("skillId is required");
    }

    @Test
    void testToolDefinitionName() {
        assertThat(new OpenSkillTabToolCallback(null).getToolDefinition()
            .name()).isEqualTo("openSkillTab");
    }
}
