/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.facade;

import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import java.util.List;

/**
 * The embedded console's data table API: every call is scoped to the {@code EMBEDDED} pool, so the automation pool's
 * tables are unreachable through it whatever a caller asks for. This is the HTTP surface and it owns authorization:
 * every method is gated {@code isTenantAdmin()} on the implementation, never on the controller, because a controller
 * wired to an unguarded facade compiles fine and silently drops the check.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface EmbeddedDataTableApiFacade {

    /**
     * @param environmentId the environment to list
     */
    List<DataTableInfo> getDataTables(long environmentId);

    /**
     * Creates a new data table in the embedded pool.
     *
     * @param environmentId the environment to create the table in
     * @param name          the table's base name, without any pool prefix
     * @param description   the table's description
     * @param columnSpecs   the table's columns
     */
    void createDataTable(long environmentId, String name, String description, List<ColumnSpec> columnSpecs);
}
