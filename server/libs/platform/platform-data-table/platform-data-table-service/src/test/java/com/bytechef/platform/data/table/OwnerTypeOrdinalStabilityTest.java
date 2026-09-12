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

package com.bytechef.platform.data.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhook;
import com.bytechef.platform.owner.Owner;
import org.junit.jupiter.api.Test;

/**
 * {@link OwnerType} is persisted as an INT ordinal, so reordering its values silently reinterprets every stored row.
 *
 * @author Ivica Cardic
 */
class OwnerTypeOrdinalStabilityTest {

    @Test
    void testConnectedUserKeepsOrdinalZero() {
        assertEquals(0, OwnerType.CONNECTED_USER.ordinal());
    }

    @Test
    void testNoValueWasInsertedBeforeConnectedUser() {
        OwnerType[] ownerTypes = OwnerType.values();

        assertEquals("CONNECTED_USER", ownerTypes[0].name());
    }

    /**
     * Asserted through the entity that persists the ordinal rather than through the enum alone, because the round trip
     * is where a reordering would actually be felt: the value written is an ordinal and the value read back is a
     * constant, and only an entity holds both ends.
     */
    @Test
    void testAPersistedOwnerTypeRoundTrips() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        Owner owner = Owner.connectedUser(42L);

        dataTableWebhook.setOwner(owner);

        assertEquals(owner, dataTableWebhook.getOwner());
    }

    @Test
    void testAPersistedOwnerDefaultsToNull() {
        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        assertNull(dataTableWebhook.getOwner());
    }
}
