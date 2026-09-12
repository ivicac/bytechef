/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.data.table.remote.client.service;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class RemoteDataTableServiceClient implements DataTableService {

    @Override
    public void createTable(
        String baseName, String description, List<ColumnSpec> columnSpecs, long environmentId,
        PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addColumn(String baseName, ColumnSpec columnSpec, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeColumn(
        String baseName, String columnName, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void renameColumn(
        String baseName, String fromColumnName, String toColumnName, long environmentId,
        PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropTable(String baseName, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<DataTableInfo> listTables(long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DataTable> fetchDataTable(DataTableRef dataTableRef) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DataTableResolution> fetchDataTableResolution(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getBaseNameById(long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getIdByBaseName(String baseName, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void renameTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void duplicateTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DataTableInfo> fetchDataTableInfo(
        String baseName, long environmentId, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateDescription(String baseName, @Nullable String description, PlatformType platformType) {
        throw new UnsupportedOperationException();
    }
}
