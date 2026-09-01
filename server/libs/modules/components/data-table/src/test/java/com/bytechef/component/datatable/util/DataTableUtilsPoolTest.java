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

package com.bytechef.component.datatable.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.datatable.util.DataTableUtils.ResolvedDataTable;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.owner.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The runtime scopes on WHO the run is for, never on where the workflow was authored -- the embedded bridge dispatches
 * an automation workflow under a connected user, so a platform-type-keyed rule would hand that run the vendor's tables
 * and hide the account's own.
 *
 * <p>
 * Resolution goes through two APIs on {@link DataTableService} for two different reasons:
 * {@code fetchDataTableResolution} decides WHICH registry row wins under owned-wins-shared-fallback within a pool and
 * which physical table that row occupies, and {@code listTables} is still the only source of column metadata, so it is
 * used a second time, keyed by id, to recover that row's details.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class DataTableUtilsPoolTest {

    private static final long ENVIRONMENT_ID = 0L;

    @Mock
    private DataTableService dataTableService;

    @Test
    void testAnOwnedRunSeesOnlyTheEmbeddedPool() {
        Optional<Owner> owner = Optional.of(Owner.connectedUser(1055L));

        when(dataTableService.listTables(anyLong(), eq(PlatformType.EMBEDDED), eq(owner))).thenReturn(List.of());

        DataTableUtils.getTableOptions(null, dataTableService, owner);

        verify(dataTableService).listTables(anyLong(), eq(PlatformType.EMBEDDED), eq(owner));
        verify(dataTableService, never()).listTables(anyLong(), eq(PlatformType.AUTOMATION), any());
    }

    @Test
    void testAnUnownedRunSeesBothPools() {
        when(dataTableService.listTables(anyLong(), any(PlatformType.class), eq(Optional.empty())))
            .thenReturn(List.of());

        DataTableUtils.getTableOptions(null, dataTableService, Optional.empty());

        verify(dataTableService).listTables(anyLong(), eq(PlatformType.EMBEDDED), eq(Optional.empty()));
        verify(dataTableService).listTables(anyLong(), eq(PlatformType.AUTOMATION), eq(Optional.empty()));
    }

    @Test
    void testPoolForIsOwnerKeyed() {
        assertThat(DataTableUtils.poolFor(Optional.of(Owner.connectedUser(1L))))
            .containsExactly(PlatformType.EMBEDDED);
        assertThat(DataTableUtils.poolFor(Optional.empty()))
            .containsExactlyInAnyOrder(PlatformType.AUTOMATION, PlatformType.EMBEDDED);
    }

    @Test
    void testAnOwnedRunLearnsNothingAboutAnAutomationTableOfTheSameName() {
        Optional<Owner> owner = Optional.of(Owner.connectedUser(1055L));

        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(Optional.empty());

        assertThat(DataTableUtils.getDataTableInfo(dataTableService, "invoices", ENVIRONMENT_ID, owner))
            .as("the vendor's invoices table must not leak its column structure to an account naming it")
            .isNull();

        verify(dataTableService, never()).fetchDataTableResolution(
            anyString(), anyLong(), eq(PlatformType.AUTOMATION), any());
    }

    @Test
    void testAVendorRunReadsTheTableFromWhicheverPoolHoldsIt() {
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.AUTOMATION,
            Optional.empty()))
                .thenReturn(Optional.empty());
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.empty()))
                .thenReturn(Optional.of(dataTableResolution(2L, "invoices", PlatformType.EMBEDDED)));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty()))
            .thenReturn(List.of(dataTableInfo(2L, "invoices")));

        ResolvedDataTable resolvedDataTable = DataTableUtils.resolveDataTable(
            dataTableService, "invoices", ENVIRONMENT_ID, Optional.empty());

        assertThat(resolvedDataTable.platformType()).isEqualTo(PlatformType.EMBEDDED);
    }

    @Test
    void testAnOwnedRunResolvesTheEmbeddedPoolWithoutConsultingTheAutomationOne() {
        Optional<Owner> owner = Optional.of(Owner.connectedUser(1055L));

        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(Optional.of(dataTableResolution(2L, "invoices", PlatformType.EMBEDDED)));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(List.of(dataTableInfo(2L, "invoices")));

        ResolvedDataTable resolvedDataTable = DataTableUtils.resolveDataTable(
            dataTableService, "invoices", ENVIRONMENT_ID, owner);

        assertThat(resolvedDataTable.platformType()).isEqualTo(PlatformType.EMBEDDED);

        verify(dataTableService, never()).fetchDataTableResolution(
            anyString(), anyLong(), eq(PlatformType.AUTOMATION), any());
        verify(dataTableService, never()).listTables(anyLong(), eq(PlatformType.AUTOMATION), any());
    }

    @Test
    void testAVendorRunRefusesToGuessWhenBothPoolsHoldTheName() {
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.AUTOMATION,
            Optional.empty()))
                .thenReturn(Optional.of(dataTableResolution(1L, "invoices", PlatformType.AUTOMATION)));
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.empty()))
                .thenReturn(Optional.of(dataTableResolution(2L, "invoices", PlatformType.EMBEDDED)));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty()))
            .thenReturn(List.of(dataTableInfo(1L, "invoices")));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty()))
            .thenReturn(List.of(dataTableInfo(2L, "invoices")));

        assertThatThrownBy(
            () -> DataTableUtils.resolveDataTable(dataTableService, "invoices", ENVIRONMENT_ID, Optional.empty()))
                .as("the picker renders both pools' tables under one identical value, so guessing here clears or "
                    + "overwrites the wrong table")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invoices")
                .hasMessageContaining("AUTOMATION")
                .hasMessageContaining("EMBEDDED");
    }

    @Test
    void testReadingMetadataRefusesToGuessWhenBothPoolsHoldTheName() {
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.AUTOMATION,
            Optional.empty()))
                .thenReturn(Optional.of(dataTableResolution(1L, "invoices", PlatformType.AUTOMATION)));
        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.empty()))
                .thenReturn(Optional.of(dataTableResolution(2L, "invoices", PlatformType.EMBEDDED)));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty()))
            .thenReturn(List.of(dataTableInfo(1L, "invoices")));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty()))
            .thenReturn(List.of(dataTableInfo(2L, "invoices")));

        assertThatThrownBy(
            () -> DataTableUtils.getDataTableInfo(dataTableService, "invoices", ENVIRONMENT_ID, Optional.empty()))
                .as("column metadata must not silently describe whichever pool the scan reached first")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testResolvingAPoolFailsClosedWhenNoPoolHoldsTheTable() {
        when(dataTableService.fetchDataTableResolution(anyString(), anyLong(), any(PlatformType.class),
            eq(Optional.empty())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> DataTableUtils.resolveDataTable(dataTableService, "invoices", ENVIRONMENT_ID, Optional.empty()))
                .as("an unresolvable pool must yield nothing rather than falling back to one the run may not read")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invoices");
    }

    /**
     * A trigger's sample output is a real row read out of the resolved table, and on the vendor's shared table that
     * read is scoped by the run owner like every other. The ref resolution returns is passed through whole, so the
     * account driving the editor sees one of its own rows rather than whichever row the shared table happens to hold
     * first -- which, in a table every account writes into, is somebody's.
     */
    @Test
    void testATriggersSampleRowIsReadAsTheRunOwner() {
        Owner accountOwner = Owner.connectedUser(1055L);
        Optional<Owner> owner = Optional.of(accountOwner);

        DataTableRowService dataTableRowService = mock(DataTableRowService.class);

        when(dataTableService.fetchDataTableResolution("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(
                Optional.of(
                    new DataTableResolution(
                        4L,
                        new DataTableRef("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, null, accountOwner))));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(List.of(dataTableInfo(4L, "invoices")));
        when(dataTableRowService.listRows(any(DataTableRef.class), anyInt(), anyInt())).thenReturn(List.of());

        DataTableUtils.createTriggerOutputResponse(dataTableRowService, dataTableService, "invoices", owner);

        ArgumentCaptor<DataTableRef> dataTableRefArgumentCaptor = ArgumentCaptor.forClass(DataTableRef.class);

        verify(dataTableRowService).listRows(dataTableRefArgumentCaptor.capture(), anyInt(), anyInt());

        DataTableRef dataTableRef = dataTableRefArgumentCaptor.getValue();

        assertThat(dataTableRef.runOwner())
            .as("a trigger's sample row must be read as the account the trigger runs for")
            .isEqualTo(accountOwner);
    }

    private static DataTableResolution dataTableResolution(long id, String name, PlatformType platformType) {
        return new DataTableResolution(id, DataTableRef.shared(name, ENVIRONMENT_ID, platformType));
    }

    private static DataTableInfo dataTableInfo(long id, String baseName) {
        return new DataTableInfo(id, baseName, null, List.of(), Instant.now(), null);
    }
}
