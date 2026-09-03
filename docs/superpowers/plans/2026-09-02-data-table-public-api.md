# Public Data Table API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `/api/automation/v1` endpoints for data-table DDL (tables, columns) and rows
(filtered queries, single/batch writes, CSV, upsert on a caller-supplied external id), plus the
CLI commands that drive them.

**Architecture:** A new spec-first EE module `automation-data-table-public-rest` whose two
hand-written controllers resolve `{name}` → id and delegate to `WorkspaceDataTableFacade`, which
already carries every `@PreAuthorize`. Every capability the facade lacks is added to the facade
and the platform services (never to the controllers), so GraphQL and MCP inherit it. A reserved
`external_id` column joins `owner_id`/`owner_type` on every physical table, backfilled by a
Liquibase custom change that mirrors the owner backfill.

**Tech Stack:** Java 25, Spring Boot 4, openapi-generator 7.24.0 (`spring` generator with the
vendored `pojo.mustache`; `java`/`native` generator for the CLI client), Spring Data JDBC +
`JdbcTemplate`, Liquibase, JUnit 5 + AssertJ + Mockito, Testcontainers (PostgreSQL 15), Spring Shell.

**Spec:** `docs/superpowers/specs/2026-09-02-data-table-public-api-design.md`

## Global Constraints

- **EE licensing:** every file under `server/ee/` uses the ByteChef Enterprise header and carries a
  `@version ee` Javadoc tag. Spotless picks the header from the `@version ee` **content**, not the path.
  CE files (`server/libs/`) use the Apache 2.0 header.
- **Formatting:** `./gradlew spotlessApply` before every commit. Never judge a Gradle run piped into
  `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep `^> Task .* FAILED`.
- **Java style:** blank line before every control statement; blank line between a variable
  modification and the statement that uses it; no `_`-prefixed methods; descriptive names (no `t`, `c`);
  no `TODO:` comments; test method names are camelCase with no underscores; unit test classes end in
  `Test`, integration tests in `IntTest`.
- **Enum ordinals are persisted as INT** — never reorder `ColumnType`, `OwnerType`, `PlatformType`.
- **Never `git stash`** in this repo — the stash is shared across worktrees.
- **Commit messages:** server `--- <description>` / `- <description>`; client-side `... client - ...`.
  End every commit with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **PostgreSQL 15 is the floor** — `NULLS NOT DISTINCT` is relied on.
- **Testcontainers** need Docker (OrbStack socket on this machine); IntTests run with
  `./gradlew :<module>:testIntegration`.
- **Wire limits (spec §4.3, §6.2):** `pageSize` default 50, max 500; batch max 1000 rows / ids;
  `external_id` ≤ 255 chars.
- **Name pattern (spec §4.2):** `^[a-z_][a-z0-9_]*$`, not starting with `dt_`; reserved names
  `id`, `owner_id`, `owner_type`, `external_id` are rejected on the public surface.

## File structure

| Path | Responsibility |
|---|---|
| `server/libs/platform/platform-data-table/platform-data-table-api/.../configuration/exception/DataTableErrorType.java` | **moved here from `-service`**, gains keys 103–116 |
| `.../platform-data-table-api/.../exception/DataTableException.java` | `AbstractException` subclass for typed data-table errors |
| `.../platform-data-table-api/.../domain/ReservedColumns.java` | gains `EXTERNAL_ID` |
| `.../platform-data-table-api/.../execution/domain/DataTableRow.java` | gains `externalId`; 2-arg constructor kept |
| `.../platform-data-table-api/.../execution/domain/{NewRow,ExternalIdPatch,UpsertResult,CreateStrategy}.java` | value types for the new row operations |
| `.../platform-data-table-api/.../execution/service/DataTableRowService.java` | new: `fetchRowByExternalId`, `upsertRow`, `insertRows`, `deleteRows`, `clearRows`, `countRows`, `insertRow(…, externalId)`, `updateRow(…, patch)`; `importCsv` returns `int` |
| `.../platform-data-table-api/.../configuration/service/DataTableService.java` | new: `updateDescription`, `fetchDataTableInfo` |
| `.../platform-data-table-service/.../configuration/service/DataTableServiceImpl.java` | `external_id` in `CREATE TABLE`, partial unique index, typed errors, new methods |
| `.../platform-data-table-service/.../execution/service/DataTableRowServiceImpl.java` | `external_id` on read/write, upsert, batch, count |
| `.../platform-data-table-service/.../configuration/migration/DataTableExternalIdColumn{Migrator,Change}.java` | backfill |
| `.../platform-data-table-service/src/main/resources/config/liquibase/changelog/platform/data_table/20260902000001_platform_data_table_backfill_external_id.xml` | changelog (picked up by `includeAll`) |
| `server/libs/automation/automation-data-table/automation-data-table-api/.../facade/WorkspaceDataTableFacade.java` + `-service/.../WorkspaceDataTableFacadeImpl.java` | new guarded methods |
| `server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/` | new module: `openapi.yaml`, `generated/`, controllers, parser, validator, mapper |
| `server/ee/libs/automation/automation-security-web/automation-security-web-impl/.../AutomationApiKeyAuthenticationProvider.java` | key-type check |
| `docs/lib/openapi/index.ts` | wire the new spec into the docs build |
| `cli/clients/automation-data-table/` | generated Java client |
| `cli/commands/automation/.../AutomationDataTableCommand.java`, `AutomationDataTableRowCommand.java`, `AutomationClientFactory.java` | CLI |

Every task touching `server/ee/` or `cli/` uses the Enterprise header **only** under `server/ee/`;
`cli/` files use the Apache header like the existing CLI sources.

---

### Task 1: Typed data-table errors in the `-api` module (and the spec amendment they force)

**Files:**
- Move: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/exception/DataTableErrorType.java` → same package under `platform-data-table-api/src/main/java/...`
- Create: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/configuration/exception/DataTableException.java`
- Test: `server/libs/platform/platform-data-table/platform-data-table-api/src/test/java/com/bytechef/platform/data/table/configuration/exception/DataTableErrorTypeTest.java`
- Modify: `docs/superpowers/specs/2026-09-02-data-table-public-api-design.md` §7.2 and §7.3

**Interfaces:**
- Consumes: `com.bytechef.exception.AbstractErrorType`, `com.bytechef.exception.AbstractException` (module `exception-api`, already a dependency of `platform-data-table-api` through `DataTableStorageLimitExceededException`'s neighbours — verify with step 3).
- Produces: `DataTableErrorType.{DATA_TABLE_NOT_FOUND=100, DATA_TABLE_NOT_CREATED=101, DATA_TABLE_NOT_DUPLICATED=102, DATA_TABLE_NAME_INVALID=103, DATA_TABLE_ALREADY_EXISTS=104, COLUMN_NOT_FOUND=105, COLUMN_ALREADY_EXISTS=106, COLUMN_NAME_INVALID=107, ROW_NOT_FOUND=108, ROW_VALUE_INVALID=109, ROW_EXTERNAL_ID_CONFLICT=110, ROW_EXTERNAL_ID_REQUIRED=111, FILTER_INVALID=112, SORT_INVALID=113, BATCH_TOO_LARGE=114, CSV_INVALID=115, STORAGE_LIMIT_EXCEEDED=116}` and `DataTableException(String message, DataTableErrorType errorType)`.

The spec numbered the new keys from 100 because it did not know `DataTableErrorType` already existed
in `-service` with 100–102 taken. Keys are a public contract, so the existing three stay and the new
ones are appended. `getIdByBaseName` already throws `ExecutionException(…, DATA_TABLE_NOT_FOUND)` —
that keeps working; the public handler matches on `AbstractException` whose entity class is
`DataTableErrorType`.

- [ ] **Step 1: Amend the spec**

In §7.2 replace the key table with the numbering above (100–102 marked "existing"), and replace the
first paragraph of §7.3 with:

```
`GlobalResponseEntityExceptionHandler` is `@Order(HIGHEST_PRECEDENCE)` and maps every
`AbstractException` to 400, so a package-scoped advice cannot reliably outrank it. Spring resolves
`@ExceptionHandler` methods declared **on the controller class itself** before any advice, so the
public module's two controllers extend `AbstractDataTableApiController`, whose handlers map a
`DataTableErrorType`-bearing `AbstractException` to 400/404/409, `DataTableStorageLimitExceededException`
to 507, and (item URLs only) `AccessDeniedException` to 404. The body is built with the same fields
`AbstractResponseEntityExceptionHandler` emits. Nothing global changes.
```

- [ ] **Step 2: Write the failing key-stability test**

```java
package com.bytechef.platform.data.table.configuration.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DataTableErrorTypeTest {

    @Test
    void testKeysAreStable() {
        assertThat(DataTableErrorType.DATA_TABLE_NOT_FOUND.getErrorKey()).isEqualTo(100);
        assertThat(DataTableErrorType.DATA_TABLE_NAME_INVALID.getErrorKey()).isEqualTo(103);
        assertThat(DataTableErrorType.DATA_TABLE_ALREADY_EXISTS.getErrorKey()).isEqualTo(104);
        assertThat(DataTableErrorType.COLUMN_NOT_FOUND.getErrorKey()).isEqualTo(105);
        assertThat(DataTableErrorType.COLUMN_ALREADY_EXISTS.getErrorKey()).isEqualTo(106);
        assertThat(DataTableErrorType.COLUMN_NAME_INVALID.getErrorKey()).isEqualTo(107);
        assertThat(DataTableErrorType.ROW_NOT_FOUND.getErrorKey()).isEqualTo(108);
        assertThat(DataTableErrorType.ROW_VALUE_INVALID.getErrorKey()).isEqualTo(109);
        assertThat(DataTableErrorType.ROW_EXTERNAL_ID_CONFLICT.getErrorKey()).isEqualTo(110);
        assertThat(DataTableErrorType.ROW_EXTERNAL_ID_REQUIRED.getErrorKey()).isEqualTo(111);
        assertThat(DataTableErrorType.FILTER_INVALID.getErrorKey()).isEqualTo(112);
        assertThat(DataTableErrorType.SORT_INVALID.getErrorKey()).isEqualTo(113);
        assertThat(DataTableErrorType.BATCH_TOO_LARGE.getErrorKey()).isEqualTo(114);
        assertThat(DataTableErrorType.CSV_INVALID.getErrorKey()).isEqualTo(115);
        assertThat(DataTableErrorType.STORAGE_LIMIT_EXCEEDED.getErrorKey()).isEqualTo(116);
    }

    @Test
    void testExceptionCarriesKeyAndEntityClass() {
        DataTableException dataTableException = new DataTableException(
            "row 7 not found", DataTableErrorType.ROW_NOT_FOUND);

        assertThat(dataTableException.getErrorKey()).isEqualTo(108);
        assertThat(dataTableException.getEntityClass()).isEqualTo(DataTableErrorType.class);
        assertThat(dataTableException.getMessage()).isEqualTo("row 7 not found");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-api:test --tests '*DataTableErrorTypeTest' > /tmp/t1.log 2>&1; echo $?`
Expected: non-zero; `grep -c "cannot find symbol" /tmp/t1.log` > 0. If the failure is a missing
`exception-api` dependency instead, add
`api(project(":server:libs:core:exception:exception-api"))` to
`platform-data-table-api/build.gradle.kts` and rerun.

- [ ] **Step 4: Move the class and add the keys**

`git mv` the file to the `-api` module (same package), then replace its body:

```java
public class DataTableErrorType extends AbstractErrorType {

    public static final DataTableErrorType DATA_TABLE_NOT_FOUND = new DataTableErrorType(100);
    public static final DataTableErrorType DATA_TABLE_NOT_CREATED = new DataTableErrorType(101);
    public static final DataTableErrorType DATA_TABLE_NOT_DUPLICATED = new DataTableErrorType(102);
    public static final DataTableErrorType DATA_TABLE_NAME_INVALID = new DataTableErrorType(103);
    public static final DataTableErrorType DATA_TABLE_ALREADY_EXISTS = new DataTableErrorType(104);
    public static final DataTableErrorType COLUMN_NOT_FOUND = new DataTableErrorType(105);
    public static final DataTableErrorType COLUMN_ALREADY_EXISTS = new DataTableErrorType(106);
    public static final DataTableErrorType COLUMN_NAME_INVALID = new DataTableErrorType(107);
    public static final DataTableErrorType ROW_NOT_FOUND = new DataTableErrorType(108);
    public static final DataTableErrorType ROW_VALUE_INVALID = new DataTableErrorType(109);
    public static final DataTableErrorType ROW_EXTERNAL_ID_CONFLICT = new DataTableErrorType(110);
    public static final DataTableErrorType ROW_EXTERNAL_ID_REQUIRED = new DataTableErrorType(111);
    public static final DataTableErrorType FILTER_INVALID = new DataTableErrorType(112);
    public static final DataTableErrorType SORT_INVALID = new DataTableErrorType(113);
    public static final DataTableErrorType BATCH_TOO_LARGE = new DataTableErrorType(114);
    public static final DataTableErrorType CSV_INVALID = new DataTableErrorType(115);
    public static final DataTableErrorType STORAGE_LIMIT_EXCEEDED = new DataTableErrorType(116);

    private DataTableErrorType(int errorKey) {
        super(DataTableErrorType.class, errorKey);
    }
}
```

Create `DataTableException`:

```java
package com.bytechef.platform.data.table.configuration.exception;

import com.bytechef.exception.AbstractException;

/**
 * A data-table failure a caller can act on. The {@link DataTableErrorType} key is the public contract; the message is
 * for humans.
 *
 * @author Ivica Cardic
 */
public class DataTableException extends AbstractException {

    public DataTableException(String message, DataTableErrorType errorType) {
        super(message, errorType);
    }

    public DataTableException(String message, Throwable cause, DataTableErrorType errorType) {
        super(message, cause, errorType);
    }
}
```

- [ ] **Step 5: Run the test and the service module's compile**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-api:test --tests '*DataTableErrorTypeTest' :server:libs:platform:platform-data-table:platform-data-table-service:compileJava > /tmp/t1.log 2>&1; echo $?`
Expected: 0. (`DataTableServiceImpl` imports the same package name, so the move compiles unchanged.)

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add docs/superpowers/specs/2026-09-02-data-table-public-api-design.md server/libs/platform/platform-data-table
git commit -m "--- Move DataTableErrorType to the api module and add the public data table error keys

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: `external_id` becomes a reserved column of every new physical table

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/domain/ReservedColumns.java`
- Modify: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/service/DataTableServiceImpl.java` (`buildCreateTableSql`, `createPhysicalTable`, `duplicateTable`)
- Test: `.../platform-data-table-api/src/test/java/com/bytechef/platform/data/table/domain/ReservedColumnsTest.java` (existing — extend)
- Test: `.../platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableDdlOwnerColumnsTest.java` (existing — extend)
- Test: `.../platform-data-table-service/src/test/java/com/bytechef/platform/data/table/configuration/service/DataTableExternalIdIndexIntTest.java` (new)

**Interfaces:**
- Produces: `ReservedColumns.EXTERNAL_ID = "external_id"` (reserved, **not** hidden);
  `DataTableServiceImpl.buildExternalIdIndexSql(String physicalName)` (package-private static).

- [ ] **Step 1: Extend `ReservedColumnsTest`**

```java
    @Test
    void testExternalIdIsReservedButNotHidden() {
        assertTrue(ReservedColumns.isReserved("external_id"));
        assertTrue(ReservedColumns.isReserved("EXTERNAL_ID"));
        assertFalse(ReservedColumns.isHidden("external_id"));
        assertTrue(ReservedColumns.all()
            .contains(ReservedColumns.EXTERNAL_ID));
    }
```

- [ ] **Step 2: Extend `DataTableDdlOwnerColumnsTest`** (it already asserts on `buildCreateTableSql`)

```java
    @Test
    void testCreateTableCarriesTheExternalIdColumn() {
        String sql = DataTableServiceImpl.buildCreateTableSql("dt_0_orders", List.of());

        assertTrue(sql.contains("\"external_id\" VARCHAR(255)"), sql);
    }

    @Test
    void testExternalIdIndexIsPartialAndNullsNotDistinct() {
        String sql = DataTableServiceImpl.buildExternalIdIndexSql("dt_0_orders");

        assertEquals(
            "CREATE UNIQUE INDEX ON \"dt_0_orders\" (\"owner_id\", \"external_id\") NULLS NOT DISTINCT " +
                "WHERE \"external_id\" IS NOT NULL",
            sql);
    }
```

- [ ] **Step 3: Write the failing IntTest**

```java
package com.bytechef.platform.data.table.configuration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableExternalIdIndexIntTest {

    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void beforeEach() {
        dataTableService.dropTable("keyed", ENVIRONMENT_ID, PlatformType.AUTOMATION);

        dataTableService.createTable(
            "keyed", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION);
    }

    @Test
    void testTheSameExternalIdTwiceWithANullOwnerIsRejected() {
        jdbcTemplate.update("INSERT INTO \"dt_0_keyed\" (\"external_id\", \"title\") VALUES ('k1', 'a')");

        assertThrows(
            DuplicateKeyException.class,
            () -> jdbcTemplate.update("INSERT INTO \"dt_0_keyed\" (\"external_id\", \"title\") VALUES ('k1', 'b')"));
    }

    @Test
    void testRowsWithoutAnExternalIdCoexist() {
        jdbcTemplate.update("INSERT INTO \"dt_0_keyed\" (\"title\") VALUES ('a')");
        jdbcTemplate.update("INSERT INTO \"dt_0_keyed\" (\"title\") VALUES ('b')");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"dt_0_keyed\"", Integer.class);

        assertEquals(2, count);
    }

    @Test
    void testTwoOwnersMayShareAnExternalId() {
        jdbcTemplate.update(
            "INSERT INTO \"dt_0_keyed\" (\"owner_id\", \"owner_type\", \"external_id\") VALUES (1, 0, 'k1')");
        jdbcTemplate.update(
            "INSERT INTO \"dt_0_keyed\" (\"owner_id\", \"owner_type\", \"external_id\") VALUES (2, 0, 'k1')");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"dt_0_keyed\"", Integer.class);

        assertEquals(2, count);
    }
}
```

- [ ] **Step 4: Run all three to verify they fail**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-api:test --tests '*ReservedColumnsTest' :server:libs:platform:platform-data-table:platform-data-table-service:test --tests '*DataTableDdlOwnerColumnsTest' > /tmp/t2.log 2>&1; echo $?`
Expected: non-zero (`EXTERNAL_ID` undefined, `buildExternalIdIndexSql` undefined).

- [ ] **Step 5: Implement**

`ReservedColumns`:

```java
    public static final String EXTERNAL_ID = "external_id";

    private static final Set<String> ALL = Set.of(ID, OWNER_ID, OWNER_TYPE, EXTERNAL_ID);
```

(`HIDDEN` stays `Set.of(OWNER_ID, OWNER_TYPE)`. Update the class Javadoc: "`external_id` is reserved
but not hidden — it is the caller's own key, returned on every row and usable in filters and sorts.")

`DataTableServiceImpl.buildCreateTableSql` — after the `owner_type` column:

```java
        return "CREATE TABLE " + escapeIdentifier(physicalName) + " (\"id\" BIGSERIAL PRIMARY KEY, " +
            escapeIdentifier(ReservedColumns.OWNER_ID) + " BIGINT, " +
            escapeIdentifier(ReservedColumns.OWNER_TYPE) + " INT, " +
            escapeIdentifier(ReservedColumns.EXTERNAL_ID) + " VARCHAR(255)" +
            (userColumnsSql.isEmpty() ? "" : ", " + userColumnsSql) + ")";
```

Add beside `buildOwnerIndexSql`:

```java
    /**
     * The upsert key. {@code NULLS NOT DISTINCT} is for {@code owner_id}: without it every automation-pool row, whose
     * owner is NULL, would be a distinct key and the pool would get no uniqueness at all. The predicate is for
     * {@code external_id}: with {@code NULLS NOT DISTINCT} and no predicate every row without a key would collapse
     * into one. Unnamed for the same 63-byte reason as the owner index.
     */
    static String buildExternalIdIndexSql(String physicalName) {
        return "CREATE UNIQUE INDEX ON " + escapeIdentifier(physicalName) + " (" +
            escapeIdentifier(ReservedColumns.OWNER_ID) + ", " + escapeIdentifier(ReservedColumns.EXTERNAL_ID) +
            ") NULLS NOT DISTINCT WHERE " + escapeIdentifier(ReservedColumns.EXTERNAL_ID) + " IS NOT NULL";
    }
```

`createPhysicalTable` executes it third:

```java
        jdbcTemplate.execute(buildExternalIdIndexSql(physicalName));
```

`duplicateTable` copies it — in the `copiedColumnNames` block add
`copiedColumnNames.add(ReservedColumns.EXTERNAL_ID);` after `OWNER_TYPE`, and extend the comment:
"`external_id` is copied too: a duplicate that dropped it would turn every keyed row into an unkeyed
one and make the next upsert insert a twin."

- [ ] **Step 6: Run unit tests, then the IntTest**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-api:test :server:libs:platform:platform-data-table:platform-data-table-service:test > /tmp/t2.log 2>&1; echo $?`
Expected: 0.
Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableExternalIdIndexIntTest' --tests '*DataTableServiceIntTest' --tests '*DataTableDuplicateRowOwnerIntTest' > /tmp/t2i.log 2>&1; echo $?`
Expected: 0.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-data-table
git commit -m "--- Reserve external_id on every physical data table with a partial unique upsert index

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Backfill `external_id` onto existing physical tables

**Files:**
- Create: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/java/com/bytechef/platform/data/table/configuration/migration/DataTableExternalIdColumnMigrator.java`
- Create: `.../configuration/migration/DataTableExternalIdColumnChange.java`
- Create: `server/libs/platform/platform-data-table/platform-data-table-service/src/main/resources/config/liquibase/changelog/platform/data_table/20260902000001_platform_data_table_backfill_external_id.xml`
- Test: `.../src/test/java/com/bytechef/platform/data/table/configuration/migration/DataTableExternalIdColumnMigratorIntTest.java`

**Interfaces:**
- Produces: `new DataTableExternalIdColumnMigrator(JdbcTemplate).migrate(@Nullable String schemaName) : int`.

- [ ] **Step 1: Write the failing IntTest** (mirrors `DataTableOwnerColumnMigratorIntTest`)

```java
package com.bytechef.platform.data.table.configuration.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableExternalIdColumnMigratorIntTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DataTableExternalIdColumnMigrator migrator;

    @BeforeEach
    void beforeEach() {
        migrator = new DataTableExternalIdColumnMigrator(jdbcTemplate);
    }

    @Test
    void testMigrateAddsTheColumnAndTheIndexToAPreexistingTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"dt_0_legacykey\"");
        jdbcTemplate.execute(
            "CREATE TABLE \"dt_0_legacykey\" (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, " +
                "\"owner_type\" INT, \"title\" TEXT)");
        jdbcTemplate.update("INSERT INTO \"dt_0_legacykey\" (\"title\") VALUES ('a'), ('b')");

        assertTrue(migrator.migrate() >= 1);

        assertTrue(hasColumn("dt_0_legacykey", "external_id"));

        jdbcTemplate.update("INSERT INTO \"dt_0_legacykey\" (\"external_id\") VALUES ('k')");

        assertThrows(
            DuplicateKeyException.class,
            () -> jdbcTemplate.update("INSERT INTO \"dt_0_legacykey\" (\"external_id\") VALUES ('k')"));
    }

    @Test
    void testMigrateSweepsTheEmbeddedPoolToo() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"edt_0_legacykey\"");
        jdbcTemplate.execute(
            "CREATE TABLE \"edt_0_legacykey\" (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, " +
                "\"owner_type\" INT)");

        migrator.migrate();

        assertTrue(hasColumn("edt_0_legacykey", "external_id"));
    }

    @Test
    void testMigrateIsIdempotent() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"dt_0_legacykey_two\"");
        jdbcTemplate.execute(
            "CREATE TABLE \"dt_0_legacykey_two\" (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, " +
                "\"owner_type\" INT)");

        migrator.migrate();

        assertEquals(0, migrator.migrate());
    }

    @Test
    void testMigrateIgnoresNonDataTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"not_a_keyed_table\"");
        jdbcTemplate.execute("CREATE TABLE \"not_a_keyed_table\" (\"id\" BIGSERIAL PRIMARY KEY)");

        migrator.migrate();

        assertFalse(hasColumn("not_a_keyed_table", "external_id"));
    }

    @Test
    void testLiquibaseRanTheBackfillChangeset() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM databasechangelog WHERE id = ?", Integer.class, "20260902000001-1");

        assertEquals(1, count);
    }

    private boolean hasColumn(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
            Integer.class, tableName, columnName);

        return count != null && count > 0;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableExternalIdColumnMigratorIntTest' > /tmp/t3.log 2>&1; echo $?`
Expected: non-zero (class missing).

- [ ] **Step 3: Implement the migrator** (same shape as `DataTableOwnerColumnMigrator`; copy its
`resolveSchema` and `quote` verbatim)

```java
package com.bytechef.platform.data.table.configuration.migration;

import com.bytechef.platform.data.table.domain.ReservedColumns;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

/**
 * Adds the reserved {@code external_id} column and its partial unique index to physical data tables created before the
 * column existed. Discovered from {@code information_schema} like the owner backfill, and for the same reason: the
 * tables are created at runtime and cannot be named in a changeset.
 *
 * <p>
 * The index is {@code (owner_id, external_id) NULLS NOT DISTINCT WHERE external_id IS NOT NULL}. Both halves matter:
 * the first gives the automation pool, whose owner is always NULL, real uniqueness; the second keeps the rows that
 * have no key -- every row on the day this runs -- from colliding with each other.
 *
 * @author Ivica Cardic
 */
public class DataTableExternalIdColumnMigrator {

    private static final Logger log = LoggerFactory.getLogger(DataTableExternalIdColumnMigrator.class);

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings("EI")
    public DataTableExternalIdColumnMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int migrate() {
        return migrate(null);
    }

    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public int migrate(@Nullable String schemaName) {
        String schema = resolveSchema(schemaName);

        List<String> tableNames = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND (table_name LIKE 'dt\\_%' OR table_name LIKE 'edt\\_%')",
            String.class, schema);

        int altered = 0;

        for (String tableName : tableNames) {
            if (hasExternalIdColumn(schema, tableName)) {
                continue;
            }

            String qualifiedName = quote(schema) + "." + quote(tableName);

            jdbcTemplate.execute(
                "ALTER TABLE " + qualifiedName + " ADD COLUMN IF NOT EXISTS " + quote(ReservedColumns.EXTERNAL_ID)
                    + " VARCHAR(255)");
            jdbcTemplate.execute(
                "CREATE UNIQUE INDEX ON " + qualifiedName + " (" + quote(ReservedColumns.OWNER_ID) + ", "
                    + quote(ReservedColumns.EXTERNAL_ID) + ") NULLS NOT DISTINCT WHERE "
                    + quote(ReservedColumns.EXTERNAL_ID) + " IS NOT NULL");

            altered++;
        }

        if (altered > 0) {
            log.info("Added external_id to {} data tables in schema {}", altered, schema);
        }

        return altered;
    }

    private boolean hasExternalIdColumn(String schema, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
            Integer.class, schema, tableName, ReservedColumns.EXTERNAL_ID);

        return count != null && count == 1;
    }

    private String resolveSchema(@Nullable String schemaName) {
        if (schemaName != null && !schemaName.isBlank()) {
            return schemaName;
        }

        return jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
    }

    private String quote(String identifier) {
        Assert.hasText(identifier, "identifier must not be empty");

        String normalizedName = identifier.toLowerCase(Locale.ROOT);

        Assert.isTrue(normalizedName.matches("[a-z_][a-z0-9_]*"), "Invalid identifier: " + identifier);

        return '"' + normalizedName + '"';
    }
}
```

`DataTableExternalIdColumnChange` is `DataTableOwnerColumnChange` with the migrator class swapped and
the confirmation message `"Added external_id to " + alteredTableCount + " data tables"`. Copy the
whole file, rename, replace the two references.

The changelog:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                   logicalFilePath="config/liquibase/changelog/platform/data_table/20260902000001_platform_data_table_backfill_external_id.xml">

    <!--
        Physical data tables are created at runtime, so the external_id column and its upsert index are backfilled
        onto the ones that already exist; tables created from here on get both from createTable.
    -->
    <changeSet id="20260902000001-1" author="Ivica Cardic">
        <customChange class="com.bytechef.platform.data.table.configuration.migration.DataTableExternalIdColumnChange"/>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: Run the IntTest**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableExternalIdColumnMigratorIntTest' > /tmp/t3.log 2>&1; echo $?`
Expected: 0.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-data-table
git commit -m "--- Backfill external_id onto existing physical data tables

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Rows carry `externalId` — read, insert, update, fetch-by-key

**Files:**
- Modify: `server/libs/platform/platform-data-table/platform-data-table-api/src/main/java/com/bytechef/platform/data/table/execution/domain/DataTableRow.java`
- Create: `.../platform-data-table-api/.../execution/domain/ExternalIdPatch.java`
- Modify: `.../platform-data-table-api/.../execution/service/DataTableRowService.java`
- Modify: `.../platform-data-table-service/.../execution/service/DataTableRowServiceImpl.java`
- Test: `.../platform-data-table-service/src/test/java/com/bytechef/platform/data/table/execution/service/DataTableRowExternalIdIntTest.java`

**Interfaces:**
- Produces:
  - `record DataTableRow(long id, @Nullable String externalId, Map<String, Object> values)` with the
    existing `DataTableRow(long id, Map<String, Object> values)` kept as a delegating constructor, so
    no existing caller changes.
  - `record ExternalIdPatch(@Nullable String externalId)` — a non-null patch means "set to this
    value (null clears)"; passing `null` for the patch means "leave untouched".
  - `DataTableRowService.insertRow(DataTableRef, Map<String,Object> values, @Nullable String externalId)`
  - `DataTableRowService.updateRow(DataTableRef, long id, Map<String,Object> values, @Nullable ExternalIdPatch patch)`
  - `DataTableRowService.fetchRowByExternalId(DataTableRef, String externalId) : Optional<DataTableRow>`
  - Existing 2-/3-arg `insertRow`/`updateRow` delegate with `null`.
  - A duplicate key on insert/update throws `DataTableException(ROW_EXTERNAL_ID_CONFLICT)`.

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteDataTableRowServiceClient`
in ``server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableRowServiceClient.java``
implements `DataTableRowService` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

Reads include `external_id` **only when the physical table has the column** (checked from the
`information_schema` column list already fetched by `requireColumns`). Every existing IntTest builds
its tables by hand without the column, and production tables always have it after Task 3's backfill,
so the conditional costs nothing and keeps the suite green.

- [ ] **Step 1: Write the failing IntTest**

```java
package com.bytechef.platform.data.table.execution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.exception.DataTableException;
import com.bytechef.platform.data.table.configuration.exception.DataTableErrorType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.domain.ExternalIdPatch;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableRowExternalIdIntTest {

    private static final long ENVIRONMENT_ID = 0;
    private static final DataTableRef REF = DataTableRef.unowned("keyedrows", ENVIRONMENT_ID, PlatformType.AUTOMATION);

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @BeforeEach
    void beforeEach() {
        dataTableService.dropTable("keyedrows", ENVIRONMENT_ID, PlatformType.AUTOMATION);

        dataTableService.createTable(
            "keyedrows", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION);
    }

    @Test
    void testInsertWithAnExternalIdReturnsIt() {
        DataTableRow dataTableRow = dataTableRowService.insertRow(REF, Map.of("title", "a"), "ORD-1");

        assertEquals("ORD-1", dataTableRow.externalId());
        assertEquals("a", dataTableRow.values()
            .get("title"));
    }

    @Test
    void testInsertWithoutAnExternalIdLeavesItNull() {
        DataTableRow dataTableRow = dataTableRowService.insertRow(REF, Map.of("title", "a"));

        assertNull(dataTableRow.externalId());
    }

    @Test
    void testReadsCarryTheExternalId() {
        DataTableRow inserted = dataTableRowService.insertRow(REF, Map.of("title", "a"), "ORD-1");

        DataTableRow read = dataTableRowService.getRow(REF, inserted.id());

        assertEquals("ORD-1", read.externalId());

        List<DataTableRow> listed = dataTableRowService.listRows(REF, 10, 0);

        assertEquals("ORD-1", listed.getFirst()
            .externalId());
    }

    @Test
    void testFetchByExternalId() {
        dataTableRowService.insertRow(REF, Map.of("title", "a"), "ORD-1");

        Optional<DataTableRow> found = dataTableRowService.fetchRowByExternalId(REF, "ORD-1");

        assertTrue(found.isPresent());
        assertEquals("a", found.get()
            .values()
            .get("title"));
        assertTrue(dataTableRowService.fetchRowByExternalId(REF, "missing")
            .isEmpty());
    }

    @Test
    void testDuplicateExternalIdOnInsertIsATypedConflict() {
        dataTableRowService.insertRow(REF, Map.of("title", "a"), "ORD-1");

        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> dataTableRowService.insertRow(REF, Map.of("title", "b"), "ORD-1"));

        assertEquals(DataTableErrorType.ROW_EXTERNAL_ID_CONFLICT.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testUpdateCanSetAndClearTheExternalId() {
        DataTableRow inserted = dataTableRowService.insertRow(REF, Map.of("title", "a"));

        DataTableRow keyed = dataTableRowService.updateRow(
            REF, inserted.id(), Map.of(), new ExternalIdPatch("ORD-9"));

        assertEquals("ORD-9", keyed.externalId());

        DataTableRow untouched = dataTableRowService.updateRow(REF, inserted.id(), Map.of("title", "b"), null);

        assertEquals("ORD-9", untouched.externalId());
        assertEquals("b", untouched.values()
            .get("title"));

        DataTableRow cleared = dataTableRowService.updateRow(
            REF, inserted.id(), Map.of(), new ExternalIdPatch(null));

        assertNull(cleared.externalId());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableRowExternalIdIntTest' > /tmp/t4.log 2>&1; echo $?`
Expected: non-zero (compile errors on the new signatures).

- [ ] **Step 3: Value types in `-api`**

`DataTableRow`:

```java
@SuppressFBWarnings("EI")
public record DataTableRow(long id, @Nullable String externalId, Map<String, Object> values) {

    public DataTableRow(long id, Map<String, Object> values) {
        this(id, null, values);
    }
}
```

`ExternalIdPatch` (new file, Apache header):

```java
package com.bytechef.platform.data.table.execution.domain;

import org.jspecify.annotations.Nullable;

/**
 * A requested change to a row's external id. The patch itself being null means "leave it alone"; a patch holding null
 * means "clear it". Two nulls with two meanings is why this is a record and not a plain nullable string.
 *
 * @author Ivica Cardic
 */
public record ExternalIdPatch(@Nullable String externalId) {
}
```

`DataTableRowService` — add, with Javadoc:

```java
    Optional<DataTableRow> fetchRowByExternalId(DataTableRef dataTableRef, String externalId);

    DataTableRow insertRow(DataTableRef dataTableRef, Map<String, Object> values, @Nullable String externalId);

    DataTableRow updateRow(
        DataTableRef dataTableRef, long id, Map<String, Object> values, @Nullable ExternalIdPatch externalIdPatch);
```

- [ ] **Step 4: Implement in `DataTableRowServiceImpl`**

Add a helper used by every read:

```java
    private static boolean hasExternalIdColumn(List<ColumnSpec> columnSpecs) {
        return columnSpecs.stream()
            .anyMatch(columnSpec -> ReservedColumns.EXTERNAL_ID.equalsIgnoreCase(columnSpec.name()));
    }

    private static @Nullable String readExternalId(ResultSet resultSet, boolean hasExternalIdColumn)
        throws SQLException {

        return hasExternalIdColumn ? resultSet.getString(ReservedColumns.EXTERNAL_ID) : null;
    }
```

In `getRow` and both `listRows` overloads: compute `boolean hasExternalIdColumn = hasExternalIdColumn(columnSpecs);`
right after `requireColumns`, add `(hasExternalIdColumn ? ", \"external_id\"" : "")` to the
`selectColumns` string after `"id"`, and construct rows as
`new DataTableRow(rowId, readExternalId(resultSet, hasExternalIdColumn), values)`.

`insertRow(ref, values)` becomes `return insertRow(dataTableRef, values, null);`. The 3-arg version is
the existing body with these changes:

```java
        if (externalId != null) {
            insertColumnNames.add(ReservedColumns.EXTERNAL_ID);
        }
```
(before the owner columns are appended), the matching bind after the user columns and before the owner:

```java
            if (externalId != null) {
                ps.setString(i++, externalId);
            }
```
`returningColumnNames` gains `ReservedColumns.EXTERNAL_ID` when `hasExternalIdColumn`, the row is built
with `readExternalId(resultSet, hasExternalIdColumn)`, and the whole `jdbcTemplate.query(...)` call is
wrapped:

```java
        DataTableRow result;

        try {
            result = jdbcTemplate.query(sql, ps -> { ... }, resultSet -> { ... });
        } catch (DuplicateKeyException duplicateKeyException) {
            throw new DataTableException(
                "A row with external id '" + externalId + "' already exists", duplicateKeyException,
                DataTableErrorType.ROW_EXTERNAL_ID_CONFLICT);
        }
```

`updateRow(ref, id, values)` becomes `return updateRow(dataTableRef, id, values, null);`. The 4-arg
version: build `setClauses` as a `List<String>` from `updatableColumnNames`, then

```java
        if (externalIdPatch != null) {
            setClauses.add(escapeIdentifier(ReservedColumns.EXTERNAL_ID) + " = ?");
        }

        if (setClauses.isEmpty()) {
            DataTableRow current = getRow(dataTableRef, id);

            if (current == null) {
                throw new DataTableException("Row not found: id=" + id, DataTableErrorType.ROW_NOT_FOUND);
            }

            return current;
        }
```
(replacing the existing "nothing to update" block), bind the patch after the user columns and before
`ps.setLong(i++, id)`:

```java
            if (externalIdPatch != null) {
                ps.setString(i++, externalIdPatch.externalId());
            }
```
and wrap in the same `DuplicateKeyException` → `ROW_EXTERNAL_ID_CONFLICT` translation. The two
`throw new IllegalArgumentException("Row not found: id=" + id)` sites in `updateRow` become
`throw new DataTableException("Row not found: id=" + id, DataTableErrorType.ROW_NOT_FOUND)`.

`fetchRowByExternalId`:

```java
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public Optional<DataTableRow> fetchRowByExternalId(DataTableRef dataTableRef, String externalId) {
        String physicalName = dataTableRef.physicalName();

        List<ColumnSpec> columnSpecs = requireColumns(physicalName);
        List<String> columnNames = userColumnNames(columnSpecs);

        String sql = "SELECT " + selectColumns(columnNames, true) + " FROM " + escapeIdentifier(physicalName) +
            " WHERE " + escapeIdentifier(ReservedColumns.EXTERNAL_ID) + " = ?" +
            RowQuerySqlBuilder.readableOwnerPredicate(dataTableRef);

        List<DataTableRow> rows = jdbcTemplate.query(sql, ps -> {
            ps.setString(1, externalId);

            RowQuerySqlBuilder.bindOwner(ps, 2, dataTableRef);
        }, (resultSet, rowNum) -> toRow(resultSet, columnNames, true));

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
```

Introduce the three small helpers this uses and refactor `getRow`/`listRows` onto them so there is one
row-mapping path:

```java
    private static List<String> userColumnNames(List<ColumnSpec> columnSpecs) {
        return columnSpecs.stream()
            .map(ColumnSpec::name)
            .filter(columnName -> !ReservedColumns.isReserved(columnName))
            .toList();
    }

    private String selectColumns(List<String> columnNames, boolean hasExternalIdColumn) {
        return "\"id\"" + (hasExternalIdColumn ? ", " + escapeIdentifier(ReservedColumns.EXTERNAL_ID) : "") +
            (columnNames.isEmpty() ? "" : ", " + columnNames.stream()
                .map(this::escapeIdentifier)
                .collect(Collectors.joining(", ")));
    }

    private static DataTableRow toRow(ResultSet resultSet, List<String> columnNames, boolean hasExternalIdColumn)
        throws SQLException {

        Map<String, Object> values = new HashMap<>();

        for (String columnName : columnNames) {
            values.put(columnName, resultSet.getObject(columnName));
        }

        return new DataTableRow(resultSet.getLong("id"), readExternalId(resultSet, hasExternalIdColumn), values);
    }
```

- [ ] **Step 5: Run the new IntTest and the whole row suite**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration > /tmp/t4.log 2>&1; echo $?`
Expected: 0. Also compile every consumer of `DataTableRow`:
`./gradlew compileJava compileTestJava --continue > /tmp/t4c.log 2>&1; echo $?` → 0, and
`grep -c '^> Task .* FAILED' /tmp/t4c.log` → 0.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-data-table
git commit -m "--- Carry external_id on data table rows through read, insert, update and fetch-by-key

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: `upsertRow` on the ownership index

**Files:**
- Create: `.../platform-data-table-api/.../execution/domain/UpsertResult.java`
- Modify: `DataTableRowService.java`, `DataTableRowServiceImpl.java`
- Test: `DataTableRowExternalIdIntTest.java` (extend); `DataTableRowOwnerScopingIntTest.java` (extend — the ownership suite)

**Interfaces:**
- Produces: `record UpsertResult(DataTableRow row, boolean created)`;
  `DataTableRowService.upsertRow(DataTableRef, String externalId, Map<String,Object> values) : UpsertResult`.

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteDataTableRowServiceClient`
in ``server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableRowServiceClient.java``
implements `DataTableRowService` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

- [ ] **Step 1: Failing tests**

Append to `DataTableRowExternalIdIntTest`:

```java
    @Test
    void testUpsertCreatesThenMerges() {
        UpsertResult first = dataTableRowService.upsertRow(REF, "ORD-1", Map.of("title", "a"));

        assertTrue(first.created());
        assertEquals("ORD-1", first.row()
            .externalId());

        UpsertResult second = dataTableRowService.upsertRow(REF, "ORD-1", Map.of("title", "b"));

        assertFalse(second.created());
        assertEquals(first.row()
            .id(),
            second.row()
                .id());
        assertEquals("b", second.row()
            .values()
            .get("title"));
        assertEquals(1, dataTableRowService.listRows(REF, 10, 0)
            .size());
    }

    @Test
    void testUpsertWithNoValuesReturnsTheExistingRowUnchanged() {
        dataTableRowService.upsertRow(REF, "ORD-1", Map.of("title", "a"));

        UpsertResult result = dataTableRowService.upsertRow(REF, "ORD-1", Map.of());

        assertFalse(result.created());
        assertEquals("a", result.row()
            .values()
            .get("title"));
    }
```

Append to `DataTableRowOwnerScopingIntTest` (follow its existing setup for two owners; the table there
is created by hand, so first alter it in the test: `ALTER TABLE ... ADD COLUMN "external_id" VARCHAR(255)`
plus the unique index from Task 2 — copy the SQL string from `buildExternalIdIndexSql`):

```java
    @Test
    void testUpsertCannotLandOnAnotherAccountsRow() {
        DataTableRef accountOne = ownedRef(1L);
        DataTableRef accountTwo = ownedRef(2L);

        dataTableRowService.upsertRow(accountOne, "ORD-1", Map.of("title", "one"));
        dataTableRowService.upsertRow(accountTwo, "ORD-1", Map.of("title", "two"));

        assertEquals("one", dataTableRowService.fetchRowByExternalId(accountOne, "ORD-1")
            .orElseThrow()
            .values()
            .get("title"));
        assertEquals("two", dataTableRowService.fetchRowByExternalId(accountTwo, "ORD-1")
            .orElseThrow()
            .values()
            .get("title"));
        assertEquals(2, countAllRows());
    }
```
(`ownedRef(long)` and `countAllRows()` are whatever that class already uses to build an owned
`DataTableRef` and count physical rows — reuse them; do not add a second way.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableRowExternalIdIntTest' --tests '*DataTableRowOwnerScopingIntTest' > /tmp/t5.log 2>&1; echo $?`
Expected: non-zero.

- [ ] **Step 3: Implement**

`UpsertResult` (new, `-api`):

```java
package com.bytechef.platform.data.table.execution.domain;

/**
 * The row an upsert left behind and whether it had to create it.
 *
 * @author Ivica Cardic
 */
public record UpsertResult(DataTableRow row, boolean created) {
}
```

`DataTableRowService`:

```java
    /**
     * Inserts the row keyed by {@code externalId} or merges {@code values} into the row that already carries it, in one
     * statement. The conflict target is the ownership index, so the owner the ref carries is part of the key: an
     * account can never upsert onto another account's row. Only the supplied columns are written on the update path.
     */
    UpsertResult upsertRow(DataTableRef dataTableRef, String externalId, Map<String, Object> values);
```

`DataTableRowServiceImpl`:

```java
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public UpsertResult upsertRow(DataTableRef dataTableRef, String externalId, Map<String, Object> values) {
        Assert.hasText(externalId, "externalId must not be empty");

        dataTableStorageService.checkWithinLimit(0);

        String physicalName = dataTableRef.physicalName();

        List<ColumnSpec> columnSpecs = requireColumns(physicalName);

        Assert.isTrue(hasExternalIdColumn(columnSpecs), "Table " + physicalName + " has no external_id column");

        List<String> allColumnNames = columnSpecs.stream()
            .map(ColumnSpec::name)
            .toList();
        List<String> userColumnNames = userColumnNames(columnSpecs);
        List<String> writtenColumnNames = resolveWritableColumnNames(values, allColumnNames);

        List<String> insertColumnNames = new ArrayList<>();

        insertColumnNames.add(ReservedColumns.EXTERNAL_ID);
        insertColumnNames.addAll(writtenColumnNames);

        Owner runOwner = dataTableRef.runOwner();

        if (runOwner != null) {
            insertColumnNames.add(ReservedColumns.OWNER_ID);
            insertColumnNames.add(ReservedColumns.OWNER_TYPE);
        }

        String columnsClause = insertColumnNames.stream()
            .map(this::escapeIdentifier)
            .collect(Collectors.joining(", "));
        String placeholders = insertColumnNames.stream()
            .map(columnName -> "?")
            .collect(Collectors.joining(", "));

        // With nothing to merge the DO UPDATE still has to write something, or Postgres returns no row for the
        // conflict case; re-writing the key is a no-op that keeps RETURNING populated.
        String updateClause = writtenColumnNames.isEmpty()
            ? escapeIdentifier(ReservedColumns.EXTERNAL_ID) + " = EXCLUDED." + escapeIdentifier(ReservedColumns.EXTERNAL_ID)
            : writtenColumnNames.stream()
                .map(columnName -> escapeIdentifier(columnName) + " = EXCLUDED." + escapeIdentifier(columnName))
                .collect(Collectors.joining(", "));

        String sql = "INSERT INTO " + escapeIdentifier(physicalName) + " (" + columnsClause + ") VALUES (" +
            placeholders + ") ON CONFLICT (" + escapeIdentifier(ReservedColumns.OWNER_ID) + ", " +
            escapeIdentifier(ReservedColumns.EXTERNAL_ID) + ") WHERE " + escapeIdentifier(ReservedColumns.EXTERNAL_ID) +
            " IS NOT NULL DO UPDATE SET " + updateClause + " RETURNING " + selectColumns(userColumnNames, true) +
            ", (xmax = 0) AS \"created\"";

        Map<String, ColumnType> typeMap = columnTypeMap(columnSpecs);

        UpsertResult upsertResult = jdbcTemplate.query(sql, ps -> {
            int i = 1;

            ps.setString(i++, externalId);

            for (String columnName : writtenColumnNames) {
                ColumnType columnType = typeMap.getOrDefault(columnName.toLowerCase(Locale.ROOT), ColumnType.STRING);

                setParam(ps, i++, columnType, coerceValue(columnType, getValueCaseInsensitive(values, columnName)));
            }

            if (runOwner != null) {
                ps.setLong(i++, runOwner.id());

                OwnerType ownerType = runOwner.type();

                ps.setInt(i, ownerType.ordinal());
            }
        }, resultSet -> {
            if (!resultSet.next()) {
                throw new IllegalStateException("Upsert returned no row");
            }

            return new UpsertResult(toRow(resultSet, userColumnNames, true), resultSet.getBoolean("created"));
        });

        Map<String, Object> payload = new HashMap<>();

        payload.put("id", upsertResult.row()
            .id());
        payload.put("values", upsertResult.row()
            .values());

        applicationEventPublisher.publishEvent(
            new DataTableWebhookEvent(
                dataTableRef,
                upsertResult.created() ? DataTableWebhookType.RECORD_CREATED : DataTableWebhookType.RECORD_UPDATED,
                payload));

        return upsertResult;
    }
```

`resolveWritableColumnNames(values, allColumnNames)` is the case-insensitive filter that `insertRow`
and `updateRow` both open-code today (the `values.keySet().stream().filter(...).map(...)` block) —
extract it once and use it from all three.

`xmax = 0` is true for a row the statement inserted and false for one it updated; it is the
documented PostgreSQL idiom for distinguishing the two on `ON CONFLICT DO UPDATE`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration > /tmp/t5.log 2>&1; echo $?`
Expected: 0.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-data-table
git commit -m "--- Add upsertRow keyed on the data table ownership index

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: `countRows`, `deleteRows`, `clearRows`, `insertRows`, and a counting, strict `importCsv`

**Files:**
- Create: `.../platform-data-table-api/.../execution/domain/NewRow.java`, `CreateStrategy.java`
- Modify: `DataTableRowService.java`, `DataTableRowServiceImpl.java`
- Modify: every `importCsv` caller (`WorkspaceDataTableFacadeImpl`, its interface, `DataTableRowGraphQlController`) — the return type changes to `int`
- Test: `.../platform-data-table-service/src/test/java/com/bytechef/platform/data/table/execution/service/DataTableRowBatchIntTest.java`

**Interfaces:**
- Produces:
  - `record NewRow(Map<String, Object> values, @Nullable String externalId)`
  - `enum CreateStrategy { INSERT, UPSERT }`
  - `long countRows(DataTableRef, List<RowFilter>)`
  - `List<Long> deleteRows(DataTableRef, List<Long> ids)` — the ids actually deleted
  - `long clearRows(DataTableRef)`
  - `List<DataTableRow> insertRows(DataTableRef, List<NewRow>, CreateStrategy)` — one transaction
  - `int importCsv(DataTableRef, String csv)` — rows inserted; an unknown header column throws `DataTableException(CSV_INVALID)`
  - `UPSERT` with a null `externalId` throws `DataTableException(ROW_EXTERNAL_ID_REQUIRED)`

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteDataTableRowServiceClient`
in ``server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableRowServiceClient.java``
implements `DataTableRowService` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteWorkspaceDataTableFacadeClient`
in ``server/ee/libs/automation/automation-data-table/automation-data-table-remote-client/src/main/java/com/bytechef/ee/automation/data/table/remote/client/facade/RemoteWorkspaceDataTableFacadeClient.java``
implements `WorkspaceDataTableFacade` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

`importCsv`'s return type changes from `void` to `int` in BOTH remote clients as well as in the
interfaces and `DataTableRowGraphQlController`.

The strict CSV header is a visible change for the UI's import (it used to skip unknown columns
silently). The spec asks for it; the plan records it here so the release note can name it.

- [ ] **Step 1: Failing IntTest**

```java
package com.bytechef.platform.data.table.execution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.exception.DataTableErrorType;
import com.bytechef.platform.data.table.configuration.exception.DataTableException;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.RowFilter;
import com.bytechef.platform.data.table.execution.domain.CreateStrategy;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.domain.NewRow;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableRowBatchIntTest {

    private static final long ENVIRONMENT_ID = 0;
    private static final DataTableRef REF = DataTableRef.unowned("batched", ENVIRONMENT_ID, PlatformType.AUTOMATION);

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @BeforeEach
    void beforeEach() {
        dataTableService.dropTable("batched", ENVIRONMENT_ID, PlatformType.AUTOMATION);

        dataTableService.createTable(
            "batched", null,
            List.of(new ColumnSpec("title", ColumnType.STRING), new ColumnSpec("score", ColumnType.INTEGER)),
            ENVIRONMENT_ID, PlatformType.AUTOMATION);
    }

    @Test
    void testCountRowsHonoursFilters() {
        dataTableRowService.insertRow(REF, Map.of("title", "a", "score", 1));
        dataTableRowService.insertRow(REF, Map.of("title", "b", "score", 5));

        assertEquals(2, dataTableRowService.countRows(REF, List.of()));
        assertEquals(1, dataTableRowService.countRows(REF, List.of(new RowFilter("score", RowFilter.Operator.GT, "2"))));
    }

    @Test
    void testInsertRowsInsertsAll() {
        List<DataTableRow> rows = dataTableRowService.insertRows(
            REF, List.of(new NewRow(Map.of("title", "a"), null), new NewRow(Map.of("title", "b"), "k2")),
            CreateStrategy.INSERT);

        assertEquals(2, rows.size());
        assertEquals("k2", rows.get(1)
            .externalId());
        assertEquals(2, dataTableRowService.countRows(REF, List.of()));
    }

    @Test
    void testInsertRowsIsAllOrNothing() {
        dataTableRowService.insertRow(REF, Map.of("title", "taken"), "k1");

        assertThrows(
            DataTableException.class,
            () -> dataTableRowService.insertRows(
                REF, List.of(new NewRow(Map.of("title", "new"), null), new NewRow(Map.of("title", "dup"), "k1")),
                CreateStrategy.INSERT));

        assertEquals(1, dataTableRowService.countRows(REF, List.of()));
    }

    @Test
    void testUpsertStrategyRequiresAKeyOnEveryRow() {
        DataTableException dataTableException = assertThrows(
            DataTableException.class,
            () -> dataTableRowService.insertRows(
                REF, List.of(new NewRow(Map.of("title", "a"), null)), CreateStrategy.UPSERT));

        assertEquals(DataTableErrorType.ROW_EXTERNAL_ID_REQUIRED.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testUpsertStrategyMergesExistingKeys() {
        dataTableRowService.insertRow(REF, Map.of("title", "old"), "k1");

        dataTableRowService.insertRows(
            REF, List.of(new NewRow(Map.of("title", "new"), "k1"), new NewRow(Map.of("title", "b"), "k2")),
            CreateStrategy.UPSERT);

        assertEquals(2, dataTableRowService.countRows(REF, List.of()));
        assertEquals("new", dataTableRowService.fetchRowByExternalId(REF, "k1")
            .orElseThrow()
            .values()
            .get("title"));
    }

    @Test
    void testDeleteRowsReturnsOnlyWhatItDeleted() {
        DataTableRow first = dataTableRowService.insertRow(REF, Map.of("title", "a"));
        DataTableRow second = dataTableRowService.insertRow(REF, Map.of("title", "b"));

        List<Long> deletedIds = dataTableRowService.deleteRows(REF, List.of(first.id(), second.id(), 999_999L));

        assertEquals(List.of(first.id(), second.id()), deletedIds);
        assertEquals(0, dataTableRowService.countRows(REF, List.of()));
    }

    @Test
    void testClearRowsEmptiesTheTable() {
        dataTableRowService.insertRow(REF, Map.of("title", "a"));
        dataTableRowService.insertRow(REF, Map.of("title", "b"));

        assertEquals(2, dataTableRowService.clearRows(REF));
        assertEquals(0, dataTableRowService.countRows(REF, List.of()));
    }

    @Test
    void testImportCsvCountsAndHonoursExternalId() {
        int imported = dataTableRowService.importCsv(REF, "title,score,external_id\na,1,k1\nb,2,\n");

        assertEquals(2, imported);
        assertEquals("a", dataTableRowService.fetchRowByExternalId(REF, "k1")
            .orElseThrow()
            .values()
            .get("title"));
    }

    @Test
    void testImportCsvRejectsAnUnknownHeader() {
        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> dataTableRowService.importCsv(REF, "title,nosuch\na,b\n"));

        assertEquals(DataTableErrorType.CSV_INVALID.getErrorKey(), dataTableException.getErrorKey());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableRowBatchIntTest' > /tmp/t6.log 2>&1; echo $?`
Expected: non-zero.

- [ ] **Step 3: Value types and interface**

`NewRow` and `CreateStrategy` in `execution/domain` (Apache header, one-line Javadoc each):

```java
public record NewRow(Map<String, Object> values, @Nullable String externalId) {
}

public enum CreateStrategy {
    INSERT, UPSERT
}
```

`DataTableRowService` additions (and change `void importCsv` → `int importCsv`):

```java
    long countRows(DataTableRef dataTableRef, List<RowFilter> rowFilters);

    /** Deletes the rows among {@code ids} the ref may write; returns the ids that were actually deleted. */
    List<Long> deleteRows(DataTableRef dataTableRef, List<Long> ids);

    /** Deletes every row the ref may write; returns the count. */
    long clearRows(DataTableRef dataTableRef);

    /**
     * Inserts (or, under {@link CreateStrategy#UPSERT}, upserts) every row in one transaction: one failure rolls back
     * all of them. UPSERT requires an external id on every row.
     */
    List<DataTableRow> insertRows(DataTableRef dataTableRef, List<NewRow> newRows, CreateStrategy createStrategy);
```

- [ ] **Step 4: Implement in `DataTableRowServiceImpl`**

```java
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public long countRows(DataTableRef dataTableRef, List<RowFilter> rowFilters) {
        String physicalName = dataTableRef.physicalName();

        List<ColumnSpec> columnSpecs = requireColumns(physicalName);
        Map<String, ColumnType> columnTypes = columnTypeMap(columnSpecs);

        RowQuerySqlBuilder.Fragment fragment = RowQuerySqlBuilder.filters(rowFilters, columnTypes);

        String sql = "SELECT COUNT(*) FROM " + escapeIdentifier(physicalName) + " WHERE TRUE" +
            RowQuerySqlBuilder.readableOwnerPredicate(dataTableRef) + fragment.sql();

        Long count = jdbcTemplate.query(sql, ps -> {
            int index = RowQuerySqlBuilder.bindOwner(ps, 1, dataTableRef);

            for (RowQuerySqlBuilder.Binding binding : fragment.bindings()) {
                setParam(ps, index++, binding.type(), binding.value());
            }
        }, resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L);

        return count == null ? 0L : count;
    }

    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public List<Long> deleteRows(DataTableRef dataTableRef, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        String physicalName = dataTableRef.physicalName();

        requireColumns(physicalName);

        String sql = "DELETE FROM " + escapeIdentifier(physicalName) + " WHERE \"id\" = ANY(?)" +
            RowQuerySqlBuilder.writableOwnerPredicate(dataTableRef) + " RETURNING \"id\"";

        List<Long> deletedIds = jdbcTemplate.query(sql, ps -> {
            ps.setArray(1, ps.getConnection()
                .createArrayOf("bigint", ids.toArray()));

            RowQuerySqlBuilder.bindOwner(ps, 2, dataTableRef);
        }, (resultSet, rowNum) -> resultSet.getLong("id"));

        for (Long deletedId : deletedIds) {
            publishDeleted(dataTableRef, deletedId);
        }

        return deletedIds;
    }

    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public long clearRows(DataTableRef dataTableRef) {
        String physicalName = dataTableRef.physicalName();

        requireColumns(physicalName);

        String sql = "DELETE FROM " + escapeIdentifier(physicalName) + " WHERE TRUE" +
            RowQuerySqlBuilder.writableOwnerPredicate(dataTableRef) + " RETURNING \"id\"";

        List<Long> deletedIds = jdbcTemplate.query(
            sql, ps -> RowQuerySqlBuilder.bindOwner(ps, 1, dataTableRef), (resultSet, rowNum) -> resultSet.getLong("id"));

        for (Long deletedId : deletedIds) {
            publishDeleted(dataTableRef, deletedId);
        }

        return deletedIds.size();
    }

    @Override
    @Transactional
    public List<DataTableRow> insertRows(DataTableRef dataTableRef, List<NewRow> newRows, CreateStrategy createStrategy) {
        if (createStrategy == CreateStrategy.UPSERT) {
            boolean missingKey = newRows.stream()
                .anyMatch(newRow -> newRow.externalId() == null || newRow.externalId()
                    .isBlank());

            if (missingKey) {
                throw new DataTableException(
                    "Every row needs an externalId under the UPSERT strategy", DataTableErrorType.ROW_EXTERNAL_ID_REQUIRED);
            }
        }

        List<DataTableRow> dataTableRows = new ArrayList<>(newRows.size());

        for (NewRow newRow : newRows) {
            if (createStrategy == CreateStrategy.UPSERT) {
                UpsertResult upsertResult = upsertRow(dataTableRef, newRow.externalId(), newRow.values());

                dataTableRows.add(upsertResult.row());
            } else {
                dataTableRows.add(insertRow(dataTableRef, newRow.values(), newRow.externalId()));
            }
        }

        return dataTableRows;
    }

    private void publishDeleted(DataTableRef dataTableRef, long id) {
        Map<String, Object> payload = new HashMap<>();

        payload.put("id", id);

        applicationEventPublisher.publishEvent(
            new DataTableWebhookEvent(dataTableRef, DataTableWebhookType.RECORD_DELETED, payload));
    }
```

Refactor the existing `deleteRow` to call `publishDeleted`. `@Transactional` needs
`org.springframework.transaction.annotation.Transactional`; the module already depends on
`spring-tx` transitively through Spring Data JDBC — if compile says otherwise, add
`implementation("org.springframework:spring-tx")` to the module's `build.gradle.kts`.

`importCsv`: change the signature to `int`, keep a counter incremented after each `insertRow`, return
it at the end (and `return 0` where it currently returns early). In the header-mapping loop, an
unknown, non-reserved header now throws:

```java
                        if (columnName == null) {
                            throw new DataTableException(
                                "CSV header '" + header + "' is not a column of this table", DataTableErrorType.CSV_INVALID);
                        }
```
and a header equal (case-insensitively) to `external_id` maps to a sentinel so the row loop passes it
as the external id:

```java
                        if (ReservedColumns.EXTERNAL_ID.equalsIgnoreCase(header)) {
                            mappedColumnNames.add(ReservedColumns.EXTERNAL_ID);

                            continue;
                        }
```
(place this before the generic `isReserved` check, which keeps skipping `id`/`owner_*`). In the row
loop, pull the external id out instead of putting it into `values`:

```java
                String externalId = null;

                for (int col = 0; col < limit; col++) {
                    String curColumnName = mappedColumnNames.get(col);

                    if (curColumnName == null) {
                        continue;
                    }

                    String field = fields.get(col);

                    if (ReservedColumns.EXTERNAL_ID.equals(curColumnName)) {
                        externalId = (field == null || field.isBlank()) ? null : field.trim();

                        continue;
                    }

                    values.put(curColumnName, (field == null || field.isEmpty()) ? null : field);
                }

                insertRow(dataTableRef, values, externalId);

                imported++;
```

`exportCsv`: when `hasExternalIdColumn(columnSpecs)`, write `external_id` as the **first** header and
`dataTableRow.externalId()` (empty string for null) as the first field of every row.

Update `WorkspaceDataTableFacade.importCsv` and its impl to return the `int`;
`DataTableRowGraphQlController.importDataTableCsv` keeps returning `true` (discard the count).

- [ ] **Step 5: Run the tests**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration :server:libs:platform:platform-data-table:platform-data-table-service:test :server:libs:automation:automation-data-table:automation-data-table-service:test :server:libs:automation:automation-data-table:automation-data-table-graphql:test > /tmp/t6.log 2>&1; echo $?`
Expected: 0.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/platform/platform-data-table server/libs/automation/automation-data-table
git commit -m "--- Add row counting, batch insert and delete, clear and a counting strict CSV import

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: `DataTableService` — typed DDL errors, `updateDescription`, `fetchDataTableInfo`

**Files:**
- Modify: `DataTableService.java`, `DataTableServiceImpl.java`
- Modify: `.../platform-data-table-service/src/test/java/.../configuration/service/DataTableServiceImplBaseNameTest.java` (expected exception type changes)
- Test: `.../configuration/service/DataTableServiceIntTest.java` (extend)

**Interfaces:**
- Produces:
  - `void updateDescription(String baseName, @Nullable String description, PlatformType platformType)`
  - `Optional<DataTableInfo> fetchDataTableInfo(String baseName, long environmentId, PlatformType platformType)` — empty when the registry row or **this environment's physical table** is missing
  - `createTable` throws `DATA_TABLE_ALREADY_EXISTS` when the physical table exists in this environment, `DATA_TABLE_NAME_INVALID` for a bad name
  - `addColumn` throws `COLUMN_NAME_INVALID` (reserved or bad pattern) / `COLUMN_ALREADY_EXISTS`
  - `removeColumn` / `renameColumn` throw `COLUMN_NOT_FOUND`; `renameColumn` also `COLUMN_NAME_INVALID` / `COLUMN_ALREADY_EXISTS`
  - `validateBaseName` throws `DataTableException(DATA_TABLE_NAME_INVALID)` instead of `IllegalArgumentException`

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteDataTableServiceClient`
in ``server/ee/libs/platform/platform-data-table/platform-data-table-remote-client/src/main/java/com/bytechef/ee/platform/data/table/remote/client/service/RemoteDataTableServiceClient.java``
implements `DataTableService` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

**Existing test this task breaks (controller ruling R2).** `DataTableServiceIntTest` lines 80-82
currently read:

```java
        assertThrowsExactly(
            BadSqlGrammarException.class, () -> createTable("registered", "a description", DEV_ENVIRONMENT_ID),
            "The same name twice in one environment is the physical table colliding, not a registry decision");
```

Your new `physicalTableExists` check fires before the SQL does, so BOTH the exception type and that
message become wrong. Replace them with:

```java
        DataTableException dataTableException = assertThrowsExactly(
            DataTableException.class, () -> createTable("registered", "a description", DEV_ENVIRONMENT_ID),
            "The same name twice in one environment is now a registry decision, not the physical table colliding");

        assertEquals(DataTableErrorType.DATA_TABLE_ALREADY_EXISTS.getErrorKey(), dataTableException.getErrorKey());
```

Remove the now-unused `BadSqlGrammarException` import.

- [ ] **Step 1: Failing tests** — append to `DataTableServiceIntTest`:

```java
    @Test
    void testCreateTwiceInOneEnvironmentIsATypedConflict() {
        createTable("registered", "a description", DEV_ENVIRONMENT_ID);

        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> createTable("registered", "a description", DEV_ENVIRONMENT_ID));

        assertEquals(DataTableErrorType.DATA_TABLE_ALREADY_EXISTS.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testFetchDataTableInfoIsPerEnvironment() {
        createTable("registered", "a description", DEV_ENVIRONMENT_ID);

        Optional<DataTableInfo> dev = dataTableService.fetchDataTableInfo(
            "registered", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION);
        Optional<DataTableInfo> stage = dataTableService.fetchDataTableInfo(
            "registered", STAGE_ENVIRONMENT_ID, PlatformType.AUTOMATION);

        assertTrue(dev.isPresent());
        assertEquals("a description", dev.get()
            .description());
        assertTrue(dev.get()
            .columns()
            .stream()
            .noneMatch(columnSpec -> ReservedColumns.isReserved(columnSpec.name())));
        assertTrue(stage.isEmpty());
    }

    @Test
    void testUpdateDescriptionWritesTheRegistry() {
        createTable("registered", "before", DEV_ENVIRONMENT_ID);

        dataTableService.updateDescription("registered", "after", PlatformType.AUTOMATION);

        assertEquals("after", dataTableService.fetchDataTableInfo("registered", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION)
            .orElseThrow()
            .description());
    }

    @Test
    void testColumnErrorsAreTyped() {
        createTable("registered", null, DEV_ENVIRONMENT_ID);

        assertEquals(
            DataTableErrorType.COLUMN_ALREADY_EXISTS.getErrorKey(),
            assertThrows(DataTableException.class, () -> dataTableService.addColumn(
                "registered", new ColumnSpec("title", ColumnType.STRING), DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION))
                    .getErrorKey());
        assertEquals(
            DataTableErrorType.COLUMN_NAME_INVALID.getErrorKey(),
            assertThrows(DataTableException.class, () -> dataTableService.addColumn(
                "registered", new ColumnSpec("external_id", ColumnType.STRING), DEV_ENVIRONMENT_ID,
                PlatformType.AUTOMATION))
                    .getErrorKey());
        assertEquals(
            DataTableErrorType.COLUMN_NOT_FOUND.getErrorKey(),
            assertThrows(DataTableException.class, () -> dataTableService.removeColumn(
                "registered", "nosuch", DEV_ENVIRONMENT_ID, PlatformType.AUTOMATION))
                    .getErrorKey());
    }
```
(`createTable(name, description, env)` is the test's existing helper; it creates a `title` STRING
column — read it before writing the assertions above and adjust the column name if it differs.)

In `DataTableServiceImplBaseNameTest`, change every `assertThrows(IllegalArgumentException.class, …)`
on `validateBaseName` to `assertThrows(DataTableException.class, …)`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test --tests '*DataTableServiceImplBaseNameTest' > /tmp/t7.log 2>&1; echo $?` → non-zero.

- [ ] **Step 3: Implement**

`DataTableService` additions:

```java
    /** Writes the registry description; environment-independent because the registry row is the logical table. */
    void updateDescription(String baseName, @Nullable String description, PlatformType platformType);

    /**
     * The table as it exists in one environment: registry metadata plus the physical table's user columns. Empty when
     * either half is missing -- a registry row alone is a table that lives in some other environment.
     */
    Optional<DataTableInfo> fetchDataTableInfo(String baseName, long environmentId, PlatformType platformType);
```

`DataTableServiceImpl`:

```java
    static void validateBaseName(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            throw new DataTableException("baseName must not be empty", DataTableErrorType.DATA_TABLE_NAME_INVALID);
        }

        String normalized = baseName.toLowerCase(Locale.ROOT);

        if (normalized.startsWith("dt_") || !normalized.matches("[a-z_][a-z0-9_]*")) {
            throw new DataTableException("Invalid base name: " + baseName, DataTableErrorType.DATA_TABLE_NAME_INVALID);
        }
    }

    private static void validateColumnName(String columnName) {
        if (columnName == null || columnName.isBlank() || !columnName.toLowerCase(Locale.ROOT)
            .matches("[a-z_][a-z0-9_]*")) {

            throw new DataTableException("Invalid column name: " + columnName, DataTableErrorType.COLUMN_NAME_INVALID);
        }

        if (ReservedColumns.isReserved(columnName)) {
            throw new DataTableException(
                "Column name '" + columnName + "' is reserved", DataTableErrorType.COLUMN_NAME_INVALID);
        }
    }

    private boolean physicalTableExists(String physicalName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
            Integer.class, physicalName);

        return count != null && count > 0;
    }

    private boolean hasColumn(String physicalName, String columnName) {
        return listColumns(physicalName).stream()
            .anyMatch(columnSpec -> columnSpec.name()
                .equalsIgnoreCase(columnName));
    }

    @Override
    @Transactional
    public void updateDescription(String baseName, @Nullable String description, PlatformType platformType) {
        DataTable dataTable = getDataTable(baseName, platformType);

        dataTable.setDescription(description);

        dataTableRepository.save(dataTable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataTableInfo> fetchDataTableInfo(String baseName, long environmentId, PlatformType platformType) {
        validateBaseName(baseName);

        Optional<DataTable> dataTableOptional = fetchDataTable(baseName, platformType);

        if (dataTableOptional.isEmpty()) {
            return Optional.empty();
        }

        DataTable dataTable = dataTableOptional.get();
        DataTableRef dataTableRef = DataTableRef.unowned(baseName, environmentId, platformType);
        String physicalName = dataTableRef.physicalName();

        if (!physicalTableExists(physicalName)) {
            return Optional.empty();
        }

        List<ColumnSpec> columnSpecs = listColumns(physicalName).stream()
            .filter(columnSpec -> !ReservedColumns.isReserved(columnSpec.name()))
            .toList();

        return Optional.of(
            new DataTableInfo(
                dataTable.getId(), dataTable.getName(), dataTable.getDescription(), columnSpecs,
                dataTable.getLastModifiedDate()));
    }
```

In `createTable`, after `validateBaseName` and the reserved-column check, validate every column name
with `validateColumnName` and then:

```java
        if (physicalTableExists(dataTableRef.physicalName())) {
            throw new DataTableException(
                "Data table '" + baseName + "' already exists in this environment",
                DataTableErrorType.DATA_TABLE_ALREADY_EXISTS);
        }
```
(build `dataTableRef` before this check). In `addColumn`: `validateColumnName(columnSpec.name())` and
a `hasColumn` → `COLUMN_ALREADY_EXISTS` check before the `ALTER`. In `removeColumn`:
`validateColumnName(columnName)` then `!hasColumn` → `COLUMN_NOT_FOUND`. In `renameColumn`: replace
the two `Assert.isTrue(... reserved ...)` lines with `validateColumnName(fromColumnName)` /
`validateColumnName(toColumnName)`, then `!hasColumn(from)` → `COLUMN_NOT_FOUND` and `hasColumn(to)` →
`COLUMN_ALREADY_EXISTS`.

`DataTableInfo`'s existing five-field constructor is used as-is. Check that `listColumns` in
`DataTableServiceImpl` maps `character varying` to `STRING` — it does via the `default` branch.

- [ ] **Step 4: Run the suites**

Run: `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:test :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration > /tmp/t7.log 2>&1; echo $?` → 0.
Then `./gradlew compileJava compileTestJava --continue > /tmp/t7c.log 2>&1; echo $?` → 0 (the
component module's tests may assert `IllegalArgumentException` on bad names — fix any that fail to
expect `DataTableException`).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs
git commit -m "--- Type the data table DDL errors and add updateDescription and fetchDataTableInfo

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: The guarded facade methods

**Files:**
- Modify: `server/libs/automation/automation-data-table/automation-data-table-api/src/main/java/com/bytechef/automation/data/table/configuration/facade/WorkspaceDataTableFacade.java`
- Modify: `server/libs/automation/automation-data-table/automation-data-table-api/build.gradle.kts` (add `api("org.springframework.data:spring-data-commons")` if `Page` does not resolve)
- Modify: `server/libs/automation/automation-data-table/automation-data-table-service/src/main/java/com/bytechef/automation/data/table/configuration/facade/WorkspaceDataTableFacadeImpl.java`
- Test: `.../automation-data-table-service/src/test/java/.../facade/WorkspaceDataTableFacadeAuthorizationTest.java` (extend)
- Test: `.../facade/WorkspaceDataTableFacadeRowsTest.java` (new, Mockito)

**Interfaces:**
- Produces (all on `WorkspaceDataTableFacade`; every `dataTableId`-taking method is guarded
  `DataTable:DATA_TABLE_VIEW` for reads and `DataTable:DATA_TABLE_EDIT` for writes, exactly like their
  neighbours):

```java
    DataTableInfo getTable(long dataTableId, long environmentId);              // throws DATA_TABLE_NOT_FOUND
    void updateDescription(long dataTableId, @Nullable String description);
    Page<DataTableRow> listRows(
        long dataTableId, List<RowFilter> rowFilters, List<RowSort> rowSorts, int pageNumber, int pageSize,
        long environmentId);
    DataTableRow getRow(long dataTableId, long rowId, long environmentId);       // throws ROW_NOT_FOUND
    Optional<DataTableRow> fetchRowByExternalId(long dataTableId, String externalId, long environmentId);
    DataTableRow insertRow(long dataTableId, Map<String, Object> values, @Nullable String externalId, long environmentId);
    DataTableRow updateRow(
        long dataTableId, long rowId, Map<String, Object> values, @Nullable ExternalIdPatch externalIdPatch,
        long environmentId);
    UpsertResult upsertRow(long dataTableId, String externalId, Map<String, Object> values, long environmentId);
    boolean deleteRowByExternalId(long dataTableId, String externalId, long environmentId);
    List<DataTableRow> insertRows(long dataTableId, List<NewRow> newRows, CreateStrategy createStrategy, long environmentId);
    List<Long> deleteRows(long dataTableId, List<Long> rowIds, long environmentId);
    long clearRows(long dataTableId, long environmentId);
    int importCsv(long dataTableId, String csv, long environmentId);            // already changed in Task 6
    Map<Long, List<Tag>> getTagsByTableId(long workspaceId);   // Workspace:DATA_TABLE_VIEW
    long getWorkspaceId(long dataTableId);                     // DataTable:DATA_TABLE_VIEW
```

The last two are controller ruling R3 — the plan first put them in Task 11, but Task 8 owns the
facade surface. `getTagsByTableId` collects the workspace's table ids exactly as `listTables` does,
then joins `dataTableTagService.getTagsByTableName()` through `dataTableService.getBaseNameById`.
`getWorkspaceId` wraps `workspaceDataTableService.fetchWorkspaceId(dataTableId)` and throws
`DataTableException(DATA_TABLE_NOT_FOUND)` when empty. Both need `assertExpression` lines too.

**Remote-client stubs (controller ruling R1 — the plan originally omitted this).** `RemoteWorkspaceDataTableFacadeClient`
in ``server/ee/libs/automation/automation-data-table/automation-data-table-remote-client/src/main/java/com/bytechef/ee/automation/data/table/remote/client/facade/RemoteWorkspaceDataTableFacadeClient.java``
implements `WorkspaceDataTableFacade` and `worker-app` compiles against it. Every method you add to that interface
needs a matching stub there in the same commit:

```java
    @Override
    public <signature> {
        throw new UnsupportedOperationException();
    }
```

That file contains nothing else — `@Override` plus the throw, no imports beyond the signature's
types. Omitting a stub breaks `worker-app`'s compile.

- [ ] **Step 1: Failing authorization tests** — append to `WorkspaceDataTableFacadeAuthorizationTest`
  one `assertExpression` per new method: `getTable`, `listRows` (already present — the overload shares
  the name, and the helper picks the first declared method, so the annotation must be on both),
  `getRow`, `fetchRowByExternalId` → `DATA_TABLE_VIEW`; `updateDescription`, `insertRow`, `updateRow`,
  `upsertRow`, `deleteRowByExternalId`, `insertRows`, `deleteRows`, `clearRows` → `DATA_TABLE_EDIT`.

- [ ] **Step 2: Failing behaviour test** (`WorkspaceDataTableFacadeRowsTest`, same Mockito shape as
  `WorkspaceDataTableFacadePoolTest`):

```java
    @Test
    void testListRowsBuildsAPageFromRowsAndCount() {
        when(dataTableService.getBaseNameById(7L)).thenReturn("orders");
        when(dataTableRowService.listRows(any(), eq(50), eq(100), eq(List.of()), eq(List.of())))
            .thenReturn(List.of(new DataTableRow(1L, Map.of())));
        when(dataTableRowService.countRows(any(), eq(List.of()))).thenReturn(151L);

        Page<DataTableRow> page = workspaceDataTableFacade.listRows(7L, List.of(), List.of(), 2, 50, ENVIRONMENT_ID);

        assertEquals(151L, page.getTotalElements());
        assertEquals(2, page.getNumber());
        assertEquals(1, page.getContent()
            .size());
    }

    @Test
    void testGetRowTranslatesMissingIntoATypedNotFound() {
        when(dataTableService.getBaseNameById(7L)).thenReturn("orders");
        when(dataTableRowService.getRow(any(), eq(9L))).thenReturn(null);

        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> workspaceDataTableFacade.getRow(7L, 9L, ENVIRONMENT_ID));

        assertEquals(DataTableErrorType.ROW_NOT_FOUND.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testGetTableTranslatesMissingIntoATypedNotFound() {
        when(dataTableService.getBaseNameById(7L)).thenReturn("orders");
        when(dataTableService.fetchDataTableInfo("orders", ENVIRONMENT_ID, PlatformType.AUTOMATION))
            .thenReturn(Optional.empty());

        assertThrows(DataTableException.class, () -> workspaceDataTableFacade.getTable(7L, ENVIRONMENT_ID));
    }

    @Test
    void testEveryRowMethodUsesAnUnownedAutomationRef() {
        when(dataTableService.getBaseNameById(7L)).thenReturn("orders");

        workspaceDataTableFacade.clearRows(7L, ENVIRONMENT_ID);

        ArgumentCaptor<DataTableRef> captor = ArgumentCaptor.forClass(DataTableRef.class);

        verify(dataTableRowService).clearRows(captor.capture());

        assertNull(captor.getValue()
            .runOwner());
        assertEquals(PlatformType.AUTOMATION, captor.getValue()
            .platformType());
    }
```

- [ ] **Step 3: Run both to verify failure**

Run: `./gradlew :server:libs:automation:automation-data-table:automation-data-table-service:test > /tmp/t8.log 2>&1; echo $?` → non-zero.

- [ ] **Step 4: Implement**

Interface: add the signatures above with one-line Javadoc each. Impl — every method follows the
existing pattern (`@Override`, guard, delegate through `dataTableRef(dataTableId, environmentId)`):

```java
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public DataTableInfo getTable(long dataTableId, long environmentId) {
        String baseName = dataTableService.getBaseNameById(dataTableId);

        return dataTableService.fetchDataTableInfo(baseName, environmentId, PlatformType.AUTOMATION)
            .orElseThrow(() -> new DataTableException(
                "Data table '" + baseName + "' does not exist in this environment",
                DataTableErrorType.DATA_TABLE_NOT_FOUND));
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public void updateDescription(long dataTableId, @Nullable String description) {
        dataTableService.updateDescription(
            dataTableService.getBaseNameById(dataTableId), description, PlatformType.AUTOMATION);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public Page<DataTableRow> listRows(
        long dataTableId, List<RowFilter> rowFilters, List<RowSort> rowSorts, int pageNumber, int pageSize,
        long environmentId) {

        DataTableRef dataTableRef = dataTableRef(dataTableId, environmentId);

        List<DataTableRow> dataTableRows = dataTableRowService.listRows(
            dataTableRef, pageSize, pageNumber * pageSize, rowFilters, rowSorts);
        long total = dataTableRowService.countRows(dataTableRef, rowFilters);

        return new PageImpl<>(dataTableRows, PageRequest.of(pageNumber, pageSize), total);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public DataTableRow getRow(long dataTableId, long rowId, long environmentId) {
        DataTableRow dataTableRow = dataTableRowService.getRow(dataTableRef(dataTableId, environmentId), rowId);

        if (dataTableRow == null) {
            throw new DataTableException("Row not found: id=" + rowId, DataTableErrorType.ROW_NOT_FOUND);
        }

        return dataTableRow;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_VIEW')")
    public Optional<DataTableRow> fetchRowByExternalId(long dataTableId, String externalId, long environmentId) {
        return dataTableRowService.fetchRowByExternalId(dataTableRef(dataTableId, environmentId), externalId);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public DataTableRow insertRow(
        long dataTableId, Map<String, Object> values, @Nullable String externalId, long environmentId) {

        return dataTableRowService.insertRow(dataTableRef(dataTableId, environmentId), values, externalId);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public DataTableRow updateRow(
        long dataTableId, long rowId, Map<String, Object> values, @Nullable ExternalIdPatch externalIdPatch,
        long environmentId) {

        return dataTableRowService.updateRow(dataTableRef(dataTableId, environmentId), rowId, values, externalIdPatch);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public UpsertResult upsertRow(long dataTableId, String externalId, Map<String, Object> values, long environmentId) {
        return dataTableRowService.upsertRow(dataTableRef(dataTableId, environmentId), externalId, values);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public boolean deleteRowByExternalId(long dataTableId, String externalId, long environmentId) {
        DataTableRef dataTableRef = dataTableRef(dataTableId, environmentId);

        return dataTableRowService.fetchRowByExternalId(dataTableRef, externalId)
            .map(dataTableRow -> dataTableRowService.deleteRow(dataTableRef, dataTableRow.id()))
            .orElse(false);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public List<DataTableRow> insertRows(
        long dataTableId, List<NewRow> newRows, CreateStrategy createStrategy, long environmentId) {

        return dataTableRowService.insertRows(dataTableRef(dataTableId, environmentId), newRows, createStrategy);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public List<Long> deleteRows(long dataTableId, List<Long> rowIds, long environmentId) {
        return dataTableRowService.deleteRows(dataTableRef(dataTableId, environmentId), rowIds);
    }

    @Override
    @PreAuthorize("hasPermission(#dataTableId, 'DataTable', 'DATA_TABLE_EDIT')")
    public long clearRows(long dataTableId, long environmentId) {
        return dataTableRowService.clearRows(dataTableRef(dataTableId, environmentId));
    }
```

`Page`/`PageImpl`/`PageRequest` are `org.springframework.data.domain`. The class is already
`@Transactional`, so `insertRows` runs in one transaction here as well as in the service.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :server:libs:automation:automation-data-table:automation-data-table-service:test :server:libs:automation:automation-data-table:automation-data-table-api:compileJava > /tmp/t8.log 2>&1; echo $?` → 0.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/libs/automation/automation-data-table
git commit -m "--- Add the guarded facade methods the public data table API delegates to

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: The public-rest module and its `openapi.yaml`

**Files:**
- Create: `server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/build.gradle.kts`
- Create: `server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/openapi.yaml`
- Create: `.../automation-data-table-public-rest/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — **not needed**: the module's controllers are plain `@RestController`s found by `server-app`'s component scan of `com.bytechef`, like `WorkflowExecutionApiController`. Do not add one.
- Modify: `settings.gradle.kts` (add `include("server:ee:libs:automation:automation-data-table:automation-data-table-public-rest")` beside line 673's neighbour), `server/apps/server-app/build.gradle.kts` (add `implementation(project(":server:ee:libs:automation:automation-data-table:automation-data-table-public-rest"))` beside line 339)
- Generated (committed): `.../automation-data-table-public-rest/generated/src/main/java/com/bytechef/ee/automation/data/table/public_/web/rest/{DataTableApi,DataTableRowApi}.java` and `.../model/*Model.java`

**Interfaces:**
- Produces the generated interfaces `DataTableApi` (tag `data-table`) and `DataTableRowApi` (tag
  `data-table-row`) with these operationIds → Java methods: `listDataTables`, `createDataTable`,
  `getDataTable`, `updateDataTable`, `deleteDataTable`, `createColumn`, `deleteColumn`, `renameColumn`;
  `listRows`, `createRow`, `getRow`, `updateRow`, `deleteRow`, `getRowByExternalId`,
  `upsertRowByExternalId`, `deleteRowByExternalId`, `batchRows`, `deleteRows`, `clearRows`,
  `importRows`, `exportRows`. Models carry the `Model` suffix (`DataTableModel`, `DataTableRowModel`,
  `CreateDataTableRequestModel`, …); `Page` maps to `org.springframework.data.domain.Page`.

- [ ] **Step 1: `build.gradle.kts`** — copy `automation-configuration-public-rest/build.gradle.kts`
  and change: `apiPackage` → `com.bytechef.ee.automation.data.table.public_.web.rest`, `modelPackage` →
  `com.bytechef.ee.automation.data.table.public_.web.rest.model`; the `dependencies` block becomes:

```kotlin
dependencies {
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.apache.commons:commons-lang3")
    implementation(libs.io.swagger.core.v3.swagger.annotations)
    implementation("jakarta.annotation:jakarta.annotation-api")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation(libs.org.openapitools.jackson.databind.nullable)
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.security:spring-security-core")
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:exception:exception-api"))
    implementation(project(":server:libs:core:rest:rest-api"))
    implementation(project(":server:libs:atlas:atlas-coordinator:atlas-coordinator-api"))
    implementation(project(":server:libs:automation:automation-data-table:automation-data-table-api"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    implementation(project(":server:libs:platform:platform-data-table:platform-data-table-api"))
    implementation(project(":server:libs:platform:platform-tag:platform-tag-api"))
    implementation(project(":server:ee:libs:core:commons:commons-util"))

    testImplementation("org.springframework:spring-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(project(":server:libs:test:test-int-support"))
}
```
`@ConditionalOnEEVersion` lives in `server/ee/libs/core/commons/commons-util` — confirm with
`grep -rl "ConditionalOnEEVersion" server/ee/libs/core --include='*.java' | head -1` and adjust the
project path if it differs.

- [ ] **Step 2: Write `openapi.yaml`** — the complete file:

```yaml
---
openapi: "3.0.1"
info:
  title: "The Automation Data Table Public V1 API"
  version: "1"
servers:
  - url: "/api/automation/v1"
tags:
  - name: "data-table"
    description: "Data table and column definitions."
  - name: "data-table-row"
    description: "Data table rows."
paths:
  /workspaces/{workspaceId}/data-tables:
    get:
      description: "List the data tables of a workspace that exist in the requested environment."
      summary: "List data tables"
      tags:
        - "data-table"
      operationId: "listDataTables"
      parameters:
        - $ref: "#/components/parameters/WorkspaceId"
        - $ref: "#/components/parameters/XEnvironment"
        - name: "tag"
          description: "Return only tables carrying this tag."
          in: "query"
          required: false
          schema:
            type: "string"
      responses:
        "200":
          description: "The data tables."
          content:
            application/json:
              schema:
                type: "array"
                items:
                  $ref: "#/components/schemas/DataTable"
        "403":
          $ref: "#/components/responses/Error"
    post:
      description: "Create a data table in the requested environment. The same name may exist in several\
        \ environments; it is one logical table with one physical table per environment."
      summary: "Create a data table"
      tags:
        - "data-table"
      operationId: "createDataTable"
      parameters:
        - $ref: "#/components/parameters/WorkspaceId"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateDataTableRequest"
      responses:
        "201":
          description: "The created data table."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTable"
        "400":
          $ref: "#/components/responses/Error"
        "403":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
  /data-tables/{name}:
    get:
      description: "Get a data table as it exists in the requested environment."
      summary: "Get a data table"
      tags:
        - "data-table"
      operationId: "getDataTable"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "200":
          description: "The data table."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTable"
        "404":
          $ref: "#/components/responses/Error"
    patch:
      description: "Update the description and/or tags. Both are properties of the logical table and apply\
        \ to every environment. Omitted fields are left untouched."
      summary: "Update a data table"
      tags:
        - "data-table"
      operationId: "updateDataTable"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpdateDataTableRequest"
      responses:
        "200":
          description: "The updated data table."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTable"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
    delete:
      description: "Drop the table in the requested environment, with all of its rows. The logical table is\
        \ removed once its last environment is dropped. Irreversible."
      summary: "Delete a data table"
      tags:
        - "data-table"
      operationId: "deleteDataTable"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "204":
          description: "Deleted."
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/columns:
    post:
      description: "Add a column."
      summary: "Add a column"
      tags:
        - "data-table"
      operationId: "createColumn"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateColumnRequest"
      responses:
        "201":
          description: "The data table with the new column."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTable"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/columns/{column}:
    delete:
      description: "Drop a column and every value stored in it. Irreversible."
      summary: "Delete a column"
      tags:
        - "data-table"
      operationId: "deleteColumn"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/ColumnName"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "204":
          description: "Deleted."
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/columns/{column}/rename:
    post:
      description: "Rename a column. Workflows and filters that name the old column stop matching."
      summary: "Rename a column"
      tags:
        - "data-table"
      operationId: "renameColumn"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/ColumnName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/RenameColumnRequest"
      responses:
        "200":
          description: "The data table with the renamed column."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTable"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows:
    get:
      description: "Query rows. Filters are ANDed; each is `column:OPERATOR:value` with OPERATOR one of\
        \ EQ, NEQ, IN, CONTAINS, STARTS_WITH, GT, GTE, LT, LTE, BETWEEN. IN and BETWEEN take comma-separated\
        \ values. `id` and `externalId` may be filtered and sorted like any column. The `where` parameter is\
        \ reserved for a future expression filter and must not be sent."
      summary: "Query rows"
      tags:
        - "data-table-row"
      operationId: "listRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
        - name: "filter"
          description: "A `column:OPERATOR:value` condition. Repeat the parameter to add conditions."
          in: "query"
          required: false
          style: "form"
          explode: true
          schema:
            type: "array"
            items:
              type: "string"
        - name: "sort"
          description: "A `column:ASC` or `column:DESC` ordering. Repeat to add tie-breakers; `id:ASC` is always last."
          in: "query"
          required: false
          style: "form"
          explode: true
          schema:
            type: "array"
            items:
              type: "string"
        - name: "pageNumber"
          description: "The zero-based page to return."
          in: "query"
          required: false
          schema:
            type: "integer"
            format: "int32"
            default: 0
        - name: "pageSize"
          description: "Rows per page; at most 500."
          in: "query"
          required: false
          schema:
            type: "integer"
            format: "int32"
            default: 50
      responses:
        "200":
          description: "The page of rows."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Page"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
    post:
      description: "Insert one row."
      summary: "Insert a row"
      tags:
        - "data-table-row"
      operationId: "createRow"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateRowRequest"
      responses:
        "201":
          description: "The inserted row."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
        "507":
          $ref: "#/components/responses/Error"
    delete:
      description: "Delete rows by id. `ids` is required; to empty a table use the clear operation."
      summary: "Delete rows"
      tags:
        - "data-table-row"
      operationId: "deleteRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
        - name: "ids"
          description: "Comma-separated row ids; at most 1000."
          in: "query"
          required: true
          style: "form"
          explode: false
          schema:
            type: "array"
            items:
              type: "integer"
              format: "int64"
      responses:
        "200":
          description: "What was deleted."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DeleteRowsResponse"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/{id}:
    get:
      summary: "Get a row"
      tags:
        - "data-table-row"
      operationId: "getRow"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/RowId"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "200":
          description: "The row."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "404":
          $ref: "#/components/responses/Error"
    patch:
      description: "Merge values into a row. Omitted columns are untouched; a null value clears a column.\
        \ `externalId` may be set or cleared."
      summary: "Update a row"
      tags:
        - "data-table-row"
      operationId: "updateRow"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/RowId"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpdateRowRequest"
      responses:
        "200":
          description: "The updated row."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
    delete:
      summary: "Delete a row"
      tags:
        - "data-table-row"
      operationId: "deleteRow"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/RowId"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "204":
          description: "Deleted."
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/by-external-id/{externalId}:
    get:
      summary: "Get a row by external id"
      tags:
        - "data-table-row"
      operationId: "getRowByExternalId"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/ExternalId"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "200":
          description: "The row."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "404":
          $ref: "#/components/responses/Error"
    put:
      description: "Upsert: insert the row under this external id, or merge the values into the row that\
        \ already carries it. Idempotent."
      summary: "Upsert a row by external id"
      tags:
        - "data-table-row"
      operationId: "upsertRowByExternalId"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/ExternalId"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpsertRowRequest"
      responses:
        "200":
          description: "The row existed and was updated."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "201":
          description: "The row was created."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/DataTableRow"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "507":
          $ref: "#/components/responses/Error"
    delete:
      summary: "Delete a row by external id"
      tags:
        - "data-table-row"
      operationId: "deleteRowByExternalId"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/ExternalId"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "204":
          description: "Deleted."
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/batch:
    post:
      description: "Insert or upsert up to 1000 rows in one transaction; one failure rolls back all of them.\
        \ Under UPSERT every row must carry an externalId."
      summary: "Batch insert or upsert rows"
      tags:
        - "data-table-row"
      operationId: "batchRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/BatchRowsRequest"
      responses:
        "200":
          description: "The rows as stored."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/BatchRowsResponse"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
        "507":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/clear:
    post:
      description: "Delete every row. Irreversible."
      summary: "Clear a table"
      tags:
        - "data-table-row"
      operationId: "clearRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "200":
          description: "How many rows were deleted."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ClearRowsResponse"
        "404":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/import:
    post:
      description: "Insert rows from CSV. The header names existing columns; an optional `external_id`\
        \ column sets each row's external id. Empty fields are null."
      summary: "Import rows from CSV"
      tags:
        - "data-table-row"
      operationId: "importRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      requestBody:
        required: true
        content:
          text/csv:
            schema:
              type: "string"
      responses:
        "200":
          description: "How many rows were inserted."
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ImportRowsResponse"
        "400":
          $ref: "#/components/responses/Error"
        "404":
          $ref: "#/components/responses/Error"
        "409":
          $ref: "#/components/responses/Error"
        "507":
          $ref: "#/components/responses/Error"
  /data-tables/{name}/rows/export:
    get:
      description: "Export every row as CSV. `external_id` is the first column; `id` is not included."
      summary: "Export rows as CSV"
      tags:
        - "data-table-row"
      operationId: "exportRows"
      parameters:
        - $ref: "#/components/parameters/TableName"
        - $ref: "#/components/parameters/XEnvironment"
      responses:
        "200":
          description: "The CSV document."
          content:
            text/csv:
              schema:
                type: "string"
        "404":
          $ref: "#/components/responses/Error"
components:
  parameters:
    WorkspaceId:
      name: "workspaceId"
      description: "The id of a workspace."
      in: "path"
      required: true
      schema:
        type: "integer"
        format: "int64"
    TableName:
      name: "name"
      description: "The table name."
      in: "path"
      required: true
      schema:
        type: "string"
    ColumnName:
      name: "column"
      description: "The column name."
      in: "path"
      required: true
      schema:
        type: "string"
    RowId:
      name: "id"
      description: "The row id."
      in: "path"
      required: true
      schema:
        type: "integer"
        format: "int64"
    ExternalId:
      name: "externalId"
      description: "The caller-supplied row key."
      in: "path"
      required: true
      schema:
        type: "string"
        maxLength: 255
    XEnvironment:
      name: "X-Environment"
      description: "The environment whose physical table is addressed. PRODUCTION when omitted."
      in: "header"
      required: false
      schema:
        $ref: "#/components/schemas/Environment"
  responses:
    Error:
      description: "The problem."
      content:
        application/problem+json:
          schema:
            $ref: "#/components/schemas/Error"
  schemas:
    Environment:
      description: "The environment."
      type: "string"
      enum:
        - "DEVELOPMENT"
        - "STAGING"
        - "PRODUCTION"
    ColumnType:
      description: "The type of a column."
      type: "string"
      enum:
        - "STRING"
        - "NUMBER"
        - "INTEGER"
        - "DATE"
        - "DATE_TIME"
        - "BOOLEAN"
    CreateStrategy:
      description: "How a batch treats each row."
      type: "string"
      enum:
        - "INSERT"
        - "UPSERT"
    Error:
      description: "An RFC 7807 problem detail. Branch on errorKey, never on detail."
      type: "object"
      properties:
        type:
          description: "A URI identifying the problem type."
          type: "string"
        title:
          type: "string"
        status:
          type: "integer"
        detail:
          description: "A human-readable explanation of this occurrence."
          type: "string"
        errorKey:
          description: "The stable machine-readable key. 100 DATA_TABLE_NOT_FOUND, 103 DATA_TABLE_NAME_INVALID,\
            \ 104 DATA_TABLE_ALREADY_EXISTS, 105 COLUMN_NOT_FOUND, 106 COLUMN_ALREADY_EXISTS, 107 COLUMN_NAME_INVALID,\
            \ 108 ROW_NOT_FOUND, 109 ROW_VALUE_INVALID, 110 ROW_EXTERNAL_ID_CONFLICT, 111 ROW_EXTERNAL_ID_REQUIRED,\
            \ 112 FILTER_INVALID, 113 SORT_INVALID, 114 BATCH_TOO_LARGE, 115 CSV_INVALID, 116 STORAGE_LIMIT_EXCEEDED.\
            \ Absent on schema-validation failures."
          type: "integer"
        entityClass:
          type: "string"
    DataTableColumn:
      type: "object"
      required:
        - "name"
        - "type"
      properties:
        name:
          type: "string"
        type:
          $ref: "#/components/schemas/ColumnType"
    DataTable:
      type: "object"
      properties:
        name:
          description: "The table name; its identity."
          type: "string"
        description:
          type: "string"
        columns:
          type: "array"
          items:
            $ref: "#/components/schemas/DataTableColumn"
        tags:
          type: "array"
          items:
            type: "string"
        lastModifiedDate:
          type: "string"
          format: "date-time"
    CreateDataTableRequest:
      type: "object"
      required:
        - "name"
        - "columns"
      properties:
        name:
          description: "Lower-case letters, digits and underscores, not starting with a digit or `dt_`."
          type: "string"
          pattern: "^[a-z_][a-z0-9_]*$"
        description:
          type: "string"
        columns:
          type: "array"
          minItems: 1
          items:
            $ref: "#/components/schemas/DataTableColumn"
        tags:
          type: "array"
          items:
            type: "string"
    UpdateDataTableRequest:
      type: "object"
      properties:
        description:
          type: "string"
        tags:
          type: "array"
          items:
            type: "string"
    CreateColumnRequest:
      type: "object"
      required:
        - "name"
        - "type"
      properties:
        name:
          type: "string"
          pattern: "^[a-z_][a-z0-9_]*$"
        type:
          $ref: "#/components/schemas/ColumnType"
    RenameColumnRequest:
      type: "object"
      required:
        - "newName"
      properties:
        newName:
          type: "string"
          pattern: "^[a-z_][a-z0-9_]*$"
    DataTableRow:
      type: "object"
      properties:
        id:
          type: "integer"
          format: "int64"
        externalId:
          description: "The caller-supplied key, or null."
          type: "string"
        values:
          description: "Column name to value. STRING→string, NUMBER→number, INTEGER→integer, BOOLEAN→boolean,\
            \ DATE→YYYY-MM-DD, DATE_TIME→ISO-8601 UTC."
          type: "object"
          additionalProperties: true
    CreateRowRequest:
      type: "object"
      required:
        - "values"
      properties:
        values:
          description: "Column name to value. Each value may also be given as a string in the column's format."
          type: "object"
          additionalProperties: true
        externalId:
          type: "string"
          maxLength: 255
    UpdateRowRequest:
      type: "object"
      properties:
        values:
          type: "object"
          additionalProperties: true
        externalId:
          description: "Set the key; send null to clear it; omit to leave it."
          type: "string"
          nullable: true
          maxLength: 255
    UpsertRowRequest:
      type: "object"
      required:
        - "values"
      properties:
        values:
          type: "object"
          additionalProperties: true
    BatchRow:
      type: "object"
      required:
        - "values"
      properties:
        values:
          type: "object"
          additionalProperties: true
        externalId:
          type: "string"
          maxLength: 255
    BatchRowsRequest:
      type: "object"
      required:
        - "rows"
      properties:
        rows:
          type: "array"
          minItems: 1
          maxItems: 1000
          items:
            $ref: "#/components/schemas/BatchRow"
        createStrategy:
          $ref: "#/components/schemas/CreateStrategy"
    BatchRowsResponse:
      type: "object"
      properties:
        rows:
          type: "array"
          items:
            $ref: "#/components/schemas/DataTableRow"
    DeleteRowsResponse:
      type: "object"
      properties:
        deletedCount:
          type: "integer"
        deletedIds:
          type: "array"
          items:
            type: "integer"
            format: "int64"
    ClearRowsResponse:
      type: "object"
      properties:
        deletedCount:
          type: "integer"
          format: "int64"
    ImportRowsResponse:
      type: "object"
      properties:
        importedCount:
          type: "integer"
    Page:
      description: "A page of rows."
      type: "object"
      properties:
        number:
          type: "integer"
        size:
          type: "integer"
        numberOfElements:
          type: "integer"
        totalPages:
          type: "integer"
        totalElements:
          type: "integer"
        content:
          type: "array"
          items:
            $ref: "#/components/schemas/DataTableRow"
```

`UpdateRowRequest.externalId` is `nullable: true` so the generated model wraps it in
`JsonNullable<String>` — that is how the controller tells "absent" from "null" (Task 12).

- [ ] **Step 3: Register the module and generate**

Add the `include(...)` line to `settings.gradle.kts` (keep the alphabetical block order) and the
`implementation(project(...))` line to `server-app/build.gradle.kts`, then:

Run: `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:generateOpenAPI > /tmp/t9.log 2>&1; echo $?` → 0.
Run: `ls server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/generated/src/main/java/com/bytechef/ee/automation/data/table/public_/web/rest/` → `DataTableApi.java DataTableRowApi.java model/`.
Run: `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:compileJava > /tmp/t9c.log 2>&1; echo $?` → 0. (The generated `*Api` interfaces have default
methods, so the module compiles with no controllers yet.)

- [ ] **Step 4: Commit — spec and generated sources separately**

```bash
git add settings.gradle.kts server/apps/server-app/build.gradle.kts server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/build.gradle.kts server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/openapi.yaml
git commit -m "--- Add the public data table API module and its OpenAPI specification

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git add server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/generated
git commit -m "- Generate the public data table API interfaces and models

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Controller support — error handling base, query parser, value validator, mapper

**Files** (all under `server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/src/main/java/com/bytechef/ee/automation/data/table/public_/web/rest/`):
- Create: `AbstractDataTableApiController.java`
- Create: `RowQueryParser.java`
- Create: `RowValuesValidator.java`
- Create: `DataTableModelMapper.java`
- Create: `DataTableApiSupport.java` (name resolution + environment)
- Tests (mirror path under `src/test/java`): `RowQueryParserTest.java`, `RowValuesValidatorTest.java`, `AbstractDataTableApiControllerTest.java`

Every file: Enterprise header, `@version ee` Javadoc tag.

**Interfaces:**
- Produces:
  - `RowQueryParser.parseFilters(List<String> raw, Set<String> filterableColumns) : List<RowFilter>` — throws `DataTableException(FILTER_INVALID)`
  - `RowQueryParser.parseSorts(List<String> raw, Set<String> sortableColumns) : List<RowSort>` — throws `SORT_INVALID`
  - `RowValuesValidator.validate(Map<String,Object> values, List<ColumnSpec> columns)` — throws `ROW_VALUE_INVALID`; `validateExternalId(String)` — throws `ROW_VALUE_INVALID` when blank or > 255
  - `DataTableModelMapper.toModel(DataTableInfo, List<Tag>) : DataTableModel`, `toModel(DataTableRow) : DataTableRowModel`, `toColumnSpec(DataTableColumnModel)`
  - `DataTableApiSupport.environmentId(@Nullable EnvironmentModel) : long`, `resolveTableId(String name) : long` (throws `DATA_TABLE_NOT_FOUND` via `getIdByBaseName`), `validateTableName(String)`, `validateColumnName(String)`
  - `AbstractDataTableApiController` — `@ExceptionHandler`s described in the spec §7.3 amendment

- [ ] **Step 1: Failing parser tests**

```java
class RowQueryParserTest {

    private static final Set<String> COLUMNS = Set.of("id", "external_id", "title", "score");

    @Test
    void testParsesOperatorAndKeepsColonsInTheValue() {
        List<RowFilter> rowFilters = RowQueryParser.parseFilters(
            List.of("title:EQ:a:b:c", "score:GTE:10"), COLUMNS);

        assertEquals(2, rowFilters.size());
        assertEquals("title", rowFilters.getFirst()
            .field());
        assertEquals(RowFilter.Operator.EQ, rowFilters.getFirst()
            .operator());
        assertEquals("a:b:c", rowFilters.getFirst()
            .value());
    }

    @Test
    void testInAndBetweenSplitOnCommas() {
        List<RowFilter> rowFilters = RowQueryParser.parseFilters(
            List.of("score:IN:1,2,3", "score:BETWEEN:1,9"), COLUMNS);

        assertEquals(List.of("1", "2", "3"), rowFilters.get(0)
            .value());
        assertEquals(List.of("1", "9"), rowFilters.get(1)
            .value());
    }

    @Test
    void testBetweenNeedsExactlyTwoValues() {
        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> RowQueryParser.parseFilters(List.of("score:BETWEEN:1"), COLUMNS));

        assertEquals(DataTableErrorType.FILTER_INVALID.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testUnknownOperatorColumnOrShapeIsInvalid() {
        for (String bad : List.of("score:LIKE:1", "nosuch:EQ:1", "owner_id:EQ:1", "score", "score:EQ")) {
            assertThrows(DataTableException.class, () -> RowQueryParser.parseFilters(List.of(bad), COLUMNS), bad);
        }
    }

    @Test
    void testExternalIdIsAddressableAsExternalIdAndExternalUnderscoreId() {
        assertEquals("external_id", RowQueryParser.parseFilters(List.of("externalId:EQ:k"), COLUMNS)
            .getFirst()
            .field());
    }

    @Test
    void testParsesSorts() {
        List<RowSort> rowSorts = RowQueryParser.parseSorts(List.of("score:DESC", "title:ASC"), COLUMNS);

        assertEquals(RowSort.Direction.DESC, rowSorts.getFirst()
            .direction());
        assertThrows(DataTableException.class, () -> RowQueryParser.parseSorts(List.of("score:UP"), COLUMNS));
        assertThrows(DataTableException.class, () -> RowQueryParser.parseSorts(List.of("nosuch:ASC"), COLUMNS));
    }
}
```

- [ ] **Step 2: Failing validator tests**

```java
class RowValuesValidatorTest {

    private static final List<ColumnSpec> COLUMNS = List.of(
        new ColumnSpec("title", ColumnType.STRING), new ColumnSpec("score", ColumnType.INTEGER),
        new ColumnSpec("price", ColumnType.NUMBER), new ColumnSpec("done", ColumnType.BOOLEAN),
        new ColumnSpec("day", ColumnType.DATE), new ColumnSpec("at", ColumnType.DATE_TIME));

    @Test
    void testNaturalTypesAndStringFormsPass() {
        Map<String, Object> values = new HashMap<>();

        values.put("title", "x");
        values.put("score", 3);
        values.put("price", "9.5");
        values.put("done", "true");
        values.put("day", "2026-09-02");
        values.put("at", "2026-09-02T10:00:00Z");
        values.put("nullable", null);

        assertThrows(DataTableException.class, () -> RowValuesValidator.validate(values, COLUMNS), "unknown column");

        values.remove("nullable");

        assertDoesNotThrow(() -> RowValuesValidator.validate(values, COLUMNS));
    }

    @Test
    void testUncoercibleValuesNameTheColumn() {
        DataTableException dataTableException = assertThrows(
            DataTableException.class, () -> RowValuesValidator.validate(Map.of("score", "ten"), COLUMNS));

        assertEquals(DataTableErrorType.ROW_VALUE_INVALID.getErrorKey(), dataTableException.getErrorKey());
        assertTrue(dataTableException.getMessage()
            .contains("score"));
        assertThrows(DataTableException.class, () -> RowValuesValidator.validate(Map.of("done", "yes"), COLUMNS));
        assertThrows(DataTableException.class, () -> RowValuesValidator.validate(Map.of("day", "tomorrow"), COLUMNS));
        assertThrows(DataTableException.class, () -> RowValuesValidator.validate(Map.of("id", 1), COLUMNS));
    }

    @Test
    void testExternalIdBounds() {
        assertThrows(DataTableException.class, () -> RowValuesValidator.validateExternalId(" "));
        assertThrows(DataTableException.class, () -> RowValuesValidator.validateExternalId("x".repeat(256)));
        assertDoesNotThrow(() -> RowValuesValidator.validateExternalId("x".repeat(255)));
    }
}
```

- [ ] **Step 3: Failing handler test**

```java
class AbstractDataTableApiControllerTest {

    private final AbstractDataTableApiController controller = new AbstractDataTableApiController() {};

    @Test
    void testDataTableExceptionsMapByKey() {
        assertEquals(HttpStatus.NOT_FOUND, controller.handleDataTableException(
            new DataTableException("x", DataTableErrorType.ROW_NOT_FOUND), request("/api/automation/v1/data-tables/t"))
            .getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.handleDataTableException(
            new DataTableException("x", DataTableErrorType.ROW_EXTERNAL_ID_CONFLICT), request("/x"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.handleDataTableException(
            new DataTableException("x", DataTableErrorType.FILTER_INVALID), request("/x"))
            .getStatusCode());

        ProblemDetail body = controller.handleDataTableException(
            new DataTableException("x", DataTableErrorType.FILTER_INVALID), request("/x"))
            .getBody();

        assertEquals(112, body.getProperties()
            .get("errorKey"));
        assertEquals("DataTableErrorType", body.getProperties()
            .get("entityClass"));
    }

    @Test
    void testExecutionExceptionWithADataTableKeyIsHandledTheSameWay() {
        assertEquals(HttpStatus.NOT_FOUND, controller.handleDataTableException(
            new ExecutionException("x", DataTableErrorType.DATA_TABLE_NOT_FOUND), request("/x"))
            .getStatusCode());
    }

    @Test
    void testStorageLimitIs507() {
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, controller.handleStorageLimit(
            new DataTableStorageLimitExceededException(10, 5), request("/x"))
            .getStatusCode());
    }

    @Test
    void testAccessDeniedIs404OnItemUrlsAnd403OnWorkspaceUrls() {
        assertEquals(HttpStatus.NOT_FOUND, controller.handleAccessDenied(
            new AccessDeniedException("x"), request("/api/automation/v1/data-tables/orders"))
            .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.handleAccessDenied(
            new AccessDeniedException("x"), request("/api/automation/v1/workspaces/1/data-tables"))
            .getStatusCode());
    }

    private static HttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);

        return request;
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:test > /tmp/t10.log 2>&1; echo $?` → non-zero.

- [ ] **Step 5: Implement**

`AbstractDataTableApiController`:

```java
/**
 * Error translation shared by the public data table controllers. Declared on the controller class rather than as an
 * advice because {@code GlobalResponseEntityExceptionHandler} is {@code HIGHEST_PRECEDENCE} and maps every
 * {@code AbstractException} to 400; Spring consults a controller's own {@code @ExceptionHandler}s before any advice.
 *
 * <p>
 * The body is the same {@code ProblemDetail} shape the global handler emits -- {@code type}, {@code title},
 * {@code errorKey}, {@code entityClass} -- so a consumer never sees two error dialects.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public abstract class AbstractDataTableApiController {

    private static final Set<Integer> NOT_FOUND_KEYS = Set.of(
        DataTableErrorType.DATA_TABLE_NOT_FOUND.getErrorKey(), DataTableErrorType.COLUMN_NOT_FOUND.getErrorKey(),
        DataTableErrorType.ROW_NOT_FOUND.getErrorKey());
    private static final Set<Integer> CONFLICT_KEYS = Set.of(
        DataTableErrorType.DATA_TABLE_ALREADY_EXISTS.getErrorKey(),
        DataTableErrorType.COLUMN_ALREADY_EXISTS.getErrorKey(),
        DataTableErrorType.ROW_EXTERNAL_ID_CONFLICT.getErrorKey());

    /**
     * Declared for the two concrete types rather than for {@code AbstractException}, and never rethrows. A handler
     * that throws is logged by {@code ExceptionHandlerExceptionResolver} and yields null; because that resolver has
     * already been selected, the global advice in it is NOT retried, so a rethrow here would surface as a raw 500.
     * An exception carrying some other entity class therefore gets the same 400 body the global advice would have
     * produced, built from its own entity class and error key.
     */
    @ExceptionHandler({
        DataTableException.class, ExecutionException.class
    })
    public ResponseEntity<ProblemDetail> handleDataTableException(
        AbstractException exception, HttpServletRequest request) {

        if (exception.getEntityClass() != DataTableErrorType.class) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    problemDetail(
                        HttpStatus.BAD_REQUEST, exception.getMessage(), exception.getErrorKey(),
                        exception.getErrorMessageCode(), exception.getEntityClass()));
        }

        HttpStatus status = HttpStatus.BAD_REQUEST;

        if (NOT_FOUND_KEYS.contains(exception.getErrorKey())) {
            status = HttpStatus.NOT_FOUND;
        } else if (CONFLICT_KEYS.contains(exception.getErrorKey())) {
            status = HttpStatus.CONFLICT;
        }

        return ResponseEntity.status(status)
            .body(
                problemDetail(
                    status, exception.getMessage(), exception.getErrorKey(), exception.getErrorMessageCode(),
                    DataTableErrorType.class));
    }

    @ExceptionHandler(DataTableStorageLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleStorageLimit(
        DataTableStorageLimitExceededException exception, HttpServletRequest request) {

        DataTableErrorType errorType = DataTableErrorType.STORAGE_LIMIT_EXCEEDED;

        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
            .body(
                problemDetail(
                    HttpStatus.INSUFFICIENT_STORAGE, exception.getMessage(), errorType.getErrorKey(),
                    "error.dataTableErrorType." + errorType.getErrorKey(), DataTableErrorType.class));
    }

    /**
     * Names are guessable, so on item URLs a denial answers exactly like a missing table: a caller learns nothing
     * about tables in workspaces they cannot see. Under /workspaces the caller already named the workspace, so a
     * plain 403 leaks nothing and is the more useful answer.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        String requestUri = request.getRequestURI();

        if (requestUri.contains("/data-tables/")) {
            DataTableErrorType errorType = DataTableErrorType.DATA_TABLE_NOT_FOUND;

            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    problemDetail(
                        HttpStatus.NOT_FOUND, "Data table not found", errorType.getErrorKey(),
                        "error.dataTableErrorType." + errorType.getErrorKey(), DataTableErrorType.class));
        }

        throw exception;
    }

    private static ProblemDetail problemDetail(
        HttpStatus status, String detail, int errorKey, String errorMessageCode, Class<?> entityClass) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);

        problemDetail.setTitle("Error");
        problemDetail.setType(URI.create(ErrorConstants.PROBLEM_BASE_URL + "/" + errorMessageCode));
        problemDetail.setProperty("entityClass", entityClass.getSimpleName());
        problemDetail.setProperty("errorKey", errorKey);

        return problemDetail;
    }
}
```
(`ErrorConstants` is `com.bytechef.web.rest.error.constant.ErrorConstants` from `rest-api`. Rethrowing
from an `@ExceptionHandler` lets the global advice take over — Spring treats a handler that throws as
"not handled here".)

`RowQueryParser`:

```java
/**
 * Turns the wire filter and sort grammar into the platform's {@link RowFilter}/{@link RowSort}. Values are never
 * interpreted here -- type coercion is the service's -- so the only failures are shape, operator and column.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class RowQueryParser {

    private RowQueryParser() {
    }

    static List<RowFilter> parseFilters(@Nullable List<String> raw, Set<String> filterableColumns) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<RowFilter> rowFilters = new ArrayList<>(raw.size());

        for (String term : raw) {
            String[] parts = term.split(":", 3);

            if (parts.length < 3) {
                throw new DataTableException(
                    "A filter is column:OPERATOR:value, got '" + term + "'", DataTableErrorType.FILTER_INVALID);
            }

            String column = column(parts[0], filterableColumns, DataTableErrorType.FILTER_INVALID);
            RowFilter.Operator operator = operator(parts[1], term);
            String rawValue = parts[2];

            Object value = switch (operator) {
                case IN, BETWEEN -> {
                    List<String> values = Arrays.stream(rawValue.split(","))
                        .map(String::trim)
                        .filter(item -> !item.isEmpty())
                        .toList();

                    if (operator == RowFilter.Operator.BETWEEN && values.size() != 2) {
                        throw new DataTableException(
                            "BETWEEN takes exactly two comma-separated values, got '" + rawValue + "'",
                            DataTableErrorType.FILTER_INVALID);
                    }

                    if (values.isEmpty()) {
                        throw new DataTableException(
                            "IN needs at least one value, got '" + rawValue + "'", DataTableErrorType.FILTER_INVALID);
                    }

                    yield values;
                }
                default -> rawValue;
            };

            rowFilters.add(new RowFilter(column, operator, value));
        }

        return rowFilters;
    }

    static List<RowSort> parseSorts(@Nullable List<String> raw, Set<String> sortableColumns) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<RowSort> rowSorts = new ArrayList<>(raw.size());

        for (String term : raw) {
            String[] parts = term.split(":", 2);

            if (parts.length != 2) {
                throw new DataTableException(
                    "A sort is column:ASC or column:DESC, got '" + term + "'", DataTableErrorType.SORT_INVALID);
            }

            String column = column(parts[0], sortableColumns, DataTableErrorType.SORT_INVALID);

            RowSort.Direction direction;

            try {
                direction = RowSort.Direction.valueOf(parts[1].trim()
                    .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException illegalArgumentException) {
                throw new DataTableException(
                    "Unknown sort direction '" + parts[1] + "'", DataTableErrorType.SORT_INVALID);
            }

            rowSorts.add(new RowSort(column, direction));
        }

        return rowSorts;
    }

    private static String column(String field, Set<String> columns, DataTableErrorType errorType) {
        String column = field.trim()
            .toLowerCase(Locale.ROOT);

        if ("externalid".equals(column)) {
            column = ReservedColumns.EXTERNAL_ID;
        }

        if (column.isEmpty() || ReservedColumns.isHidden(column) || !columns.contains(column)) {
            throw new DataTableException("Unknown column '" + field + "'", errorType);
        }

        return column;
    }

    private static RowFilter.Operator operator(String raw, String term) {
        try {
            return RowFilter.Operator.valueOf(raw.trim()
                .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new DataTableException(
                "Unknown operator in '" + term + "'", DataTableErrorType.FILTER_INVALID);
        }
    }
}
```

`RowValuesValidator`:

```java
/**
 * Strict request validation, deliberately stricter than the service's {@code coerceValue}: that path turns an
 * unparseable INTEGER into null for the workflow editor's benefit, which on a public API would be a silent data
 * change. Unknown columns are rejected here for the same reason.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class RowValuesValidator {

    private static final int MAX_EXTERNAL_ID_LENGTH = 255;

    private RowValuesValidator() {
    }

    static void validate(Map<String, Object> values, List<ColumnSpec> columnSpecs) {
        Map<String, ColumnType> columnTypes = new HashMap<>();

        for (ColumnSpec columnSpec : columnSpecs) {
            columnTypes.put(columnSpec.name()
                .toLowerCase(Locale.ROOT), columnSpec.type());
        }

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String column = entry.getKey()
                .toLowerCase(Locale.ROOT);
            ColumnType columnType = columnTypes.get(column);

            if (columnType == null || ReservedColumns.isReserved(column)) {
                throw new DataTableException(
                    "'" + entry.getKey() + "' is not a column of this table", DataTableErrorType.ROW_VALUE_INVALID);
            }

            Object value = entry.getValue();

            if (value == null || isCoercible(columnType, value)) {
                continue;
            }

            throw new DataTableException(
                "Value '" + value + "' is not a valid " + columnType + " for column '" + entry.getKey() + "'",
                DataTableErrorType.ROW_VALUE_INVALID);
        }
    }

    static void validateExternalId(@Nullable String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new DataTableException(
                "externalId must be 1-" + MAX_EXTERNAL_ID_LENGTH + " characters", DataTableErrorType.ROW_VALUE_INVALID);
        }
    }

    private static boolean isCoercible(ColumnType columnType, Object value) {
        String text = String.valueOf(value)
            .trim();

        try {
            return switch (columnType) {
                case STRING -> true;
                case BOOLEAN -> value instanceof Boolean || "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
                case INTEGER -> value instanceof Integer || value instanceof Long || parsesAsLong(text);
                case NUMBER -> value instanceof Number || parsesAsDecimal(text);
                case DATE -> parsesAsDate(text);
                case DATE_TIME -> parsesAsDateTime(text);
            };
        } catch (RuntimeException runtimeException) {
            return false;
        }
    }

    private static boolean parsesAsLong(String text) {
        Long.parseLong(text);

        return true;
    }

    private static boolean parsesAsDecimal(String text) {
        new BigDecimal(text);

        return true;
    }

    private static boolean parsesAsDate(String text) {
        LocalDate.parse(text);

        return true;
    }

    private static boolean parsesAsDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
        } catch (DateTimeParseException dateTimeParseException) {
            LocalDateTime.parse(text);
        }

        return true;
    }
}
```

`DataTableApiSupport` (a Spring `@Component`, `@ConditionalOnEEVersion`):

```java
@Component
@ConditionalOnEEVersion
public class DataTableApiSupport {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final DataTableService dataTableService;
    private final EnvironmentService environmentService;

    @SuppressFBWarnings("EI2")
    public DataTableApiSupport(DataTableService dataTableService, EnvironmentService environmentService) {
        this.dataTableService = dataTableService;
        this.environmentService = environmentService;
    }

    public long environmentId(@Nullable EnvironmentModel environmentModel) {
        Environment environment = environmentService.getEnvironment(
            environmentModel == null ? null : environmentModel.name());

        return environment.ordinal();
    }

    /** Resolves the public name to the guarded id; an unknown name is the typed not-found the handler maps to 404. */
    public long resolveTableId(String name) {
        validateTableName(name);

        return dataTableService.getIdByBaseName(name, PlatformType.AUTOMATION);
    }

    public static void validateTableName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name)
            .matches() || name.startsWith("dt_")) {

            throw new DataTableException("Invalid table name '" + name + "'", DataTableErrorType.DATA_TABLE_NAME_INVALID);
        }
    }

    public static void validateColumnName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name)
            .matches() || ReservedColumns.isReserved(name)) {

            throw new DataTableException("Invalid column name '" + name + "'", DataTableErrorType.COLUMN_NAME_INVALID);
        }
    }
}
```

`DataTableModelMapper` (static, package-private):

```java
final class DataTableModelMapper {

    private DataTableModelMapper() {
    }

    static DataTableModel toModel(DataTableInfo dataTableInfo, List<Tag> tags) {
        return new DataTableModel()
            .name(dataTableInfo.baseName())
            .description(dataTableInfo.description())
            .columns(dataTableInfo.columns()
                .stream()
                .map(columnSpec -> new DataTableColumnModel()
                    .name(columnSpec.name())
                    .type(ColumnTypeModel.valueOf(columnSpec.type()
                        .name())))
                .toList())
            .tags(tags.stream()
                .map(Tag::getName)
                .toList())
            .lastModifiedDate(
                dataTableInfo.lastModifiedDate() == null
                    ? null : dataTableInfo.lastModifiedDate()
                        .atOffset(ZoneOffset.UTC));
    }

    static DataTableRowModel toModel(DataTableRow dataTableRow) {
        Map<String, Object> values = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : dataTableRow.values()
            .entrySet()) {

            values.put(entry.getKey(), toJsonValue(entry.getValue()));
        }

        return new DataTableRowModel()
            .id(dataTableRow.id())
            .externalId(dataTableRow.externalId())
            .values(values);
    }

    static ColumnSpec toColumnSpec(DataTableColumnModel dataTableColumnModel) {
        DataTableApiSupport.validateColumnName(dataTableColumnModel.getName());

        return new ColumnSpec(
            dataTableColumnModel.getName(), ColumnType.valueOf(dataTableColumnModel.getType()
                .name()));
    }

    /** JDBC hands back java.sql types; the wire contract is ISO text for dates and plain JSON for the rest. */
    private static @Nullable Object toJsonValue(@Nullable Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant()
                .toString();
        }

        if (value instanceof java.sql.Date date) {
            return date.toLocalDate()
                .toString();
        }

        return value;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:test > /tmp/t10.log 2>&1; echo $?` → 0.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/ee/libs/automation/automation-data-table/automation-data-table-public-rest
git commit -m "- Add the public data table API error handling, query parser, value validator and mapper

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: `DataTableApiController` — the DDL endpoints

**Files:**
- Create: `.../public_/web/rest/DataTableApiController.java`
- Test: `.../src/test/java/.../public_/web/rest/DataTableApiControllerTest.java`

**Interfaces:**
- Consumes: `WorkspaceDataTableFacade` (Task 8), `DataTableApiSupport`, `DataTableModelMapper`, the generated `DataTableApi`.

- [ ] **Step 1: Failing test** (plain Mockito, like `WorkflowExecutionApiControllerTest`)

```java
class DataTableApiControllerTest {

    private final WorkspaceDataTableFacade facade = mock(WorkspaceDataTableFacade.class);
    private final DataTableService dataTableService = mock(DataTableService.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);
    private DataTableApiController controller;

    @BeforeEach
    void beforeEach() {
        when(environmentService.getEnvironment((String) null)).thenReturn(Environment.PRODUCTION);
        when(environmentService.getEnvironment("STAGING")).thenReturn(Environment.STAGING);

        controller = new DataTableApiController(facade, new DataTableApiSupport(dataTableService, environmentService));
    }

    @Test
    void testListFiltersByTagAndMapsColumns() {
        DataTableInfo orders = new DataTableInfo(
            7L, "orders", "d", List.of(new ColumnSpec("total", ColumnType.NUMBER)), Instant.EPOCH);
        DataTableInfo other = new DataTableInfo(8L, "other", null, List.of(), Instant.EPOCH);

        when(facade.listTables(1L, Environment.STAGING.ordinal())).thenReturn(List.of(orders, other));
        when(facade.getDataTableTags(1L)).thenReturn(List.of());
        when(facade.getTagsByTableId(1L)).thenReturn(Map.of(7L, List.of(new Tag("hot"))));

        List<DataTableModel> models = controller.listDataTables(1L, EnvironmentModel.STAGING, "hot")
            .getBody();

        assertEquals(1, models.size());
        assertEquals("orders", models.getFirst()
            .getName());
        assertEquals(ColumnTypeModel.NUMBER, models.getFirst()
            .getColumns()
            .getFirst()
            .getType());
        assertEquals(List.of("hot"), models.getFirst()
            .getTags());
    }

    @Test
    void testCreateValidatesNamesThenCreatesThenReturnsTheTable() {
        when(dataTableService.getIdByBaseName("orders", PlatformType.AUTOMATION)).thenReturn(7L);
        when(facade.getTable(7L, Environment.PRODUCTION.ordinal()))
            .thenReturn(new DataTableInfo(7L, "orders", "d", List.of(), Instant.EPOCH));
        when(facade.getTagsByTableId(1L)).thenReturn(Map.of());

        ResponseEntity<DataTableModel> response = controller.createDataTable(
            1L, null, new CreateDataTableRequestModel()
                .name("orders")
                .description("d")
                .columns(List.of(new DataTableColumnModel().name("total")
                    .type(ColumnTypeModel.NUMBER)))
                .tags(List.of("hot")));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        verify(facade).createTable(eq("orders"), eq("d"), eq(List.of(new ColumnSpec("total", ColumnType.NUMBER))),
            eq(1L), eq((long) Environment.PRODUCTION.ordinal()));
        verify(facade).updateTags(eq(7L), argThat(tags -> tags.size() == 1 && "hot".equals(tags.getFirst()
            .getName())));
    }

    @Test
    void testCreateRejectsAReservedColumnBeforeTouchingTheFacade() {
        assertThrows(
            DataTableException.class,
            () -> controller.createDataTable(
                1L, null, new CreateDataTableRequestModel()
                    .name("orders")
                    .columns(List.of(new DataTableColumnModel().name("owner_id")
                        .type(ColumnTypeModel.STRING)))));

        verifyNoInteractions(facade);
    }

    @Test
    void testUpdateLeavesOmittedFieldsAlone() {
        when(dataTableService.getIdByBaseName("orders", PlatformType.AUTOMATION)).thenReturn(7L);
        when(facade.getTable(7L, Environment.PRODUCTION.ordinal()))
            .thenReturn(new DataTableInfo(7L, "orders", "d", List.of(), Instant.EPOCH));
        when(facade.getTagsByTableId(anyLong())).thenReturn(Map.of());

        controller.updateDataTable("orders", null, new UpdateDataTableRequestModel().description("new"));

        verify(facade).updateDescription(7L, "new");
        verify(facade, never()).updateTags(anyLong(), anyList());
    }

    @Test
    void testDeleteIs204() {
        when(dataTableService.getIdByBaseName("orders", PlatformType.AUTOMATION)).thenReturn(7L);

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteDataTable("orders", null)
            .getStatusCode());

        verify(facade).dropTable(7L, Environment.PRODUCTION.ordinal());
    }

    @Test
    void testColumnOperationsResolveTheTableOnce() {
        when(dataTableService.getIdByBaseName("orders", PlatformType.AUTOMATION)).thenReturn(7L);
        when(facade.getTable(7L, Environment.PRODUCTION.ordinal()))
            .thenReturn(new DataTableInfo(7L, "orders", null, List.of(), Instant.EPOCH));
        when(facade.getTagsByTableId(anyLong())).thenReturn(Map.of());

        controller.createColumn("orders", null, new CreateColumnRequestModel().name("qty")
            .type(ColumnTypeModel.INTEGER));
        controller.renameColumn("orders", "qty", null, new RenameColumnRequestModel().newName("quantity"));
        controller.deleteColumn("orders", "quantity", null);

        verify(facade).addColumn(7L, new ColumnSpec("qty", ColumnType.INTEGER), Environment.PRODUCTION.ordinal());
        verify(facade).renameColumn(7L, "qty", "quantity", Environment.PRODUCTION.ordinal());
        verify(facade).removeColumn(7L, "quantity", Environment.PRODUCTION.ordinal());
    }
}
```

`getTagsByTableId(long workspaceId)` and `getWorkspaceId(long dataTableId)` already exist on the
facade — Task 8 added them (controller ruling R3). Do NOT add or modify facade methods in this task;
stub them in your tests and call them from the controller.

- [ ] **Step 2: Run to verify failure** — `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:test --tests '*DataTableApiControllerTest' > /tmp/t11.log 2>&1; echo $?` → non-zero.

- [ ] **Step 3: Implement**

```java
/**
 * Public DDL endpoints of the data table API. Every operation resolves the public name to the guarded id and delegates
 * to {@link WorkspaceDataTableFacade}; no authorization lives here.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController("com.bytechef.ee.automation.data.table.public_.web.rest.DataTableApiController")
@RequestMapping("${openapi.openAPIDefinition.base-path.automation:}/v1")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class DataTableApiController extends AbstractDataTableApiController implements DataTableApi {

    private final WorkspaceDataTableFacade workspaceDataTableFacade;
    private final DataTableApiSupport support;

    @SuppressFBWarnings("EI2")
    public DataTableApiController(WorkspaceDataTableFacade workspaceDataTableFacade, DataTableApiSupport support) {
        this.workspaceDataTableFacade = workspaceDataTableFacade;
        this.support = support;
    }

    @Override
    public ResponseEntity<List<DataTableModel>> listDataTables(
        Long workspaceId, @Nullable EnvironmentModel xEnvironment, @Nullable String tag) {

        long environmentId = support.environmentId(xEnvironment);

        Map<Long, List<Tag>> tagsByTableId = workspaceDataTableFacade.getTagsByTableId(workspaceId);

        List<DataTableModel> dataTableModels = workspaceDataTableFacade.listTables(workspaceId, environmentId)
            .stream()
            .map(dataTableInfo -> DataTableModelMapper.toModel(
                dataTableInfo, tagsByTableId.getOrDefault(dataTableInfo.id(), List.of())))
            .filter(dataTableModel -> tag == null || dataTableModel.getTags()
                .contains(tag))
            .toList();

        return ResponseEntity.ok(dataTableModels);
    }

    @Override
    public ResponseEntity<DataTableModel> createDataTable(
        Long workspaceId, @Nullable EnvironmentModel xEnvironment, CreateDataTableRequestModel request) {

        DataTableApiSupport.validateTableName(request.getName());

        List<ColumnSpec> columnSpecs = request.getColumns()
            .stream()
            .map(DataTableModelMapper::toColumnSpec)
            .toList();
        long environmentId = support.environmentId(xEnvironment);

        workspaceDataTableFacade.createTable(
            request.getName(), request.getDescription(), columnSpecs, workspaceId, environmentId);

        long dataTableId = support.resolveTableId(request.getName());

        if (request.getTags() != null && !request.getTags()
            .isEmpty()) {

            workspaceDataTableFacade.updateTags(dataTableId, toTags(request.getTags()));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(table(dataTableId, workspaceId, environmentId));
    }

    @Override
    public ResponseEntity<DataTableModel> getDataTable(String name, @Nullable EnvironmentModel xEnvironment) {
        long dataTableId = support.resolveTableId(name);

        return ResponseEntity.ok(table(dataTableId, null, support.environmentId(xEnvironment)));
    }

    @Override
    public ResponseEntity<DataTableModel> updateDataTable(
        String name, @Nullable EnvironmentModel xEnvironment, UpdateDataTableRequestModel request) {

        long dataTableId = support.resolveTableId(name);

        if (request.getDescription() != null) {
            workspaceDataTableFacade.updateDescription(dataTableId, request.getDescription());
        }

        if (request.getTags() != null) {
            workspaceDataTableFacade.updateTags(dataTableId, toTags(request.getTags()));
        }

        return ResponseEntity.ok(table(dataTableId, null, support.environmentId(xEnvironment)));
    }

    @Override
    public ResponseEntity<Void> deleteDataTable(String name, @Nullable EnvironmentModel xEnvironment) {
        workspaceDataTableFacade.dropTable(support.resolveTableId(name), support.environmentId(xEnvironment));

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<DataTableModel> createColumn(
        String name, @Nullable EnvironmentModel xEnvironment, CreateColumnRequestModel request) {

        long dataTableId = support.resolveTableId(name);
        long environmentId = support.environmentId(xEnvironment);

        DataTableApiSupport.validateColumnName(request.getName());

        workspaceDataTableFacade.addColumn(
            dataTableId, new ColumnSpec(request.getName(), ColumnType.valueOf(request.getType()
                .name())),
            environmentId);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(table(dataTableId, null, environmentId));
    }

    @Override
    public ResponseEntity<Void> deleteColumn(String name, String column, @Nullable EnvironmentModel xEnvironment) {
        DataTableApiSupport.validateColumnName(column);

        workspaceDataTableFacade.removeColumn(support.resolveTableId(name), column, support.environmentId(xEnvironment));

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<DataTableModel> renameColumn(
        String name, String column, @Nullable EnvironmentModel xEnvironment, RenameColumnRequestModel request) {

        DataTableApiSupport.validateColumnName(column);
        DataTableApiSupport.validateColumnName(request.getNewName());

        long dataTableId = support.resolveTableId(name);
        long environmentId = support.environmentId(xEnvironment);

        workspaceDataTableFacade.renameColumn(dataTableId, column, request.getNewName(), environmentId);

        return ResponseEntity.ok(table(dataTableId, null, environmentId));
    }

    /**
     * Tags live on the registry row, keyed by workspace; when the caller did not name the workspace (item URLs) the
     * table's own workspace is looked up through the facade.
     */
    private DataTableModel table(long dataTableId, @Nullable Long workspaceId, long environmentId) {
        DataTableInfo dataTableInfo = workspaceDataTableFacade.getTable(dataTableId, environmentId);

        long resolvedWorkspaceId = workspaceId != null
            ? workspaceId : workspaceDataTableFacade.getWorkspaceId(dataTableId);

        List<Tag> tags = workspaceDataTableFacade.getTagsByTableId(resolvedWorkspaceId)
            .getOrDefault(dataTableId, List.of());

        return DataTableModelMapper.toModel(dataTableInfo, tags);
    }

    private static List<Tag> toTags(List<String> names) {
        return names.stream()
            .map(String::trim)
            .filter(tagName -> !tagName.isEmpty())
            .distinct()
            .map(Tag::new)
            .toList();
    }
}
```

`getWorkspaceId(long dataTableId)` is one more facade method (`DATA_TABLE_VIEW` on the table; wraps
`workspaceDataTableService.fetchWorkspaceId(dataTableId).orElseThrow(() -> new DataTableException(…, DATA_TABLE_NOT_FOUND))`).
Add it, its authorization assertion, and stub it in the tests above (`when(facade.getWorkspaceId(7L)).thenReturn(1L)`).

- [ ] **Step 4: Run** — `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:test :server:libs:automation:automation-data-table:automation-data-table-service:test > /tmp/t11.log 2>&1; echo $?` → 0.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/ee/libs/automation/automation-data-table/automation-data-table-public-rest server/libs/automation/automation-data-table
git commit -m "- Add the public data table DDL controller

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: `DataTableRowApiController` — the row endpoints

**Files:**
- Create: `.../public_/web/rest/DataTableRowApiController.java`
- Test: `.../src/test/java/.../public_/web/rest/DataTableRowApiControllerTest.java`

**Interfaces:**
- Consumes: Tasks 8, 10, 11's `DataTableApiSupport`; generated `DataTableRowApi`.
- Constants: `MAX_PAGE_SIZE = 500`, `DEFAULT_PAGE_SIZE = 50`, `MAX_BATCH_SIZE = 1000`.

- [ ] **Step 1: Failing test** — the same shape as Task 11's; the cases that must exist:

```java
    @Test
    void testListParsesFiltersAgainstTheTableColumnsAndCapsPageSize() {
        stubTable();  // getIdByBaseName("orders") → 7, facade.getTable(7, PRODUCTION) → columns [total NUMBER]
        when(facade.listRows(eq(7L), anyList(), anyList(), eq(0), eq(500), eq(0L)))
            .thenReturn(new PageImpl<>(List.of(new DataTableRow(1L, "k", Map.of("total", new BigDecimal("9.5"))))));

        ResponseEntity<Page> response = controller.listRows(
            "orders", null, List.of("total:GTE:5", "externalId:EQ:k"), List.of("total:DESC"), 0, 9_999);

        ArgumentCaptor<List<RowFilter>> filters = ArgumentCaptor.forClass(List.class);

        verify(facade).listRows(eq(7L), filters.capture(), anyList(), eq(0), eq(500), eq(0L));

        assertEquals("external_id", filters.getValue()
            .get(1)
            .field());
        assertEquals("k", ((DataTableRowModel) response.getBody()
            .getContent()
            .getFirst()).getExternalId());
    }

    @Test
    void testCreateValidatesValuesBeforeTheFacade() {
        stubTable();

        assertThrows(DataTableException.class, () -> controller.createRow(
            "orders", null, new CreateRowRequestModel().values(Map.of("total", "lots"))));

        verify(facade, never()).insertRow(anyLong(), anyMap(), any(), anyLong());
    }

    @Test
    void testUpdateDistinguishesAbsentNullAndValueForExternalId() {
        stubTable();
        when(facade.updateRow(eq(7L), eq(3L), anyMap(), any(), eq(0L)))
            .thenReturn(new DataTableRow(3L, null, Map.of()));

        controller.updateRow("orders", 3L, null, new UpdateRowRequestModel().values(Map.of()));
        controller.updateRow("orders", 3L, null, new UpdateRowRequestModel().values(Map.of())
            .externalId(JsonNullable.of(null)));
        controller.updateRow("orders", 3L, null, new UpdateRowRequestModel().values(Map.of())
            .externalId(JsonNullable.of("k")));

        InOrder inOrder = inOrder(facade);

        inOrder.verify(facade).updateRow(eq(7L), eq(3L), anyMap(), isNull(), eq(0L));
        inOrder.verify(facade).updateRow(eq(7L), eq(3L), anyMap(), eq(new ExternalIdPatch(null)), eq(0L));
        inOrder.verify(facade).updateRow(eq(7L), eq(3L), anyMap(), eq(new ExternalIdPatch("k")), eq(0L));
    }

    @Test
    void testUpsertAnswers201OnCreateAnd200OnMerge() {
        stubTable();
        when(facade.upsertRow(7L, "k", Map.of(), 0L))
            .thenReturn(new UpsertResult(new DataTableRow(1L, "k", Map.of()), true))
            .thenReturn(new UpsertResult(new DataTableRow(1L, "k", Map.of()), false));

        UpsertRowRequestModel request = new UpsertRowRequestModel().values(Map.of());

        assertEquals(HttpStatus.CREATED, controller.upsertRowByExternalId("orders", "k", null, request)
            .getStatusCode());
        assertEquals(HttpStatus.OK, controller.upsertRowByExternalId("orders", "k", null, request)
            .getStatusCode());
    }

    @Test
    void testBatchRejectsMoreThanAThousandRows() {
        stubTable();

        List<BatchRowModel> rows = Collections.nCopies(1001, new BatchRowModel().values(Map.of()));

        DataTableException dataTableException = assertThrows(
            DataTableException.class,
            () -> controller.batchRows("orders", null, new BatchRowsRequestModel().rows(rows)));

        assertEquals(DataTableErrorType.BATCH_TOO_LARGE.getErrorKey(), dataTableException.getErrorKey());
    }

    @Test
    void testDeleteRowsRequiresIdsAndCapsThem() {
        stubTable();

        assertThrows(DataTableException.class, () -> controller.deleteRows("orders", null, List.of()));
        assertThrows(DataTableException.class, () -> controller.deleteRows(
            "orders", null, LongStream.rangeClosed(1, 1001)
                .boxed()
                .toList()));
    }

    @Test
    void testExportSetsCsvHeaders() {
        stubTable();
        when(facade.exportCsv(7L, 0L)).thenReturn("external_id,total\n,1\n");

        ResponseEntity<String> response = controller.exportRows("orders", null);

        assertEquals("text/csv;charset=UTF-8", response.getHeaders()
            .getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("attachment; filename=\"orders.csv\"", response.getHeaders()
            .getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }
```

Also cover: `getRow` → 200; `deleteRow` false → `ROW_NOT_FOUND`; `getRowByExternalId` empty →
`ROW_NOT_FOUND`; `deleteRowByExternalId` false → `ROW_NOT_FOUND`; `clearRows` returns the count;
`importRows` returns `importedCount`.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```java
@RestController("com.bytechef.ee.automation.data.table.public_.web.rest.DataTableRowApiController")
@RequestMapping("${openapi.openAPIDefinition.base-path.automation:}/v1")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class DataTableRowApiController extends AbstractDataTableApiController implements DataTableRowApi {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_BATCH_SIZE = 1000;
    static final int MAX_PAGE_SIZE = 500;

    private final WorkspaceDataTableFacade workspaceDataTableFacade;
    private final DataTableApiSupport support;

    @SuppressFBWarnings("EI2")
    public DataTableRowApiController(WorkspaceDataTableFacade workspaceDataTableFacade, DataTableApiSupport support) {
        this.workspaceDataTableFacade = workspaceDataTableFacade;
        this.support = support;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity<Page> listRows(
        String name, @Nullable EnvironmentModel xEnvironment, @Nullable List<String> filter,
        @Nullable List<String> sort, @Nullable Integer pageNumber, @Nullable Integer pageSize) {

        Resolved resolved = resolve(name, xEnvironment);
        Set<String> queryable = queryableColumns(resolved.dataTableInfo());

        List<RowFilter> rowFilters = RowQueryParser.parseFilters(filter, queryable);
        List<RowSort> rowSorts = RowQueryParser.parseSorts(sort, queryable);

        int resolvedPageNumber = pageNumber == null ? 0 : Math.max(0, pageNumber);
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Page<DataTableRow> page = workspaceDataTableFacade.listRows(
            resolved.dataTableId(), rowFilters, rowSorts, resolvedPageNumber, resolvedPageSize,
            resolved.environmentId());

        return ResponseEntity.ok(page.map(DataTableModelMapper::toModel));
    }

    @Override
    public ResponseEntity<DataTableRowModel> createRow(
        String name, @Nullable EnvironmentModel xEnvironment, CreateRowRequestModel request) {

        Resolved resolved = resolve(name, xEnvironment);

        RowValuesValidator.validate(request.getValues(), resolved.dataTableInfo()
            .columns());

        if (request.getExternalId() != null) {
            RowValuesValidator.validateExternalId(request.getExternalId());
        }

        DataTableRow dataTableRow = workspaceDataTableFacade.insertRow(
            resolved.dataTableId(), request.getValues(), request.getExternalId(), resolved.environmentId());

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(DataTableModelMapper.toModel(dataTableRow));
    }

    @Override
    public ResponseEntity<DataTableRowModel> getRow(String name, Long id, @Nullable EnvironmentModel xEnvironment) {
        long dataTableId = support.resolveTableId(name);

        return ResponseEntity.ok(
            DataTableModelMapper.toModel(
                workspaceDataTableFacade.getRow(dataTableId, id, support.environmentId(xEnvironment))));
    }

    @Override
    public ResponseEntity<DataTableRowModel> updateRow(
        String name, Long id, @Nullable EnvironmentModel xEnvironment, UpdateRowRequestModel request) {

        Resolved resolved = resolve(name, xEnvironment);
        Map<String, Object> values = request.getValues() == null ? Map.of() : request.getValues();

        RowValuesValidator.validate(values, resolved.dataTableInfo()
            .columns());

        ExternalIdPatch externalIdPatch = null;

        JsonNullable<String> externalId = request.getExternalId();

        if (externalId != null && externalId.isPresent()) {
            String value = externalId.get();

            if (value != null) {
                RowValuesValidator.validateExternalId(value);
            }

            externalIdPatch = new ExternalIdPatch(value);
        }

        DataTableRow dataTableRow = workspaceDataTableFacade.updateRow(
            resolved.dataTableId(), id, values, externalIdPatch, resolved.environmentId());

        return ResponseEntity.ok(DataTableModelMapper.toModel(dataTableRow));
    }

    @Override
    public ResponseEntity<Void> deleteRow(String name, Long id, @Nullable EnvironmentModel xEnvironment) {
        boolean deleted = workspaceDataTableFacade.deleteRow(
            support.resolveTableId(name), id, support.environmentId(xEnvironment));

        if (!deleted) {
            throw new DataTableException("Row not found: id=" + id, DataTableErrorType.ROW_NOT_FOUND);
        }

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<DataTableRowModel> getRowByExternalId(
        String name, String externalId, @Nullable EnvironmentModel xEnvironment) {

        DataTableRow dataTableRow = workspaceDataTableFacade
            .fetchRowByExternalId(support.resolveTableId(name), externalId, support.environmentId(xEnvironment))
            .orElseThrow(() -> new DataTableException(
                "Row not found: externalId=" + externalId, DataTableErrorType.ROW_NOT_FOUND));

        return ResponseEntity.ok(DataTableModelMapper.toModel(dataTableRow));
    }

    @Override
    public ResponseEntity<DataTableRowModel> upsertRowByExternalId(
        String name, String externalId, @Nullable EnvironmentModel xEnvironment, UpsertRowRequestModel request) {

        Resolved resolved = resolve(name, xEnvironment);

        RowValuesValidator.validateExternalId(externalId);
        RowValuesValidator.validate(request.getValues(), resolved.dataTableInfo()
            .columns());

        UpsertResult upsertResult = workspaceDataTableFacade.upsertRow(
            resolved.dataTableId(), externalId, request.getValues(), resolved.environmentId());

        return ResponseEntity.status(upsertResult.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(DataTableModelMapper.toModel(upsertResult.row()));
    }

    @Override
    public ResponseEntity<Void> deleteRowByExternalId(
        String name, String externalId, @Nullable EnvironmentModel xEnvironment) {

        boolean deleted = workspaceDataTableFacade.deleteRowByExternalId(
            support.resolveTableId(name), externalId, support.environmentId(xEnvironment));

        if (!deleted) {
            throw new DataTableException("Row not found: externalId=" + externalId, DataTableErrorType.ROW_NOT_FOUND);
        }

        return ResponseEntity.noContent()
            .build();
    }

    @Override
    public ResponseEntity<BatchRowsResponseModel> batchRows(
        String name, @Nullable EnvironmentModel xEnvironment, BatchRowsRequestModel request) {

        Resolved resolved = resolve(name, xEnvironment);

        if (request.getRows()
            .size() > MAX_BATCH_SIZE) {

            throw new DataTableException(
                "A batch holds at most " + MAX_BATCH_SIZE + " rows", DataTableErrorType.BATCH_TOO_LARGE);
        }

        List<NewRow> newRows = new ArrayList<>(request.getRows()
            .size());

        for (BatchRowModel batchRowModel : request.getRows()) {
            RowValuesValidator.validate(batchRowModel.getValues(), resolved.dataTableInfo()
                .columns());

            if (batchRowModel.getExternalId() != null) {
                RowValuesValidator.validateExternalId(batchRowModel.getExternalId());
            }

            newRows.add(new NewRow(batchRowModel.getValues(), batchRowModel.getExternalId()));
        }

        CreateStrategy createStrategy = request.getCreateStrategy() == null
            ? CreateStrategy.INSERT : CreateStrategy.valueOf(request.getCreateStrategy()
                .name());

        List<DataTableRow> dataTableRows = workspaceDataTableFacade.insertRows(
            resolved.dataTableId(), newRows, createStrategy, resolved.environmentId());

        return ResponseEntity.ok(
            new BatchRowsResponseModel().rows(dataTableRows.stream()
                .map(DataTableModelMapper::toModel)
                .toList()));
    }

    @Override
    public ResponseEntity<DeleteRowsResponseModel> deleteRows(
        String name, @Nullable EnvironmentModel xEnvironment, List<Long> ids) {

        if (ids == null || ids.isEmpty()) {
            throw new DataTableException("ids is required", DataTableErrorType.ROW_VALUE_INVALID);
        }

        if (ids.size() > MAX_BATCH_SIZE) {
            throw new DataTableException(
                "At most " + MAX_BATCH_SIZE + " ids per request", DataTableErrorType.BATCH_TOO_LARGE);
        }

        List<Long> deletedIds = workspaceDataTableFacade.deleteRows(
            support.resolveTableId(name), ids, support.environmentId(xEnvironment));

        return ResponseEntity.ok(
            new DeleteRowsResponseModel().deletedCount(deletedIds.size())
                .deletedIds(deletedIds));
    }

    @Override
    public ResponseEntity<ClearRowsResponseModel> clearRows(String name, @Nullable EnvironmentModel xEnvironment) {
        long deletedCount = workspaceDataTableFacade.clearRows(
            support.resolveTableId(name), support.environmentId(xEnvironment));

        return ResponseEntity.ok(new ClearRowsResponseModel().deletedCount(deletedCount));
    }

    @Override
    public ResponseEntity<ImportRowsResponseModel> importRows(
        String name, @Nullable EnvironmentModel xEnvironment, String body) {

        int importedCount = workspaceDataTableFacade.importCsv(
            support.resolveTableId(name), body, support.environmentId(xEnvironment));

        return ResponseEntity.ok(new ImportRowsResponseModel().importedCount(importedCount));
    }

    @Override
    public ResponseEntity<String> exportRows(String name, @Nullable EnvironmentModel xEnvironment) {
        String csv = workspaceDataTableFacade.exportCsv(support.resolveTableId(name), support.environmentId(xEnvironment));

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".csv\"")
            .body(csv);
    }

    private Resolved resolve(String name, @Nullable EnvironmentModel xEnvironment) {
        long dataTableId = support.resolveTableId(name);
        long environmentId = support.environmentId(xEnvironment);

        return new Resolved(dataTableId, environmentId, workspaceDataTableFacade.getTable(dataTableId, environmentId));
    }

    private static Set<String> queryableColumns(DataTableInfo dataTableInfo) {
        Set<String> columns = new HashSet<>();

        columns.add(ReservedColumns.ID);
        columns.add(ReservedColumns.EXTERNAL_ID);

        for (ColumnSpec columnSpec : dataTableInfo.columns()) {
            columns.add(columnSpec.name()
                .toLowerCase(Locale.ROOT));
        }

        return columns;
    }

    private record Resolved(long dataTableId, long environmentId, DataTableInfo dataTableInfo) {
    }
}
```

`RowQuerySqlBuilder.column(...)` accepts `id` by name but not `external_id` — it checks
`columnTypes.containsKey(column) || ReservedColumns.ID.equals(column)`, and `columnTypes` is built
from every physical column (reserved ones included, via `requireColumns` → `columnTypeMap`), so
`external_id` is present as `STRING`. Verify that with an assertion in `DataTableRowExternalIdIntTest`:
filtering `external_id:EQ:k` through `listRows(ref, 10, 0, filters, sorts)` returns the keyed row.

- [ ] **Step 4: Run** — `./gradlew :server:ee:libs:automation:automation-data-table:automation-data-table-public-rest:test > /tmp/t12.log 2>&1; echo $?` → 0; then the IntTest addition:
`./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration --tests '*DataTableRowExternalIdIntTest' > /tmp/t12i.log 2>&1; echo $?` → 0.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/ee/libs/automation/automation-data-table/automation-data-table-public-rest server/libs/platform/platform-data-table
git commit -m "- Add the public data table row controller

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 13: The AUTOMATION key-type check

**Files:**
- Modify: `server/ee/libs/automation/automation-security-web/automation-security-web-impl/src/main/java/com/bytechef/ee/automation/security/web/authentication/AutomationApiKeyAuthenticationProvider.java`
- Test: `server/ee/libs/automation/automation-security-web/automation-security-web-impl/src/test/java/com/bytechef/ee/automation/security/web/authentication/AutomationApiKeyAuthenticationProviderTest.java`

- [ ] **Step 1: Failing test**

```java
@ExtendWith(MockitoExtension.class)
class AutomationApiKeyAuthenticationProviderTest {

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private AuthorityService authorityService;
    @Mock
    private UserService userService;

    @Test
    void testAnEmbeddedKeyIsRejected() {
        ApiKey apiKey = new ApiKey();

        apiKey.setType(PlatformType.EMBEDDED);

        when(apiKeyService.getApiKey("btc_x", 0L)).thenReturn(apiKey);

        AutomationApiKeyAuthenticationProvider provider = new AutomationApiKeyAuthenticationProvider(
            apiKeyService, authorityService, userService);

        assertThrows(
            BadCredentialsException.class,
            () -> provider.authenticate(new AutomationApiKeyAuthenticationToken("btc_x", 0L)));

        verifyNoInteractions(userService);
    }
}
```
(Read `AutomationApiKeyAuthenticationToken`'s constructors and `ApiKey`'s setters before finalising
the test; the shapes above are the ones the provider's `authenticate` reads.)

- [ ] **Step 2: Run to verify failure** — `./gradlew :server:ee:libs:automation:automation-security-web:automation-security-web-impl:test --tests '*AutomationApiKeyAuthenticationProviderTest' > /tmp/t13.log 2>&1; echo $?` → non-zero.

- [ ] **Step 3: Implement** — after the `apiKey` lookup in `authenticate`:

```java
        if (apiKey.getType() != PlatformType.AUTOMATION) {
            throw new BadCredentialsException("Automation API key required");
        }
```

- [ ] **Step 4: Run** → 0. **Step 5: Commit**

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add server/ee/libs/automation/automation-security-web
git commit -m "- Reject non-automation API keys on the automation public API

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 14: Docs wiring

**Files:**
- Modify: `docs/lib/openapi/index.ts`
- Modify: `docs/content/docs/platform/automation/data/data-tables.mdx`

- [ ] **Step 1:** add to `SPECS`, before `automation`:

```ts
  'automation-data-tables': path.join(
    SPECS_ROOT,
    'automation/automation-data-table/automation-data-table-public-rest/openapi.yaml',
  ),
```

Then find where the other `SPECS` keys become pages — `grep -rn "'automation'" docs --include='*.ts' --include='*.tsx' --include='*.mjs' | grep -v node_modules` — and add the new key wherever `automation` is listed (a page generator config and/or a sidebar `meta.json`), so the reference pages are generated at build. A key present in `SPECS` but absent from the generator list is exactly the silent-omission failure the spec warns about.

- [ ] **Step 2:** in `data-tables.mdx`, add a short section after the actions list:

```mdx
## Public REST API

Tables, columns and rows are also available over `/api/automation/v1` — see the
[Data Tables API reference](/docs/reference/api/automation-data-tables). Rows can be queried with
`filter=column:OPERATOR:value`, written in batches, imported and exported as CSV, and upserted on a
caller-supplied `externalId`.
```
(Adjust the link to the path the generator actually produces — check the generated `automation`
reference page's URL and mirror it.)

- [ ] **Step 3:** `cd docs && npm run build > /tmp/t14.log 2>&1; echo $?` → 0, and the new reference
page exists under the build output. Commit:

```bash
git add docs/lib/openapi/index.ts docs/content/docs/platform/automation/data/data-tables.mdx
git commit -m "- docs - Wire the public data table API reference into the docs build

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
(Add any generator/meta file touched in Step 1 to the same commit.)

---

### Task 15: CLI client module

**Files:**
- Create: `cli/clients/automation-data-table/build.gradle.kts`
- Modify: `settings.gradle.kts` (`include("cli:clients:automation-data-table")` after line 31), `cli/commands/automation/build.gradle.kts` (add `implementation(project(":cli:clients:automation-data-table"))`)
- Generated (committed): `cli/clients/automation-data-table/generated/`

- [ ] **Step 1:** copy `cli/clients/embedded-execution/build.gradle.kts` and change `inputSpec` to
`"${rootDir}/server/ee/libs/automation/automation-data-table/automation-data-table-public-rest/openapi.yaml"`,
the three packages to `com.bytechef.cli.client.automationdatatable{.api,.model,}`.

- [ ] **Step 2:** `./gradlew :cli:clients:automation-data-table:generateClient :cli:clients:automation-data-table:compileJava > /tmp/t15.log 2>&1; echo $?` → 0. Confirm `generated/src/main/java/com/bytechef/cli/client/automationdatatable/api/{DataTableApi,DataTableRowApi}.java` exist.

- [ ] **Step 3:** commit build files, then generated sources, separately:

```bash
git add settings.gradle.kts cli/clients/automation-data-table/build.gradle.kts cli/commands/automation/build.gradle.kts
git commit -m "- Add the CLI client module for the public data table API

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git add cli/clients/automation-data-table/generated
git commit -m "- Generate the CLI client for the public data table API

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 16: CLI commands

**Files:**
- Modify: `cli/commands/automation/src/main/java/com/bytechef/cli/command/automation/AutomationClientFactory.java`
- Create: `.../AutomationDataTableCommand.java`, `.../AutomationDataTableRowCommand.java`
- Test: `.../src/test/java/com/bytechef/cli/command/automation/AutomationDataTableCommandTest.java`, `AutomationDataTableRowCommandTest.java`
- Modify: `cli/README.md` (command reference)

**Interfaces:**
- `AutomationClientFactory.dataTableApi(CliConfig)` and `dataTableRowApi(CliConfig)` returning the
  generated `com.bytechef.cli.client.automationdatatable.api.{DataTableApi,DataTableRowApi}` built on
  `apiClient(config)`. Because the generated client's `ApiClient` class is per-package, the factory needs
  a second `apiClient` overload returning `com.bytechef.cli.client.automationdatatable.ApiClient` with the
  same base URI and `AuthInterceptor`.

- [ ] **Step 1: Failing tests** — one `StubApi` test per command, following
`AutomationExecutionCommandTest`. The cases that matter:

```java
    @Test
    void testTableCreateSendsColumnsAndTags() throws Exception {
        try (StubApi stub = StubApi.start(201, "{\"name\":\"orders\",\"columns\":[]}")) {
            int code = CliApplication.execute(
                "automation", "data-table", "create", "--workspace-id", "1", "--name", "orders",
                "--column", "total:NUMBER", "--column", "sku:STRING", "--tag", "hot",
                "--host", stub.host(), "--token", "btc_x", "--environment", "STAGING");

            assertEquals(0, code);
            assertEquals("/api/automation/v1/workspaces/1/data-tables", stub.lastPath());
            assertEquals("STAGING", stub.lastEnvironment());
            assertTrue(stub.lastBody()
                .contains("\"type\":\"NUMBER\""), stub.lastBody());
        }
    }

    @Test
    void testRowListForwardsFiltersVerbatim() throws Exception {
        try (StubApi stub = StubApi.start(200, "{\"content\":[],\"totalElements\":0}")) {
            int code = CliApplication.execute(
                "automation", "data-table", "row", "list", "--name", "orders", "--filter", "total:GTE:5",
                "--filter", "sku:IN:a,b", "--sort", "total:DESC", "--page-size", "10",
                "--host", stub.host(), "--token", "btc_x");

            assertEquals(0, code);
            assertTrue(stub.lastPath()
                .startsWith("/api/automation/v1/data-tables/orders/rows?"), stub.lastPath());
            assertTrue(stub.lastPath()
                .contains("filter=total%3AGTE%3A5"), stub.lastPath());
            assertTrue(stub.lastPath()
                .contains("pageSize=10"), stub.lastPath());
        }
    }

    @Test
    void testRowClearRefusesWithoutYes() throws Exception {
        try (StubApi stub = StubApi.start(200, "{\"deletedCount\":0}")) {
            int code = CliApplication.execute(
                "automation", "data-table", "row", "clear", "--name", "orders", "--host", stub.host(),
                "--token", "btc_x");

            assertEquals(1, code);
            assertNull(stub.lastPath());
        }
    }

    @Test
    void testRowImportSendsCsvBody() throws Exception {
        Path csv = Files.createTempFile("rows", ".csv");

        Files.writeString(csv, "total\n1\n");

        try (StubApi stub = StubApi.start(200, "{\"importedCount\":1}")) {
            int code = CliApplication.execute(
                "automation", "data-table", "row", "import", "--name", "orders", "--file", csv.toString(),
                "--host", stub.host(), "--token", "btc_x");

            assertEquals(0, code);
            assertEquals("/api/automation/v1/data-tables/orders/rows/import", stub.lastPath());
            assertEquals("total\n1\n", stub.lastBody());
        }
    }
```

`StubApi` needs to record the request body (`lastBody()`) — add it: read
`exchange.getRequestBody().readAllBytes()` into a field before responding.

- [ ] **Step 2: Run to verify failure** — `./gradlew :cli:commands:automation:test > /tmp/t16.log 2>&1; echo $?` → non-zero.

- [ ] **Step 3: Implement**

`AutomationDataTableCommand` (every command takes the standard `--profile/--host/--token/--environment`
and resolves `CliConfig` exactly like `AutomationExecutionCommand.resolve`; copy that method and the
two setters):

```java
@org.springframework.stereotype.Component
public class AutomationDataTableCommand {

    // configPath, environmentVariables, setters and resolve(...) as in AutomationExecutionCommand

    @Command(name = "automation data-table list", description = "List data tables of a workspace.")
    public void list(
        @Option(longName = "workspace-id") Long workspaceId,
        @Option(longName = "tag") String tag,
        @Option(longName = "profile") String profile,
        @Option(longName = "host") String host,
        @Option(longName = "token") String token,
        @Option(longName = "environment") String environment,
        @Option(longName = "output", defaultValue = "json") String output) {

        CliConfig config = resolve(profile, host, token, environment, workspaceId);

        try {
            List<DataTableModel> tables = AutomationClientFactory.dataTableApi(config)
                .listDataTables(config.workspaceId(), environmentModel(config), tag);

            new OutputRenderer(System.out).render(tables, output);
        } catch (ApiException e) {
            throw AutomationClientFactory.toCliException(e);
        }
    }

    @Command(name = "automation data-table get", description = "Get a data table.")
    public void get(@Option(longName = "name", required = true) String name, /* standard options */) { ... getDataTable(name, environmentModel(config)) ... }

    @Command(name = "automation data-table create", description = "Create a data table.")
    public void create(
        @Option(longName = "workspace-id") Long workspaceId,
        @Option(longName = "name", required = true) String name,
        @Option(longName = "description") String description,
        @Option(longName = "column", required = true, arity = CommandRegistration.OptionArity.ONE_OR_MORE) String[] columns,
        @Option(longName = "tag", arity = CommandRegistration.OptionArity.ZERO_OR_MORE) String[] tags,
        /* standard options */) {

        CreateDataTableRequestModel request = new CreateDataTableRequestModel()
            .name(name)
            .description(description)
            .columns(Arrays.stream(columns)
                .map(AutomationDataTableCommand::parseColumn)
                .toList())
            .tags(tags == null ? List.of() : Arrays.asList(tags));
        ...createDataTable(config.workspaceId(), environmentModel(config), request)
    }

    @Command(name = "automation data-table update", ...)   // --name, --description, --tag...  → updateDataTable
    @Command(name = "automation data-table delete", ...)   // --name → deleteDataTable; prints "Data table deleted."
    @Command(name = "automation data-table column add", ...)    // --name --column name:TYPE → createColumn
    @Command(name = "automation data-table column remove", ...) // --name --column → deleteColumn
    @Command(name = "automation data-table column rename", ...) // --name --column --new-name → renameColumn

    static DataTableColumnModel parseColumn(String spec) {
        int separator = spec.indexOf(':');

        if (separator <= 0 || separator == spec.length() - 1) {
            throw new CliException(1, "A column is name:TYPE, got '" + spec + "'");
        }

        try {
            return new DataTableColumnModel()
                .name(spec.substring(0, separator))
                .type(ColumnTypeModel.fromValue(spec.substring(separator + 1)
                    .toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new CliException(1, "Unknown column type in '" + spec + "'");
        }
    }

    static EnvironmentModel environmentModel(CliConfig config) {
        return config.environment() == null ? null : EnvironmentModel.fromValue(config.environment()
            .name());
    }
}
```
(Check `CliConfig`'s accessor for the environment — `AutomationExecutionCommand` passes `null` for
`xEnvironment` and lets `AuthInterceptor` set the `X-Environment` header from the profile; if that is
the mechanism, pass `null` here too and drop `environmentModel`. Read `AuthInterceptor` first.)

`AutomationDataTableRowCommand` — the ten row commands from spec §8, each a thin call:

| Command | Options | Client call |
|---|---|---|
| `row list` | `--name`, `--filter…`, `--sort…`, `--page`, `--page-size`, `--output` | `listRows(name, env, filters, sorts, page, pageSize)` |
| `row get` | `--name`, `--id` \| `--external-id` | `getRow` / `getRowByExternalId` |
| `row insert` | `--name`, `--values <json>`, `--external-id` | `createRow` |
| `row update` | `--name`, `--id`, `--values <json>`, `--external-id` | `updateRow` (send `externalId` only when given) |
| `row upsert` | `--name`, `--external-id`, `--values <json>` | `upsertRowByExternalId` |
| `row delete` | `--name`, `--id` \| `--ids a,b,c` \| `--external-id` | `deleteRow` / `deleteRows` / `deleteRowByExternalId` |
| `row batch` | `--name`, `--file rows.json`, `--strategy` | `batchRows` (file = JSON array of `{values, externalId?}`) |
| `row clear` | `--name`, `--yes` | `clearRows`; without `--yes` → `CliException(1, "Refusing to clear without --yes")` before any request |
| `row import` | `--name`, `--file data.csv` | `importRows(name, env, Files.readString(file))` |
| `row export` | `--name`, `--file` | `exportRows`; write to the file, or `System.out` when omitted |

`--values` is parsed with a Jackson `ObjectMapper` into `Map<String, Object>` (`jackson-databind` is
already on the client module's classpath); a parse failure is `CliException(1, "…")`. `--ids` uses
`CliArgs.splitCsvLong`. Every `ApiException` goes through `AutomationClientFactory.toCliException`.

- [ ] **Step 4: Run** — `./gradlew :cli:commands:automation:test :cli:cli-app:installDist > /tmp/t16.log 2>&1; echo $?` → 0. Then smoke the binary against the stub-free help:
`cli/cli-app/build/install/bytechef/bin/bytechef help "automation data-table row list"` prints the options.

- [ ] **Step 5:** add the commands to `cli/README.md`'s automation section (one line each, same format
as the existing `automation execution` entries). Commit:

```bash
./gradlew spotlessApply > /dev/null 2>&1
git add cli
git commit -m "- Add the data table and row CLI commands

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 17: Whole-tree verification

- [ ] **Step 1:** `./gradlew spotlessApply > /tmp/t17s.log 2>&1; echo $?` → 0; `git status --short` shows nothing (formatting already committed).
- [ ] **Step 2:** `./gradlew check --continue > /tmp/t17.log 2>&1; echo $?` → 0; `grep -c '^> Task .* FAILED' /tmp/t17.log` → 0. SpotBugs findings are read from `build/reports/spotbugs/*.html`, not the stale XML.
- [ ] **Step 3:** `./gradlew :server:libs:platform:platform-data-table:platform-data-table-service:testIntegration :server:libs:automation:automation-data-table:automation-data-table-service:testIntegration > /tmp/t17i.log 2>&1; echo $?` → 0.
- [ ] **Step 4:** boot the monolith (`./gradlew -p server/apps/server-app bootRun` with the dev infra up), create an AUTOMATION API key in the UI, then walk the surface with `curl` against `http://localhost:9555/api/automation/v1`:
  create a table → add a column → insert a row → upsert twice by external id (expect 201 then 200) → `GET …/rows?filter=externalId:EQ:k` → export CSV (first column `external_id`) → clear → delete the table. Each answer's status must match the spec table; paste the transcript into the PR description.
- [ ] **Step 5:** `cd docs && npm run build` → 0 and the reference page renders the 21 operations.
- [ ] **Step 6:** open the PR against `master` on `bytechefhq/bytechef` with a body listing the three
  user-visible behaviour changes from spec §10 and the curl transcript. Push to the upstream remote,
  not a fork (a fork PR gets no `SONAR_TOKEN`).

---

## Self-review against the spec

| Spec section | Task |
|---|---|
| §3 module, thin controllers, no controller `@PreAuthorize` | 9, 10, 11, 12 |
| §4.1 key-type check, `X-Environment` default | 13, 10 (`DataTableApiSupport.environmentId`) |
| §4.2 name identity, strict validation, reserved names | 10 (`validateTableName`/`validateColumnName`), 7 |
| §4.3 resources, typing, merge semantics, pagination, filter grammar, `where` reserved | 9 (yaml), 10 (parser, mapper), 12 |
| §4.4 nested workspace collection | 9 |
| §5 DDL endpoints, PATCH description/tags, absent operations | 11, 7, 8 |
| §6.1 reserved column, partial index, backfill | 2, 3 |
| §6.2 row endpoints incl. clear/batch/import/export | 12, 6 |
| §6.3 facade/service methods | 4, 5, 6, 7, 8 |
| §7 errors, 507, 404-for-403 on item URLs | 1, 10 |
| §8 CLI | 15, 16 |
| §9 testing (ownership cases, backfill IntTest, security test) | 5, 3, 13, every task's tests |
| §10 wiring, docs, visible changes | 9, 14, 17 |

Deviations recorded in the plan and to be folded back into the spec by Task 1's amendment step:
error keys renumbered (100–102 pre-exist), controller-local `@ExceptionHandler` instead of a
package-scoped advice, and two extra facade reads (`getTagsByTableId`, `getWorkspaceId`) that the
table resource's `tags` field needs.
