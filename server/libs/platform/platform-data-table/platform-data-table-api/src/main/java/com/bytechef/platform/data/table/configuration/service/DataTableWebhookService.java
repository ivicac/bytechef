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

import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Service for managing webhooks associated with data tables. Webhooks enable external systems to receive notifications
 * about specific events occurring within a data table.
 *
 * <p>
 * A registration binds to one data table, so registration and delivery both key on the table -- never on the base name,
 * which does not identify one table: {@code orders} is legal in both pools at once, and the automation and embedded
 * tables of that name are two tables sharing a name.
 *
 * <p>
 * The table alone is not enough, though, and briefly this interface said it was. A table belongs to nobody and holds
 * the rows of every account in the pool, so keying on it fires every registration on it for every row written into it.
 * A registration therefore also carries the owner of the run that made it, and that owner is the only thing separating
 * two accounts' deliveries out of the one table they share: see {@link Webhook#receivesRowsWrittenBy}.
 *
 * @author Ivica Cardic
 */
public interface DataTableWebhookService {

    /**
     * Adds a new webhook for the table {@code dataTableRef} addresses, enabling notifications for external systems when
     * certain events occur on it.
     *
     * <p>
     * Takes the resolved table rather than a base name and a pool, so a registration lands on the same table the
     * registering run reads and writes. The pool and the environment come out of the ref for the same reason, and so
     * does the owner the registration is stamped with: the ref's run owner, which is the account the registering run
     * acts for. Nothing else may supply it -- a registration owner passed alongside the ref could disagree with it,
     * which is the whole class of bug the ref exists to make unconstructable.
     *
     * @param dataTableRef The table the webhook is registered against, as resolution settled it, carrying the owner the
     *                     registering run acts for.
     * @param url          The URL that will receive the webhook notifications.
     * @param type         The type of event for which the webhook should trigger (e.g., record_created, record_deleted,
     *                     record_updated).
     * @return The unique identifier of the newly created webhook.
     */
    long addWebhook(DataTableRef dataTableRef, String url, DataTableWebhookType type);

    /**
     * The registrations of the table {@code dataTableRef} addresses, in that ref's environment, that are entitled to a
     * row written by a run acting for that ref's run owner.
     *
     * <p>
     * Both scopes at once, and both are needed. Resolving the ref back to its registry row is what makes delivery meet
     * registration -- {@link #addWebhook} resolves the identical way, so an event on account 42's own table reaches
     * account 42's registrations and never the vendor's, even though both tables are named the same. The pool stays in
     * the lookup because it is part of the ref: a base name is legal in both pools after the split.
     *
     * <p>
     * On a shared table that leaves every account's registrations pointing at one registry row, so the ref's run owner
     * then decides between them through {@link Webhook#receivesRowsWrittenBy}. This is the scoping call: delivery has
     * no owner of its own to consult and must not grow one.
     *
     * @param dataTableRef The table whose webhooks should be listed, carrying the owner of the run whose row event is
     *                     being delivered.
     * @return The webhooks entitled to that run's rows, or an empty list when the ref addresses no registry row.
     */
    List<Webhook> listWebhooks(DataTableRef dataTableRef);

    /**
     * Every registration of one registry row in one environment, whoever owns it. The management listing -- the console
     * shows a table's registrations, and a console caller is already authorized against the table.
     *
     * <p>
     * Deliberately NOT the primitive the ref-keyed form is built on any more. It cannot be: it has no owner to scope
     * by, so routing delivery through it is exactly the fan-out being prevented.
     *
     * @param dataTableId   The registry id of the data table.
     * @param environmentId The environment ID for which to list webhooks.
     * @return A list of webhooks associated with the specified data table.
     */
    List<Webhook> listWebhooks(long dataTableId, long environmentId);

    /**
     * Removes an existing webhook identified by its unique identifier. This action stops notifications from being sent
     * to the associated URL.
     *
     * @param id The unique identifier of the webhook to be removed.
     */
    void removeWebhook(long id);

    /**
     * @param dataTableId the table this registration belongs to
     * @param owner       the account whose run registered it, or null for the vendor's own registration; on a shared
     *                    table this is the only thing separating one account's registrations from another's
     */
    record Webhook(
        long id, long dataTableId, String url, DataTableWebhookType type, long environmentId,
        @Nullable Owner owner) {

        /**
         * Whether a row written by a run acting for {@code rowOwner} belongs on this registration.
         *
         * <p>
         * The rule is the row READ predicate, deliberately: a registration fires for the rows its owner may read -- its
         * own, plus the unowned ones the vendor shares with every account -- and a vendor registration fires for
         * unowned rows only, never falling through to an account's.
         *
         * <p>
         * The alternative rule, "its own rows and only those", is defensible and differs from this one on exactly one
         * case: the vendor's shared rows. It is rejected because a trigger is the push form of a read. An account
         * polling the shared table with {@code findRecords} sees the vendor's seeded rows; a trigger that silently
         * skipped them would make push and pull disagree about what the table contains, and the account would have no
         * way to tell that a row it can plainly read never fired. Nothing is disclosed by the choice either -- an
         * unowned row is readable by every account in the pool by construction, so sending it discloses only what the
         * recipient could already fetch. The narrow direction is where a leak could live, and this rule is strictly
         * narrower than the table-only keying it replaces.
         *
         * @param rowOwner the owner the writing run acted for, from the event's ref; null for a row belonging to nobody
         */
        public boolean receivesRowsWrittenBy(@Nullable Owner rowOwner) {
            if (rowOwner == null) {
                return true;
            }

            return Objects.equals(owner, rowOwner);
        }
    }
}
