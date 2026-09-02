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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The BULK row paths -- export, import, and the update that writes nothing -- walk or address a whole table in one
 * statement, which makes them the widest way to cross an ownership boundary. Every other test of the row predicate
 * drives it one row at a time; these drive it through the statements that do not.
 *
 * <p>
 * A base name is one physical table shared by every account in the pool, so the predicate is the whole of the
 * separation here and there is no second table to fall back on. That makes the export assertion two-sided and worth
 * stating in full: an account's export must carry its own rows AND the vendor's unowned ones -- an unowned row belongs
 * to nobody and is readable by everyone, and a bulk read that dropped them would disagree with every row-at-a-time read
 * -- while carrying nothing of another account's.
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
    private static final Owner ACCOUNT_BOB = Owner.connectedUser(1056L);
    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    /**
     * An export is a single statement over the whole table, so it is where a missing predicate would empty the table
     * out onto whoever asked. Asserted in both directions at once: what must be there and what must not.
     */
    @Test
    void testAnExportReadsTheAccountsOwnRowsAndTheUnownedOnesOnly() {
        createTable("ps_export");

        dataTableRowService.insertRow(resolve("ps_export", ACCOUNT_ALICE), Map.of("title", "alice"));
        dataTableRowService.insertRow(resolve("ps_export", ACCOUNT_BOB), Map.of("title", "bob"));
        dataTableRowService.insertRow(resolve("ps_export", null), Map.of("title", "vendor"));

        assertThat(dataTableRowService.exportCsv(resolve("ps_export", ACCOUNT_ALICE)))
            .as("a bulk read carries the same rows a row-at-a-time read would: the account's own and the unowned")
            .contains("alice")
            .contains("vendor")
            .doesNotContain("bob");
    }

    /**
     * The vendor side of the same statement, and the direction a leak would actually live in: a run acting for no
     * account reads the unowned rows and never falls through to an account's.
     */
    @Test
    void testAVendorExportReadsTheUnownedRowsOnly() {
        createTable("ps_vendor_export");

        dataTableRowService.insertRow(resolve("ps_vendor_export", ACCOUNT_ALICE), Map.of("title", "alice"));
        dataTableRowService.insertRow(resolve("ps_vendor_export", null), Map.of("title", "vendor"));

        assertThat(dataTableRowService.exportCsv(resolve("ps_vendor_export", null)))
            .contains("vendor")
            .doesNotContain("alice");
    }

    /**
     * An import writes rows in bulk, and is the counterpart risk to the export: every row it produces has to be stamped
     * with the importing account, or it lands unowned and becomes every account's.
     */
    @Test
    void testAnImportStampsEveryRowItWritesWithTheImportingAccount() {
        createTable("ps_import");

        dataTableRowService.importCsv(resolve("ps_import", ACCOUNT_ALICE), "title\nalice\nalice2\n");

        assertThat(titles(resolve("ps_import", ACCOUNT_ALICE))).containsExactlyInAnyOrder("alice", "alice2");
        assertThat(titles(resolve("ps_import", ACCOUNT_BOB)))
            .as("an import must not add rows another account reads")
            .isEmpty();
        assertThat(titles(resolve("ps_import", null)))
            .as("nor rows the vendor reads, which is what an unstamped import would produce")
            .isEmpty();
    }

    /**
     * An update naming no real column returns the current row instead of writing. That read goes through the same
     * predicate as any other, so a row id belonging to another account is simply not found.
     */
    @Test
    void testAnUpdateThatChangesNothingStillAppliesThePredicate() {
        createTable("ps_noop_update");

        DataTableRef dataTableRef = resolve("ps_noop_update", ACCOUNT_ALICE);

        DataTableRow row = dataTableRowService.insertRow(dataTableRef, Map.of("title", "alice"));

        assertThat(
            dataTableRowService.updateRow(dataTableRef, row.id(), Map.of("nosuchcolumn", "x"))
                .values())
                    .containsEntry("title", "alice");

        assertThatThrownBy(
            () -> dataTableRowService.updateRow(resolve("ps_noop_update", ACCOUNT_BOB), row.id(), Map.of()))
                .as("another account's row is not found rather than returned")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row not found");

        assertThatThrownBy(() -> dataTableRowService.updateRow(dataTableRef, row.id() + 1000, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Row not found");
    }

    @Test
    void testAnAutomationWriteIsUnaffected() {
        dataTableService.createTable(
            "ps_plain", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION);

        DataTableRow row = dataTableRowService.insertRow(
            DataTableRef.unowned("ps_plain", ENVIRONMENT_ID, PlatformType.AUTOMATION), Map.of("title", "x"));

        assertThat(row).isNotNull();
    }

    private void createTable(String baseName) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED);
    }

    private DataTableRef resolve(String baseName, @Nullable Owner owner) {
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
