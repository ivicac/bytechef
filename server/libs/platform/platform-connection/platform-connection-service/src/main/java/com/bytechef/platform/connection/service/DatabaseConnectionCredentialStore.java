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

import com.bytechef.platform.connection.domain.Connection;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Default {@link ConnectionCredentialStore} backed by the {@code connection.parameters} column. Parameters are
 * encrypted on disk by the existing {@code EncryptedMapWrapper} converters; this store only mutates the in-memory
 * entity — actual persistence happens when the surrounding service calls {@code ConnectionRepository.save(connection)}.
 *
 * <p>
 * Always registered — no {@code @ConditionalOnProperty}. Connections with {@code credentialStoreType = DATABASE} (the
 * default for every existing and new row) dispatch here.
 *
 * @author Ivica Cardic
 */
@Component
public class DatabaseConnectionCredentialStore implements ConnectionCredentialStore {

    @Override
    public ConnectionCredentialStoreType getType() {
        return ConnectionCredentialStoreType.DATABASE;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Map<String, ?> getParameters(Connection connection) {
        return connection.getParameters();
    }

    @Override
    public void storeParameters(Connection connection, Map<String, ?> parameters) {
        connection.setParameters(parameters);
    }

    @Override
    public void deleteParameters(Connection connection) {
        // No-op: parameters are cleared when the connection row is deleted.
    }
}
