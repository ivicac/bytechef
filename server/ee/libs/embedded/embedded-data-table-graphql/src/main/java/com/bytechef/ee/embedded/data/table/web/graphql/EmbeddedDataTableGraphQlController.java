/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.ee.embedded.data.table.facade.EmbeddedDataTableApiFacade;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * The embedded console's GraphQL entry point for data tables, scoped to the {@code EMBEDDED} pool. Authorization lives
 * on {@link EmbeddedDataTableApiFacade}, not here — every method there is gated {@code isTenantAdmin()}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnCoordinator
@SuppressFBWarnings("EI")
public class EmbeddedDataTableGraphQlController {

    private final EmbeddedDataTableApiFacade embeddedDataTableApiFacade;
    private final EnvironmentService environmentService;

    public EmbeddedDataTableGraphQlController(
        EmbeddedDataTableApiFacade embeddedDataTableApiFacade, EnvironmentService environmentService) {

        this.embeddedDataTableApiFacade = embeddedDataTableApiFacade;
        this.environmentService = environmentService;
    }

    @QueryMapping
    public List<EmbeddedDataTable> embeddedDataTables(@Argument Long environmentId) {
        Environment environment = environmentService.getEnvironment(environmentId);

        List<DataTableInfo> dataTableInfos = embeddedDataTableApiFacade.getDataTables(environment.ordinal());

        return dataTableInfos.stream()
            .map(
                EmbeddedDataTableGraphQlController::toEmbeddedDataTable)
            .toList();
    }

    @MutationMapping
    public boolean createEmbeddedDataTable(@Argument CreateEmbeddedDataTableInput input) {
        Environment environment = environmentService.getEnvironment(input.environmentId());

        List<ColumnSpec> columnSpecs = input.columns()
            .stream()
            .map(EmbeddedDataTableColumnInput::toSpec)
            .toList();

        embeddedDataTableApiFacade.createDataTable(
            environment.ordinal(), input.name(), input.description(), columnSpecs);

        return true;
    }

    @SuppressFBWarnings("EI")
    public record CreateEmbeddedDataTableInput(Long environmentId, String name, @Nullable String description,
        List<EmbeddedDataTableColumnInput> columns) {
    }

    public record EmbeddedDataTableColumnInput(String name, ColumnType type) {
        public ColumnSpec toSpec() {
            return new ColumnSpec(name, type);
        }
    }

    /**
     * Deliberately the same shape as the automation {@code DataTable}, so one set of client components can render both
     * surfaces. The column id is base64 for the same reason it is there.
     */
    private static EmbeddedDataTable toEmbeddedDataTable(DataTableInfo dataTableInfo) {
        List<EmbeddedDataTableColumn> columns = dataTableInfo.columns()
            .stream()
            .map(
                columnSpec -> new EmbeddedDataTableColumn(
                    EncodingUtils.urlEncodeBase64ToString(columnSpec.name()), columnSpec.name(), columnSpec.type()))
            .toList();

        Instant lastModifiedDate = dataTableInfo.lastModifiedDate();

        return new EmbeddedDataTable(
            dataTableInfo.id(), dataTableInfo.baseName(), dataTableInfo.description(), columns,
            lastModifiedDate == null ? null : lastModifiedDate.toEpochMilli());
    }

    public record EmbeddedDataTable(Long id, String baseName, String description, List<EmbeddedDataTableColumn> columns,
        @Nullable Long lastModifiedDate) {
    }

    public record EmbeddedDataTableColumn(String id, String name, ColumnType type) {
    }
}
