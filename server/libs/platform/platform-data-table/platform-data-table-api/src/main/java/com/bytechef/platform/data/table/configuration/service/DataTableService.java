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
 * A table belongs to no account. One base name in one environment in one pool is one physical table, and the accounts
 * sharing it are separated by the {@code owner_id} predicate on its rows -- so nothing here takes an owner to choose a
 * table with. {@link #fetchDataTableResolution} is the one exception, and its owner is the run owner it binds into the
 * ref for the row layer to use.
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
     */
    void addColumn(String baseName, ColumnSpec columnSpec, long environmentId, PlatformType platformType);

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
     */
    void createTable(
        String baseName, String description, List<ColumnSpec> columnSpecs, long environmentId,
        PlatformType platformType);

    /**
     * Deletes a dynamic data table in the specified environment and pool with the given base name. This action is
     * irreversible and will permanently remove the table and its associated data.
     *
     * @param baseName      The logical base name of the table to be deleted. The name must not include a "dt_" prefix,
     *                      as it is added internally.
     * @param environmentId The target environmentID.
     * @param platformType  The pool the table belongs to.
     */
    void dropTable(String baseName, long environmentId, PlatformType platformType);

    /**
     * Duplicates an existing dynamic data table in the specified environment and pool. The new table will have a
     * different base name while preserving the structure.
     *
     * @param platformType The pool both the source and the copy belong to; duplication never crosses pools.
     */
    void duplicateTable(String fromBaseName, String toBaseName, long environmentId, PlatformType platformType);

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
     * The registry row a base name names in a pool.
     *
     * <p>
     * One row, or none: a base name is unique within a pool, so there is nothing to choose between and no owner to
     * choose it with.
     *
     * @param baseName     the logical base name to resolve
     * @param platformType the pool to resolve within
     * @return the registry row, or empty when the pool holds no table of that name
     */
    Optional<DataTable> fetchDataTable(String baseName, PlatformType platformType);

    /**
     * The registry row whose physical table {@code dataTableRef} addresses -- the inverse of
     * {@link #fetchDataTableResolution}, which produced the ref in the first place.
     *
     * <p>
     * Exists so that a caller holding only a ref -- the webhook registry, whose events arrive from the row layer -- can
     * name the table without rebuilding the base name and pool by hand.
     *
     * @param dataTableRef the resolved table
     * @return the registry row that table belongs to, or empty when no row claims it
     */
    Optional<DataTable> fetchDataTable(DataTableRef dataTableRef);

    /**
     * Resolves a base name to the one physical table that holds it, and binds the run's owner into the ref.
     *
     * <p>
     * The owner here is the run owner and nothing else: it selects rows inside the table, never the table. There is one
     * table per base name per environment per pool, so resolution has nothing to choose between.
     *
     * @param baseName      the logical base name to resolve
     * @param environmentId the environment the run is in; physical tables are per environment, registry rows are not
     * @param platformType  the pool to resolve within
     * @param owner         the caller's owner, or empty for a run with no named account
     * @return the registry row and the physical table this run reaches, or empty when it resolves no table of that name
     */
    Optional<DataTableResolution> fetchDataTableResolution(
        String baseName, long environmentId, PlatformType platformType, Optional<Owner> owner);

    /**
     * The table as it exists in one environment: registry metadata plus the physical table's user columns. Empty when
     * either half is missing -- a registry row alone is a table that lives in some other environment.
     *
     * @param baseName      the logical base name to resolve
     * @param environmentId the environment whose physical table must exist for a result to be returned
     * @param platformType  the pool to resolve within
     * @return the table's registry metadata and columns, or empty when the registry row or this environment's physical
     *         table is missing
     */
    Optional<DataTableInfo> fetchDataTableInfo(String baseName, long environmentId, PlatformType platformType);

    /**
     * Every data table in one environment and pool, with its columns.
     *
     * <p>
     * Unfiltered by account, because a table is not an account's to hide: every account in the pool reaches the same
     * tables and is separated inside them by the row predicate.
     *
     * @param environmentId the environment ID
     * @param platformType  the pool to list
     */
    List<DataTableInfo> listTables(long environmentId, PlatformType platformType);

    /**
     * Removes a column from an existing dynamic data table in the specified environment and pool.
     *
     * @param baseName      The logical base name of the table from which the column will be removed. The name must not
     *                      include a "dt_" prefix.
     * @param columnName    The name of the column to be removed. This must match the existing column name in the table.
     * @param environmentId The target environment ID.
     * @param platformType  The pool the table belongs to.
     */
    void removeColumn(String baseName, String columnName, long environmentId, PlatformType platformType);

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
     */
    void renameColumn(
        String baseName, String fromColumnName, String toColumnName, long environmentId, PlatformType platformType);

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
     */
    void renameTable(String fromBaseName, String toBaseName, long environmentId, PlatformType platformType);

    /**
     * Writes the registry description. Environment-independent -- the registry row is the logical table across every
     * environment, so there is nothing here for an environment to select between.
     *
     * @param baseName     The logical base name of the table whose description is being updated.
     * @param description  The new description, or {@code null} to clear it.
     * @param platformType The pool the table belongs to.
     */
    void updateDescription(String baseName, @Nullable String description, PlatformType platformType);
}
