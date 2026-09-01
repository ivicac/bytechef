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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.audit.DataTableAuditPublisher;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.repository.DataTableRepository;
import com.bytechef.platform.owner.Owner;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The same visibility rule the rows already follow, one level up: a table with no owner belongs to the vendor and is
 * everyone's, and a caller with no owner is an admin or an automation caller and sees everything.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class DataTableOwnershipTest {

    private static final Optional<Owner> ACCOUNT_A = Optional.of(Owner.connectedUser(1L));
    private static final Optional<Owner> ACCOUNT_B = Optional.of(Owner.connectedUser(2L));
    private static final Optional<Owner> NO_OWNER = Optional.empty();

    @Mock
    private DataTableRepository dataTableRepository;

    @Test
    void testAnUnownedTableIsVisibleToEveryone() {
        assertTrue(DataTableServiceImpl.isReadableBy(unowned(), ACCOUNT_A));
        assertTrue(DataTableServiceImpl.isReadableBy(unowned(), ACCOUNT_B));
    }

    @Test
    void testAnAdminWithNoOwnerSeesEveryTable() {
        assertTrue(DataTableServiceImpl.isReadableBy(ownedBy(2L), NO_OWNER));
        assertTrue(DataTableServiceImpl.isReadableBy(unowned(), NO_OWNER));
    }

    @Test
    void testAnAccountSeesItsOwnTable() {
        assertTrue(DataTableServiceImpl.isReadableBy(ownedBy(1L), ACCOUNT_A));
    }

    @Test
    void testAnAccountDoesNotSeeAnotherAccountsTable() {
        assertFalse(DataTableServiceImpl.isReadableBy(ownedBy(2L), ACCOUNT_A));
    }

    /**
     * A half-written owner -- an id with no type -- must not read as unowned, or a botched write would silently share
     * the table with every account.
     */
    @Test
    void testATableWithAnIdButNoTypeIsNotVisibleToAnAccount() {
        DataTable dataTable = new DataTable();

        dataTable.setOwnerId(1L);

        assertFalse(DataTableServiceImpl.isReadableBy(dataTable, ACCOUNT_A));
    }

    @Test
    void testAssigningAnOwnerStampsBothColumns() {
        DataTable dataTable = embedded(unowned());

        when(dataTableRepository.findById(7L)).thenReturn(Optional.of(dataTable));

        dataTableService().assignOwner(7L, Owner.connectedUser(1L));

        assertEquals(1L, dataTable.getOwnerId());
        assertEquals(OwnerType.CONNECTED_USER, dataTable.getOwnerType());
    }

    /**
     * Only the EMBEDDED pool has accounts. An AUTOMATION table is unowned by definition, and an owner on one resolves
     * for nobody: a run with an owner is narrowed to EMBEDDED, and a run without one only ever reaches unowned rows.
     */
    @Test
    void testAssigningAnOwnerToAnAutomationTableIsRefused() {
        DataTable dataTable = unowned();

        when(dataTableRepository.findById(7L)).thenReturn(Optional.of(dataTable));

        assertThrows(
            IllegalArgumentException.class, () -> dataTableService().assignOwner(7L, Owner.connectedUser(1L)));

        assertNull(dataTable.getOwnerId());
        assertNull(dataTable.getOwnerType());
    }

    @Test
    void testAssigningANullOwnerReturnsTheTableToTheVendor() {
        DataTable dataTable = embedded(ownedBy(1L));

        when(dataTableRepository.findById(7L)).thenReturn(Optional.of(dataTable));

        dataTableService().assignOwner(7L, null);

        assertNull(dataTable.getOwnerId());
        assertNull(dataTable.getOwnerType());
        assertTrue(DataTableServiceImpl.isReadableBy(dataTable, ACCOUNT_B));
    }

    /**
     * The repair path for a row that acquired an owner before the pool guard above existed. Refusing this too would
     * leave such a row permanently unreachable, so unassigning stays allowed in every pool.
     */
    @Test
    void testAnAutomationTableThatSomehowCarriesAnOwnerCanStillBeReturnedToTheVendor() {
        DataTable dataTable = ownedBy(1L);

        when(dataTableRepository.findById(7L)).thenReturn(Optional.of(dataTable));

        dataTableService().assignOwner(7L, null);

        assertNull(dataTable.getOwnerId());
        assertNull(dataTable.getOwnerType());
    }

    @Test
    void testAssigningAnOwnerToAMissingTableIsRejected() {
        when(dataTableRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class, () -> dataTableService().assignOwner(7L, Owner.connectedUser(1L)));
    }

    private DataTableServiceImpl dataTableService() {
        return new DataTableServiceImpl(
            mock(DataTableAuditPublisher.class), dataTableRepository, mock(JdbcTemplate.class));
    }

    /**
     * Named, because {@code assignOwner} now moves the physical tables with the row and reads the name to find them.
     * Against a mocked {@code JdbcTemplate} the scan returns nothing and no rename is attempted, which is the point --
     * what these tests pin is the registry half.
     */
    private static DataTable unowned() {
        DataTable dataTable = new DataTable();

        dataTable.setName("orders");

        return dataTable;
    }

    private static DataTable ownedBy(long ownerId) {
        DataTable dataTable = unowned();

        dataTable.setOwnerId(ownerId);
        dataTable.setOwnerType(OwnerType.CONNECTED_USER);

        return dataTable;
    }

    /**
     * {@code platformType} is a primitive ordinal, so an unstamped {@link DataTable} is AUTOMATION. Every table these
     * tests assign an owner to has to say EMBEDDED out loud.
     */
    private static DataTable embedded(DataTable dataTable) {
        dataTable.setPlatformType(PlatformType.EMBEDDED);

        return dataTable;
    }
}
