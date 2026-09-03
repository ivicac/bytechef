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

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.exception.DataTableException;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.domain.RowFilter;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The row axis, end to end and against a real schema: which rows of ONE shared physical table a run can see and change.
 *
 * <p>
 * Every fixture here is a shared table, because that is the case row ownership exists for -- three accounts resolving
 * one base name all land in {@code edt_0_<name>}, and the only thing separating them is the predicate. The owned table,
 * where the predicate is satisfied by construction, is pinned in {@link DataTableEmbeddedOwnedTableIntTest}.
 *
 * <p>
 * Every ref comes out of {@link DataTableService#fetchDataTableResolution}, not out of a constructor: that call is the
 * single place an owner reaches the row layer, so a test that hand-built its refs would assert the predicate while
 * leaving unasserted the wiring that decides what the predicate is given.
 *
 * <p>
 * Against Postgres rather than a mocked template because what is claimed is what the database returns for a disjunction
 * over a nullable column, which a mock can be made to agree with either way.
 *
 * <p>
 * Fixture tables are named {@code ro_*} because the integration tests in this module share one Spring context and one
 * schema, with no per-class isolation.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
class DataTableRowOwnerScopingIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(7101L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(7102L);
    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testAnAccountReadsItsOwnRowsAndTheUnownedOnesAndNotAnotherAccountsRows() {
        String baseName = createSharedTable("ro_scopedreads");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));
        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));
        dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(titlesRead(baseName, ACCOUNT_A))
            .as("an account reads its own rows and the vendor's unowned ones, and no other account's")
            .containsExactlyInAnyOrder("a", "vendor");
    }

    @Test
    void testAnAccountCannotReadAnotherAccountsRowById() {
        String baseName = createSharedTable("ro_scopedgetrow");

        DataTableRow accountBRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        assertThat(dataTableRowService.getRow(refFor(baseName, ACCOUNT_A), accountBRow.id()))
            .as("another account's row must be indistinguishable from a row that does not exist")
            .isNull();
    }

    @Test
    void testAnAccountCannotDeleteAnotherAccountsRow() {
        String baseName = createSharedTable("ro_scopeddelete");

        DataTableRow accountBRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        assertThat(dataTableRowService.deleteRow(refFor(baseName, ACCOUNT_A), accountBRow.id()))
            .as("deleting another account's row must report no row deleted")
            .isFalse();

        assertThat(titleOf(baseName, accountBRow.id()))
            .as("and must leave it in place")
            .isEqualTo("b");
    }

    @Test
    void testAnAccountCannotUpdateAnotherAccountsRow() {
        String baseName = createSharedTable("ro_scopedupdate");

        DataTableRow accountBRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        assertThatThrownBy(
            () -> dataTableRowService.updateRow(
                refFor(baseName, ACCOUNT_A), accountBRow.id(), Map.of("title", "hijacked")))
                    .isInstanceOf(DataTableException.class);

        assertThat(titleOf(baseName, accountBRow.id())).isEqualTo("b");
    }

    /**
     * The read predicate and the write predicate genuinely differ, and this is where. Account A can SEE the vendor's
     * unowned row -- the first test in this class asserts that -- and still may not rewrite or destroy it, because it
     * is the same row every other account is reading.
     */
    @Test
    void testAnAccountsWriteDoesNotTouchAnUnownedRow() {
        String baseName = createSharedTable("ro_scopedwriteunowned");

        DataTableRow vendorRow = dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(titlesRead(baseName, ACCOUNT_A))
            .as("the unowned row is readable by the account, which is what makes the refusal below meaningful")
            .containsExactly("vendor");

        assertThatThrownBy(
            () -> dataTableRowService.updateRow(
                refFor(baseName, ACCOUNT_A), vendorRow.id(), Map.of("title", "hijacked")))
                    .isInstanceOf(DataTableException.class);

        assertThat(dataTableRowService.deleteRow(refFor(baseName, ACCOUNT_A), vendorRow.id()))
            .as("an account must not delete the reference data every other account reads")
            .isFalse();

        assertThat(titleOf(baseName, vendorRow.id())).isEqualTo("vendor");
    }

    /**
     * A vendor run reads the unowned rows and never falls through to an account's -- the resolution rule the resource
     * axis already establishes, one level down.
     */
    @Test
    void testAVendorRunReadsUnownedRowsOnly() {
        String baseName = createSharedTable("ro_vendorreads");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));
        dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(titlesRead(baseName, null))
            .as("a run with no account must not see an account's rows")
            .containsExactly("vendor");
    }

    @Test
    void testAVendorRunWritesUnownedRowsOnly() {
        String baseName = createSharedTable("ro_vendorwrites");

        DataTableRow accountRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));

        assertThatThrownBy(
            () -> dataTableRowService.updateRow(
                refFor(baseName, null), accountRow.id(), Map.of("title", "hijacked")))
                    .isInstanceOf(DataTableException.class);

        assertThat(dataTableRowService.deleteRow(refFor(baseName, null), accountRow.id()))
            .as("the vendor must not delete an account's row either")
            .isFalse();

        assertThat(titleOf(baseName, accountRow.id())).isEqualTo("a");
    }

    /**
     * Both columns or neither. An {@code owner_id} without an {@code owner_type} matches no predicate at all: not the
     * vendor's, because the id is set, and not the account's, because an owner IS the pair. It would look written and
     * behave as though it were not.
     */
    @Test
    void testAnInsertWithAnOwnerStampsBothColumns() {
        String baseName = createSharedTable("ro_stamped");

        DataTableRow dataTableRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));

        assertThat(ownerIdOf(baseName, dataTableRow.id())).isEqualTo(ACCOUNT_A.id());

        OwnerType ownerType = ACCOUNT_A.type();

        assertThat(ownerTypeOf(baseName, dataTableRow.id()))
            .as("owner_type moves with owner_id; an id beside a null type belongs to nobody")
            .isEqualTo(ownerType.ordinal());
    }

    @Test
    void testAnInsertWithNoOwnerProducesAnUnownedRow() {
        String baseName = createSharedTable("ro_unstamped");

        DataTableRow dataTableRow = dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(ownerIdOf(baseName, dataTableRow.id())).isNull();
        assertThat(ownerTypeOf(baseName, dataTableRow.id())).isNull();
    }

    /**
     * An insert naming no user column at all goes down the {@code DEFAULT VALUES} branch, which is the one place the
     * stamp could be dropped without any other test noticing.
     */
    @Test
    void testAnInsertWithNoValuesIsStillStamped() {
        String baseName = createSharedTable("ro_stampednovalues");

        DataTableRow dataTableRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of());

        assertThat(ownerIdOf(baseName, dataTableRow.id())).isEqualTo(ACCOUNT_A.id());
    }

    /**
     * CSV import goes through the same insert, so the stamp reaches rows that never passed through a value map a caller
     * wrote.
     */
    @Test
    void testImportedRowsAreStampedToo() {
        String baseName = createSharedTable("ro_importstamp");

        dataTableRowService.importCsv(refFor(baseName, ACCOUNT_A), "title\nimported\n");

        assertThat(titlesRead(baseName, ACCOUNT_A)).containsExactly("imported");
        assertThat(titlesRead(baseName, ACCOUNT_B))
            .as("an imported row belongs to the account that imported it")
            .isEmpty();
    }

    @Test
    void testCsvExportCarriesTheReadPredicate() {
        String baseName = createSharedTable("ro_exportscoped");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));
        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        assertThat(dataTableRowService.exportCsv(refFor(baseName, ACCOUNT_A)))
            .contains("a")
            .doesNotContain("b");
    }

    /**
     * A workflow may still narrow within what it is allowed to see, and must not be able to widen it. The filter is
     * ANDed onto the owner predicate rather than replacing it.
     */
    @Test
    void testAFilteredReadIsStillScopedToTheAccount() {
        String baseName = createSharedTable("ro_filtered");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "shared-value"));
        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "shared-value"));

        assertThat(
            dataTableRowService.listRows(
                refFor(baseName, ACCOUNT_A), 100, 0,
                List.of(new RowFilter("title", RowFilter.Operator.EQ, "shared-value")), List.of()))
                    .as("a filter matching both accounts' rows must still return only the caller's")
                    .hasSize(1);
    }

    /**
     * The other half of the same claim, and the one that catches a mis-bracketed predicate. The read is a disjunction
     * and the caller's filters are appended after it; {@code AND} binds tighter than {@code OR}, so an unbracketed
     * predicate reads as "every row of mine, OR an unowned row matching the filter" -- the filter stops narrowing the
     * caller's own rows at all.
     */
    @Test
    void testAFilterStillNarrowsWithinTheAccountsOwnRows() {
        String baseName = createSharedTable("ro_filternarrow");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "keep"));
        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "drop"));

        assertThat(
            dataTableRowService.listRows(
                refFor(baseName, ACCOUNT_A), 100, 0,
                List.of(new RowFilter("title", RowFilter.Operator.EQ, "keep")), List.of()))
                    .as("a filter must narrow the caller's own rows, not be short-circuited past them")
                    .hasSize(1);
    }

    /**
     * The pool split still holds, and is a different mechanism from the row predicate: an EMBEDDED ref and an
     * AUTOMATION ref for one base name are two physical tables, so no owner predicate is asked to carry that
     * separation.
     */
    @Test
    void testAnEmbeddedRefNeverReachesAnAutomationTable() {
        dataTableService.createTable(
            "ro_pooled", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION);
        dataTableService.createTable(
            "ro_pooled", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED);

        DataTableRef automationDataTableRef = resolve("ro_pooled", PlatformType.AUTOMATION, null);

        dataTableRowService.insertRow(automationDataTableRef, Map.of("title", "automation"));

        DataTableRef embeddedDataTableRef = resolve("ro_pooled", PlatformType.EMBEDDED, null);

        assertThat(dataTableRowService.listRows(embeddedDataTableRef, 100, 0))
            .as("an embedded run must not read the automation pool's rows")
            .isEmpty();
    }

    /**
     * The bulk counterpart to {@link #testAnAccountCannotDeleteAnotherAccountsRow}. {@code deleteRows} carries the
     * writable predicate exactly the single-row {@code deleteRow} does, but it is a separate SQL statement built by
     * hand -- nothing stops a future edit from swapping in the readable predicate on this method alone, leaving
     * {@code deleteRow} untouched and the suite still green.
     */
    @Test
    void testDeleteRowsCannotDeleteAnotherAccountsRow() {
        String baseName = createSharedTable("ro_scopedbulkdelete");

        DataTableRow accountARow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));
        DataTableRow accountBRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        List<Long> deletedIds = dataTableRowService.deleteRows(
            refFor(baseName, ACCOUNT_A), List.of(accountARow.id(), accountBRow.id()));

        assertThat(deletedIds)
            .as("a bulk delete must only report the caller's own row as deleted")
            .containsExactly(accountARow.id());
        assertThat(countAllRows(baseName))
            .as("another account's row must survive a bulk delete that named it")
            .isEqualTo(1);
        assertThat(titleOf(baseName, accountBRow.id())).isEqualTo("b");
    }

    /**
     * The same risk as {@link #testDeleteRowsCannotDeleteAnotherAccountsRow}, for the table-wide form: a run that
     * clears "its" rows must not touch another account's, even though {@code clearRows} takes no ids to check at all.
     */
    @Test
    void testClearRowsCannotClearAnotherAccountsRows() {
        String baseName = createSharedTable("ro_scopedclear");

        dataTableRowService.insertRow(refFor(baseName, ACCOUNT_A), Map.of("title", "a"));
        DataTableRow accountBRow = dataTableRowService.insertRow(refFor(baseName, ACCOUNT_B), Map.of("title", "b"));

        long clearedCount = dataTableRowService.clearRows(refFor(baseName, ACCOUNT_A));

        assertThat(clearedCount)
            .as("clearRows must only count the caller's own rows")
            .isEqualTo(1);
        assertThat(countAllRows(baseName))
            .as("another account's row must survive a clear it did not ask for")
            .isEqualTo(1);
        assertThat(titleOf(baseName, accountBRow.id())).isEqualTo("b");
    }

    /**
     * The case that actually distinguishes {@code writableOwnerPredicate} from {@code readableOwnerPredicate}: an
     * unowned row. Both predicates reject another account's owned row alike, so
     * {@link #testDeleteRowsCannotDeleteAnotherAccountsRow} cannot tell a {@code deleteRows} that was accidentally
     * built on the readable predicate from one built on the writable one -- only an unowned row can, because the
     * readable predicate admits it and the writable one does not. Mirrors the single-row template,
     * {@link #testAnAccountsWriteDoesNotTouchAnUnownedRow}.
     */
    @Test
    void testDeleteRowsCannotDeleteAnUnownedRow() {
        String baseName = createSharedTable("ro_scopedbulkdeleteunowned");

        DataTableRow vendorRow = dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(titlesRead(baseName, ACCOUNT_A))
            .as("the unowned row is readable by the account, which is what makes the refusal below meaningful")
            .containsExactly("vendor");

        List<Long> deletedIds = dataTableRowService.deleteRows(refFor(baseName, ACCOUNT_A), List.of(vendorRow.id()));

        assertThat(deletedIds)
            .as("an account must not delete the reference data every other account reads")
            .isEmpty();
        assertThat(titleOf(baseName, vendorRow.id())).isEqualTo("vendor");
    }

    /**
     * The table-wide counterpart to {@link #testDeleteRowsCannotDeleteAnUnownedRow}, for the same reason
     * {@link #testClearRowsCannotClearAnotherAccountsRows} alone cannot tell the two predicates apart.
     */
    @Test
    void testClearRowsCannotClearUnownedRows() {
        String baseName = createSharedTable("ro_scopedclearunowned");

        DataTableRow vendorRow = dataTableRowService.insertRow(refFor(baseName, null), Map.of("title", "vendor"));

        assertThat(titlesRead(baseName, ACCOUNT_A))
            .as("the unowned row is readable by the account, which is what makes the refusal below meaningful")
            .containsExactly("vendor");

        long clearedCount = dataTableRowService.clearRows(refFor(baseName, ACCOUNT_A));

        assertThat(clearedCount)
            .as("clearRows must not count the reference data every other account reads")
            .isZero();
        assertThat(titleOf(baseName, vendorRow.id())).isEqualTo("vendor");
    }

    /**
     * The ownership index makes the row owner part of the conflict key, so two accounts upserting the same
     * {@code external_id} against the same shared table must land on two separate rows rather than one account's upsert
     * merging onto the other's.
     */
    @Test
    void testUpsertCannotLandOnAnotherAccountsRow() {
        String baseName = createSharedTable("ro_upsertscoped");

        DataTableRef accountOneRef = refFor(baseName, ACCOUNT_A);
        DataTableRef accountTwoRef = refFor(baseName, ACCOUNT_B);

        dataTableRowService.upsertRow(accountOneRef, "ORD-1", Map.of("title", "one"));
        dataTableRowService.upsertRow(accountTwoRef, "ORD-1", Map.of("title", "two"));

        assertThat(dataTableRowService.fetchRowByExternalId(accountOneRef, "ORD-1"))
            .isPresent()
            .get()
            .extracting(dataTableRow -> dataTableRow.values()
                .get("title"))
            .isEqualTo("one");
        assertThat(dataTableRowService.fetchRowByExternalId(accountTwoRef, "ORD-1"))
            .isPresent()
            .get()
            .extracting(dataTableRow -> dataTableRow.values()
                .get("title"))
            .isEqualTo("two");
        assertThat(countAllRows(baseName))
            .as("each account's upsert must create its own row, not merge onto the other account's")
            .isEqualTo(2);
    }

    private String createSharedTable(String baseName) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED);

        return baseName;
    }

    private DataTableRef refFor(String baseName, @Nullable Owner owner) {
        return resolve(baseName, PlatformType.EMBEDDED, owner);
    }

    /**
     * Through resolution, always. Building a ref directly here would let a test pass while the one wiring that puts an
     * owner on a ref was broken.
     */
    private DataTableRef resolve(String baseName, PlatformType platformType, @Nullable Owner owner) {
        Optional<DataTableResolution> dataTableResolutionOptional = dataTableService.fetchDataTableResolution(
            baseName, ENVIRONMENT_ID, platformType, Optional.ofNullable(owner));

        assertThat(dataTableResolutionOptional).isPresent();

        DataTableResolution dataTableResolution = dataTableResolutionOptional.get();

        return dataTableResolution.dataTableRef();
    }

    private List<String> titlesRead(String baseName, @Nullable Owner owner) {
        return dataTableRowService.listRows(resolve(baseName, PlatformType.EMBEDDED, owner), 100, 0)
            .stream()
            .map(dataTableRow -> {
                Map<String, Object> values = dataTableRow.values();

                return String.valueOf(values.get("title"));
            })
            .toList();
    }

    private String titleOf(String baseName, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"title\" FROM " + physicalName(baseName) + " WHERE \"id\" = ?", String.class, id);
    }

    private Long ownerIdOf(String baseName, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"owner_id\" FROM " + physicalName(baseName) + " WHERE \"id\" = ?", Long.class, id);
    }

    private Integer ownerTypeOf(String baseName, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"owner_type\" FROM " + physicalName(baseName) + " WHERE \"id\" = ?", Integer.class, id);
    }

    private Integer countAllRows(String baseName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + physicalName(baseName), Integer.class);
    }

    private static String physicalName(String baseName) {
        DataTableRef dataTableRef = DataTableRef.unowned(baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED);

        return "\"" + dataTableRef.physicalName() + "\"";
    }
}
