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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every physical data table carries the row owner columns, whether the table itself has an owner or not.
 *
 * <p>
 * The uniformity is the claim worth pinning. In a table an account already owns every row is that account's anyway, so
 * the row predicate is satisfied by construction and costs an index lookup -- which is what lets a row operation stay
 * ignorant of which kind of table it holds. Declare the columns on shared tables only and every statement would have to
 * ask first.
 *
 * <p>
 * Asserted on the generated SQL rather than on a live table, so the two DDL paths can be pinned without Postgres. The
 * create and duplicate paths were near-copies of one string; both go through the one helper asserted here, which is
 * what stops them drifting apart again.
 *
 * @author Ivica Cardic
 */
class DataTableDdlOwnerColumnsTest {

    @Test
    void testTheCreateStatementDeclaresBothOwnerColumns() {
        String createTableSql = DataTableServiceImpl.buildCreateTableSql("edt_0_5_orders", List.of());

        assertThat(createTableSql).contains("\"owner_id\" BIGINT");
        assertThat(createTableSql).contains("\"owner_type\" INT");
    }

    /**
     * A shared table gets the same columns as an owned one. The owned form is the case that could plausibly have been
     * special-cased, so the shared form is asserted beside it rather than assumed.
     */
    @Test
    void testASharedTableDeclaresTheOwnerColumnsToo() {
        String createTableSql = DataTableServiceImpl.buildCreateTableSql("edt_0_orders", List.of());

        assertThat(createTableSql).contains("\"owner_id\" BIGINT");
        assertThat(createTableSql).contains("\"owner_type\" INT");
    }

    @Test
    void testTheCreateStatementStillDeclaresThePrimaryKey() {
        assertThat(DataTableServiceImpl.buildCreateTableSql("edt_0_5_orders", List.of()))
            .contains("\"id\" BIGSERIAL PRIMARY KEY");
    }

    @Test
    void testTheCreateStatementDeclaresTheUserColumns() {
        String createTableSql = DataTableServiceImpl.buildCreateTableSql(
            "edt_0_5_orders", List.of(new ColumnSpec("title", ColumnType.STRING)));

        assertThat(createTableSql).contains("\"title\" VARCHAR(255)");
    }

    /**
     * A table with no user columns still has to be creatable: the statement must not end on a dangling comma.
     */
    @Test
    void testAColumnlessTableStillProducesValidSql() {
        assertThat(DataTableServiceImpl.buildCreateTableSql("edt_0_orders", List.of()))
            .isEqualTo(
                "CREATE TABLE \"edt_0_orders\" (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, " +
                    "\"owner_type\" INT, \"external_id\" VARCHAR(255))");
    }

    @Test
    void testCreateTableCarriesTheExternalIdColumn() {
        String sql = DataTableServiceImpl.buildCreateTableSql("dt_0_orders", List.of());

        assertTrue(sql.contains("\"external_id\" VARCHAR(255)"), sql);
    }

    /**
     * The index the row predicate is served from. Unnamed on purpose -- a physical name may already be the 63 bytes
     * Postgres keeps, and a name of ours plus a suffix would truncate into a collision with the next long table's.
     */
    @Test
    void testTheOwnerIndexIsCreatedUnnamed() {
        assertThat(DataTableServiceImpl.buildOwnerIndexSql("edt_0_orders"))
            .isEqualTo("CREATE INDEX ON \"edt_0_orders\" (\"owner_id\")");
    }

    @Test
    void testExternalIdIndexIsPartialAndNullsNotDistinct() {
        String sql = DataTableServiceImpl.buildExternalIdIndexSql("dt_0_orders");

        assertEquals(
            "CREATE UNIQUE INDEX ON \"dt_0_orders\" (\"owner_id\", \"external_id\") NULLS NOT DISTINCT " +
                "WHERE \"external_id\" IS NOT NULL",
            sql);
    }
}
