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

/**
 * Strategy for persisting and resolving the credential payload of a {@link Connection}.
 *
 * <p>
 * {@link DatabaseConnectionCredentialStore} is the always-registered default. Operators may additionally register one
 * external store (AWS Secrets Manager / HashiCorp Vault) via configuration; each connection row carries a
 * {@link ConnectionCredentialStoreType} discriminator so the service can dispatch per-row.
 *
 * <p>
 * Read-only implementations (operator policy via {@code bytechef.connection.credential-store.<provider>.read-only})
 * throw {@link UnsupportedOperationException} from {@link #storeParameters} and {@link #deleteParameters}; callers gate
 * on {@link #isReadOnly()} first.
 *
 * @author Ivica Cardic
 */
public interface ConnectionCredentialStore {

    /** Identifies which connection rows this store handles. */
    ConnectionCredentialStoreType getType();

    /** Whether this store refuses writes in the current deployment. */
    boolean isReadOnly();

    /** Resolve the credential parameters for the given connection. */
    Map<String, ?> getParameters(Connection connection);

    /**
     * Persist the credential payload. Called BEFORE the connection row is saved, so the implementation may mutate the
     * entity (e.g., setting {@code credentialRef}, clearing {@code parameters}). Throws
     * {@link UnsupportedOperationException} on read-only stores.
     */
    void storeParameters(Connection connection, Map<String, ?> parameters);

    /**
     * Remove the credential payload. Called BEFORE the row is deleted. Throws {@link UnsupportedOperationException} on
     * read-only stores.
     */
    void deleteParameters(Connection connection);
}
