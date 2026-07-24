/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.datatable;

import com.bytechef.automation.ai.tool.ToolArtifactRecorder;
import com.bytechef.automation.ai.tool.datatable.QueryDataTableToolCallback;
import com.bytechef.automation.data.table.configuration.facade.WorkspaceDataTableFacade;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the Data Table tool-callback lists shared by the Copilot agents and the AI Hub {@code data_table_agent}
 * subagent. Read list feeds ASK; write list feeds BUILD.
 *
 * @author Ivica Cardic
 * @version ee
 */
public class DataTableToolCallbacksFactory {

    private final WorkspaceDataTableFacade workspaceDataTableFacade;
    private final DataTableService dataTableService;
    private final DataTableRowService dataTableRowService;
    private final @Nullable ToolArtifactRecorder artifactRecorder;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public DataTableToolCallbacksFactory(
        WorkspaceDataTableFacade workspaceDataTableFacade,
        DataTableService dataTableService,
        DataTableRowService dataTableRowService,
        @Nullable ToolArtifactRecorder artifactRecorder) {

        this.workspaceDataTableFacade = workspaceDataTableFacade;
        this.dataTableService = dataTableService;
        this.dataTableRowService = dataTableRowService;
        this.artifactRecorder = artifactRecorder;
    }

    public List<ToolCallback> readToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        toolCallbacks.add(new ListDataTablesToolCallback(workspaceDataTableFacade));
        toolCallbacks.add(new QueryDataTableToolCallback(dataTableRowService, dataTableService));
        toolCallbacks.add(new AggregateDataTableToolCallback(dataTableRowService, dataTableService));

        return toolCallbacks;
    }

    public List<ToolCallback> writeToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>(readToolCallbacks());

        toolCallbacks.add(
            new AddDataTableRowToolCallback(dataTableRowService, workspaceDataTableFacade, artifactRecorder));
        toolCallbacks.add(
            new UpdateDataTableRowToolCallback(dataTableRowService, workspaceDataTableFacade, artifactRecorder));
        toolCallbacks.add(
            new DeleteDataTableRowToolCallback(dataTableRowService, workspaceDataTableFacade, artifactRecorder));
        toolCallbacks.add(
            new AddDataTableColumnToolCallback(dataTableService, workspaceDataTableFacade, artifactRecorder));
        toolCallbacks.add(new CreateDataTableToolCallback(workspaceDataTableFacade));
        toolCallbacks.add(new CreateDataTableFromCsvToolCallback(dataTableRowService, workspaceDataTableFacade));
        toolCallbacks.add(new CloneDataTableToolCallback(dataTableService, workspaceDataTableFacade));
        toolCallbacks.add(new DropDataTableToolCallback(workspaceDataTableFacade));

        return toolCallbacks;
    }
}
