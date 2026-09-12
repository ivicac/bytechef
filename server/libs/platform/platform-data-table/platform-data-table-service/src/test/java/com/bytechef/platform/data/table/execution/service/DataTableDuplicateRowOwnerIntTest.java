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

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A duplicate copies the rows AND whose each of them is.
 *
 * <p>
 * One table holds every account's rows, so the copy inherits a mix of owners rather than a single one. Dropping the
 * owner columns from the INSERT ... SELECT would leave every copied row unowned: readable by every account, since the
 * read predicate admits unowned rows, and writable by none, since the write predicate matches on the owner alone. The
 * failure is silent -- the copy looks complete and behaves as though its rows belonged to nobody.
 *
 * <p>
 * Fixture tables are named {@code dro_*} because the integration tests in this module share one schema with no
 * per-class isolation.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableDuplicateRowOwnerIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(7701L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(7702L);
    private static final long ENVIRONMENT_ID = 0;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableRowService dataTableRowService;

    @Test
    void testACopiedRowKeepsTheAccountItBelongedTo() {
        dataTableService.createTable(
            "dro_source", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED);

        dataTableRowService.insertRow(resolve("dro_source", ACCOUNT_A), Map.of("title", "a row"));

        dataTableService.duplicateTable("dro_source", "dro_copy", ENVIRONMENT_ID, PlatformType.EMBEDDED);

        List<DataTableRow> copiedDataTableRows = dataTableRowService.listRows(resolve("dro_copy", ACCOUNT_A), 100, 0);

        assertThat(copiedDataTableRows)
            .as("the account whose row it was still reads it in the copy")
            .hasSize(1);

        DataTableRow copiedDataTableRow = copiedDataTableRows.getFirst();

        assertThat(
            dataTableRowService.updateRow(resolve("dro_copy", ACCOUNT_A), copiedDataTableRow.id(),
                Map.of("title", "changed"))
                .values())
                    .as("a copied row must still be writable by the account whose row it is")
                    .containsEntry("title", "changed");

        assertThat(dataTableRowService.listRows(resolve("dro_copy", ACCOUNT_B), 100, 0))
            .as("and must not have become unowned, which would make it every account's")
            .isEmpty();
    }

    private DataTableRef resolve(String baseName, @Nullable Owner owner) {
        DataTableResolution dataTableResolution = dataTableService.fetchDataTableResolution(
            baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.ofNullable(owner))
            .orElseThrow();

        return dataTableResolution.dataTableRef();
    }
}
