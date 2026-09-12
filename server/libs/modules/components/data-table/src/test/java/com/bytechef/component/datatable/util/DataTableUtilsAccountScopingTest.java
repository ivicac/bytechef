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

package com.bytechef.component.datatable.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.owner.Owner;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The account selector is how a vendor run reaches one account's table; {@code effectiveOwner} is the one place that
 * decides whether a named account is honored or ignored.
 *
 * @author Ivica Cardic
 */
class DataTableUtilsAccountScopingTest {

    @Test
    void testAnOwnedRunCannotNameAnotherAccount() {
        Optional<Owner> alice = Optional.of(Owner.connectedUser(1055L));

        assertThat(DataTableUtils.effectiveOwner(alice, 9999L))
            .as("a run that already belongs to an account must ignore a named account")
            .isEqualTo(alice);
    }

    @Test
    void testAVendorRunActsForTheAccountItNames() {
        assertThat(DataTableUtils.effectiveOwner(Optional.empty(), 9999L))
            .contains(Owner.connectedUser(9999L));
    }

    @Test
    void testAVendorRunThatNamesNoAccountStaysUnowned() {
        assertThat(DataTableUtils.effectiveOwner(Optional.empty(), null)).isEmpty();
    }
}
