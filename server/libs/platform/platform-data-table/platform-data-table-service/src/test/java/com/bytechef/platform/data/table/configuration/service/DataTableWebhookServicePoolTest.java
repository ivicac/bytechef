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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhook;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.repository.DataTableWebhookRepository;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService.Webhook;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The webhook registry used to resolve its table by base name. That was survivable while a base name identified one
 * table; it stopped being so the moment {@code invoices} could exist in both pools at once. A name-keyed registry binds
 * both pools' registrations to whichever row it happened to find, so a row written into one pool's table is POSTed to
 * the other pool's registered URL.
 *
 * <p>
 * Keyed on the resolved table instead, registration and delivery go through the same lookup and therefore meet: the
 * pool is in the ref, and so is the run owner that separates two accounts inside one table.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class DataTableWebhookServicePoolTest {

    private static final long ACCOUNT_A_ID = 4201L;
    private static final long AUTOMATION_TABLE_ID = 1L;
    private static final long ENVIRONMENT_ID = 0;
    private static final long VENDOR_TABLE_ID = 2L;
    private static final Owner ACCOUNT_A_OWNER = Owner.connectedUser(ACCOUNT_A_ID);
    private static final Owner ACCOUNT_B_OWNER = Owner.connectedUser(4202L);

    private static final DataTableRef AUTOMATION_TABLE =
        DataTableRef.unowned("invoices", ENVIRONMENT_ID, PlatformType.AUTOMATION);
    private static final DataTableRef VENDOR_TABLE =
        DataTableRef.unowned("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED);

    /**
     * The one EMBEDDED table, addressed by a run acting for account A. Same registry row as {@link #VENDOR_TABLE},
     * differing only in the run owner -- which is therefore the only thing that can tell their registrations apart.
     */
    private static final DataTableRef EMBEDDED_TABLE_AS_ACCOUNT_A =
        new DataTableRef("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT_A_OWNER);

    @Mock
    private DataTableService dataTableService;

    @Mock
    private DataTableWebhookRepository dataTableWebhookRepository;

    @InjectMocks
    private DataTableWebhookServiceImpl dataTableWebhookService;

    @Captor
    private ArgumentCaptor<DataTableRef> dataTableRefCaptor;

    @Captor
    private ArgumentCaptor<DataTableWebhook> dataTableWebhookCaptor;

    @Test
    void testAddingAWebhookBindsToTheResolvedTableAndNotTheName() {
        givenTheNameExistsInBothPools();
        givenTheRepositoryAssignsIds();

        long webhookId = dataTableWebhookService.addWebhook(
            EMBEDDED_TABLE_AS_ACCOUNT_A, "https://example.test/a", DataTableWebhookType.RECORD_CREATED);

        assertThat(webhookId).isEqualTo(9L);

        verify(dataTableWebhookRepository).save(dataTableWebhookCaptor.capture());

        DataTableWebhook savedDataTableWebhook = dataTableWebhookCaptor.getValue();

        assertThat(savedDataTableWebhook.getDataTableId())
            .as("the registration must bind to the embedded table, not the automation one of the same name")
            .isEqualTo(VENDOR_TABLE_ID);

        assertThat(resolvedDataTableRef())
            .as("the registration must be resolved through the ref it was given, run owner and pool intact")
            .isEqualTo(EMBEDDED_TABLE_AS_ACCOUNT_A);
    }

    /**
     * The registration owner comes off the ref's RUN owner and nowhere else. On a shared table every account's ref has
     * the same null resource owner, so a registration stamped from that one would be indistinguishable between accounts
     * -- which is the fan-out this column exists to stop.
     */
    @Test
    void testARegistrationIsStampedWithTheRefsRunOwner() {
        givenTheNameExistsInBothPools();
        givenTheRepositoryAssignsIds();

        dataTableWebhookService.addWebhook(
            EMBEDDED_TABLE_AS_ACCOUNT_A, "https://example.test/a", DataTableWebhookType.RECORD_CREATED);

        verify(dataTableWebhookRepository).save(dataTableWebhookCaptor.capture());

        DataTableWebhook savedDataTableWebhook = dataTableWebhookCaptor.getValue();

        assertThat(savedDataTableWebhook.getOwner())
            .as("a registration made on the shared table by account A belongs to account A")
            .isEqualTo(ACCOUNT_A_OWNER);
    }

    @Test
    void testAVendorRegistrationCarriesNoOwner() {
        givenTheNameExistsInBothPools();
        givenTheRepositoryAssignsIds();

        dataTableWebhookService.addWebhook(
            VENDOR_TABLE, "https://example.test/vendor", DataTableWebhookType.RECORD_CREATED);

        verify(dataTableWebhookRepository).save(dataTableWebhookCaptor.capture());

        DataTableWebhook savedDataTableWebhook = dataTableWebhookCaptor.getValue();

        assertThat(savedDataTableWebhook.getOwner()).isNull();
    }

    /**
     * Three registrations on one shared table, which the registry row cannot tell apart: the run owner on the ref is
     * all that is left. A lookup for a row written by account A returns account A's registration alone -- not account
     * B's, and not the vendor's, which may not read an account's row.
     */
    @Test
    void testASharedTableLookupReturnsOnlyTheAskingAccountsRegistrations() {
        givenTheNameExistsInBothPools();

        when(dataTableWebhookRepository.findByDataTableIdAndEnvironment(
            VENDOR_TABLE_ID, Environment.DEVELOPMENT.ordinal()))
                .thenReturn(
                    List.of(
                        dataTableWebhook(9L, VENDOR_TABLE_ID, "https://example.test/a", ACCOUNT_A_OWNER),
                        dataTableWebhook(10L, VENDOR_TABLE_ID, "https://example.test/b", ACCOUNT_B_OWNER),
                        dataTableWebhook(11L, VENDOR_TABLE_ID, "https://example.test/vendor", null)));

        List<Webhook> webhooks = dataTableWebhookService.listWebhooks(EMBEDDED_TABLE_AS_ACCOUNT_A);

        assertThat(webhooks)
            .extracting(Webhook::url)
            .as("account B's and the vendor's registrations hang off the same registry row and are not the answer")
            .containsExactly("https://example.test/a");
    }

    @Test
    void testAVendorRegistrationBindsToTheSharedTable() {
        givenTheNameExistsInBothPools();
        givenTheRepositoryAssignsIds();

        dataTableWebhookService.addWebhook(
            VENDOR_TABLE, "https://example.test/vendor", DataTableWebhookType.RECORD_CREATED);

        verify(dataTableWebhookRepository).save(dataTableWebhookCaptor.capture());

        assertThat(dataTableWebhookCaptor.getValue()
            .getDataTableId()).isEqualTo(VENDOR_TABLE_ID);
    }

    /**
     * The delivery half of the same regression, from an account's ref rather than the vendor's: carrying a run owner
     * must not cost the ref its pool. The registry row is found by the pool, and the owner then narrows the
     * registrations within it.
     */
    @Test
    void testListingWebhooksForAnAccountNeverReachesTheOtherPoolsRegistrations() {
        givenTheNameExistsInBothPools();

        when(dataTableWebhookRepository.findByDataTableIdAndEnvironment(
            VENDOR_TABLE_ID, Environment.DEVELOPMENT.ordinal()))
                .thenReturn(
                    List.of(dataTableWebhook(9L, VENDOR_TABLE_ID, "https://example.test/a", ACCOUNT_A_OWNER)));

        List<Webhook> webhooks = dataTableWebhookService.listWebhooks(EMBEDDED_TABLE_AS_ACCOUNT_A);

        assertThat(webhooks).singleElement()
            .satisfies(webhook -> assertThat(webhook.url()).isEqualTo("https://example.test/a"));

        verify(dataTableWebhookRepository, never())
            .findByDataTableIdAndEnvironment(AUTOMATION_TABLE_ID, Environment.DEVELOPMENT.ordinal());
    }

    @Test
    void testListingWebhooksResolvesTheTableWithinOnePool() {
        givenTheNameExistsInBothPools();

        when(dataTableWebhookRepository.findByDataTableIdAndEnvironment(
            VENDOR_TABLE_ID, Environment.DEVELOPMENT.ordinal()))
                .thenReturn(List.of(dataTableWebhook(9L, VENDOR_TABLE_ID, "https://example.test/vendor", null)));

        List<Webhook> webhooks = dataTableWebhookService.listWebhooks(VENDOR_TABLE);

        assertThat(webhooks).singleElement()
            .satisfies(webhook -> assertThat(webhook.dataTableId()).isEqualTo(VENDOR_TABLE_ID));

        assertThat(resolvedDataTableRef())
            .as("a shared EMBEDDED ref must not be resolved as its AUTOMATION namesake")
            .isEqualTo(VENDOR_TABLE);
    }

    @Test
    void testAnUnregisteredTableHasNoWebhooks() {
        when(dataTableService.fetchDataTable(EMBEDDED_TABLE_AS_ACCOUNT_A)).thenReturn(Optional.empty());

        assertThat(dataTableWebhookService.listWebhooks(EMBEDDED_TABLE_AS_ACCOUNT_A)).isEmpty();
    }

    /**
     * The ref the service actually resolved with. Captured rather than asserted with {@code never()} on the refs it
     * should not have used: the service makes exactly one lookup, so a {@code never()} on any other ref passes for
     * every implementation that compiles, while this fails the moment a lookup rebuilds the ref and loses the owner or
     * the pool.
     */
    private DataTableRef resolvedDataTableRef() {
        verify(dataTableService).fetchDataTable(dataTableRefCaptor.capture());

        return dataTableRefCaptor.getValue();
    }

    /**
     * Two registry rows claim the name {@code invoices}, one per pool. Only a lookup that carries the pool picks the
     * right one -- and both EMBEDDED refs, the vendor's and account A's, necessarily pick the same one, which is why
     * the registration owner and not the row is what tells those two apart.
     */
    private void givenTheNameExistsInBothPools() {
        lenient().when(dataTableService.fetchDataTable(AUTOMATION_TABLE))
            .thenReturn(Optional.of(dataTable(AUTOMATION_TABLE_ID)));
        lenient().when(dataTableService.fetchDataTable(VENDOR_TABLE))
            .thenReturn(Optional.of(dataTable(VENDOR_TABLE_ID)));
        lenient().when(dataTableService.fetchDataTable(EMBEDDED_TABLE_AS_ACCOUNT_A))
            .thenReturn(Optional.of(dataTable(VENDOR_TABLE_ID)));
    }

    private void givenTheRepositoryAssignsIds() {
        when(dataTableWebhookRepository.save(any(DataTableWebhook.class))).thenAnswer(invocation -> {
            DataTableWebhook dataTableWebhook = invocation.getArgument(0);

            dataTableWebhook.setId(9L);

            return dataTableWebhook;
        });
    }

    private static DataTable dataTable(long id) {
        DataTable dataTable = new DataTable();

        dataTable.setId(id);
        dataTable.setName("invoices");

        return dataTable;
    }

    private static DataTableWebhook dataTableWebhook(long id, long dataTableId, String url, @Nullable Owner owner) {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setId(id);
        dataTableWebhook.setDataTableId(dataTableId);
        dataTableWebhook.setUrl(url);
        dataTableWebhook.setType(DataTableWebhookType.RECORD_CREATED);
        dataTableWebhook.setEnvironment(Environment.DEVELOPMENT);
        dataTableWebhook.setOwner(owner);

        return dataTableWebhook;
    }
}
