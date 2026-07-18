/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.ai.mcp.domain.McpProjectWorkflow;
import com.bytechef.automation.ai.mcp.service.McpProjectWorkflowService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class UpdateMcpProjectWorkflowParametersToolCallbackTest {

    private McpProjectWorkflowService mcpProjectWorkflowService;
    private UpdateMcpProjectWorkflowParametersToolCallback toolCallback;

    @BeforeEach
    void beforeEach() {
        mcpProjectWorkflowService = mock(McpProjectWorkflowService.class);
        toolCallback = new UpdateMcpProjectWorkflowParametersToolCallback(mcpProjectWorkflowService);
    }

    @Test
    void testToolDefinitionName() {
        assertThat(toolCallback.getToolDefinition()
            .name()).isEqualTo("updateMcpProjectWorkflowParameters");
    }

    @Test
    void testMissingIdReturnsError() {
        String result = toolCallback.call("{\"toolName\": \"get_weather\"}");

        assertThat(result).contains("error");
        assertThat(result).contains("mcpProjectWorkflowId is required");

        verify(mcpProjectWorkflowService, never()).updateParameters(anyLong(), anyMap());
    }

    @Test
    void testBlankToolNameReturnsError() {
        String result = toolCallback.call("{\"mcpProjectWorkflowId\": 1, \"toolName\": \"  \"}");

        assertThat(result).contains("error");
        assertThat(result).contains("toolName must not be blank");

        verify(mcpProjectWorkflowService, never()).updateParameters(anyLong(), anyMap());
    }

    @Test
    void testUnknownIdReturnsError() {
        when(mcpProjectWorkflowService.fetchMcpProjectWorkflow(99L)).thenReturn(Optional.empty());

        String result = toolCallback.call("{\"mcpProjectWorkflowId\": 99, \"toolName\": \"get_weather\"}");

        assertThat(result).contains("error");
        assertThat(result).contains("McpProjectWorkflow not found");

        verify(mcpProjectWorkflowService, never()).updateParameters(anyLong(), anyMap());
    }

    @Test
    void testMergePreservesExistingParameters() {
        McpProjectWorkflow existingMcpProjectWorkflow = new McpProjectWorkflow();

        existingMcpProjectWorkflow.setParameters(
            Map.of("toolName", "old_name", "city", "fromAi('city', 'STRING', {required: true})"));

        McpProjectWorkflow updatedMcpProjectWorkflow = new McpProjectWorkflow();

        when(mcpProjectWorkflowService.fetchMcpProjectWorkflow(7L))
            .thenReturn(Optional.of(existingMcpProjectWorkflow));
        when(mcpProjectWorkflowService.updateParameters(eq(7L), anyMap())).thenAnswer(invocation -> {
            updatedMcpProjectWorkflow.setParameters(invocation.getArgument(1));

            return updatedMcpProjectWorkflow;
        });

        String result = toolCallback.call(
            "{\"mcpProjectWorkflowId\": 7, \"toolName\": \"get_weather\", \"toolDescription\": \"Fetch weather\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> parametersCaptor = ArgumentCaptor.forClass(Map.class);

        verify(mcpProjectWorkflowService).updateParameters(eq(7L), parametersCaptor.capture());

        Map<String, ?> mergedParameters = parametersCaptor.getValue();

        assertThat(mergedParameters).containsEntry("toolName", "get_weather");
        assertThat(mergedParameters).containsEntry("toolDescription", "Fetch weather");
        assertThat(mergedParameters).containsEntry("city", "fromAi('city', 'STRING', {required: true})");

        assertThat(result).contains("get_weather");
    }

    @Test
    void testParametersOverlayReplacesOnlySuppliedKeys() {
        McpProjectWorkflow existingMcpProjectWorkflow = new McpProjectWorkflow();

        existingMcpProjectWorkflow.setParameters(Map.of("toolName", "get_weather", "city", "Berlin", "unit", "C"));

        when(mcpProjectWorkflowService.fetchMcpProjectWorkflow(7L))
            .thenReturn(Optional.of(existingMcpProjectWorkflow));
        when(mcpProjectWorkflowService.updateParameters(eq(7L), anyMap()))
            .thenAnswer(invocation -> {
                McpProjectWorkflow updatedMcpProjectWorkflow = new McpProjectWorkflow();

                updatedMcpProjectWorkflow.setParameters(invocation.getArgument(1));

                return updatedMcpProjectWorkflow;
            });

        toolCallback.call(
            "{\"mcpProjectWorkflowId\": 7, \"parameters\": {\"city\": " +
                "\"fromAi('city', 'STRING', {description: 'City name', required: true})\"}}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> parametersCaptor = ArgumentCaptor.forClass(Map.class);

        verify(mcpProjectWorkflowService).updateParameters(eq(7L), parametersCaptor.capture());

        Map<String, ?> mergedParameters = parametersCaptor.getValue();

        assertThat(mergedParameters)
            .containsEntry("city", "fromAi('city', 'STRING', {description: 'City name', required: true})");
        assertThat(mergedParameters).containsEntry("unit", "C");
        assertThat(mergedParameters).containsEntry("toolName", "get_weather");
    }
}
