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

package com.bytechef.platform.data.table.configuration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.owner.Owner;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * A webhook's owner is stored as two columns, and the delivery predicate reads the pair as one value.
 *
 * <p>
 * {@code setOwner} only ever writes both or neither, so a half-written pair reaches the entity solely through
 * persistence -- a partial migration, or a hand-edited row. That is precisely when it matters: returning null for it
 * would read as a vendor registration, so an account's webhook would silently stop receiving its own rows and start
 * receiving every unowned one. The pair is therefore rejected rather than interpreted, and these tests reproduce the
 * broken state the only way it can occur.
 *
 * @author Ivica Cardic
 */
class DataTableWebhookOwnerTest {

    private static final long ACCOUNT_ID = 42L;

    @Test
    void testNeitherColumnSetIsAVendorRegistration() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        assertThat(dataTableWebhook.getOwner()).isNull();
    }

    @Test
    void testBothColumnsSetReturnTheOwner() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setOwner(Owner.connectedUser(ACCOUNT_ID));

        assertThat(dataTableWebhook.getOwner()).isEqualTo(Owner.connectedUser(ACCOUNT_ID));
    }

    @Test
    void testClearingTheOwnerClearsBothColumns() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setOwner(Owner.connectedUser(ACCOUNT_ID));
        dataTableWebhook.setOwner(null);

        assertThat(readField(dataTableWebhook, "ownerId"))
            .as("an owner_id left behind by a cleared owner would belong to nobody and match no predicate")
            .isNull();
        assertThat(readField(dataTableWebhook, "ownerType")).isNull();
    }

    @Test
    void testAnOwnerIdWithNoTypeIsRejected() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setOwner(Owner.connectedUser(ACCOUNT_ID));

        setField(dataTableWebhook, "ownerType", null);

        assertThatThrownBy(dataTableWebhook::getOwner)
            .as("a half-written pair must not read as a vendor registration")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("incomplete owner");
    }

    @Test
    void testAnOwnerTypeWithNoIdIsRejected() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setOwner(Owner.connectedUser(ACCOUNT_ID));

        setField(dataTableWebhook, "ownerId", null);

        assertThatThrownBy(dataTableWebhook::getOwner)
            .as("a half-written pair must not read as a vendor registration")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("incomplete owner");
    }

    // Spring Data JDBC populates these columns directly, so reflection reproduces the persisted state that no setter
    // on this class can express, and reads back the column a setter deliberately does not expose.
    private static void setField(DataTableWebhook dataTableWebhook, String fieldName, Object value) {
        try {
            Field field = DataTableWebhook.class.getDeclaredField(fieldName);

            field.setAccessible(true);

            field.set(dataTableWebhook, value);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to set " + fieldName, exception);
        }
    }

    private static Object readField(DataTableWebhook dataTableWebhook, String fieldName) {
        try {
            Field field = DataTableWebhook.class.getDeclaredField(fieldName);

            field.setAccessible(true);

            return field.get(dataTableWebhook);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read " + fieldName, exception);
        }
    }
}
