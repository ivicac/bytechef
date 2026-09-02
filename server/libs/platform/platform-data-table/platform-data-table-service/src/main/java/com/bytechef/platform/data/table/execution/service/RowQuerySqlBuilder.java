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

import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.ReservedColumns;
import com.bytechef.platform.data.table.domain.RowFilter;
import com.bytechef.platform.data.table.domain.RowSort;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Turns a query's {@link RowFilter}s into one SQL fragment plus the parameters it binds, and its {@link RowSort}s into
 * an ORDER BY clause. The two share one notion of which column a caller may name, which is why they share a class.
 *
 * <p>
 * The fragment always opens with {@code " AND "} so it can be appended to the {@code WHERE TRUE} that
 * {@link DataTableRowServiceImpl#listRows} already emits, before {@code ORDER BY}.
 *
 * <p>
 * The owner predicates live here too, and they are a different kind of thing from a caller's filters: a filter is what
 * the workflow asked for, and the owner predicate is what the run is allowed to ask for. Both come out of this class so
 * that the fragment a statement appends and the parameters it binds are written next to each other and cannot fall out
 * of step, but only the owner predicate reads its owner from the {@link DataTableRef} -- which is the single source
 * either owner ever comes from.
 *
 * <p>
 * Reads and writes get genuinely different SQL, and deliberately so. A vendor-seeded reference row is every account's
 * to read and nobody's to change, so the read admits unowned rows and the write does not.
 *
 * <p>
 * A field must name a real column of the table, or {@code id}. That check is what makes interpolating the column name
 * safe, and it is also what keeps {@code owner_id} and {@code owner_type} unaddressable from a workflow: naming one is
 * how a step would read another account's rows out of a shared table, or hand its own rows away.
 *
 * @author Ivica Cardic
 */
final class RowQuerySqlBuilder {

    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private RowQuerySqlBuilder() {
    }

    /**
     * What a run may read: its own rows and the ones belonging to nobody. A run with no owner sees the unowned rows
     * alone and never falls through to an account's.
     *
     * <p>
     * There is one kind of table and nothing branches on anything else. Every account in the pool reads the same
     * physical table, so this predicate is the only thing separating one account's rows from another's -- not a
     * narrowing applied on top of a table that was already the caller's, which is what it once was.
     */
    static String readableOwnerPredicate(DataTableRef dataTableRef) {
        if (dataTableRef.runOwnerId() == null) {
            return " AND " + quote(ReservedColumns.OWNER_ID) + " IS NULL";
        }

        return " AND (" + quote(ReservedColumns.OWNER_ID) + " = ? OR " + quote(ReservedColumns.OWNER_ID) +
            " IS NULL)";
    }

    /**
     * What a run may change: its own rows, and only those. Narrower than the read predicate on purpose -- the unowned
     * row an account can see is the same row every other account is reading.
     */
    static String writableOwnerPredicate(DataTableRef dataTableRef) {
        if (dataTableRef.runOwnerId() == null) {
            return " AND " + quote(ReservedColumns.OWNER_ID) + " IS NULL";
        }

        return " AND " + quote(ReservedColumns.OWNER_ID) + " = ?";
    }

    /**
     * Binds whatever the owner predicate placed, and returns the next free index. Both predicates place one parameter
     * when the run has an owner and none when it does not, so this is the counterpart to either.
     */
    static int bindOwner(PreparedStatement preparedStatement, int index, DataTableRef dataTableRef)
        throws SQLException {

        Long runOwnerId = dataTableRef.runOwnerId();

        if (runOwnerId == null) {
            return index;
        }

        preparedStatement.setLong(index, runOwnerId);

        return index + 1;
    }

    record Binding(ColumnType type, @Nullable Object value) {
    }

    record Fragment(String sql, List<Binding> bindings) {
    }

    static Fragment filters(List<RowFilter> rowFilters, Map<String, ColumnType> columnTypes) {
        if (rowFilters.isEmpty()) {
            return new Fragment("", List.of());
        }

        StringBuilder sql = new StringBuilder();
        List<Binding> bindings = new ArrayList<>();

        for (RowFilter rowFilter : rowFilters) {
            String column = column(rowFilter, columnTypes);

            sql.append(" AND ")
                .append(quote(column));

            append(sql, bindings, rowFilter, columnType(column, columnTypes));
        }

        return new Fragment(sql.toString(), List.copyOf(bindings));
    }

    private static void append(
        StringBuilder sql, List<Binding> bindings, RowFilter rowFilter, ColumnType columnType) {

        Object value = rowFilter.value();

        switch (rowFilter.operator()) {
            case EQ -> appendComparison(sql, bindings, "=", "IS NULL", value, columnType);
            case NEQ -> appendComparison(sql, bindings, "<>", "IS NOT NULL", value, columnType);
            case GT -> appendComparison(sql, bindings, ">", null, value, columnType);
            case GTE -> appendComparison(sql, bindings, ">=", null, value, columnType);
            case LT -> appendComparison(sql, bindings, "<", null, value, columnType);
            case LTE -> appendComparison(sql, bindings, "<=", null, value, columnType);
            case CONTAINS -> appendLike(sql, bindings, rowFilter, columnType, "%", "%");
            case STARTS_WITH -> appendLike(sql, bindings, rowFilter, columnType, "", "%");
            case IN -> appendIn(sql, bindings, rowFilter, columnType);
            case BETWEEN -> appendBetween(sql, bindings, rowFilter, columnType);
            default -> throw new IllegalArgumentException("Unsupported operator: " + rowFilter.operator());
        }
    }

    private static void appendComparison(
        StringBuilder sql, List<Binding> bindings, String operator, @Nullable String nullForm,
        @Nullable Object value, ColumnType columnType) {

        if (value == null) {
            Assert.notNull(nullForm, "A null value is only meaningful with Equals or Not Equals");

            sql.append(' ')
                .append(nullForm);

            return;
        }

        Assert.isTrue(!(value instanceof Collection<?>), "Operator " + operator + " takes a single value, not a list");

        sql.append(' ')
            .append(operator)
            .append(" ?");

        bindings.add(new Binding(columnType, DataTableRowServiceImpl.coerceValue(columnType, value)));
    }

    private static void appendLike(
        StringBuilder sql, List<Binding> bindings, RowFilter rowFilter, ColumnType columnType, String prefix,
        String suffix) {

        Assert.isTrue(
            columnType == ColumnType.STRING,
            "Operator " + rowFilter.operator() + " applies to text columns only, and '" + rowFilter.field() + "' is " +
                columnType);

        Object value = rowFilter.value();

        Assert.notNull(value, "Operator " + rowFilter.operator() + " requires a value");

        sql.append(" LIKE ?")
            .append(LIKE_ESCAPE);

        bindings.add(new Binding(ColumnType.STRING, prefix + escapeLike(String.valueOf(value)) + suffix));
    }

    private static void appendIn(
        StringBuilder sql, List<Binding> bindings, RowFilter rowFilter, ColumnType columnType) {

        List<?> values = listValue(rowFilter);

        Assert.isTrue(!values.isEmpty(), "Operator In requires at least one value");

        sql.append(" IN (");

        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }

            sql.append('?');

            bindings.add(new Binding(columnType, DataTableRowServiceImpl.coerceValue(columnType, values.get(index))));
        }

        sql.append(')');
    }

    private static void appendBetween(
        StringBuilder sql, List<Binding> bindings, RowFilter rowFilter, ColumnType columnType) {

        List<?> values = listValue(rowFilter);

        Assert.isTrue(values.size() == 2, "Operator Between requires exactly two values");

        sql.append(" BETWEEN ? AND ?");

        for (Object value : values) {
            bindings.add(new Binding(columnType, DataTableRowServiceImpl.coerceValue(columnType, value)));
        }
    }

    private static List<?> listValue(RowFilter rowFilter) {
        Object value = rowFilter.value();

        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }

        throw new IllegalArgumentException("Operator " + rowFilter.operator() + " requires a list of values");
    }

    /**
     * Builds the ORDER BY clause, always ending in {@code "id"}.
     *
     * <p>
     * That trailing key is not decoration. LIMIT/OFFSET pagination over a sort whose key has ties leaves the order of
     * the tied rows up to the planner, so the same row can appear on two pages or on none. Ending every sort on the
     * primary key makes the order total, and therefore the pagination stable.
     */
    static String orderBy(List<RowSort> rowSorts, Map<String, ColumnType> columnTypes) {
        if (rowSorts.isEmpty()) {
            return " ORDER BY " + quote(ReservedColumns.ID);
        }

        StringBuilder sql = new StringBuilder(" ORDER BY ");

        for (RowSort rowSort : rowSorts) {
            sql.append(quote(column(rowSort.field(), "sorted on", columnTypes)))
                .append(' ')
                .append(rowSort.direction() == RowSort.Direction.DESC ? "DESC" : "ASC")
                .append(", ");
        }

        return sql.append(quote(ReservedColumns.ID))
            .toString();
    }

    private static String column(RowFilter rowFilter, Map<String, ColumnType> columnTypes) {
        return column(rowFilter.field(), "filtered on", columnTypes);
    }

    private static ColumnType columnType(String column, Map<String, ColumnType> columnTypes) {
        ColumnType columnType = columnTypes.get(column);

        return columnType == null ? ColumnType.INTEGER : columnType;
    }

    /**
     * Resolves the one column a term may name. {@code id} is accepted although it is reserved -- it is on every row
     * already, and sorting by it descending is how a caller asks for the newest records.
     */
    private static String column(String field, String verb, Map<String, ColumnType> columnTypes) {
        Assert.hasText(field, "A query term must name a column");

        String column = field.toLowerCase(Locale.ROOT);

        Assert.isTrue(!ReservedColumns.isHidden(column), "Column '" + field + "' cannot be " + verb);
        Assert.isTrue(
            columnTypes.containsKey(column) || ReservedColumns.ID.equals(column), "Unknown column: " + field);

        return column;
    }

    private static String quote(String column) {
        Assert.isTrue(column.matches("[a-z_][a-z0-9_]*"), "Invalid identifier: " + column);

        return '"' + column + '"';
    }

    /**
     * Escapes the wildcards LIKE would otherwise honour, so a search for a literal {@code %} finds one. The backslash
     * goes first, or it would escape the escapes added after it.
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }
}
