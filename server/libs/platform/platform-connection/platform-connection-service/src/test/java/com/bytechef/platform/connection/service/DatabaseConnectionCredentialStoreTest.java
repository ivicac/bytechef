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

package com.bytechef.platform.connection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.connection.domain.Connection;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class DatabaseConnectionCredentialStoreTest {

    private final DatabaseConnectionCredentialStore store = new DatabaseConnectionCredentialStore();

    @Test
    void testGetTypeIsDatabase() {
        assertThat(store.getType()).isEqualTo(ConnectionCredentialStoreType.DATABASE);
    }

    @Test
    void testIsReadOnlyIsFalse() {
        assertThat(store.isReadOnly()).isFalse();
    }

    @Test
    void testStoreParametersMutatesEntity() {
        Connection connection = new Connection();
        Map<String, Object> params = Map.of("token", "abc123");

        store.storeParameters(connection, params);

        assertThat((Map<String, Object>) connection.getParameters()).containsEntry("token", "abc123");
    }

    @Test
    void testGetParametersReadsEntityField() {
        Connection connection = new Connection();
        Map<String, Object> params = Map.of("apiKey", "xyz");

        connection.setParameters(params);

        assertThat((Map<String, Object>) store.getParameters(connection)).containsEntry("apiKey", "xyz");
    }

    @Test
    void testDeleteParametersIsNoOp() {
        Connection connection = new Connection();
        Map<String, Object> params = Map.of("apiKey", "xyz");

        connection.setParameters(params);

        store.deleteParameters(connection);

        // No-op: parameters remain on the entity (row delete cascades them).
        assertThat((Map<String, Object>) connection.getParameters()).containsEntry("apiKey", "xyz");
    }
}
