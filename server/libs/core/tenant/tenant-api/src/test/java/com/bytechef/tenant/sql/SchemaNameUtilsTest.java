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

package com.bytechef.tenant.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class SchemaNameUtilsTest {

    @Test
    void testQuoteWrapsTheSchemaNameInDoubleQuotes() {
        assertThat(SchemaNameUtils.quote("bytechef_000001")).isEqualTo("\"bytechef_000001\"");
    }

    @Test
    void testQuoteFoldsTheSchemaNameToLowerCase() {
        assertThat(SchemaNameUtils.quote("bytechef_Tenant1")).isEqualTo("\"bytechef_tenant1\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "a b", "a\"b", "a.b", "bad-id"
    })
    void testQuoteRejectsAnInvalidSchemaName(String schemaName) {
        assertThatThrownBy(() -> SchemaNameUtils.quote(schemaName))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
