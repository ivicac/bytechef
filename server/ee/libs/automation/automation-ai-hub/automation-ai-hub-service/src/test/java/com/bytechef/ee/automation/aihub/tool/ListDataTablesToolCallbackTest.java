/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.data.table.configuration.domain.DataTableInfo;
import com.bytechef.automation.data.table.configuration.service.DataTableService;
import com.bytechef.automation.data.table.domain.ColumnSpec;
import com.bytechef.automation.data.table.domain.ColumnType;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ListDataTablesToolCallbackTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void testToolDefinitionExposesListDataTablesName() {
        DataTableService dataTableService = mock(DataTableService.class);

        ListDataTablesToolCallback callback = new ListDataTablesToolCallback(
            dataTableService);

        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo("listDataTables");
        assertThat(definition.description()).isNotBlank();
    }

    @Test
    void testCallReturnsDataTableListFromWorkspace() throws Exception {
        long workspaceId = 1L;

        DataTableService dataTableService = mock(DataTableService.class);

        List<ColumnSpec> columns = List.of(
            new ColumnSpec("name", ColumnType.STRING),
            new ColumnSpec("age", ColumnType.INTEGER));
        DataTableInfo dataTableInfo = new DataTableInfo(42L, "contacts", "Contacts table", columns, null);

        when(dataTableService.listTables(workspaceId, 0L)).thenReturn(List.of(dataTableInfo));

        ListDataTablesToolCallback callback = new ListDataTablesToolCallback(
            dataTableService);

        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(workspaceId, 10L, (short) 0, "x", 0L, "thread-1").toToolContext());

        String result = callback.call("{}", toolContext);

        JsonNode arrayNode = jsonMapper.readTree(result);

        assertThat(arrayNode.isArray()).isTrue();
        assertThat(arrayNode).hasSize(1);

        JsonNode first = arrayNode.get(0);

        assertThat(first.get("id")
            .asLong()).isEqualTo(42L);
        assertThat(first.get("name")
            .asText()).isEqualTo("contacts");

        JsonNode columnsNode = first.get("columns");

        assertThat(columnsNode.isArray()).isTrue();
        assertThat(columnsNode).hasSize(2);
        assertThat(columnsNode.get(0)
            .get("name")
            .asText()).isEqualTo("name");
        assertThat(columnsNode.get(0)
            .get("type")
            .asText()).isEqualTo("STRING");
    }

    @Test
    void testCallReturnsErrorWhenWorkspaceIdMissing() throws Exception {
        DataTableService dataTableService = mock(DataTableService.class);

        ListDataTablesToolCallback callback = new ListDataTablesToolCallback(
            dataTableService);

        String result = callback.call("{}");

        JsonNode node = jsonMapper.readTree(result);

        assertThat(node.has("error")).isTrue();
    }

    @Test
    void testCallReturnsEmptyArrayWhenNoDataTables() throws Exception {
        long workspaceId = 1L;

        DataTableService dataTableService = mock(DataTableService.class);

        when(dataTableService.listTables(workspaceId, 0L)).thenReturn(List.of());

        ListDataTablesToolCallback callback = new ListDataTablesToolCallback(
            dataTableService);

        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(workspaceId, 10L, (short) 0, "x", 0L, "thread-1").toToolContext());

        String result = callback.call("{}", toolContext);

        JsonNode arrayNode = jsonMapper.readTree(result);

        assertThat(arrayNode.isArray()).isTrue();
        assertThat(arrayNode).isEmpty();
    }

    @Test
    void testCallPassesEnvironmentIdToService() throws Exception {
        long workspaceId = 1L;
        long environmentId = 2L;

        DataTableService dataTableService = mock(DataTableService.class);

        when(dataTableService.listTables(workspaceId, environmentId)).thenReturn(List.of());

        ListDataTablesToolCallback callback = new ListDataTablesToolCallback(
            dataTableService);

        ToolContext toolContext = new ToolContext(
            new AiHubToolInvocationContext(workspaceId, 10L, (short) 0, "x", environmentId, "thread-1")
                .toToolContext());

        callback.call("{}", toolContext);

        verify(dataTableService).listTables(workspaceId, environmentId);
    }
}
