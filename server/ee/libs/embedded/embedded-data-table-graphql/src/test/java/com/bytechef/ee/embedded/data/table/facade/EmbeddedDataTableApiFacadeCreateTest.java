/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The {@code isTenantAdmin()} gate on {@code createDataTable} is asserted once, in the canonical enumeration in
 * {@link EmbeddedDataTableApiFacadeTest#testEveryFacadeMethodIsGatedOnTenantAdmin()}, not here -- a second reflection
 * assertion in this file would just be a second place for that list to go stale.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedDataTableApiFacadeCreateTest {

    private static final long ACCOUNT_A_ID = 42L;
    private static final long ENVIRONMENT_ID = 0;

    @Mock
    private DataTableService dataTableService;

    @InjectMocks
    private EmbeddedDataTableApiFacadeImpl embeddedDataTableApiFacade;

    @Captor
    private ArgumentCaptor<Optional<Owner>> ownerCaptor;

    @Test
    void testCreateGoesIntoTheEmbeddedPool() {
        List<ColumnSpec> columnSpecs = List.of(new ColumnSpec("title", ColumnType.STRING));

        embeddedDataTableApiFacade.createDataTable(ENVIRONMENT_ID, "conversations", "d", columnSpecs, null);

        verify(dataTableService).createTable(
            "conversations", "d", columnSpecs, ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.empty());
    }

    /**
     * The branch the whole per-account feature exists for: a vendor creating a table that belongs to one of its
     * accounts rather than to itself. An ownerId that arrived as a connected user has to leave the facade as one, or
     * the account gets the vendor's shared table under a name it thinks is its own.
     */
    @Test
    void testCreateForAnAccountCarriesThatAccountsOwner() {
        List<ColumnSpec> columnSpecs = List.of(new ColumnSpec("title", ColumnType.STRING));

        embeddedDataTableApiFacade.createDataTable(
            ENVIRONMENT_ID, "conversations", "d", columnSpecs, ACCOUNT_A_ID);

        verify(dataTableService).createTable(
            "conversations", "d", columnSpecs, ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.of(Owner.connectedUser(ACCOUNT_A_ID)));
    }

    /**
     * The observable consequence of the branch above, stated rather than assumed: the owner the facade hands over is
     * the one the physical name is built from, so an owned create lands in {@code edt_0_42_connecteduser_conversations}
     * and not in the vendor's {@code edt_0_conversations}. Asserted here because the facade is where the
     * {@code ownerId} becomes an {@link Owner}; a facade that dropped it would still register a row, just the wrong
     * one.
     */
    @Test
    void testTheOwnerTheFacadePassesIsTheOneThePhysicalNameIsBuiltFrom() {
        embeddedDataTableApiFacade.createDataTable(
            ENVIRONMENT_ID, "conversations", "d", List.of(new ColumnSpec("title", ColumnType.STRING)), ACCOUNT_A_ID);

        verify(dataTableService).createTable(
            eq("conversations"), eq("d"), anyList(), eq(ENVIRONMENT_ID), eq(PlatformType.EMBEDDED),
            ownerCaptor.capture());

        Optional<Owner> capturedOwner = ownerCaptor.getValue();

        assertThat(capturedOwner)
            .as("an ownerId on the request must reach the service as an owner rather than be dropped")
            .isPresent();

        Owner owner = capturedOwner.orElseThrow();

        assertThat(owner.type()).isEqualTo(OwnerType.CONNECTED_USER);

        DataTableRef dataTableRef =
            new DataTableRef("conversations", ENVIRONMENT_ID, PlatformType.EMBEDDED, owner, owner);

        assertThat(dataTableRef.physicalName()).isEqualTo("edt_0_42_connecteduser_conversations");
    }
}
