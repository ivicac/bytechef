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

package com.bytechef.platform.data.table.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.constant.PlatformType;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PhysicalTableNamingTest {

    @Test
    void testEmbeddedCarriesItsOwnPoolToken() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 0L, "orders"))
            .isEqualTo("edt_0_orders");
    }

    @Test
    void testAutomationKeepsItsOwnPoolToken() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.AUTOMATION, 1L, "orders"))
            .isEqualTo("dt_1_orders");
    }

    /**
     * The prefix is what {@code listTables} strips to recover a base name, so it has to be exactly the part of the name
     * that precedes one -- a prefix short by a character would leave a stray delimiter on every base name it recovers.
     */
    @Test
    void testThePrefixIsEverythingBeforeTheBaseName() {
        String prefix = PhysicalTableNaming.prefix(PlatformType.EMBEDDED, 2L);

        assertThat(prefix).isEqualTo("edt_2_");
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 2L, "orders"))
            .isEqualTo(prefix + "orders");
    }

    @Test
    void testAMixedCaseBaseNameIsLowercased() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 0L, "MyOrders"))
            .isEqualTo("edt_0_myorders");
    }
}
