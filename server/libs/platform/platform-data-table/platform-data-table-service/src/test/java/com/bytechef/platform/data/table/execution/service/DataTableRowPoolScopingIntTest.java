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

package com.bytechef.platform.data.table.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * What a run reaches in the EMBEDDED pool now that ownership lives on the table rather than on the row: which physical
 * table a base name resolves to, and therefore which rows a bulk operation over it can see.
 *
 * <p>
 * Row-by-row scoping used to be tested here at length. It is gone -- a resolved table carries one owner for all of its
 * rows -- so the isolation claims live in {@link DataTableEmbeddedOwnedTableIntTest}, which asserts them against the
 * physical tables themselves. What remains here is the part that is not about a single row: the vendor's view of an
 * assigned table, and the bulk read/write paths that walk a whole table at once.
 *
 * <p>
 * Every fixture table is named {@code ps_*} because the integration tests in this module share one Spring context and
 * one schema, with no per-class isolation.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableRowPoolScopingIntTest {

    private static final Owner ACCOUNT_ALICE = Owner.connectedUser(1055L);
    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Test
    void testAVendorRunStillSeesATableAssignedToAnAccount() {
        createTable("ps_assigned", null);

        long id = dataTableService.getIdByBaseName("ps_assigned", PlatformType.EMBEDDED);

        dataTableService.assignOwner(id, ACCOUNT_ALICE);

        // Table-level ownership is metadata the vendor manages and must be able to see; only the ROWS inside are the
        // account's. Filtering both levels alike would make the console's assignment view unusable.
        assertThat(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty()))
            .extracting(DataTableInfo::baseName)
            .contains("ps_assigned");
    }

    /**
     * An export walks the whole table in one statement, so it is the widest way to read across an ownership boundary.
     * It must return the resolved table's rows and nothing from anyone else's.
     */
    @Test
    void testAnExportReadsOnlyTheResolvedTablesRows() {
        createTable("ps_export", ACCOUNT_ALICE);
        createTable("ps_export", null);

        dataTableRowService.insertRow(resolve("ps_export", ACCOUNT_ALICE), Map.of("title", "alice"));
        dataTableRowService.insertRow(resolve("ps_export", null), Map.of("title", "vendor"));

        assertThat(dataTableRowService.exportCsv(resolve("ps_export", ACCOUNT_ALICE)))
            .contains("alice")
            .doesNotContain("vendor");
    }

    /**
     * An import writes rows in bulk, and is the counterpart risk to the export: every row it produces has to land in
     * the table the caller resolved rather than in the shared one.
     */
    @Test
    void testAnImportWritesOnlyIntoTheResolvedTable() {
        createTable("ps_import", ACCOUNT_ALICE);
        createTable("ps_import", null);

        dataTableRowService.importCsv(resolve("ps_import", ACCOUNT_ALICE), "title\nalice\n");

        assertThat(titles(resolve("ps_import", ACCOUNT_ALICE))).containsExactly("alice");
        assertThat(titles(resolve("ps_import", null)))
            .as("an import into an account's table must not add rows every account reads")
            .isEmpty();
    }

    /**
     * An update naming no real column returns the current row instead of writing. That read goes through the same
     * resolved table as any other, so a row id from elsewhere is simply not found.
     */
    @Test
    void testAnUpdateThatChangesNothingReadsTheResolvedTable() {
        createTable("ps_noop_update", ACCOUNT_ALICE);

        DataTableRef dataTableRef = resolve("ps_noop_update", ACCOUNT_ALICE);

        DataTableRow row = dataTableRowService.insertRow(dataTableRef, Map.of("title", "alice"));

        assertThat(
            dataTableRowService.updateRow(dataTableRef, row.id(), Map.of("nosuchcolumn", "x"))
                .values())
                    .containsEntry("title", "alice");

        assertThatThrownBy(() -> dataTableRowService.updateRow(dataTableRef, row.id() + 1000, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Row not found");
    }

    @Test
    void testAnAutomationWriteIsUnaffected() {
        dataTableService.createTable(
            "ps_plain", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());

        DataTableRow row = dataTableRowService.insertRow(
            DataTableRef.shared("ps_plain", ENVIRONMENT_ID, PlatformType.AUTOMATION), Map.of("title", "x"));

        assertThat(row).isNotNull();
    }

    private void createTable(String baseName, Owner owner) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.ofNullable(owner));
    }

    private DataTableRef resolve(String baseName, Owner owner) {
        DataTableResolution dataTableResolution = dataTableService.fetchDataTableResolution(
            baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.ofNullable(owner))
            .orElseThrow();

        return dataTableResolution.dataTableRef();
    }

    private List<String> titles(DataTableRef dataTableRef) {
        return dataTableRowService.listRows(dataTableRef, 100, 0)
            .stream()
            .map(dataTableRow -> String.valueOf(
                dataTableRow.values()
                    .get("title")))
            .toList();
    }
}
