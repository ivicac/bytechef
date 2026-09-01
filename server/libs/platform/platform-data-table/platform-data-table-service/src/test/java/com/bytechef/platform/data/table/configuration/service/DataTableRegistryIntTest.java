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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableRegistryIntTest {

    private static final long ENVIRONMENT_ID = 0;
    private static final long OTHER_ENVIRONMENT_ID = 1;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Test
    void testCreateTableRegistersTheTable() {
        dataTableService.createTable(
            "registered", "a description", List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        assertEquals(
            "registered",
            dataTableService.getBaseNameById(
                dataTableService.getIdByBaseName("registered", PlatformType.AUTOMATION)));

        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());

        assertTrue(
            dataTableInfos.stream()
                .anyMatch(dataTableInfo -> "registered".equals(dataTableInfo.baseName())),
            "A created table must be visible to listTables, which skips unregistered physical tables");
    }

    /**
     * The registry row is the LOGICAL table; each environment holds its own physical instance of it. dropTable already
     * treats it that way -- it removes the row only once no physical table for the base name remains in any environment
     * -- so creation has to reuse an existing row rather than insert a second one that uk_data_table_name forbids.
     */
    @Test
    void testTheSameNameCanBeCreatedInASecondEnvironment() {
        dataTableService.createTable(
            "shared", "a description", List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());
        dataTableService.createTable(
            "shared", "a description", List.of(new ColumnSpec("title", ColumnType.STRING)), OTHER_ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        assertTrue(
            listedIn(ENVIRONMENT_ID, "shared"), "the table must remain visible in the environment it was created in");
        assertTrue(listedIn(OTHER_ENVIRONMENT_ID, "shared"), "and be visible in the second environment");
    }

    private boolean listedIn(long environmentId, String baseName) {
        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            environmentId, PlatformType.AUTOMATION, Optional.empty());

        return dataTableInfos.stream()
            .anyMatch(dataTableInfo -> baseName.equals(dataTableInfo.baseName()));
    }

    @Test
    void testDuplicateTableRegistersTheCopy() {
        dataTableService.createTable(
            "original", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        dataTableService.duplicateTable("original", "copy", ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());

        assertEquals(
            "copy",
            dataTableService.getBaseNameById(dataTableService.getIdByBaseName("copy", PlatformType.AUTOMATION)));
    }

    @Test
    void testADuplicatedTableCanStillTakeRows() {
        dataTableService.createTable(
            "source", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        dataTableService.duplicateTable("source", "duplicate", ENVIRONMENT_ID, PlatformType.AUTOMATION,
            Optional.empty());

        // The duplicate is created through the same buildCreateTableSql as the original, so a column declared in one
        // path and forgotten in the other would surface here as a write into a table missing it.
        dataTableRowService.insertRow(
            dataTableRef("duplicate", ENVIRONMENT_ID, PlatformType.AUTOMATION), Map.of("title", "a"));

        assertEquals(
            1,
            dataTableRowService
                .listRows(dataTableRef("duplicate", ENVIRONMENT_ID, PlatformType.AUTOMATION), 100, 0)
                .size());
    }

    /**
     * These tables are all created shared, so naming the shared physical form here states a fact about the fixture
     * rather than skipping resolution.
     */
    private static DataTableRef dataTableRef(String baseName, long environmentId, PlatformType platformType) {
        return DataTableRef.shared(baseName, environmentId, platformType);
    }

}
