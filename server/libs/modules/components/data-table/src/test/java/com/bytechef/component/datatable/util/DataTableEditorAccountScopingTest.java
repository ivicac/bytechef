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

import static com.bytechef.component.datatable.constant.DataTableConstants.TABLE;
import static com.bytechef.platform.configuration.domain.Environment.DEVELOPMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.datatable.action.DataTableCreateRecordsAction;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.DynamicOptionsProperty;
import com.bytechef.component.definition.OptionsDataSource;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The editor's own two lookups -- which tables the dropdown offers, and which columns the dynamic properties describe
 * -- used to resolve their owner straight from {@code OwnerResolution}, skipping {@code effectiveOwner}. A vendor step
 * that named an account was therefore offered the SHARED tables and described with the shared table's columns, while
 * the run itself acted on the account's: the editor and the execution disagreed about which table the step was for.
 *
 * <p>
 * These are the behavioural half of {@code DataTableComponentResolutionGuardTest}, which can only see that the call is
 * written.
 *
 * @author Ivica Cardic
 */
class DataTableEditorAccountScopingTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 99L;
    private static final long ACCOUNT_TABLE_ID = 7L;
    private static final long SHARED_TABLE_ID = 3L;

    private final DataTableService dataTableService = mock(DataTableService.class);

    /**
     * Every account sees the same tables with the same columns, so re-asking either lookup when the account changes
     * costs a round trip and can only return the same answer. The sample row is the exception and is asserted below.
     */
    @Test
    void testTableAndColumnLookupsDoNotDependOnTheAccount() {
        ActionDefinition actionDefinition = DataTableCreateRecordsAction.of(
            dataTableService, mock(DataTableRowService.class), ownerResolverProvider(null));

        List<? extends Property> properties = actionDefinition.getProperties();

        Property tableProperty = properties.stream()
            .filter(property -> TABLE.equals(property.getName()))
            .findFirst()
            .orElseThrow();

        OptionsDataSource<?> optionsDataSource = ((DynamicOptionsProperty<?>) tableProperty)
            .getOptionsDataSource()
            .orElseThrow();

        assertThat(optionsDataSource.getOptionsLookupDependsOn()
            .orElse(List.of()))
                .as("a table list is the same for every account, so there is nothing to re-ask on")
                .isEmpty();
    }

    /**
     * The account selector is the vendor's. A run that already belongs to an account may not name another, or an
     * account's own workflow would read another account's rows out of the shared table. Once per-account tables are
     * gone this guard is the whole of the isolation story for a vendor workflow, so it is asserted on the owner that
     * reaches resolution rather than on which tables come back.
     */
    @Test
    void testARunBelongingToAnAccountCannotNameAnother() {
        assertThat(DataTableUtils.effectiveOwner(Optional.of(Owner.connectedUser(ACCOUNT_ID)), OTHER_ACCOUNT_ID))
            .as("a run that already belongs to an account keeps its own owner")
            .isEqualTo(Optional.of(Owner.connectedUser(ACCOUNT_ID)));
    }

    @Test
    void testAVendorRunMayActForANamedAccount() {
        assertThat(DataTableUtils.effectiveOwner(Optional.empty(), ACCOUNT_ID))
            .as("a vendor run naming an account acts for it")
            .isEqualTo(Optional.of(Owner.connectedUser(ACCOUNT_ID)));
    }

    @Test
    void testTheDynamicPropertiesDescribeTheSharedColumnsWhenNoAccountIsNamed() throws Exception {
        givenTables();

        ActionDefinition.PropertiesFunction propertiesFunction =
            DataTableUtils.createDynamicProperties(dataTableService, ownerResolverProvider(null), true);

        List<? extends Property.ValueProperty<?>> properties = propertiesFunction.apply(
            parameters(Map.of("table", "orders")), parameters(Map.of()), Map.of(), actionContext());

        assertThat(properties)
            .extracting(Property::getName)
            .containsExactly("sharedColumn");
    }

    /**
     * A shared {@code orders} and account 42's own {@code orders}, each with a column the other does not have, so a
     * lookup that reached the wrong one cannot produce the right answer by accident.
     */
    private void givenTables() {
        Owner accountOwner = Owner.connectedUser(ACCOUNT_ID);
        Optional<Owner> account = Optional.of(accountOwner);

        DataTableRef accountDataTableRef =
            new DataTableRef("orders", DEVELOPMENT.ordinal(), PlatformType.EMBEDDED, accountOwner);
        DataTableRef sharedDataTableRef =
            DataTableRef.unowned("orders", DEVELOPMENT.ordinal(), PlatformType.EMBEDDED);

        when(dataTableService.listTables(DEVELOPMENT.ordinal(), PlatformType.EMBEDDED))
            .thenReturn(
                List.of(
                    new DataTableInfo(
                        ACCOUNT_TABLE_ID, "account_orders", null,
                        List.of(new ColumnSpec("accountColumn", ColumnType.STRING)), Instant.now()),
                    new DataTableInfo(
                        SHARED_TABLE_ID, "shared_orders", null,
                        List.of(new ColumnSpec("sharedColumn", ColumnType.STRING)), Instant.now())));

        when(dataTableService.fetchDataTableResolution("orders", DEVELOPMENT.ordinal(), PlatformType.EMBEDDED, account))
            .thenReturn(Optional.of(new DataTableResolution(ACCOUNT_TABLE_ID, accountDataTableRef)));
        when(
            dataTableService.fetchDataTableResolution(
                "orders", DEVELOPMENT.ordinal(), PlatformType.EMBEDDED, Optional.empty()))
                    .thenReturn(Optional.of(new DataTableResolution(SHARED_TABLE_ID, sharedDataTableRef)));
    }

    private static ObjectProvider<OwnerResolver> ownerResolverProvider(@Nullable Long ownerId) {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(anyLong(), any(PlatformType.class)))
            .thenReturn(ownerId == null ? Optional.empty() : Optional.of(Owner.connectedUser(ownerId)));

        @SuppressWarnings("unchecked")
        ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);

        return ownerResolverProvider;
    }

    private static ActionContextAware actionContext() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(1L);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);
        when(actionContextAware.getEnvironmentId()).thenReturn((long) DEVELOPMENT.ordinal());

        return actionContextAware;
    }

    private static Parameters parameters(Map<String, ?> map) {
        Parameters parameters = mock(Parameters.class);

        when(parameters.getString(any())).thenAnswer(
            invocation -> {
                Object value = map.get(invocation.getArgument(0, String.class));

                return value == null ? null : String.valueOf(value);
            });
        when(parameters.getLong(any())).thenAnswer(
            invocation -> {
                Object value = map.get(invocation.getArgument(0, String.class));

                return value == null ? null : ((Number) value).longValue();
            });

        return parameters;
    }
}
