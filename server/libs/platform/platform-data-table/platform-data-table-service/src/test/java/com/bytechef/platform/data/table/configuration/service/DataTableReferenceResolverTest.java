/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.data.table.configuration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.definition.BaseProperty.ResourceType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class DataTableReferenceResolverTest {

    private final DataTableRowService dataTableRowService = mock(DataTableRowService.class);
    private final DataTableService dataTableService = mock(DataTableService.class);
    private final DataTableReferenceResolver resolver =
        new DataTableReferenceResolver(dataTableRowService, dataTableService);

    @Test
    void resourceTypeIsDataTable() {
        assertEquals(ResourceType.DATA_TABLE, resolver.getResourceType());
    }

    @Test
    void existingTableResolves() {
        when(dataTableService.listTables(0L, PlatformType.AUTOMATION)).thenReturn(List.of(table("conversations")));

        assertNull(resolver.findProblem("Conversations", 0L));
    }

    /**
     * Both pools are searched because the editor validates a workflow with no connected user -- the vendor case, which
     * {@code poolFor} admits to both -- so a name held by the other pool is not a problem to report.
     */
    @Test
    void tableInTheOtherPoolStillResolves() {
        when(dataTableService.listTables(0L, PlatformType.AUTOMATION)).thenReturn(List.of());
        when(dataTableService.listTables(0L, PlatformType.EMBEDDED)).thenReturn(List.of(table("conversations")));

        assertNull(resolver.findProblem("conversations", 0L));
    }

    /**
     * What {@code DataTableUtils.resolveDataTable} refuses at run time -- a base name legal in both pools, with nothing
     * to tell them apart -- reported in the editor instead of surfacing mid-run.
     */
    @Test
    void tableInBothPoolsIsReportedAsAmbiguous() {
        when(dataTableService.listTables(0L, PlatformType.AUTOMATION)).thenReturn(List.of(table("conversations")));
        when(dataTableService.listTables(0L, PlatformType.EMBEDDED)).thenReturn(List.of(table("conversations")));

        assertEquals(
            "Data table 'conversations' exists in more than one data table pool in this environment, so a run cannot " +
                "tell which one is meant",
            resolver.findProblem("conversations", 0L));
    }

    @Test
    void missingTableReportsEnvironment() {
        when(dataTableService.listTables(1L, PlatformType.AUTOMATION)).thenReturn(List.of(table("orders")));

        assertEquals(
            "Data table 'conversations' does not exist in this environment", resolver.findProblem("conversations", 1L));
    }

    @Test
    void tableWithoutPrimaryKeyReportsRowServiceReason() {
        when(dataTableService.listTables(0L, PlatformType.AUTOMATION)).thenReturn(List.of(table("conversations")));
        when(dataTableRowService.listRows(any(DataTableRef.class), anyInt(), anyInt()))
            .thenThrow(new IllegalStateException("Table does not have primary key column 'id': dt_0_conversations"));

        assertEquals(
            "Table does not have primary key column 'id': dt_0_conversations",
            resolver.findProblem("conversations", 0L));
    }

    private static DataTableInfo table(String baseName) {
        return new DataTableInfo(1L, baseName, null, List.of(), Instant.now());
    }
}
