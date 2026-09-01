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
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ownership only means anything in the EMBEDDED pool, and nothing anywhere exercised it end to end -- which is how a
 * change shipped in which every embedded read returned every account's rows and every embedded write threw, with the
 * whole suite green.
 *
 * <p>
 * Against a real schema rather than mocks on purpose: what is being asserted is that two accounts naming the same table
 * end up in two different Postgres tables, and a mock of the row service cannot be wrong about that.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
class DataTableEmbeddedOwnedTableIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(4201L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(4202L);
    private static final long ENVIRONMENT_ID = 0;
    private static final long OTHER_ENVIRONMENT_ID = 1;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testAnOwnedTableGetsAPhysicalTableOfItsOwn() {
        createTable("ownedorders", ACCOUNT_A);

        assertThat(physicalTableExists("edt_0_4201_connecteduser_ownedorders"))
            .as("an owned table must not share the vendor's physical table")
            .isTrue();
        assertThat(physicalTableExists("edt_0_ownedorders")).isFalse();

        // The registry half of the same create. The physical name alone would still be right if the row had been
        // written unowned, and resolution keys off the row, not off the table that happens to exist.
        assertThat(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.of(ACCOUNT_A)))
            .filteredOn(dataTableInfo -> "ownedorders".equals(dataTableInfo.baseName()))
            .singleElement()
            .extracting(DataTableInfo::ownerId)
            .isEqualTo(ACCOUNT_A.id());
    }

    @Test
    void testASharedTableKeepsItsUnownedPhysicalName() {
        createTable("sharedorders", null);

        assertThat(physicalTableExists("edt_0_sharedorders"))
            .as("existing shared tables are released data and must never be renamed")
            .isTrue();
    }

    /**
     * The whole point of the design: two accounts each holding an {@code invoices}, resolving the same base name, must
     * land in different physical tables and see only their own rows.
     */
    @Test
    void testTwoAccountsOwningTheSameNameDoNotSeeEachOthersRows() {
        createTable("twoaccounts", ACCOUNT_A);
        createTable("twoaccounts", ACCOUNT_B);

        dataTableRowService.insertRow(resolve("twoaccounts", ACCOUNT_A), Map.of("title", "a"));
        dataTableRowService.insertRow(resolve("twoaccounts", ACCOUNT_B), Map.of("title", "b"));

        assertThat(titles(resolve("twoaccounts", ACCOUNT_A))).containsExactly("a");
        assertThat(titles(resolve("twoaccounts", ACCOUNT_B))).containsExactly("b");
    }

    /**
     * Every embedded write threw {@code IllegalArgumentException} while writes went through the row-owner check with
     * nothing to declare. Insert, update and delete are asserted together because they failed together.
     */
    @Test
    void testAnEmbeddedWriteSucceedsAndLandsInTheOwnersPhysicalTable() {
        createTable("writable", ACCOUNT_A);

        DataTableRef dataTableRef = resolve("writable", ACCOUNT_A);

        DataTableRow inserted = dataTableRowService.insertRow(dataTableRef, Map.of("title", "first"));

        assertThat(rowCount("edt_0_4201_connecteduser_writable")).isEqualTo(1);

        DataTableRow updated = dataTableRowService.updateRow(
            dataTableRef, inserted.id(), Map.of("title", "second"));

        assertThat(updated.values()).containsEntry("title", "second");

        assertThat(dataTableRowService.deleteRow(dataTableRef, inserted.id())).isTrue();
        assertThat(rowCount("edt_0_4201_connecteduser_writable")).isZero();
    }

    /**
     * An account with no table of its own falls back to the vendor's, and must read the vendor's rows through the
     * vendor's physical table rather than an empty one of its own.
     */
    @Test
    void testAnAccountWithNoOwnTableFallsBackToTheSharedPhysicalTable() {
        createTable("fallback", null);

        dataTableRowService.insertRow(resolve("fallback", null), Map.of("title", "vendor"));

        DataTableRef accountDataTableRef = resolve("fallback", ACCOUNT_A);

        assertThat(accountDataTableRef.physicalName()).isEqualTo("edt_0_fallback");
        assertThat(titles(accountDataTableRef)).containsExactly("vendor");
    }

    /**
     * Read-half is pinned above; this is the write half. An account with no table of its own resolves to the shared
     * table exactly as a read does, and writes into it freely -- the rows it creates there are its own, and it is those
     * it goes on to update and delete. What it may not touch is the vendor's unowned rows, which
     * {@code DataTableRowOwnerScopingIntTest} pins.
     */
    @Test
    void testAnAccountWithNoOwnTableCanWriteTheSharedPhysicalTable() {
        createTable("writablefallback", null);

        DataTableRef accountDataTableRef = resolve("writablefallback", ACCOUNT_A);

        DataTableRow inserted = dataTableRowService.insertRow(accountDataTableRef, Map.of("title", "first"));

        assertThat(rowCount("edt_0_writablefallback")).isEqualTo(1);

        DataTableRow updated = dataTableRowService.updateRow(
            accountDataTableRef, inserted.id(), Map.of("title", "second"));

        assertThat(updated.values()).containsEntry("title", "second");

        assertThat(dataTableRowService.deleteRow(accountDataTableRef, inserted.id())).isTrue();
        assertThat(rowCount("edt_0_writablefallback")).isZero();
    }

    /**
     * Before this design, {@code checkWritableOwner} threw when an EMBEDDED write carried neither an owner nor an
     * explicit {@code shared} marker -- the only way to write the shared table was to say so out loud. That predicate
     * and the marker are both gone: an owner that fails to resolve (no {@code OwnerResolver} bean, no job principal, a
     * job principal {@code resolveJobPrincipal} cannot map to a connected user) is {@code
     * Optional.empty()}, which is indistinguishable at this layer from a genuine vendor run, and both now resolve and
     * write the shared table silently rather than being refused.
     */
    @Test
    void testAnUnresolvedOwnerWriteSucceedsAgainstTheSharedTableInsteadOfThrowing() {
        createTable("unresolvedowner", null);

        DataTableRef sharedDataTableRef = resolve("unresolvedowner", null);

        DataTableRow inserted = dataTableRowService.insertRow(sharedDataTableRef, Map.of("title", "vendor"));

        assertThat(rowCount("edt_0_unresolvedowner")).isEqualTo(1);

        DataTableRow updated = dataTableRowService.updateRow(
            sharedDataTableRef, inserted.id(), Map.of("title", "vendor-updated"));

        assertThat(updated.values()).containsEntry("title", "vendor-updated");

        assertThat(dataTableRowService.deleteRow(sharedDataTableRef, inserted.id())).isTrue();
        assertThat(rowCount("edt_0_unresolvedowner")).isZero();
    }

    /**
     * The override the design exists for: the vendor ships one workflow naming {@code override}, and giving one account
     * its own table redirects that account's runs without touching the workflow or the other accounts.
     */
    @Test
    void testAnAccountsOwnTableOverridesTheSharedOneWithoutDisturbingIt() {
        createTable("override", null);
        createTable("override", ACCOUNT_A);

        dataTableRowService.insertRow(resolve("override", null), Map.of("title", "vendor"));
        dataTableRowService.insertRow(resolve("override", ACCOUNT_A), Map.of("title", "mine"));

        assertThat(titles(resolve("override", ACCOUNT_A))).containsExactly("mine");
        assertThat(titles(resolve("override", ACCOUNT_B)))
            .as("an account with no override still reads the vendor's table")
            .containsExactly("vendor");
        assertThat(titles(resolve("override", null))).containsExactly("vendor");
    }

    /**
     * {@code assignOwner} moves the physical table to the owned name rather than leaving the registry row and the table
     * disagreeing. {@code data_table} has no environment column, so one row maps to a table per environment and the
     * rename fans out over however many of them exist -- here, one.
     */
    @Test
    void testAssigningAnOwnerRenamesThePhysicalTableToTheOwnedForm() {
        createTable("assigned", null);

        dataTableRowService.insertRow(resolve("assigned", null), Map.of("title", "kept"));

        dataTableService.assignOwner(
            dataTableService.getIdByBaseName("assigned", PlatformType.EMBEDDED), ACCOUNT_A);

        DataTableRef dataTableRef = resolve("assigned", ACCOUNT_A);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_4201_connecteduser_assigned");
        assertThat(physicalTableExists("edt_0_assigned"))
            .as("the unowned name must be released, or the next shared table of that name cannot be created")
            .isFalse();
        assertThat(titles(dataTableRef)).containsExactly("kept");
    }

    /**
     * The fan-out. A registry row is the LOGICAL table and each environment holds its own physical instance of it, so
     * an assignment has to move every instance that exists -- and only the ones that exist, which is why a table
     * created in one environment and never promoted is the same code path with a shorter list.
     */
    @Test
    void testAssigningAnOwnerRenamesEveryEnvironmentsPhysicalTable() {
        createTable("multienv", null);
        createTableIn("multienv", null, OTHER_ENVIRONMENT_ID);

        dataTableService.assignOwner(
            dataTableService.getIdByBaseName("multienv", PlatformType.EMBEDDED), ACCOUNT_A);

        assertThat(physicalTableExists("edt_0_4201_connecteduser_multienv")).isTrue();
        assertThat(physicalTableExists("edt_1_4201_connecteduser_multienv")).isTrue();
        assertThat(physicalTableExists("edt_0_multienv")).isFalse();
        assertThat(physicalTableExists("edt_1_multienv")).isFalse();
    }

    /**
     * The two axes meet here, and the meeting is where an assignment can leak.
     *
     * <p>
     * A shared table is exactly where several accounts' rows collect -- that is what the row axis is for. Assigning it
     * to one of them re-stamps every row onto the new owner, so without this refusal account B's rows would become
     * account A's, readable and writable by A, under a console action labelled only "assign owner".
     *
     * <p>
     * Asserts the rows are still B's afterwards, not merely that a throwable arrived. A guard that threw after
     * re-stamping, or after renaming the table, would satisfy the first assertion and still have moved the data.
     */
    @Test
    void testAssigningASharedTableRefusesWhenItHoldsAnotherAccountsRows() {
        createTable("mixed", null);

        dataTableRowService.insertRow(resolve("mixed", ACCOUNT_B), Map.of("title", "account b row"));

        assertThatThrownBy(() -> dataTableService.assignOwner(idOf("mixed", null), ACCOUNT_A))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("another account");

        assertThat(physicalTableExists("edt_0_4201_connecteduser_mixed")).isFalse();
        assertThat(titles(resolve("mixed", ACCOUNT_B))).containsExactly("account b row");
        assertThat(titles(resolve("mixed", ACCOUNT_A))).isEmpty();
    }

    /**
     * The refusal is about OTHER accounts, not about owned rows as such: a table whose rows are already the target's,
     * or unowned, is assignable. Without this the guard could be satisfied by refusing every assignment.
     */
    @Test
    void testAssigningASharedTableStillWorksWhenItsRowsAreUnownedOrAlreadyTheTargets() {
        createTable("tidy", null);

        dataTableRowService.insertRow(resolve("tidy", null), Map.of("title", "vendor row"));
        dataTableRowService.insertRow(resolve("tidy", ACCOUNT_A), Map.of("title", "account a row"));

        dataTableService.assignOwner(idOf("tidy", null), ACCOUNT_A);

        assertThat(titles(resolve("tidy", ACCOUNT_A))).containsExactlyInAnyOrder("vendor row", "account a row");
    }

    /**
     * Returning a table to the vendor is the exact inverse, and has to be: it is the repair path for a row that
     * acquired an owner some other way, and a repair that left the table at the owned name would fix nothing.
     */
    @Test
    void testUnassigningAnOwnerRenamesThePhysicalTableBack() {
        createTable("unassigned", ACCOUNT_A);

        dataTableRowService.insertRow(resolve("unassigned", ACCOUNT_A), Map.of("title", "kept"));

        dataTableService.assignOwner(idOf("unassigned", ACCOUNT_A), null);

        DataTableRef dataTableRef = resolve("unassigned", null);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_unassigned");
        assertThat(physicalTableExists("edt_0_4201_connecteduser_unassigned")).isFalse();
        assertThat(titles(dataTableRef)).containsExactly("kept");
    }

    /**
     * The reason the rename exists. While an assigned table kept its unowned physical name, the row it belonged to was
     * recoverable only for as long as it was the sole claimant of that name -- the moment a second account owned an
     * {@code exclusive} too, the bare table had two candidate owners, recovery failed closed, and the first account's
     * table answered every read with a raw "relation does not exist".
     */
    @Test
    void testAnAssignedTableStaysReachableOnceASecondAccountHoldsTheSameName() {
        createTable("exclusive", null);

        dataTableRowService.insertRow(resolve("exclusive", null), Map.of("title", "kept"));

        dataTableService.assignOwner(
            dataTableService.getIdByBaseName("exclusive", PlatformType.EMBEDDED), ACCOUNT_A);

        createTable("exclusive", ACCOUNT_B);

        assertThat(titles(resolve("exclusive", ACCOUNT_A))).containsExactly("kept");
        assertThat(titles(resolve("exclusive", ACCOUNT_B))).isEmpty();
    }

    /**
     * An assignment that would collide is refused whole. Renaming only the environments that happen to be free would
     * leave one registry row spread over two physical names, which is the state this whole change exists to remove.
     */
    @Test
    void testAssigningAnOwnerThatAlreadyHoldsThePhysicalNameIsRefused() {
        createTable("collide", null);
        createTable("collide", ACCOUNT_A);

        long dataTableId = dataTableService.getIdByBaseName("collide", PlatformType.EMBEDDED);

        assertThatThrownBy(() -> dataTableService.assignOwner(dataTableId, ACCOUNT_A))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(physicalTableExists("edt_0_collide"))
            .as("a refused assignment must leave both tables exactly where they were")
            .isTrue();
        assertThat(physicalTableExists("edt_0_4201_connecteduser_collide")).isTrue();
    }

    /**
     * {@code DataTableRef.shared} is public, and is a CLAIM that a table has no owner. Recovering the row behind a bare
     * physical name used to fall back to "the only candidate" when no shared row existed, so a shared ref for a name
     * exactly one account held resolved to that account's registry row -- an unowned ref answered with an owned table.
     * The claim is now taken at its word: no shared row, no answer.
     */
    @Test
    void testASharedRefNeverResolvesToAnOwnedRegistryRow() {
        createTable("sharedref", ACCOUNT_A);

        assertThat(
            dataTableService.fetchDataTable(
                DataTableRef.shared("sharedref", ENVIRONMENT_ID, PlatformType.EMBEDDED)))
                    .isEmpty();
    }

    /**
     * The interaction between the two axes, and the reason there is no special case anywhere.
     *
     * <p>
     * In a table an account already owns, the row predicate is satisfied by construction: an insert stamps the same
     * owner the physical name carries, so the read finds the row it just wrote and the narrower write predicate finds
     * it too. A design where the predicate applied to shared tables only would need every row operation to know which
     * kind of table it was holding; this is what says it does not.
     */
    @Test
    void testRowsInAnOwnedTableAreStampedWithThatTablesOwner() {
        createTable("ownedstamp", ACCOUNT_A);

        DataTableRef dataTableRef = resolve("ownedstamp", ACCOUNT_A);

        DataTableRow inserted = dataTableRowService.insertRow(dataTableRef, Map.of("title", "mine"));

        assertThat(ownerIdOf("edt_0_4201_connecteduser_ownedstamp", inserted.id()))
            .as("an insert into an owned table stamps the owner the table name already carries")
            .isEqualTo(ACCOUNT_A.id());

        OwnerType ownerType = ACCOUNT_A.type();

        assertThat(ownerTypeOf("edt_0_4201_connecteduser_ownedstamp", inserted.id()))
            .as("both owner columns move together; an id beside a null type belongs to nobody")
            .isEqualTo(ownerType.ordinal());

        assertThat(titles(dataTableRef))
            .as("and the read predicate is satisfied by construction, so the row comes straight back")
            .containsExactly("mine");
    }

    /**
     * An assignment is a statement about both axes at once: the table becomes this account's, and so does everything in
     * it. Left unstamped, the new owner could read the rows it inherited -- the read predicate admits unowned ones --
     * and could not update or delete a single one, because the write predicate matches on the owner alone.
     */
    @Test
    void testAssigningAnOwnerMovesTheExistingRowsOntoThatOwner() {
        createTable("restamped", null);

        DataTableRow inserted = dataTableRowService.insertRow(resolve("restamped", null), Map.of("title", "kept"));

        dataTableService.assignOwner(
            dataTableService.getIdByBaseName("restamped", PlatformType.EMBEDDED), ACCOUNT_A);

        assertThat(ownerIdOf("edt_0_4201_connecteduser_restamped", inserted.id())).isEqualTo(ACCOUNT_A.id());

        DataTableRef dataTableRef = resolve("restamped", ACCOUNT_A);

        assertThat(
            dataTableRowService.updateRow(dataTableRef, inserted.id(), Map.of("title", "changed"))
                .values())
                    .as("an inherited row must be writable by the account that inherited it")
                    .containsEntry("title", "changed");
    }

    /**
     * The inverse, and the reason it is not optional: a table handed back to the vendor whose rows kept the old owner's
     * stamp would come back empty, because a run with no owner reads unowned rows alone.
     */
    @Test
    void testUnassigningAnOwnerReturnsTheRowsToNobody() {
        createTable("unrestamped", ACCOUNT_A);

        DataTableRow inserted = dataTableRowService.insertRow(
            resolve("unrestamped", ACCOUNT_A), Map.of("title", "kept"));

        dataTableService.assignOwner(idOf("unrestamped", ACCOUNT_A), null);

        assertThat(ownerIdOf("edt_0_unrestamped", inserted.id())).isNull();
        assertThat(ownerTypeOf("edt_0_unrestamped", inserted.id())).isNull();

        assertThat(titles(resolve("unrestamped", null)))
            .as("the vendor must get its rows back, not an empty table")
            .containsExactly("kept");
    }

    /**
     * A duplicate copies the rows AND their ownership. Dropping the owner columns from the copy would leave every
     * copied row unowned: readable by the account the copy belongs to, untouchable by its writes.
     */
    @Test
    void testADuplicateCopiesRowOwnershipAlongWithTheRows() {
        createTable("dupsource", ACCOUNT_A);

        dataTableRowService.insertRow(resolve("dupsource", ACCOUNT_A), Map.of("title", "copied"));

        dataTableService.duplicateTable(
            "dupsource", "dupcopy", ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.of(ACCOUNT_A));

        DataTableRef copyDataTableRef = resolve("dupcopy", ACCOUNT_A);

        List<DataTableRow> copiedDataTableRows = dataTableRowService.listRows(copyDataTableRef, 100, 0);

        assertThat(copiedDataTableRows).hasSize(1);

        DataTableRow copiedDataTableRow = copiedDataTableRows.getFirst();

        assertThat(ownerIdOf("edt_0_4201_connecteduser_dupcopy", copiedDataTableRow.id()))
            .as("a copy inherits the source row's owner, not a null one")
            .isEqualTo(ACCOUNT_A.id());

        assertThat(
            dataTableRowService.updateRow(copyDataTableRef, copiedDataTableRow.id(), Map.of("title", "changed"))
                .values())
                    .as("a copied row must be writable by the account whose copy it is")
                    .containsEntry("title", "changed");
    }

    private void createTable(String baseName, Owner owner) {
        createTableIn(baseName, owner, ENVIRONMENT_ID);
    }

    private void createTableIn(String baseName, Owner owner, long environmentId) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), environmentId,
            PlatformType.EMBEDDED, Optional.ofNullable(owner));
    }

    /**
     * {@code getIdByBaseName} resolves with no owner and therefore only ever finds the shared row, which is exactly
     * wrong for a table that already belongs to an account.
     */
    private long idOf(String baseName, Owner owner) {
        DataTable dataTable = dataTableService
            .fetchDataTable(baseName, PlatformType.EMBEDDED, Optional.ofNullable(owner))
            .orElseThrow();

        return dataTable.getId();
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

    private boolean physicalTableExists(String physicalName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                + "AND table_name = ?",
            Integer.class, physicalName);

        return count != null && count > 0;
    }

    private int rowCount(String physicalName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + physicalName, Integer.class);

        return count == null ? 0 : count;
    }

    private Long ownerIdOf(String physicalName, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"owner_id\" FROM \"" + physicalName + "\" WHERE \"id\" = ?", Long.class, id);
    }

    private Integer ownerTypeOf(String physicalName, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"owner_type\" FROM \"" + physicalName + "\" WHERE \"id\" = ?", Integer.class, id);
    }
}
