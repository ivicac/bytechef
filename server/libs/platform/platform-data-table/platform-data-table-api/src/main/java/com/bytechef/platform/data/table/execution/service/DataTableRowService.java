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

import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.RowFilter;
import com.bytechef.platform.data.table.domain.RowSort;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import java.util.List;
import java.util.Map;

/**
 * Service for managing data table row operations (CRUD and CSV import/export).
 *
 * <p>
 * This service handles all data manipulation operations on dynamic data tables, while the structure (DDL) operations
 * are managed by {@link com.bytechef.platform.data.table.configuration.service.DataTableService}.
 * </p>
 *
 * <p>
 * Every method names its table with a {@link DataTableRef} rather than with a base name, and that is the module's one
 * defence against a run touching another account's data. A ref already carries the pool, the environment and BOTH
 * owners resolution settled on -- the one that chose the physical table and the one the run acts for -- so this service
 * never chooses any of them. There is no base name here for it to pair with an owner of its own, and no owner parameter
 * on any operation for a caller to supply one, so neither the table a statement reaches nor the rows it matches can
 * differ from what resolution picked. Get a ref from {@code DataTableService.fetchDataTableResolution}; see
 * {@link DataTableRef} for the one other, deliberately narrow, way to build one.
 * </p>
 *
 * <p>
 * Reads and writes are scoped differently, and the difference is deliberate: a read admits the run's own rows and the
 * ones belonging to nobody, a write matches the run's alone. A vendor-seeded reference row is every account's to read
 * and nobody's to change.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface DataTableRowService {

    /**
     * Deletes a row by id.
     *
     * @param dataTableRef the resolved physical table
     * @param id           the row id
     * @return true if a row was deleted, false if no row with that id exists
     */
    boolean deleteRow(DataTableRef dataTableRef, long id);

    /**
     * Gets a single row by its id.
     *
     * @param dataTableRef the resolved physical table
     * @param id           the row id
     * @return the row if found, null otherwise
     */
    DataTableRow getRow(DataTableRef dataTableRef, long id);

    /**
     * Inserts a row with provided values. Returns the created row including generated id.
     *
     * @param dataTableRef the resolved physical table
     * @param values       column name to value map
     * @return the created row with generated id
     */
    DataTableRow insertRow(DataTableRef dataTableRef, Map<String, Object> values);

    /**
     * Lists rows of a dynamic table with pagination.
     *
     * @param dataTableRef the resolved physical table
     * @param limit        maximum number of rows to return
     * @param offset       number of rows to skip
     * @return list of rows with their data
     */
    List<DataTableRow> listRows(DataTableRef dataTableRef, int limit, int offset);

    /**
     * Filtered and sorted form. These filters narrow within what the run may already see, and are ANDed onto the row
     * owner predicate rather than replacing it -- a workflow can ask for less than its own rows and never for more. See
     * {@link RowFilter} and {@link RowSort}.
     */
    List<DataTableRow> listRows(
        DataTableRef dataTableRef, int limit, int offset, List<RowFilter> rowFilters, List<RowSort> rowSorts);

    /**
     * Exports the entire table (excluding the primary key column 'id' in the header) as CSV text. The first row is a
     * header with column names in their physical order.
     *
     * @param dataTableRef the resolved physical table
     * @return CSV text representation of all rows
     */
    String exportCsv(DataTableRef dataTableRef);

    /**
     * Imports CSV text into the table. The CSV must contain a header row with column names matching existing columns
     * (case-insensitive). Unknown columns are ignored. The 'id' column, if present, is ignored.
     *
     * @param dataTableRef the resolved physical table
     * @param csv          CSV text with header row
     */
    void importCsv(DataTableRef dataTableRef, String csv);

    /**
     * Updates a row by its stable id. Returns the updated row.
     *
     * @param dataTableRef the resolved physical table
     * @param id           the row id
     * @param values       column name to value map (only provided columns will be updated)
     * @return the updated row
     */
    DataTableRow updateRow(DataTableRef dataTableRef, long id, Map<String, Object> values);
}
