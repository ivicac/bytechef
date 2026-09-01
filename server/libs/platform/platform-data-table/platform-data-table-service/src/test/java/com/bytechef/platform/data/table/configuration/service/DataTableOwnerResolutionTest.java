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

import static com.bytechef.platform.constant.PlatformType.EMBEDDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.repository.DataTableRepository;
import com.bytechef.platform.owner.Owner;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The owned-wins-shared-fallback resolution rule: the CU's own table wins over the shared one, and a run with no owner
 * never falls through to an account's table.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class DataTableOwnerResolutionTest {

    private static final long ACCOUNT_ID = 42L;

    // Distinct, and that is the whole point -- see tableNamed.
    private static final long OWNED_ID = 1L;
    private static final long SHARED_ID = 2L;

    @Mock
    private DataTableRepository dataTableRepository;

    @InjectMocks
    private DataTableServiceImpl dataTableService;

    @Test
    void testTheAccountsOwnTableWinsOverTheSharedOne() {
        DataTable owned = tableNamed(OWNED_ID, "orders", ACCOUNT_ID);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdAndOwnerType(
            "orders", EMBEDDED.ordinal(), ACCOUNT_ID, OwnerType.CONNECTED_USER.ordinal()))
                .thenReturn(Optional.of(owned));

        Optional<DataTable> resolved = dataTableService.fetchDataTable(
            "orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        assertThat(resolved).contains(owned);

        // The shared lookup must not even be attempted -- falling through would make the override advisory.
        verify(dataTableRepository, never())
            .findByNameAndPlatformTypeAndOwnerIdIsNull(anyString(), anyInt());
    }

    @Test
    void testAnAccountWithNoOwnTableFallsBackToTheSharedOne() {
        DataTable shared = tableNamed(SHARED_ID, "orders", null);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdAndOwnerType(
            "orders", EMBEDDED.ordinal(), ACCOUNT_ID, OwnerType.CONNECTED_USER.ordinal()))
                .thenReturn(Optional.empty());
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.of(shared));

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .contains(shared);
    }

    @Test
    void testAVendorRunReadsOnlyTheSharedTable() {
        DataTable shared = tableNamed(SHARED_ID, "orders", null);

        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.of(shared));

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.empty())).contains(shared);

        // A vendor run must never resolve an account's table implicitly; it names the account or sees shared.
        //
        // nullable(...) rather than anyLong()/anyInt(): both owner parameters are boxed, and the any* matchers do not
        // match null, so this would have tolerated an implementation that called the owned finder with a null owner.
        verify(dataTableRepository, never())
            .findByNameAndPlatformTypeAndOwnerIdAndOwnerType(
                anyString(), anyInt(), nullable(Long.class), nullable(Integer.class));
    }

    @Test
    void testNeitherTableResolvesToEmpty() {
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdAndOwnerType(
            "orders", EMBEDDED.ordinal(), ACCOUNT_ID, OwnerType.CONNECTED_USER.ordinal()))
                .thenReturn(Optional.empty());
        when(dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull("orders", EMBEDDED.ordinal()))
            .thenReturn(Optional.empty());

        assertThat(dataTableService.fetchDataTable("orders", EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID))))
            .isEmpty();
    }

    /**
     * The id is not decoration. {@link DataTable#equals} compares id and nothing else, so two fixtures built without
     * one are equal to each other and every {@code contains} above would degrade to "some table was returned" --
     * passing just as happily for the shared table as for the account's own.
     */
    private static DataTable tableNamed(long id, String name, @Nullable Long ownerId) {
        DataTable dataTable = new DataTable();

        dataTable.setId(id);
        dataTable.setName(name);
        dataTable.setOwnerId(ownerId);

        return dataTable;
    }
}
