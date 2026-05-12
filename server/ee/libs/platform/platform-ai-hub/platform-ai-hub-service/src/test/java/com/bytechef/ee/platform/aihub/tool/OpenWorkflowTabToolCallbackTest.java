/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class OpenWorkflowTabToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void testToolDefinitionExposesOpenWorkflowTabName() {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo("openWorkflowTab");
        assertThat(definition.description()).isNotBlank();
        assertThat(definition.inputSchema()).contains("workflowId");
        assertThat(definition.inputSchema()).contains("projectId");
        assertThat(definition.inputSchema()).contains("projectWorkflowId");
        assertThat(definition.inputSchema()).contains("name");
    }

    @Test
    void testCallEchoesArgumentsAsOpenedPayload() throws Exception {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        String result = callback.call(
            "{\"workflowId\":\"42\",\"projectId\":\"99\",\"projectWorkflowId\":7,\"name\":\"Leads Sync\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.get("opened")
            .asBoolean()).isTrue();
        assertThat(node.get("workflowId")
            .asText()).isEqualTo("42");
        assertThat(node.get("projectId")
            .asText()).isEqualTo("99");
        assertThat(node.get("projectWorkflowId")
            .asLong()).isEqualTo(7L);
        assertThat(node.get("name")
            .asText()).isEqualTo("Leads Sync");
    }

    @Test
    void testCallReturnsErrorOnInvalidJson() throws Exception {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        String result = callback.call("not-json");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
    }

    @Test
    void testCallReturnsErrorWhenWorkflowIdMissing() throws Exception {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        String result = callback.call("{\"projectId\":\"99\",\"projectWorkflowId\":7,\"name\":\"Leads Sync\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
    }

    @Test
    void testCallReturnsErrorWhenProjectIdMissing() throws Exception {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        String result = callback.call("{\"workflowId\":\"42\",\"projectWorkflowId\":7,\"name\":\"Leads Sync\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
    }

    @Test
    void testCallReturnsErrorWhenProjectWorkflowIdMissing() throws Exception {
        OpenWorkflowTabToolCallback callback = new OpenWorkflowTabToolCallback();

        String result = callback.call("{\"workflowId\":\"42\",\"projectId\":\"99\",\"name\":\"Leads Sync\"}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
    }
}
