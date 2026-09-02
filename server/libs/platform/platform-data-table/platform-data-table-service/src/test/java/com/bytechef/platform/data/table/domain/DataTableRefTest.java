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

package com.bytechef.platform.data.table.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import org.junit.jupiter.api.Test;

/**
 * The ref carries one owner, and it picks rows rather than tables: two runs acting for two accounts address one
 * physical table and are told apart inside it.
 *
 * @author Ivica Cardic
 */
class DataTableRefTest {

    private static final long ENVIRONMENT_ID = 0L;

    private static final Owner ACCOUNT = Owner.connectedUser(42L);
    private static final Owner OTHER_ACCOUNT = Owner.connectedUser(99L);

    @Test
    void testARefForARunWithNoAccountCarriesNoOwner() {
        DataTableRef dataTableRef = DataTableRef.unowned("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_orders");
        assertThat(dataTableRef.runOwner()).isNull();
        assertThat(dataTableRef.runOwnerId()).isNull();
    }

    @Test
    void testARefForAnAccountCarriesThatAccountAsItsRunOwner() {
        DataTableRef dataTableRef = new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_orders");
        assertThat(dataTableRef.runOwnerId()).isEqualTo(42L);
    }

    /**
     * The physical name is what a run addresses. If an owner could reach it, two accounts would have two tables again
     * and the row predicate would stop being the thing that separates them.
     */
    @Test
    void testNoPhysicalNameCarriesAnOwnerSegment() {
        DataTableRef ownedRun = new DataTableRef("orders", 1, PlatformType.EMBEDDED, Owner.connectedUser(42));
        DataTableRef vendorRun = DataTableRef.unowned("orders", 1, PlatformType.EMBEDDED);

        assertThat(ownedRun.physicalName())
            .as("the run owner scopes rows, never the table")
            .isEqualTo(vendorRun.physicalName())
            .isEqualTo("edt_1_orders");
    }

    /**
     * The same claim across two different accounts, which is where a reintroduced owner segment would show up first: if
     * either account reached a name of its own, the two would stop sharing a table.
     */
    @Test
    void testTwoAccountsAddressTheSameTable() {
        DataTableRef accountDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT);
        DataTableRef otherAccountDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, OTHER_ACCOUNT);

        assertThat(accountDataTableRef.physicalName())
            .isEqualTo(otherAccountDataTableRef.physicalName())
            .isEqualTo(DataTableRef.unowned("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED)
                .physicalName());
    }

    /**
     * Postgres truncates an over-long identifier rather than refusing it, so two tables whose names agree in their
     * first 63 bytes would silently become one table. A long enough base name is all it takes.
     */
    @Test
    void testANameThatWouldTruncateIsRefused() {
        assertThat(DataTableRef.unowned("a".repeat(50), ENVIRONMENT_ID, PlatformType.EMBEDDED)
            .physicalName())
                .hasSizeLessThanOrEqualTo(63);

        assertThatThrownBy(
            () -> DataTableRef.unowned("a".repeat(70), ENVIRONMENT_ID, PlatformType.EMBEDDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 63 bytes");
    }

    @Test
    void testABaseNameSpelledAsAPhysicalNameIsRefused() {
        assertThatThrownBy(() -> DataTableRef.unowned("dt_0_orders", ENVIRONMENT_ID, PlatformType.AUTOMATION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not start with 'dt_'");
    }
}
