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

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class PhysicalTableNamingTest {

    @Test
    void testASharedTableKeepsTheUnownedName() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.EMBEDDED, 0L, null, "orders"))
            .isEqualTo("edt_0_orders");
    }

    @Test
    void testAnOwnedTableCarriesItsOwnerInTheName() {
        assertThat(
            PhysicalTableNaming.buildPhysicalName(
                PlatformType.EMBEDDED, 0L, Owner.connectedUser(5L), "orders"))
                    .isEqualTo("edt_0_5_connecteduser_orders");
    }

    @Test
    void testAutomationKeepsItsOwnPoolToken() {
        assertThat(PhysicalTableNaming.buildPhysicalName(PlatformType.AUTOMATION, 1L, null, "orders"))
            .isEqualTo("dt_1_orders");
    }

    /**
     * The reason the type is in the name at all. An owner is the pair, and two owners of different types sharing an id
     * must not address one physical table -- they hold two registry rows, because {@code owner_type} is in the unique
     * index beside {@code owner_id}.
     *
     * <p>
     * Asserted per type rather than by comparing two, since {@code CONNECTED_USER} is still the only value and a
     * distinctness check over one element proves nothing. Every type's token appearing in its own name IS what makes
     * two types distinct once there are two.
     */
    @Test
    void testTheTypeAndNotOnlyTheIdReachesTheName() {
        Set<String> physicalNames = new HashSet<>();

        for (OwnerType ownerType : OwnerType.values()) {
            String physicalName = PhysicalTableNaming.buildPhysicalName(
                PlatformType.EMBEDDED, 0L, new Owner(ownerType, 5L), "orders");

            assertThat(physicalName)
                .as("an owned physical name must spell its owner's type, not only its id")
                .contains("_" + PhysicalTableNaming.ownerTypeToken(ownerType) + "_");

            physicalNames.add(physicalName);
        }

        OwnerType[] ownerTypes = OwnerType.values();

        assertThat(physicalNames)
            .as("two owner types sharing an id must not spell the same physical table")
            .hasSize(ownerTypes.length);
    }

    /**
     * The token is the constant name with its underscores removed, so the run between the owner id and the base name is
     * one field with no internal delimiter. Two constants that collapsed to the same token would make a physical name
     * ambiguous, and no parse could recover which one it meant.
     */
    @Test
    void testEveryOwnerTypeHasItsOwnToken() {
        Set<String> tokens = new HashSet<>();

        for (OwnerType ownerType : OwnerType.values()) {
            String token = PhysicalTableNaming.ownerTypeToken(ownerType);

            assertThat(token)
                .as("owner type token must carry no underscore")
                .matches("[a-z][a-z0-9]*");

            tokens.add(token);
        }

        OwnerType[] ownerTypes = OwnerType.values();

        assertThat(tokens).hasSize(ownerTypes.length);
    }

    @Test
    void testATokenRoundTripsBackToItsOwnerType() {
        for (OwnerType ownerType : OwnerType.values()) {
            String token = PhysicalTableNaming.ownerTypeToken(ownerType);

            assertThat(PhysicalTableNaming.ownerType(token)).contains(ownerType);
        }
    }

    /**
     * A token no type answers to is empty rather than a guess. A physical table left behind by a build that knew a type
     * this one does not must be skipped, not attributed to whichever type happens to be first.
     */
    @Test
    void testAnUnknownTokenResolvesToNoOwnerType() {
        Optional<OwnerType> ownerType = PhysicalTableNaming.ownerType("nosuchtype");

        assertThat(ownerType).isEmpty();
    }
}
