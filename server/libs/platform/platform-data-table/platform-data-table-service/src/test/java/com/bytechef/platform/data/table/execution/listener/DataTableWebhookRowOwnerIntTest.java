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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.data.table.execution.service.DataTableRowService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * A webhook is a push channel, so what it carries leaves the tenant whether or not the recipient asked for it. These
 * tests are therefore asserted on the URLs actually POSTed, not on what a lookup returned: a filtered list is a claim
 * about a query, and the defect is in what crosses the network.
 *
 * <p>
 * The defect. Keying registrations on the table alone was correct while a table had exactly one owner. Row-level
 * ownership made a shared table hold the rows of every account in the pool, so on one, every registration fired for
 * every row written into it, and account A's row values were POSTed to account B's registered URL.
 *
 * <p>
 * Everything real except the transport: a real Postgres schema, real resolution, a real row insert publishing a real
 * Spring event, and the production listener wired to a {@link RestTemplate} the test can read. Resolution in particular
 * has to be real, because on a shared table every account's ref addresses the identical physical table and the only
 * thing left separating them is the owner resolution puts on the ref.
 *
 * <p>
 * Fixture tables are named {@code wro_*} because the integration tests in this module share one schema with no
 * per-class isolation.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = {
        DataTableIntTestConfiguration.class, DataTableWebhookRowOwnerIntTest.WebhookDeliveryConfiguration.class
    })
@Import(PostgreSQLContainerConfiguration.class)
class DataTableWebhookRowOwnerIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(8801L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(8802L);
    private static final long ENVIRONMENT_ID = 0;

    private static final String ACCOUNT_A_URL = "https://example.test/a";
    private static final String ACCOUNT_B_URL = "https://example.test/b";
    private static final String VENDOR_URL = "https://example.test/vendor";

    @Autowired
    private DataTableRowService dataTableRowService;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableWebhookService dataTableWebhookService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RestTemplate restTemplate;

    private final ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

    @BeforeEach
    void beforeEach() {
        reset(restTemplate);
    }

    /**
     * The leak, stated where it happens. Two accounts hold registrations on one shared table; account A writes a row;
     * account B's URL must see nothing.
     */
    @Test
    void testAnAccountsInsertIntoASharedTableDoesNotReachAnotherAccountsWebhook() {
        createSharedTable("wroleak");

        register("wroleak", ACCOUNT_A, ACCOUNT_A_URL);
        register("wroleak", ACCOUNT_B, ACCOUNT_B_URL);

        insertRow("wroleak", ACCOUNT_A);

        assertThat(postedUrls())
            .as("account A's row values must not be POSTed to account B's registered URL")
            .containsExactly(ACCOUNT_A_URL);
    }

    /**
     * The resource-level case, which the row axis must not regress. Account A owns its own physical table; a row
     * written into it reaches account A's registration on it.
     */
    @Test
    void testAWebhookOnAnAccountsOwnTableStillFiresForThatTablesRows() {
        createSharedTable("wroowned");
        createOwnedTable("wroowned", ACCOUNT_A);

        register("wroowned", null, VENDOR_URL);
        register("wroowned", ACCOUNT_A, ACCOUNT_A_URL);

        insertRow("wroowned", ACCOUNT_A);

        assertThat(postedUrls())
            .as("an owned table's own registration must still fire, and the vendor's must not")
            .containsExactly(ACCOUNT_A_URL);
    }

    /**
     * The rule chosen for the vendor's unowned rows, pinned. Delivery follows the row READ predicate: a registration
     * fires for the rows its owner may read, which on a shared table is its own plus the vendor's unowned ones.
     *
     * <p>
     * The alternative rule, "its own rows and only those", would make this assertion contain the vendor's URL alone. It
     * was rejected because a trigger is the push form of a read: an account polling this table sees the vendor's rows,
     * and a trigger that skipped them would make push and pull disagree about what the table contains. Nothing is
     * disclosed by the choice, an unowned row being readable by every account in the pool by construction.
     */
    @Test
    void testTheVendorsUnownedRowReachesEveryAccountsWebhookOnTheSharedTable() {
        createSharedTable("wrovendorrow");

        register("wrovendorrow", ACCOUNT_A, ACCOUNT_A_URL);
        register("wrovendorrow", ACCOUNT_B, ACCOUNT_B_URL);
        register("wrovendorrow", null, VENDOR_URL);

        insertRow("wrovendorrow", null);

        assertThat(postedUrls())
            .as("a row belonging to nobody is one every account may read, so every registration receives it")
            .containsExactlyInAnyOrder(ACCOUNT_A_URL, ACCOUNT_B_URL, VENDOR_URL);
    }

    /**
     * The converse, and the direction a leak could actually live in: the vendor's registration sees unowned rows only
     * and never falls through to an account's.
     */
    @Test
    void testTheVendorsWebhookNeverReceivesAnAccountsRow() {
        createSharedTable("wrovendorhook");

        register("wrovendorhook", null, VENDOR_URL);

        insertRow("wrovendorhook", ACCOUNT_A);

        assertThat(postedUrls())
            .as("the vendor may not read an account's row, so its registration must not be sent one")
            .isEmpty();
    }

    /**
     * Both owner columns move together on registration. An {@code owner_id} beside a null {@code owner_type} matches no
     * predicate and belongs to nobody, so the pair is asserted in the database rather than through the accessor that
     * would hide a half-written one behind an exception.
     */
    @Test
    void testRegistrationStampsBothOwnerColumnsOrNeither() {
        createSharedTable("wrocolumns");

        long accountWebhookId = register("wrocolumns", ACCOUNT_A, ACCOUNT_A_URL);
        long vendorWebhookId = register("wrocolumns", null, VENDOR_URL);

        assertThat(ownerColumnsOf(accountWebhookId))
            .as("an account's registration carries its id and the matching owner type")
            .containsExactly(ACCOUNT_A.id(), (long) OwnerType.CONNECTED_USER.ordinal());
        assertThat(ownerColumnsOf(vendorWebhookId))
            .as("the vendor's registration carries neither column")
            .containsExactly(null, null);
    }

    private void createSharedTable(String baseName) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.empty());
    }

    private void createOwnedTable(String baseName, Owner owner) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID, PlatformType.EMBEDDED,
            Optional.of(owner));
    }

    private void insertRow(String baseName, @Nullable Owner owner) {
        dataTableRowService.insertRow(resolve(baseName, owner), Map.of("title", "row of " + owner));
    }

    private List<@Nullable Long> ownerColumnsOf(long webhookId) {
        return jdbcTemplate.queryForObject(
            "SELECT owner_id, owner_type FROM data_table_webhook WHERE id = ?",
            (resultSet, rowNum) -> {
                Long ownerId = resultSet.getObject("owner_id", Long.class);
                Integer ownerType = resultSet.getObject("owner_type", Integer.class);

                return Arrays.asList(ownerId, ownerType == null ? null : ownerType.longValue());
            },
            webhookId);
    }

    private List<String> postedUrls() {
        // atLeast(0) so that "nothing was posted" is expressible: atLeastOnce would fail the vendor case on the
        // verification rather than on the assertion, reporting a missing invocation instead of the claim under test.
        verify(restTemplate, atLeast(0)).postForObject(urlCaptor.capture(), any(), eq(String.class));

        return urlCaptor.getAllValues();
    }

    private long register(String baseName, @Nullable Owner owner, String url) {
        return dataTableWebhookService.addWebhook(
            resolve(baseName, owner), url, DataTableWebhookType.RECORD_CREATED);
    }

    private DataTableRef resolve(String baseName, @Nullable Owner owner) {
        DataTableResolution dataTableResolution = dataTableService.fetchDataTableResolution(
            baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.ofNullable(owner))
            .orElseThrow();

        return dataTableResolution.dataTableRef();
    }

    /**
     * The production listener, reached by the real Spring event, wired to a transport the test can read. Declared here
     * rather than scanned because {@link DataTableIntTestConfiguration} excludes it precisely so that no integration
     * test POSTs over the real network.
     */
    @Configuration
    static class WebhookDeliveryConfiguration {

        @Bean
        RestTemplate dataTableWebhookRestTemplate() {
            return mock(RestTemplate.class);
        }

        @Bean
        DataTableWebhookEventListener dataTableWebhookEventListener(
            DataTableWebhookService dataTableWebhookService, RestTemplate restTemplate) {

            return new DataTableWebhookEventListener(dataTableWebhookService, restTemplate);
        }
    }
}
