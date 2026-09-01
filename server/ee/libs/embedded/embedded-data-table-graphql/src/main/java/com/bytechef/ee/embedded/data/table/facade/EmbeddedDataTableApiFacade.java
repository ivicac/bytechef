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
import org.jspecify.annotations.Nullable;

/**
 * The embedded console's view of data table ownership. This is the HTTP surface and it owns authorization: every method
 * is gated {@code isTenantAdmin()} on the implementation, never on the controller, because a controller wired to an
 * unguarded facade compiles fine and silently drops the check.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface EmbeddedDataTableApiFacade {

    /**
     * @param environmentId the environment to list
     * @param ownerId       the connected user to filter to, or null for every table in the tenant
     */
    List<DataTableInfo> getDataTables(long environmentId, @Nullable Long ownerId);

    /**
     * @param dataTableId the data table to assign
     * @param ownerId     the connected user to assign it to, or null to return it to the vendor
     */
    void assignDataTableOwner(long dataTableId, @Nullable Long ownerId);

    /**
     * Creates a new data table in the embedded pool, either the vendor's own or one connected account's.
     *
     * <p>
     * An account's table is a physical table of its own, which is what lets two accounts each have an {@code orders}
     * and never see each other's rows. It is also how a per-account override is created: the vendor's workflow keeps
     * naming {@code orders}, and a run belonging to that account resolves its copy instead of the shared one.
     * {@link #assignDataTableOwner} cannot produce this -- it reassigns a table without giving it a table of its own.
     *
     * @param environmentId the environment to create the table in
     * @param name          the table's base name, without any pool prefix
     * @param description   the table's description
     * @param columnSpecs   the table's columns
     * @param ownerId       the connected user the table belongs to, or null to create it shared with every account
     */
    void createDataTable(
        long environmentId, String name, String description, List<ColumnSpec> columnSpecs, @Nullable Long ownerId);
}
