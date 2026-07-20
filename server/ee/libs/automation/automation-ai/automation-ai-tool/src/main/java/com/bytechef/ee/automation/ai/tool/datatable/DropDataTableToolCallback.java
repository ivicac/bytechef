/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.datatable;

import com.bytechef.ai.agent.tool.ToolErrors;
import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.automation.data.table.configuration.facade.WorkspaceDataTableFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drops an entire data table (and, by cascade, all its rows) in the current environment. Irreversible — the caller must
 * confirm with the user first. Authorization is enforced by {@link WorkspaceDataTableFacade#dropTable(long, long)}
 * itself via {@code @PreAuthorize hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class DropDataTableToolCallback implements ToolCallback {

    static final String TOOL_NAME = "dropDataTable";

    private static final String DESCRIPTION = """
        Drop an entire data table and all its rows in the current environment. Irreversible.
        Always confirm with the user before calling.""";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "id": {"type": "integer", "description": "Data table id to drop"}
            },
            "required": ["id"]
        }""";

    private final WorkspaceDataTableFacade workspaceDataTableFacade;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public DropDataTableToolCallback(WorkspaceDataTableFacade workspaceDataTableFacade) {
        this.workspaceDataTableFacade = workspaceDataTableFacade;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(TOOL_NAME)
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
            DropDataTableToolInput input = jsonMapper.readValue(toolInput, DropDataTableToolInput.class);

            if (input.id() == null) {
                return toolError("id is required");
            }

            AgentToolInvocationContext invocationContext = AgentToolInvocationContext.fromToolContext(toolContext);

            long environmentId = invocationContext == null ? 0L : invocationContext.resolveEnvironmentOrDefault();

            workspaceDataTableFacade.dropTable(input.id(), environmentId);

            return jsonMapper.writeValueAsString(Map.of("deleted", true, "id", input.id()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return toolError(exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(jsonMapper, DropDataTableToolCallback.class, TOOL_NAME, exception);
        }
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record DropDataTableToolInput(Long id) {
    }
}
