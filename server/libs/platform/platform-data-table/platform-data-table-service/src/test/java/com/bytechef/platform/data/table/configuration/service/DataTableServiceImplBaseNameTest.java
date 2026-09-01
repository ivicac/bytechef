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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the coupling {@code PhysicalTableNamingTest} relies on: the owned physical-name form
 * ({@code <pool>_<envId>_<ownerId>_<ownerTypeToken>_<baseName>}) can only be told apart from the shared form because a
 * base name can never start with a digit. If {@link DataTableServiceImpl#validateBaseName(String)} ever loosened that
 * rule, an owned name like {@code edt_0_5_connecteduser_orders} would become ambiguous with a shared table whose base
 * name is {@code "5_connecteduser_orders"}.
 *
 * <p>
 * Lives beside {@link DataTableServiceImpl} (same package) rather than next to {@code PhysicalTableNamingTest} because
 * {@code validateBaseName} is intentionally not public.
 *
 * @author Ivica Cardic
 */
class DataTableServiceImplBaseNameTest {

    @Test
    void testTheOwnedFormCannotBeSpelledAsASharedBaseName() {
        assertThatThrownBy(() -> DataTableServiceImpl.validateBaseName("5_orders"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
