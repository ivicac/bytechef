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

package com.bytechef.platform.connection.web.graphql;

import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionCredentialStore;
import com.bytechef.platform.connection.service.ConnectionCredentialStoreType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ContextConfiguration;

/**
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = {
    ConnectionCredentialStoreGraphQlControllerIntTest.TestStoresConfiguration.class,
    ConnectionCredentialStoreGraphQlController.class
})
@GraphQlTest(
    controllers = ConnectionCredentialStoreGraphQlController.class,
    properties = {
        "bytechef.coordinator.enabled=true",
        "spring.graphql.schema.locations=classpath*:/graphql/"
    })
@Import(ConnectionCredentialStoreGraphQlControllerIntTest.TestStoresConfiguration.class)
class ConnectionCredentialStoreGraphQlControllerIntTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    void testConnectionCredentialStoresReturnsRegisteredStores() {
        graphQlTester.document("""
            query {
                connectionCredentialStores {
                    type
                    readOnly
                }
            }
            """)
            .execute()
            .path("connectionCredentialStores")
            .entityList(Object.class)
            .hasSize(2);
    }

    @Test
    void testDatabaseStoreReportsNotReadOnly() {
        graphQlTester.document("""
            query {
                connectionCredentialStores {
                    type
                    readOnly
                }
            }
            """)
            .execute()
            .path("connectionCredentialStores")
            .entityList(Map.class)
            .satisfies(stores -> {
                boolean found = stores.stream()
                    .anyMatch(
                        store -> "DATABASE".equals(store.get("type")) && Boolean.FALSE.equals(store.get("readOnly")));

                if (!found) {
                    throw new AssertionError(
                        "Expected a DATABASE store with readOnly=false but none was found in: " + stores);
                }
            });
    }

    @Test
    void testHashiCorpVaultStoreReportsReadOnly() {
        graphQlTester.document("""
            query {
                connectionCredentialStores {
                    type
                    readOnly
                }
            }
            """)
            .execute()
            .path("connectionCredentialStores")
            .entityList(Map.class)
            .satisfies(stores -> {
                boolean found = stores.stream()
                    .anyMatch(
                        store -> "HASHICORP_VAULT".equals(store.get("type"))
                            && Boolean.TRUE.equals(store.get("readOnly")));

                if (!found) {
                    throw new AssertionError(
                        "Expected a HASHICORP_VAULT store with readOnly=true but none was found in: " + stores);
                }
            });
    }

    @TestConfiguration
    static class TestStoresConfiguration {

        @Bean
        List<ConnectionCredentialStore> connectionCredentialStores() {
            return List.of(
                new StubStore(ConnectionCredentialStoreType.DATABASE, false),
                new StubStore(ConnectionCredentialStoreType.HASHICORP_VAULT, true));
        }
    }

    private record StubStore(ConnectionCredentialStoreType type, boolean readOnly)
        implements ConnectionCredentialStore {

        @Override
        public ConnectionCredentialStoreType getType() {
            return type;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public Map<String, ?> getParameters(Connection connection) {
            return Map.of();
        }

        @Override
        public void storeParameters(Connection connection, Map<String, ?> parameters) {
            // no-op for test
        }

        @Override
        public void deleteParameters(Connection connection) {
            // no-op for test
        }
    }
}
