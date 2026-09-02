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
import com.bytechef.platform.constant.OwnerType;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
@SuppressFBWarnings(
    value = "REDOS",
    justification = "PHYSICAL_NAME is possessive throughout and its optional group is atomic, so the engine cannot " +
        "backtrack at all; the detector is syntactic and does not read those quantifiers. Its input is also neither " +
        "attacker-supplied nor long: physical table names come from information_schema, having been built by " +
        "DataTableRef, and Postgres caps an identifier at 63 bytes.")
public class DataTableServiceImpl implements DataTableService {

    private static final Logger log = LoggerFactory.getLogger(DataTableServiceImpl.class);

    /**
     * Matches what remains of a physical table name once its environment prefix is stripped, when that remainder is the
     * owned form {@code <ownerId>_<ownerTypeToken>_<baseName>}. See {@link #parseBaseNameAndOwner(String)}.
     */
    private static final Pattern OWNED_PHYSICAL_NAME_SUFFIX =
        Pattern.compile("^(\\d+)_([a-z][a-z0-9]*)_([a-z_][a-z0-9_]*)$");

    /**
     * A whole physical name, pulled apart: pool token, environment id, optional owner id and owner type token, base
     * name. Same invariant as {@link #OWNED_PHYSICAL_NAME_SUFFIX} -- a base name cannot start with a digit, so the
     * optional owner group can never swallow the start of a name.
     *
     * <p>
     * Possessive quantifiers and an atomic group state that invariant to the regex engine rather than only to the
     * reader. Because a base name cannot begin with a digit there is exactly one way to split any name, so there is
     * nothing for backtracking to find and refusing to backtrack changes no result -- it only stops the engine
     * exploring alternatives that cannot match. Without them the engine cannot know that, and the nested quantifiers
     * read as a denial-of-service risk on a name long enough to matter.
     */
    private static final Pattern PHYSICAL_NAME = Pattern.compile(
        "^(dt|edt)_(\\d++)_(?>(\\d++)_([a-z][a-z0-9]*+)_)?([a-z_][a-z0-9_]*+)$");

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
    public void addColumn(
        String baseName, ColumnSpec columnSpec, long environmentId, PlatformType platformType,
        Optional<Owner> owner) {

        validateBaseName(baseName);
        Assert.notNull(columnSpec, "column must not be null");

        DataTable dataTable = getDataTable(baseName, platformType, owner);

        DataTableRef dataTableRef = physicalRef(dataTable, baseName, environmentId, platformType, owner);

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
        PlatformType platformType, Optional<Owner> owner) {

        validateBaseName(baseName);

        Assert.notEmpty(columnSpecs, "columns must not be empty");

        boolean hasReservedColumn = columnSpecs.stream()
            .anyMatch(columnSpec -> ReservedColumns.isReserved(columnSpec.name()));

        Assert.isTrue(!hasReservedColumn, "Column names " + ReservedColumns.all() + " are reserved");

        // Built from the owner the table is being created FOR, not recovered from a lookup: there is nothing to
        // resolve yet, and this is the one moment a physical name is chosen rather than read back. The creation acts
        // for the owner it names, so the same owner fills both halves of the ref.
        Owner tableOwner = owner.orElse(null);

        DataTableRef dataTableRef = new DataTableRef(baseName, environmentId, platformType, tableOwner, tableOwner);

        createPhysicalTable(dataTableRef.physicalName(), columnSpecs);

        long dataTableId = register(baseName, description, platformType, owner.orElse(null));

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
    public void dropTable(String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {
        validateBaseName(baseName);

        Optional<DataTable> dataTableOptional = fetchDataTable(baseName, platformType, owner);

        if (dataTableOptional.isEmpty()) {
            return;
        }

        DataTable dataTable = dataTableOptional.get();

        DataTableRef dataTableRef = physicalRef(dataTable, baseName, environmentId, platformType, owner);

        String sql = "DROP TABLE IF EXISTS " + escapeIdentifier(dataTableRef.physicalName());

        jdbcTemplate.execute(sql);

        // Scoped to this owner's physical form. Without that, dropping an account's copy would find the vendor's
        // shared table still standing, conclude an instance remains, and leave the account's registry row orphaned.
        if (!hasPhysicalTablesForBaseName(baseName, platformType, dataTableRef.resourceOwner())) {
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
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType,
        Optional<Owner> owner) {

        validateBaseName(fromBaseName);
        validateBaseName(toBaseName);

        DataTable fromDataTable = getDataTable(fromBaseName, platformType, owner);

        DataTableRef fromDataTableRef = physicalRef(fromDataTable, fromBaseName, environmentId, platformType, owner);

        // The copy inherits the SOURCE's owner, not the caller's: duplicating the vendor's shared table from a run
        // that happens to belong to an account must not silently hand that account a private copy.
        Owner fromOwner = fromDataTableRef.resourceOwner();

        DataTableRef toDataTableRef =
            new DataTableRef(toBaseName, environmentId, platformType, fromOwner, owner.orElse(null));

        String fromPhysicalName = fromDataTableRef.physicalName();
        String toPhysicalName = toDataTableRef.physicalName();

        List<ColumnSpec> columnSpecs = listColumns(fromPhysicalName)
            .stream()
            .filter(columnSpec -> !ReservedColumns.isReserved(columnSpec.name()))
            .toList();

        createPhysicalTable(toPhysicalName, columnSpecs);

        // The owner columns are copied along with the user ones, so a duplicate is the source table's rows AND their
        // ownership. Dropping them would leave every copied row unowned: readable by the account the copy belongs to,
        // since the read predicate admits unowned rows, but untouchable by its writes, which match on the owner alone.
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

        register(toBaseName, fromDataTable.getDescription(), platformType, fromOwner);
    }

    /**
     * The owner on a registry row, as an {@link Owner}. A row with an id but no type is treated as owned, matching
     * {@link #isReadableBy} -- a half-written owner must fail closed rather than read as the vendor's.
     */
    private static @Nullable Owner ownerOf(DataTable dataTable) {
        Long ownerId = dataTable.getOwnerId();

        if (ownerId == null) {
            return null;
        }

        OwnerType ownerType = dataTable.getOwnerType();

        return new Owner(ownerType == null ? OwnerType.CONNECTED_USER : ownerType, ownerId);
    }

    @Override
    public String getBaseNameById(long id) {
        DataTable dataTable = dataTableRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Data table with id=" + id + " not found"));

        return dataTable.getName();
    }

    @Override
    public long getIdByBaseName(String baseName, PlatformType platformType) {
        DataTable dataTable = fetchDataTable(baseName, platformType, Optional.empty())
            .orElseThrow(() -> new ExecutionException(
                "Unable to find table " + baseName, DataTableErrorType.DATA_TABLE_NOT_FOUND));

        return dataTable.getId();
    }

    /**
     * The table a run resolves for a base name: the caller's own if they have one, the shared one otherwise.
     *
     * <p>
     * Deliberately the opposite of the cross-pool rule, where two matches mean a bug and resolution fails closed.
     * Within a pool two matches are the feature -- the vendor ships one workflow naming {@code orders}, and giving a
     * customer their own {@code orders} is a drop-in override needing no workflow edit. Failing on ambiguity here would
     * make per-account tables unusable.
     *
     * <p>
     * A run with no owner resolves only shared tables. Reaching an account's table from a vendor run is deliberate, via
     * the account selector on the action, which supplies the owner this method then resolves for.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType, Optional<Owner> owner) {
        String normalizedBaseName = normalizeBaseName(baseName);

        if (owner.isPresent()) {
            Owner curOwner = owner.get();

            Optional<DataTable> ownedDataTable = findOwnedDataTable(normalizedBaseName, platformType, curOwner);

            if (ownedDataTable.isPresent()) {
                return ownedDataTable;
            }
        }

        return dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(
            normalizedBaseName, platformType.ordinal());
    }

    /**
     * The inverse of {@link #physicalRef}, and written to mirror it branch for branch so the two cannot drift: an owned
     * ref maps to the row carrying that owner, an unowned ref to the row carrying none.
     *
     * <p>
     * The unowned branch takes the ref's claim at its word rather than asking who currently occupies the bare physical
     * table. It used to ask, because {@link #assignOwner} could leave an owned row sitting at an unowned name; it no
     * longer can. Asking was also unsound in its own right -- with no shared row to prefer it fell back to the sole
     * remaining candidate, so an unowned ref for a name exactly one account held answered with that account's row.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<DataTable> fetchDataTable(DataTableRef dataTableRef) {
        String baseName = dataTableRef.baseName();
        PlatformType platformType = dataTableRef.platformType();

        Owner resourceOwner = dataTableRef.resourceOwner();

        if (resourceOwner == null) {
            return dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(baseName, platformType.ordinal());
        }

        return findOwnedDataTable(baseName, platformType, resourceOwner);
    }

    /**
     * The registry row one owner holds under a base name in a pool. The lookup takes the whole owner because the key is
     * the whole owner -- {@code owner_type} is in the unique index beside {@code owner_id}, so an id alone can match
     * two rows.
     */
    private Optional<DataTable> findOwnedDataTable(String baseName, PlatformType platformType, Owner owner) {
        OwnerType ownerType = owner.type();

        return dataTableRepository.findByNameAndPlatformTypeAndOwnerIdAndOwnerType(
            baseName, platformType.ordinal(), owner.id(), ownerType.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataTableResolution> fetchDataTableResolution(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {

        return fetchDataTable(baseName, platformType, owner)
            .map(
                dataTable -> new DataTableResolution(
                    dataTable.getId(), physicalRef(dataTable, baseName, environmentId, platformType, owner)));
    }

    /**
     * The physical table a resolved registry row occupies in one environment.
     *
     * <p>
     * Takes the already-resolved row rather than a base name and an owner, which is the point: the owner that ends up
     * in the physical name is the owner of the row {@link #fetchDataTable} chose, so a statement can never reach a
     * different table from the one resolution settled on.
     *
     * <p>
     * The row's owner IS the answer, with no schema lookup behind it. It used to need one, because {@link #assignOwner}
     * left a reassigned row sitting at its unowned physical name and something had to notice; the rename removed that
     * divergence, and with it the only case where the row and its table disagreed.
     *
     * <p>
     * The caller's own owner rides along as the ref's run owner, and the two are not the same thing: an account that
     * falls back to the vendor's shared table gets a ref with no resource owner and its own run owner. That pairing is
     * the reason the ref carries two.
     */
    private static DataTableRef physicalRef(
        DataTable dataTable, String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {

        return new DataTableRef(baseName, environmentId, platformType, ownerOf(dataTable), owner.orElse(null));
    }

    private DataTable getDataTable(String baseName, PlatformType platformType, Optional<Owner> owner) {
        return fetchDataTable(baseName, platformType, owner)
            .orElseThrow(() -> new ExecutionException(
                "Unable to find table " + baseName, DataTableErrorType.DATA_TABLE_NOT_FOUND));
    }

    private DataTableRef getDataTableRef(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner) {

        return physicalRef(getDataTable(baseName, platformType, owner), baseName, environmentId, platformType, owner);
    }

    /**
     * The same visibility rule the rows follow, one level up: an unowned table belongs to the vendor and is everyone's,
     * and a caller with no owner is an admin or an automation caller and sees everything.
     *
     * <p>
     * The filter runs on the registry row before the column lookup, so a table the caller cannot see costs no
     * {@code information_schema} work.
     */
    @Override
    public List<DataTableInfo> listTables(long environmentId, PlatformType platformType, Optional<Owner> owner) {
        String prefix = PhysicalTableNaming.prefix(platformType, environmentId, null);

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

            BaseNameAndOwner baseNameAndOwner = parseBaseNameAndOwner(tableName.substring(prefix.length()));
            String baseName = baseNameAndOwner.baseName();

            // Keyed off what the physical name says rather than off a caller's preference: an owner suffix means the
            // row carrying that owner, no suffix means the row carrying none. The two forms are exhaustive and
            // disjoint, so there is nothing to prefer between them.
            DataTable dataTable = baseNameAndOwner.owner()
                .map(parsedOwner -> fetchDataTable(baseName, platformType, Optional.of(parsedOwner)).orElse(null))
                .orElseGet(
                    () -> dataTableRepository
                        .findByNameAndPlatformTypeAndOwnerIdIsNull(baseName, platformType.ordinal())
                        .orElse(null));

            if (dataTable == null) {
                log.warn("Skipping unregistered physical data table '{}' in environment {}", baseName, environmentId);

                continue;
            }

            if (!isReadableBy(dataTable, owner)) {
                continue;
            }

            List<ColumnSpec> columnSpecs = listColumns(tableName)
                .stream()
                .filter(columnSpec -> !ReservedColumns.isReserved(columnSpec.name()))
                .toList();

            dataTableInfos.add(
                new DataTableInfo(
                    dataTable.getId(), baseName, dataTable.getDescription(), columnSpecs,
                    dataTable.getLastModifiedDate(), dataTable.getOwnerId()));
        }

        return dataTableInfos;
    }

    /**
     * A physical name with everything but its environment prefix stripped is either a bare base name (the shared form)
     * or {@code <ownerId>_<ownerTypeToken>_<baseName>} (the owned form). A base name can never start with a digit
     * ({@code [a-z_][a-z0-9_]*}), so a leading run of digits followed by an underscore is unambiguously an owner id,
     * never part of the name -- the same invariant {@link PhysicalTableNaming} relies on to keep the two forms apart.
     *
     * <p>
     * The owner comes back as the pair the name spells, never as a type assumed from the id. This used to hand back
     * {@code Owner.connectedUser(id)} because the name carried no type, which was right only for as long as
     * {@code CONNECTED_USER} was the only one: the moment a second type existed, an owned table of that type would have
     * been attributed to a connected user of the same id, and {@link #listTables} would have gone looking for the wrong
     * registry row.
     *
     * <p>
     * Recovering the wrong base name here is silent rather than loud: the registry lookup that follows simply misses,
     * and an owned table would disappear from {@link #listTables} instead of raising an error. A token no
     * {@code OwnerType} answers to is treated the same way -- the name is left unparsed, so it fails to resolve rather
     * than resolving to somebody.
     */
    private static BaseNameAndOwner parseBaseNameAndOwner(String remainder) {
        Matcher matcher = OWNED_PHYSICAL_NAME_SUFFIX.matcher(remainder);

        if (matcher.matches()) {
            Optional<OwnerType> ownerType = PhysicalTableNaming.ownerType(matcher.group(2));

            if (ownerType.isPresent()) {
                long ownerId = Long.parseLong(matcher.group(1));

                return new BaseNameAndOwner(matcher.group(3), Optional.of(new Owner(ownerType.get(), ownerId)));
            }
        }

        return new BaseNameAndOwner(remainder, Optional.empty());
    }

    private record BaseNameAndOwner(String baseName, Optional<Owner> owner) {
    }

    @Override
    @Transactional
    public void assignOwner(long dataTableId, @Nullable Owner owner) {
        DataTable dataTable = dataTableRepository.findById(dataTableId)
            .orElseThrow(() -> new IllegalArgumentException("Data table not found: " + dataTableId));

        // Only the EMBEDDED pool has accounts, so an owner is refused anywhere else -- phrased as "not EMBEDDED"
        // rather than "is AUTOMATION" so a pool added later has to opt in rather than inherit permission. An owned
        // AUTOMATION row resolves for nobody: a run carrying an owner is narrowed to EMBEDDED and never looks at this
        // pool, and a run carrying none reaches only unowned rows.
        //
        // Unassigning is allowed in every pool, deliberately: it is the repair path for a row that acquired an owner
        // before this guard existed, and refusing it would leave that row permanently unreachable.
        if (owner != null && dataTable.getPlatformType() != PlatformType.EMBEDDED) {
            throw new IllegalArgumentException(
                "Data table " + dataTableId + " is not in the EMBEDDED pool and cannot be assigned an owner");
        }

        // The whole owner, not its id: a change of type at the same id changes the physical name just as a change of
        // id does, so it has to move the tables too.
        Owner fromOwner = ownerOf(dataTable);

        if (!Objects.equals(fromOwner, owner)) {
            renamePhysicalTables(dataTable, fromOwner, owner);
        }

        dataTable.setOwnerId(owner == null ? null : owner.id());
        dataTable.setOwnerType(owner == null ? null : owner.type());

        dataTableRepository.save(dataTable);
    }

    /**
     * Moves every physical instance of a reassigned table to the name its new owner spells.
     *
     * <p>
     * The registry row is the LOGICAL table and each environment holds its own physical instance of it, so the rename
     * fans out over however many instances exist -- which is normally fewer than there are environments, since a table
     * created in DEVELOPMENT and never promoted has exactly one. The list is read from {@code information_schema}
     * rather than from the environment enum for that reason: what exists is the question, not what could.
     *
     * <p>
     * Only this row's instances are touched. A physical name carries its owner, and at most one registry row holds a
     * given (name, pool, owner) -- the unique key says so -- so every table matching the name this row is moving FROM
     * is necessarily one of its own.
     *
     * <p>
     * Renames are checked in full before any is executed, and the whole method runs inside {@code assignOwner}'s
     * transaction, where Postgres keeps DDL transactional. A collision in the last environment therefore leaves the
     * first one untouched: the alternative -- one registry row spread over two physical names -- is precisely the
     * divergence this exists to remove.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void renamePhysicalTables(DataTable dataTable, @Nullable Owner fromOwner, @Nullable Owner toOwner) {
        String baseName = dataTable.getName();
        PlatformType platformType = dataTable.getPlatformType();

        // The registry rejects the duplicate too, but only once the row is saved and as a DuplicateKeyException. An
        // account cannot own two tables of one name, so saying so here keeps the failure a plain argument error.
        if (toOwner != null && findOwnedDataTable(baseName, platformType, toOwner).isPresent()) {
            throw new IllegalArgumentException(
                "Owner " + toOwner.id() + " already has a data table named '" + baseName + "'");
        }

        List<String> fromPhysicalNames = physicalNames(baseName, platformType, fromOwner);

        Map<String, String> renames = new LinkedHashMap<>();

        for (String fromPhysicalName : fromPhysicalNames) {
            checkNoOtherAccountsRows(fromPhysicalName, dataTable, toOwner);

            long environmentId = environmentIdOf(fromPhysicalName);

            String toPhysicalName =
                new DataTableRef(baseName, environmentId, platformType, toOwner, toOwner).physicalName();

            if (physicalTableExists(toPhysicalName)) {
                throw new IllegalArgumentException(
                    "Data table " + dataTable.getId() + " cannot be reassigned: '" + toPhysicalName +
                        "' already exists");
            }

            renames.put(fromPhysicalName, toPhysicalName);
        }

        for (Map.Entry<String, String> rename : renames.entrySet()) {
            jdbcTemplate.execute(
                "ALTER TABLE " + escapeIdentifier(rename.getKey()) + " RENAME TO " +
                    escapeIdentifier(rename.getValue()));

            restampRowOwners(rename.getValue(), toOwner);
        }
    }

    /**
     * Refuses an assignment that would hand one account's rows to another.
     *
     * <p>
     * {@link #restampRowOwners} moves every row in the table onto the new owner, which is right when the rows are
     * unowned or already that account's and wrong when they are not. A shared table is exactly where rows of several
     * accounts collect -- that is what the row axis is for -- so assigning one to a single account would silently make
     * every other account's rows readable and writable by it, under a console action labelled only "assign owner".
     *
     * <p>
     * Refused rather than filtered. Re-stamping only the unowned rows would leave the others sitting in a table their
     * account can no longer resolve: not disclosed, but silently unreachable, which is a worse thing to discover late.
     * The vendor can unassign first -- which says "share this with everyone" in as many words -- and then assign.
     *
     * <p>
     * Unassignment ({@code toOwner} null) is deliberately not checked. Making a table shared is a statement that its
     * rows are everyone's, and it is also the repair path for a row that acquired an owner before these rules existed.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void checkNoOtherAccountsRows(String physicalName, DataTable dataTable, @Nullable Owner toOwner) {
        if (toOwner == null) {
            return;
        }

        String sql = "SELECT COUNT(*) FROM " + escapeIdentifier(physicalName) + " WHERE " +
            escapeIdentifier(ReservedColumns.OWNER_ID) + " IS NOT NULL AND NOT (" +
            escapeIdentifier(ReservedColumns.OWNER_ID) + " = ? AND " +
            escapeIdentifier(ReservedColumns.OWNER_TYPE) + " = ?)";

        Long otherAccountRowCount = jdbcTemplate.queryForObject(
            sql, Long.class, toOwner.id(), ownerTypeOrdinal(toOwner));

        if (otherAccountRowCount != null && otherAccountRowCount > 0) {
            throw new IllegalArgumentException(
                "Data table " + dataTable.getId() + " cannot be assigned to owner " + toOwner.id() + ": '" +
                    physicalName + "' holds " + otherAccountRowCount +
                    " row(s) belonging to another account. Unassign the table first to share those rows, or remove " +
                    "them.");
        }
    }

    private static int ownerTypeOrdinal(Owner owner) {
        OwnerType ownerType = owner.type();

        return ownerType.ordinal();
    }

    /**
     * Moves the rows of a reassigned table onto its new owner, in the same transaction as the rename.
     *
     * <p>
     * The two axes are independent, but an assignment is a statement about both: the table becomes this account's, and
     * so does everything in it. Left alone, the rows would keep whatever owner they were written with -- the new owner
     * could read them, because the read predicate admits unowned rows, and could not update or delete a single one,
     * because the write predicate matches on the owner alone. An unassignment is the same statement inverted, and has
     * to be, or a table handed back to the vendor would come back empty.
     *
     * <p>
     * Both columns move together. An {@code owner_id} left beside a null {@code owner_type} belongs to nobody: it
     * matches no predicate, reads as written and behaves as though it were not.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void restampRowOwners(String physicalName, @Nullable Owner toOwner) {
        String sql = "UPDATE " + escapeIdentifier(physicalName) + " SET " +
            escapeIdentifier(ReservedColumns.OWNER_ID) + " = ?, " +
            escapeIdentifier(ReservedColumns.OWNER_TYPE) + " = ?";

        jdbcTemplate.update(sql, preparedStatement -> {
            if (toOwner == null) {
                preparedStatement.setNull(1, Types.BIGINT);
                preparedStatement.setNull(2, Types.INTEGER);

                return;
            }

            preparedStatement.setLong(1, toOwner.id());

            OwnerType ownerType = toOwner.type();

            preparedStatement.setInt(2, ownerType.ordinal());
        });
    }

    /**
     * The environment a physical name belongs to. Every name reaching this has already matched {@link #PHYSICAL_NAME}
     * in {@link #physicalNames}, so a name that does not parse is a bug rather than input.
     */
    private static long environmentIdOf(String physicalName) {
        Matcher matcher = PHYSICAL_NAME.matcher(physicalName);

        Assert.isTrue(matcher.matches(), "Unparseable physical table name: " + physicalName);

        return Long.parseLong(matcher.group(2));
    }

    private boolean physicalTableExists(String physicalName) {
        String sql = "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' AND table_name = ?";

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, physicalName);

        return count != null && count > 0;
    }

    /**
     * Whether {@code owner} may see this table at all. Mirrors {@code KnowledgeBaseServiceImpl.isReadableBy}
     * deliberately: two stores, one rule, and a caller must not be able to tell "somebody else's" from "does not
     * exist".
     *
     * <p>
     * An owner id with no type is treated as owned rather than unowned, so a half-written owner fails closed instead of
     * quietly sharing the table with every account.
     */
    static boolean isReadableBy(DataTable dataTable, Optional<Owner> owner) {
        Long ownerId = dataTable.getOwnerId();

        if (ownerId == null || owner.isEmpty()) {
            return true;
        }

        Owner currentOwner = owner.get();

        return currentOwner.id() == ownerId && currentOwner.type() == dataTable.getOwnerType();
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
        String baseName, String columnName, long environmentId, PlatformType platformType, Optional<Owner> owner) {

        validateBaseName(baseName);
        Assert.hasText(columnName, "columnName must not be empty");

        DataTableRef dataTableRef = getDataTableRef(baseName, environmentId, platformType, owner);

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
        String baseName, String fromColumnName, String toColumnName, long environmentId, PlatformType platformType,
        Optional<Owner> owner) {

        validateBaseName(baseName);
        Assert.hasText(fromColumnName, "fromColumnName must not be empty");
        Assert.hasText(toColumnName, "toColumnName must not be empty");
        Assert.isTrue(!ReservedColumns.isReserved(fromColumnName), "Reserved columns cannot be renamed");
        Assert.isTrue(!ReservedColumns.isReserved(toColumnName), "Cannot rename to a reserved name");

        DataTableRef dataTableRef = getDataTableRef(baseName, environmentId, platformType, owner);

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
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType,
        Optional<Owner> owner) {

        validateBaseName(fromBaseName);
        validateBaseName(toBaseName);

        DataTable dataTable = getDataTable(fromBaseName, platformType, owner);

        DataTableRef fromDataTableRef = physicalRef(dataTable, fromBaseName, environmentId, platformType, owner);

        // The renamed table keeps its owner: a rename changes what a table is called, never whose it is.
        DataTableRef toDataTableRef = new DataTableRef(
            toBaseName, environmentId, platformType, fromDataTableRef.resourceOwner(), owner.orElse(null));

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
    private boolean hasPhysicalTablesForBaseName(String baseName, PlatformType platformType, @Nullable Owner owner) {
        List<String> physicalNames = physicalNames(baseName, platformType, owner);

        return !physicalNames.isEmpty();
    }

    /**
     * Every physical instance of one owner's copy of a base name, across every environment in this pool.
     */
    private List<String> physicalNames(String baseName, PlatformType platformType, @Nullable Owner owner) {
        String normalizedBaseName = baseName.toLowerCase(Locale.ROOT);
        String escapedBaseName = normalizedBaseName.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        String pattern = PhysicalTableNaming.poolToken(platformType) + "\\_%\\_" + escapedBaseName;

        String sql = "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' AND table_name LIKE ? ESCAPE '\\'";

        List<String> tableNames = jdbcTemplate.query(
            sql, ps -> ps.setString(1, pattern), (resultSet, rowNum) -> resultSet.getString("table_name"));

        // The LIKE pattern cannot tell the owned form from the shared one -- its '%' swallows "0" and "0_5" alike --
        // so the owner is matched here, against a parsed name rather than a pattern.
        return tableNames.stream()
            .filter(tableName -> matchesPoolBaseNameAndOwner(tableName, platformType, normalizedBaseName, owner))
            .toList();
    }

    /**
     * Whether a physical table name belongs to this pool, this base name and this owner, in any environment. The owner
     * is compared as the pair the name spells, so an owned table of another type is not one of this owner's.
     */
    private static boolean matchesPoolBaseNameAndOwner(
        String tableName, PlatformType platformType, String baseName, @Nullable Owner owner) {

        Matcher matcher = PHYSICAL_NAME.matcher(tableName);

        if (!matcher.matches()) {
            return false;
        }

        String poolToken = matcher.group(1);

        if (!poolToken.equals(PhysicalTableNaming.poolToken(platformType))) {
            return false;
        }

        if (!baseName.equals(matcher.group(5))) {
            return false;
        }

        String parsedOwnerId = matcher.group(3);

        if (parsedOwnerId == null) {
            return owner == null;
        }

        if (owner == null) {
            return false;
        }

        Optional<OwnerType> parsedOwnerType = PhysicalTableNaming.ownerType(matcher.group(4));

        return parsedOwnerType.filter(ownerType -> new Owner(ownerType, Long.parseLong(parsedOwnerId)).equals(owner))
            .isPresent();
    }

    /**
     * Writes the {@code data_table} row that gives a physical table its id. Nothing else creates one, and everything
     * keyed on a data table id -- tags, webhooks, the workspace relation, {@code listTables} -- needs it to exist.
     *
     * <p>
     * Called after the DDL and inside the same transaction, so a failed CREATE leaves no registry row and a failed
     * insert leaves no physical table.
     */
    private long register(
        String baseName, @Nullable String description, PlatformType platformType, @Nullable Owner owner) {

        Assert.hasText(baseName, "baseName required");

        String normalizedBaseName = normalizeBaseName(baseName);

        // Exact, never the owned-wins-shared-fallback lookup: registering an account's "orders" must not find the
        // vendor's row of that name and conclude the table is already registered.
        Optional<DataTable> existingDataTable = owner == null
            ? dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(
                normalizedBaseName, platformType.ordinal())
            : findOwnedDataTable(normalizedBaseName, platformType, owner);

        return existingDataTable
            .map(DataTable::getId)
            .orElseGet(() -> {
                DataTable dataTable = new DataTable();

                dataTable.setName(normalizedBaseName);
                dataTable.setDescription(description);
                dataTable.setPlatformType(platformType);

                if (owner != null) {
                    dataTable.setOwnerId(owner.id());
                    dataTable.setOwnerType(owner.type());
                }

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
     * Every physical table carries {@code owner_id} / {@code owner_type}, owned and shared alike. Uniformly, and that
     * uniformity is the point: in a table an account already owns the row predicate is satisfied by construction, so no
     * row operation has to know which kind of table it is holding. A design where only shared tables carried the
     * columns would put that question in front of every statement.
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

    /**
     * Package-private (not private) so {@code PhysicalTableNamingTest}'s sibling test in this package can pin the
     * coupling between this rule and the physical-naming scheme: a base name can never start with a digit, which is the
     * only reason an owned physical name cannot collide with a shared one.
     */
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
