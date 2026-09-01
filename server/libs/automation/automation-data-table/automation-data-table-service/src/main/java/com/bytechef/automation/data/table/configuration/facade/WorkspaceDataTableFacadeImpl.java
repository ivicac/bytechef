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

import com.bytechef.automation.data.table.configuration.domain.WorkspaceDataTable;
import com.bytechef.automation.data.table.configuration.service.WorkspaceDataTableService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableTagService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService.Webhook;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableStorageUsage;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.data.table.execution.service.DataTableStorageService;
import com.bytechef.platform.tag.domain.Tag;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class WorkspaceDataTableFacadeImpl implements WorkspaceDataTableFacade {

    private final DataTableRowService dataTableRowService;
    private final DataTableService dataTableService;
    private final DataTableStorageService dataTableStorageService;
    private final DataTableTagService dataTableTagService;
    private final DataTableWebhookService dataTableWebhookService;
    private final WorkspaceDataTableService workspaceDataTableService;

    @SuppressFBWarnings("EI")
    public WorkspaceDataTableFacadeImpl(
        DataTableRowService dataTableRowService, DataTableService dataTableService,
        DataTableStorageService dataTableStorageService, DataTableTagService dataTableTagService,
        DataTableWebhookService dataTableWebhookService, WorkspaceDataTableService workspaceDataTableService) {

        this.dataTableRowService = dataTableRowService;
        this.dataTableService = dataTableService;
        this.dataTableStorageService = dataTableStorageService;
        this.dataTableTagService = dataTableTagService;
        this.dataTableWebhookService = dataTableWebhookService;
        this.workspaceDataTableService = workspaceDataTableService;
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void addColumn(long dataTableId, ColumnSpec columnSpec, long environmentId) {
        dataTableService.addColumn(
            dataTableService.getBaseNameById(dataTableId), columnSpec, environmentId, PlatformType.AUTOMATION,
            Optional.empty());
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_TABLE_CREATE')")
    public void createTable(
        String baseName, String description, List<ColumnSpec> columnSpecs, long workspaceId, long environmentId) {

        dataTableService.createTable(
            baseName, description, columnSpecs, environmentId, PlatformType.AUTOMATION, Optional.empty());

        long dataTableId = dataTableService.getIdByBaseName(baseName, PlatformType.AUTOMATION);

        workspaceDataTableService.assignDataTableToWorkspace(dataTableId, workspaceId);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void dropTable(long dataTableId, long environmentId) {
        dataTableService.dropTable(
            dataTableService.getBaseNameById(dataTableId), environmentId, PlatformType.AUTOMATION, Optional.empty());
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void duplicateTable(long dataTableId, String newBaseName, long environmentId) {
        dataTableService.duplicateTable(
            dataTableService.getBaseNameById(dataTableId), newBaseName, environmentId, PlatformType.AUTOMATION,
            Optional.empty());

        Optional<Long> workspaceId = workspaceDataTableService.fetchWorkspaceId(dataTableId);

        if (workspaceId.isPresent()) {
            workspaceDataTableService.assignDataTableToWorkspace(
                dataTableService.getIdByBaseName(newBaseName, PlatformType.AUTOMATION), workspaceId.get());
        }
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_TABLE_VIEW')")
    public List<Tag> getDataTableTags(long workspaceId) {
        List<Long> dataTableIds = workspaceDataTableService.getWorkspaceDataTables(workspaceId)
            .stream()
            .map(WorkspaceDataTable::getDataTableId)
            .filter(Objects::nonNull)
            .toList();

        return dataTableTagService.getTags(dataTableIds);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_TABLE_VIEW')")
    public List<DataTableInfo> listTables(long workspaceId, long environmentId) {
        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            environmentId, PlatformType.AUTOMATION, Optional.empty());

        Set<Long> dataTableIds = workspaceDataTableService.getWorkspaceDataTables(workspaceId)
            .stream()
            .map(WorkspaceDataTable::getDataTableId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        return dataTableInfos.stream()
            .filter(dataTableInfo -> dataTableInfo.id() != null && dataTableIds.contains(dataTableInfo.id()))
            .toList();
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void removeColumn(long dataTableId, String columnName, long environmentId) {
        dataTableService.removeColumn(
            dataTableService.getBaseNameById(dataTableId), columnName, environmentId, PlatformType.AUTOMATION,
            Optional.empty());
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void renameColumn(long dataTableId, String fromColumnName, String newName, long environmentId) {
        dataTableService.renameColumn(
            dataTableService.getBaseNameById(dataTableId), fromColumnName, newName, environmentId,
            PlatformType.AUTOMATION, Optional.empty());
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void renameTable(long dataTableId, String newBaseName, long environmentId) {
        dataTableService.renameTable(
            dataTableService.getBaseNameById(dataTableId), newBaseName, environmentId, PlatformType.AUTOMATION,
            Optional.empty());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public List<DataTableRow> listRows(long dataTableId, int limit, int offset, long environmentId) {
        return dataTableRowService.listRows(dataTableRef(dataTableId, environmentId), limit, offset);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public DataTableRow insertRow(long dataTableId, Map<String, Object> values, long environmentId) {
        return dataTableRowService.insertRow(dataTableRef(dataTableId, environmentId), values);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public DataTableRow updateRow(long dataTableId, long rowId, Map<String, Object> values, long environmentId) {
        return dataTableRowService.updateRow(dataTableRef(dataTableId, environmentId), rowId, values);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public boolean deleteRow(long dataTableId, long rowId, long environmentId) {
        return dataTableRowService.deleteRow(dataTableRef(dataTableId, environmentId), rowId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public String exportCsv(long dataTableId, long environmentId) {
        return dataTableRowService.exportCsv(dataTableRef(dataTableId, environmentId));
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void importCsv(long dataTableId, String csv, long environmentId) {
        dataTableRowService.importCsv(dataTableRef(dataTableId, environmentId), csv);
    }

    /**
     * Workspace tables live in the AUTOMATION pool, where no table carries an owner -- ownership belongs to connected
     * users, and those exist only in the embedded pool. Claiming "shared" here is therefore a statement of fact rather
     * than a shortcut past resolution; see {@link DataTableRef}.
     */
    private DataTableRef dataTableRef(long dataTableId, long environmentId) {
        return DataTableRef.shared(
            dataTableService.getBaseNameById(dataTableId), environmentId, PlatformType.AUTOMATION);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void updateTags(long dataTableId, List<Tag> tags) {
        dataTableTagService.updateTags(dataTableId, tags);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public List<Webhook> listWebhooks(long dataTableId, long environmentId) {
        return dataTableWebhookService.listWebhooks(dataTableId, environmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public DataTableStorageUsage getStorageUsage() {
        return dataTableStorageService.getUsage();
    }
}
