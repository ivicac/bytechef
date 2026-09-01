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

package com.bytechef.automation.data.table.configuration.facade;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.data.table.configuration.service.WorkspaceDataTableService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableTagService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.data.table.execution.service.DataTableStorageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The workspace facade is an automation-only surface -- every call it makes to the pool-aware {@link DataTableService}
 * must ask for {@link PlatformType#AUTOMATION}, never leave the pool unscoped or defer to some other pool.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDataTableFacadePoolTest {

    private static final long ENVIRONMENT_ID = 0L;
    private static final long WORKSPACE_ID = 1049L;

    @Mock
    private DataTableRowService dataTableRowService;

    @Mock
    private DataTableService dataTableService;

    @Mock
    private DataTableStorageService dataTableStorageService;

    @Mock
    private DataTableTagService dataTableTagService;

    @Mock
    private DataTableWebhookService dataTableWebhookService;

    @Mock
    private WorkspaceDataTableService workspaceDataTableService;

    @InjectMocks
    private WorkspaceDataTableFacadeImpl workspaceDataTableFacade;

    @Test
    void testTheWorkspaceFacadeOnlyEverAsksForAutomation() {
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty()))
            .thenReturn(List.of());
        when(workspaceDataTableService.getWorkspaceDataTables(WORKSPACE_ID)).thenReturn(List.of());

        workspaceDataTableFacade.listTables(WORKSPACE_ID, ENVIRONMENT_ID);

        verify(dataTableService).listTables(ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());
    }

    @Test
    void testCreateGoesIntoTheAutomationPool() {
        List<ColumnSpec> columnSpecs = List.of(new ColumnSpec("title", ColumnType.STRING));

        when(dataTableService.getIdByBaseName("t", PlatformType.AUTOMATION)).thenReturn(7L);

        workspaceDataTableFacade.createTable("t", "d", columnSpecs, WORKSPACE_ID, ENVIRONMENT_ID);

        verify(dataTableService).createTable(
            "t", "d", columnSpecs, ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());
    }
}
