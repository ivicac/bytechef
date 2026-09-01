# Remove Row-Level Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make resource-level ownership the only ownership level for data tables, deleting row-level ownership entirely.

**Architecture:** A data table is owned by one connected user or by nobody (shared). Ownership is settled once, at resolution — a run either resolves the table or does not, and every row inside a resolved table belongs to it. The owner moves into the physical table name and the registry key; every per-row owner column, filter and check is deleted.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, PostgreSQL, JUnit 5, Mockito, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-31-resource-level-ownership-only-design.md`

## Global Constraints

- Resolution rule: **the CU's own resource wins, shared is the fallback.** A run for CU-42 naming `orders` resolves CU-42's `orders` if it exists, otherwise the shared `orders`, otherwise fails. A vendor run (no owner) resolves only shared resources unless it names an account.
- A run that already belongs to a CU may **not** name a different one. This is the existing rule in `DataTableUtils.effectiveOwner`; keep it verbatim.
- Physical naming: shared stays `<pool>_<envId>_<baseName>`; owned becomes `<pool>_<envId>_<ownerId>_<baseName>`. `<pool>` is `dt` for AUTOMATION and `edt` for EMBEDDED.
- Base names match `[a-z_][a-z0-9_]*` and therefore **cannot start with a digit**. This is the sole reason the owned and shared physical forms cannot collide — treat it as a load-bearing invariant, not a coincidence.
- Registry keys gain the owner: `data_table` becomes `(name, platform_type, owner_id)`. NULL owner needs a **partial** unique index, because Postgres treats every NULL as distinct and a non-partial index including `owner_id` never fires for shared rows.
- Orphaned `owner_id`/`owner_type` columns on existing dynamic tables are **left in place**. Do not write a migration to drop them — every worktree on this machine shares one Postgres and a sibling worktree may still be running the old code.
- The pool split is unaffected. `PlatformType` scoping stays everywhere it currently is; only ownership changes.
- EE files (`server/ee/`) take the ByteChef Enterprise license header and a `@version ee` javadoc tag. CE files take Apache 2.0.
- Java style: blank line before control statements; blank line after a variable modification that precedes its use; no trailing blank line before a class's closing brace; no `_` prefix on private methods; descriptive names.
- No `TODO:` comments — Checkstyle's `TodoComment` rule forbids them.
- Test naming: `Test` for unit, `IntTest` for integration. camelCase method names with no underscores, including private helpers.
- Run `./gradlew spotlessApply` before every commit.
- Never judge a Gradle run piped into `tail`/`grep`. Redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- Testcontainers needs `DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Commit messages: `732 <description>`. Fresh commits only, never amend. Stage only files you changed.
- Do not `git stash` — the stash stack is shared across all worktrees on this machine.

---

## File Structure

**Deleted outright:**
- `platform-data-table-api/.../domain/RowOwnerFilter.java`
- `platform-data-table-service/.../configuration/migration/DataTableOwnerColumnMigrator.java`
- `platform-data-table-service/src/test/.../domain/RowOwnerFilterTest.java`
- `data-table/src/test/.../util/DataTableUtilsSharedWriteTest.java`
- `data-table/src/test/.../util/DataTableUtilsTriggerOutputTest.java`
- `platform-data-table-service/src/test/.../execution/service/DataTableRowOwnerScopingIntTest.java`
- `.../changelog/platform/data-table/20260830000001_platform_data_table_webhook_add_owner.xml`

**Owner moves into naming and resolution:**
- `platform-data-table-service/.../internal/PhysicalTableNaming.java` — owner in the name
- `platform-data-table-service/.../configuration/repository/DataTableRepository.java` — owner in the finders
- `platform-data-table-api/.../configuration/service/DataTableService.java` + Impl — owned-wins resolution

**Owner leaves the row layer:**
- `platform-data-table-api/.../execution/service/DataTableRowService.java` + Impl — drop the 9 owner-scoped overloads
- `platform-data-table-service/.../execution/service/RowQuerySqlBuilder.java` — drop owner predicates

**Component surface:**
- `data-table/.../util/DataTableUtils.java` — drop `selectionFilter`, `poolFor(RowOwnerFilter)`, `sharedRecordProperty`; rewrite `accountProperty` description; keep `effectiveOwner`
- 6 actions, 3 triggers

**Webhooks collapse:**
- `platform-data-table-api/.../configuration/domain/DataTableWebhook.java`, `.../service/DataTableWebhookService.java`, `.../execution/event/DataTableWebhookEvent.java`, `.../execution/listener/DataTableWebhookEventListener.java`

**EE alignment:**
- `platform-data-table-remote-client/.../RemoteDataTableRowServiceClient.java`, `RemoteDataTableServiceClient.java`

---

## Task 1: Owner in the physical name and the registry key

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/internal/PhysicalTableNaming.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/repository/DataTableRepository.java`
- Create: `server/libs/config/liquibase-config/src/main/resources/config/liquibase/changelog/platform/data-table/20260831000001_platform_data_table_owner_unique_index.xml`
- Test: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/internal/PhysicalTableNamingTest.java`

**Interfaces:**
- Produces: `PhysicalTableNaming.buildPhysicalName(PlatformType platformType, long environmentId, @Nullable Long ownerId, String baseName)` and `PhysicalTableNaming.prefix(PlatformType platformType, long environmentId, @Nullable Long ownerId)`. `DataTableRepository.findByNameAndPlatformTypeAndOwnerId(String name, int platformType, Long ownerId)` and `findByNameAndPlatformTypeAndOwnerIdIsNull(String name, int platformType)`.

- [ ] **Step 1: Write the failing test**

Create `PhysicalTableNamingTest.java`:

```java
class PhysicalTableNamingTest {

    @Test
    void testASharedTableKeepsTheUnownedName() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 0L, null, "orders"))
            .isEqualTo("edt_0_orders");
    }

    @Test
    void testAnOwnedTableCarriesItsOwnerInTheName() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 0L, 5L, "orders"))
            .isEqualTo("edt_0_5_orders");
    }

    // The owned and shared forms are only distinguishable because a base name cannot start with a digit
    // ([a-z_][a-z0-9_]*), so "5_orders" is not a legal base name and cannot masquerade as owner 5's "orders".
    // If that validation ever loosens, these two forms collide silently.
    @Test
    void testTheOwnedFormCannotBeSpelledAsASharedBaseName() {
        assertThatThrownBy(() -> DataTableServiceImpl.validateBaseName("5_orders"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testAutomationKeepsItsOwnPoolToken() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.AUTOMATION, 1L, null, "orders"))
            .isEqualTo("dt_1_orders");
    }
}
```

If `validateBaseName` is private, make it package-private for the third test rather than duplicating the regex — the point of that test is that the two rules stay coupled.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test --tests '*PhysicalTableNamingTest' > /tmp/t.log 2>&1
```
Then `echo $?` on its own line and grep `/tmp/t.log`. Expected: compile failure — `buildPhysicalName` has no 4-argument form.

- [ ] **Step 3: Add the owner to the naming**

```java
public static String buildPhysicalName(
    PlatformType platformType, long environmentId, @Nullable Long ownerId, String baseName) {

    return prefix(platformType, environmentId, ownerId) + baseName.toLowerCase(Locale.ROOT);
}

/**
 * The owner sits between the environment and the base name. A base name cannot start with a digit
 * ({@code [a-z_][a-z0-9_]*}), so an owned name can never be spelled as a shared one -- that validation is what
 * keeps the two forms apart, and loosening it would make them collide.
 */
public static String prefix(PlatformType platformType, long environmentId, @Nullable Long ownerId) {
    String environmentPrefix = poolToken(platformType) + "_" + environmentId + "_";

    return ownerId == null ? environmentPrefix : environmentPrefix + ownerId + "_";
}
```

Delete the old 3-argument `buildPhysicalName` and `prefix` so no caller can keep the unowned assumption by accident. The compiler then enumerates every call site — work through them, passing the owner each carries.

- [ ] **Step 4: Add the owner-aware repository finders**

```java
Optional<DataTable> findByNameAndPlatformTypeAndOwnerId(String name, int platformType, Long ownerId);

Optional<DataTable> findByNameAndPlatformTypeAndOwnerIdIsNull(String name, int platformType);
```

Keep `findByNameAndPlatformType` for now; Task 2 removes its last caller.

- [ ] **Step 5: Add the unique index changeset**

New file, registered in the data-table changelog's `includeAll` directory. Two indexes, because a single non-partial one never fires for shared rows:

```xml
<changeSet id="20260831000001-1" author="Ivica Cardic">
    <createIndex indexName="uk_data_table_name_platform_type_owner_id" tableName="data_table" unique="true">
        <column name="name"/>
        <column name="platform_type"/>
        <column name="owner_id"/>
    </createIndex>
</changeSet>

<changeSet id="20260831000001-2" author="Ivica Cardic">
    <sql>
        CREATE UNIQUE INDEX uk_data_table_name_platform_type_shared
        ON data_table (name, platform_type) WHERE owner_id IS NULL
    </sql>
    <rollback>
        DROP INDEX IF EXISTS uk_data_table_name_platform_type_shared
    </rollback>
</changeSet>
```

Drop the old `(name, platform_type)` unique constraint in the same file — two CUs may now each own an `orders`.

- [ ] **Step 6: Run tests to verify they pass**

Run the command from Step 2, plus the module's `testIntegration` so the changeset is exercised against a fresh Testcontainers schema.

- [ ] **Step 7: Commit**

```bash
git add server/libs/platform/platform-data-table server/libs/config/liquibase-config
git commit -m "732 Put the owner in the data table's physical name and registry key"
```

---

## Task 2: Owned-wins-shared-fallback resolution

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableService.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableServiceImpl.java`
- Test: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableOwnerResolutionTest.java`

**Interfaces:**
- Consumes: the repository finders from Task 1.
- Produces: `Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType, Optional<Owner> owner)` on `DataTableService` — the single resolution entry point. Returns the owner's table if one exists, else the shared one, else empty.

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MockitoExtension.class)
class DataTableOwnerResolutionTest {

    private static final long ACCOUNT_ID = 42L;

    @Mock
    private DataTableRepository dataTableRepository;

    @InjectMocks
    private DataTableServiceImpl dataTableService;

    @Test
    void testTheAccountsOwnTableWinsOverTheSharedOne() {
        DataTable owned = tableNamed("orders", ACCOUNT_ID);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerId("orders", EMBEDDED.ordinal(), ACCOUNT_ID))
            .thenReturn(Optional.of(owned));

        Optional<DataTable> resolved = dataTableService.fetchDataTable(
            "orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        assertThat(resolved).contains(owned);

        // The shared lookup must not even be attempted -- falling through would make the override advisory.
        verify(dataTableRepository, never())
            .findByNameAndPlatformTypeAndOwnerIdIsNull(anyString(), anyInt());
    }

    @Test
    void testAnAccountWithNoOwnTableFallsBackToTheSharedOne() {
        DataTable shared = tableNamed("orders", null);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerId("orders", EMBEDDED.ordinal(), ACCOUNT_ID))
            .thenReturn(Optional.empty());
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.of(shared));

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .contains(shared);
    }

    @Test
    void testAVendorRunReadsOnlyTheSharedTable() {
        DataTable shared = tableNamed("orders", null);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.of(shared));

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.empty())).contains(shared);

        // A vendor run must never resolve an account's table implicitly; it names the account or sees shared.
        verify(dataTableRepository, never())
            .findByNameAndPlatformTypeAndOwnerId(anyString(), anyInt(), anyLong());
    }

    @Test
    void testNeitherTableResolvesToEmpty() {
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerId("orders", EMBEDDED.ordinal(), ACCOUNT_ID))
            .thenReturn(Optional.empty());
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.empty());

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .isEmpty();
    }

    private static DataTable tableNamed(String name, @Nullable Long ownerId) {
        DataTable dataTable = new DataTable();

        dataTable.setName(name);
        dataTable.setOwnerId(ownerId);

        return dataTable;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: compile failure — `fetchDataTable` does not exist.

- [ ] **Step 3: Implement the resolution**

```java
/**
 * The table a run resolves for a base name: the caller's own if they have one, the shared one otherwise.
 *
 * <p>
 * Deliberately the opposite of the cross-pool rule, where two matches mean a bug and resolution fails closed. Within
 * a pool two matches are the feature -- the vendor ships one workflow naming {@code orders}, and giving a customer
 * their own {@code orders} is a drop-in override needing no workflow edit. Failing on ambiguity here would make
 * per-account tables unusable.
 *
 * <p>
 * A run with no owner resolves only shared tables. Reaching an account's table from a vendor run is deliberate, via
 * the account selector on the action, which supplies the owner this method then resolves for.
 */
@Override
@Transactional(readOnly = true)
public Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType, Optional<Owner> owner) {
    if (owner.isPresent()) {
        Owner curOwner = owner.get();

        Optional<DataTable> ownedDataTable = dataTableRepository.findByNameAndPlatformTypeAndOwnerId(
            baseName, platformType.ordinal(), curOwner.id());

        if (ownedDataTable.isPresent()) {
            return ownedDataTable;
        }
    }

    return dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(baseName, platformType.ordinal());
}
```

- [ ] **Step 4: Route every by-name lookup through it**

`DataTableServiceImpl` currently calls `findByNameAndPlatformType` at lines 162, 226, 242, 278, 409 and 453. Each becomes a `fetchDataTable` call with the caller's owner. Delete `findByNameAndPlatformType` from the repository once the last caller is gone, so the unowned assumption cannot come back.

- [ ] **Step 5: Run tests to verify they pass**

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-data-table
git commit -m "732 Resolve a data table to the account's own, falling back to the shared one"
```

---

## Task 3: Delete row-level ownership from the row service

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/execution/service/DataTableRowService.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/execution/service/DataTableRowServiceImpl.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/execution/service/RowQuerySqlBuilder.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableServiceImpl.java` (DDL at lines 127 and 204)
- Delete: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/migration/DataTableOwnerColumnMigrator.java`
- Delete: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/execution/service/DataTableRowOwnerScopingIntTest.java`
- Test: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableDdlHasNoOwnerColumnsTest.java`

**Interfaces:**
- Produces: `DataTableRowService` with a single form of each method — the owner-scoped overloads are gone. Every method keeps `(String baseName, ..., long environmentId, PlatformType platformType)`.

- [ ] **Step 1: Write the failing test**

The DDL must stop creating owner columns. Assert on the generated SQL rather than on a live table, so the test does not need Postgres:

```java
class DataTableDdlHasNoOwnerColumnsTest {

    // Every row in a table now belongs to that table's owner, so a per-row owner column is not merely unused --
    // it is a second, contradictory place to record the same fact.
    @Test
    void testTheCreateStatementDeclaresNoOwnerColumns() {
        String createTableSql = DataTableServiceImpl.buildCreateTableSql("edt_0_5_orders", List.of());

        assertThat(createTableSql).doesNotContain("owner_id");
        assertThat(createTableSql).doesNotContain("owner_type");
    }

    @Test
    void testTheCreateStatementStillDeclaresThePrimaryKey() {
        assertThat(DataTableServiceImpl.buildCreateTableSql("edt_0_5_orders", List.of()))
            .contains("\"id\" BIGSERIAL PRIMARY KEY");
    }
}
```

Extract the inline DDL string at `DataTableServiceImpl:127` into a package-private static `buildCreateTableSql(String physicalName, List<ColumnSpec> columnSpecs)` so it is assertable. The duplicate path at line 204 must call the same helper — the two strings are currently near-copies and drifting them is exactly the bug this prevents.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — the generated SQL contains `owner_id`.

- [ ] **Step 3: Strip the owner from the DDL**

Remove `"owner_id" BIGINT, "owner_type" INT` from the create statement and delete the accompanying `CREATE INDEX ... ("owner_type", "owner_id")`. Do this in the extracted helper so both paths change together.

- [ ] **Step 4: Delete the owner-scoped service overloads**

In `DataTableRowService`, delete the nine `RowOwnerFilter`-bearing overloads (lines 60, 77, 95, 114, 122, 130, 146, 163, 183) and keep the plain forms. In `DataTableRowServiceImpl`, delete `checkWritableOwner` and the owner-column handling around line 395. In `RowQuerySqlBuilder`, delete `readableOwnerPredicate`, `writableOwnerPredicate` and their callers.

Leave `ReservedColumns.OWNER_ID`/`OWNER_TYPE` in place — orphaned columns still exist on dev databases and `RowQuerySqlBuilder` must keep refusing to let a workflow address them.

- [ ] **Step 5: Delete the migrator**

`DataTableOwnerColumnMigrator` adds the columns to existing tables. Delete the class and its Spring registration. Do **not** add a migration that drops the columns.

- [ ] **Step 6: Run tests to verify they pass**

Run `test` and `testIntegration` for the module. `DataTableRowOwnerScopingIntTest` tests the deleted behaviour — delete it rather than adapting it.

- [ ] **Step 7: Commit**

```bash
git add server/libs/platform/platform-data-table
git commit -m "732 Stop recording an owner on every data table row"
```

---

## Task 4: Delete RowOwnerFilter and simplify the component surface

**Files:**
- Delete: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/domain/RowOwnerFilter.java`
- Delete: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/domain/RowOwnerFilterTest.java`
- Delete: `server/libs/modules/components/data-table/src/test/java/com/bytechef/component/datatable/util/DataTableUtilsSharedWriteTest.java`
- Delete: `server/libs/modules/components/data-table/src/test/java/com/bytechef/component/datatable/util/DataTableUtilsTriggerOutputTest.java`
- Modify: `server/libs/modules/components/data-table/src/main/java/com/bytechef/component/datatable/util/DataTableUtils.java`
- Modify: the 6 actions and 3 triggers under `server/libs/modules/components/data-table/src/main/java/com/bytechef/component/datatable/`
- Modify: `server/libs/modules/components/data-table/src/test/java/com/bytechef/component/datatable/DataTableComponentUsesScopedRowServiceTest.java`

**Interfaces:**
- Consumes: `DataTableService.fetchDataTable(String, PlatformType, Optional<Owner>)` from Task 2.
- Produces: `DataTableUtils.accountProperty()` (description rewritten), `DataTableUtils.effectiveOwner(Optional<Owner>, Long)` (unchanged), `DataTableUtils.resolveDataTable(DataTableService, String, long, Optional<Owner>)` returning `ResolvedDataTable`.

- [ ] **Step 1: Write the failing test**

Rewrite `DataTableComponentUsesScopedRowServiceTest` to assert the new contract — that no action reaches the row service with anything owner-shaped, because the concept is gone:

```java
class DataTableComponentUsesScopedRowServiceTest {

    private static final String ACTION_ROOT =
        "src/main/java/com/bytechef/component/datatable/action";
    private static final String TRIGGER_ROOT =
        "src/main/java/com/bytechef/component/datatable/trigger";

    // RowOwnerFilter is deleted. Its name surviving anywhere in the component means a merge resurrected a call
    // that no longer compiles against the row service, or that someone reintroduced per-row scoping by hand.
    @Test
    void testNoActionOrTriggerMentionsARowOwnerFilter() {
        assertThat(sourcesMentioning("RowOwnerFilter")).isEmpty();
    }

    // Every step must still resolve its table through the pool-and-owner-aware helper; dropping the call would
    // leave the step reading whatever table the base name happened to hit.
    @Test
    void testEveryActionResolvesItsTableThroughTheHelper() {
        assertThat(sourcesNotMentioning(ACTION_ROOT, "DataTableUtils.resolveDataTable(")).isEmpty();
    }
}
```

Write `sourcesMentioning`/`sourcesNotMentioning` as private helpers walking both roots, in the style of `KnowledgeBaseComponentScopesByIdReadsTest`.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — actions and triggers still mention `RowOwnerFilter`.

- [ ] **Step 3: Simplify `DataTableUtils`**

Delete `selectionFilter`, `poolFor(RowOwnerFilter)`, and `sharedRecordProperty`. Keep `poolFor(Optional<Owner>)` and `effectiveOwner` unchanged. Change `resolveDataTable` to call `dataTableService.fetchDataTable(baseName, platformType, owner)` per pool.

Rewrite the account property description — the current wording describes row filtering and is now wrong:

```java
public static ModifiableIntegerProperty accountProperty() {
    return integer(ACCOUNT_ID)
        .label("Account")
        .description(
            "The connected user whose table this step acts on. Leave empty to act on the shared table. A step " +
                "running for an account already uses that account's table, and ignores this.")
        .required(false);
}
```

- [ ] **Step 4: Update the actions and triggers**

Each of the six actions and three triggers currently builds a `RowOwnerFilter` and passes it to the row service. Delete the filter; resolve the table with `effectiveOwner(resolvedOwner, namedAccountId)` and call the plain row-service method. Remove the `SHARED` property from `DataTableCreateRecordsAction` and `DataTableUpdateRecordAction`, and delete `DataTableConstants.SHARED`.

- [ ] **Step 5: Run tests to verify they pass**

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-data-table server/libs/modules/components/data-table
git commit -m "732 Delete RowOwnerFilter and the per-row account selector semantics"
```

---

## Task 5: Collapse webhook ownership onto the table

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/domain/DataTableWebhook.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableWebhookService.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/execution/event/DataTableWebhookEvent.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/execution/listener/DataTableWebhookEventListener.java`
- Delete: `server/libs/config/liquibase-config/src/main/resources/config/liquibase/changelog/platform/data-table/20260830000001_platform_data_table_webhook_add_owner.xml`
- Delete: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/domain/DataTableWebhookOwnerTest.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/test/java/com/bytechef/platform/data/table/execution/listener/DataTableWebhookEventListenerTest.java`

**Interfaces:**
- Produces: `DataTableWebhookService.Webhook(long id, long dataTableId, String url, DataTableWebhookType type, long environmentId)` — the `owner` component is removed.

- [ ] **Step 1: Write the failing test**

A webhook now hangs off a table and the table has one owner, so cross-account delivery is impossible by construction. What still needs pinning is that a webhook never receives an event from the other pool:

```java
@Test
void testAWebhookNeverReceivesAnEventFromTheOtherPool() {
    when(dataTableWebhookService.listWebhooks("orders", 0L, PlatformType.EMBEDDED))
        .thenReturn(List.of(webhook(1L, "https://embedded.example/hook")));

    dataTableWebhookEventListener.onDataTableWebhookEvent(
        new DataTableWebhookEvent("orders", RECORD_CREATED, Map.of("id", 1), 0L, PlatformType.AUTOMATION));

    // The automation event must resolve automation's webhooks, not embedded's, even though the base name is legal
    // in both pools.
    verify(dataTableWebhookService).listWebhooks("orders", 0L, PlatformType.AUTOMATION);
    verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: compile failure — `DataTableWebhookEvent`'s constructor still takes an owner.

- [ ] **Step 3: Remove the owner from the webhook chain**

Delete `ownerId`/`ownerType` and `getOwner`/`setOwner` from `DataTableWebhook`; drop `owner` from the `Webhook` record; drop the owner parameter from `addWebhook`; delete `deliversTo` and its `.filter(...)` in the listener; drop the owner argument from the three publish sites in `DataTableRowServiceImpl`. Keep `platformType` on the event and the pool argument on `listWebhooks`.

- [ ] **Step 4: Delete the owner changeset**

The changeset adding the webhook owner columns is unreleased and was added on this branch. Delete the file. Liquibase does not object to a `databasechangelog` row whose changeset is no longer on disk, and per the spec the already-created columns are left orphaned.

- [ ] **Step 5: Run tests to verify they pass**

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-data-table server/libs/config/liquibase-config
git commit -m "732 Take the owner off the webhook now the table carries it"
```

---

## Task 6: Owner in the knowledge base registry key and resolution

**Files:**
- Create: `server/libs/config/liquibase-config/src/main/resources/config/liquibase/changelog/platform/knowledge-base/20260831000002_platform_knowledge_base_owner_unique_index.xml`
- Modify: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseOptionsUtils.java`
- Test: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseOwnerResolutionTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks — knowledge bases already carry resource-level ownership.
- Produces: `KnowledgeBaseOptionsUtils.resolveKnowledgeBase` applying owned-wins-shared-fallback, matching `DataTableService.fetchDataTable`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void testTheAccountsOwnKnowledgeBaseWinsOverTheSharedOne() {
    KnowledgeBase owned = knowledgeBaseNamed("docs", ACCOUNT_ID);
    KnowledgeBase shared = knowledgeBaseNamed("docs", null);

    when(knowledgeBaseService.getKnowledgeBases(0, EMBEDDED, Optional.of(OWNER)))
        .thenReturn(List.of(shared, owned));

    assertThat(KnowledgeBaseOptionsUtils.resolveKnowledgeBaseByName(
        knowledgeBaseService, "docs", 0, EMBEDDED, Optional.of(OWNER)))
        .isEqualTo(owned);
}

@Test
void testAnAccountWithNoOwnKnowledgeBaseFallsBackToTheSharedOne() {
    KnowledgeBase shared = knowledgeBaseNamed("docs", null);

    when(knowledgeBaseService.getKnowledgeBases(0, EMBEDDED, Optional.of(OWNER)))
        .thenReturn(List.of(shared));

    assertThat(KnowledgeBaseOptionsUtils.resolveKnowledgeBaseByName(
        knowledgeBaseService, "docs", 0, EMBEDDED, Optional.of(OWNER)))
        .isEqualTo(shared);
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Add the unique index changeset**

Same two-index shape as Task 1, on `knowledge_base (name, platform_type, environment, owner_id)` plus a partial index for `owner_id IS NULL`. This also closes issue #5591, which reported that the existing key omits `environment`.

- [ ] **Step 4: Apply owned-wins to name resolution**

- [ ] **Step 5: Run tests to verify they pass**

- [ ] **Step 6: Commit**

```bash
git add server/libs/modules/components/ai/vectorstore/knowledgebase server/libs/config/liquibase-config
git commit -m "732 Key a knowledge base on its owner and resolve the account's own first"
```

---

## Task 7: EE remote clients and embedded facades

**Files:**
- Modify: `server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableRowServiceClient.java`
- Modify: `server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableServiceClient.java`
- Modify: `server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableWebhookServiceClient.java`

**Interfaces:**
- Consumes: the trimmed `DataTableRowService`, `DataTableService` and `DataTableWebhookService` interfaces from Tasks 2, 3 and 5.

- [ ] **Step 1: Compile to enumerate the breakage**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/c.log 2>&1
```
`echo $?` on its own line, then grep `/tmp/c.log` for `^> Task .* FAILED`. The remote clients implement the interfaces just changed, so the compiler lists exactly what to fix. This is the enumeration step — there is no test to write first.

- [ ] **Step 2: Update each stub to the new signature**

These stubs throw `UnsupportedOperationException`; the work is signature alignment only. Keep the Enterprise header and `@version ee` tag on every file.

- [ ] **Step 3: Verify the whole tree compiles**

- [ ] **Step 4: Run the full affected-module test sweep**

```bash
DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test \
  :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration \
  :server:libs:modules:components:data-table:test \
  :server:libs:modules:components:ai:vectorstore:knowledgebase:test \
  :server:libs:automation:automation-data-table:automation-data-table-service:test \
  --continue > /tmp/t.log 2>&1
```

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-data-table
git commit -m "732 Align the EE data table remote clients with the trimmed interfaces"
```

---

## Self-Review

**Spec coverage.** Model and resolution rule — Tasks 2 and 6. Vendor reaching a CU's resource via `accountId` — Task 4. Listing unchanged — no task, correctly. Physical naming — Task 1. Registry keys with partial indexes — Tasks 1 and 6. Row owner columns removed from DDL and the migrator deleted — Task 3. Orphaned columns left in place — asserted as a negative in Task 3 Step 5 and in the Global Constraints. Deletions list — Tasks 3, 4, 5. Webhooks collapse — Task 5. **N1 is deliberately absent — see below.**

**Placeholder scan.** No TBDs. Every code step carries real code. Task 7 Step 1 is an enumeration step with no test, which is stated explicitly rather than papered over with a fake test.

**Type consistency.** `fetchDataTable(String, PlatformType, Optional<Owner>)` is introduced in Task 2 and consumed by Task 4. `buildPhysicalName(PlatformType, long, Long, String)` is introduced in Task 1 and used by Task 3's DDL work. `Webhook` loses its `owner` component in Task 5 and Task 7 aligns the EE client to it.

**Known gap, deliberate:** `DataTableUtils.resolveDataTable` currently rejects a base name held by more than one pool. Task 2's owned-wins rule operates *within* a pool, so the two compose — but a reviewer should confirm the cross-pool rejection survives Task 4's rewrite, because collapsing the two lookups is a tempting simplification that would silently drop it.

---

## Not in this plan: N1 (knowledge base ownership in the vectorstore path)

The spec folds N1 in. It is **not** in this plan, and that is a scope call the executor should not reverse.

`KnowledgeBaseVectorStore.createVectorStore` needs the run's owner, and its whole call chain is context-free:

- `VectorStore.createVectorStore(Parameters, Parameters, EmbeddingModel)` — a `@FunctionalInterface` implemented by 14 vectorstore components
- `VectorStoreFunction.apply(Parameters, Parameters, Parameters, Map<String, ComponentConnection>)` — a platform SPI
- and `VectorStoreFunction` is consumed by four *further* cluster elements — `VectorStoreChatMemory`, `VectorStoreChatMemoryUtils`, `VectorStoreDocumentRetriever`, `QuestionAnswerRag` — whose own access to a context is not yet established

A default 4-argument `createVectorStore` delegating to the 3-argument SAM would spare the 13 non-KB components, but that does not answer the open question: whether the RAG consumers can supply an owner at all, or whether the context has to be threaded further up still. Writing task steps for that now would mean guessing.

N1 needs a short investigation — how deep does a context have to travel to reach `VectorStoreFunction.apply` — and then its own plan. It is the more dangerous of the two pieces, because under this design the resource check is the only enforcement point for knowledge bases.

---

## Execution note

All tasks land in the existing `worktree-resource-pools-server-split` worktree on top of the verified pool-split commits. The whole set is regrouped afterwards, then merged ff-only to `0_732`. No intermediate merge.
