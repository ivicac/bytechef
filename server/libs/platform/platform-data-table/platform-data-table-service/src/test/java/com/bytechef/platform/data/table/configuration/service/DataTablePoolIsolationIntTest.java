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

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTablePoolIsolationIntTest {

    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testEachPoolGetsItsOwnPhysicalPrefix() {
        dataTableService.createTable(
            "orders", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());
        dataTableService.createTable(
            "orders", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.empty());

        assertThat(physicalTableExists("dt_0_orders")).isTrue();
        assertThat(physicalTableExists("edt_0_orders")).isTrue();
    }

    @Test
    void testNeitherPoolListsTheOther() {
        dataTableService.createTable(
            "automationOnly".toLowerCase(), null, List.of(new ColumnSpec("a", ColumnType.STRING)),
            ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty());
        dataTableService.createTable(
            "embeddedonly", null, List.of(new ColumnSpec("a", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.empty());

        assertThat(baseNames(PlatformType.AUTOMATION))
            .contains("automationonly")
            .doesNotContain("embeddedonly");
        assertThat(baseNames(PlatformType.EMBEDDED))
            .contains("embeddedonly")
            .doesNotContain("automationonly");
    }

    @Test
    void testTheColumnAndThePrefixAgree() {
        dataTableService.createTable(
            "agreeing", null, List.of(new ColumnSpec("a", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.empty());

        long id = dataTableService.getIdByBaseName("agreeing", PlatformType.EMBEDDED);

        Integer storedOrdinal = jdbcTemplate.queryForObject(
            "SELECT platform_type FROM data_table WHERE id = ?", Integer.class, id);

        assertThat(storedOrdinal).isEqualTo(PlatformType.EMBEDDED.ordinal());
        assertThat(physicalTableExists("edt_0_agreeing")).isTrue();
        assertThat(physicalTableExists("dt_0_agreeing")).isFalse();
    }

    @Test
    void testRowsGoIntoTheRightPhysicalTable() {
        dataTableService.createTable(
            "rows", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.empty());

        dataTableRowService.insertRow(
            dataTableRef("rows", ENVIRONMENT_ID, PlatformType.EMBEDDED), Map.of("title", "x"));

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM edt_0_rows", Integer.class);

        assertThat(count).as("the row must land in the embedded physical table, not dt_0_rows")
            .isEqualTo(1);
    }

    /**
     * Regression for a review finding: {@code dropTable} scoped {@code hasPhysicalTablesForBaseName} to the pool but
     * left the registry find/delete unscoped, so dropping the last EMBEDDED instance of a name that also exists in
     * AUTOMATION could delete the AUTOMATION registry row too (or throw, since two rows now share the name).
     */
    @Test
    void testDroppingOnePoolsLastInstanceLeavesTheOtherPoolsRegistryRowIntact() {
        dataTableService.createTable(
            "dualpool", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());
        dataTableService.createTable(
            "dualpool", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.empty());

        dataTableService.dropTable("dualpool", ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty());

        assertThat(physicalTableExists("edt_0_dualpool")).isFalse();
        assertThat(physicalTableExists("dt_0_dualpool")).isTrue();
        assertThat(baseNames(PlatformType.AUTOMATION))
            .as("dropping the EMBEDDED instance must not delete the AUTOMATION registry row")
            .contains("dualpool");
    }

    /**
     * Regression for a review finding: the shared {@code PhysicalTableNaming} helper dropped the lowercasing both
     * original {@code buildPhysicalName} copies had, so a mixed-case base name built a physical name that DDL (which
     * still lowercases through {@code escapeIdentifier}) and raw {@code information_schema} lookups (which do not)
     * disagreed on.
     */
    @Test
    void testMixedCaseBaseNameStillResolvesTheRightPhysicalTable() {
        dataTableService.createTable(
            "MixedCase", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        DataTableRow inserted = dataTableRowService
            .insertRow(dataTableRef("MixedCase", ENVIRONMENT_ID, PlatformType.AUTOMATION), Map.of("title", "x"));

        assertThat(inserted).isNotNull();

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dt_0_mixedcase", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    private List<String> baseNames(PlatformType platformType) {
        return dataTableService.listTables(ENVIRONMENT_ID, platformType, Optional.empty())
            .stream()
            .map(DataTableInfo::baseName)
            .toList();
    }

    private boolean physicalTableExists(String physicalName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                + "AND table_name = ?",
            Integer.class, physicalName);

        return count != null && count > 0;
    }

    /**
     * These tables are all created shared, so naming the shared physical form here states a fact about the fixture
     * rather than skipping resolution.
     */
    private static DataTableRef dataTableRef(String baseName, long environmentId, PlatformType platformType) {
        return DataTableRef.shared(baseName, environmentId, platformType);
    }

}
