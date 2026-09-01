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

import static com.bytechef.component.datatable.constant.DataTableConstants.ACCOUNT_ID;
import static com.bytechef.component.datatable.constant.DataTableConstants.TABLE;
import static com.bytechef.component.datatable.constant.DataTableConstants.VALUES;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.date;
import static com.bytechef.component.definition.ComponentDsl.dateTime;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.definition.BaseOutputDefinition.OutputResponse;
import static com.bytechef.platform.configuration.domain.Environment.DEVELOPMENT;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentDsl.ModifiableIntegerProperty;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Property;
import com.bytechef.component.definition.TriggerDefinition;
import com.bytechef.definition.BaseProperty.BaseValueProperty;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.TriggerContextAware;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Utility to construct output schemas for Data Table actions using table metadata.
 *
 * @author Ivica Cardic
 */
public final class DataTableUtils {

    private DataTableUtils() {
    }

    /**
     * Returns an OptionsFunction for action table selection dropdowns, narrowed to the tables the calling owner may
     * see. Without the owner the dropdown lists every table in the tenant, which is how a connected user learns the
     * names of other accounts' tables.
     *
     * <p>
     * Through {@link #effectiveOwner(Optional, Long)}, like every action's own resolution, because the account selector
     * is part of which table the step will act on: a vendor step that names an account was offered the shared tables
     * and, once one was picked, described with the shared table's columns while acting on the account's. The dropdown
     * declares {@code optionsLookupDependsOn(ACCOUNT_ID)} at each call site so the editor re-asks when the account
     * changes.
     *
     * @param dataTableService      the data table service
     * @param ownerResolverProvider resolves the owner this invocation acts for
     * @return an OptionsFunction that provides table options
     */
    public static ActionDefinition.OptionsFunction<String> getActionTableOptions(
        DataTableService dataTableService, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (inputParameters, connectionParameters, dependencyPaths, searchText, context) -> getTableOptions(
            searchText, dataTableService,
            effectiveOwner(
                OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider),
                inputParameters.getLong(ACCOUNT_ID)));
    }

    /**
     * Trigger form of {@link #getActionTableOptions}. {@code TriggerContextAware} has its own {@code OwnerResolution}
     * overload -- it carries no editor flag, so it reads the job principal when there is one and the security context
     * otherwise.
     *
     * <p>
     * Through {@link #effectiveOwner(Optional, Long)} as well, which today is the identity: no trigger declares
     * {@link #accountProperty()}, so there is never a named account to reconcile. Written this way so the two options
     * paths cannot drift -- a trigger that gains the selector narrows its dropdown without a second edit here, and
     * neither path can quietly become the one that skips the rule.
     *
     * @param dataTableService      the data table service
     * @param ownerResolverProvider resolves the owner this invocation acts for
     * @return an OptionsFunction that provides table options
     */
    public static TriggerDefinition.OptionsFunction<String> getTriggerTableOptions(
        DataTableService dataTableService, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        return (inputParameters, connectionParameters, dependencyPaths, searchText, context) -> getTableOptions(
            searchText, dataTableService,
            effectiveOwner(
                OwnerResolution.resolve((TriggerContextAware) context, ownerResolverProvider),
                inputParameters.getLong(ACCOUNT_ID)));
    }

    /**
     * The pool a run may see, derived from WHO the run is for rather than from where the workflow was authored. The
     * embedded bridge dispatches an automation workflow under a connected user, so PlatformType answers a different
     * question than this one and must not be used here.
     */
    public static List<PlatformType> poolFor(Optional<Owner> owner) {
        return owner.isPresent()
            ? List.of(PlatformType.EMBEDDED)
            : List.of(PlatformType.AUTOMATION, PlatformType.EMBEDDED);
    }

    /**
     * The optional account selector every row action carries. Declared here so the six actions cannot drift apart on
     * its name, label or wording.
     */
    public static ModifiableIntegerProperty accountProperty() {
        return integer(ACCOUNT_ID)
            .label("Account")
            .description(
                "The connected user whose table this step acts on. Leave empty to act on the shared table. A step " +
                    "running for an account already uses that account's table, and ignores this.")
            .required(false);
    }

    /**
     * A vendor run may act for a named account. A run that already belongs to one may not name a different account --
     * without this, an account's own workflow names another and reads its table.
     *
     * <p>
     * Every caller feeds the result into a single local, because the same value has to drive three things that must
     * agree: {@link #poolFor(Optional)}, which registry row resolution picks, and -- since resolution puts this owner
     * into the ref as its run owner -- which rows inside the resolved table the step may read and write. Deriving them
     * from two owner values would let them disagree, and the disagreement worth fearing is silent: a step reaching the
     * right table and the wrong account's rows in it.
     *
     * <p>
     * This is also the only point in the component where an owner can be NAMED rather than derived, and the rule above
     * is what keeps that harmless: the selector is the vendor's, and a run that already belongs to an account cannot
     * use it. A step never names the owner of its own rows.
     */
    public static Optional<Owner> effectiveOwner(Optional<Owner> resolvedOwner, @Nullable Long namedAccountId) {
        if (resolvedOwner.isPresent()) {
            return resolvedOwner;
        }

        return namedAccountId == null ? Optional.empty() : Optional.of(Owner.connectedUser(namedAccountId));
    }

    /**
     * A named table together with the physical table it resolved to and that table's column metadata. The three travel
     * together because every caller that needs one needs the others, and resolving them separately meant scanning the
     * same pool twice -- and, worse, risked the metadata describing a different table from the one the rows come out
     * of.
     *
     * <p>
     * The {@code dataTableRef} answers both ownership questions at once, and every row-service call in the component
     * passes it whole: its resource owner is the table's, and its run owner is the account this step acts for, which is
     * what scopes the rows inside a table shared by every account. Nothing here re-derives either -- an action that
     * spelled a base name instead would leave the row service with no way to know who was asking.
     */
    public record ResolvedDataTable(DataTableRef dataTableRef, DataTableInfo dataTableInfo) {

        public PlatformType platformType() {
            return dataTableRef.platformType();
        }
    }

    /**
     * The table a run names, together with the single pool that holds it, resolved across every pool the run may read.
     * Resolution rather than a constant because {@link #poolFor(Optional)} admits both pools to a vendor run, while a
     * row operation names exactly one table and therefore needs exactly one pool.
     *
     * <p>
     * Fails closed in both directions. A table no pool of this run holds is rejected rather than reaching a pool the
     * run may not read; a name held by more than one pool is rejected rather than picked, because the same base name is
     * legal in both pools after the split and the picker cannot tell them apart -- an option's value is the bare base
     * name, so two same-named tables render as two identical choices. Guessing here would clear or overwrite the wrong
     * table. Naming an account narrows the run to the EMBEDDED pool and resolves the ambiguity.
     */
    public static ResolvedDataTable resolveDataTable(
        DataTableService dataTableService, String baseName, long environmentId, Optional<Owner> owner) {

        return resolveDataTable(dataTableService, baseName, environmentId, poolFor(owner), owner);
    }

    private static ResolvedDataTable resolveDataTable(
        DataTableService dataTableService, String baseName, long environmentId, List<PlatformType> platformTypes,
        Optional<Owner> owner) {

        ResolvedDataTable resolvedDataTable = findDataTable(
            dataTableService, baseName, environmentId, platformTypes, owner);

        if (resolvedDataTable == null) {
            throw new IllegalArgumentException(
                "Data table '" + baseName + "' was not found in any data table pool this run may read");
        }

        return resolvedDataTable;
    }

    @Nullable
    private static ResolvedDataTable findDataTable(
        DataTableService dataTableService, String baseName, long environmentId, List<PlatformType> platformTypes,
        Optional<Owner> owner) {

        List<ResolvedDataTable> resolvedDataTables = new ArrayList<>();

        for (PlatformType platformType : platformTypes) {
            dataTableService.fetchDataTableResolution(baseName, environmentId, platformType, owner)
                .flatMap(
                    dataTableResolution -> dataTableInfoOf(
                        dataTableService, dataTableResolution, environmentId, platformType, owner))
                .ifPresent(resolvedDataTables::add);
        }

        if (resolvedDataTables.size() > 1) {
            List<PlatformType> holdingPlatformTypes = resolvedDataTables.stream()
                .map(ResolvedDataTable::platformType)
                .toList();

            throw new IllegalArgumentException(
                "Data table '" + baseName + "' exists in more than one data table pool this run may read "
                    + holdingPlatformTypes + "; name an account so the run resolves to a single pool");
        }

        return resolvedDataTables.isEmpty() ? null : resolvedDataTables.getFirst();
    }

    /**
     * {@link DataTableService#fetchDataTableResolution} settles WHICH registry row wins under
     * owned-wins-shared-fallback and which physical table that row occupies, but carries no column metadata.
     * {@link DataTableService#listTables} is still the only source of that, so this recovers the matching
     * {@link DataTableInfo} from the same visibility-filtered list the caller's owner already sees -- keyed by registry
     * id rather than by name, so a pool holding both an owned and a shared table for the same base name still recovers
     * the one resolution picked.
     *
     * <p>
     * The {@link DataTableRef} is carried through untouched from that same resolution. Nothing downstream re-derives
     * it, which is what stops the rows a step reads coming from a different table than the columns it was described
     * with.
     */
    private static Optional<ResolvedDataTable> dataTableInfoOf(
        DataTableService dataTableService, DataTableResolution dataTableResolution, long environmentId,
        PlatformType platformType, Optional<Owner> owner) {

        return dataTableService.listTables(environmentId, platformType, owner)
            .stream()
            .filter(dataTableInfo -> Objects.equals(dataTableInfo.id(), dataTableResolution.dataTableId()))
            .findFirst()
            .map(dataTableInfo -> new ResolvedDataTable(dataTableResolution.dataTableRef(), dataTableInfo));
    }

    public static List<Option<String>> getTableOptions(
        String searchText, DataTableService dataTableService, Optional<Owner> owner) {

        List<DataTableInfo> dataTableInfos = poolFor(owner).stream()
            .flatMap(platformType -> dataTableService.listTables(DEVELOPMENT.ordinal(), platformType, owner)
                .stream())
            .toList();

        return dataTableInfos.stream()
            .filter(
                dataTableInfo -> searchText == null || dataTableInfo.baseName()
                    .toLowerCase()
                    .contains(searchText.toLowerCase()))
            .<Option<String>>map(
                dataTableInfo -> option(dataTableInfo.baseName(), dataTableInfo.baseName(),
                    dataTableInfo.description()))
            .toList();
    }

    /**
     * Fetches a DataTableInfo by base name and environment ID, narrowed to the pools this run may see. Without the
     * owner this resolved a table by name with no scoping at all, which is how an account naming {@code invoices}
     * learned the column structure of the vendor's table of that name.
     *
     * @param dataTableService the data table service
     * @param baseName         the table base name
     * @param environmentId    the environment ID
     * @param owner            the owner this invocation acts for
     * @return the DataTableInfo if found, null otherwise
     * @throws IllegalArgumentException if more than one pool this run may read holds the name -- see
     *                                  {@link #resolveDataTable}
     */
    @Nullable
    public static DataTableInfo getDataTableInfo(
        DataTableService dataTableService, String baseName, long environmentId, Optional<Owner> owner) {

        ResolvedDataTable resolvedDataTable = findDataTable(
            dataTableService, baseName, environmentId, poolFor(owner), owner);

        return resolvedDataTable == null ? null : resolvedDataTable.dataTableInfo();
    }

    /**
     * Registers a trigger's webhook against the table the run actually resolves for the name it gave.
     *
     * <p>
     * The registration binds to the resolved table, not to the base name -- which after per-account tables names as
     * many tables as there are accounts holding one. Binding by name would register the vendor's {@code orders} for an
     * account that owns its own, so the account's rows would be POSTed to the vendor's URL and its own registration
     * would never fire.
     *
     * @param dataTableService        the data table service
     * @param dataTableWebhookService the webhook registry
     * @param baseName                the table the trigger names
     * @param webhookUrl              the URL to notify
     * @param type                    the row event to subscribe to
     * @param environmentId           the environment the trigger runs in
     * @param owner                   the owner this registration acts for
     * @return the registered webhook id
     */
    public static long registerWebhook(
        DataTableService dataTableService, DataTableWebhookService dataTableWebhookService, String baseName,
        String webhookUrl, DataTableWebhookType type, long environmentId, Optional<Owner> owner) {

        ResolvedDataTable resolvedDataTable = resolveDataTable(dataTableService, baseName, environmentId, owner);

        return dataTableWebhookService.addWebhook(resolvedDataTable.dataTableRef(), webhookUrl, type);
    }

    /**
     * Creates a OutputResponse for a data table trigger, including schema and sample data from the first row.
     *
     * <p>
     * The sample row comes from whichever table {@code owner} resolves, read as that same owner: the ref resolution
     * returns carries the owner in both positions, so on an account's own table the row is one of its own and on the
     * vendor's shared table it is one of the account's or an unowned one. Resolving the table is no longer the whole
     * answer -- a shared table holds every account's rows, and a sample built without the run owner would show one
     * account a row of another's.
     *
     * @param dataTableRowService the data table row service
     * @param dataTableService    the data table service
     * @param baseName            the table base name
     * @param owner               the owner this trigger acts for
     * @return an OutputResponse with schema and optional sample data
     */
    public static OutputResponse createTriggerOutputResponse(
        DataTableRowService dataTableRowService, DataTableService dataTableService, String baseName,
        Optional<Owner> owner) {

        ResolvedDataTable resolvedDataTable =
            resolveDataTable(dataTableService, baseName, DEVELOPMENT.ordinal(), owner);

        DataTableInfo dataTableInfo = resolvedDataTable.dataTableInfo();

        BaseValueProperty<?> rowSchema = rowObjectSchema(dataTableInfo);

        List<DataTableRow> rows = dataTableRowService.listRows(resolvedDataTable.dataTableRef(), 1, 0);

        if (rows.isEmpty()) {
            return OutputResponse.of(rowSchema);
        }

        DataTableRow firstRow = rows.getFirst();

        Map<String, Object> sampleOutput = createSampleOutput(dataTableInfo, firstRow.id(), firstRow.values());

        return OutputResponse.of(rowSchema, sampleOutput);
    }

    /**
     * Takes the already-resolved table rather than looking it up, so that an output refresh scans the catalog once
     * instead of once here, once for the pool and once again in {@link #createSampleOutput}.
     */
    public static BaseValueProperty<?> rowObjectSchema(@Nullable DataTableInfo dataTableInfo) {
        List<Property.ValueProperty<?>> properties = new ArrayList<>();

        properties.add(integer("id").label("ID"));

        if (dataTableInfo != null && dataTableInfo.columns() != null) {
            for (ColumnSpec columnSpec : dataTableInfo.columns()) {
                properties.add(mapColumn(columnSpec));
            }
        }

        return object().properties(properties.toArray(Property.ValueProperty[]::new));
    }

    /**
     * Creates a sample output map from a row's values, filling in sample values for null columns based on their types.
     * Takes the already-resolved table for the same reason {@link #rowObjectSchema} does.
     *
     * @param dataTableInfo the resolved table, or null when the run could not see one
     * @param rowId         the row id
     * @param rowValues     the row values map (may contain null values)
     * @return a map with id and all column values, with sample values for null entries
     */
    public static Map<String, Object> createSampleOutput(
        @Nullable DataTableInfo dataTableInfo, long rowId, Map<String, Object> rowValues) {

        Map<String, Object> sampleOutput = new HashMap<>();

        sampleOutput.put("id", rowId);

        if (dataTableInfo != null && dataTableInfo.columns() != null) {
            for (ColumnSpec columnSpec : dataTableInfo.columns()) {
                String columnName = columnSpec.name();
                Object value = rowValues.get(columnName);

                sampleOutput.put(
                    columnName, Objects.requireNonNullElseGet(value, () -> getSampleValue(columnSpec.type())));
            }
        }

        return sampleOutput;
    }

    /**
     * Creates a PropertiesFunction for dynamic properties lookup based on table columns, narrowed to the tables the
     * calling owner may see. Without the owner the column structure of any table in the tenant is readable by name.
     *
     * <p>
     * Through {@link #effectiveOwner(Optional, Long)} for the same reason the table dropdown is: these ARE the columns
     * the step will write, and a vendor step naming an account was offered the shared table's. Each call site declares
     * {@code propertiesLookupDependsOn(TABLE, ACCOUNT_ID)} so the editor re-asks when either changes.
     *
     * @param dataTableService      the data table service
     * @param ownerResolverProvider resolves the owner this invocation acts for
     * @return a PropertiesFunction that returns properties based on the selected table
     */
    public static ActionDefinition.PropertiesFunction createDynamicProperties(
        DataTableService dataTableService, ObjectProvider<OwnerResolver> ownerResolverProvider, boolean singleRecord) {

        return (inputParameters, connectionParameters, dependencyPaths, context) -> {
            String baseName = inputParameters.getString(TABLE);

            if (baseName == null || baseName.isBlank()) {
                return List.of();
            }

            DataTableInfo dataTableInfo = getDataTableInfo(
                dataTableService, baseName, DEVELOPMENT.ordinal(),
                effectiveOwner(
                    OwnerResolution.resolve((ActionContextAware) context, ownerResolverProvider),
                    inputParameters.getLong(ACCOUNT_ID)));

            if (dataTableInfo == null || dataTableInfo.columns() == null) {
                return List.of();
            }

            List<Property.ValueProperty<?>> columnProperties = new ArrayList<>();

            for (ColumnSpec columnSpec : dataTableInfo.columns()) {
                columnProperties.add(mapColumn(columnSpec));
            }

            if (singleRecord) {
                return columnProperties;
            }

            var valuesObject = object(VALUES)
                .label("Values")
                .properties(columnProperties)
                .required(true);

            var recordsArray = array(VALUES)
                .label("Records")
                .items(valuesObject)
                .required(true);

            return List.of(recordsArray);
        };
    }

    private static Object getSampleValue(ColumnType columnType) {
        return switch (columnType) {
            case STRING -> "sample value";
            case NUMBER -> 1.0;
            case INTEGER -> 1;
            case DATE -> LocalDate.now();
            case DATE_TIME -> LocalDateTime.now();
            case BOOLEAN -> false;
        };
    }

    private static Property.ValueProperty<?> mapColumn(ColumnSpec columnSpec) {
        String name = columnSpec.name();
        ColumnType type = columnSpec.type();

        return switch (type) {
            case STRING -> string(name);
            case NUMBER -> number(name);
            case INTEGER -> integer(name);
            case DATE -> date(name);
            case DATE_TIME -> dateTime(name);
            case BOOLEAN -> bool(name);
        };
    }
}
