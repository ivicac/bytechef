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

import com.bytechef.exception.ExecutionException;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.audit.DataTableAuditEvent;
import com.bytechef.platform.data.table.configuration.audit.DataTableAuditPublisher;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.exception.DataTableErrorType;
import com.bytechef.platform.data.table.configuration.repository.DataTableRepository;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.domain.ReservedColumns;
import com.bytechef.platform.data.table.internal.PhysicalTableNaming;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * @author Ivica Cardic
 */
@Service
public class DataTableServiceImpl implements DataTableService {

    private static final Logger log = LoggerFactory.getLogger(DataTableServiceImpl.class);

    /**
     * A whole physical name, pulled apart: pool token, environment id, base name.
     *
     * <p>
     * Needed because the {@code LIKE} pattern the candidates are read with cannot tell an environment segment from part
     * of a longer base name -- {@code dt\_%\_orders} matches {@code dt_0_my_orders} as readily as {@code dt_0_orders}.
     * The quantifiers are possessive so that the engine is told what the grammar already guarantees: an environment
     * segment is digits and a base name cannot begin with one, so there is exactly one way to split any name and
     * nothing for backtracking to find.
     */
    private static final Pattern PHYSICAL_NAME = Pattern.compile("^(dt|edt)_(\\d++)_([a-z_][a-z0-9_]*+)$");

    private final DataTableAuditPublisher dataTableAuditPublisher;
    private final DataTableRepository dataTableRepository;
    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings("EI")
    public DataTableServiceImpl(
        DataTableAuditPublisher dataTableAuditPublisher, DataTableRepository dataTableRepository,
        JdbcTemplate jdbcTemplate) {

        this.dataTableAuditPublisher = dataTableAuditPublisher;
        this.dataTableRepository = dataTableRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Adds a column to an existing data table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void addColumn(String baseName, ColumnSpec columnSpec, long environmentId, PlatformType platformType) {
        validateBaseName(baseName);
        Assert.notNull(columnSpec, "column must not be null");

        DataTable dataTable = getDataTable(baseName, platformType);

        DataTableRef dataTableRef = DataTableRef.unowned(baseName, environmentId, platformType);

        String sql = "ALTER TABLE " + escapeIdentifier(dataTableRef.physicalName()) + " ADD COLUMN " +
            escapeIdentifier(columnSpec.name()) + " " + sqlType(columnSpec.type());

        jdbcTemplate.execute(sql);

        dataTableAuditPublisher.publish(
            DataTableAuditEvent.DATA_TABLE_COLUMN_ADDED, dataTable.getId(), Map.of("columnName", columnSpec.name()));
    }

    /**
     * Creates a new data table with the specified columns.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @Transactional
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void createTable(
        String baseName, String description, List<ColumnSpec> columnSpecs, long environmentId,
        PlatformType platformType) {

        validateBaseName(baseName);

        Assert.notEmpty(columnSpecs, "columns must not be empty");

        boolean hasReservedColumn = columnSpecs.stream()
            .anyMatch(columnSpec -> ReservedColumns.isReserved(columnSpec.name()));

        Assert.isTrue(!hasReservedColumn, "Column names " + ReservedColumns.all() + " are reserved");

        DataTableRef dataTableRef = DataTableRef.unowned(baseName, environmentId, platformType);

        createPhysicalTable(dataTableRef.physicalName(), columnSpecs);

        long dataTableId = register(baseName, description, platformType);

        dataTableAuditPublisher.publish(
            DataTableAuditEvent.DATA_TABLE_CREATED, dataTableId, Map.of("name", dataTableRef.baseName()));
    }

    /**
     * Drops an existing data table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void dropTable(String baseName, long environmentId, PlatformType platformType) {
        validateBaseName(baseName);

        Optional<DataTable> dataTableOptional = fetchDataTable(baseName, platformType);

        if (dataTableOptional.isEmpty()) {
            return;
        }

        DataTable dataTable = dataTableOptional.get();

        DataTableRef dataTableRef = DataTableRef.unowned(baseName, environmentId, platformType);

        String sql = "DROP TABLE IF EXISTS " + escapeIdentifier(dataTableRef.physicalName());

        jdbcTemplate.execute(sql);

        // The registry row is the LOGICAL table and outlives any one environment's instance of it, so it goes only
        // once the last environment's physical table has been dropped.
        if (!hasPhysicalTablesForBaseName(baseName, platformType)) {
            dataTableRepository.deleteById(dataTable.getId());

            dataTableAuditPublisher.publish(DataTableAuditEvent.DATA_TABLE_DELETED, dataTable.getId(), Map.of());
        }
    }

    /**
     * Duplicates an existing data table to a new table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @Transactional
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void duplicateTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType) {

        validateBaseName(fromBaseName);
        validateBaseName(toBaseName);

        DataTable fromDataTable = getDataTable(fromBaseName, platformType);

        DataTableRef fromDataTableRef = DataTableRef.unowned(fromBaseName, environmentId, platformType);
        DataTableRef toDataTableRef = DataTableRef.unowned(toBaseName, environmentId, platformType);

        String fromPhysicalName = fromDataTableRef.physicalName();
        String toPhysicalName = toDataTableRef.physicalName();

        List<ColumnSpec> columnSpecs = listColumns(fromPhysicalName)
            .stream()
            .filter(columnSpec -> !ReservedColumns.isReserved(columnSpec.name()))
            .toList();

        createPhysicalTable(toPhysicalName, columnSpecs);

        // The owner columns are copied along with the user ones, so a duplicate is the source table's rows AND whose
        // each of them is. Dropping them would leave every copied row unowned: readable by every account, since the
        // read predicate admits unowned rows, and writable by none, since the write predicate matches on the owner.
        List<String> copiedColumnNames = new ArrayList<>();

        copiedColumnNames.add(ReservedColumns.OWNER_ID);
        copiedColumnNames.add(ReservedColumns.OWNER_TYPE);
        copiedColumnNames.addAll(columnSpecs.stream()
            .map(ColumnSpec::name)
            .toList());

        String columnList = copiedColumnNames.stream()
            .map(DataTableServiceImpl::escapeIdentifier)
            .collect(Collectors.joining(", "));

        String insertSql = "INSERT INTO " + escapeIdentifier(toPhysicalName) + " (" + columnList + ") SELECT " +
            columnList + " FROM " + escapeIdentifier(fromPhysicalName);

        jdbcTemplate.execute(insertSql);

        register(toBaseName, fromDataTable.getDescription(), platformType);
    }

    @Override
    public String getBaseNameById(long id) {
        DataTable dataTable = dataTableRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Data table with id=" + id + " not found"));

        return dataTable.getName();
    }

    @Override
    public long getIdByBaseName(String baseName, PlatformType platformType) {
        DataTable dataTable = fetchDataTable(baseName, platformType)
            .orElseThrow(() -> new ExecutionException(
                "Unable to find table " + baseName, DataTableErrorType.DATA_TABLE_NOT_FOUND));

        return dataTable.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType) {
        String normalizedBaseName = normalizeBaseName(baseName);

        return dataTableRepository.findByNameAndPlatformType(normalizedBaseName, platformType.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataTable> fetchDataTable(DataTableRef dataTableRef) {
        String baseName = dataTableRef.baseName();
        PlatformType platformType = dataTableRef.platformType();

        return dataTableRepository.findByNameAndPlatformType(baseName, platformType.ordinal());
    }

    /**
     * The owner is carried into the ref untouched and never consulted here: it is the run owner the row layer narrows
     * on, and the table it names is the one physical table the base name has in this environment and pool.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<DataTableResolution> fetchDataTableResolution(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {

        return fetchDataTable(baseName, platformType)
            .map(
                dataTable -> new DataTableResolution(
                    dataTable.getId(),
                    new DataTableRef(baseName, environmentId, platformType, owner.orElse(null))));
    }

    private DataTable getDataTable(String baseName, PlatformType platformType) {
        return fetchDataTable(baseName, platformType)
            .orElseThrow(() -> new ExecutionException(
                "Unable to find table " + baseName, DataTableErrorType.DATA_TABLE_NOT_FOUND));
    }

    /**
     * The physical table a base name addresses, having first confirmed that the registry knows the name. The lookup is
     * not redundant: a ref can be built for any well-formed name, and an ALTER against an unregistered one would fail
     * as a raw SQL error rather than as a missing table.
     */
    private DataTableRef getDataTableRef(String baseName, long environmentId, PlatformType platformType) {
        getDataTable(baseName, platformType);

        return DataTableRef.unowned(baseName, environmentId, platformType);
    }

    /**
     * Every registered table in one environment and pool. Nothing is filtered out by account: an account is separated
     * from another inside a table, by the row predicate, and never by which tables it can see.
     */
    @Override
    public List<DataTableInfo> listTables(long environmentId, PlatformType platformType) {
        String prefix = PhysicalTableNaming.prefix(platformType, environmentId);

        // LIKE treats _ as a wildcard, so the startsWith below is the real guard; the pattern only narrows the scan.
        String sqlTables = "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' AND table_name LIKE ?";

        List<String> tableNames = jdbcTemplate.query(
            sqlTables, ps -> ps.setString(1, prefix + "%"), (rs, rowNum) -> rs.getString("table_name"));

        List<DataTableInfo> dataTableInfos = new ArrayList<>();

        for (String tableName : tableNames) {
            if (!tableName.startsWith(prefix)) {
                continue;
            }

            String baseName = tableName.substring(prefix.length());

            DataTable dataTable = dataTableRepository
                .findByNameAndPlatformType(baseName, platformType.ordinal())
                .orElse(null);

            if (dataTable == null) {
                log.warn("Skipping unregistered physical data table '{}' in environment {}", baseName, environmentId);

                continue;
            }

            List<ColumnSpec> columnSpecs = listColumns(tableName)
                .stream()
                .filter(columnSpec -> !ReservedColumns.isReserved(columnSpec.name()))
                .toList();

            dataTableInfos.add(
                new DataTableInfo(
                    dataTable.getId(), baseName, dataTable.getDescription(), columnSpecs,
                    dataTable.getLastModifiedDate()));
        }

        return dataTableInfos;
    }

    /**
     * Removes a column from an existing data table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void removeColumn(
        String baseName, String columnName, long environmentId, PlatformType platformType) {

        validateBaseName(baseName);
        Assert.hasText(columnName, "columnName must not be empty");

        DataTableRef dataTableRef = getDataTableRef(baseName, environmentId, platformType);

        String sql = "ALTER TABLE " + escapeIdentifier(dataTableRef.physicalName()) + " DROP COLUMN " +
            escapeIdentifier(columnName);

        jdbcTemplate.execute(sql);
    }

    /**
     * Renames a column in an existing data table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void renameColumn(
        String baseName, String fromColumnName, String toColumnName, long environmentId,
        PlatformType platformType) {

        validateBaseName(baseName);
        Assert.hasText(fromColumnName, "fromColumnName must not be empty");
        Assert.hasText(toColumnName, "toColumnName must not be empty");
        Assert.isTrue(!ReservedColumns.isReserved(fromColumnName), "Reserved columns cannot be renamed");
        Assert.isTrue(!ReservedColumns.isReserved(toColumnName), "Cannot rename to a reserved name");

        DataTableRef dataTableRef = getDataTableRef(baseName, environmentId, platformType);

        String sql = "ALTER TABLE " + escapeIdentifier(dataTableRef.physicalName()) + " RENAME COLUMN " +
            escapeIdentifier(fromColumnName) + " TO " + escapeIdentifier(toColumnName);

        jdbcTemplate.execute(sql);
    }

    /**
     * Renames an existing data table.
     *
     * <p>
     * <b>Security Note:</b> The SQL_INJECTION_SPRING_JDBC suppression is safe because all identifiers are validated
     * through {@link #escapeIdentifier(String)} and {@link #validateBaseName(String)} which enforce a strict allowlist
     * pattern {@code [a-z_][a-z0-9_]*}, preventing SQL injection.
     */
    @Override
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void renameTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType) {

        validateBaseName(fromBaseName);
        validateBaseName(toBaseName);

        DataTable dataTable = getDataTable(fromBaseName, platformType);

        DataTableRef fromDataTableRef = DataTableRef.unowned(fromBaseName, environmentId, platformType);
        DataTableRef toDataTableRef = DataTableRef.unowned(toBaseName, environmentId, platformType);

        String sql = "ALTER TABLE " + escapeIdentifier(fromDataTableRef.physicalName()) + " RENAME TO " +
            escapeIdentifier(toDataTableRef.physicalName());

        jdbcTemplate.execute(sql);

        dataTable.setName(toDataTableRef.baseName());

        dataTableRepository.save(dataTable);
    }

    /**
     * Whether any physical instance of {@code baseName} remains for this pool, in any environment. Scans only this
     * pool's prefix -- without that, dropping the EMBEDDED "orders" would find the AUTOMATION "dt_0_orders", conclude
     * an instance remains, and leave the EMBEDDED registry row behind as an orphan.
     */
    private boolean hasPhysicalTablesForBaseName(String baseName, PlatformType platformType) {
        List<String> physicalNames = physicalNames(baseName, platformType);

        return !physicalNames.isEmpty();
    }

    /**
     * Every physical instance of a base name, across every environment in this pool.
     */
    private List<String> physicalNames(String baseName, PlatformType platformType) {
        String normalizedBaseName = baseName.toLowerCase(Locale.ROOT);
        String escapedBaseName = normalizedBaseName.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        String pattern = PhysicalTableNaming.poolToken(platformType) + "\\_%\\_" + escapedBaseName;

        String sql = "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' AND table_name LIKE ? ESCAPE '\\'";

        List<String> tableNames = jdbcTemplate.query(
            sql, ps -> ps.setString(1, pattern), (resultSet, rowNum) -> resultSet.getString("table_name"));

        // The LIKE pattern's '%' swallows more than an environment segment, so the candidates are matched here
        // against a parsed name rather than left to the pattern.
        return tableNames.stream()
            .filter(tableName -> matchesPoolAndBaseName(tableName, platformType, normalizedBaseName))
            .toList();
    }

    /**
     * Whether a physical table name belongs to this pool and this base name, in any environment.
     */
    private static boolean matchesPoolAndBaseName(String tableName, PlatformType platformType, String baseName) {
        Matcher matcher = PHYSICAL_NAME.matcher(tableName);

        if (!matcher.matches()) {
            return false;
        }

        String poolToken = matcher.group(1);

        if (!poolToken.equals(PhysicalTableNaming.poolToken(platformType))) {
            return false;
        }

        return baseName.equals(matcher.group(3));
    }

    /**
     * Writes the {@code data_table} row that gives a physical table its id. Nothing else creates one, and everything
     * keyed on a data table id -- tags, webhooks, the workspace relation, {@code listTables} -- needs it to exist.
     *
     * <p>
     * Called after the DDL and inside the same transaction, so a failed CREATE leaves no registry row and a failed
     * insert leaves no physical table.
     */
    private long register(String baseName, @Nullable String description, PlatformType platformType) {
        Assert.hasText(baseName, "baseName required");

        String normalizedBaseName = normalizeBaseName(baseName);

        Optional<DataTable> existingDataTable =
            dataTableRepository.findByNameAndPlatformType(normalizedBaseName, platformType.ordinal());

        return existingDataTable
            .map(DataTable::getId)
            .orElseGet(() -> {
                DataTable dataTable = new DataTable();

                dataTable.setName(normalizedBaseName);
                dataTable.setDescription(description);
                dataTable.setPlatformType(platformType);

                DataTable savedDataTable = dataTableRepository.save(dataTable);

                return savedDataTable.getId();
            });
    }

    /**
     * Creates one physical table: the table itself and the index its owner column is read through.
     *
     * <p>
     * Extracted because {@code createTable} and {@code duplicateTable} used to hold near-copies of the statement, and a
     * column added to one and forgotten in the other produces a duplicate whose rows the row service cannot read. One
     * helper makes that drift impossible rather than merely unlikely.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void createPhysicalTable(String physicalName, List<ColumnSpec> columnSpecs) {
        jdbcTemplate.execute(buildCreateTableSql(physicalName, columnSpecs));
        jdbcTemplate.execute(buildOwnerIndexSql(physicalName));
    }

    /**
     * The one CREATE TABLE statement a data table is ever built from.
     *
     * <p>
     * Every physical table carries {@code owner_id} / {@code owner_type}, in both pools. Uniformly, and that uniformity
     * is the point: these two columns are the only thing separating one account's rows from another's, so every table
     * has to carry them and no row operation has to ask whether this one does.
     */
    static String buildCreateTableSql(String physicalName, List<ColumnSpec> columnSpecs) {
        String userColumnsSql = columnSpecs.stream()
            .map(columnSpec -> escapeIdentifier(columnSpec.name()) + " " + sqlType(columnSpec.type()))
            .collect(Collectors.joining(", "));

        return "CREATE TABLE " + escapeIdentifier(physicalName) + " (\"id\" BIGSERIAL PRIMARY KEY, " +
            escapeIdentifier(ReservedColumns.OWNER_ID) + " BIGINT, " +
            escapeIdentifier(ReservedColumns.OWNER_TYPE) + " INT" +
            (userColumnsSql.isEmpty() ? "" : ", " + userColumnsSql) + ")";
    }

    /**
     * The index every row read and every row write narrows on.
     *
     * <p>
     * Deliberately unnamed, so Postgres derives the name itself. A name of our own would be the physical name plus a
     * suffix, and a physical name may already be 63 bytes -- the longest identifier Postgres keeps. It truncates rather
     * than refusing, so two long tables would silently ask for the same index name and the second CREATE would fail.
     */
    static String buildOwnerIndexSql(String physicalName) {
        return "CREATE INDEX ON " + escapeIdentifier(physicalName) + " (" +
            escapeIdentifier(ReservedColumns.OWNER_ID) + ")";
    }

    private static String escapeIdentifier(String identifier) {
        Assert.hasText(identifier, "identifier must not be empty");

        String normalized = identifier.toLowerCase(Locale.ROOT);

        Assert.isTrue(normalized.matches("[a-z_][a-z0-9_]*"), "Invalid identifier: " + identifier);

        return '"' + normalized + '"';
    }

    private List<ColumnSpec> listColumns(String physicalName) {
        String sql = "SELECT column_name, data_type FROM information_schema.columns " +
            "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position";

        return jdbcTemplate.query(sql, ps -> ps.setString(1, physicalName), (rs, rowNum) -> {
            String name = rs.getString("column_name");
            String dataType = rs.getString("data_type");

            return new ColumnSpec(name, mapType(dataType));
        });
    }

    private ColumnType mapType(String pgType) {
        String lowerCaseType = pgType.toLowerCase(Locale.ROOT);

        if (lowerCaseType.startsWith("timestamp")) {
            return ColumnType.DATE_TIME;
        }

        if (lowerCaseType.equals("boolean") || lowerCaseType.equals("bool")) {
            return ColumnType.BOOLEAN;
        }

        switch (lowerCaseType) {
            case "integer", "int4", "smallint", "int2", "bigint", "int8", "serial", "bigserial" -> {
                return ColumnType.INTEGER;
            }
            case "numeric", "decimal", "double precision", "real" -> {
                return ColumnType.NUMBER;
            }
            case "date" -> {
                return ColumnType.DATE;
            }
            default -> {
                return ColumnType.STRING;
            }
        }
    }

    private static String sqlType(ColumnType type) {
        return switch (type) {
            case STRING -> "VARCHAR(255)";
            case NUMBER -> "DECIMAL(38,9)";
            case INTEGER -> "INTEGER";
            case DATE -> "DATE";
            case DATE_TIME -> "TIMESTAMP";
            case BOOLEAN -> "BOOLEAN";
        };
    }

    private static String normalizeBaseName(String baseName) {
        return baseName.toLowerCase(Locale.ROOT);
    }

    static void validateBaseName(String baseName) {
        Assert.hasText(baseName, "baseName must not be empty");

        String normalized = baseName.toLowerCase(Locale.ROOT);

        Assert.isTrue(!normalized.startsWith("dt_"), "baseName must not start with 'dt_'");
        Assert.isTrue(normalized.matches("[a-z_][a-z0-9_]*"), "Invalid base name: " + baseName);
    }
}
