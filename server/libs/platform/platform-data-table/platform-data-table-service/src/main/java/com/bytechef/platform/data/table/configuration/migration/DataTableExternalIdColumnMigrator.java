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
 * Adds the reserved {@code external_id} column and its upsert index to physical data tables created before the column
 * existed. The table set is discovered from {@code information_schema} rather than declared, which is why this is Java
 * rather than plain changeset XML -- {@link DataTableExternalIdColumnChange} runs it as a Liquibase
 * {@code customChange}, so it is still recorded in {@code databasechangelog} and still runs once per tenant schema.
 *
 * <p>
 * Both pool prefixes are swept, {@code dt_} for AUTOMATION and {@code edt_} for EMBEDDED. The index is
 * {@code (owner_id, external_id) NULLS NOT DISTINCT WHERE external_id IS NOT NULL}, the identical shape
 * {@code createTable} uses for a table built today. {@code NULLS NOT DISTINCT} is for {@code owner_id}, whose NULL
 * means the automation pool and must still be unique; the {@code WHERE} predicate is for {@code external_id}, because
 * on the day this migration runs every existing row has a NULL there, and without the predicate they would all collapse
 * into one index key.
 *
 * <p>
 * A table is skipped only when the column AND the index are both present. Before this branch existed a user column
 * literally named {@code external_id} was perfectly legal -- {@code addColumn} validated no names at all -- and such a
 * table would have satisfied a column-only guard while never receiving an index, leaving {@code upsertRow} to fail at
 * runtime with "there is no unique or exclusion constraint matching the ON CONFLICT specification". Where such a column
 * already holds duplicate {@code (owner_id, external_id)} pairs the index creation fails and the migration stops: loud,
 * at upgrade time, is the correct outcome for a data-integrity migration.
 *
 * <p>
 * Idempotent, and scoped to one schema per call. Deliberately not a Spring bean: Liquibase instantiates the change
 * reflectively, long before an application context exists.
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

    /**
     * Migrates the connection's current schema.
     *
     * @return the number of tables altered; 0 when every table already carries both the column and the index
     */
    public int migrate() {
        return migrate(null);
    }

    /**
     * Migrates one named schema, or the connection's current schema when {@code schemaName} is null.
     *
     * <p>
     * The schema is named rather than inherited because Liquibase's per-tenant run sets its own default schema without
     * necessarily moving the JDBC connection's {@code search_path}. Trusting {@code current_schema()} there would
     * quietly migrate the wrong schema and report success.
     *
     * @return the number of tables altered; 0 when every table already carries both the column and the index
     */
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
            boolean hasExternalIdColumn = hasExternalIdColumn(schema, tableName);

            if (hasExternalIdColumn && hasExternalIdIndex(schema, tableName)) {
                continue;
            }

            String qualifiedName = quote(schema) + "." + quote(tableName);

            if (!hasExternalIdColumn) {
                jdbcTemplate.execute(
                    "ALTER TABLE " + qualifiedName + " ADD COLUMN IF NOT EXISTS " + quote(ReservedColumns.EXTERNAL_ID)
                        + " VARCHAR(255)");
            }

            // Unnamed, so Postgres derives the name: a physical table name may already be the 63 bytes Postgres keeps,
            // and a name of ours plus a suffix would be truncated into a collision with the next long table's. Cannot
            // carry IF NOT EXISTS either -- Postgres requires a name for that -- so idempotency comes from the
            // hasExternalIdIndex guard above, not from this statement.
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

    /**
     * Whether the table already carries the upsert index, asked of {@code pg_index} rather than by name: the index is
     * created unnamed, so Postgres derived whatever name it liked and no name of ours would find it. A unique index
     * whose two key columns are {@code owner_id} and {@code external_id}, in that order, is what {@code ON CONFLICT}
     * needs as its arbiter, so that is exactly what is asked for.
     */
    private boolean hasExternalIdIndex(String schema, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_index pgIndex "
                + "JOIN pg_class tableClass ON tableClass.oid = pgIndex.indrelid "
                + "JOIN pg_namespace tableNamespace ON tableNamespace.oid = tableClass.relnamespace "
                + "WHERE tableNamespace.nspname = ? AND tableClass.relname = ? "
                + "AND pgIndex.indisunique AND pgIndex.indnkeyatts = 2 "
                + "AND (SELECT attribute.attname FROM pg_attribute attribute "
                + "WHERE attribute.attrelid = tableClass.oid AND attribute.attnum = pgIndex.indkey[0]) = ? "
                + "AND (SELECT attribute.attname FROM pg_attribute attribute "
                + "WHERE attribute.attrelid = tableClass.oid AND attribute.attnum = pgIndex.indkey[1]) = ?",
            Integer.class, schema, tableName, ReservedColumns.OWNER_ID, ReservedColumns.EXTERNAL_ID);

        return count != null && count > 0;
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

    /**
     * Schema and table names reach this from {@code information_schema} or from Liquibase and never from a user, but
     * they are still held to the identifier pattern the rest of the data table code enforces.
     */
    private String quote(String identifier) {
        Assert.hasText(identifier, "identifier must not be empty");

        String normalizedName = identifier.toLowerCase(Locale.ROOT);

        Assert.isTrue(normalizedName.matches("[a-z_][a-z0-9_]*"), "Invalid identifier: " + identifier);

        return '"' + normalizedName + '"';
    }
}
