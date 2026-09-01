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
 * The ref carries two owners on two independent axes: {@code resourceOwner} picks the physical table and
 * {@code runOwner} picks the rows inside it. The three pairings resolution can produce are all legal, and the fourth --
 * an owned table addressed by a run that does not own it -- is refused in the constructor.
 *
 * @author Ivica Cardic
 */
class DataTableRefTest {

    private static final long ENVIRONMENT_ID = 0L;

    private static final Owner ACCOUNT = Owner.connectedUser(42L);
    private static final Owner OTHER_ACCOUNT = Owner.connectedUser(99L);

    @Test
    void testAnOwnedRefSpellsBothHalvesOfItsOwner() {
        DataTableRef dataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT, ACCOUNT);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_42_connecteduser_orders");
        assertThat(dataTableRef.resourceOwnerId()).isEqualTo(42L);
        assertThat(dataTableRef.runOwnerId()).isEqualTo(42L);
    }

    @Test
    void testASharedRefKeepsTheReleasedName() {
        DataTableRef dataTableRef = DataTableRef.shared("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_orders");
        assertThat(dataTableRef.resourceOwner()).isNull();
        assertThat(dataTableRef.runOwner()).isNull();
        assertThat(dataTableRef.resourceOwnerId()).isNull();
        assertThat(dataTableRef.runOwnerId()).isNull();
    }

    /**
     * The pairing one field could not express, and the reason there are two: the table is the vendor's, the run is an
     * account's, and the physical name must stay the shared one while the run owner remains available to scope the
     * rows.
     */
    @Test
    void testAnAccountOnTheSharedTableCarriesARunOwnerAndNoResourceOwner() {
        DataTableRef dataTableRef = new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, null, ACCOUNT);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_orders");
        assertThat(dataTableRef.resourceOwnerId()).isNull();
        assertThat(dataTableRef.runOwnerId()).isEqualTo(42L);
    }

    /**
     * The invariant. Resolution never produces a ref whose resource owner disagrees with its run owner -- a run with no
     * owner resolves only shared tables, and an owned registry row is only ever matched against the run's own owner --
     * so this exists to make the mis-resolved ref unconstructable rather than merely absent.
     */
    @Test
    void testARefForAnotherAccountsTableIsRefused() {
        assertThatThrownBy(
            () -> new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT, OTHER_ACCOUNT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be held by a run acting for that owner");
    }

    /**
     * The vendor variant of the same mistake, and the more dangerous one: a run with no owner addressing an account's
     * physical table reads that account's data with nothing to scope it.
     */
    @Test
    void testAnOwnedRefHeldByARunWithNoOwnerIsRefused() {
        assertThatThrownBy(() -> new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be held by a run acting for that owner");
    }

    /**
     * Postgres truncates an over-long identifier rather than refusing it, so two owned tables whose names agree in
     * their first 63 bytes would silently become one table. The owner is what pushes a name over -- a shared name is
     * the length it always was -- so the check lives where the owner is applied.
     */
    @Test
    void testAnOwnedNameThatWouldTruncateIsRefused() {
        String baseName = "a".repeat(50);

        assertThat(DataTableRef.shared(baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED)
            .physicalName())
                .hasSizeLessThanOrEqualTo(63);

        assertThatThrownBy(
            () -> new DataTableRef(baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT, ACCOUNT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 63 bytes");
    }

    /**
     * A run owner alone never reaches the physical name. Without this, the two axes could be collapsed back into one by
     * accident and nothing but an integration test would notice.
     */
    @Test
    void testTheRunOwnerDoesNotReachThePhysicalName() {
        DataTableRef sharedDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, null, ACCOUNT);
        DataTableRef otherAccountsSharedDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, null, OTHER_ACCOUNT);

        assertThat(sharedDataTableRef.physicalName())
            .isEqualTo(otherAccountsSharedDataTableRef.physicalName())
            .isEqualTo(DataTableRef.shared("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED)
                .physicalName());
    }
}
