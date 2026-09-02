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

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A data table belongs to nobody, so nothing on this interface selects a table by owner.
 *
 * @author Ivica Cardic
 */
class DataTableServiceOwnerParameterTest {

    /**
     * Asserted reflectively rather than by reading the interface, so the parameter cannot creep back one signature at a
     * time. fetchDataTableResolution is the single exception: its owner is the run owner that binds into the row
     * predicate, not a table selector.
     */
    @Test
    void testOnlyResolutionAcceptsAnOwner() {
        List<String> offenders = Arrays.stream(DataTableService.class.getMethods())
            .filter(method -> !"fetchDataTableResolution".equals(method.getName()))
            .filter(method -> Arrays.stream(method.getGenericParameterTypes())
                .map(Type::getTypeName)
                .anyMatch(typeName -> typeName.contains("com.bytechef.platform.owner.Owner")))
            .map(Method::getName)
            .toList();

        assertThat(offenders)
            .as("only fetchDataTableResolution may take an owner; a DDL method taking one is a table selector")
            .isEmpty();
    }
}
