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
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableServiceIntTest {

    private static final long DEV_ENVIRONMENT_ID = 0;
    private static final long STAGE_ENVIRONMENT_ID = 1;

    private static final List<String> BASE_NAMES = List.of(
        "registered", "original", "copy", "shared", "source", "duplicate");

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @BeforeEach
    void beforeEach() {
        for (String baseName : BASE_NAMES) {
            dataTableService.dropTable(baseName, DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());
            dataTableService.dropTable(baseName, STAGE_ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());
        }
    }

    @Test
    void testCreateTableRegistersTheTable() {
        createTable("registered", "a description", DEV_ENVIRONMENT_ID);

        assertEquals(
            "registered",
            dataTableService.getBaseNameById(dataTableService.getIdByBaseName("registered", PlatformType.AUTOMATION)));

        assertTrue(
            listedIn(DEV_ENVIRONMENT_ID, "registered"),
            "A created table must be visible to listTables, which skips unregistered physical tables");

        assertThrowsExactly(
            BadSqlGrammarException.class, () -> createTable("registered", "a description", DEV_ENVIRONMENT_ID),
            "The same name twice in one environment is the physical table colliding, not a registry decision");
    }

    /**
     * The registry row is the LOGICAL table; each environment holds its own physical instance of it. dropTable already
     * treats it that way -- it removes the row only once no physical table for the base name remains in any environment
     * -- so creation has to reuse an existing row rather than insert a second one that uk_data_table_name forbids.
     */
    @Test
    void testTheSameNameCanBeCreatedInASecondEnvironment() {
        createTable("shared", "a description", DEV_ENVIRONMENT_ID);
        createTable("shared", "a description", STAGE_ENVIRONMENT_ID);

        assertTrue(
            listedIn(DEV_ENVIRONMENT_ID, "shared"),
            "the table must remain visible in the environment it was created in");
        assertTrue(listedIn(STAGE_ENVIRONMENT_ID, "shared"), "and be visible in the second environment");

        assertEquals(
            1, countNamed(DEV_ENVIRONMENT_ID, "shared"),
            "reusing the row must not make the table appear twice in the environment that already had it");
    }

    @Test
    void testCreateTableRegistersAMixedCaseNameUnderItsLowercasedForm() {
        createTable("Registered", "a description", DEV_ENVIRONMENT_ID);

        assertTrue(
            listedIn(DEV_ENVIRONMENT_ID, "registered"),
            "listTables derives the base name from the lowercased physical table, so the registry must agree");

        assertEquals(
            dataTableService.getIdByBaseName("Registered", PlatformType.AUTOMATION),
            dataTableService.getIdByBaseName("registered", PlatformType.AUTOMATION));
    }

    @Test
    void testDuplicateTableRegistersTheCopy() {
        createTable("original", null, DEV_ENVIRONMENT_ID);

        dataTableService.duplicateTable(
            "original", "copy", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());

        assertEquals(
            "copy",
            dataTableService.getBaseNameById(dataTableService.getIdByBaseName("copy", PlatformType.AUTOMATION)));
    }

    @Test
    void testADuplicatedTableCanStillTakeRows() {
        createTable("source", null, DEV_ENVIRONMENT_ID);

        dataTableService.duplicateTable(
            "source", "duplicate", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());

        // The duplicate is created through the same buildCreateTableSql as the original, so a column declared in one
        // path and forgotten in the other would surface here as a write into a table missing it.
        dataTableRowService.insertRow(
            dataTableRef("duplicate", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION), Map.of("title", "a"));

        assertEquals(
            1,
            dataTableRowService
                .listRows(dataTableRef("duplicate", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION), 100, 0)
                .size());
    }

    private void createTable(String baseName, @Nullable String description, long environmentId) {

        dataTableService.createTable(
            baseName, description, List.of(new ColumnSpec("title", ColumnType.STRING)), environmentId,
            PlatformType.AUTOMATION, Optional.empty());
    }

    private long countNamed(long environmentId, String baseName) {
        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            environmentId, PlatformType.AUTOMATION, Optional.empty());

        return dataTableInfos.stream()
            .filter(dataTableInfo -> baseName.equals(dataTableInfo.baseName()))
            .count();
    }

    private boolean listedIn(long environmentId, String baseName) {
        return countNamed(environmentId, baseName) > 0;
    }

    /**
     * These tables are all created shared, so naming the shared physical form here states a fact about the fixture
     * rather than skipping resolution.
     */
    private static DataTableRef dataTableRef(String baseName, long environmentId, PlatformType platformType) {
        return DataTableRef.shared(baseName, environmentId, platformType);
    }
}
