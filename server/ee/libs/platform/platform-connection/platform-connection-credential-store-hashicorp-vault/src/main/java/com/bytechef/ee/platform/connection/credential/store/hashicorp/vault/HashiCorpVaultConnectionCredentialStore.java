/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.connection.credential.store.hashicorp.vault;

import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionCredentialStore;
import com.bytechef.platform.connection.service.ConnectionCredentialStoreType;
import com.bytechef.platform.connection.util.CredentialPathResolver;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

/**
 * HashiCorp Vault-backed {@link ConnectionCredentialStore}. Reads/writes secrets in KV v2 at a path derived from the
 * operator-configured template (default {@code "bytechef/connections/{ref}"}).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class HashiCorpVaultConnectionCredentialStore implements ConnectionCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(HashiCorpVaultConnectionCredentialStore.class);

    private static final String DEFAULT_PATH_TEMPLATE = "bytechef/connections/{ref}";

    private final Cache<String, Map<String, Object>> cache;
    private final String kvMount;
    private final String pathTemplate;
    private final boolean readOnly;
    private final VaultTemplate vaultTemplate;

    @SuppressFBWarnings("EI2")
    public HashiCorpVaultConnectionCredentialStore(
        ApplicationProperties applicationProperties, VaultTemplate vaultTemplate) {

        ApplicationProperties.Connection.CredentialStore credentialStore = applicationProperties.getConnection()
            .getCredentialStore();
        ApplicationProperties.Connection.CredentialStore.HashiCorpVault vaultConfig = credentialStore
            .getHashicorpVault();

        String configuredTemplate = credentialStore.getPathTemplate();

        this.pathTemplate = configuredTemplate != null ? configuredTemplate : DEFAULT_PATH_TEMPLATE;
        this.kvMount = vaultConfig.getKvMount();
        this.readOnly = vaultConfig.isReadOnly();
        this.vaultTemplate = vaultTemplate;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(credentialStore.getCache()
                .getTtl())
            .build();
    }

    @Override
    public ConnectionCredentialStoreType getType() {
        return ConnectionCredentialStoreType.HASHICORP_VAULT;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public Map<String, ?> getParameters(Connection connection) {
        String ref = connection.getCredentialRef();

        if (ref == null) {
            return Map.of();
        }

        String path = resolvePath(ref);

        return cache.get(path, this::fetchSecret);
    }

    @Override
    public void storeParameters(Connection connection, Map<String, ?> parameters) {
        if (readOnly) {
            throw new UnsupportedOperationException("HashiCorp Vault store is configured read-only");
        }

        String ref = connection.getCredentialRef();

        if (ref == null) {
            ref = UUID.randomUUID()
                .toString();

            connection.setCredentialRef(ref);
        }

        String path = resolvePath(ref);

        kvOps().put(path, parameters);

        cache.invalidate(path);

        connection.setParameters(Map.of());
    }

    @Override
    public void deleteParameters(Connection connection) {
        if (readOnly) {
            throw new UnsupportedOperationException("HashiCorp Vault store is configured read-only");
        }

        String ref = connection.getCredentialRef();

        if (ref == null) {
            return;
        }

        String path = resolvePath(ref);

        kvOps().delete(path);

        cache.invalidate(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSecret(String path) {
        try {
            VaultResponse response = kvOps().get(path);

            if (response == null || response.getData() == null) {
                log.warn("Secret not found in HashiCorp Vault: {}", path);

                return Map.of();
            }

            return (Map<String, Object>) response.getData();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fetch secret from HashiCorp Vault: " + path, exception);
        }
    }

    private VaultKeyValueOperations kvOps() {
        return vaultTemplate.opsForKeyValue(kvMount, VaultKeyValueOperationsSupport.KeyValueBackend.KV_2);
    }

    private String resolvePath(String ref) {
        return CredentialPathResolver.resolve(pathTemplate, TenantContext.getCurrentTenantId(), null, ref);
    }
}
