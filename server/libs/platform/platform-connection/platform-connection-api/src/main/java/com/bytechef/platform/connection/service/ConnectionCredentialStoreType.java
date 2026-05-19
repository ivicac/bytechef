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

/**
 * Identifies the backend that holds the credential payload for a given
 * {@link com.bytechef.platform.connection.domain.Connection}.
 *
 * <p>
 * Persisted as INT ordinal on the {@code connection.credential_store_type} column. New values must always be appended
 * (never inserted) to preserve ordinal stability.
 *
 * @author Ivica Cardic
 */
public enum ConnectionCredentialStoreType {

    /** Default — credentials stored encrypted in the {@code connection.parameters} column. */
    DATABASE,

    /** AWS Secrets Manager. Implemented in a follow-up PR. */
    AWS_SECRETS_MANAGER,

    /** HashiCorp Vault (KV v2). Implemented in a follow-up PR. */
    HASHICORP_VAULT
}
