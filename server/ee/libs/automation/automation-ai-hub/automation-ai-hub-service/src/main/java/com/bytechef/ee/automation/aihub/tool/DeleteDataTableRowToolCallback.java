/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import com.bytechef.automation.data.table.configuration.domain.DataTableInfo;
import com.bytechef.automation.data.table.configuration.service.DataTableService;
import com.bytechef.automation.data.table.execution.domain.DataTableRow;
import com.bytechef.automation.data.table.execution.service.DataTableRowService;
import com.bytechef.ee.ai.mcp.tool.util.ToolErrors;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactKind;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactService;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI {@link ToolCallback} that deletes a data-table row by id. The mutation is executed immediately — every
 * server-side mutation lands in real time and is recorded as a task artifact for audit purposes.
 *
 * <p>
 * This callback is registered on {@code aiHubBuildSpringAIAgent} only — the ASK variant is read-only.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class DeleteDataTableRowToolCallback implements ToolCallback {

    private static final long DEFAULT_ENVIRONMENT_ORDINAL = 0L;
    private static final String TOOL_NAME = "deleteDataTableRow";

    private static final String DESCRIPTION = """
        Delete a row from a data table by its id. Supply the dataTableId (from listDataTables) and
        the rowId to delete. The row is deleted immediately. The dataTableId must belong to the
        current workspace.""";

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "dataTableId": {"type": "string", "description": "Data table id obtained from listDataTables"},
                    "rowId": {"type": "string", "description": "The stable row id to delete"}
                },
                "required": ["dataTableId", "rowId"]
            }""";

    private final DataTableRowService dataTableRowService;
    private final DataTableService dataTableService;
    private final AiHubTaskArtifactService taskArtifactService;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public DeleteDataTableRowToolCallback(
        DataTableRowService dataTableRowService, DataTableService dataTableService,
        AiHubTaskArtifactService taskArtifactService) {

        this.dataTableRowService = dataTableRowService;
        this.dataTableService = dataTableService;
        this.taskArtifactService = taskArtifactService;
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
            DeleteDataTableRowInput input = jsonMapper.readValue(toolInput, DeleteDataTableRowInput.class);

            if (input.dataTableId() == null || input.dataTableId()
                .isBlank()) {
                return toolError("dataTableId is required");
            }

            if (input.rowId() == null || input.rowId()
                .isBlank()) {
                return toolError("rowId is required");
            }

            AiHubToolInvocationContext invocationContext =
                AiHubToolInvocationContext.fromToolContext(toolContext);

            Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();

            if (workspaceId == null) {
                return toolError(
                    "Workspace context unavailable - open this chat from the AI Hub of a workspace.");
            }

            long dataTableId;

            try {
                dataTableId = Long.parseLong(input.dataTableId());
            } catch (NumberFormatException exception) {
                return toolError("Invalid dataTableId - must be a numeric id obtained from listDataTables");
            }

            long rowId;

            try {
                rowId = Long.parseLong(input.rowId());
            } catch (NumberFormatException exception) {
                return toolError("Invalid rowId - must be a numeric id");
            }

            long environmentId = resolveEnvironmentId(invocationContext);

            DataTableInfo tableInfo = resolveTableInWorkspace(dataTableId, workspaceId, environmentId);

            if (tableInfo == null) {
                return toolError(
                    "Data table " + input.dataTableId() + " not found in the current workspace.");
            }

            String baseName = tableInfo.baseName();

            DataTableRow priorRow = dataTableRowService.getRow(baseName, rowId, environmentId);

            boolean wasDeleted = dataTableRowService.deleteRow(baseName, rowId, environmentId);

            if (wasDeleted) {
                recordArtifact(invocationContext, baseName, input.dataTableId(), environmentId, priorRow, rowId);
            }

            return jsonMapper.writeValueAsString(new DeleteDataTableRowOutput(wasDeleted, rowId));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(jsonMapper, DeleteDataTableRowToolCallback.class, TOOL_NAME, exception);
        }
    }

    private void recordArtifact(
        AiHubToolInvocationContext invocationContext, String baseName, String dataTableId, long environmentId,
        @Nullable DataTableRow priorRow, long rowId) {

        String threadId = invocationContext.threadId();
        Long userId = invocationContext.userId();

        if (threadId == null || userId == null) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();

        metadata.put("baseName", baseName);
        metadata.put("dataTableId", dataTableId);
        metadata.put("environmentId", environmentId);

        if (priorRow != null) {
            metadata.put("priorValues", priorRow.values());
        }

        taskArtifactService.record(
            threadId, userId, AiHubTaskArtifactKind.DATA_TABLE_ROW_DELETED,
            String.valueOf(rowId), baseName + " row " + rowId, metadata);
    }

    private DataTableInfo resolveTableInWorkspace(long dataTableId, long workspaceId, long environmentId) {
        List<DataTableInfo> workspaceTables = dataTableService.listTables(workspaceId, environmentId);

        return workspaceTables.stream()
            .filter(tableInfo -> tableInfo.id() != null && tableInfo.id() == dataTableId)
            .findFirst()
            .orElse(null);
    }

    private long resolveEnvironmentId(AiHubToolInvocationContext invocationContext) {
        Long environmentId = invocationContext.environmentId();

        return environmentId != null ? environmentId : DEFAULT_ENVIRONMENT_ORDINAL;
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record DeleteDataTableRowInput(String dataTableId, String rowId) {
    }

    public record DeleteDataTableRowOutput(boolean deleted, long rowId) {
    }
}
