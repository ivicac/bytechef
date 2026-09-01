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

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Unified service for managing dynamic data tables and querying their metadata.
 *
 * <p>
 * Every table belongs to exactly one pool -- {@link PlatformType#AUTOMATION} or {@link PlatformType#EMBEDDED} -- and
 * physical table names are constructed internally using the pattern <code>dt_&lt;envIndex&gt;_&lt;baseName&gt;</code>
 * for AUTOMATION and <code>edt_&lt;envIndex&gt;_&lt;baseName&gt;</code> for EMBEDDED, where envIndex is mapped as
 * DEVELOPMENT=0, STAGING=1, PRODUCTION=2. The same base name may exist in both pools at once; the pool, not the name
 * alone, identifies the table.
 * </p>
 *
 * <p>
 * Callers must pass only the logical base table name (without any <code>dt_</code>/<code>edt_</code> prefix). Inputs
 * starting with <code>dt_</code> will be rejected.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface DataTableService {

    /**
     * Adds a new column to an existing dynamic data table in the specified environment and pool.
     *
     * @param baseName      The logical base name of the table to which the column will be added. The name must not
     *                      include a "dt_" prefix.
     * @param columnSpec    The specification of the column to be added, including the column name and type.
     * @param environmentId The target environment ID.
     * @param platformType  The pool the table belongs to.
     * @param owner         The owner the caller acts for, used to resolve which copy of the table to alter.
     */
    void addColumn(
        String baseName, ColumnSpec columnSpec, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Creates a new dynamic data table in the specified environment and pool with the given base name, description, and
     * column specifications. The physical table name is derived internally based on the pool, environment and base
     * name.
     *
     * @param baseName      The logical base name of the table. The name must not include a "dt_" prefix, as it will be
     *                      automatically added.
     * @param description   A description for the table to provide additional metadata about its purpose.
     * @param columnSpecs   A list of column specifications defining the structure of the table, including column names
     *                      and types. This list must be non-null and non-empty.
     * @param environmentId The target environment where the table should be created (e.g., DEVELOPMENT, STAGING,
     *                      PRODUCTION).
     * @param platformType  The pool the table belongs to.
     * @param owner         The account the new table belongs to, or empty to create it shared. An owned table gets its
     *                      own physical table, which is what keeps one account's rows out of another's; a shared one
     *                      keeps the unowned physical name.
     */
    void createTable(
        String baseName, String description, List<ColumnSpec> columnSpecs, long environmentId,
        PlatformType platformType, Optional<Owner> owner);

    /**
     * Deletes a dynamic data table in the specified environment and pool with the given base name. This action is
     * irreversible and will permanently remove the table and its associated data.
     *
     * @param baseName      The logical base name of the table to be deleted. The name must not include a "dt_" prefix,
     *                      as it is added internally.
     * @param environmentId The target environmentID.
     * @param platformType  The pool the table belongs to.
     * @param owner         The owner the caller acts for, used to resolve which copy of the table to drop.
     */
    void dropTable(String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Duplicates an existing dynamic data table in the specified environment and pool. The new table will have a
     * different base name while preserving the structure.
     *
     * @param platformType The pool both the source and the copy belong to; duplication never crosses pools.
     * @param owner        The owner the caller acts for. The copy inherits the source's owner, so duplicating an
     *                     account's table produces another table of that account's rather than a shared one.
     */
    void duplicateTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Retrieves the base name of a dynamic data table by its unique identifier.
     *
     * <p>
     * Unchanged by the pool split: the id already identifies the pool.
     *
     * @param id The unique identifier of the data table.
     * @return The base name of the data table corresponding to the given identifier. If no table is found for the
     *         provided ID, the method may return null or an empty string.
     */
    String getBaseNameById(long id);

    /**
     * Retrieves the unique identifier of a dynamic data table based on its base name and pool.
     *
     * @param baseName     The logical base name of the data table. The base name should not include a "dt_" prefix, as
     *                     it is internally managed by the service. Must not be null or empty.
     * @param platformType The pool the table belongs to.
     * @return The unique identifier of the data table corresponding to the given base name. If no table is found with
     *         the provided base name, the method may return -1 or a similar default value.
     */
    long getIdByBaseName(String baseName, PlatformType platformType);

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
     *
     * @param baseName     the logical base name to resolve
     * @param platformType the pool to resolve within
     * @param owner        the caller's owner, or empty for a vendor run with no named account
     * @return the caller's own table if one exists, else the shared table, else empty
     */
    Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType, Optional<Owner> owner);

    /**
     * The registry row whose physical table {@code dataTableRef} addresses -- the inverse of
     * {@link #fetchDataTableResolution}, which produced the ref in the first place.
     *
     * <p>
     * Exists so that a caller holding only a ref -- the webhook registry, whose events arrive from the row layer -- can
     * name the table by identity rather than by base name. That distinction is the whole point: after per-account
     * tables a base name matches as many registry rows as there are accounts holding one, so an owned ref must map to
     * its account's row and never to the vendor's.
     *
     * <p>
     * A ref carrying an owner maps to the row with that owner; a ref carrying none maps to the row carrying none. The
     * second half is a claim taken at its word, not a search for whoever currently occupies the unowned physical table:
     * {@code DataTableRef.shared} is public, and a name held by exactly one account must not answer it.
     *
     * @param dataTableRef the resolved table
     * @return the registry row that table belongs to, or empty when no row claims it
     */
    Optional<DataTable> fetchDataTable(DataTableRef dataTableRef);

    /**
     * The table a run reaches for a base name, resolved once: which registry row won, and which physical table that row
     * occupies in this environment.
     *
     * <p>
     * The owner in the returned {@link com.bytechef.platform.data.table.domain.DataTableRef} comes out of the same
     * {@link #fetchDataTable} lookup that chose the registry row -- that is the whole reason this exists rather than
     * callers pairing a base name with an owner of their own. Every DDL and DML statement takes the ref, so the
     * physical name a statement touches and the registry row resolution picked can never be about two different tables.
     *
     * <p>
     * The owner in the ref is the owner on the registry row, with nothing between the two. {@link #assignOwner} moves
     * the physical tables with the row, so the row's owner and the name its table sits at cannot disagree, and a
     * missing physical table surfaces as a missing table rather than as somebody else's rows.
     *
     * @param baseName      the logical base name to resolve
     * @param environmentId the environment the run is in; physical tables are per environment, registry rows are not
     * @param platformType  the pool to resolve within
     * @param owner         the caller's owner, or empty for a vendor run with no named account
     * @return the registry row and the physical table this run reaches, or empty when it resolves no table of that name
     */
    Optional<DataTableResolution> fetchDataTableResolution(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Owner-aware form. An unowned table belongs to the vendor and is listed for everyone, an empty owner lists
     * everything, and an owned one is listed only for that owner -- the same rule the rows follow, one level up.
     *
     * @param environmentId the environment ID
     * @param platformType  the pool to list
     * @param owner         the caller's owner, or empty for an admin or automation caller
     */
    List<DataTableInfo> listTables(long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Assigns a data table to an account, or returns it to the vendor when {@code owner} is null.
     *
     * <p>
     * Table-level only. Rows keep whatever owner they were written with -- reassigning a table does not rewrite its
     * contents.
     *
     * <p>
     * The physical tables move with the row. A physical name spells its owner ({@code <pool>_<envId>_<baseName>}
     * shared, {@code <pool>_<envId>_<ownerId>_<baseName>} owned), so a reassignment that left them behind would put the
     * registry and the schema into disagreement -- survivable only for as long as the reassigned row is the sole
     * claimant of the unowned name, and unrecoverable the moment a second account owns a table of the same name. Every
     * environment holding an instance is renamed; environments holding none are simply not in the list.
     *
     * <p>
     * All or nothing. The renames are validated in full before the first is executed and run inside this method's
     * transaction, so a collision in one environment leaves every other environment, and the registry row itself,
     * exactly as they were.
     *
     * <p>
     * EMBEDDED only, for a non-null owner: accounts exist in that pool alone, so an owner on an AUTOMATION table would
     * resolve for nobody and is refused. Passing null is allowed in every pool -- it is the repair path for a row that
     * acquired an owner before that was refused, and it renames the tables back.
     *
     * @param dataTableId the data table id
     * @param owner       the owning account, or null to make the table shared again
     * @throws IllegalArgumentException if no such table exists, if a non-null owner is given for a table outside the
     *                                  EMBEDDED pool, or if the new owner already holds a table of this name
     */
    void assignOwner(long dataTableId, @Nullable Owner owner);

    /**
     * Removes a column from an existing dynamic data table in the specified environment and pool.
     *
     * @param baseName      The logical base name of the table from which the column will be removed. The name must not
     *                      include a "dt_" prefix.
     * @param columnName    The name of the column to be removed. This must match the existing column name in the table.
     * @param environmentId The target environment ID.
     * @param platformType  The pool the table belongs to.
     * @param owner         The owner the caller acts for, used to resolve which copy of the table to alter.
     */
    void removeColumn(
        String baseName, String columnName, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * Renames a column in an existing dynamic data table in the specified environment and pool.
     *
     * @param baseName       The logical base name of the table containing the column to be renamed. The name must not
     *                       include a "dt_" prefix.
     * @param fromColumnName The current name of the column to be renamed. This must match the existing column name in
     *                       the table.
     * @param toColumnName   The new name for the column. This name must not conflict with any existing columns in the
     *                       table.
     * @param environmentId  The target environment ID.
     * @param platformType   The pool the table belongs to.
     * @param owner          The owner the caller acts for, used to resolve which copy of the table to alter.
     */
    void renameColumn(
        String baseName, String fromColumnName, String toColumnName, long environmentId, PlatformType platformType,
        Optional<Owner> owner);

    /**
     * Renames an existing dynamic data table in the specified environment and pool from one base name to another. This
     * method updates the logical base name of the table, which will also be reflected in its physical table
     * representation.
     *
     * @param fromBaseName  The current logical base name of the table. This name must not include a "dt_" prefix and
     *                      must match an existing table.
     * @param toBaseName    The new logical base name for the table. This name must not include a "dt_" prefix and must
     *                      not conflict with any existing table's base name in the same environment and pool.
     * @param environmentId The target environment ID.
     * @param platformType  The pool the table belongs to.
     * @param owner         The owner the caller acts for, used to resolve which copy of the table to rename. The copy
     *                      keeps its owner -- renaming never moves a table between accounts.
     */
    void renameTable(
        String fromBaseName, String toBaseName, long environmentId, PlatformType platformType, Optional<Owner> owner);
}
