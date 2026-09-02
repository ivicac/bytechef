/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.facade;

import static org.mockito.Mockito.verify;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The {@code isTenantAdmin()} gate on {@code createDataTable} is asserted once, in the canonical enumeration in
 * {@link EmbeddedDataTableApiFacadeTest#testEveryFacadeMethodIsGatedOnTenantAdmin()}, not here -- a second reflection
 * assertion in this file would just be a second place for that list to go stale.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedDataTableApiFacadeCreateTest {

    private static final long ENVIRONMENT_ID = 0;

    @Mock
    private DataTableService dataTableService;

    @InjectMocks
    private EmbeddedDataTableApiFacadeImpl embeddedDataTableApiFacade;

    @Test
    void testCreateGoesIntoTheEmbeddedPool() {
        List<ColumnSpec> columnSpecs = List.of(new ColumnSpec("title", ColumnType.STRING));

        embeddedDataTableApiFacade.createDataTable(ENVIRONMENT_ID, "conversations", "d", columnSpecs);

        verify(dataTableService).createTable("conversations", "d", columnSpecs, ENVIRONMENT_ID, PlatformType.EMBEDDED);
    }
}
