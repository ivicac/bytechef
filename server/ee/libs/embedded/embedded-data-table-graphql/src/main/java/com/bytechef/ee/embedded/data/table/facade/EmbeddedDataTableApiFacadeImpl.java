/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.facade;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@SuppressFBWarnings("EI")
public class EmbeddedDataTableApiFacadeImpl implements EmbeddedDataTableApiFacade {

    private final DataTableService dataTableService;

    public EmbeddedDataTableApiFacadeImpl(DataTableService dataTableService) {
        this.dataTableService = dataTableService;
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public List<DataTableInfo> getDataTables(long environmentId) {
        return dataTableService.listTables(environmentId, PlatformType.EMBEDDED);
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public void createDataTable(long environmentId, String name, String description, List<ColumnSpec> columnSpecs) {
        dataTableService.createTable(name, description, columnSpecs, environmentId, PlatformType.EMBEDDED);
    }
}
