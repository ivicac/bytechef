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

package com.bytechef.platform.data.table.execution.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService.Webhook;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.execution.event.DataTableWebhookEvent;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

/**
 * A webhook is a push channel, so the row it carries leaves the tenant whether or not anyone asked for it. What is
 * pinned here is the listener's half of the scoping: it looks registrations up by the event's ref and by nothing else,
 * so an event on account 42's table cannot consult the vendor's table's registrations.
 *
 * <p>
 * The other half, which registrations on ONE shared table an event's run owner is entitled to, is decided by
 * {@code DataTableWebhookService.listWebhooks(DataTableRef)} and pinned end to end in
 * {@link DataTableWebhookRowOwnerIntTest}. It is deliberately not duplicated here: the listener holding an owner rule
 * of its own is the thing that must not exist, and a test asserting one would invite it back.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class DataTableWebhookEventListenerTest {

    private static final long ACCOUNT_A_ID = 4201L;
    private static final long ENVIRONMENT_ID = 0;
    private static final Owner ACCOUNT_A_OWNER = Owner.connectedUser(ACCOUNT_A_ID);

    private static final DataTableRef ACCOUNT_A_TABLE = new DataTableRef(
        "invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, ACCOUNT_A_OWNER, ACCOUNT_A_OWNER);
    private static final DataTableRef VENDOR_TABLE =
        DataTableRef.shared("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED);

    private static final long VENDOR_DATA_TABLE_ID = 10L;

    private static final String ACCOUNT_A_URL = "https://example.test/a";
    private static final String VENDOR_URL = "https://example.test/vendor";

    @Mock
    private DataTableWebhookService dataTableWebhookService;

    @Mock
    private RestTemplate restTemplate;

    @Captor
    private ArgumentCaptor<DataTableRef> dataTableRefCaptor;

    @Captor
    private ArgumentCaptor<String> urlCaptor;

    /**
     * The regression this keying exists for. The vendor registers on the shared {@code invoices}; account A owns its
     * own. A row written into account A's table must reach account A's registration, and the vendor's URL must never
     * see it.
     */
    @Test
    void testARowInAnAccountsOwnTableReachesOnlyThatTablesWebhook() {
        when(dataTableWebhookService.listWebhooks(ACCOUNT_A_TABLE))
            .thenReturn(List.of(webhook(1L, 20L, ACCOUNT_A_URL)));

        listener().onDataTableWebhookEvent(event(ACCOUNT_A_TABLE));

        assertThat(postedUrls())
            .as("the vendor's registration hangs off a different table and must not receive an account's row values")
            .containsExactly(ACCOUNT_A_URL);

        // A captor over every lookup, not never() on the vendor's ref. never() only forbids one ref the listener was
        // never going to name; this states the whole set, so an implementation that consulted the shared table as
        // well -- the actual way an account's row would reach the vendor's URL -- fails on the extra invocation
        // rather than passing because the extra ref happened not to be the one spelled out.
        verify(dataTableWebhookService, atLeastOnce()).listWebhooks(dataTableRefCaptor.capture());

        assertThat(dataTableRefCaptor.getAllValues())
            .as("the account's table is the only registration source a row written into it may consult")
            .containsExactly(ACCOUNT_A_TABLE);
    }

    @Test
    void testARowInTheVendorsSharedTableReachesTheVendorsWebhook() {
        when(dataTableWebhookService.listWebhooks(VENDOR_TABLE))
            .thenReturn(List.of(webhook(3L, VENDOR_DATA_TABLE_ID, VENDOR_URL)));

        listener().onDataTableWebhookEvent(event(VENDOR_TABLE));

        assertThat(postedUrls()).containsExactly(VENDOR_URL);
    }

    @Test
    void testAWebhookOfAnotherEventTypeIsNeverCalled() {
        when(dataTableWebhookService.listWebhooks(ACCOUNT_A_TABLE))
            .thenReturn(List.of(webhook(1L, 20L, ACCOUNT_A_URL, DataTableWebhookType.RECORD_DELETED)));

        listener().onDataTableWebhookEvent(event(ACCOUNT_A_TABLE));

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }

    /**
     * Asserted on the ref the listener actually looked up rather than with {@code never()} on the one it should not
     * have: the listener makes exactly one lookup, so a {@code never()} on any other ref passes for every
     * implementation that compiles. This fails if the lookup ever rebuilds the ref and lands in the wrong pool.
     */
    @Test
    void testTheListenerResolvesWebhooksInTheEventsPoolOnly() {
        when(dataTableWebhookService.listWebhooks(VENDOR_TABLE)).thenReturn(List.of());

        listener().onDataTableWebhookEvent(event(VENDOR_TABLE));

        verify(dataTableWebhookService).listWebhooks(dataTableRefCaptor.capture());

        assertThat(dataTableRefCaptor.getValue())
            .as("an EMBEDDED event must not be resolved against the AUTOMATION table of the same name")
            .isEqualTo(VENDOR_TABLE);
    }

    private DataTableWebhookEventListener listener() {
        return new DataTableWebhookEventListener(dataTableWebhookService, restTemplate);
    }

    private List<String> postedUrls() {
        verify(restTemplate, atLeastOnce()).postForObject(urlCaptor.capture(), any(), eq(String.class));

        return urlCaptor.getAllValues();
    }

    private static DataTableWebhookEvent event(DataTableRef dataTableRef) {
        return new DataTableWebhookEvent(dataTableRef, DataTableWebhookType.RECORD_CREATED, Map.of("id", 1L));
    }

    private static Webhook webhook(long id, long dataTableId, String url) {
        return webhook(id, dataTableId, url, DataTableWebhookType.RECORD_CREATED);
    }

    private static Webhook webhook(long id, long dataTableId, String url, DataTableWebhookType type) {
        // Whatever the service returns is already scoped to the event's run owner, so the fixture carries the owner
        // that scoping would have left: account A's registrations for account A's table, the vendor's for the shared
        // one.
        Owner owner = dataTableId == VENDOR_DATA_TABLE_ID ? null : ACCOUNT_A_OWNER;

        return new Webhook(id, dataTableId, url, type, ENVIRONMENT_ID, owner);
    }
}
