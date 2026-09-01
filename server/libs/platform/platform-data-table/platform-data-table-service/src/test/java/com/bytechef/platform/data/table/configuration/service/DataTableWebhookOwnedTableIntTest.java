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

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService.Webhook;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A base name stopped identifying one table when accounts got tables of their own, and the webhook registry was still
 * keyed by base name -- so the vendor's registration on {@code orders} and account A's registration on its own
 * {@code orders} both bound to the vendor's row. Account A's rows were then POSTed to the vendor's URL and account A's
 * own registration never fired.
 *
 * <p>
 * Against a real schema because the claim is about which registry row a registration binds to when two rows
 * legitimately share a name; a mocked service can be made to agree with either answer.
 *
 * <p>
 * Which registry row is only half of the scoping. Two accounts falling back to one shared registry row are separated by
 * the registration owner instead, which is asserted end to end on the delivered POSTs in
 * {@code DataTableWebhookRowOwnerIntTest}.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableWebhookOwnedTableIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(5301L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(5302L);
    private static final long ENVIRONMENT_ID = 0;

    private static final String ACCOUNT_A_URL = "https://example.test/a";
    private static final String ACCOUNT_B_URL = "https://example.test/b";
    private static final String VENDOR_URL = "https://example.test/vendor";

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DataTableWebhookService dataTableWebhookService;

    /**
     * The regression, end to end. Two registrations on the same base name in the same pool and environment, one for the
     * vendor's table and one for account A's, must not see each other.
     */
    @Test
    void testAnAccountsRegistrationBindsToItsOwnTableAndNotTheVendors() {
        createTable("hooked", null);
        createTable("hooked", ACCOUNT_A);

        long vendorWebhookId = register("hooked", null, VENDOR_URL);
        long accountWebhookId = register("hooked", ACCOUNT_A, ACCOUNT_A_URL);

        assertThat(urlsOf(resolve("hooked", ACCOUNT_A)))
            .as("an event on account A's table must not reach the vendor's registered URL")
            .containsExactly(ACCOUNT_A_URL);
        assertThat(urlsOf(resolve("hooked", null)))
            .as("and the vendor's table must not pick up the account's registration either")
            .containsExactly(VENDOR_URL);

        assertThat(accountWebhookId).isNotEqualTo(vendorWebhookId);
    }

    /**
     * An account with no table of its own resolves the vendor's, and this test used to assert that its registration was
     * therefore the vendor's -- one table, one set of hooks. That was true only while a table's rows all belonged to
     * its owner. They do not: the vendor's table is shared, account B writes its own rows into it, and the vendor may
     * not read them. So the fallback shares the TABLE and not the registrations, and the run owner on the ref is what
     * separates them.
     */
    @Test
    void testAnAccountFallingBackToTheVendorsTableDoesNotShareItsRegistrations() {
        createTable("fallbackhooked", null);

        register("fallbackhooked", null, VENDOR_URL);

        long accountWebhookId = register("fallbackhooked", ACCOUNT_B, ACCOUNT_B_URL);

        assertThat(urlsOf(resolve("fallbackhooked", ACCOUNT_B)))
            .as("account B's rows are B's alone, so only B's registration is entitled to them")
            .containsExactly(ACCOUNT_B_URL);
        assertThat(urlsOf(resolve("fallbackhooked", null)))
            .as("while a row belonging to nobody is one every account may read, so both are entitled to it")
            .containsExactlyInAnyOrder(ACCOUNT_B_URL, VENDOR_URL);

        assertThat(accountWebhookId).isPositive();
    }

    /**
     * The pool stays in the lookup: the same base name registered in both pools yields two independent registrations.
     */
    @Test
    void testARegistrationNeverReachesTheOtherPoolsTableOfTheSameName() {
        dataTableService.createTable(
            "pooledhooked", null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.AUTOMATION, Optional.empty());
        createTable("pooledhooked", null);

        register("pooledhooked", null, VENDOR_URL);

        DataTableRef automationDataTableRef = dataTableService.fetchDataTableResolution(
            "pooledhooked", ENVIRONMENT_ID, PlatformType.AUTOMATION, Optional.empty())
            .orElseThrow()
            .dataTableRef();

        assertThat(urlsOf(automationDataTableRef))
            .as("an embedded registration must not fire for the automation table of the same name")
            .isEmpty();
    }

    @Test
    void testRemovingARegistrationLeavesTheOtherTablesRegistrationInPlace() {
        createTable("removable", null);
        createTable("removable", ACCOUNT_A);

        long vendorWebhookId = register("removable", null, VENDOR_URL);

        register("removable", ACCOUNT_A, ACCOUNT_A_URL);

        dataTableWebhookService.removeWebhook(vendorWebhookId);

        assertThat(urlsOf(resolve("removable", null))).isEmpty();
        assertThat(urlsOf(resolve("removable", ACCOUNT_A))).containsExactly(ACCOUNT_A_URL);
    }

    private void createTable(String baseName, Owner owner) {
        dataTableService.createTable(
            baseName, null, List.of(new ColumnSpec("title", ColumnType.STRING)), ENVIRONMENT_ID,
            PlatformType.EMBEDDED, Optional.ofNullable(owner));
    }

    private long register(String baseName, Owner owner, String url) {
        return dataTableWebhookService.addWebhook(
            resolve(baseName, owner), url, DataTableWebhookType.RECORD_CREATED);
    }

    private DataTableRef resolve(String baseName, Owner owner) {
        DataTableResolution dataTableResolution = dataTableService.fetchDataTableResolution(
            baseName, ENVIRONMENT_ID, PlatformType.EMBEDDED, Optional.ofNullable(owner))
            .orElseThrow();

        return dataTableResolution.dataTableRef();
    }

    private List<String> urlsOf(DataTableRef dataTableRef) {
        return dataTableWebhookService.listWebhooks(dataTableRef)
            .stream()
            .map(Webhook::url)
            .toList();
    }
}
