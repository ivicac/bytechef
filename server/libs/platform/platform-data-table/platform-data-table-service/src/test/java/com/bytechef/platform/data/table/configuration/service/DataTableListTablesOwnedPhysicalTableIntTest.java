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

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.repository.DataTableRepository;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A connected user's own physical table must appear in {@link DataTableService#listTables} for that owner, under its
 * plain base name -- not disappear behind a "skip unregistered physical table" warning and not show up spelled with the
 * owner id baked into its name.
 *
 * <p>
 * No production code path creates an owned physical table yet (that lands with the component surface in a later task),
 * so this test builds the scenario directly: a physical table named in the owned form
 * ({@code edt_<envId>_<ownerId>_<ownerTypeToken>_<baseName>}) plus the registry row an owned {@code createTable} would
 * have written for it.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableListTablesOwnedPhysicalTableIntTest {

    private static final long ENVIRONMENT_ID = 0;
    private static final long OWNER_ID = 5L;
    private static final long STALE_OWNER_ID = 7L;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRepository dataTableRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testAnOwnedPhysicalTableIsListedUnderItsPlainBaseNameForItsOwner() {
        jdbcTemplate.execute(
            "CREATE TABLE edt_0_5_connecteduser_orders (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, "
                + "\"owner_type\" INT, \"title\" VARCHAR(255))");

        DataTable dataTable = new DataTable();

        dataTable.setName("orders");
        dataTable.setPlatformType(PlatformType.EMBEDDED);
        dataTable.setOwnerId(OWNER_ID);
        dataTable.setOwnerType(OwnerType.CONNECTED_USER);

        dataTableRepository.save(dataTable);

        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.of(Owner.connectedUser(OWNER_ID)));

        assertThat(dataTableInfos)
            .as("the owned physical table must be recovered as base name 'orders', not '5_connecteduser_orders'")
            .anySatisfy(info -> {
                assertThat(info.baseName()).isEqualTo("orders");
                assertThat(info.ownerId()).isEqualTo(OWNER_ID);
            });
    }

    /**
     * A table left at the pre-{@code owner_type} name is invisible rather than mis-attributed. Owned physical tables
     * only ever existed on this branch, so the only ones carrying the old {@code <ownerId>_<baseName>} form are in
     * local development databases -- and there is no in-product repair for them, because the rename fan-out on
     * {@code assignOwner} matches the new form and finds nothing to move. Recreating them is the answer, and this pins
     * what happens meanwhile: the name does not parse, the registry lookup misses, and {@code listTables} skips it with
     * a warning. It is never handed to an owner it does not belong to.
     */
    @Test
    void testATableLeftAtTheOldOwnedNameIsSkippedRatherThanAttributed() {
        jdbcTemplate.execute(
            "CREATE TABLE edt_0_7_invoices (\"id\" BIGSERIAL PRIMARY KEY, \"owner_id\" BIGINT, \"owner_type\" INT, "
                + "\"title\" VARCHAR(255))");

        DataTable dataTable = new DataTable();

        dataTable.setName("invoices");
        dataTable.setPlatformType(PlatformType.EMBEDDED);
        dataTable.setOwnerId(STALE_OWNER_ID);
        dataTable.setOwnerType(OwnerType.CONNECTED_USER);

        dataTableRepository.save(dataTable);

        List<DataTableInfo> dataTableInfos = dataTableService.listTables(
            ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.of(Owner.connectedUser(STALE_OWNER_ID)));

        assertThat(dataTableInfos)
            .extracting(DataTableInfo::baseName)
            .doesNotContain("invoices", "7_invoices");
    }
}
