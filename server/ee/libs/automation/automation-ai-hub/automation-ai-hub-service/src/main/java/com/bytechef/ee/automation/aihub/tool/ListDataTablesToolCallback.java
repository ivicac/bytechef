/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import com.bytechef.automation.data.table.configuration.domain.DataTableInfo;
import com.bytechef.automation.data.table.configuration.service.DataTableService;
import com.bytechef.automation.data.table.domain.ColumnType;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.platform.aihub.util.ToolErrors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI {@link ToolCallback} that lists all data tables in the current workspace. Returns a compact JSON array of
 * table summaries including id, name, columns (with name and type), so the AI Hub agent can enumerate available data
 * tables and refer to them by id when calling queryDataTable.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ListDataTablesToolCallback implements ToolCallback {

    private static final long DEFAULT_ENVIRONMENT_ORDINAL = 0L;

    private static final String DESCRIPTION = """
        List all data tables in the current workspace. Returns a JSON array of table summaries
        including id, name, and columns (each column has name and type). Use the returned id values
        when calling queryDataTable. Call this first when the user asks about their data tables.""";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {}
        }""";

    private final DataTableService dataTableService;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ListDataTablesToolCallback(DataTableService dataTableService) {
        this.dataTableService = dataTableService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("listDataTables")
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
            AiHubToolInvocationContext invocationContext =
                AiHubToolInvocationContext.fromToolContext(toolContext);

            Long workspaceId = invocationContext.workspaceId();

            if (workspaceId == null) {
                return toolError(
                    "Workspace context unavailable - open this chat from the AI Hub of a workspace.");
            }

            long environmentId = resolveEnvironmentId(invocationContext);

            List<DataTableInfo> dataTableInfos = dataTableService.listTables(workspaceId, environmentId);

            List<DataTableSummary> summaries = dataTableInfos.stream()
                .map(info -> {
                    List<ColumnSummary> columnSummaries = info.columns()
                        .stream()
                        .map(columnSpec -> new ColumnSummary(columnSpec.name(), columnSpec.type()))
                        .toList();

                    return new DataTableSummary(info.id(), info.baseName(), columnSummaries);
                })
                .toList();

            return jsonMapper.writeValueAsString(summaries);
        } catch (JacksonException exception) {
            return toolError("Serialization error: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(
                jsonMapper, ListDataTablesToolCallback.class, "listDataTables", exception);
        }
    }

    private long resolveEnvironmentId(AiHubToolInvocationContext invocationContext) {
        Long environmentId = invocationContext.environmentId();

        return environmentId != null ? environmentId : DEFAULT_ENVIRONMENT_ORDINAL;
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record ColumnSummary(String name, ColumnType type) {
    }

    @SuppressFBWarnings({
        "EI_EXPOSE_REP", "EI_EXPOSE_REP2"
    })
    public record DataTableSummary(Long id, String name, List<ColumnSummary> columns) {
    }
}
