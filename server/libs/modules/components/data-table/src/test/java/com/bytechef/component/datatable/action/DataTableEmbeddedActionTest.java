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

package com.bytechef.component.datatable.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ActionDefinition.PerformFunction;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.domain.RowFilter;
import com.bytechef.platform.data.table.domain.RowSort;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The embedded pool is where ownership means anything, and until now no test drove an action against it at all. That
 * gap let a change ship in which every embedded read returned every account's rows and every embedded write threw, with
 * the whole suite green.
 *
 * <p>
 * What these tests pin is the thing that separates one account from another: the action must hand the row service the
 * table that resolution settled on, owner and all, and never a name the row service would have to guess an owner for.
 * An account's step must therefore reach {@code edt_<env>_<accountId>_<baseName>}, and a vendor step naming nothing
 * must reach the unowned {@code edt_<env>_<baseName>}.
 *
 * <p>
 * That is the resource axis, and it only separates accounts that own a table each. The row axis is the other half: on
 * the vendor's SHARED table -- the case row ownership exists for -- resolution settles on one physical table for
 * everybody, the ref carries no resource owner, and the only thing left separating account 42's rows from account 99's
 * is the run owner the action put in that ref. The tests below therefore let resolution mint the ref from whatever
 * owner the action passes it, exactly as {@code DataTableServiceImpl} does, rather than handing back a canned one: a
 * canned ref carries the run owner the test wrote into it however wrong the action's own owner was, which is precisely
 * the wiring under test.
 *
 * @author Ivica Cardic
 */
class DataTableEmbeddedActionTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long DATA_TABLE_ID = 7L;
    private static final long ENVIRONMENT_ID = 0L;
    private static final long OTHER_ACCOUNT_ID = 99L;
    private static final Owner ACCOUNT_OWNER = Owner.connectedUser(ACCOUNT_ID);
    private static final Owner OTHER_ACCOUNT_OWNER = Owner.connectedUser(OTHER_ACCOUNT_ID);

    private final DataTableRowService dataTableRowService = mock(DataTableRowService.class);
    private final DataTableService dataTableService = mock(DataTableService.class);

    @Test
    void testAnAccountsFindRecordsReadsWithThatAccountsOwnRunOwner() throws Exception {
        DataTableRef ownedDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT_OWNER);

        givenResolution(ownedDataTableRef, ACCOUNT_ID);

        when(
            dataTableRowService.listRows(
                any(DataTableRef.class), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
                ArgumentMatchers.<List<RowSort>>any()))
                    .thenReturn(List.of(new DataTableRow(1L, Map.of("title", "a"))));

        perform(
            DataTableFindRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(ACCOUNT_ID)),
            Map.of("table", "orders", "limit", 100, "offset", 0));

        verify(dataTableRowService).listRows(ownedDataTableRef, 100, 0, List.<RowFilter>of(), List.<RowSort>of());
    }

    /**
     * The same claim for a second account, and the pairing that makes it worth stating twice: the two refs address one
     * physical table and differ only in the owner the rows are read as. An action that resolved its table without
     * passing its own owner would reach the right table and read the wrong account's rows.
     */
    @Test
    void testAnotherAccountsFindRecordsReadsTheSameTableAsThatAccount() throws Exception {
        DataTableRef otherOwnedDataTableRef = new DataTableRef(
            "orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, OTHER_ACCOUNT_OWNER);

        givenResolution(otherOwnedDataTableRef, OTHER_ACCOUNT_ID);

        when(
            dataTableRowService.listRows(
                any(DataTableRef.class), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
                ArgumentMatchers.<List<RowSort>>any()))
                    .thenReturn(List.of());

        perform(
            DataTableFindRecordsAction.of(
                dataTableService, dataTableRowService, ownerResolverProvider(OTHER_ACCOUNT_ID)),
            Map.of("table", "orders", "limit", 100, "offset", 0));

        verify(dataTableRowService).listRows(
            otherOwnedDataTableRef, 100, 0, List.<RowFilter>of(), List.<RowSort>of());

        DataTableRef accountDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT_OWNER);

        assertThat(otherOwnedDataTableRef.physicalName())
            .as("two accounts naming the same table share it, and only the run owner tells them apart")
            .isEqualTo(accountDataTableRef.physicalName());
        assertThat(otherOwnedDataTableRef.runOwner()).isNotEqualTo(accountDataTableRef.runOwner());
    }

    @Test
    void testAnAccountsCreateRecordsWritesWithThatAccountsOwnRunOwner() throws Exception {
        DataTableRef ownedDataTableRef =
            new DataTableRef("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT_OWNER);

        givenResolution(ownedDataTableRef, ACCOUNT_ID);

        when(dataTableRowService.insertRow(any(DataTableRef.class), any()))
            .thenReturn(new DataTableRow(1L, Map.of("title", "a")));

        // Every embedded write threw IllegalArgumentException before physical tables carried the owner, so simply
        // completing is half of what this asserts.
        perform(
            DataTableCreateRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(ACCOUNT_ID)),
            Map.of("table", "orders", "records", Map.of("values", List.of(Map.of("title", "a")))));

        verify(dataTableRowService).insertRow(ownedDataTableRef, Map.of("title", "a"));
    }

    @Test
    void testAVendorStepNamingNoAccountWritesWithNoRunOwner() throws Exception {
        DataTableRef sharedDataTableRef = DataTableRef.unowned("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED);

        givenResolution(sharedDataTableRef, null);

        when(dataTableService.fetchDataTableResolution(
            "orders", ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty()))
                .thenReturn(Optional.empty());

        when(dataTableRowService.insertRow(any(DataTableRef.class), any()))
            .thenReturn(new DataTableRow(1L, Map.of("title", "a")));

        perform(
            DataTableCreateRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(null)),
            Map.of("table", "orders", "records", Map.of("values", List.of(Map.of("title", "a")))));

        verify(dataTableRowService).insertRow(sharedDataTableRef, Map.of("title", "a"));

        assertThat(sharedDataTableRef.physicalName())
            .as("a physical name carries the pool and the environment and nothing else")
            .isEqualTo("edt_0_orders");
    }

    /**
     * The row axis on the shared table. Both accounts resolve the vendor's {@code orders}, so the ref they read with is
     * identical but for its run owner -- and an action that resolved its table without passing its own owner would read
     * the unowned rows and never notice, because the table it reached is right.
     */
    @Test
    void testAnAccountsFindRecordsOnTheSharedTableScopesTheRowsToThatAccount() throws Exception {
        givenSharedTableResolvedForWhoeverAsks();

        when(
            dataTableRowService.listRows(
                any(DataTableRef.class), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
                ArgumentMatchers.<List<RowSort>>any()))
                    .thenReturn(List.of());

        perform(
            DataTableFindRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(ACCOUNT_ID)),
            Map.of("table", "orders", "limit", 100, "offset", 0));

        DataTableRef dataTableRef = capturedListRowsDataTableRef();

        assertThat(dataTableRef.runOwner())
            .as("an account's read of the one table must be scoped to that account's rows")
            .isEqualTo(ACCOUNT_OWNER);
    }

    @Test
    void testAVendorFindRecordsOnTheSharedTableScopesTheRowsToNobody() throws Exception {
        givenSharedTableResolvedForWhoeverAsks();

        when(dataTableService.fetchDataTableResolution(
            "orders", ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty()))
                .thenReturn(Optional.empty());

        when(
            dataTableRowService.listRows(
                any(DataTableRef.class), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
                ArgumentMatchers.<List<RowSort>>any()))
                    .thenReturn(List.of());

        perform(
            DataTableFindRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(null)),
            Map.of("table", "orders", "limit", 100, "offset", 0));

        assertThat(capturedListRowsDataTableRef().runOwner())
            .as("a vendor run reads the unowned rows and must not fall through to an account's")
            .isNull();
    }

    /**
     * The stamp on a new row is the same run owner, which is why an insert needs no more wiring than a read: the ref
     * the action hands the row service is what decides both.
     */
    @Test
    void testAnAccountsCreateRecordsOnTheSharedTableStampsItsOwnRows() throws Exception {
        givenSharedTableResolvedForWhoeverAsks();

        when(dataTableRowService.insertRow(any(DataTableRef.class), any()))
            .thenReturn(new DataTableRow(1L, Map.of("title", "a")));

        perform(
            DataTableCreateRecordsAction.of(dataTableService, dataTableRowService, ownerResolverProvider(ACCOUNT_ID)),
            Map.of("table", "orders", "records", Map.of("values", List.of(Map.of("title", "a")))));

        ArgumentCaptor<DataTableRef> dataTableRefArgumentCaptor = ArgumentCaptor.forClass(DataTableRef.class);

        verify(dataTableRowService).insertRow(dataTableRefArgumentCaptor.capture(), any());

        DataTableRef dataTableRef = dataTableRefArgumentCaptor.getValue();

        assertThat(dataTableRef.runOwner())
            .as("a row an account writes into the shared table must be stamped with that account")
            .isEqualTo(ACCOUNT_OWNER);
    }

    /**
     * The editor's Test button. There is no persisted job and therefore no job principal, so the owner comes off the
     * security context instead -- {@code resolveJobPrincipal} is stubbed empty here so an answer of account 42 can only
     * have come from the connected user driving the editor. Without this branch the run reads and writes the vendor's
     * rows while an account is watching it.
     */
    @Test
    void testAnEditorTestRunScopesTheRowsToTheConnectedUserDrivingIt() throws Exception {
        givenSharedTableResolvedForWhoeverAsks();

        when(
            dataTableRowService.listRows(
                any(DataTableRef.class), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
                ArgumentMatchers.<List<RowSort>>any()))
                    .thenReturn(List.of());

        performInEditor(
            DataTableFindRecordsAction.of(
                dataTableService, dataTableRowService, editorOwnerResolverProvider(ACCOUNT_ID)),
            Map.of("table", "orders", "limit", 100, "offset", 0));

        assertThat(capturedListRowsDataTableRef().runOwner())
            .as("an editor test run must read the rows of the connected user driving it")
            .isEqualTo(ACCOUNT_OWNER);
    }

    private DataTableRef capturedListRowsDataTableRef() {
        ArgumentCaptor<DataTableRef> dataTableRefArgumentCaptor = ArgumentCaptor.forClass(DataTableRef.class);

        verify(dataTableRowService).listRows(
            dataTableRefArgumentCaptor.capture(), anyInt(), anyInt(), ArgumentMatchers.<List<RowFilter>>any(),
            ArgumentMatchers.<List<RowSort>>any());

        return dataTableRefArgumentCaptor.getValue();
    }

    /**
     * The vendor's shared {@code orders}, resolved the way {@code DataTableServiceImpl} resolves it: the registry row
     * carries no owner, so the ref gets no resource owner, and the run owner is whichever one the caller asked with.
     * That one line is what makes these tests about the action rather than about the stub.
     */
    private void givenSharedTableResolvedForWhoeverAsks() {
        when(
            dataTableService.fetchDataTableResolution(
                ArgumentMatchers.eq("orders"), ArgumentMatchers.eq(ENVIRONMENT_ID),
                ArgumentMatchers.eq(PlatformType.EMBEDDED), ArgumentMatchers.<Optional<Owner>>any()))
                    .thenAnswer(invocation -> {
                        Optional<Owner> runOwner = invocation.getArgument(3);

                        return Optional.of(
                            new DataTableResolution(
                                DATA_TABLE_ID,
                                new DataTableRef(
                                    "orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, runOwner.orElse(null))));
                    });

        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED))
            .thenReturn(
                List.of(
                    new DataTableInfo(
                        DATA_TABLE_ID, "orders", null, List.of(new ColumnSpec("title", ColumnType.STRING)),
                        Instant.now())));
    }

    private void givenResolution(DataTableRef dataTableRef, @Nullable Long ownerId) {
        Optional<Owner> owner = ownerId == null ? Optional.empty() : Optional.of(Owner.connectedUser(ownerId));

        when(dataTableService.fetchDataTableResolution("orders", ENVIRONMENT_ID, PlatformType.EMBEDDED, owner))
            .thenReturn(Optional.of(new DataTableResolution(DATA_TABLE_ID, dataTableRef)));
        when(dataTableService.listTables(ENVIRONMENT_ID, PlatformType.EMBEDDED))
            .thenReturn(
                List.of(
                    new DataTableInfo(
                        DATA_TABLE_ID, "orders", null, List.of(new ColumnSpec("title", ColumnType.STRING)),
                        Instant.now())));
    }

    private static void perform(ActionDefinition actionDefinition, Map<String, ?> inputParameters) throws Exception {
        perform(actionDefinition, inputParameters, actionContext());
    }

    private static void performInEditor(
        ActionDefinition actionDefinition, Map<String, ?> inputParameters) throws Exception {

        perform(actionDefinition, inputParameters, editorActionContext());
    }

    private static void perform(
        ActionDefinition actionDefinition, Map<String, ?> inputParameters,
        ActionContextAware actionContextAware) throws Exception {

        PerformFunction performFunction = (PerformFunction) actionDefinition.getPerform()
            .orElseThrow();

        performFunction.apply(parameters(inputParameters), parameters(Map.of()), actionContextAware);
    }

    /**
     * A run belonging to {@code ownerId}, or a vendor run when it is null. The action reads its owner through
     * {@code OwnerResolution}, which asks the resolver for the job principal, so that is what is stubbed here rather
     * than the account selector -- the selector is the vendor's way in, and a run that already belongs to an account is
     * not allowed to use it.
     */
    private static ObjectProvider<OwnerResolver> ownerResolverProvider(@Nullable Long ownerId) {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(anyLong(), any(PlatformType.class)))
            .thenReturn(ownerId == null ? Optional.empty() : Optional.of(Owner.connectedUser(ownerId)));

        @SuppressWarnings("unchecked")
        ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);

        return ownerResolverProvider;
    }

    /**
     * The editor form of {@link #ownerResolverProvider(Long)}: the security context answers and the job principal does
     * not, so an owner that reaches the ref can only have come from the connected user driving the Test button.
     */
    private static ObjectProvider<OwnerResolver> editorOwnerResolverProvider(long ownerId) {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(ownerId)));
        when(ownerResolver.resolveJobPrincipal(anyLong(), any(PlatformType.class))).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);

        return ownerResolverProvider;
    }

    private static ActionContextAware editorActionContext() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(true);
        when(actionContextAware.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return actionContextAware;
    }

    private static ActionContextAware actionContext() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(1L);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);
        when(actionContextAware.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return actionContextAware;
    }

    private static Parameters parameters(Map<String, ?> map) {
        Parameters parameters = mock(Parameters.class);

        when(parameters.getRequiredString(any())).thenAnswer(
            invocation -> String.valueOf(map.get(invocation.getArgument(0, String.class))));
        when(parameters.getLong(any())).thenAnswer(
            invocation -> {
                Object value = map.get(invocation.getArgument(0, String.class));

                return value == null ? null : ((Number) value).longValue();
            });
        when(parameters.getInteger(any(), anyInt())).thenAnswer(
            invocation -> {
                Object value = map.get(invocation.getArgument(0, String.class));

                return value == null ? invocation.getArgument(1) : ((Number) value).intValue();
            });
        when(parameters.getList(any(), any(com.bytechef.component.definition.TypeReference.class), any()))
            .thenAnswer(
                invocation -> {
                    Object value = map.get(invocation.getArgument(0, String.class));

                    return value == null ? invocation.getArgument(2) : value;
                });
        when(parameters.getRequiredMap(any(), any(Class.class))).thenAnswer(
            invocation -> map.get(invocation.getArgument(0, String.class)));

        return parameters;
    }
}
