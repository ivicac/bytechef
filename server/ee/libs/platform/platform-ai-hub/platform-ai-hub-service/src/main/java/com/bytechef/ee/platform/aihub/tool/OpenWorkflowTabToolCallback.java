/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

import com.bytechef.ee.ai.mcp.tool.util.ToolErrors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signaling-only Spring AI {@link ToolCallback} that lets the AI Hub agent request a workflow to be opened in the
 * client resource panel. The server-side implementation is a no-op that echoes the arguments back as a JSON result; the
 * AI Hub client subscriber intercepts the tool-call result event and updates the tabs store.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class OpenWorkflowTabToolCallback implements ToolCallback {

    private static final String DESCRIPTION = """
        Open a workflow in the AI Hub resource panel so the user can see it.
        Call this after creating a workflow or when referring to an existing workflow.
        Use the workflowId, projectId, and projectWorkflowId returned from createWorkflow or
        listWorkflows - never invent workflow IDs, project IDs, or projectWorkflowIds.""";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "workflowId": {"type": "string", "description": "Workflow id"},
                "projectId": {"type": "string", "description": "Project id that owns the workflow"},
                "projectWorkflowId": {"type": "number", "description": "Project workflow id (from listWorkflows)"},
                "name": {"type": "string", "description": "Display name for the tab"}
            },
            "required": ["workflowId", "projectId", "projectWorkflowId", "name"]
        }""";

    private final JsonMapper jsonMapper = new JsonMapper();

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("openWorkflowTab")
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            OpenWorkflowTabInput input = jsonMapper.readValue(toolInput, OpenWorkflowTabInput.class);

            if (input.workflowId() == null || input.workflowId()
                .isBlank()) {
                return toolError("workflowId is required");
            }

            if (input.projectId() == null || input.projectId()
                .isBlank()) {
                return toolError("projectId is required");
            }

            if (input.projectWorkflowId() == null) {
                return toolError("projectWorkflowId is required");
            }

            if (input.name() == null || input.name()
                .isBlank()) {
                return toolError("name is required");
            }

            return jsonMapper.writeValueAsString(
                new OpenWorkflowTabOutput(
                    true, input.workflowId(), input.projectId(), input.projectWorkflowId(), input.name()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(
                jsonMapper, OpenWorkflowTabToolCallback.class, "openWorkflowTab", exception);
        }
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record OpenWorkflowTabInput(String workflowId, String projectId, Long projectWorkflowId, String name) {
    }

    public record OpenWorkflowTabOutput(
        boolean opened, String workflowId, String projectId, Long projectWorkflowId, String name) {
    }
}
